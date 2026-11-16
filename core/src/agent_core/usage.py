"""Local usage log (ROADMAP §7.6).

One JSON line per finished task under ``~/.agent/logs/usage.jsonl``.  Nothing
leaves the machine: there is no user id and no network request.  The file is
rotated once it passes 10 MB so a year of chatting cannot fill the disk, and the
report is only computed when the Shell asks for it.

本地用量统计：每个任务一行，超过 10MB 轮转到 usage-YYYYMM.jsonl；
不做任何网络请求、不记用户标识，报表只在用户主动查询时计算。
"""

import calendar
import json
import logging
import os
import time
from typing import Any, Dict, List

LOGGER = logging.getLogger(__name__)

USAGE_FILE = "usage.jsonl"
MAX_BYTES = 10 * 1024 * 1024
MAX_REPORT_DAYS = 90
DEFAULT_REPORT_DAYS = 7
TIME_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


def default_log_dir() -> str:
    """Where ``core-*.log`` and ``usage.jsonl`` live; overridable for tests."""
    return os.environ.get("AGENT_LOG_DIR") or os.path.join(
        os.path.expanduser("~"), ".agent", "logs")


def timestamp(epoch: float) -> str:
    return time.strftime(TIME_FORMAT, time.gmtime(epoch))


def _parse_timestamp(text: str) -> float:
    try:
        return float(calendar.timegm(time.strptime(text, TIME_FORMAT)))
    except (TypeError, ValueError):
        return 0.0


class UsageLog:
    def __init__(self, directory: str) -> None:
        self.directory = directory
        self.path = os.path.join(directory, USAGE_FILE)

    # ------------------------------------------------------------------ write

    def record(self, task_id: str, model: str, usage_in: int, usage_out: int,
               tool_calls: int, duration_ms: int, ok: bool) -> None:
        entry = {
            "ts": timestamp(time.time()),
            "taskId": task_id or "",
            "model": model or "",
            "in": int(usage_in or 0),
            "out": int(usage_out or 0),
            "toolCalls": int(tool_calls or 0),
            "durationMs": int(duration_ms or 0),
            "ok": bool(ok),
        }
        try:
            os.makedirs(self.directory, exist_ok=True)
            self._rotate_if_needed()
            with open(self.path, "a", encoding="utf-8", newline="\n") as handle:
                handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
        except OSError:  # pragma: no cover - statistics must never break a chat
            LOGGER.warning("failed to append usage line", exc_info=True)

    def _rotate_if_needed(self) -> None:
        try:
            if not os.path.exists(self.path) or os.path.getsize(self.path) < MAX_BYTES:
                return
        except OSError:
            return
        target = os.path.join(self.directory, "usage-%s.jsonl" % time.strftime("%Y%m"))
        try:
            if os.path.exists(target):
                # Same month twice: keep both by appending, then drop the source.
                with open(self.path, "rb") as source, open(target, "ab") as archive:
                    archive.write(source.read())
                os.remove(self.path)
            else:
                os.replace(self.path, target)
            LOGGER.info("rotated usage log to %s", target)
        except OSError:  # pragma: no cover - defensive
            LOGGER.warning("failed to rotate usage log", exc_info=True)

    # ------------------------------------------------------------------- read

    def files(self) -> List[str]:
        if not os.path.isdir(self.directory):
            return []
        names = [
            name for name in os.listdir(self.directory)
            if name == USAGE_FILE
            or (name.startswith("usage-") and name.endswith(".jsonl"))
        ]
        return [os.path.join(self.directory, name) for name in sorted(names)]

    def report(self, days: int = DEFAULT_REPORT_DAYS) -> Dict[str, Any]:
        days = max(1, min(int(days or DEFAULT_REPORT_DAYS), MAX_REPORT_DAYS))
        cutoff = time.time() - days * 86400
        tasks = failed = tokens_in = tokens_out = tools = duration = 0
        by_day: Dict[str, Dict[str, int]] = {}
        models: Dict[str, Dict[str, int]] = {}

        for path in self.files():
            try:
                with open(path, "r", encoding="utf-8") as handle:
                    lines = handle.readlines()
            except OSError:
                LOGGER.warning("cannot read usage log %s", path, exc_info=True)
                continue
            for line in lines:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                epoch = _parse_timestamp(str(entry.get("ts") or ""))
                if epoch < cutoff:
                    continue
                ok = bool(entry.get("ok", True))
                tokens_in += int(entry.get("in") or 0)
                tokens_out += int(entry.get("out") or 0)
                tools += int(entry.get("toolCalls") or 0)
                duration += int(entry.get("durationMs") or 0)
                tasks += 1
                if not ok:
                    failed += 1
                day = str(entry.get("ts") or "")[:10]
                bucket = by_day.setdefault(day, {"tasks": 0, "tokensIn": 0, "tokensOut": 0})
                bucket["tasks"] += 1
                bucket["tokensIn"] += int(entry.get("in") or 0)
                bucket["tokensOut"] += int(entry.get("out") or 0)
                model = str(entry.get("model") or "") or "unknown"
                model_bucket = models.setdefault(model, {"tasks": 0, "tokensOut": 0})
                model_bucket["tasks"] += 1
                model_bucket["tokensOut"] += int(entry.get("out") or 0)

        return {
            "days": days,
            "since": timestamp(cutoff),
            "tasks": tasks,
            "ok": tasks - failed,
            "failed": failed,
            "tokensIn": tokens_in,
            "tokensOut": tokens_out,
            "toolCalls": tools,
            "durationMs": duration,
            "avgDurationMs": int(duration / tasks) if tasks else 0,
            "byDay": [dict(date=day, **by_day[day]) for day in sorted(by_day)],
            "models": [
                dict(model=name, **models[name])
                for name in sorted(models, key=lambda n: models[n]["tasks"], reverse=True)
            ],
        }
