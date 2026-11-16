"""Tests for the local usage log (ROADMAP §7.6)."""

import json
import os
import shutil
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core import usage as usage_module  # noqa: E402
from agent_core.usage import UsageLog, timestamp  # noqa: E402


class TestUsageLog(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-usage-")
        self.log = UsageLog(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_record_writes_one_json_line_per_task(self):
        self.log.record("t1", "qwen2.5:7b", 1200, 380, 5, 18342, True)

        with open(self.log.path, "r", encoding="utf-8") as handle:
            lines = [line for line in handle.read().splitlines() if line.strip()]

        self.assertEqual(len(lines), 1)
        entry = json.loads(lines[0])
        self.assertEqual(entry["taskId"], "t1")
        self.assertEqual(entry["model"], "qwen2.5:7b")
        self.assertEqual((entry["in"], entry["out"], entry["toolCalls"]), (1200, 380, 5))
        self.assertTrue(entry["ok"])
        self.assertRegex(entry["ts"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

    def test_report_aggregates_and_groups_by_day(self):
        self.log.record("t1", "m", 100, 20, 1, 1000, True)
        self.log.record("t2", "m", 300, 40, 3, 3000, False)
        self.log.record("t3", "other", 0, 10, 0, 500, True)

        report = self.log.report(days=7)

        self.assertEqual(report["days"], 7)
        self.assertEqual(report["tasks"], 3)
        self.assertEqual(report["failed"], 1)
        self.assertEqual(report["tokensIn"], 400)
        self.assertEqual(report["tokensOut"], 70)
        self.assertEqual(report["toolCalls"], 4)
        self.assertEqual(report["avgDurationMs"], 1500)
        self.assertEqual(len(report["byDay"]), 1)
        self.assertEqual(report["byDay"][0]["tasks"], 3)
        self.assertEqual(report["models"][0]["model"], "m")

    def test_old_lines_are_outside_the_window(self):
        old = {"ts": timestamp(time.time() - 40 * 86400), "taskId": "old", "model": "m",
               "in": 999, "out": 999, "toolCalls": 0, "durationMs": 1, "ok": True}
        with open(self.log.path, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(old) + "\n")
        self.log.record("new", "m", 10, 5, 0, 10, True)

        report = self.log.report(days=7)

        self.assertEqual(report["tasks"], 1)
        self.assertEqual(report["tokensIn"], 10)
        self.assertEqual(self.log.report(days=90)["tasks"], 2)

    def test_rotation_archives_and_keeps_every_line(self):
        original = usage_module.MAX_BYTES
        usage_module.MAX_BYTES = 10
        try:
            self.log.record("t1", "m", 1, 1, 0, 1, True)
            self.log.record("t2", "m", 1, 1, 0, 1, True)
        finally:
            usage_module.MAX_BYTES = original

        names = [os.path.basename(path) for path in self.log.files()]
        self.assertTrue(any(name.startswith("usage-") for name in names), names)
        self.assertEqual(self.log.report(days=7)["tasks"], 2)

    def test_report_of_an_empty_directory_is_zeroed(self):
        report = self.log.report()
        self.assertEqual(report["tasks"], 0)
        self.assertEqual(report["avgDurationMs"], 0)
        self.assertEqual(report["byDay"], [])

    def test_broken_lines_are_skipped(self):
        with open(self.log.path, "w", encoding="utf-8") as handle:
            handle.write("not json\n\n")
        self.log.record("t1", "m", 1, 2, 0, 10, True)
        self.assertEqual(self.log.report()["tasks"], 1)


if __name__ == "__main__":
    unittest.main()
