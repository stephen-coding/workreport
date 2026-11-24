"""Agent main loop: LLM <-> tool execution rounds with parallel tools."""

import asyncio
import logging
import time
from collections import Counter
from typing import Awaitable, Callable, Optional

from agent_core import diagnostics
from agent_core.agent.compression import compress_tool_output
from agent_core.agent.permissions import PermissionTracker
from agent_core.agent.scheduler import ToolScheduler
from agent_core.agent.session import Session
from agent_core.agent.status import TurnStats
from agent_core.agent.todos import TODO_TOOL
from agent_core.llm.base import ChatResult, TokenCallback
from agent_core.llm.context import budget_for, trim_history
from agent_core.protocol import ChatConfig, Message, Role, ToolCall, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "你是嵌入在 IntelliJ 系列 IDE 与 VSCode 中的 AI 编码助手。"
    "回答要简洁、准确、可执行；涉及代码时给出完整可运行的片段，保留原有技术术语。"
    "可以使用提供的工具读取文件、列出目录、搜索代码、查看 Git 记录；"
    "需要了解代码时优先调用工具获取事实，不要凭空猜测。"
    "修改文件前先用 read_file 确认原文；不要捏造事实。"
    "改完代码后用 run_command 跑构建或测试验证（例如 mvn -q test、python -m unittest）；"
    "删除文件用 delete_file 逐个确认，推送与历史重写交给用户本人。"
    "任务需要 3 步以上时，先用 todo_write 列清单；开始做某项时置为 in_progress，"
    "完成后置为 completed，且每完成一项就立刻回写一次清单；同一时间最多一个 in_progress。"
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


def _int(value) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _add_usage(total: dict, usage: Optional[dict]) -> None:
    """Accumulate one round; providers report a subset of the keys."""
    if not isinstance(usage, dict):
        return
    in_tokens = _int(usage.get("in"))
    out_tokens = _int(usage.get("out"))
    total["in"] += in_tokens
    total["out"] += out_tokens
    total["total"] += _int(usage.get("total")) or (in_tokens + out_tokens)


def _usage_snapshot(total: dict) -> Optional[dict]:
    # None lets the Shell hide the line when the endpoint never reports usage.
    # 端点不报用量时返回 None，界面就可以不显示这一行。
    if not (total["in"] or total["out"] or total["total"]):
        return None
    return dict(total)


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
    on_reasoning: Optional[TokenCallback] = None,
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
    usage_total = {"in": 0, "out": 0, "total": 0}
    unknown_tools: Counter = Counter()
    # Round that last wrote the checklist; 0 means "not yet in this turn".
    # 最近一次回写清单的轮次；0 表示本轮还没写过。
    todo_round = 0
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

    for round_index in range(max_iterations):
        stats.begin_round()
        diagnostics.emit("round", round=round_index + 1, maxRounds=max_iterations,
                         history=len(session.messages), tools=len(tools or []))
        trace = diagnostics.current()
        if trace is not None:
            trace.count("rounds")
        messages = [Message(role=Role.SYSTEM, content=system_prompt)] + session.messages
        messages = trim_history(messages, budget_for(config.context_window))
        if not any(m.role == Role.USER for m in messages):
            # vLLM answers 400 "No user query found in messages." when no user role
            # survives; re-anchor the current input instead of losing the turn.
            # 兜底：裁剪后如果一条 user 都没有，就把本轮输入补回去，避免 vLLM 直接 400。
            messages.append(Message(role=Role.USER, content=user_input))
        # The status bar is appended after trimming, so it is never the thing that
        # gets dropped, and it only touches the tail of the prefix.
        # 状态栏在裁剪之后追加：既不会被裁掉，也只改动前缀末尾。
        #
        # It also repeats the checklist: the model then sees what is still open and how
        # long it has been since the last todo_write, which is what keeps the list live.
        # 状态栏同时重复清单与“多少轮未回写”，这才是让清单活在进程里的驱动。
        stale_rounds = (round_index + 1 - todo_round) if session.todos else 0
        status = stats.render(max_iterations, todos=session.todos,
                              todo_inherited=round_index == 0,
                              stale_rounds=stale_rounds)
        if status:
            messages.append(Message(role=Role.USER, content=status))
        started = time.monotonic()
        try:
            result = await provider.chat_stream(
                messages,
                tools=tools,
                config=config,
                on_token=emit_token,
                on_tool_call=_noop_tool_call,
                on_reasoning=on_reasoning,
            )
        except asyncio.CancelledError:
            diagnostics.emit("llm_cancel", round=round_index + 1,
                             elapsedMs=int((time.monotonic() - started) * 1000))
            raise
        except Exception as exc:
            # The transport layer already shaped the user-facing text; here we keep the
            # round number and timing so a failed turn can be told apart from a slow one.
            # 运输层已把错误写成用户能看懂的文本；这里记录轮次与耗时，便于区分“挂起”与“报错”。
            LOGGER.warning("LLM call failed on round %d after %dms: %s",
                           round_index + 1, int((time.monotonic() - started) * 1000), exc)
            diagnostics.emit("llm_error", round=round_index + 1,
                             elapsedMs=int((time.monotonic() - started) * 1000),
                             model=config.model, error=diagnostics.brief(exc))
            raise
        llm_ms = int((time.monotonic() - started) * 1000)
        await _flush_notice(provider, on_notice)
        _add_usage(usage_total, result.usage)
        trace = diagnostics.current()
        if trace is not None:
            trace.count("llmCalls")
        LOGGER.debug("round %d: llm %dms, tool calls=%d", round_index + 1, llm_ms,
                     len(result.tool_calls))
        diagnostics.emit("llm_round", round=round_index + 1, elapsedMs=llm_ms,
                         toolCalls=len(result.tool_calls), model=config.model,
                         usage=result.usage)

        if not result.tool_calls:
            diagnostics.emit("llm_final", round=round_index + 1)
            session.append(Message(role=Role.ASSISTANT, content=result.content))
            result.usage_total = _usage_snapshot(usage_total)
            return result

        # One tool round: keep the assistant message with calls, then the results.
        # 一轮工具调用：保留含 tool_calls 的 assistant 消息，再注入工具结果。
        session.append(Message(role=Role.ASSISTANT, content=result.content,
                                tool_calls=result.tool_calls))
        stats.record_calls(result.tool_calls)
        if any(call.name == TODO_TOOL for call in result.tool_calls):
            todo_round = round_index + 1
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
            LOGGER.warning("stopping: tool %r does not exist (asked %d times)",
                           stuck[0], unknown_tools[stuck[0]])
            diagnostics.emit("llm_stop", reason="unknown-tool-loop", tool=stuck[0],
                             attempts=unknown_tools[stuck[0]])
            stop_text = ("模型反复要求调用不存在的工具「%s」（已尝试 %d 次），已停止本轮。"
                         "可用工具里没有它，换一个支持工具调用的模型，或直接告诉我用哪个工具。"
                         % (stuck[0], unknown_tools[stuck[0]]))
            session.append(Message(role=Role.ASSISTANT, content=stop_text))
            return ChatResult(content=stop_text,
                              usage_total=_usage_snapshot(usage_total))

        if denied_too_often:
            LOGGER.warning("stopping: tool calls kept being denied")
            diagnostics.emit("llm_stop", reason="denied-too-often")
            stop_text = "连续多次未获得授权，已停止工具执行。请确认后重新发起请求。"
            session.append(Message(role=Role.ASSISTANT, content=stop_text))
            return ChatResult(content=stop_text,
                              usage_total=_usage_snapshot(usage_total))

    LOGGER.warning("iteration budget exhausted after %d rounds", max_iterations)
    diagnostics.emit("llm_stop", reason="max-iterations", rounds=max_iterations)
    limit_text = ("（已达到最大迭代轮次 %d，任务可能未完成，回复「继续」可接着跑。）"
                  % max_iterations)
    session.append(Message(role=Role.ASSISTANT, content=limit_text))
    return ChatResult(content=limit_text, usage_total=_usage_snapshot(usage_total))
