"""Ollama chat provider."""

import json
import logging
from typing import List, Optional, Tuple

import httpx

from agent_core.llm.base import (
    ChatResult,
    LLMProvider,
    TokenCallback,
    ToolCallCallback,
    accept_tools,
    is_tool_unsupported,
    suppress_tools,
    take_tools_notice,
    to_ollama_messages,
    to_ollama_tools,
    tools_suppressed,
)
from agent_core.llm.streaming import OllamaStreamAccumulator, parse_sse_line
from agent_core.llm.tool_text import TextToolCallFilter, recover_text_tool_calls
from agent_core.llm.transport import HTTP_TIMEOUT, describe_transport_error
from agent_core.protocol import ChatConfig, ErrorCode, Message, RpcError, ToolDef

LOGGER = logging.getLogger(__name__)

_TOOLS_DISABLED_NOTICE = (
    "当前 Ollama 模型不支持工具调用（tools），已改用文本工具调用兼容模式："
    "模型把 <tool_call> 写进正文时仍会执行。换用支持 tools 的模型后会重新尝试。"
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
        wants_tools = bool(tools) and not tools_suppressed(*tools_key)
        if wants_tools:
            body["tools"] = to_ollama_tools(tools)
        elif tools:
            self._warn_once(tools_key)

        # Hide tool-call markup the model may print into the text channel.
        # 模型把 <tool_call> 写进正文时，先屏蔽它再交给界面。
        guard = TextToolCallFilter()

        async def emit(piece: str) -> None:
            for visible in guard.feed(piece):
                await on_token(visible)

        try:
            async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, transport=self._transport) as client:
                try:
                    accumulator = await self._stream(client, url, body, emit)
                    if wants_tools:
                        # The model accepted tool definitions again: end the probation.
                        # 模型又接受工具定义：结束假释。
                        accept_tools(*tools_key)
                except _ToolRejected as rejected:
                    LOGGER.warning("endpoint %s rejects tool definitions (%d): %s",
                                   url, rejected.status, rejected.body[:200])
                    suppress_tools(*tools_key)
                    self._warn_once(tools_key)
                    body.pop("tools", None)
                    accumulator = await self._stream(client, url, body, emit)
        except httpx.TransportError as exc:
            # httpcore drops the text on timeouts/resets; give the Shell a real message.
            # httpcore 的超时/断连异常没有文本，这里补上阶段、端点与处置建议。
            raise describe_transport_error(exc, url, config.model) from exc

        for visible in guard.flush():
            await on_token(visible)

        calls = accumulator.finalize()
        content = accumulator.content
        if not calls:
            content, calls = recover_text_tool_calls(content)
        for call in calls:
            await on_tool_call(call)
        return ChatResult(
            content=content,
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

    def _warn_once(self, tools_key: Tuple[str, str]) -> None:
        # One notice per endpoint per Core process, even though a new provider is
        # built for every task.
        # 同一模型在一个 Core 进程内只提示一次（provider 是每个任务新建的）。
        if take_tools_notice(*tools_key):
            self.notice = _TOOLS_DISABLED_NOTICE
