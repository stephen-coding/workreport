"""Task checklist: normalisation, tool handler, persistence and re-render."""

import asyncio
import json
import tempfile
import unittest

from agent_core.agent import todos as todo_tools
from agent_core.agent.session import Session, SessionManager
from agent_core.agent.store import SessionStore
from agent_core.protocol import Message, Role, ToolCall
from agent_core.tools.registry import ToolRegistry


def _call(todos, call_id="t1"):
    return ToolCall(id=call_id, name=todo_tools.TODO_TOOL, arguments={"todos": todos})


class TestNormalize(unittest.TestCase):
    def test_valid_list_keeps_order_and_ids(self):
        todos = todo_tools.normalize_todos([
            {"id": "a", "content": "读代码", "status": "completed"},
            {"id": "b", "content": "改代码", "status": "in_progress"},
        ])
        self.assertEqual([t["id"] for t in todos], ["a", "b"])
        self.assertEqual(todos[1]["status"], "in_progress")

    def test_bad_entries_are_dropped_or_downgraded(self):
        todos = todo_tools.normalize_todos([
            "不是对象",
            {"id": "a", "content": "   "},
            {"content": "缺 id", "status": "doing"},
            {"id": "c", "content": "缺状态"},
        ])
        # The blank entry disappears; the unknown status falls back to pending.
        self.assertEqual([t["content"] for t in todos], ["缺 id", "缺状态"])
        self.assertEqual([t["status"] for t in todos], ["pending", "pending"])
        self.assertEqual(todos[0]["id"], "1")

    def test_non_list_payload_is_empty(self):
        self.assertEqual(todo_tools.normalize_todos(None), [])
        self.assertEqual(todo_tools.normalize_todos({"todos": []}), [])


class TestRender(unittest.TestCase):
    def test_render_counts_progress(self):
        todos = todo_tools.normalize_todos([
            {"id": "1", "content": "甲", "status": "completed"},
            {"id": "2", "content": "乙", "status": "in_progress"},
        ])
        text = todo_tools.render(todos)
        self.assertIn("任务清单（2 项）", text)
        self.assertIn("[x] 1. 甲", text)
        self.assertIn("[>] 2. 乙", text)
        self.assertIn("进度：1/2", text)

    def test_render_empty(self):
        self.assertEqual(todo_tools.render([]), "清单已清空。")


class TestToolHandler(unittest.TestCase):
    def setUp(self):
        self.published = []
        self.registry = ToolRegistry()
        todo_tools.register_tools(self.registry, self.published.append)
        self.session = Session("hash#1")
        self.token = todo_tools.bind_session(self.session)

    def tearDown(self):
        if self.token is not None:
            todo_tools.reset_session(self.token)

    def _run(self, call):
        handler = self.registry.handler(todo_tools.TODO_TOOL)
        return asyncio.run(handler(call))

    def test_handler_writes_session_and_publishes(self):
        result = self._run(_call([{"id": "1", "content": "跑测试", "status": "pending"}]))

        self.assertTrue(result.ok)
        self.assertEqual(self.session.todos,
                         [{"id": "1", "content": "跑测试", "status": "pending"}])
        self.assertEqual(self.published, [self.session.todos])
        self.assertIn("跑测试", result.content)

    def test_empty_list_clears_the_checklist(self):
        self.session.todos = [{"id": "1", "content": "旧项", "status": "pending"}]
        result = self._run(_call([]))

        self.assertTrue(result.ok)
        self.assertEqual(self.session.todos, [])
        # The Shell must be told, otherwise the strip keeps showing the old rows.
        self.assertEqual(self.published, [[]])
        self.assertIn("已清空", result.content)

    def test_missing_or_non_list_payload_is_an_error(self):
        missing = self._run(ToolCall(id="t1", name=todo_tools.TODO_TOOL, arguments={}))
        self.assertFalse(missing.ok)
        self.assertIn("缺少 todos", missing.error)

        wrong = self._run(ToolCall(id="t2", name=todo_tools.TODO_TOOL,
                                   arguments={"todos": "1,2"}))
        self.assertFalse(wrong.ok)
        self.assertIn("必须是数组", wrong.error)
        self.assertEqual(self.session.todos, [])
        self.assertEqual(self.published, [])

    def test_all_unusable_entries_do_not_wipe_the_checklist(self):
        kept = [{"id": "1", "content": "保留我", "status": "pending"}]
        self.session.todos = list(kept)

        # A status-only update (no content) must not silently truncate the list.
        result = self._run(_call([{"id": "1", "status": "completed"}]))

        self.assertFalse(result.ok)
        self.assertIn("没有一条可用", result.error)
        self.assertEqual(self.session.todos, kept)
        self.assertEqual(self.published, [])

    def test_without_a_bound_session_the_tool_refuses(self):
        # Unbind: the handler must not guess which session to write.
        todo_tools.reset_session(self.token)
        self.token = None
        result = self._run(_call([{"id": "1", "content": "x", "status": "pending"}]))
        self.assertFalse(result.ok)


class TestStatusBlock(unittest.TestCase):
    """The per-round repeat that keeps the model updating the list."""

    def _todos(self):
        return todo_tools.normalize_todos([
            {"id": "1", "content": "甲", "status": "completed"},
            {"id": "2", "content": "乙", "status": "in_progress"},
        ])

    def test_marks_and_progress_are_repeated(self):
        text = todo_tools.render_status(self._todos())
        self.assertIn("清单: 1/2 完成", text)
        self.assertIn("[x] 1. 甲", text)
        self.assertIn("[>] 2. 乙", text)
        self.assertIn("todo_write", text)

    def test_empty_checklist_renders_nothing(self):
        self.assertEqual(todo_tools.render_status([]), "")

    def test_inherited_list_says_so(self):
        text = todo_tools.render_status(self._todos(), inherited=True)
        self.assertIn("沿用上一轮", text)

    def test_long_list_hides_finished_rows_first(self):
        raw = [{"id": str(i), "content": "项%d" % i,
                "status": "completed" if i <= 6 else "pending"} for i in range(1, 11)]
        text = todo_tools.render_status(todo_tools.normalize_todos(raw))
        # Unfinished work must survive the cap; the tail of finished rows is dropped.
        self.assertIn("[ ] 10. 项10", text)
        self.assertIn("另有", text)
        self.assertLessEqual(text.count("\n"), todo_tools.MAX_STATUS_ITEMS + 3)

    def test_stale_rounds_add_a_pointed_reminder(self):
        text = todo_tools.render_status(self._todos(), stale_rounds=todo_tools.STALE_TODO_ROUNDS)
        self.assertIn("轮没有回写清单", text)

    def test_finished_list_is_not_nagged_about(self):
        done = todo_tools.normalize_todos([{"id": "1", "content": "甲",
                                            "status": "completed"}])
        text = todo_tools.render_status(done, stale_rounds=9)
        self.assertNotIn("轮没有回写清单", text)


class TestPersistence(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = SessionStore(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _session_with_todos(self):
        session = Session("hash#1")
        session.append(Message(role=Role.USER, content="给 3 个文件加注释"))
        session.append(Message(role=Role.ASSISTANT, content="好"))
        session.todos = todo_tools.normalize_todos(
            [{"id": "1", "content": "A.java", "status": "completed"}])
        return session

    def test_todos_survive_a_restart(self):
        self.store.save(self._session_with_todos())

        restored = self.store.load("hash#1")

        self.assertEqual(restored.todos,
                         [{"id": "1", "content": "A.java", "status": "completed"}])

    def test_old_file_without_todos_reads_as_empty(self):
        self.store.save(self._session_with_todos())
        path = self.store._path("hash#1")
        data = json.loads(path.read_text(encoding="utf-8"))
        data.pop("todos", None)
        data["version"] = 1
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

        self.assertEqual(self.store.load("hash#1").todos, [])

    def test_detail_exposes_todos_to_the_shell(self):
        manager = SessionManager(self.store)
        manager.save(self._session_with_todos())

        detail = manager.detail("hash#1")

        self.assertEqual(detail["todos"][0]["content"], "A.java")
        self.assertEqual(detail["todos"][0]["status"], "completed")


if __name__ == "__main__":
    unittest.main()
