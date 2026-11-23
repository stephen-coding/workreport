"""Session persistence: one JSON file per conversation under ``<data_dir>/sessions``.

The Core already keeps every conversation in memory; this store makes it survive a
Core restart (IDE restart, Core path change, crash) so the Shell can list previous
conversations and reopen them where they stopped.

会话落盘：每个会话一个 JSON 文件。Core 重启后仍能列出历史会话并接着聊。
"""

import json
import logging
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional

from agent_core.agent.session import Session, make_title
from agent_core.agent.todos import normalize_todos
from agent_core.agent.blackboard import normalize_artifacts
from agent_core.protocol import Message, Role, ToolCall

LOGGER = logging.getLogger(__name__)

# v2 adds the task checklist; v3 adds the team blackboard (artifacts + team runs).
# Older files simply have no ``artifacts`` / ``teamRuns`` key and read as empty lists.
# v2 增加任务清单；v3 增加团队黑板（artifacts + teamRuns）。旧文件缺这些键时读为空列表。
STORE_VERSION = 3
# Newest messages kept on disk: enough to resume a long conversation, small enough
# that the file stays a few hundred KB.  Trimming keeps whole rounds (see save()).
MAX_MESSAGES = 240
MAX_LIST_ITEMS = 50
PREVIEW_CHARS = 80
# A .tmp file older than this was left behind by a crashed write; sweep it on list.
TEMP_MAX_AGE_SEC = 3600
# The history list reads every file; a handful of threads takes the per-file
# round trip (NTFS is the slow part, not the JSON) off the critical path.
# 历史列表要读每个文件，用少量线程把 NTFS 的打开开销叠起来（基线 §7.4）。
LIST_THREADS = 8


def _file_name(session_id: str) -> str:
    """Session ids contain '#' and maybe ':' — map them onto a safe file name."""
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", session_id or "")
    return (cleaned or "session")[:120] + ".json"


def _decode(raw: Dict[str, Any]) -> Optional[Message]:
    try:
        role = Role(str(raw.get("role") or ""))
    except ValueError:
        return None
    calls = None
    raw_calls = raw.get("toolCalls")
    if isinstance(raw_calls, list) and raw_calls:
        calls = [ToolCall(id=str(c.get("id") or ""), name=str(c.get("name") or ""),
                          arguments=c.get("arguments") or {}) for c in raw_calls]
    return Message(role=role, content=raw.get("content"), tool_calls=calls,
                   tool_call_id=raw.get("toolCallId"), name=raw.get("name"))


def _encode(message: Message) -> Dict[str, Any]:
    item: Dict[str, Any] = {"role": message.role.value, "content": message.content}
    if message.tool_calls:
        item["toolCalls"] = [{"id": call.id, "name": call.name, "arguments": call.arguments}
                             for call in message.tool_calls]
    if message.tool_call_id:
        item["toolCallId"] = message.tool_call_id
    if message.name:
        item["name"] = message.name
    return item


class SessionStore:
    def __init__(self, data_dir: str) -> None:
        self.root = Path(data_dir) / "sessions"

    def _path(self, session_id: str) -> Path:
        return self.root / _file_name(session_id)

    # ---------------------------------------------------------------- read / write

    def load(self, session_id: str) -> Optional[Session]:
        path = self._path(session_id)
        if not path.exists():
            return None
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            LOGGER.warning("unreadable session file %s", path)
            return None
        session = Session(session_id)
        session.title = str(data.get("title") or "")
        session.updated_at = int(data.get("updatedAt") or 0)
        session.todos = normalize_todos(data.get("todos"))
        session.artifacts = normalize_artifacts(data.get("artifacts"))
        session.team_runs = _normalise_records(data.get("teamRuns"))
        for raw in data.get("messages") or []:
            message = _decode(raw)
            if message is not None:
                session.messages.append(message)
        return session

    def save(self, session: Session) -> None:
        if not session.messages:
            return
        messages = session.messages[-MAX_MESSAGES:]
        # Never start with a tool result whose assistant message was trimmed away.
        while messages and messages[0].role == Role.TOOL:
            messages.pop(0)
        payload = {
            "version": STORE_VERSION,
            "id": session.session_id,
            "title": session.title or make_title(_first_user_text(session.messages)),
            "updatedAt": session.updated_at or int(time.time() * 1000),
            "messages": [_encode(message) for message in messages],
            "todos": session.todos,
            "artifacts": session.artifacts,
            "teamRuns": session.team_runs,
        }
        path = self._path(session.session_id)
        tmp = path.with_name(path.name + ".tmp")
        self.root.mkdir(parents=True, exist_ok=True)
        tmp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, path)

    # ---------------------------------------------------------------- queries

    def list_sessions(self) -> List[Dict[str, Any]]:
        if not self.root.exists():
            return []
        self._sweep_temps()
        paths = list(self.root.glob("*.json"))
        if len(paths) <= LIST_THREADS:
            entries = [entry for entry in map(_list_entry, paths) if entry is not None]
        else:
            with ThreadPoolExecutor(max_workers=LIST_THREADS) as pool:
                entries = [entry for entry in pool.map(_list_entry, paths)
                           if entry is not None]
        entries.sort(key=lambda item: item["updatedAt"], reverse=True)
        return entries[:MAX_LIST_ITEMS]

    def delete(self, session_id: str) -> int:
        """Remove the session file plus any half-written temp file.

        Returns the number of bytes actually freed, so the Shell can tell the user
        the space is gone (0 means nothing was on disk).
        删除会话文件并清掉同名的 .tmp 残留，返回真正释放的字节数。
        """
        path = self._path(session_id)
        freed = 0
        for target in (path, path.with_name(path.name + ".tmp")):
            try:
                freed += target.stat().st_size
                target.unlink()
            except FileNotFoundError:
                continue
            except OSError:  # pragma: no cover - defensive
                LOGGER.warning("failed to delete %s", target, exc_info=True)
        return freed

    def iter_documents(self) -> Iterator[Dict[str, Any]]:
        """Yield ``{id, title, content, updatedAt}`` for the full-text index.

        Only user/assistant prose is indexed: tool traffic is noise for a search
        and would blow the index up by an order of magnitude.
        只索引用户与助手的正文，工具流水对检索无益且会把索引撑大一个量级。
        """
        if not self.root.exists():
            return
        for path in sorted(self.root.glob("*.json")):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            session_id = str(data.get("id") or "")
            if not session_id:
                continue
            parts: List[str] = []
            for message in data.get("messages") or []:
                if message.get("role") not in ("user", "assistant"):
                    continue
                text = str(message.get("content") or "").strip()
                if text:
                    parts.append(text)
            yield {
                "id": session_id,
                "title": str(data.get("title") or ""),
                "content": "\n".join(parts),
                "updatedAt": int(data.get("updatedAt") or 0),
            }

    def _sweep_temps(self) -> None:
        """Drop stale .tmp files left by an interrupted save (never touches fresh ones)."""
        now = time.time()
        for path in self.root.glob("*.tmp"):
            try:
                if now - path.stat().st_mtime > TEMP_MAX_AGE_SEC:
                    path.unlink()
                    LOGGER.info("swept stale session temp %s", path)
            except OSError:  # pragma: no cover - defensive
                continue


def _list_entry(path: Path) -> Optional[Dict[str, Any]]:
    """One row of the history list; unreadable files are simply skipped."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    session_id = str(data.get("id") or "")
    if not session_id:
        return None
    # Count the turns a Shell replays, not the tool traffic behind them.
    messages = [m for m in (data.get("messages") or [])
                if m.get("role") in ("user", "assistant")
                and str(m.get("content") or "").strip()]
    return {
        "id": session_id,
        "title": str(data.get("title") or ""),
        "preview": _preview(messages),
        "messageCount": len(messages),
        "updatedAt": int(data.get("updatedAt") or 0),
    }


def _first_user_text(messages: List[Message]) -> str:
    for message in messages:
        if message.role == Role.USER and (message.content or "").strip():
            return message.content or ""
    return ""


def _preview(messages: List[Dict[str, Any]]) -> str:
    for message in messages:
        if message.get("role") != "user":
            continue
        flat = " ".join(str(message.get("content") or "").split())
        if flat:
            return flat[:PREVIEW_CHARS]
    return ""


def _normalise_records(raw: Any) -> List[Dict[str, Any]]:
    """Keep only dict rows for team runs; a broken/legacy file reads as empty."""
    if not isinstance(raw, list):
        return []
    return [item for item in raw if isinstance(item, dict)]
