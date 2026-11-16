"""Tests for pending memories: extraction rules, Markdown file, promote flow."""

import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.session import Session  # noqa: E402
from agent_core.memory import MemoryService  # noqa: E402
from agent_core.memory.pending import (  # noqa: E402
    PendingStore,
    SOURCE_CORRECTION,
    SOURCE_INSTRUCTION,
    derive_title,
    extract_candidates,
)
from agent_core.protocol import Message, Role  # noqa: E402


def _user(text):
    return Message(role=Role.USER, content=text)


class TestExtraction(unittest.TestCase):

    def test_instruction_sentence_becomes_a_candidate(self):
        items = extract_candidates([_user("以后都用中文写提交信息。另外看看这个 bug。")])
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["category"], "user_preference")
        self.assertEqual(items[0]["source"], SOURCE_INSTRUCTION)
        self.assertIn("中文写提交信息", items[0]["content"])
        self.assertEqual(items[0]["title"], "用中文写提交信息")

    def test_negated_instruction_is_kept(self):
        items = extract_candidates([_user("不要用 PowerShell 管道跑 git log")])
        self.assertEqual(len(items), 1)
        self.assertIn("PowerShell", items[0]["content"])

    def test_correction_becomes_a_candidate(self):
        items = extract_candidates([_user("不对，应该是 300 秒超时")])
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["category"], "correction")
        self.assertEqual(items[0]["source"], SOURCE_CORRECTION)

    def test_questions_are_not_memories(self):
        self.assertEqual(extract_candidates([_user("以后都用中文吗？")]), [])

    def test_assistant_text_is_ignored(self):
        messages = [Message(role=Role.ASSISTANT, content="以后都用中文写提交信息")]
        self.assertEqual(extract_candidates(messages), [])

    def test_code_fence_is_ignored(self):
        text = "看这段：\n```\n以后都用中文\n```\n"
        self.assertEqual(extract_candidates([_user(text)]), [])

    def test_same_sentence_is_counted_once(self):
        items = extract_candidates([_user("记住：构建用 mvn -q。记住：构建用 mvn -q。")])
        self.assertEqual(len(items), 1)

    def test_candidates_are_capped(self):
        text = "记住这一条 %d。" * 20
        items = extract_candidates([_user(text)])
        self.assertLessEqual(len(items), 8)

    def test_title_drops_the_leading_marker(self):
        self.assertEqual(derive_title("记住：日志目录是 ~/.agent/logs"), "日志目录是 ~/.agent/logs")


class TestPendingStore(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-pending-")
        self.store = PendingStore(os.path.join(self.tmp, "_pending"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _candidate(self, content, title="标题", category="user_preference"):
        return {"title": title, "content": content, "category": category,
                "source": SOURCE_INSTRUCTION, "keywords": ["kw"]}

    def test_add_writes_a_markdown_file_with_a_header(self):
        added = self.store.add("proj#abc", [self._candidate("以后都用中文写提交信息")])
        self.assertEqual(len(added), 1)

        path = os.path.join(self.tmp, "_pending", "proj-abc.md")
        self.assertTrue(os.path.exists(path))
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
        self.assertIn("session: proj#abc", text)
        self.assertIn("source: chat", text)
        self.assertIn("以后都用中文写提交信息", text)

    def test_add_is_idempotent_across_rounds(self):
        first = self.store.add("proj#abc", [self._candidate("以后都用中文写提交信息")])
        second = self.store.add("proj#abc", [self._candidate("以后都用中文写提交信息")])
        self.assertEqual(len(first), 1)
        self.assertEqual(second, [])
        self.assertEqual(len(self.store.list_all()), 1)

    def test_ids_keep_increasing_after_a_discard(self):
        first = self.store.add("proj#abc", [self._candidate("记住第一条约定")])
        second = self.store.add("proj#abc", [self._candidate("记住第二条约定")])
        self.store.remove(first[0]["id"])
        third = self.store.add("proj#abc", [self._candidate("记住第三条约定")])
        self.assertNotEqual(third[0]["id"], second[0]["id"])
        self.assertEqual({item["id"] for item in self.store.list_all()},
                         {second[0]["id"], third[0]["id"]})

    def test_remove_drops_the_file_when_empty(self):
        self.store.add("proj#abc", [self._candidate("记住这条")])
        pending = self.store.list_all()[0]
        self.assertTrue(self.store.remove(pending["id"]))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "_pending", "proj-abc.md")))
        self.assertFalse(self.store.remove(pending["id"]))

    def test_update_rewrites_content_and_title(self):
        self.store.add("proj#abc", [self._candidate("以后都用中文写提交信息")])
        pending = self.store.list_all()[0]
        updated = self.store.update(pending["id"], content="提交信息统一用中文，标题不超过 72 字")
        self.assertIn("72", updated["content"])
        self.assertIn("72", self.store.list_all()[0]["content"])

    def test_get_returns_none_for_unknown_ids(self):
        self.assertIsNone(self.store.get("nope-1"))


class TestCaptureAndPromote(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-capture-")
        self.project = os.path.join(self.tmp, "project")
        self.data = os.path.join(self.tmp, "data")
        os.makedirs(self.project, exist_ok=True)
        self.service = MemoryService(self.project, self.data)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _session(self, *texts):
        session = Session("proj#1")
        for text in texts:
            session.append(_user(text))
        session.append(Message(role=Role.ASSISTANT, content="好的"))
        return session

    def test_capture_does_not_touch_the_overview(self):
        added = self.service.capture(self._session("以后都用中文写提交信息"))
        self.assertEqual(len(added), 1)
        self.assertNotIn("提交信息", self.service.overview_text())
        self.assertEqual(len(self.service.pending_list()), 1)

    def test_promote_moves_the_candidate_into_memory(self):
        self.service.capture(self._session("以后都用中文写提交信息"))
        pending = self.service.pending_list()[0]

        result = self.service.promote(pending["id"])

        self.assertIsNotNone(result["memoryId"])
        self.assertIsNone(result["duplicate"])
        self.assertEqual(self.service.pending_list(), [])
        self.assertIn("提交信息", self.service.overview_text())
        self.assertIsNotNone(self.service.store.get(result["memoryId"]))

    def test_promote_reports_a_likely_duplicate(self):
        existing = self.service.save("提交信息偏好", "以后用中文写提交信息")
        self.service.capture(self._session("以后都用中文写提交信息"))
        pending = self.service.pending_list()[0]

        result = self.service.promote(pending["id"])

        self.assertIsNotNone(result["duplicate"])
        self.assertEqual(result["duplicate"]["id"], existing.id)

    def test_promote_accepts_edited_content(self):
        self.service.capture(self._session("以后都用中文写提交信息"))
        pending = self.service.pending_list()[0]

        result = self.service.promote(pending["id"], content="提交信息统一用中文，首行不超过 72 字")

        stored = self.service.store.get(result["memoryId"])
        self.assertIn("72", stored.content)

    def test_promote_rejects_unknown_ids(self):
        with self.assertRaises(KeyError):
            self.service.promote("nope-1")

    def test_discard_removes_only_that_candidate(self):
        self.service.capture(self._session("以后都用中文写提交信息", "不要用 wget 下载依赖"))
        items = self.service.pending_list()
        self.assertEqual(len(items), 2)
        self.assertTrue(self.service.discard(items[0]["id"]))
        self.assertEqual(len(self.service.pending_list()), 1)

    def test_capture_skips_a_round_without_instructions(self):
        self.assertEqual(self.service.capture(self._session("帮我看看这个报错")), [])


if __name__ == "__main__":
    unittest.main()
