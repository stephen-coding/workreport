"""Tests for conversation full-text search (ROADMAP §7.2)."""

import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.search import SessionSearch  # noqa: E402
from agent_core.agent.session import SessionManager  # noqa: E402
from agent_core.agent.store import SessionStore  # noqa: E402
from agent_core.protocol import Message, Role  # noqa: E402


class TestSessionSearch(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-search-")
        self.search = SessionSearch(os.path.join(self.tmp, "sessions.db"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_chinese_substring_is_found_and_highlighted(self):
        self.search.index("s1", "缓存调优",
                          "我们讨论过 prefix cache 的实现，后来把前缀缓存命中率做到了 80%", 1000)

        results = self.search.search("前缀缓存")

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["sessionId"], "s1")
        self.assertEqual(results[0]["title"], "缓存调优")
        self.assertIn("<mark>", results[0]["snippet"])
        self.assertEqual(results[0]["updatedAt"], 1000)

    def test_english_words_match(self):
        self.search.index("s1", "cache", "we discussed prefix cache for two weeks", 1000)
        results = self.search.search("prefix cache")
        self.assertEqual([r["sessionId"] for r in results], ["s1"])

    def test_short_query_falls_back_to_like(self):
        self.search.index("s1", "缓存", "命中率提高", 1000)
        results = self.search.search("命中")
        self.assertEqual([r["sessionId"] for r in results], ["s1"])
        self.assertIn("<mark>命中</mark>", results[0]["snippet"])

    def test_unknown_query_returns_nothing(self):
        self.search.index("s1", "缓存", "前缀缓存", 1000)
        self.assertEqual(self.search.search("完全不相干的内容"), [])
        self.assertEqual(self.search.search("   "), [])

    def test_reindex_is_skipped_when_not_newer(self):
        self.assertTrue(self.search.index("s1", "t", "first body", 1000))
        self.assertFalse(self.search.index("s1", "t", "second body", 1000))

        self.assertTrue(self.search.index("s1", "t", "second body", 2000))
        self.assertEqual(self.search.search("second body")[0]["sessionId"], "s1")

    def test_remove_drops_the_document(self):
        self.search.index("s1", "缓存", "前缀缓存", 1000)
        self.search.remove("s1")
        self.assertEqual(self.search.search("前缀缓存"), [])
        self.assertEqual(self.search.indexed_updated_at("s1"), 0)

    def test_limit_is_respected(self):
        for index in range(5):
            self.search.index("s%d" % index, "缓存", "前缀缓存 讨论了两次", 1000 + index)
        self.assertEqual(len(self.search.search("前缀缓存", limit=2)), 2)
        self.assertEqual(len(self.search.search("前缀缓存", limit=999)), 5)


class TestSessionIndexHooks(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-search-hooks-")
        self.store = SessionStore(self.tmp)
        self.search = SessionSearch(os.path.join(self.tmp, "sessions.db"))
        self.manager = SessionManager(
            self.store,
            on_saved=lambda session_id, title, content, updated_at:
                self.search.index(session_id, title, content, updated_at),
            on_deleted=self.search.remove,
        )

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_saved_session_is_searchable_and_delete_drops_it(self):
        session = self.manager.get_or_create("hash#1")
        session.title = "缓存调优"
        session.touch()
        session.append(Message(role=Role.USER, content="前缀缓存怎么调？"))
        session.append(Message(role=Role.ASSISTANT, content="先量 bm25 的命中率"))
        self.manager.save(session)

        results = self.search.search("前缀缓存")
        self.assertEqual([r["sessionId"] for r in results], ["hash#1"])

        self.manager.delete("hash#1")
        self.assertEqual(self.search.search("前缀缓存"), [])

    def test_iter_documents_only_indexes_prose(self):
        session = self.manager.get_or_create("hash#2")
        session.title = "构建"
        session.touch()
        session.append(Message(role=Role.USER, content="构建命令是什么"))
        session.append(Message(role=Role.ASSISTANT, content="mvn -q -DskipTests compile"))
        self.manager.save(session)

        documents = {doc["id"]: doc for doc in self.store.iter_documents()}

        self.assertIn("hash#2", documents)
        self.assertIn("mvn -q", documents["hash#2"]["content"])
        self.assertEqual(documents["hash#2"]["title"], "构建")


if __name__ == "__main__":
    unittest.main()
