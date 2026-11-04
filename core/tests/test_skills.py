"""Tests for the Skill system: frontmatter parsing, loading order, matching, tools."""

import asyncio
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.skills import SkillLoader, SkillService, parse_skill  # noqa: E402
from agent_core.skills.loader import parse_frontmatter  # noqa: E402
from agent_core.tools.registry import ToolRegistry  # noqa: E402

SAMPLE = """---
name: demo-skill
description: 演示用
scenarios: ["演示", "demo"]
tools: [read_file, git_log]
---
# 步骤
1. 做点什么
"""


class TestFrontmatter(unittest.TestCase):
    def test_inline_list_and_body(self):
        meta, body = parse_frontmatter(SAMPLE)
        self.assertEqual(meta["name"], "demo-skill")
        self.assertEqual(meta["scenarios"], ["演示", "demo"])
        self.assertEqual(meta["tools"], ["read_file", "git_log"])
        self.assertIn("# 步骤", body)

    def test_block_list(self):
        text = "---\nname: x\nscenarios:\n  - a\n  - b\n---\nbody\n"
        meta, _ = parse_frontmatter(text)
        self.assertEqual(meta["scenarios"], ["a", "b"])

    def test_no_frontmatter(self):
        meta, body = parse_frontmatter("just text")
        self.assertEqual(meta, {})
        self.assertEqual(body, "just text")

    def test_missing_name_is_rejected(self):
        self.assertIsNone(parse_skill("---\ndescription: x\n---\nbody", "p", "global"))


class TestSkillLoader(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-skills-")
        self.project = os.path.join(self.tmp, "project")
        self.global_dir = os.path.join(self.tmp, "global-skills")
        os.makedirs(self.project, exist_ok=True)
        os.makedirs(self.global_dir, exist_ok=True)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, directory, name, text):
        os.makedirs(directory, exist_ok=True)
        path = os.path.join(directory, name, "SKILL.md")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)

    def test_builtin_skills_are_loaded(self):
        loader = SkillLoader(self.project, self.global_dir)
        names = {skill.name for skill in loader.list_skills()}
        self.assertTrue({"daily-report", "weekly-report",
                         "quarterly-report", "yearly-report"}.issubset(names))

    def test_project_skill_overrides_global(self):
        self._write(self.global_dir, "shared", "---\nname: shared\ndescription: global\n---\nglobal body\n")
        project_skills = os.path.join(self.project, ".agent", "skills")
        self._write(project_skills, "shared", "---\nname: shared\ndescription: project\n---\nproject body\n")

        loader = SkillLoader(self.project, self.global_dir)
        skill = loader.get("shared")
        self.assertEqual(skill.scope, "project")
        self.assertIn("project body", skill.body)

    def test_match_by_scenario(self):
        loader = SkillLoader(self.project, self.global_dir)
        self.assertEqual(loader.match("帮我写今天的日报").name, "daily-report")
        self.assertEqual(loader.match("生成周报").name, "weekly-report")
        self.assertIsNone(loader.match("解释一下这段代码"))

    def test_flat_markdown_file_is_accepted(self):
        with open(os.path.join(self.global_dir, "flat.md"), "w", encoding="utf-8") as handle:
            handle.write(SAMPLE)
        loader = SkillLoader(self.project, self.global_dir)
        self.assertIsNotNone(loader.get("demo-skill"))


class TestSkillService(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-skills-svc-")
        self.loader = SkillLoader(self.tmp, os.path.join(self.tmp, "global"))
        self.service = SkillService(self.loader)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_instruction_for_matches_daily_report(self):
        instruction = self.service.instruction_for("生成日报")
        self.assertIn("daily-report", instruction)
        self.assertIn("git_log", instruction)

    def test_instruction_for_explicit_name(self):
        instruction = self.service.instruction_for("随便说点什么", "weekly-report")
        self.assertIn("weekly-report", instruction)

    def test_no_instruction_without_match(self):
        self.assertEqual(self.service.instruction_for("解释这段代码"), "")

    def test_tools_registered_and_callable(self):
        registry = ToolRegistry()
        self.service.register_tools(registry)
        self.assertIn("skill_load", registry.names())
        self.assertIn("skill_list", registry.names())

        handler = registry.handler("skill_load")
        from agent_core.protocol import ToolCall
        result = asyncio.run(handler(ToolCall(id="c1", name="skill_load",
                                             arguments={"name": "daily-report"})))
        self.assertTrue(result.ok)
        self.assertIn("日报生成指令", result.content)


if __name__ == "__main__":
    unittest.main()
