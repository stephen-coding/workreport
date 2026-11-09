"""OpenAI-compatible chat provider (OpenAI / DeepSeek / Qwen / vLLM ...)."""

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
    to_openai_messages,
    to_openai_tools,
    tools_suppressed,
)
from agent_core.llm.streaming import OpenAIStreamAccumulator, parse_sse_line
from agent_core.llm.tool_text import TextToolCallFilter, recover_text_tool_calls
from agent_core.protocol import ChatConfig, ErrorCode, Message, RpcError, ToolDef

LOGGER = logging.getLogger(__name__)

_HTTP_TIMEOUT = httpx.Timeout(connect=10.0, read=180.0, write=30.0, pool=10.0)

_TOOLS_DISABLED_NOTICE = (
    "端点未启用函数调用（vLLM 需启动参数 --enable-auto-tool-choice --tool-call-parser <parser>），"
    "已改用文本工具调用兼容模式：模型把 <tool_call> 写进正文时仍会执行。"
    "服务端补上参数后会自动恢复标准模式。"
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
        wants_tools = bool(tools) and not tools_suppressed(*tools_key)
        if wants_tools:
            body["tools"] = to_openai_tools(tools)
            body["tool_choice"] = "auto"
        elif tools:
            self._warn_once(tools_key)

        # Hide tool-call markup the model may print into the text channel.
        # 模型把 <tool_call> 写进正文时，先屏蔽它再交给界面。
        guard = TextToolCallFilter()

        async def emit(piece: str) -> None:
            for visible in guard.feed(piece):
                await on_token(visible)

        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT, transport=self._transport) as client:
            try:
                accumulator = await self._stream(client, url, headers, body, emit)
                if wants_tools:
                    # The endpoint took the tool definitions again: end the probation.
                    # 端点又接受工具定义：结束假释，后续请求继续带工具。
                    accept_tools(*tools_key)
            except _ToolRejected as rejected:
                LOGGER.warning("endpoint %s rejects tool definitions (%d): %s",
                               url, rejected.status, rejected.body[:200])
                suppress_tools(*tools_key)
                self._warn_once(tools_key)
                body.pop("tools", None)
                body.pop("tool_choice", None)
                accumulator = await self._stream(client, url, headers, body, emit)

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

    def _warn_once(self, tools_key: Tuple[str, str]) -> None:
        # One notice per endpoint per Core process, even though a new provider is
        # built for every task.
        # 同一端点在一个 Core 进程内只提示一次（provider 是每个任务新建的）。
        if take_tools_notice(*tools_key):
            self.notice = _TOOLS_DISABLED_NOTICE
