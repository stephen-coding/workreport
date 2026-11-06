"""OpenAI-compatible chat provider (OpenAI / DeepSeek / Qwen / vLLM ...)."""

import json
import logging
from typing import List, Optional, Set, Tuple

import httpx

from agent_core.llm.base import (
    ChatResult,
    LLMProvider,
    TokenCallback,
    ToolCallCallback,
    is_tool_unsupported,
    to_openai_messages,
    to_openai_tools,
)
from agent_core.llm.streaming import OpenAIStreamAccumulator, parse_sse_line
from agent_core.protocol import ChatConfig, ErrorCode, Message, RpcError, ToolDef

LOGGER = logging.getLogger(__name__)

_HTTP_TIMEOUT = httpx.Timeout(connect=10.0, read=180.0, write=30.0, pool=10.0)

# (url, model) pairs already known to reject tool definitions, so later requests skip
# the wasted 400. 记录已知不支持 tools 的端点，后续请求直接不带工具定义。
_TOOLS_UNSUPPORTED: Set[Tuple[str, str]] = set()

_TOOLS_DISABLED_NOTICE = (
    "端点未启用函数调用（vLLM 需启动参数 --enable-auto-tool-choice --tool-call-parser <parser>），"
    "本次已按纯对话模式回答：可以对话，但读写文件、执行命令、检索代码等工具不可用。"
)


def resolve_chat_url(base_url: str) -> str:
    """Normalize a base URL (or full endpoint) to a chat-completions URL."""
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/chat/completions"):
        return base
    if base.endswith("/v1"):
        return base + "/chat/completions"
    return base + "/v1/chat/completions"


class _ToolRejected(Exception):
    """The endpoint refused the call because of the tool definitions."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__("%d: %s" % (status, body[:300]))
        self.status = status
        self.body = body


class OpenAICompatProvider(LLMProvider):
    provider_id = "openai_compat"

    # Set when tools had to be dropped; drained by ``consume_notice()``.
    notice: Optional[str] = None

    def __init__(self, transport: Optional[httpx.AsyncBaseTransport] = None) -> None:
        # Tests inject a mock transport; production keeps None (real network).
        self._transport = transport
        self._notified = False

    def consume_notice(self) -> Optional[str]:
        """One-shot human readable warning for the Shell (``chat/notice``)."""
        notice, self.notice = self.notice, None
        return notice

    async def chat_stream(
        self,
        messages: List[Message],
        tools: Optional[List[ToolDef]],
        config: ChatConfig,
        on_token: TokenCallback,
        on_tool_call: ToolCallCallback,
    ) -> ChatResult:
        url = resolve_chat_url(config.base_url)
        if not url:
            raise RpcError(ErrorCode.LLM_ERROR, "base_url 未配置")

        headers = {"Content-Type": "application/json"}
        if config.api_key:
            headers["Authorization"] = "Bearer " + config.api_key

        body = {
            "model": config.model,
            "messages": to_openai_messages(messages),
            "temperature": config.temperature,
            "max_tokens": config.max_tokens,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        tools_key = (url, config.model or "")
        wants_tools = bool(tools) and tools_key not in _TOOLS_UNSUPPORTED
        if wants_tools:
            body["tools"] = to_openai_tools(tools)
            body["tool_choice"] = "auto"
        elif tools:
            self._warn_once()

        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT, transport=self._transport) as client:
            try:
                accumulator = await self._stream(client, url, headers, body, on_token)
            except _ToolRejected as rejected:
                LOGGER.warning("endpoint %s rejects tool definitions (%d): %s",
                               url, rejected.status, rejected.body[:200])
                _TOOLS_UNSUPPORTED.add(tools_key)
                self._warn_once()
                body.pop("tools", None)
                body.pop("tool_choice", None)
                accumulator = await self._stream(client, url, headers, body, on_token)

        calls = accumulator.finalize()
        for call in calls:
            await on_tool_call(call)
        return ChatResult(
            content=accumulator.content,
            tool_calls=calls,
            finish_reason=accumulator.finish_reason,
            usage=accumulator.usage,
        )

    async def _stream(self, client: httpx.AsyncClient, url: str, headers: dict, body: dict,
                      on_token: TokenCallback) -> OpenAIStreamAccumulator:
        accumulator = OpenAIStreamAccumulator()
        async with client.stream("POST", url, headers=headers, json=body) as response:
            if response.status_code >= 400:
                raw = (await response.aread()).decode("utf-8", "replace")
                if is_tool_unsupported(response.status_code, raw):
                    raise _ToolRejected(response.status_code, raw)
                raise RpcError(
                    ErrorCode.LLM_ERROR,
                    "LLM 请求失败(%d) %s: %s" % (response.status_code, url, raw[:500]),
                )
            async for line in response.aiter_lines():
                payload = parse_sse_line(line)
                if payload is None:
                    continue
                if payload == "[DONE]":
                    break
                try:
                    chunk = json.loads(payload)
                except json.JSONDecodeError:
                    LOGGER.warning("drop invalid SSE chunk: %r", payload[:200])
                    continue
                for token in accumulator.feed(chunk):
                    await on_token(token)
        return accumulator

    def _warn_once(self) -> None:
        if not self._notified:
            self._notified = True
            self.notice = _TOOLS_DISABLED_NOTICE
