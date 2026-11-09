"""Tool calls that models write into the text channel.

Some OpenAI-compatible endpoints never return ``delta.tool_calls``: vLLM started
without ``--tool-call-parser``, Ollama builds without tool support, or a model
whose chat template prints the call itself.  The markup then arrives as ordinary
``content`` and used to end up as raw XML in the chat bubble while no tool ran:

    <tool_call>
    <function=read_file>
    <parameter=path>
    src/main.py
    </parameter>
    </function>
    </tool_call>

Two shapes are recovered: the Hermes/Qwen ``<function=...>`` form above and the
``{"name": ..., "arguments": {...}}`` JSON form.  ``TextToolCallFilter`` hides
the markup from the token stream, ``recover_text_tool_calls`` turns the
accumulated content into real ``ToolCall`` objects.
"""

import json
import logging
import re
import uuid
from typing import Any, Dict, List, Tuple

from agent_core.protocol import ToolCall

LOGGER = logging.getLogger(__name__)

_CALL_OPEN = "<tool_call>"
_CALL_CLOSE = "</tool_call>"
_FUNC_OPEN = "<function="
_FUNC_CLOSE = "</function>"

_CALL_BLOCK_RE = re.compile(re.escape(_CALL_OPEN) + r"(.*?)" + re.escape(_CALL_CLOSE), re.S)
_FUNC_RE = re.compile(r"<function=([^>\s]+)>(.*?)</function>", re.S)
_PARAM_RE = re.compile(r"<parameter=([^>\s]+)>(.*?)</parameter>", re.S)
_INT_RE = re.compile(r"^-?\d+$")

# Longest prefix that must be held back to recognise a marker split across chunks.
_HOLD = max(len(_CALL_OPEN), len(_FUNC_OPEN)) - 1


def _coerce(value: str) -> Any:
    """Turn "12" / "true" into JSON scalars; everything else stays text."""
    text = value.strip()
    if _INT_RE.match(text):
        return int(text)
    if text.lower() in ("true", "false"):
        return text.lower() == "true"
    return value


def _param_value(raw: str) -> str:
    value = raw.replace("\r\n", "\n")
    if value.startswith("\n"):
        value = value[1:]
    if value.endswith("\n"):
        value = value[:-1]
    # A single-line value never carries meaningful indentation; multiline ones
    # (file content) keep it.
    return value.strip() if "\n" not in value else value


def _make_call(name: str, args: Dict[str, Any]) -> ToolCall:
    # Unique per turn: the Core keys pending results by tool call id.
    return ToolCall(id="txt_" + uuid.uuid4().hex[:10], name=name.strip(), arguments=args)


def _parse_block(block: str) -> List[ToolCall]:
    calls: List[ToolCall] = []
    for match in _FUNC_RE.finditer(block):
        args = {m.group(1).strip(): _coerce(_param_value(m.group(2)))
                for m in _PARAM_RE.finditer(match.group(2))}
        if match.group(1).strip():
            calls.append(_make_call(match.group(1), args))
    if calls:
        return calls

    text = block.strip()
    if not text:
        return calls
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        LOGGER.warning("unparsed text tool call: %r", block[:200])
        return calls
    for item in parsed if isinstance(parsed, list) else [parsed]:
        if not isinstance(item, dict):
            continue
        name = item.get("name") or item.get("tool") or ""
        args = item.get("arguments")
        if args is None:
            args = item.get("parameters")
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {"_raw": args}
        if name and isinstance(args, dict):
            calls.append(_make_call(str(name), args))
    return calls


def recover_text_tool_calls(content: str) -> Tuple[str, List[ToolCall]]:
    """Split tool-call markup out of ``content``; returns (visible text, calls)."""
    if not content or (_CALL_OPEN not in content and _FUNC_OPEN not in content):
        return content, []

    calls: List[ToolCall] = []
    for match in _CALL_BLOCK_RE.finditer(content):
        calls.extend(_parse_block(match.group(1)))
    cleaned = _CALL_BLOCK_RE.sub("", content)

    # A template that forgets the <tool_call> wrapper still gets executed.
    if _FUNC_OPEN in cleaned:
        calls.extend(_parse_block(cleaned))
        cleaned = _FUNC_RE.sub("", cleaned)

    return cleaned.strip(), calls


class TextToolCallFilter:
    """Pass text through while swallowing tool-call markup blocks.

    ``feed`` returns the text that is safe to show now; the tail is held back
    until it can be proven not to be the start of a marker.
    """

    def __init__(self) -> None:
        self._pending = ""
        self._in_block = False
        self._closer = _CALL_CLOSE

    def feed(self, piece: str) -> List[str]:
        out: List[str] = []
        if piece:
            self._pending += piece
        while True:
            if self._in_block:
                end = self._pending.find(self._closer)
                if end < 0:
                    return out
                self._pending = self._pending[end + len(self._closer):]
                self._in_block = False
                continue
            index = self._marker_index()
            if index < 0:
                if len(self._pending) > _HOLD:
                    cut = len(self._pending) - _HOLD
                    out.append(self._pending[:cut])
                    self._pending = self._pending[cut:]
                return out
            if index:
                out.append(self._pending[:index])
                self._pending = self._pending[index:]
            self._closer = _FUNC_CLOSE if self._pending.startswith(_FUNC_OPEN) else _CALL_CLOSE
            self._in_block = True

    def flush(self) -> List[str]:
        """End of stream: release the held text, drop an unterminated block."""
        rest, self._pending = self._pending, ""
        if self._in_block:
            # The model stopped mid-call; the parser drops it as well, so keep it
            # hidden instead of dumping half a tag into the bubble.
            self._in_block = False
            return []
        return [rest] if rest else []

    def _marker_index(self) -> int:
        hits = [self._pending.find(marker) for marker in (_CALL_OPEN, _FUNC_OPEN)]
        hits = [hit for hit in hits if hit >= 0]
        return min(hits) if hits else -1
