"""Ask-the-user tool (``ask_user``).

The model gets a *blocking* question tool: it pauses the turn, the Shell renders a card
(options plus an optional custom answer), the answer comes back as the tool result and the
loop continues in the same round. It reuses the confirmation channel (``agent/confirm``):
a future keyed by an id, answered by a Shell RPC.

设计要点：
- 提问没有短超时：用户可能离开几分钟。``chat/cancel`` 会取消任务，挂起的 future 随之清理并向
  Shell 发 ``ask/withdraw``，卡片不会一直挂着。
- 一轮里若出现 ``ask_user``，调度器先单独跑它（见 ``scheduler.INTERACTIVE_TOOLS``）：模型不能
  一边问用户一边改文件，否则用户还在选的时候文件已经被改了。
- 没有可用界面（VS Code 端尚未实现该通知）时返回一条说明，让模型带假设继续，绝不空等。

Python 3.9 compatible.
"""

from typing import Any, Awaitable, Callable, Dict, List, Optional

from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry

ASK_TOOL = "ask_user"

# A card that asks more than three questions is a form, not a question.
MAX_QUESTIONS = 3
MAX_OPTIONS = 4

ASK_DEF = ToolDef(
    name=ASK_TOOL,
    description=(
        "向用户提问，获取继续所需的信息。只在信息确实缺失、且无法从代码或上下文推断时使用；"
        "能从仓库里读到的信息请自己读，不要拿它当确认工具。"
        "一次可问 1~3 个问题，每题给 2~4 个选项，用户可以改选「其它」并填写自定义答案。"
        "用户点「跳过」时会返回一条说明，此时请基于合理假设继续，并在结论里标注假设。"
    ),
    parameters={
        "type": "object",
        "properties": {
            "questions": {
                "type": "array",
                "description": "要问的问题，1~3 个。",
                "items": {
                    "type": "object",
                    "properties": {
                        "header": {
                            "type": "string",
                            "description": "12 字以内的短标签，用于卡片上的分栏标题。",
                        },
                        "question": {
                            "type": "string",
                            "description": "问题正文，尽量一句话。",
                        },
                        "options": {
                            "type": "array",
                            "description": "2~4 个候选答案。",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "label": {"type": "string",
                                              "description": "选项标题，尽量短。"},
                                    "description": {"type": "string",
                                                    "description": "一句话说明取舍或影响。"},
                                },
                                "required": ["label"],
                            },
                        },
                        "multiSelect": {
                            "type": "boolean",
                            "description": "允许同时选多项，默认 false。",
                        },
                        "allowCustom": {
                            "type": "boolean",
                            "description": "除了选项还允许自定义答案，默认 true。",
                        },
                    },
                    "required": ["question", "options"],
                },
            },
        },
        "required": ["questions"],
    },
    permission=Permission.AUTO,
    # A human may take a coffee break; chat/cancel still interrupts this wait.
    # 用户可以离开很久；需要中断时走 chat/cancel。
    timeout_sec=1800.0,
    remote=False,
)

# ``ask`` receives the sanitised questions and returns the Shell's reply, or None when no
# UI is available. Returning None keeps the model moving instead of hanging the turn.
AskCallback = Callable[[List[Dict[str, Any]]], Awaitable[Optional[Dict[str, Any]]]]


def normalize_questions(raw: Any) -> List[Dict[str, Any]]:
    """Coerce whatever the model sent into a clean, renderable shape."""
    if isinstance(raw, dict):
        raw = [raw]
    if not isinstance(raw, list):
        return []
    questions: List[Dict[str, Any]] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        text = str(entry.get("question") or "").strip()
        if not text:
            continue
        options: List[Dict[str, str]] = []
        for option in entry.get("options") or []:
            if isinstance(option, str):
                label, description = option.strip(), ""
            elif isinstance(option, dict):
                label = str(option.get("label") or option.get("text") or "").strip()
                description = str(option.get("description") or "").strip()
            else:
                continue
            if not label:
                continue
            options.append({"label": label, "description": description})
            if len(options) >= MAX_OPTIONS:
                break
        if not options:
            # A free-form question is still useful: the user types the answer.
            options = []
        header = str(entry.get("header") or "").strip()
        questions.append({
            "header": header[:24],
            "question": text,
            "options": options,
            "multiSelect": bool(entry.get("multiSelect")),
            "allowCustom": entry.get("allowCustom", True) is not False,
        })
        if len(questions) >= MAX_QUESTIONS:
            break
    return questions


def render(questions: List[Dict[str, Any]], reply: Dict[str, Any]) -> str:
    """Text form for the tool result: exactly what the model needs to continue."""
    if reply.get("skipped"):
        return ("用户跳过了这次提问，没有给出答案。"
                "请基于合理假设继续完成任务，并在最终回答里列出你采用的假设。")
    answers = reply.get("answers") or []
    by_index: Dict[int, Dict[str, Any]] = {}
    for entry in answers:
        if not isinstance(entry, dict):
            continue
        try:
            index = int(entry.get("index"))
        except (TypeError, ValueError):
            continue
        by_index[index] = entry

    lines = ["用户已回答："]
    for index, question in enumerate(questions):
        answer = by_index.get(index) or {}
        picked = [str(item).strip() for item in (answer.get("picked") or []) if str(item).strip()]
        custom = str(answer.get("custom") or "").strip()
        parts = list(picked)
        if custom:
            parts.append("其它：" + custom)
        label = question.get("header") or ("Q%d" % (index + 1))
        if parts:
            lines.append("%s. %s → %s" % (index + 1, label, "；".join(parts)))
        else:
            lines.append("%s. %s → （未选择）" % (index + 1, label))
    return "\n".join(lines)


def make_handler(ask: AskCallback):
    """Build the tool handler; ``ask`` forwards the questions to the Shell."""

    async def handle(call: ToolCall) -> ToolResult:
        questions = normalize_questions((call.arguments or {}).get("questions"))
        if not questions:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="questions 不能为空：每题需要 question，可选 2~4 个 options")
        try:
            reply = await ask(questions)
        except Exception as exc:  # pragma: no cover - a broken Shell must not kill the turn
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="提问失败：%s。请基于合理假设继续，并标注假设。" % exc)
        if reply is None:
            return ToolResult(
                tool_call_id=call.id, ok=False,
                error=("当前没有可以接收提问的界面（或用户未在时限内回答）。"
                       "请基于合理假设继续完成任务，并在最终回答里列出你采用的假设。"))
        return ToolResult(tool_call_id=call.id, ok=True, content=render(questions, reply))

    return handle


def register_tools(registry: ToolRegistry, ask: AskCallback) -> None:
    registry.register(ASK_DEF, make_handler(ask))
