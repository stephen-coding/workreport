"""Tests for subagent definitions, the parallel pool and the dispatch tool."""

import asyncio
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.loop import run_chat  # noqa: E402
from agent_core.agent.session import Session  # noqa: E402
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

SAMPLE_META = """---
name: meta
description: 带元数据的子代理
tools: [read_file, write_file, subagent_dispatch]
max_iterations: 2
output: json
model: tiny-model
timeout_sec: 90
---
# 指令
输出 JSON。
"""


class FakeProvider:
    """Records the conversation and answers with a fixed text."""

    provider_id = "fake"

    def __init__(self, answer="子代理结论"):
        self.answer = answer
        self.calls = []

    async def chat_stream(self, messages, tools, config, on_token, on_tool_call):
        self.calls.append({"messages": list(messages), "tools": list(tools or []),
                           "max_iterations": config.max_iterations,
                           "model": config.model})
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

    def test_parse_role_metadata(self):
        definition = parse_definition(SAMPLE_META, "p", "builtin")
        self.assertEqual(definition.output, "json")
        self.assertEqual(definition.model, "tiny-model")
        self.assertEqual(definition.timeout_sec, 90.0)
        # 写工具与递归派发一律被剔除
        self.assertEqual(definition.allowed_tools(), ["read_file"])
        self.assertIn("JSON", definition.system_prompt())

    def test_unknown_output_and_zero_timeout_fall_back(self):
        text = SAMPLE.replace("max_iterations: 3",
                              "max_iterations: 3\noutput: yaml\ntimeout_sec: 0")
        definition = parse_definition(text, "p", "builtin")
        self.assertEqual(definition.output, "text")
        self.assertEqual(definition.timeout_sec, 1.0)

    def test_builtin_definitions_are_loaded(self):
        loader = SubagentLoader(self.project, self.global_dir)
        names = {d.name for d in loader.list_definitions()}
        self.assertTrue({"code-reviewer", "bug-hunter", "test-generator",
                         "general"}.issubset(names))
        reviewer = loader.get("code-reviewer")
        self.assertEqual(reviewer.output, "bullets")
        self.assertGreater(reviewer.timeout_sec, 0)

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

    def test_dispatch_mixed_roles_in_one_call(self):
        async def scenario():
            return await self.service._dispatch_handler(ToolCall(
                id="c1", name="subagent_dispatch",
                arguments={"tasks": [
                    {"type": "code-reviewer", "goal": "审 a.py"},
                    {"type": "general", "goal": "查 b.py 的调用方",
                     "context": "b.py 在 core/src 下"},
                ]}))

        result = asyncio.run(scenario())
        self.assertTrue(result.ok)
        self.assertIn("code-reviewer-1", result.content)
        self.assertIn("general-1", result.content)
        self.assertEqual(len(self.provider.calls), 2)
        goals = [call["messages"][1].content for call in self.provider.calls]
        self.assertTrue(any("背景与相关线索" in goal for goal in goals))

    def test_dispatch_strips_write_tools_from_override(self):
        async def scenario():
            return await self.service._dispatch_handler(ToolCall(
                id="c1", name="subagent_dispatch",
                arguments={"tasks": [{"type": "general", "goal": "g",
                                       "tools": ["read_file", "write_file", "git_diff"]}]}))

        result = asyncio.run(scenario())
        self.assertTrue(result.ok)
        sent_tools = {tool.name for tool in self.provider.calls[0]["tools"]}
        self.assertEqual(sent_tools, {"read_file", "git_diff"})

    def test_role_model_and_iterations_override(self):
        directory = os.path.join(self.tmp, ".agent", "subagents", "cheap")
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, "SUBAGENT.md"), "w", encoding="utf-8") as handle:
            handle.write("---\nname: cheap\ndescription: 轻量角色\n"
                         "tools: [read_file]\nmax_iterations: 2\n"
                         "model: tiny-model\n---\n只给结论。\n")
        self.loader.load_all(refresh=True)

        async def scenario():
            return await self.service._dispatch_handler(
                ToolCall(id="c1", name="subagent_dispatch",
                         arguments={"type": "cheap", "goals": ["看 a.py"]}))

        result = asyncio.run(scenario())
        self.assertTrue(result.ok)
        self.assertEqual(self.provider.calls[0]["model"], "tiny-model")
        self.assertEqual(self.provider.calls[0]["max_iterations"], 2)
        self.assertIn("tiny-model", result.content)

    def test_timeout_becomes_failed_report(self):
        class SlowProvider(FakeProvider):
            async def chat_stream(self, messages, tools, config, on_token, on_tool_call):
                await asyncio.sleep(0.3)
                return await super().chat_stream(messages, tools, config,
                                                 on_token, on_tool_call)

        service = SubagentService(self.loader, self.registry,
                                  get_config=lambda: self.config,
                                  get_provider=lambda: SlowProvider())
        task = SubagentTask(id="slow-1", goal="g", role="general", timeout_sec=0.05)
        report = asyncio.run(service._run_task(task))
        self.assertFalse(report.ok)
        self.assertIn("超时", report.summary)
        self.assertEqual(report.role, "general")

    def test_catalog_lists_roles_and_policy(self):
        text = self.service.catalog_text()
        self.assertIn("code-reviewer", text)
        self.assertIn("只读", text)
        self.assertIn("何时委派", text)

    def test_list_handler(self):
        async def scenario():
            return await self.service._list_handler(
                ToolCall(id="c1", name="subagent_list"))

        result = asyncio.run(scenario())
        self.assertIn("code-reviewer", result.content)


class TestCatalogInjection(unittest.TestCase):
    def test_catalog_is_appended_to_system_prompt(self):
        provider = FakeProvider("你好")
        session = Session("s1")

        async def emit(_delta):
            return None

        async def scenario():
            return await run_chat(session, "hi", ChatConfig(), provider, emit,
                                  subagent_catalog="ROSTER-ROLES")

        asyncio.run(scenario())
        system_prompt = provider.calls[0]["messages"][0].content
        self.assertIn("ROSTER-ROLES", system_prompt)
        self.assertIn("编码助手", system_prompt)

    def test_without_catalog_prompt_is_unchanged(self):
        provider = FakeProvider("你好")
        session = Session("s1")

        async def emit(_delta):
            return None

        async def scenario():
            return await run_chat(session, "hi", ChatConfig(), provider, emit)

        asyncio.run(scenario())
        system_prompt = provider.calls[0]["messages"][0].content
        self.assertNotIn("ROSTER-ROLES", system_prompt)


if __name__ == "__main__":
    unittest.main()
