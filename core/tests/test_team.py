"""Tests for team definitions, capability grants and the team tools."""

import asyncio
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent import team as team_mod  # noqa: E402
from agent_core.agent.session import Session  # noqa: E402
from agent_core.agent.subagent import (  # noqa: E402
    CAPABILITY_COMMAND,
    CAPABILITY_READONLY,
    CAPABILITY_WRITE,
    SubagentLoader,
    SubagentService,
    strip_by_capability,
)
from agent_core.agent.team import (  # noqa: E402
    TeamLoader,
    TeamService,
    parse_team,
)
from agent_core.llm.base import ChatResult  # noqa: E402
from agent_core.protocol import ChatConfig, Message, Role, ToolCall, ToolDef  # noqa: E402
from agent_core.tools.registry import ToolRegistry  # noqa: E402

SAMPLE_TEAM = """---
name: mini-team
description: 只有两名成员的演示团队
lead: 项目经理
members:
  - role: researcher
    capability: readonly
    alias: 调研员
  - role: fullstack-engineer
    capability: write
    alias: 全栈工程师
  - role: test-engineer
    capability: bogus
---
# 协作指令
先调研，再实现。
"""

TOOL_NAMES = ("read_file", "list_dir", "search_code", "git_log", "git_diff",
              "memory_recall", "skill_load", "write_file", "edit_file",
              "revert_file", "diagnostics", "run_command")


class FakeProvider:
    provider_id = "fake"

    def __init__(self, answer="成员结论"):
        self.answer = answer
        self.calls = []

    async def chat_stream(self, messages, tools, config, on_token, on_tool_call,
                          on_reasoning=None):
        self.calls.append({"tools": {tool.name for tool in (tools or [])},
                           "system": messages[0].content if messages else ""})
        await on_token(self.answer)
        return ChatResult(content=self.answer)


def _call(name, **arguments):
    return ToolCall(id="c1", name=name, arguments=arguments)


class TestParsing(unittest.TestCase):
    def test_parse_team_members_and_body(self):
        team = parse_team(SAMPLE_TEAM, "p", "builtin")
        self.assertEqual(team.name, "mini-team")
        self.assertEqual(team.lead, "项目经理")
        self.assertEqual(len(team.members), 3)
        self.assertEqual(team.members[0].display_name(), "调研员")
        self.assertEqual(team.members[1].normalised_capability(), CAPABILITY_WRITE)
        # An illegal capability degrades to read-only instead of granting anything.
        self.assertEqual(team.members[2].normalised_capability(), CAPABILITY_READONLY)
        self.assertIn("先调研", team.body)

    def test_parse_team_without_name_is_ignored(self):
        self.assertIsNone(parse_team("---\ndescription: x\n---\nbody", "p", "builtin"))
        self.assertIsNone(parse_team("# 没有 frontmatter", "p", "builtin"))

    def test_member_lookup_by_role_alias_or_display_name(self):
        team = parse_team(SAMPLE_TEAM, "p", "builtin")
        self.assertIsNotNone(team.member("researcher"))
        self.assertIsNotNone(team.member("调研员"))
        self.assertIsNotNone(team.member("fullstack-engineer"))
        self.assertIsNone(team.member("nobody"))
        self.assertIsNone(team.member(""))


class TestCapabilityMatrix(unittest.TestCase):
    def test_readonly_strips_write_and_command(self):
        tools = ["read_file", "write_file", "edit_file", "run_command", "subagent_dispatch"]
        self.assertEqual(strip_by_capability(tools, CAPABILITY_READONLY), ["read_file"])

    def test_write_grants_file_edits_but_not_terminal(self):
        tools = ["read_file", "write_file", "edit_file", "revert_file",
                 "run_command", "delete_file", "subagent_dispatch"]
        kept = strip_by_capability(tools, CAPABILITY_WRITE)
        self.assertIn("write_file", kept)
        self.assertIn("edit_file", kept)
        self.assertIn("revert_file", kept)
        self.assertNotIn("run_command", kept)
        self.assertNotIn("delete_file", kept)
        self.assertNotIn("subagent_dispatch", kept)

    def test_command_grants_terminal_but_never_deletion(self):
        tools = ["read_file", "write_file", "run_command", "delete_file"]
        kept = strip_by_capability(tools, CAPABILITY_COMMAND)
        self.assertIn("run_command", kept)
        self.assertIn("write_file", kept)
        self.assertNotIn("delete_file", kept)

    def test_unknown_capability_behaves_like_readonly(self):
        self.assertEqual(strip_by_capability(["read_file", "write_file"], "root"), ["read_file"])

    def test_d1_guardrail_keeps_plain_dispatch_read_only(self):
        """A temp subagent must stay read-only even if its file lists write tools."""
        loader = SubagentLoader()
        definition = loader.get("fullstack-engineer")
        self.assertNotIn("write_file", definition.allowed_tools())
        self.assertNotIn("run_command", definition.allowed_tools())


class TestLoader(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-team-")
        self.project = os.path.join(self.tmp, "project")
        self.global_dir = os.path.join(self.tmp, "global")
        os.makedirs(self.project)
        os.makedirs(self.global_dir)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, directory, name, text):
        path = os.path.join(directory, name, "TEAM.md")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)

    def test_builtin_software_team(self):
        loader = TeamLoader(self.project, self.global_dir)
        team = loader.get("software-dev-team")
        self.assertIsNotNone(team)
        roles = [member.role for member in team.members]
        self.assertEqual(roles, ["researcher", "fullstack-engineer",
                                 "test-engineer", "code-reviewer"])
        self.assertIn("由项目经理协调调研", team.description)
        self.assertIn("未经用户授权，不提交、推送、发布", team.body)

    def test_project_team_overrides_builtin(self):
        self._write(os.path.join(self.project, ".agent", "teams"), "software-dev-team",
                    "---\nname: software-dev-team\ndescription: 自定义团队\n---\n自定义剧本\n")
        loader = TeamLoader(self.project, self.global_dir)
        team = loader.get("software-dev-team")
        self.assertEqual(team.scope, "project")
        self.assertIn("自定义剧本", team.body)

    def test_rebinding_project_root_rescans(self):
        loader = TeamLoader(None, self.global_dir)
        self.assertIsNone(loader.get("mini-team"))
        self._write(os.path.join(self.project, ".agent", "teams"), "mini-team", SAMPLE_TEAM)
        loader.set_project_root(self.project)
        self.assertIsNotNone(loader.get("mini-team"))


class _TeamBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-team-svc-")
        self.registry = ToolRegistry()
        for name in TOOL_NAMES:
            self.registry.register(ToolDef(name=name, description=name), self._echo())
        self.provider = FakeProvider()
        self.config = ChatConfig(active_team="software-dev-team")
        self.subagent_loader = SubagentLoader(self.tmp, os.path.join(self.tmp, "global"))
        self.subagents = SubagentService(
            self.subagent_loader, self.registry,
            get_config=lambda: self.config, get_provider=lambda: self.provider)
        self.loader = TeamLoader(self.tmp, os.path.join(self.tmp, "global"))
        self.service = TeamService(
            self.loader, self.subagents, get_config=lambda: self.config,
            preapprove_fn=self._approve)
        self.approved = True
        self.preapprove_payloads = []
        self.service.register_tools(self.registry)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    async def _approve(self, payload):
        self.preapprove_payloads.append(payload)
        return self.approved

    @staticmethod
    def _echo():
        async def handler(call):
            from agent_core.protocol import ToolResult
            return ToolResult(tool_call_id=call.id, ok=True, content=call.name)
        return handler


class TestServicePrompt(_TeamBase):
    def test_disabled_team_has_no_prompt(self):
        self.config.active_team = ""
        self.assertIsNone(self.service.active_team())
        self.assertIsNone(self.service.active_prompt_text())

    def test_prompt_includes_playbook_and_roster(self):
        prompt = self.service.active_prompt_text()
        self.assertIn("本团队由项目经理", prompt)
        self.assertIn("全栈工程师", prompt)
        self.assertIn("能力=write", prompt)
        self.assertIn("team_preapprove", prompt)

    def test_list_payload_marks_active_team(self):
        payload = self.service.list_payload()
        active = [item for item in payload if item["active"]]
        self.assertEqual(len(active), 1)
        self.assertEqual(active[0]["name"], "software-dev-team")
        members = {m["role"]: m for m in active[0]["members"]}
        self.assertEqual(members["test-engineer"]["capability"], CAPABILITY_COMMAND)
        self.assertTrue(members["researcher"]["available"])

    def test_unknown_active_team_degrades_to_single_agent(self):
        self.config.active_team = "ghost"
        self.assertIsNone(self.service.active_team())
        self.assertIsNone(self.service.active_prompt_text())


class TestDispatch(_TeamBase):
    def test_unknown_member_is_reported_not_dispatched(self):
        result = asyncio.run(self.service._dispatch_handler(
            _call("team_dispatch", tasks=[{"role": "nobody", "goal": "做事"}])))
        self.assertFalse(result.ok)
        self.assertIn("团队成员不存在", result.error)

    def test_missing_role_definition_is_reported(self):
        self.loader.load_all()
        team = self.loader.get("software-dev-team")
        team.members[0].role = "ghost-role"
        result = asyncio.run(self.service._dispatch_handler(
            _call("team_dispatch", tasks=[{"role": "调研员", "goal": "做事"}])))
        self.assertFalse(result.ok)
        self.assertIn("角色库缺少定义", result.error)

    def test_capability_tools_reach_each_member(self):
        result = asyncio.run(self.service._dispatch_handler(_call(
            "team_dispatch",
            tasks=[{"role": "全栈工程师", "goal": "实现 X"},
                   {"role": "测试工程师", "goal": "验证 X"}])))
        self.assertTrue(result.ok)
        recorded = [call["tools"] for call in self.provider.calls]
        self.assertEqual(len(recorded), 2)
        self.assertTrue(any("write_file" in tools for tools in recorded))
        self.assertTrue(any("run_command" in tools for tools in recorded))
        # No member ever receives a deletion tool or recursive dispatch.
        for tools in recorded:
            self.assertNotIn("delete_file", tools)
            self.assertNotIn("subagent_dispatch", tools)

    def test_member_limit_is_enforced(self):
        self.config.team_max_members = 1
        result = asyncio.run(self.service._dispatch_handler(_call(
            "team_dispatch",
            tasks=[{"role": "调研员", "goal": "A"},
                   {"role": "代码审查员", "goal": "B"}])))
        self.assertIn("team_max_members", result.content)
        self.assertEqual(len(self.provider.calls), 1)

    def test_disabled_team_refuses_dispatch(self):
        self.config.active_team = ""
        result = asyncio.run(self.service._dispatch_handler(
            _call("team_dispatch", tasks=[{"role": "调研员", "goal": "A"}])))
        self.assertFalse(result.ok)
        self.assertIn("未启用团队", result.error)

    def test_turn_cap_bounds_the_whole_run(self):
        self.config.team_turn_cap = 1
        first = asyncio.run(self.service._dispatch_handler(_call(
            "team_dispatch", tasks=[{"role": "调研员", "goal": "A"}])))
        self.assertTrue(first.ok)
        second = asyncio.run(self.service._dispatch_handler(_call(
            "team_dispatch", tasks=[{"role": "代码审查员", "goal": "B"}])))
        self.assertFalse(second.ok)
        self.assertIn("team_turn_cap", second.error)
        # A new run starts with a fresh budget.
        self.service.clear_run("")
        third = asyncio.run(self.service._dispatch_handler(
            _call("team_dispatch", tasks=[{"role": "调研员", "goal": "C"}])))
        self.assertTrue(third.ok)


class TestPreapprove(_TeamBase):
    def _approve_with(self, files=None, plan="改 X"):
        async def scenario():
            token = team_mod.bind_run("run-1")
            try:
                return await self.service._preapprove_handler(
                    _call("team_preapprove", plan=plan, files=files or []))
            finally:
                team_mod.reset_run(token)
        return asyncio.run(scenario())

    def test_phase_scope_auto_write_after_approval(self):
        self.assertFalse(self.service.should_auto_write("run-1", "a.py"))
        result = self._approve_with()
        self.assertTrue(result.ok)
        self.assertIn("预审已通过", result.content)
        self.assertEqual(self.preapprove_payloads[0]["plan"], "改 X")
        self.assertTrue(self.service.should_auto_write("run-1", "any/where.py"))
        self.service.clear_preapprove("run-1")
        self.assertFalse(self.service.should_auto_write("run-1", "any/where.py"))

    def test_file_list_limits_the_grant(self):
        self._approve_with(files=["src/A.java"])
        self.assertTrue(self.service.should_auto_write("run-1", "./src/A.java"))
        self.assertFalse(self.service.should_auto_write("run-1", "src/B.java"))

    def test_rejection_keeps_per_edit_confirmation(self):
        self.approved = False
        result = self._approve_with()
        self.assertIn("预审未通过", result.content)
        self.assertFalse(self.service.should_auto_write("run-1", "a.py"))

    def test_switch_off_disables_auto_write(self):
        self.config.team_auto_after_preapprove = False
        self._approve_with()
        self.assertFalse(self.service.should_auto_write("run-1", "a.py"))


class TestBlackboardTools(_TeamBase):
    def test_post_and_read_are_session_bound(self):
        session = Session("team#1")
        token = team_mod.bind_session(session)
        try:
            posted = asyncio.run(self.service._post_handler(_call(
                "team_post", kind="research", title="结论", body="正文",
                author_role="调研员", refs=["a.py"])))
            self.assertTrue(posted.ok)
            self.assertEqual(session.artifacts[0]["authorRole"], "调研员")
            read = asyncio.run(self.service._read_handler(_call("team_read", kind="research")))
            self.assertIn("结论", read.content)
            empty = asyncio.run(self.service._read_handler(_call("team_read", kind="review")))
            self.assertIn("黑板为空", empty.content)
        finally:
            team_mod.reset_session(token)

    def test_post_without_session_fails_cleanly(self):
        result = asyncio.run(self.service._post_handler(
            _call("team_post", kind="note", body="x")))
        self.assertFalse(result.ok)
        self.assertIn("没有可写入黑板", result.error)

    def test_post_requires_title_or_body(self):
        session = Session("team#2")
        token = team_mod.bind_session(session)
        try:
            result = asyncio.run(self.service._post_handler(_call("team_post", kind="note")))
            self.assertFalse(result.ok)
            self.assertEqual(session.artifacts, [])
        finally:
            team_mod.reset_session(token)


if __name__ == "__main__":
    unittest.main()