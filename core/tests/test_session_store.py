"""Tests for persisted chat sessions (history list + restart resume)."""

import tempfile
import unittest

from agent_core.agent import store as store_module
from agent_core.agent.session import Session, SessionManager
from agent_core.agent.store import SessionStore
from agent_core.protocol import Message, Role, ToolCall


def _session(session_id="hash#1", title="修登录超时", updated_at=1_700_000_000_000):
    session = Session(session_id)
    session.title = title
    session.updated_at = updated_at
    session.append(Message(role=Role.USER, content="修登录超时"))
    call = ToolCall(id="c1", name="read_file", arguments={"path": "LoginService.java"})
    session.append(Message(role=Role.ASSISTANT, content="", tool_calls=[call]))
    session.append(Message(role=Role.TOOL, tool_call_id="c1", name="read_file",
                           content="class LoginService {}"))
    session.append(Message(role=Role.ASSISTANT, content="改好了"))
    return session


class TestSessionStore(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = SessionStore(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_save_then_load_keeps_rounds_and_metadata(self):
        self.store.save(_session())

        restored = self.store.load("hash#1")

        self.assertIsNotNone(restored)
        self.assertEqual(restored.title, "修登录超时")
        self.assertEqual(restored.updated_at, 1_700_000_000_000)
        self.assertEqual([m.role for m in restored.messages],
                         [Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT])
        calls = restored.messages[1].tool_calls
        self.assertEqual(calls[0].name, "read_file")
        self.assertEqual(calls[0].arguments["path"], "LoginService.java")
        self.assertEqual(restored.messages[2].tool_call_id, "c1")

    def test_list_is_newest_first_with_title_and_preview(self):
        self.store.save(_session("hash#1", "旧会话", 1_700_000_000_000))
        self.store.save(_session("hash#2", "新会话", 1_700_000_500_000))

        entries = self.store.list_sessions()

        self.assertEqual([e["id"] for e in entries], ["hash#2", "hash#1"])
        self.assertEqual(entries[0]["title"], "新会话")
        self.assertEqual(entries[0]["preview"], "修登录超时")
        # Only user/assistant turns are counted; tool traffic stays out of the list.
        self.assertEqual(entries[0]["messageCount"], 2)

    def test_delete_removes_only_that_session(self):
        self.store.save(_session("hash#1"))
        self.store.save(_session("hash#2"))

        self.assertTrue(self.store.delete("hash#1"))

        self.assertIsNone(self.store.load("hash#1"))
        self.assertIsNotNone(self.store.load("hash#2"))
        self.assertFalse(self.store.delete("hash#1"))

    def test_trimming_never_starts_with_an_orphan_tool_result(self):
        original = store_module.MAX_MESSAGES
        store_module.MAX_MESSAGES = 3
        try:
            self.store.save(_session())
            restored = self.store.load("hash#1")
        finally:
            store_module.MAX_MESSAGES = original

        self.assertEqual(len(restored.messages), 3)
        self.assertNotEqual(restored.messages[0].role, Role.TOOL)


class TestSessionManagerHistory(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = SessionStore(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_session_is_restored_after_a_core_restart(self):
        first = SessionManager(self.store)
        first.get_or_create("hash#1")
        session = first.get("hash#1")
        session.title = "修登录超时"
        session.touch()
        session.append(Message(role=Role.USER, content="修登录超时"))
        session.append(Message(role=Role.ASSISTANT, content="改好了"))
        first.save(session)

        # A fresh manager stands in for a restarted Core process.
        restarted = SessionManager(SessionStore(self.tmp.name))
        restored = restarted.get_or_create("hash#1")

        self.assertEqual(len(restored.messages), 2)
        self.assertEqual(restored.title, "修登录超时")

    def test_detail_skips_tool_traffic(self):
        manager = SessionManager(self.store)
        manager.save(_session())

        detail = manager.detail("hash#1")

        self.assertEqual(detail["title"], "修登录超时")
        self.assertEqual([m["role"] for m in detail["messages"]], ["user", "assistant"])
        self.assertEqual(detail["messages"][1]["content"], "改好了")

    def test_list_merges_live_sessions(self):
        manager = SessionManager(self.store)
        manager.save(_session("hash#1", "磁盘里的", 1_700_000_000_000))
        live = manager.get_or_create("hash#2")
        live.title = "内存里的"
        live.touch()
        live.append(Message(role=Role.USER, content="新问题"))

        ids = [entry["id"] for entry in manager.list_sessions()]

        self.assertEqual(ids[0], "hash#2")
        self.assertIn("hash#1", ids)
        live_entry = manager.list_sessions()[0]
        # Live sessions count the same way as the on-disk list: text turns only.
        self.assertEqual(live_entry["messageCount"], 1)
        self.assertEqual(live_entry["title"], "内存里的")

    def test_delete_drops_memory_and_disk(self):
        manager = SessionManager(self.store)
        session = manager.get_or_create("hash#1")
        session.append(Message(role=Role.USER, content="你好"))
        manager.save(session)

        self.assertTrue(manager.delete("hash#1"))
        self.assertIsNone(manager.get("hash#1"))
        self.assertIsNone(self.store.load("hash#1"))


if __name__ == "__main__":
    unittest.main()
