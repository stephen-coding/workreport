"""Agent main loop: LLM <-> tool execution rounds with parallel tools."""

import logging
from collections import Counter
from typing import Awaitable, Callable, Optional

from agent_core.agent.compression import compress_tool_output
from agent_core.agent.permissions import PermissionTracker
from agent_core.agent.scheduler import ToolScheduler
from agent_core.agent.session import Session
from agent_core.agent.status import TurnStats
from agent_core.llm.base import ChatResult, TokenCallback
from agent_core.llm.context import trim_history
from agent_core.protocol import ChatConfig, Message, Role, ToolCall, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "你是嵌入在 IntelliJ 系列 IDE 与 VSCode 中的 AI 编码助手。"
    "回答要简洁、准确、可执行；涉及代码时给出完整可运行的片段，保留原有技术术语。"
    "可以使用提供的工具读取文件、列出目录、搜索代码、查看 Git 记录；"
    "需要了解代码时优先调用工具获取事实，不要凭空猜测。"
    "修改文件前先用 read_file 确认原文；不要捏造事实。"
    "不确定的信息要明确说明，不要捏造。"
)

ToolStartCallback = Callable[[ToolCall], Awaitable[None]]
ToolEndCallback = Callable[[ToolCall, ToolResult], Awaitable[None]]
NoticeCallback = Callable[[str], Awaitable[None]]

# Stop the turn once the model kept asking for a tool that does not exist.
# Without this, a model that insists on "bash" burns the whole iteration budget
# printing "let me use the correct tool name" over and over.
# 同一个不存在的工具名连续失败这么多次就结束本轮，避免把轮次预算耗在空转上。
UNKNOWN_TOOL_LIMIT = 3


async def _noop_tool_call(call: ToolCall) -> None:
    # Tool calls are collected from the final accumulator and executed by the
    # scheduler; the streaming callback itself needs no work.
    return None


def _tool_path(call: ToolCall) -> Optional[str]:
    path = (call.arguments or {}).get("path")
    return path if isinstance(path, str) else None


async def _flush_notice(provider, on_notice: Optional[NoticeCallback]) -> None:
    """Forward a provider warning (e.g. tools disabled) to the Shell once."""
    consume = getattr(provider, "consume_notice", None)
    if consume is None:
        return
    text = consume()
    if text and on_notice is not None:
        await on_notice(text)


async def run_chat(
    session: Session,
    user_input: str,
    config: ChatConfig,
    provider,
    emit_token: TokenCallback,
    registry: Optional[ToolRegistry] = None,
    on_tool_start: Optional[ToolStartCallback] = None,
    on_tool_end: Optional[ToolEndCallback] = None,
    on_notice: Optional[NoticeCallback] = None,
    memory_overview: Optional[str] = None,
    skill_instruction: Optional[str] = None,
    base_prompt: Optional[str] = None,
    subagent_catalog: Optional[str] = None,
) -> ChatResult:
    session.append(Message(role=Role.USER, content=user_input))
    tools = registry.defs() if registry is not None else None
    scheduler = ToolScheduler(registry, config.tool_concurrency) if registry is not None else None
    max_iterations = max(1, config.max_iterations)
    tracker = PermissionTracker()
    stats = TurnStats()
    unknown_tools: Counter = Counter()
    system_prompt = base_prompt or SYSTEM_PROMPT
    # The roster is generated from the loaded SUBAGENT.md files, so adding a role
    # never means touching this loop; the main agent just learns it can delegate.
    # 角色清单由加载器生成后注入：主代理因此知道自己可以编排哪些专家。
    if subagent_catalog:
        system_prompt += "\n\n" + subagent_catalog
    if memory_overview:
        system_prompt += ("\n\n以下是项目长期记忆索引（需要某条正文时调用 memory_recall）：\n"
                          + memory_overview)
    if skill_instruction:
        system_prompt += "\n\n" + skill_instruction

    for _ in range(max_iterations):
        stats.begin_round()
        messages = [Message(role=Role.SYSTEM, content=system_prompt)] + session.messages
        messages = trim_history(messages)
        if not any(m.role == Role.USER for m in messages):
            # vLLM answers 400 "No user query found in messages." when no user role
            # survives; re-anchor the current input instead of losing the turn.
            # 兜底：裁剪后如果一条 user 都没有，就把本轮输入补回去，避免 vLLM 直接 400。
            messages.append(Message(role=Role.USER, content=user_input))
        # The status bar is appended after trimming, so it is never the thing that
        # gets dropped, and it only touches the tail of the prefix.
        # 状态栏在裁剪之后追加：既不会被裁掉，也只改动前缀末尾。
        status = stats.render(max_iterations)
        if status:
            messages.append(Message(role=Role.USER, content=status))
        result = await provider.chat_stream(
            messages,
            tools=tools,
            config=config,
            on_token=emit_token,
            on_tool_call=_noop_tool_call,
        )
        await _flush_notice(provider, on_notice)

        if not result.tool_calls:
            session.append(Message(role=Role.ASSISTANT, content=result.content))
            return result

        # One tool round: keep the assistant message with calls, then the results.
        # 一轮工具调用：保留含 tool_calls 的 assistant 消息，再注入工具结果。
        session.append(Message(role=Role.ASSISTANT, content=result.content,
                                tool_calls=result.tool_calls))
        stats.record_calls(result.tool_calls)
        tool_results = await scheduler.run_parallel(
            result.tool_calls, on_start=on_tool_start, on_end=on_tool_end)
        denied_too_often = False
        for call, tool_result in zip(result.tool_calls, tool_results):
            if tool_result.ok:
                content = compress_tool_output(call.name, tool_result.content or "",
                                               _tool_path(call))
            else:
                content = "ERROR: " + (tool_result.error or "tool failed")
            session.append(Message(role=Role.TOOL, tool_call_id=call.id,
                                   name=call.name, content=content))
            stats.record_result(call, tool_result.ok, tool_result.error or "")
            if not tool_result.ok and (tool_result.error or "").startswith("未知工具"):
                unknown_tools[call.name] += 1
            if tracker.record_result(tool_result.ok, tool_result.error or ""):
                denied_too_often = True

        # A chatty model can repeat one invented tool name for dozens of rounds;
        # stop early with an explanation instead of silently burning the budget.
        # 模型反复要求不存在的工具时提前停下，并把原因告诉用户。
        stuck = [name for name, count in unknown_tools.items() if count >= UNKNOWN_TOOL_LIMIT]
        if stuck:
            stop_text = ("模型反复要求调用不存在的工具「%s」（已尝试 %d 次），已停止本轮。"
                         "可用工具里没有它，换一个支持工具调用的模型，或直接告诉我用哪个工具。"
                         % (stuck[0], unknown_tools[stuck[0]]))
            session.append(Message(role=Role.ASSISTANT, content=stop_text))
            return ChatResult(content=stop_text)

        if denied_too_often:
            stop_text = "连续多次未获得授权，已停止工具执行。请确认后重新发起请求。"
            session.append(Message(role=Role.ASSISTANT, content=stop_text))
            return ChatResult(content=stop_text)

    limit_text = ("（已达到最大迭代轮次 %d，任务可能未完成，回复「继续」可接着跑。）"
                  % max_iterations)
    session.append(Message(role=Role.ASSISTANT, content=limit_text))
    return ChatResult(content=limit_text)
