"""Task checklist (``todo_write``).

The list belongs to the running session, lives in the Core and is persisted with
the conversation, so the Shell only renders what it is told. The tool is a
built-in (``remote=False``): no IDE round trip is needed to update a checklist.

Python 3.9 compatible.
"""

import contextvars
import logging
from typing import Any, Callable, Dict, List, Optional

from agent_core.agent.session import Session
from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

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
        "创建或更新本次任务的任务清单（整表覆盖，不是增量）。任务需要 3 步以上时先列清单；"
        "每开始一项就置为 in_progress，每完成一项就立刻整表重写一次；"
        "只改状态也要带上该项原有 content，否则该项会被丢弃；"
        "同一时间最多一个 in_progress；传空数组表示清空清单。"
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

# How much of the checklist the per-round status block repeats: enough to judge the
# progress, short enough to stay cheap on every round.
# 状态栏每轮重复多少条清单：够判断进度，又不至于每轮花太多 token。
MAX_STATUS_ITEMS = 6
CONTENT_CHARS = 48

# After this many rounds without a todo_write the model gets a pointed reminder.
# 连续这么多轮没回写清单就换成明确的提醒（实证过：只写规则句会被忽略）。
STALE_TODO_ROUNDS = 3

STATUS_RULE = ("未回写的进度不会出现在界面上：每开始/完成一项就立刻 todo_write 整表重写，"
               "只改状态也要带上该项原有 content。")
STALE_HINT = ("已经 %d 轮没有回写清单：先把已完成/已开始的项更新为正确状态再继续，"
              "否则界面会一直停在旧进度。")
INHERITED_HINT = "沿用上一轮清单；若本轮是新任务，先 todo_write 重写一份"

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


def _clip(text: str, limit: int = CONTENT_CHARS) -> str:
    flat = " ".join((text or "").split())
    return flat if len(flat) <= limit else flat[:limit] + "…"


def _visible(todos: List[Dict[str, str]]) -> List[Dict[str, str]]:
    """Rows worth repeating: unfinished ones win when the list is too long."""
    if len(todos) <= MAX_STATUS_ITEMS:
        return list(todos)
    active = [index for index, todo in enumerate(todos)
              if todo.get("status") not in ("completed", "cancelled")]
    if len(active) >= MAX_STATUS_ITEMS:
        keep = active[:MAX_STATUS_ITEMS]
    else:
        rest = [index for index in range(len(todos)) if index not in active]
        keep = sorted(active + rest[:MAX_STATUS_ITEMS - len(active)])
    return [todos[index] for index in keep]


def render_status(todos: List[Dict[str, str]], inherited: bool = False,
                  stale_rounds: int = 0) -> str:
    """Compact checklist for the per-round status block.

    The model cannot see the list once it leaves the recent window, so the block repeats
    the current state plus one imperative line every round. Without this the tool is easy
    to treat as a plan written once at the start - which is exactly what used to happen.
    状态栏每轮重复清单状态 + 一句命令式提醒，治住「只开头写一次」。
    """
    if not todos:
        return ""
    done = sum(1 for todo in todos if todo.get("status") in ("completed", "cancelled"))
    head = "清单: %d/%d 完成" % (done, len(todos))
    if inherited:
        head += "（" + INHERITED_HINT + "）"
    lines = [head]
    visible = _visible(todos)
    for todo in visible:
        lines.append("  %s %s. %s" % (MARKS.get(todo.get("status"), "[?]"),
                                      todo.get("id") or "-",
                                      _clip(todo.get("content"))))
    if len(visible) < len(todos):
        lines.append("  …另有 %d 项" % (len(todos) - len(visible)))
    unfinished = any(todo.get("status") not in ("completed", "cancelled") for todo in todos)
    if unfinished and stale_rounds >= STALE_TODO_ROUNDS:
        lines.append(STALE_HINT % stale_rounds)
    lines.append(STATUS_RULE)
    return "\n".join(lines)


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
        raw = (call.arguments or {}).get("todos")
        if raw is None:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="缺少 todos 参数：需要传入数组，每项含 id / content / status")
        if not isinstance(raw, list):
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="todos 必须是数组，收到的是 %s" % type(raw).__name__)
        todos = normalize_todos(raw)
        if raw and not todos:
            # Every entry was unusable: refuse instead of silently wiping the checklist.
            # 每条都不可用时报错而不是清空：静默清空比报错更难排查。
            return ToolResult(tool_call_id=call.id, ok=False,
                              error=("todos 里有 %d 条，但没有一条可用：每项都需要非空的 "
                                     "content，status 取 %s" % (len(raw), " / ".join(STATUSES))))
        # An empty array is the documented way to clear the checklist; it used to be an
        # error, which made a model that wanted to clear or reorder simply give up.
        # 空数组 = 清空清单。原来这里返回失败，模型想清空或重排时会直接放弃这个工具。
        session.todos = todos
        try:
            publish(todos)
        except Exception:  # pragma: no cover - a broken UI must not fail the tool
            LOGGER.warning("todo publish failed (%d items)", len(todos), exc_info=True)
        return ToolResult(tool_call_id=call.id, ok=True, content=render(todos))

    return handle


def register_tools(registry: ToolRegistry,
                   publish: Callable[[List[Dict[str, str]]], None]) -> None:
    registry.register(TODO_DEF, make_handler(publish))
