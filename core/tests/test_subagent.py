"""Tests for subagent definitions, the parallel pool and the dispatch tool."""

import asyncio
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.subagent import (  # noqa: E402
    SubagentLoader,
    SubagentPool,
    SubagentReport,
    SubagentService,
    SubagentTask,
    parse_definition,
)
from agent_core.llm.base import ChatResult  # noqa: E402
from agent_core.protocol import ChatConfig, ToolCall, ToolDef  # noqa: E402
from agent_core.tools.registry import ToolRegistry  # noqa: E402

SAMPLE = """---
name: demo
description: 演示子代理
tools: [read_file, memory_recall, subagent_dispatch]
max_iterations: 3
---
# 指令
只输出结论。
"""


class FakeProvider:
    """Records the conversation and answers with a fixed text."""

    provider_id = "fake"

    def __init__(self, answer="子代理结论"):
        self.answer = answer
        self.calls = []

    async def chat_stream(self, messages, tools, config, on_token, on_tool_call):
        self.calls.append({"messages": list(messages), "tools": list(tools or []),
                           "max_iterations": config.max_iterations})
        await on_token(self.answer)
        return ChatResult(content=self.answer)


class TestLoader(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-subagent-")
        self.project = os.path.join(self.tmp, "project")
        self.global_dir = os.path.join(self.tmp, "global")
        os.makedirs(self.project)
        os.makedirs(self.global_dir)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, directory, name, text):
        path = os.path.join(directory, name, "SUBAGENT.md")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)

    def test_parse_definition(self):
        definition = parse_definition(SAMPLE, "p", "builtin")
        self.assertEqual(definition.name, "demo")
        self.assertEqual(definition.max_iterations, 3)
        self.assertIn("subagent_dispatch", definition.tools)
        self.assertIn("只输出结论", definition.body)
        self.assertIn("demo", definition.system_prompt())

    def test_builtin_definitions_are_loaded(self):
        loader = SubagentLoader(self.project, self.global_dir)
        names = {d.name for d in loader.list_definitions()}
        self.assertTrue({"code-reviewer", "bug-hunter", "test-generator"}.issubset(names))

    def test_project_definition_overrides_builtin(self):
        self._write(os.path.join(self.project, ".agent", "subagents"), "code-reviewer",
                    "---\nname: code-reviewer\ndescription: 自定义\ntools: [read_file]\n---\n自定义指令\n")
        loader = SubagentLoader(self.project, self.global_dir)
        definition = loader.get("code-reviewer")
        self.assertEqual(definition.scope, "project")
        self.assertIn("自定义指令", definition.body)

    def test_rebinding_project_root_rescans(self):
        loader = SubagentLoader(None, self.global_dir)
        self.assertIsNone(loader.get("demo"))
        self._write(os.path.join(self.project, ".agent", "subagents"), "demo", SAMPLE)
        loader.set_project_root(self.project)
        self.assertIsNotNone(loader.get("demo"))


class TestPool(unittest.TestCase):
    def test_parallel_dispatch_and_queueing(self):
        async def scenario():
            active = {"now": 0, "peak": 0}

            async def runner(task):
                active["now"] += 1
                active["peak"] = max(active["peak"], active["now"])
                await asyncio.sleep(0.05)
                active["now"] -= 1
                return SubagentReport(task_id=task.id, ok=True, summary="done")

            pool = SubagentPool(max_concurrent=2)
            tasks = [SubagentTask(id="t%d" % i, goal="g%d" % i) for i in range(4)]
            reports = await pool.dispatch(tasks, runner)
            self.assertEqual([r.task_id for r in reports], ["t0", "t1", "t2", "t3"])
            self.assertEqual(active["peak"], 2)

        asyncio.run(scenario())

    def test_runner_failure_becomes_ugly_report(self):
        async def runner(task):
            raise RuntimeError("boom")

        async def scenario():
            pool = SubagentPool(max_concurrent=1)
            reports = await pool.dispatch([SubagentTask(id="t1", goal="g")], runner)
            self.assertFalse(reports[0].ok)
            self.assertIn("boom", reports[0].summary)

        asyncio.run(scenario())

    def test_events_are_emitted(self):
        events = []

        async def runner(task):
            return SubagentReport(task_id=task.id, ok=True, summary="ok")

        async def on_event(kind, task, report=None):
            events.append((kind, task.id))

        async def scenario():
            pool = SubagentPool()
            await pool.dispatch([SubagentTask(id="t1", goal="g")], runner, on_event)
            self.assertEqual(events, [("start", "t1"), ("end", "t1")])

        asyncio.run(scenario())


class TestService(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-subagent-svc-")
        self.loader = SubagentLoader(self.tmp, os.path.join(self.tmp, "global"))
        self.registry = ToolRegistry()
        for name in ("read_file", "search_code", "git_diff", "git_log", "write_file"):
            self.registry.register(ToolDef(name=name, description=name), self._async_tool())
        self.provider = FakeProvider()
        self.config = ChatConfig(subagent_concurrency=2)
        self.service = SubagentService(self.loader, self.registry,
                                       get_config=lambda: self.config,
                                       get_provider=lambda: self.provider)
        self.service.register_tools(self.registry)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    @staticmethod
    def _async_tool():
        async def handler(call):
            from agent_core.protocol import ToolResult
            return ToolResult(tool_call_id=call.id, ok=True, content="ok")

        return handler

    def test_filtered_registry_drops_recursion_and_foreign_tools(self):
        filtered = self.service._filtered_registry(["read_file", "subagent_dispatch"])
        self.assertEqual(filtered.names(), ["read_file"])

    def test_dispatch_uses_definition_prompt_and_tool_whitelist(self):
        async def scenario():
            result = await self.service._dispatch_handler(
                ToolCall(id="c1", name="subagent_dispatch",
                         arguments={"type": "code-reviewer", "goals": ["看看这个文件"]}))
            return result

        result = asyncio.run(scenario())
        self.assertTrue(result.ok)
        self.assertIn("code-reviewer-1", result.content)
        self.assertIn("成功 1/1", result.content)
        sent_tools = {tool.name for tool in self.provider.calls[0]["tools"]}
        self.assertEqual(sent_tools, {"read_file", "search_code", "git_diff", "git_log"})
        self.assertEqual(self.provider.calls[0]["max_iterations"], 6)
        system_prompt = self.provider.calls[0]["messages"][0].content
        self.assertIn("子代理", system_prompt)

    def test_dispatch_unknown_type_falls_back_to_default_tools(self):
        async def scenario():
            return await self.service._dispatch_handler(
                ToolCall(id="c1", name="subagent_dispatch",
                         arguments={"type": "unknown", "goals": ["g"]}))

        result = asyncio.run(scenario())
        self.assertTrue(result.ok)
        sent_tools = {tool.name for tool in self.provider.calls[0]["tools"]}
        self.assertEqual(sent_tools, {"read_file", "search_code", "git_diff", "git_log"})

    def test_dispatch_requires_goals(self):
        async def scenario():
            return await self.service._dispatch_handler(
                ToolCall(id="c1", name="subagent_dispatch", arguments={"type": "code-reviewer"}))

        result = asyncio.run(scenario())
        self.assertFalse(result.ok)

    def test_list_handler(self):
        async def scenario():
            return await self.service._list_handler(
                ToolCall(id="c1", name="subagent_list"))

        result = asyncio.run(scenario())
        self.assertIn("code-reviewer", result.content)


if __name__ == "__main__":
    unittest.main()
