"""Chat sessions and their manager.

Sessions live in memory for the lifetime of the Core process.  ``SessionManager``
can be given a :class:`~agent_core.agent.store.SessionStore` so a session is
restored from disk the first time the Shell asks for it again (history list,
IDE restart).
"""

import logging
import time
from typing import Any, Dict, List, Optional

from agent_core.protocol import ContextSnapshot, Message, Role

LOGGER = logging.getLogger(__name__)

TITLE_CHARS = 40

# In-memory ceiling, mirroring ``SessionStore.MAX_MESSAGES``: every request is already
# trimmed to the token budget, so keeping more than this only makes each send tokenize
# history that will never be sent (and that is what used to freeze long conversations).
# 内存消息上限与落盘一致：请求本来只按 token 预算取最近的，留更多只会让每次发送
# 重新分词永远发不出去的历史。
MAX_SESSION_MESSAGES = 240


def make_title(text: str) -> str:
    """First line of the first user message, trimmed to a readable tab title."""
    flat = " ".join((text or "").split())
    if not flat:
        return ""
    return flat[:TITLE_CHARS]


def display_messages(messages: List[Message]) -> List[Dict[str, Any]]:
    """Messages a Shell can replay: user turns and assistant text, no tool traffic."""
    out: List[Dict[str, Any]] = []
    for message in messages:
        content = message.content or ""
        if message.role == Role.TOOL:
            continue
        if message.role == Role.ASSISTANT and not content.strip():
            continue  # a round that only carried tool calls
        if message.role not in (Role.USER, Role.ASSISTANT):
            continue
        out.append({"role": message.role.value, "content": content})
    return out


class Session:
    def __init__(self, session_id: str) -> None:
        self.session_id = session_id
        self.messages: List[Message] = []
        self.last_context: Optional[ContextSnapshot] = None
        # Title and updated_at are what the history list shows.
        self.title: str = ""
        self.updated_at: int = 0
        # Set by SessionManager.delete: a task still holding this object must stop
        # persisting it, otherwise its final save() recreates the deleted file.
        # 删除墓碑：仍在跑的任务持着这个对象，结束时不能再写回磁盘。
        self.deleted: bool = False

    def append(self, message: Message) -> None:
        self.messages.append(message)
        self._trim_overflow()

    def _trim_overflow(self) -> None:
        """Drop the oldest whole turns once the transcript passes the ceiling.

        Whole turns only: an assistant message with ``tool_calls`` must not outlive the
        tool results that belong to it, and the newest turn is always kept.
        """
        while len(self.messages) > MAX_SESSION_MESSAGES:
            starts = [i for i, m in enumerate(self.messages) if m.role == Role.USER]
            if len(starts) < 2:
                return
            del self.messages[:starts[1]]

    def clear(self) -> None:
        self.messages.clear()

    def touch(self) -> None:
        self.updated_at = int(time.time() * 1000)

    def __repr__(self) -> str:
        return "Session(id=%r, messages=%d)" % (self.session_id, len(self.messages))


class SessionManager:
    def __init__(self, store=None) -> None:
        self._sessions: Dict[str, Session] = {}
        self._store = store

    def get_or_create(self, session_id: str) -> Session:
        session = self._sessions.get(session_id)
        if session is not None:
            return session
        if self._store is not None:
            restored = self._store.load(session_id)
            if restored is not None:
                LOGGER.info("restore session %s (%d messages)",
                            session_id, len(restored.messages))
                self._sessions[session_id] = restored
                return restored
        session = Session(session_id)
        self._sessions[session_id] = session
        LOGGER.info("create session %s", session_id)
        return session

    def get(self, session_id: str) -> Optional[Session]:
        return self._sessions.get(session_id)

    def drop(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)

    # ---------------------------------------------------------------- history

    def save(self, session: Session) -> None:
        """Persist after every task; a broken store must never break the chat."""
        if session.deleted or self._store is None:
            return
        try:
            self._store.save(session)
        except Exception:  # pragma: no cover - defensive
            LOGGER.exception("failed to persist session %s", session.session_id)

    def list_sessions(self) -> List[Dict[str, Any]]:
        if self._store is None:
            return []
        try:
            entries = self._store.list_sessions()
        except Exception:  # pragma: no cover - defensive
            LOGGER.exception("failed to list persisted sessions")
            return []
        # In-memory sessions are fresher than their last saved snapshot.
        by_id = {entry["id"]: entry for entry in entries}
        for session in self._sessions.values():
            entry = by_id.get(session.session_id)
            if entry is None:
                entry = {"id": session.session_id, "preview": _first_user_text(session.messages),
                         "messageCount": 0}
                by_id[session.session_id] = entry
            entry["title"] = session.title or entry.get("title") or ""
            # Count what the history list shows: user/assistant turns with text.
            entry["messageCount"] = len(display_messages(session.messages))
            entry["updatedAt"] = max(int(entry.get("updatedAt") or 0), session.updated_at)
        return sorted(by_id.values(), key=lambda item: item.get("updatedAt") or 0,
                      reverse=True)

    def detail(self, session_id: str) -> Dict[str, Any]:
        """Title + replayable messages for one conversation."""
        session = self._sessions.get(session_id)
        if session is None and self._store is not None:
            try:
                session = self._store.load(session_id)
            except Exception:  # pragma: no cover - defensive
                LOGGER.exception("failed to load session %s", session_id)
                session = None
        if session is None:
            return {"id": session_id, "title": "", "updatedAt": 0, "messages": []}
        return {
            "id": session_id,
            "title": session.title or make_title(_first_user_text(session.messages)),
            "updatedAt": session.updated_at,
            "messages": display_messages(session.messages),
        }

    def delete(self, session_id: str) -> int:
        """Drop the conversation from memory and disk; returns the bytes freed."""
        session = self._sessions.pop(session_id, None)
        if session is not None:
            # Release the transcript now instead of waiting for the tab/task to go away,
            # and tombstone it so an in-flight task cannot write it back afterwards.
            # 立即释放内存，并给仍在跑的任务打墓碑，避免任务结束时又把文件写回来。
            session.deleted = True
            session.messages.clear()
        if self._store is None:
            return 0
        try:
            return self._store.delete(session_id)
        except Exception:  # pragma: no cover - defensive
            LOGGER.exception("failed to delete session %s", session_id)
            return 0


def _first_user_text(messages: List[Message]) -> str:
    for message in messages:
        if message.role == Role.USER and (message.content or "").strip():
            return message.content or ""
    return ""
