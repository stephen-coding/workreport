"""Tool registry: definitions plus handlers.

Remote tools (``remote=True``) are executed by the IDE shell via ``tool/invoke``;
their handlers are attached by the remote proxy in M2. Built-in tools
(memory_*, skill_load ...) register plain async handlers.
"""

import logging
from typing import Awaitable, Callable, Dict, List, Optional

from agent_core.protocol import ToolCall, ToolDef, ToolResult

LOGGER = logging.getLogger(__name__)

ToolHandler = Callable[[ToolCall], Awaitable[ToolResult]]


class ToolRegistry:
    def __init__(self) -> None:
        self._defs: Dict[str, ToolDef] = {}
        self._handlers: Dict[str, ToolHandler] = {}

    def register(self, tool_def: ToolDef, handler: Optional[ToolHandler] = None) -> None:
        if tool_def.name in self._defs:
            LOGGER.warning("tool %s re-registered", tool_def.name)
        self._defs[tool_def.name] = tool_def
        if handler is not None:
            self._handlers[tool_def.name] = handler

    def get(self, name: str) -> Optional[ToolDef]:
        return self._defs.get(name)

    def handler(self, name: str) -> Optional[ToolHandler]:
        return self._handlers.get(name)

    def defs(self) -> List[ToolDef]:
        return list(self._defs.values())

    def names(self) -> List[str]:
        return sorted(self._defs.keys())
