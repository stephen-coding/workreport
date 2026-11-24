"""Per-task diagnostic trace.

Every chat task writes one JSONL file under ``<log dir>/tasks`` so a failed turn can
be replayed after the IDE console scrolled away or the Core restarted. The important
events are mirrored into the normal log as a single line each, prefixed with the task
id, so grepping ``core-YYYYMMDD.log`` for a task id already tells the story.

每个任务一份 JSONL（工具调用、Shell 往返、派发、轮次），关键事件同时以一行写进
普通日志；任务结束时打印摘要，摘要里带 trace 文件路径。

Layout::

    {"ts": 1763950000.123, "kind": "tool_start", "session": "...", "tool": "read_file", ...}

Trace files are capped (``MAX_FILE_BYTES``) and the directory keeps only the newest
``MAX_TRACES`` files, so a long-running install cannot fill the disk.
"""

import contextvars
import json
import logging
import os
import time
from typing import Any, Dict, Optional

from agent_core.usage import default_log_dir

LOGGER = logging.getLogger(__name__)

MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TRACES = 30
BRIEF_LIMIT = 240

# Events worth a line in the normal log; everything else stays JSONL-only at DEBUG.
# 值得进普通日志的事件；其余只落 JSONL（普通日志不刷屏）。
_LEVELS = {
    "task_start": logging.INFO,
    "task_end": logging.INFO,
    "tool_timeout": logging.WARNING,
    "tool_fail": logging.WARNING,
    "remote_timeout": logging.WARNING,
    "remote_orphan": logging.WARNING,
    "confirm_timeout": logging.WARNING,
    "ask_timeout": logging.WARNING,
    "dispatch_error": logging.WARNING,
    "dispatch_timeout": logging.WARNING,
    "llm_error": logging.WARNING,
}

_current: contextvars.ContextVar[Optional["TaskTrace"]] = contextvars.ContextVar(
    "agent_trace", default=None)


def enabled() -> bool:
    """Tracing is on unless ``AGENT_TRACE`` says otherwise."""
    return os.environ.get("AGENT_TRACE", "1").strip().lower() not in (
        "0", "false", "no", "off")


def trace_dir() -> str:
    return os.environ.get("AGENT_TRACE_DIR") or os.path.join(default_log_dir(), "tasks")


def brief(value: Any, limit: int = BRIEF_LIMIT) -> str:
    """One-line, length-capped rendering for logs (tool args, errors, goals)."""
    if value is None:
        return ""
    if isinstance(value, str):
        text = value
    else:
        try:
            text = json.dumps(value, ensure_ascii=False, default=str)
        except (TypeError, ValueError):
            text = str(value)
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit] + "..."


def _render(fields: Dict[str, Any]) -> str:
    parts = []
    for key, value in fields.items():
        if value is None:
            continue
        parts.append("%s=%s" % (key, brief(value, 120)))
    return " ".join(parts)


class TaskTrace:
    """Collects events for one chat task and writes the trailing summary."""

    def __init__(self, task_id: str, session_id: str = "",
                 directory: Optional[str] = None) -> None:
        self.task_id = task_id or "unknown"
        self.session_id = session_id or ""
        self.directory = directory or trace_dir()
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.path = os.path.join(self.directory, "%s-%s.jsonl" % (stamp, self.task_id))
        self.counts: Dict[str, int] = {
            "rounds": 0, "llmCalls": 0, "toolCalls": 0, "toolFails": 0,
            "subagents": 0, "dispatchErrors": 0,
        }
        self.failed_tools: Dict[str, int] = {}
        self._bytes = 0
        self._truncated = False
        self._closed = False

    # ------------------------------------------------------------------ events

    def event(self, kind: str, **fields: Any) -> None:
        if self._closed:
            return
        record: Dict[str, Any] = {"ts": round(time.time(), 3), "kind": kind}
        if self.session_id:
            record["session"] = self.session_id
        for key, value in fields.items():
            if value is not None:
                record[key] = value
        self._append(record)
        level = _LEVELS.get(kind)
        if level is not None:
            LOGGER.log(level, "[%s] %s %s", self.task_id, kind, _render(fields))

    def count(self, name: str, amount: int = 1) -> None:
        self.counts[name] = self.counts.get(name, 0) + amount

    def fail(self, tool: str) -> None:
        self.counts["toolFails"] = self.counts.get("toolFails", 0) + 1
        if tool:
            self.failed_tools[tool] = self.failed_tools.get(tool, 0) + 1

    def finish(self, ok: bool, error: str = "", **extra: Any) -> None:
        """Write the closing summary, mirror it into the log, then stop recording."""
        summary = dict(self.counts)
        summary["failedTools"] = dict(self.failed_tools) or None
        summary["ok"] = bool(ok)
        summary["truncated"] = self._truncated or None
        summary.update(extra)
        self.event("task_end", error=(error or None), **summary)
        self._closed = True
        LOGGER.log(
            logging.INFO if ok else logging.WARNING,
            "task %s finished (ok=%s): rounds=%d llm=%d tools=%d failed=%d "
            "subagents=%d duration=%.1fs trace=%s%s",
            self.task_id, ok, summary.get("rounds", 0), summary.get("llmCalls", 0),
            summary.get("toolCalls", 0), summary.get("toolFails", 0),
            summary.get("subagents", 0), float(extra.get("durationMs") or 0) / 1000.0,
            self.path, (" error=" + brief(error)) if error else "")
        self._prune()

    # ------------------------------------------------------------------ storage

    def _append(self, record: Dict[str, Any]) -> None:
        if self._truncated:
            return
        try:
            line = json.dumps(record, ensure_ascii=False, default=str) + "\n"
        except (TypeError, ValueError):  # pragma: no cover - defensive
            line = json.dumps({"ts": record.get("ts"), "kind": record.get("kind"),
                               "note": "unserialisable fields"}) + "\n"
        if self._bytes + len(line) > MAX_FILE_BYTES:
            self._truncated = True
            line = json.dumps({"ts": round(time.time(), 3), "kind": "truncated",
                               "limit": MAX_FILE_BYTES}) + "\n"
            self._bytes = MAX_FILE_BYTES
        try:
            os.makedirs(self.directory, exist_ok=True)
            with open(self.path, "a", encoding="utf-8", newline="\n") as handle:
                handle.write(line)
            self._bytes += len(line)
        except OSError:  # pragma: no cover - diagnostics must never break a chat
            LOGGER.debug("cannot append task trace", exc_info=True)

    def _prune(self) -> None:
        try:
            names = sorted(
                (os.path.join(self.directory, name) for name in os.listdir(self.directory)
                 if name.endswith(".jsonl") and name[8:9] == "-"),
                key=lambda path: os.path.getmtime(path), reverse=True)
        except OSError:
            return
        for stale in names[MAX_TRACES:]:
            try:
                os.remove(stale)
            except OSError:
                continue


# ---------------------------------------------------------------- module helpers

def start(task_id: str, session_id: str = "") -> Optional[TaskTrace]:
    """Create a trace unless tracing is disabled; never raises."""
    if not enabled():
        return None
    try:
        return TaskTrace(task_id, session_id)
    except Exception:  # pragma: no cover - defensive
        LOGGER.debug("cannot create task trace", exc_info=True)
        return None


def bind(trace: Optional[TaskTrace]):
    return _current.set(trace)


def reset(token) -> None:
    try:
        _current.reset(token)
    except (ValueError, LookupError):  # pragma: no cover - defensive
        pass


def current() -> Optional[TaskTrace]:
    return _current.get()


def emit(kind: str, **fields: Any) -> None:
    """Record an event on the active task trace; a no-op when there is none."""
    trace = _current.get()
    if trace is None:
        return
    try:
        trace.event(kind, **fields)
    except Exception:  # pragma: no cover - diagnostics must never break a chat
        LOGGER.debug("trace emit failed", exc_info=True)
