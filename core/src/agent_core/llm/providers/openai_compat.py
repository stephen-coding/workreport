"""OpenAI-compatible chat provider (OpenAI / DeepSeek / Qwen / vLLM ...)."""

import json
import logging
from typing import List, Optional

import httpx

from agent_core.llm.base import (
    ChatResult,
    LLMProvider,
    TokenCallback,
    ToolCallCallback,
    to_openai_messages,
    to_openai_tools,
)
from agent_core.llm.streaming import OpenAIStreamAccumulator, parse_sse_line
from agent_core.protocol import ChatConfig, ErrorCode, Message, RpcError, ToolDef

LOGGER = logging.getLogger(__name__)

_HTTP_TIMEOUT = httpx.Timeout(connect=10.0, read=180.0, write=30.0, pool=10.0)


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


class OpenAICompatProvider(LLMProvider):
    provider_id = "openai_compat"

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
        if tools:
            body["tools"] = to_openai_tools(tools)
            body["tool_choice"] = "auto"

        accumulator = OpenAIStreamAccumulator()
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT) as client:
            async with client.stream("POST", url, headers=headers, json=body) as response:
                if response.status_code >= 400:
                    raw = (await response.aread()).decode("utf-8", "replace")
                    raise RpcError(
                        ErrorCode.LLM_ERROR,
                        "LLM 请求失败(%d): %s" % (response.status_code, raw[:500]),
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

        calls = accumulator.finalize()
        for call in calls:
            await on_tool_call(call)
        return ChatResult(
            content=accumulator.content,
            tool_calls=calls,
            finish_reason=accumulator.finish_reason,
            usage=accumulator.usage,
        )
