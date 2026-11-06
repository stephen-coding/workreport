"""Ollama chat provider."""

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
    to_ollama_messages,
    to_ollama_tools,
)
from agent_core.llm.streaming import OllamaStreamAccumulator, parse_sse_line
from agent_core.protocol import ChatConfig, ErrorCode, Message, RpcError, ToolDef

LOGGER = logging.getLogger(__name__)

_HTTP_TIMEOUT = httpx.Timeout(connect=10.0, read=180.0, write=30.0, pool=10.0)

# (url, model) pairs whose model does not support tools ("does not support tools").
# 记录已知不支持 tools 的 Ollama 模型，后续请求直接不带工具定义。
_TOOLS_UNSUPPORTED: Set[Tuple[str, str]] = set()

_TOOLS_DISABLED_NOTICE = (
    "当前 Ollama 模型不支持工具调用（tools），本次已按纯对话模式回答："
    "可以对话，但读写文件、执行命令、检索代码等工具不可用。"
)


def resolve_chat_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/api/chat"):
        return base
    if base.endswith("/api/generate"):
        return base[: -len("/api/generate")] + "/api/chat"
    return base + "/api/chat"


class _ToolRejected(Exception):
    """The endpoint refused the call because of the tool definitions."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__("%d: %s" % (status, body[:300]))
        self.status = status
        self.body = body


class OllamaProvider(LLMProvider):
    provider_id = "ollama"

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

        options = {"temperature": config.temperature, "num_predict": config.max_tokens}
        body = {
            "model": config.model,
            "messages": to_ollama_messages(messages),
            "stream": True,
            "options": options,
        }
        tools_key = (url, config.model or "")
        if tools and tools_key not in _TOOLS_UNSUPPORTED:
            body["tools"] = to_ollama_tools(tools)
        elif tools:
            self._warn_once()

        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT, transport=self._transport) as client:
            try:
                accumulator = await self._stream(client, url, body, on_token)
            except _ToolRejected as rejected:
                LOGGER.warning("endpoint %s rejects tool definitions (%d): %s",
                               url, rejected.status, rejected.body[:200])
                _TOOLS_UNSUPPORTED.add(tools_key)
                self._warn_once()
                body.pop("tools", None)
                accumulator = await self._stream(client, url, body, on_token)

        calls = accumulator.finalize()
        for call in calls:
            await on_tool_call(call)
        return ChatResult(
            content=accumulator.content,
            tool_calls=calls,
            finish_reason="stop" if accumulator.done else None,
            usage=accumulator.usage,
        )

    async def _stream(self, client: httpx.AsyncClient, url: str, body: dict,
                      on_token: TokenCallback) -> OllamaStreamAccumulator:
        accumulator = OllamaStreamAccumulator()
        async with client.stream("POST", url, json=body) as response:
            if response.status_code >= 400:
                raw = (await response.aread()).decode("utf-8", "replace")
                if is_tool_unsupported(response.status_code, raw):
                    raise _ToolRejected(response.status_code, raw)
                raise RpcError(
                    ErrorCode.LLM_ERROR,
                    "Ollama 请求失败(%d) %s: %s" % (response.status_code, url, raw[:500]),
                )
            async for line in response.aiter_lines():
                payload = parse_sse_line(line)
                if payload is None:
                    payload = line.strip()
                if not payload:
                    continue
                try:
                    chunk = json.loads(payload)
                except json.JSONDecodeError:
                    LOGGER.warning("drop invalid NDJSON chunk: %r", payload[:200])
                    continue
                for token in accumulator.feed(chunk):
                    await on_token(token)
        return accumulator

    def _warn_once(self) -> None:
        if not self._notified:
            self._notified = True
            self.notice = _TOOLS_DISABLED_NOTICE
