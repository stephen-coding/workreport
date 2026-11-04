"""SSE stream parsing and tool-call accumulation state machines.

Two provider families behave differently:

* OpenAI-compatible: ``choices[0].delta.tool_calls`` is an *incremental* array
  keyed by ``index``; ``function.arguments`` arrives as string fragments that
  must be concatenated across chunks.
* Ollama: ``message.tool_calls`` carries *whole* tool-call objects per chunk
  (arguments already a dict or a complete JSON string); duplicates may repeat
  across chunks and must be de-duplicated by (name, arguments).
"""

import json
import logging
from typing import Any, Dict, List, Optional

from agent_core.protocol import ToolCall

LOGGER = logging.getLogger(__name__)


class OpenAIStreamAccumulator:
    """Accumulates one OpenAI-compatible streaming response."""

    def __init__(self) -> None:
        self._content_parts: List[str] = []
        self._slots: Dict[int, Dict[str, str]] = {}
        self.finish_reason: Optional[str] = None
        self.usage: Optional[Dict[str, Any]] = None

    def feed(self, chunk: Dict[str, Any]) -> List[str]:
        """Consume one SSE JSON chunk; return newly arrived text tokens."""
        tokens: List[str] = []
        if chunk.get("usage"):
            self.usage = chunk["usage"]
        choices = chunk.get("choices") or []
        if not choices:
            return tokens
        choice = choices[0]
        delta = choice.get("delta") or {}
        piece = delta.get("content")
        if piece:
            self._content_parts.append(piece)
            tokens.append(piece)
        for tc in delta.get("tool_calls") or []:
            idx = int(tc.get("index", 0))
            slot = self._slots.setdefault(idx, {"id": "", "name": "", "args": ""})
            if tc.get("id"):
                slot["id"] = tc["id"]
            fn = tc.get("function") or {}
            if fn.get("name"):
                slot["name"] = fn["name"]
            if fn.get("arguments"):
                slot["args"] += fn["arguments"]
        if choice.get("finish_reason"):
            self.finish_reason = choice["finish_reason"]
        return tokens

    def finalize(self) -> List[ToolCall]:
        calls: List[ToolCall] = []
        for idx in sorted(self._slots):
            slot = self._slots[idx]
            args: Dict[str, Any] = {}
            raw = slot["args"].strip()
            if raw:
                try:
                    parsed = json.loads(raw)
                    if isinstance(parsed, dict):
                        args = parsed
                    else:
                        args = {"_value": parsed}
                except json.JSONDecodeError:
                    LOGGER.warning("tool call %s has non-JSON arguments", slot["name"])
                    args = {"_raw": raw}
            calls.append(ToolCall(
                id=slot["id"] or "call_%d" % idx,
                name=slot["name"] or "",
                arguments=args,
            ))
        return calls

    @property
    def content(self) -> str:
        return "".join(self._content_parts)


class OllamaStreamAccumulator:
    """Accumulates one Ollama /api/chat streaming response."""

    def __init__(self) -> None:
        self._content_parts: List[str] = []
        self._tool_calls: List[ToolCall] = []
        self.done = False
        self.usage: Optional[Dict[str, Any]] = None

    def feed(self, chunk: Dict[str, Any]) -> List[str]:
        tokens: List[str] = []
        msg = chunk.get("message") or {}
        piece = msg.get("content")
        if piece:
            self._content_parts.append(piece)
            tokens.append(piece)
        for tc in msg.get("tool_calls") or []:
            fn = tc.get("function") or {}
            name = fn.get("name") or ""
            args = fn.get("arguments") or {}
            if isinstance(args, str):
                try:
                    parsed = json.loads(args)
                    args = parsed if isinstance(parsed, dict) else {"_value": parsed}
                except json.JSONDecodeError:
                    args = {"_raw": args}
            if not isinstance(args, dict):
                args = {"_value": args}
            if any(c.name == name and c.arguments == args for c in self._tool_calls):
                continue  # duplicate chunk re-sent by some servers
            self._tool_calls.append(ToolCall(
                id=tc.get("id") or "call_%d" % len(self._tool_calls),
                name=name,
                arguments=args,
            ))
        if chunk.get("done"):
            self.done = True
            if chunk.get("prompt_eval_count") is not None or chunk.get("eval_count") is not None:
                self.usage = {
                    "in": chunk.get("prompt_eval_count"),
                    "out": chunk.get("eval_count"),
                }
        return tokens

    def finalize(self) -> List[ToolCall]:
        return list(self._tool_calls)

    @property
    def content(self) -> str:
        return "".join(self._content_parts)


def parse_sse_line(line: str) -> Optional[str]:
    """Extract the payload of one SSE ``data:`` line (None for other lines)."""
    stripped = line.strip()
    if not stripped.startswith("data:"):
        return None
    return stripped[5:].strip()
