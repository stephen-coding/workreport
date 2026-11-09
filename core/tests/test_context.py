"""Tests for history trimming: rounds must be dropped as a whole."""

import unittest

from agent_core.llm.context import messages_tokens, trim_history
from agent_core.protocol import Message, Role, ToolCall


def _tool_round(round_no: int, size: int) -> list:
    call = ToolCall(id="c%d" % round_no, name="read_file", arguments={"path": "a.py"})
    return [
        Message(role=Role.ASSISTANT, content="", tool_calls=[call]),
        Message(role=Role.TOOL, tool_call_id=call.id, name=call.name, content="x" * size),
    ]


class TestTrimHistory(unittest.TestCase):

    def test_within_budget_is_untouched(self):
        messages = [Message(role=Role.SYSTEM, content="sys"),
                    Message(role=Role.USER, content="hi")]
        self.assertEqual(trim_history(messages), messages)

    def test_trimmed_round_takes_its_tool_results_with_it(self):
        messages = [Message(role=Role.SYSTEM, content="sys")]
        for index in range(20):
            messages.append(Message(role=Role.USER, content="u%d" % index))
            messages.extend(_tool_round(index, 400))

        full = messages_tokens(messages)
        trimmed = trim_history(messages, budget=full // 2)

        self.assertGreater(len(trimmed), 3)
        self.assertEqual(trimmed[0].role, Role.SYSTEM)
        # No orphan tool result and no tool_calls message missing its results.
        self.assertNotEqual(trimmed[1].role, Role.TOOL)
        pending = set()
        for message in trimmed:
            for call in message.tool_calls or []:
                pending.add(call.id)
            if message.role == Role.TOOL:
                self.assertIn(message.tool_call_id, pending)
                pending.discard(message.tool_call_id)
        self.assertEqual(pending, set())
        self.assertLessEqual(messages_tokens(trimmed), full // 2)

    def test_orphan_leading_tool_message_is_dropped(self):
        messages = [
            Message(role=Role.SYSTEM, content="sys"),
            Message(role=Role.TOOL, tool_call_id="gone", name="read_file", content="y" * 400),
            Message(role=Role.USER, content="next"),
        ]
        trimmed = trim_history(messages, budget=10)
        self.assertEqual([m.role for m in trimmed], [Role.SYSTEM, Role.USER])

    def test_user_prompt_survives_aggressive_trimming(self):
        # One long turn: every tool round outlives the opening prompt.
        messages = [Message(role=Role.SYSTEM, content="sys"),
                    Message(role=Role.USER, content="把整个仓库重构一遍")]
        for index in range(20):
            messages.extend(_tool_round(index, 400))

        trimmed = trim_history(messages, budget=200)

        self.assertEqual(trimmed[0].role, Role.SYSTEM)
        self.assertEqual(trimmed[1].role, Role.USER)
        self.assertEqual(trimmed[1].content, "把整个仓库重构一遍")
        self.assertLessEqual(messages_tokens(trimmed), 200)
        pending = set()
        for message in trimmed:
            for call in message.tool_calls or []:
                pending.add(call.id)
            if message.role == Role.TOOL:
                self.assertIn(message.tool_call_id, pending)
                pending.discard(message.tool_call_id)
        self.assertEqual(pending, set())

    def test_newest_user_turn_is_kept_while_older_ones_go(self):
        messages = [Message(role=Role.SYSTEM, content="sys")]
        for index in range(10):
            messages.append(Message(role=Role.USER, content="u%d" % index))
            messages.append(Message(role=Role.ASSISTANT, content="a" * 400))

        trimmed = trim_history(messages, budget=300)

        users = [m.content for m in trimmed if m.role == Role.USER]
        self.assertEqual(users[-1], "u9")
        self.assertNotIn("u0", users)


if __name__ == "__main__":
    unittest.main()
