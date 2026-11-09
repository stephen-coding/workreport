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
from pathlib import Path
from typing import Any, Dict, List, Optional

from agent_core.agent.session import Session, make_title
from agent_core.protocol import Message, Role, ToolCall

LOGGER = logging.getLogger(__name__)

STORE_VERSION = 1
# Newest messages kept on disk: enough to resume a long conversation, small enough
# that the file stays a few hundred KB.  Trimming keeps whole rounds (see save()).
MAX_MESSAGES = 240
MAX_LIST_ITEMS = 50
PREVIEW_CHARS = 80


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
        entries: List[Dict[str, Any]] = []
        for path in self.root.glob("*.json"):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            session_id = str(data.get("id") or "")
            if not session_id:
                continue
            # Count the turns a Shell replays, not the tool traffic behind them.
            messages = [m for m in (data.get("messages") or [])
                        if m.get("role") in ("user", "assistant")
                        and str(m.get("content") or "").strip()]
            entries.append({
                "id": session_id,
                "title": str(data.get("title") or ""),
                "preview": _preview(messages),
                "messageCount": len(messages),
                "updatedAt": int(data.get("updatedAt") or 0),
            })
        entries.sort(key=lambda item: item["updatedAt"], reverse=True)
        return entries[:MAX_LIST_ITEMS]

    def delete(self, session_id: str) -> bool:
        path = self._path(session_id)
        try:
            path.unlink()
            return True
        except FileNotFoundError:
            return False
        except OSError:  # pragma: no cover - defensive
            LOGGER.warning("failed to delete %s", path, exc_info=True)
            return False


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
