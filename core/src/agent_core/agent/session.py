"""Chat sessions and their manager."""

import logging
from typing import Dict, List, Optional

from agent_core.protocol import ContextSnapshot, Message

LOGGER = logging.getLogger(__name__)


class Session:
    def __init__(self, session_id: str) -> None:
        self.session_id = session_id
        self.messages: List[Message] = []
        self.last_context: Optional[ContextSnapshot] = None

    def append(self, message: Message) -> None:
        self.messages.append(message)

    def clear(self) -> None:
        self.messages.clear()

    def __repr__(self) -> str:
        return "Session(id=%r, messages=%d)" % (self.session_id, len(self.messages))


class SessionManager:
    def __init__(self) -> None:
        self._sessions: Dict[str, Session] = {}

    def get_or_create(self, session_id: str) -> Session:
        session = self._sessions.get(session_id)
        if session is None:
            session = Session(session_id)
            self._sessions[session_id] = session
            LOGGER.info("create session %s", session_id)
        return session

    def get(self, session_id: str) -> Optional[Session]:
        return self._sessions.get(session_id)

    def drop(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)
