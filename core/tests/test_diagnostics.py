"""Tests for the per-task diagnostic trace."""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core import diagnostics  # noqa: E402


class TestTaskTrace(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix="agent-trace-")
        self._trace_dir = os.environ.get("AGENT_TRACE_DIR")
        self._trace = os.environ.get("AGENT_TRACE")
        os.environ["AGENT_TRACE_DIR"] = self.directory
        os.environ.pop("AGENT_TRACE", None)

    def tearDown(self):
        if self._trace_dir is None:
            os.environ.pop("AGENT_TRACE_DIR", None)
        else:
            os.environ["AGENT_TRACE_DIR"] = self._trace_dir
        if self._trace is None:
            os.environ.pop("AGENT_TRACE", None)
        else:
            os.environ["AGENT_TRACE"] = self._trace

    def _lines(self, path):
        with open(path, "r", encoding="utf-8") as handle:
            return [json.loads(line) for line in handle if line.strip()]

    def test_events_are_written_and_summary_closes_the_file(self):
        trace = diagnostics.TaskTrace("task-1", "session-1", directory=self.directory)
        token = diagnostics.bind(trace)
        try:
            diagnostics.emit("tool_start", tool="read_file", call="c1")
            trace.count("toolCalls")
            diagnostics.emit("tool_end", tool="read_file", call="c1", ok=True, ms=12)
        finally:
            diagnostics.reset(token)
        trace.finish(True, durationMs=1500, tokens={"in": 10, "out": 20})

        records = self._lines(trace.path)
        kinds = [record["kind"] for record in records]
        self.assertEqual(kinds[0], "tool_start")
        self.assertIn("tool_end", kinds)
        self.assertEqual(kinds[-1], "task_end")
        self.assertTrue(records[-1]["ok"])
        self.assertEqual(records[-1]["toolCalls"], 1)
        self.assertEqual(records[-1]["durationMs"], 1500)
        self.assertEqual(records[0]["session"], "session-1")

    def test_failed_tool_is_counted_per_name(self):
        trace = diagnostics.TaskTrace("task-2", directory=self.directory)
        trace.count("toolCalls")
        trace.fail("edit_file")
        trace.count("toolCalls")
        trace.fail("edit_file")
        trace.finish(False, error="boom")
        summary = self._lines(trace.path)[-1]
        self.assertFalse(summary["ok"])
        self.assertEqual(summary["toolFails"], 2)
        self.assertEqual(summary["failedTools"], {"edit_file": 2})
        self.assertEqual(summary["error"], "boom")

    def test_events_after_finish_are_ignored(self):
        trace = diagnostics.TaskTrace("task-3", directory=self.directory)
        trace.finish(True)
        before = self._lines(trace.path)
        trace.event("tool_start", tool="late")
        self.assertEqual(before, self._lines(trace.path))

    def test_trace_can_be_disabled(self):
        os.environ["AGENT_TRACE"] = "0"
        self.assertFalse(diagnostics.enabled())
        self.assertIsNone(diagnostics.start("task-4"))
        # emit without an active trace must not raise
        diagnostics.emit("tool_start", tool="noop")

    def test_prune_keeps_only_the_newest_files(self):
        for index in range(diagnostics.MAX_TRACES + 5):
            path = os.path.join(self.directory, "20260101-0000%02d-old.jsonl" % index)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("{}\n")
            # Distinct mtimes so the ordering is deterministic.
            os.utime(path, (index + 1, index + 1))
        trace = diagnostics.TaskTrace("task-5", directory=self.directory)
        trace.finish(True)
        remaining = [name for name in os.listdir(self.directory) if name.endswith(".jsonl")]
        self.assertEqual(len(remaining), diagnostics.MAX_TRACES)

    def test_brief_stays_on_one_line_and_is_capped(self):
        text = diagnostics.brief({"path": "a/b.py", "content": "x\ny"})
        self.assertNotIn("\n", text)
        self.assertTrue(diagnostics.brief("y" * 500).endswith("..."))
        self.assertLessEqual(len(diagnostics.brief("y" * 500)), diagnostics.BRIEF_LIMIT + 3)


if __name__ == "__main__":
    unittest.main()
