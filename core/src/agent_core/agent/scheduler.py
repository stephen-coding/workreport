"""Parallel tool scheduler.

All tool calls returned in one LLM response are executed concurrently (bounded
by ``tool_concurrency`` and each tool's own timeout). Failing or timing-out
branches produce error results instead of propagating exceptions.
"""

import asyncio
import logging
from typing import Awaitable, Callable, List, Optional

from agent_core.protocol import ToolCall, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

StartCallback = Callable[[ToolCall], Awaitable[None]]
EndCallback = Callable[[ToolCall, ToolResult], Awaitable[None]]

# How many tool names an "unknown tool" error may advertise.
# 未知工具报错里最多列出多少个可用工具名。
MAX_NAME_HINTS = 12


def unknown_tool_error(registry: ToolRegistry, name: str) -> str:
    """Name the tools that do exist.

    A bare "未知工具: bash" leaves the model with nothing to correct against, so it
    keeps inventing names (bash / Grep / Read ...) until the iteration budget is
    gone. Listing the real names turns the error into a self-correcting hint.
    只报“未知工具: bash”等于让模型盲猜；把真实工具名列出来，它才能一次改对。
    """
    names = registry.names()
    hint = ", ".join(names[:MAX_NAME_HINTS])
    if len(names) > MAX_NAME_HINTS:
        hint += " 等 %d 个" % len(names)
    return "未知工具: %s。可用工具: %s" % (name, hint or "无")


class ToolScheduler:
    def __init__(self, registry: ToolRegistry, max_concurrency: int = 4) -> None:
        self.registry = registry
        self.max_concurrency = max(1, max_concurrency)

    async def run_parallel(
        self,
        calls: List[ToolCall],
        on_start: Optional[StartCallback] = None,
        on_end: Optional[EndCallback] = None,
    ) -> List[ToolResult]:
        semaphore = asyncio.Semaphore(self.max_concurrency)

        async def run_one(call: ToolCall) -> ToolResult:
            tool_def = self.registry.get(call.name)
            if tool_def is None:
                return ToolResult(tool_call_id=call.id, ok=False,
                                  error=unknown_tool_error(self.registry, call.name))
            handler = self.registry.handler(call.name)
            if handler is None:
                return ToolResult(tool_call_id=call.id, ok=False, error="工具未实现: " + call.name)

            async with semaphore:
                if on_start is not None:
                    await on_start(call)
                try:
                    result = await asyncio.wait_for(handler(call), timeout=tool_def.timeout_sec)
                except asyncio.TimeoutError:
                    result = ToolResult(tool_call_id=call.id, ok=False, error="工具执行超时")
                except asyncio.CancelledError:
                    raise
                except Exception as exc:  # pragma: no cover - defensive
                    LOGGER.exception("tool %s failed", call.name)
                    result = ToolResult(tool_call_id=call.id, ok=False, error=str(exc))

            if on_end is not None:
                await on_end(call, result)
            return result

        return list(await asyncio.gather(*(run_one(call) for call in calls)))
