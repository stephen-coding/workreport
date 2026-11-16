"""Tests for the blocking ``ask_user`` tool."""

import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent import ask as ask_tools  # noqa: E402
from agent_core.agent.scheduler import ToolScheduler  # noqa: E402
from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult  # noqa: E402
from agent_core.tools.registry import ToolRegistry  # noqa: E402

QUESTIONS = [
    {
        "header": "缓存策略",
        "question": "缓存放哪一层？",
        "options": [
            {"label": "进程内 LRU", "description": "简单，重启即失效"},
            {"label": "Redis"},
        ],
    },
    {
        "header": "过期时间",
        "question": "过期时间怎么定？",
        "options": ["跟着会话走", "固定 10 分钟"],
        "multiSelect": True,
    },
]


class TestNormalize(unittest.TestCase):
    def test_single_dict_is_wrapped(self):
        got = ask_tools.normalize_questions({"question": "只有一个问题？", "options": ["A"]})
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["question"], "只有一个问题？")

    def test_questions_are_capped(self):
        raw = [{"question": "Q%d" % i, "options": ["A"]} for i in range(6)]
        self.assertEqual(len(ask_tools.normalize_questions(raw)), ask_tools.MAX_QUESTIONS)

    def test_options_are_capped(self):
        raw = [{"question": "Q", "options": ["A", "B", "C", "D", "E", "F"]}]
        options = ask_tools.normalize_questions(raw)[0]["options"]
        self.assertEqual(len(options), ask_tools.MAX_OPTIONS)

    def test_string_options_and_missing_description(self):
        options = ask_tools.normalize_questions(QUESTIONS)[0]["options"]
        self.assertEqual(options[1], {"label": "Redis", "description": ""})

    def test_question_without_text_is_dropped(self):
        raw = [{"header": "空", "options": ["A"]}, {"question": "  ", "options": ["A"]}]
        self.assertEqual(ask_tools.normalize_questions(raw), [])

    def test_free_form_question_keeps_empty_options(self):
        got = ask_tools.normalize_questions([{"question": "说点什么？"}])
        self.assertEqual(got[0]["options"], [])
        self.assertTrue(got[0]["allowCustom"])

    def test_allow_custom_can_be_turned_off(self):
        got = ask_tools.normalize_questions([{"question": "Q", "allowCustom": False}])
        self.assertFalse(got[0]["allowCustom"])

    def test_garbage_input(self):
        self.assertEqual(ask_tools.normalize_questions(None), [])
        self.assertEqual(ask_tools.normalize_questions("nope"), [])


class TestRender(unittest.TestCase):
    def test_picks_and_custom_answer(self):
        reply = {"answers": [
            {"index": 0, "picked": ["进程内 LRU"]},
            {"index": 1, "picked": [], "custom": "按天过期"},
        ]}
        text = ask_tools.render(QUESTIONS, reply)
        self.assertIn("缓存策略 → 进程内 LRU", text)
        self.assertIn("过期时间 → 其它：按天过期", text)

    def test_multi_select_joins_labels(self):
        reply = {"answers": [{"index": 1, "picked": ["跟着会话走", "固定 10 分钟"]}]}
        text = ask_tools.render(QUESTIONS, reply)
        self.assertIn("跟着会话走；固定 10 分钟", text)

    def test_missing_answer_marks_unchosen(self):
        text = ask_tools.render(QUESTIONS, {"answers": []})
        self.assertIn("（未选择）", text)

    def test_skipped_tells_model_to_assume(self):
        text = ask_tools.render(QUESTIONS, {"skipped": True})
        self.assertIn("跳过", text)
        self.assertIn("假设", text)


class TestHandler(unittest.IsolatedAsyncioTestCase):
    async def _handle(self, ask, arguments):
        registry = ToolRegistry()
        ask_tools.register_tools(registry, ask)
        return await registry.handler(ask_tools.ASK_TOOL)(
            ToolCall(id="c1", name=ask_tools.ASK_TOOL, arguments=arguments))

    async def test_answers_are_rendered_into_the_tool_result(self):
        seen = {}

        async def ask(questions):
            seen["questions"] = questions
            return {"answers": [{"index": 0, "picked": ["Redis"]}]}

        result = await self._handle(ask, {"questions": QUESTIONS})
        self.assertTrue(result.ok)
        self.assertIn("Redis", result.content)
        self.assertEqual(len(seen["questions"]), 2)

    async def test_empty_questions_is_an_error(self):
        async def ask(questions):
            raise AssertionError("must not reach the shell")

        result = await self._handle(ask, {"questions": []})
        self.assertFalse(result.ok)
        self.assertIn("questions 不能为空", result.error)

    async def test_no_ui_degrades_to_assumptions(self):
        async def ask(questions):
            return None

        result = await self._handle(ask, {"questions": QUESTIONS})
        self.assertFalse(result.ok)
        self.assertIn("没有可以接收提问的界面", result.error)

    async def test_broken_shell_does_not_kill_the_turn(self):
        async def ask(questions):
            raise RuntimeError("pipe closed")

        result = await self._handle(ask, {"questions": QUESTIONS})
        self.assertFalse(result.ok)
        self.assertIn("提问失败", result.error)
        self.assertIn("假设", result.error)


class TestInteractiveScheduling(unittest.IsolatedAsyncioTestCase):
    async def test_ask_user_runs_alone_before_the_rest(self):
        registry = ToolRegistry()
        events = []

        async def ask_handler(call: ToolCall) -> ToolResult:
            events.append("ask:start")
            await asyncio.sleep(0.05)
            events.append("ask:end")
            return ToolResult(tool_call_id=call.id, ok=True, content="answered")

        async def edit_handler(call: ToolCall) -> ToolResult:
            events.append("edit:start")
            return ToolResult(tool_call_id=call.id, ok=True, content="edited")

        registry.register(ToolDef(name="ask_user", description="", permission=Permission.AUTO),
                          ask_handler)
        registry.register(ToolDef(name="edit", description="", permission=Permission.AUTO),
                          edit_handler)

        calls = [
            ToolCall(id="c1", name="edit"),
            ToolCall(id="c2", name="ask_user"),
            ToolCall(id="c3", name="edit"),
        ]
        results = await ToolScheduler(registry, max_concurrency=4).run_parallel(calls)

        # The question must be over before any edit starts, and results keep call order.
        self.assertEqual(events, ["ask:start", "ask:end", "edit:start", "edit:start"])
        self.assertEqual([r.tool_call_id for r in results], ["c1", "c2", "c3"])
        self.assertEqual(results[1].content, "answered")


if __name__ == "__main__":
    unittest.main()
