"""Task checklist (``todo_write``).

The list belongs to the running session, lives in the Core and is persisted with
the conversation, so the Shell only renders what it is told. The tool is a
built-in (``remote=False``): no IDE round trip is needed to update a checklist.

Python 3.9 compatible.
"""

import contextvars
from typing import Any, Callable, Dict, List, Optional

from agent_core.agent.session import Session
from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry

TODO_TOOL = "todo_write"

STATUSES = ("pending", "in_progress", "completed", "cancelled")

# Marks the Shell renders; kept here so both sides use the same vocabulary.
MARKS = {
    "pending": "[ ]",
    "in_progress": "[>]",
    "completed": "[x]",
    "cancelled": "[-]",
}

TODO_DEF = ToolDef(
    name=TODO_TOOL,
    description=(
        "创建或更新本次任务的任务清单（整表覆盖）。任务需要 3 步以上时先列清单，"
        "开始做某项时置为 in_progress，完成后置为 completed；同一时间最多一个 in_progress。"
    ),
    parameters={
        "type": "object",
        "properties": {
            "todos": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "content": {"type": "string"},
                        "status": {"type": "string",
                                   "enum": list(STATUSES)},
                    },
                    "required": ["id", "content", "status"],
                },
            },
        },
        "required": ["todos"],
    },
    permission=Permission.AUTO,
    remote=False,
)

# The session of the chat currently running; set by the server per task.
_active_session: contextvars.ContextVar[Optional[Session]] = contextvars.ContextVar(
    "todo_session", default=None)


def bind_session(session: Session):
    """Make ``session`` the target of todo_write for the current task."""
    return _active_session.set(session)


def reset_session(token) -> None:
    _active_session.reset(token)


def normalize_todos(raw: Any) -> List[Dict[str, str]]:
    """Coerce whatever the model sent into a clean, renderable list."""
    if not isinstance(raw, list):
        return []
    todos: List[Dict[str, str]] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        content = str(entry.get("content") or "").strip()
        if not content:
            continue
        status = str(entry.get("status") or "pending").strip().lower()
        if status not in STATUSES:
            status = "pending"
        # Missing ids fall back to the position in the cleaned list.
        todo_id = str(entry.get("id") or "").strip() or str(len(todos) + 1)
        todos.append({"id": todo_id, "content": content, "status": status})
    return todos


def render(todos: List[Dict[str, str]]) -> str:
    """Text form for the tool result: the model reads back what it just wrote."""
    if not todos:
        return "清单已清空。"
    lines = ["任务清单（%d 项）：" % len(todos)]
    for todo in todos:
        lines.append("%s %s. %s" % (MARKS.get(todo["status"], "[?]"), todo["id"], todo["content"]))
    done = sum(1 for todo in todos if todo["status"] in ("completed", "cancelled"))
    lines.append("进度：%d/%d" % (done, len(todos)))
    return "\n".join(lines)


def make_handler(publish: Callable[[List[Dict[str, str]]], None]):
    """Build the tool handler; ``publish`` forwards the list to the Shell."""

    async def handle(call: ToolCall) -> ToolResult:
        session = _active_session.get()
        if session is None:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="当前没有可写入清单的会话")
        todos = normalize_todos((call.arguments or {}).get("todos"))
        if not todos:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="todos 不能为空：每项需要 id / content / status")
        session.todos = todos
        try:
            publish(todos)
        except Exception:  # pragma: no cover - a broken UI must not fail the tool
            pass
        return ToolResult(tool_call_id=call.id, ok=True, content=render(todos))

    return handle


def register_tools(registry: ToolRegistry,
                   publish: Callable[[List[Dict[str, str]]], None]) -> None:
    registry.register(TODO_DEF, make_handler(publish))
