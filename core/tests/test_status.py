"""Tests for the agent status bar (counters appended to the request tail)."""

import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.loop import run_chat  # noqa: E402
from agent_core.agent.session import Session  # noqa: E402
from agent_core.agent.status import TurnStats  # noqa: E402
from agent_core.llm.base import ChatResult  # noqa: E402
from agent_core.protocol import (  # noqa: E402
    ChatConfig,
    Role,
    ToolCall,
    ToolDef,
    ToolResult,
)
from agent_core.tools.registry import ToolRegistry  # noqa: E402


def _call(name="read_file", path="a.py", call_id="c1"):
    return ToolCall(id=call_id, name=name, arguments={"path": path} if path else {})


class TestTurnStats(unittest.TestCase):

    def test_idle_turn_has_no_block(self):
        stats = TurnStats()
        stats.begin_round()
        self.assertEqual(stats.render(10), "")

    def test_counts_tools_and_flags_repeats(self):
        stats = TurnStats()
        stats.begin_round()
        stats.record_calls([_call(), _call(call_id="c2"), _call(name="edit_file")])
        stats.begin_round()
        text = stats.render(10)
        self.assertIn("<agent_status>", text)
        self.assertIn("轮次: 2/10", text)
        self.assertIn("read_file 2", text)
        self.assertIn("重复: read_file(a.py) ×2", text)
        # A single edit is not a repeat.
        self.assertNotIn("edit_file(a.py)", text)

    def test_repeats_are_found_even_when_one_off_calls_dominate(self):
        stats = TurnStats()
        stats.begin_round()
        stats.record_calls([_call(path="a.py"), _call(path="b.py", call_id="c2"),
                            _call(path="c.py", call_id="c3"), _call(path="d.py", call_id="c4")])
        stats.begin_round()
        stats.record_calls([_call(path="z.py", call_id="c5"), _call(path="z.py", call_id="c6")])
        text = stats.render(5)
        self.assertIn("重复: read_file(z.py) ×2", text)
        # Four single calls are not repeats, so the line must not exist at all.
        self.assertNotIn("a.py)", text)

    def test_repeat_list_is_capped_at_three(self):
        stats = TurnStats()
        stats.begin_round()
        for index in range(5):
            one = _call(path="f%d.py" % index, call_id="x%d" % index)
            stats.record_calls([one, one])
        text = stats.render(5)
        self.assertEqual(text.count("×2"), 3)

    def test_failure_keeps_clipped_error(self):
        stats = TurnStats()
        stats.begin_round()
        stats.record_calls([_call()])
        stats.record_result(_call(), False, "ERROR: " + "x" * 200)
        text = stats.render(5)
        self.assertIn("失败: read_file ×1", text)
        self.assertIn("…", text)
        self.assertLess(len(text), 400)

    def test_edited_files_are_deduped_in_order(self):
        stats = TurnStats()
        stats.begin_round()
        stats.record_calls([_call("edit_file", "src/A.java")])
        stats.record_result(_call("edit_file", "src/A.java"), True)
        stats.record_result(_call("edit_file", "src/A.java"), True)
        stats.record_result(_call("write_file", "src/B.java"), True)
        text = stats.render(5)
        self.assertIn("已改: A.java、B.java", text)
        self.assertEqual(text.count("A.java"), 1)


class FakeProvider:
    """Answers with one tool call, then with plain text."""

    provider_id = "fake"

    def __init__(self):
        self.calls = []

    async def chat_stream(self, messages, tools, config, on_token, on_tool_call):
        self.calls.append(list(messages))
        if len(self.calls) == 1:
            return ChatResult(tool_calls=[_call()])
        await on_token("done")
        return ChatResult(content="done")


class TestLoopInjection(unittest.TestCase):

    def _run(self):
        registry = ToolRegistry()

        async def handler(call):
            return ToolResult(tool_call_id=call.id, ok=True, content="file body")

        registry.register(ToolDef(name="read_file", description="read"), handler)
        session = Session("s1")
        provider = FakeProvider()

        async def noop(_token):
            return None

        async def scenario():
            return await run_chat(session, "读一下 a.py", ChatConfig(max_iterations=6),
                                  provider, noop, registry=registry)

        result = asyncio.run(scenario())
        return result, provider, session

    def test_status_appears_only_on_later_rounds(self):
        _, provider, _ = self._run()
        first = provider.calls[0]
        self.assertNotIn("agent_status", first[-1].content or "")
        second = provider.calls[1]
        self.assertEqual(second[-1].role, Role.USER)
        self.assertIn("<agent_status>", second[-1].content)
        self.assertIn("轮次: 2/6", second[-1].content)
        self.assertIn("read_file 1", second[-1].content)

    def test_system_prefix_is_stable_and_history_stays_clean(self):
        _, provider, session = self._run()
        first_system = provider.calls[0][0].content
        second_system = provider.calls[1][0].content
        self.assertEqual(first_system, second_system)
        self.assertNotIn("agent_status", first_system)
        # The status block is per-request only: it must never enter the transcript.
        self.assertFalse(any("agent_status" in (m.content or "") for m in session.messages))


if __name__ == "__main__":
    unittest.main()
