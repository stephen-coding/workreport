"""Tests for the parallel tool scheduler."""

import asyncio
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.scheduler import ToolScheduler  # noqa: E402
from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult  # noqa: E402
from agent_core.tools.registry import ToolRegistry  # noqa: E402


def _tool_def(name: str, timeout: float = 5.0) -> ToolDef:
    return ToolDef(name=name, description=name, permission=Permission.AUTO, timeout_sec=timeout)


class TestScheduler(unittest.IsolatedAsyncioTestCase):
    async def test_runs_tools_in_parallel(self):
        registry = ToolRegistry()

        async def slow_handler(call: ToolCall) -> ToolResult:
            await asyncio.sleep(0.1)
            return ToolResult(tool_call_id=call.id, ok=True, content="done:" + call.name)

        registry.register(_tool_def("slow"), slow_handler)
        scheduler = ToolScheduler(registry, max_concurrency=4)

        calls = [ToolCall(id="c%d" % i, name="slow") for i in range(4)]
        started = time.monotonic()
        results = await scheduler.run_parallel(calls)
        elapsed = time.monotonic() - started

        self.assertEqual(len(results), 4)
        self.assertTrue(all(r.ok for r in results))
        self.assertLess(elapsed, 0.35, "tools should run concurrently")

    async def test_unknown_tool_returns_error(self):
        scheduler = ToolScheduler(ToolRegistry())
        results = await scheduler.run_parallel([ToolCall(id="c1", name="nope")])
        self.assertFalse(results[0].ok)
        self.assertIn("未知工具", results[0].error)

    async def test_timeout_is_isolated(self):
        registry = ToolRegistry()

        async def slow_handler(call: ToolCall) -> ToolResult:
            await asyncio.sleep(1.0)
            return ToolResult(tool_call_id=call.id, ok=True)

        async def fast_handler(call: ToolCall) -> ToolResult:
            return ToolResult(tool_call_id=call.id, ok=True, content="fast")

        registry.register(_tool_def("slow", timeout=0.1), slow_handler)
        registry.register(_tool_def("fast", timeout=5.0), fast_handler)
        scheduler = ToolScheduler(registry, max_concurrency=4)

        results = await scheduler.run_parallel([
            ToolCall(id="c1", name="slow"),
            ToolCall(id="c2", name="fast"),
        ])

        self.assertFalse(results[0].ok)
        self.assertTrue(results[1].ok)
        self.assertEqual(results[1].content, "fast")

    async def test_start_and_end_callbacks(self):
        registry = ToolRegistry()
        events = []

        async def handler(call: ToolCall) -> ToolResult:
            return ToolResult(tool_call_id=call.id, ok=True, content="x")

        registry.register(_tool_def("t"), handler)
        scheduler = ToolScheduler(registry)

        async def on_start(call):
            events.append(("start", call.id))

        async def on_end(call, result):
            events.append(("end", call.id, result.ok))

        await scheduler.run_parallel([ToolCall(id="c1", name="t")],
                                     on_start=on_start, on_end=on_end)
        self.assertEqual(events, [("start", "c1"), ("end", "c1", True)])


if __name__ == "__main__":
    unittest.main()
