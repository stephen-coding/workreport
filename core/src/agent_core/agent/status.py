"""Agent status bar: a code-computed snapshot appended to the request tail.

The model is good at retrieving facts that are already in the context but bad
at recounting them ("how many times did I call this tool?"). The harness
therefore pre-counts the turn and hands the result over, instead of letting the
model rescan the whole trajectory on every iteration.

Two rules keep this cheap and cache-friendly:

* the block is appended as the last message, so the system prompt and the whole
  cached prefix stay byte-identical;
* every number is computed in code. A model believes the status bar
  unconditionally, so it must never be produced by another LLM call.
"""

import hashlib
import json
import os
from collections import Counter
from typing import Any, Dict, Iterable, List, Tuple

from agent_core.protocol import ToolCall

# Show a repeat only after the second identical call; one call is not a pattern.
# 重复调用至少出现两次才有提示价值。
REPEAT_MIN = 2
MAX_TOOLS = 4
MAX_REPEATS = 3
MAX_EDITED = 3
MAX_ERROR = 40
EDIT_TOOLS = ("write_file", "edit_file", "delete_file")


def _digest(call: ToolCall) -> str:
    """Stable fingerprint of one call, used to spot identical retries."""
    try:
        raw = json.dumps(call.arguments or {}, sort_keys=True, ensure_ascii=False)
    except (TypeError, ValueError):
        raw = str(call.arguments)
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:8]


def _short_path(value: Any) -> str:
    if not isinstance(value, str) or not value:
        return ""
    return os.path.basename(value.rstrip("/\\")) or value


def _clip(text: str, limit: int) -> str:
    flat = " ".join((text or "").split())
    return flat if len(flat) <= limit else flat[:limit] + "…"


class TurnStats:
    """Counters for one user turn (a single ``run_chat`` call)."""

    def __init__(self) -> None:
        self.rounds: int = 0
        self.calls: Counter = Counter()
        self.failures: Counter = Counter()
        self.last_error: Dict[str, str] = {}
        # (tool name, argument fingerprint) -> how many times that exact call ran.
        # (工具名, 参数指纹) -> 这一次完全相同调用的次数。
        self.call_counts: Counter = Counter()
        self.repeat_labels: Dict[Tuple[str, str], str] = {}
        self.edited: List[str] = []

    def begin_round(self) -> None:
        self.rounds += 1

    def record_calls(self, calls: Iterable[ToolCall]) -> None:
        for call in calls:
            self.calls[call.name] += 1
            key = (call.name, _digest(call))
            self.call_counts[key] += 1
            if key not in self.repeat_labels:
                label = _short_path((call.arguments or {}).get("path"))
                self.repeat_labels[key] = call.name + ("(" + label + ")" if label else "")

    def record_result(self, call: ToolCall, ok: bool, error: str = "") -> None:
        if ok:
            if call.name in EDIT_TOOLS:
                path = _short_path((call.arguments or {}).get("path"))
                if path and path not in self.edited:
                    self.edited.append(path)
            return
        self.failures[call.name] += 1
        self.last_error[call.name] = _clip(error or "未知错误", MAX_ERROR)

    def render(self, max_rounds: int) -> str:
        """Build the status block, or ``""`` when there is nothing to report."""
        if not self.calls:
            return ""

        lines = ["<agent_status>", "轮次: %d/%d" % (self.rounds, max(1, max_rounds))]

        tools = " · ".join("%s %d" % (name, count)
                           for name, count in self.calls.most_common(MAX_TOOLS))
        lines.append("工具: " + tools)

        # Filter first, then truncate: the top-N list may be full of one-off calls.
        # 先过滤再截断：按次数取前几名可能全是一次性调用。
        repeats = [(self.repeat_labels[key], count)
                   for key, count in self.call_counts.items()
                   if count >= REPEAT_MIN]
        repeats.sort(key=lambda item: item[1], reverse=True)
        if repeats:
            lines.append("重复: " + " · ".join("%s ×%d" % (label, count)
                                              for label, count in repeats[:MAX_REPEATS]))

        if self.failures:
            parts = []
            for name, count in self.failures.most_common(2):
                detail = self.last_error.get(name, "")
                parts.append("%s ×%d — %s" % (name, count, detail) if detail
                             else "%s ×%d" % (name, count))
            lines.append("失败: " + " · ".join(parts))

        if self.edited:
            lines.append("已改: " + "、".join(self.edited[:MAX_EDITED]))

        lines.append("</agent_status>")
        return "\n".join(lines)
