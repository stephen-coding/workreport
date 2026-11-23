"""Usage must be summed over every model round, not just the last one."""

import unittest

from agent_core.agent.loop import run_chat
from agent_core.agent.session import Session
from agent_core.llm.base import ChatResult
from agent_core.protocol import ChatConfig, ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry


async def _sink(token: str) -> None:
    return None


class FakeProvider:
    """Replays one ChatResult per request; the last one repeats if asked again."""

    def __init__(self, rounds):
        self._rounds = list(rounds)
        self.requests = 0

    async def chat_stream(self, messages, tools, config, on_token, on_tool_call,
                          on_reasoning=None):
        index = min(self.requests, len(self._rounds) - 1)
        self.requests += 1
        return self._rounds[index]


class TestUsageTotals(unittest.IsolatedAsyncioTestCase):

    async def test_usage_is_summed_across_tool_rounds(self):
        call = ToolCall(id="c1", name="echo", arguments={"text": "hi"})

        async def handler(tool_call):
            return ToolResult(tool_call_id=tool_call.id, ok=True, content="hi")

        registry = ToolRegistry()
        registry.register(ToolDef(name="echo", description="echo"), handler)

        provider = FakeProvider([
            ChatResult(content="", tool_calls=[call], usage={"in": 100, "out": 20}),
            ChatResult(content="done", usage={"in": 300, "out": 40}),
        ])

        result = await run_chat(Session("usage-test"), "hi", ChatConfig(), provider, _sink,
                                registry=registry)

        self.assertEqual(result.content, "done")
        # The last round is still reported as-is for older shells.
        self.assertEqual(result.usage, {"in": 300, "out": 40})
        self.assertEqual(result.usage_total, {"in": 400, "out": 60, "total": 460})

    async def test_missing_usage_stays_none(self):
        provider = FakeProvider([ChatResult(content="plain")])
        result = await run_chat(Session("usage-none"), "hi", ChatConfig(), provider, _sink)
        self.assertIsNone(result.usage_total)

    async def test_openai_style_total_key_is_kept(self):
        provider = FakeProvider([ChatResult(content="x", usage={"in": 10, "out": 5, "total": 99})])
        result = await run_chat(Session("usage-total"), "hi", ChatConfig(), provider, _sink)
        self.assertEqual(result.usage_total, {"in": 10, "out": 5, "total": 99})


if __name__ == "__main__":
    unittest.main()
