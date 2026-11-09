"""Provider protocol shared by all LLM backends."""

import json
import time
from typing import Any, Awaitable, Callable, Dict, List, Optional, Protocol, Set, Tuple

from pydantic import BaseModel, Field

from agent_core.protocol import Message, Role, ToolCall, ToolDef

TokenCallback = Callable[[str], Awaitable[None]]
ToolCallCallback = Callable[[ToolCall], Awaitable[None]]

# Endpoints that refuse tool definitions (vLLM without --enable-auto-tool-choice,
# Ollama models without tool support ...) answer 400 with one of these phrases.
# 端点拒绝工具定义时（vLLM 未开 auto tool choice、模型不支持 tools 等）会在 400 里
# 带上这些关键字，据此把“请求失败”降级成“纯对话重试”。
_TOOL_UNSUPPORTED_HINTS = (
    "tool_choice",
    "tool-call-parser",
    "enable-auto-tool-choice",
    "does not support tools",
    "do not support tools",
    "tools are not supported",
    "function calling is not supported",
)


class ChatResult(BaseModel):
    content: str = ""
    tool_calls: List[ToolCall] = Field(default_factory=list)
    finish_reason: Optional[str] = None
    usage: Optional[Dict[str, Any]] = None


class LLMProvider(Protocol):
    provider_id: str

    async def chat_stream(
        self,
        messages: List[Message],
        tools: Optional[List[ToolDef]],
        config: Any,
        on_token: TokenCallback,
        on_tool_call: ToolCallCallback,
    ) -> ChatResult:
        ...


def is_tool_unsupported(status_code: int, body: str) -> bool:
    """True when the endpoint rejected the request because of its tool definitions."""
    if status_code not in (400, 422):
        return False
    lowered = (body or "").lower()
    return any(hint in lowered for hint in _TOOL_UNSUPPORTED_HINTS)


# --- endpoint probation -----------------------------------------------------
# A rejection is remembered only for a short window.  The Core process used to skip
# tool definitions for its whole lifetime after the first 400, so once the server was
# fixed (vLLM restarted with --enable-auto-tool-choice, model swapped in, ...) the
# plugin kept answering in plain-chat mode until the IDE was restarted.
# 拒绝只记一小段时间：Core 进程此前会“一票否决”，服务端补上参数后仍永远降级，
# 必须重启 IDE 才恢复。现在过期自动重试，服务端修好就能自动用回工具。
TOOLS_RETRY_SECONDS = 300.0

# (chat url, model) -> monotonic deadline while tools stay suppressed.
_tools_suppressed: Dict[Tuple[str, str], float] = {}
# (chat url, model) pairs that already produced a chat/notice in this process.
_notice_shown: Set[Tuple[str, str]] = set()


def tools_suppressed(url: str, model: str) -> bool:
    """True while this endpoint is on probation after rejecting tool definitions."""
    key = (url, model)
    until = _tools_suppressed.get(key)
    if until is None:
        return False
    if time.monotonic() >= until:
        _tools_suppressed.pop(key, None)
        return False
    return True


def suppress_tools(url: str, model: str) -> None:
    """Drop tool definitions for a while after the endpoint refused them."""
    _tools_suppressed[(url, model)] = time.monotonic() + TOOLS_RETRY_SECONDS


def accept_tools(url: str, model: str) -> None:
    """The endpoint took our tool definitions again: clear probation and the notice flag."""
    key = (url, model)
    _tools_suppressed.pop(key, None)
    _notice_shown.discard(key)


def take_tools_notice(url: str, model: str) -> bool:
    """True the first time this process degrades this endpoint to plain chat."""
    key = (url, model)
    if key in _notice_shown:
        return False
    _notice_shown.add(key)
    return True


def to_openai_messages(messages: List[Message]) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for m in messages:
        item: Dict[str, Any] = {"role": m.role.value}
        if m.role == Role.TOOL:
            item["content"] = m.content or ""
            item["tool_call_id"] = m.tool_call_id or ""
        else:
            item["content"] = m.content
        if m.tool_calls:
            item["tool_calls"] = [
                {
                    "id": tc.id,
                    "type": "function",
                    "function": {
                        "name": tc.name,
                        "arguments": json.dumps(tc.arguments, ensure_ascii=False),
                    },
                }
                for tc in m.tool_calls
            ]
        out.append(item)
    return out


def to_ollama_messages(messages: List[Message]) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for m in messages:
        item: Dict[str, Any] = {"role": m.role.value, "content": m.content or ""}
        if m.tool_calls:
            item["tool_calls"] = [
                {"function": {"name": tc.name, "arguments": tc.arguments}}
                for tc in m.tool_calls
            ]
        out.append(item)
    return out


def to_openai_tools(tools: List[ToolDef]) -> List[Dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": t.name,
                "description": t.description,
                "parameters": t.parameters or {"type": "object", "properties": {}},
            },
        }
        for t in tools
    ]


def to_ollama_tools(tools: List[ToolDef]) -> List[Dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": t.name,
                "description": t.description,
                "parameters": t.parameters or {"type": "object", "properties": {}},
            },
        }
        for t in tools
    ]
