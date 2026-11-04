"""Tests for the memory system: SQLite index, Markdown mirror, built-in tools."""

import asyncio
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.memory import MemoryService  # noqa: E402
from agent_core.protocol import ToolCall  # noqa: E402


class MemoryTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-memory-")
        self.project = os.path.join(self.tmp, "project")
        self.data = os.path.join(self.tmp, "data")
        os.makedirs(self.project, exist_ok=True)
        self.service = MemoryService(self.project, self.data)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)


class TestMemoryStore(MemoryTestCase):
    def test_save_and_get(self):
        record = self.service.save("构建命令", "使用 mvn -q -DskipTests compile",
                                   category="project_build_configuration",
                                   keywords=["maven", "compile"])
        self.assertIsNotNone(record.id)
        loaded = self.service.store.get(record.id)
        self.assertEqual(loaded.title, "构建命令")
        self.assertIn("mvn", loaded.content)

    def test_save_same_title_updates_in_place(self):
        first = self.service.save("约定", "第一个版本")
        second = self.service.save("约定", "第二个版本")
        self.assertEqual(first.id, second.id)
        self.assertEqual(second.content, "第二个版本")
        self.assertEqual(len(self.service.store.list_all()), 1)

    def test_search_by_keyword(self):
        self.service.save("并发调度", "工具调度使用 Semaphore 限制并发",
                          keywords=["semaphore", "asyncio"])
        self.service.save("插件布局", "工具窗口在右侧", keywords=["toolwindow"])
        hits = self.service.search(["semaphore"])
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0].title, "并发调度")

    def test_delete_removes_row(self):
        record = self.service.save("临时记忆", "待删除")
        self.assertTrue(self.service.delete(record.id))
        self.assertIsNone(self.service.store.get(record.id))


class TestMemoryFiles(MemoryTestCase):
    def test_markdown_mirror_written(self):
        record = self.service.save("镜像验证", "内容落盘", keywords=["mirror"])
        path = self.service.files.path_for(record)
        self.assertTrue(os.path.exists(path))
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
        self.assertIn("镜像验证", text)
        self.assertIn("keywords: [mirror]", text)

    def test_overview_contains_saved_titles(self):
        self.service.save("概览条目", "正文", keywords=["overview"])
        overview = self.service.overview_text()
        self.assertIn("概览条目", overview)
        self.assertIn("# 记忆概览", overview)

    def test_delete_removes_markdown(self):
        record = self.service.save("待清理", "内容")
        path = self.service.files.path_for(record)
        self.service.delete(record.id)
        self.assertFalse(os.path.exists(path))

    def test_global_scope_writes_outside_project(self):
        # Redirect the home directory so the test never touches the real ~/.agent.
        fake_home = os.path.join(self.tmp, "home")
        saved = {k: os.environ.get(k) for k in ("HOME", "USERPROFILE")}
        os.environ["HOME"] = fake_home
        os.environ["USERPROFILE"] = fake_home
        try:
            service = MemoryService(self.project, self.data)
            record = service.save("全局记忆", "跨项目有效", scope="global")
            self.assertTrue(service.files.path_for(record).startswith(fake_home))
        finally:
            for key, value in saved.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value


class TestMemoryTools(MemoryTestCase):
    def test_save_then_recall(self):
        save = ToolCall(id="c1", name="memory_save",
                        arguments={"title": "风格", "content": "注释用中文",
                                   "keywords": ["comment"]})
        result = asyncio.run(self.service._save_handler(save))
        self.assertTrue(result.ok, result.error)

        recall = ToolCall(id="c2", name="memory_recall",
                          arguments={"mode": "search", "keywords": ["comment"]})
        result = asyncio.run(self.service._recall_handler(recall))
        self.assertTrue(result.ok)
        self.assertIn("注释用中文", result.content)

    def test_overview_mode_when_empty(self):
        recall = ToolCall(id="c1", name="memory_recall", arguments={"mode": "overview"})
        result = asyncio.run(self.service._recall_handler(recall))
        self.assertTrue(result.ok)
        self.assertIn("暂无记忆", result.content)

    def test_delete_tool_reports_missing_id(self):
        delete = ToolCall(id="c1", name="memory_delete", arguments={"memory_id": 999})
        result = asyncio.run(self.service._delete_handler(delete))
        self.assertFalse(result.ok)
        self.assertIn("不存在", result.error)


if __name__ == "__main__":
    unittest.main()
