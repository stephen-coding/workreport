"""Agent main loop: LLM <-> tool execution rounds with parallel tools."""

import logging
from typing import Awaitable, Callable, Optional

from agent_core.agent.compression import compress_tool_output
from agent_core.agent.permissions import PermissionTracker
from agent_core.agent.scheduler import ToolScheduler
from agent_core.agent.session import Session
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
) -> ChatResult:
    session.append(Message(role=Role.USER, content=user_input))
    tools = registry.defs() if registry is not None else None
    scheduler = ToolScheduler(registry, config.tool_concurrency) if registry is not None else None
    max_iterations = max(1, config.max_iterations)
    tracker = PermissionTracker()
    system_prompt = base_prompt or SYSTEM_PROMPT
    if memory_overview:
        system_prompt += ("\n\n以下是项目长期记忆索引（需要某条正文时调用 memory_recall）：\n"
                          + memory_overview)
    if skill_instruction:
        system_prompt += "\n\n" + skill_instruction

    for _ in range(max_iterations):
        messages = [Message(role=Role.SYSTEM, content=system_prompt)] + session.messages
        messages = trim_history(messages)
        if not any(m.role == Role.USER for m in messages):
            # vLLM answers 400 "No user query found in messages." when no user role
            # survives; re-anchor the current input instead of losing the turn.
            # 兜底：裁剪后如果一条 user 都没有，就把本轮输入补回去，避免 vLLM 直接 400。
            messages.append(Message(role=Role.USER, content=user_input))
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
            if tracker.record_result(tool_result.ok, tool_result.error or ""):
                denied_too_often = True

        if denied_too_often:
            stop_text = "连续多次未获得授权，已停止工具执行。请确认后重新发起请求。"
            session.append(Message(role=Role.ASSISTANT, content=stop_text))
            return ChatResult(content=stop_text)

    limit_text = ("（已达到最大迭代轮次 %d，任务可能未完成，回复「继续」可接着跑。）"
                  % max_iterations)
    session.append(Message(role=Role.ASSISTANT, content=limit_text))
    return ChatResult(content=limit_text)
