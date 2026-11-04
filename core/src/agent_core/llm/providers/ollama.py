"""Ollama chat provider."""

import json
import logging
from typing import List, Optional

import httpx

from agent_core.llm.base import (
    ChatResult,
    LLMProvider,
    TokenCallback,
    ToolCallCallback,
    to_ollama_messages,
    to_ollama_tools,
)
from agent_core.llm.streaming import OllamaStreamAccumulator, parse_sse_line
from agent_core.protocol import ChatConfig, ErrorCode, Message, RpcError, ToolDef

LOGGER = logging.getLogger(__name__)

_HTTP_TIMEOUT = httpx.Timeout(connect=10.0, read=180.0, write=30.0, pool=10.0)


def resolve_chat_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/api/chat"):
        return base
    return base + "/api/chat"


class OllamaProvider(LLMProvider):
    provider_id = "ollama"

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
        if tools:
            body["tools"] = to_ollama_tools(tools)

        accumulator = OllamaStreamAccumulator()
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT) as client:
            async with client.stream("POST", url, json=body) as response:
                if response.status_code >= 400:
                    raw = (await response.aread()).decode("utf-8", "replace")
                    raise RpcError(
                        ErrorCode.LLM_ERROR,
                        "Ollama 请求失败(%d): %s" % (response.status_code, raw[:500]),
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

        calls = accumulator.finalize()
        for call in calls:
            await on_tool_call(call)
        return ChatResult(
            content=accumulator.content,
            tool_calls=calls,
            finish_reason="stop" if accumulator.done else None,
            usage=accumulator.usage,
        )
