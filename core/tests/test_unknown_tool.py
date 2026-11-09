"""Unknown tool names must self-correct, and repeating one must stop the turn.

A bare "未知工具: bash" gives the model nothing to correct against, so it keeps
inventing names (bash / Grep / Read) and prints "let me use the right tool name"
until the iteration budget is gone. These tests pin both guards.
"""

import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.loop import UNKNOWN_TOOL_LIMIT, run_chat  # noqa: E402
from agent_core.agent.scheduler import ToolScheduler, unknown_tool_error  # noqa: E402
from agent_core.agent.session import Session  # noqa: E402
from agent_core.llm.base import ChatResult  # noqa: E402
from agent_core.protocol import ChatConfig, ToolCall, ToolDef, ToolResult  # noqa: E402
from agent_core.tools.registry import ToolRegistry  # noqa: E402


def _registry() -> ToolRegistry:
    registry = ToolRegistry()

    async def handler(call):
        return ToolResult(tool_call_id=call.id, ok=True, content="file body")

    registry.register(ToolDef(name="read_file", description="read"), handler)
    registry.register(ToolDef(name="search_code", description="search"), handler)
    return registry


class TestUnknownToolError(unittest.TestCase):

    def test_error_lists_the_tools_that_do_exist(self):
        text = unknown_tool_error(_registry(), "bash")
        self.assertIn("未知工具: bash", text)
        self.assertIn("read_file", text)
        self.assertIn("search_code", text)

    def test_scheduler_returns_the_same_hint(self):
        scheduler = ToolScheduler(_registry())
        calls = [ToolCall(id="c1", name="bash", arguments={"cmd": "git status"})]
        results = asyncio.run(scheduler.run_parallel(calls))
        self.assertFalse(results[0].ok)
        self.assertIn("可用工具", results[0].error)


class StubbornProvider:
    """Keeps asking for a name that is not in the registry."""

    provider_id = "stubborn"

    def __init__(self):
        self.rounds = 0

    async def chat_stream(self, messages, tools, config, on_token, on_tool_call):
        self.rounds += 1
        call = ToolCall(id="c%d" % self.rounds, name="bash", arguments={"cmd": "git status"})
        return ChatResult(tool_calls=[call])


class TestUnknownToolFuse(unittest.TestCase):

    def _run(self, max_iterations=40):
        session = Session("s1")
        provider = StubbornProvider()

        async def noop(_token):
            return None

        async def scenario():
            return await run_chat(session, "看一下 git status", ChatConfig(max_iterations=max_iterations),
                                  provider, noop, registry=_registry())

        return asyncio.run(scenario()), provider, session

    def test_turn_stops_after_repeated_unknown_tool(self):
        result, provider, _ = self._run()
        self.assertEqual(provider.rounds, UNKNOWN_TOOL_LIMIT)
        self.assertIn("不存在的工具", result.content)
        self.assertIn("bash", result.content)

    def test_each_failure_carries_the_available_names(self):
        _, _, session = self._run()
        tool_messages = [m for m in session.messages if m.role.value == "tool"]
        self.assertEqual(len(tool_messages), UNKNOWN_TOOL_LIMIT)
        for message in tool_messages:
            self.assertIn("可用工具", message.content)
            self.assertIn("read_file", message.content)


if __name__ == "__main__":
    unittest.main()
