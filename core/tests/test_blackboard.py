"""Tests for the team blackboard and its session persistence."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.agent.blackboard import (  # noqa: E402
    ARTIFACT_KINDS,
    MAX_ARTIFACTS,
    append_artifact,
    make_artifact,
    normalize_artifacts,
    render_artifacts,
    select_artifacts,
)
from agent_core.agent.session import Session  # noqa: E402
from agent_core.agent.store import STORE_VERSION, SessionStore  # noqa: E402
from agent_core.protocol import Message, Role  # noqa: E402


class TestArtifacts(unittest.TestCase):
    def test_make_artifact_normalises_fields(self):
        artifact = make_artifact("research", "  结论  ", "正文", author_role="researcher",
                                 refs="a.py, b.py")
        self.assertEqual(artifact["kind"], "research")
        self.assertEqual(artifact["title"], "结论")
        self.assertEqual(artifact["authorRole"], "researcher")
        self.assertEqual(artifact["refs"], ["a.py", "b.py"])
        self.assertGreater(artifact["producedAt"], 0)

    def test_unknown_kind_falls_back_to_note(self):
        self.assertEqual(make_artifact("bogus", "t", "b")["kind"], "note")
        for kind in ARTIFACT_KINDS:
            self.assertEqual(make_artifact(kind, "t", "b")["kind"], kind)

    def test_append_trims_oldest(self):
        items = []
        for index in range(MAX_ARTIFACTS + 5):
            append_artifact(items, make_artifact("note", "t%d" % index, "b"))
        self.assertEqual(len(items), MAX_ARTIFACTS)
        self.assertEqual(items[0]["title"], "t5")

    def test_select_filters_by_kind_and_author(self):
        items = [
            make_artifact("research", "r", "b", author_role="researcher"),
            make_artifact("review", "v", "b", author_role="code-reviewer"),
            make_artifact("review", "v2", "b", author_role="lead"),
        ]
        self.assertEqual(len(select_artifacts(items, kind="review")), 2)
        self.assertEqual(len(select_artifacts(items, author_role="lead")), 1)
        self.assertEqual(
            len(select_artifacts(items, kind="review", author_role="code-reviewer")), 1)
        self.assertEqual(len(select_artifacts(items, limit=1)), 1)

    def test_normalize_drops_broken_rows(self):
        cleaned = normalize_artifacts([
            {"kind": "plan", "title": "p", "body": "b"},
            {"kind": "plan"},
            "not-a-dict",
            {"kind": "impl", "body": "只有正文"},
        ])
        self.assertEqual([item["kind"] for item in cleaned], ["plan", "impl"])
        # Old camelCase keys are accepted so a rewritten file still reads back.
        legacy = normalize_artifacts([{"title": "t", "body": "b", "author_role": "x"}])
        self.assertEqual(legacy[0]["authorRole"], "x")
        self.assertEqual(normalize_artifacts(None), [])

    def test_render_artifacts(self):
        self.assertEqual(render_artifacts([]), "（黑板为空）")
        text = render_artifacts([make_artifact("impl", "改完了", "细节", author_role="全栈工程师")])
        self.assertIn("[impl]", text)
        self.assertIn("全栈工程师", text)


class TestSessionPersistence(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="agent-blackboard-")
        self.store = SessionStore(self.tmp)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_artifacts_and_runs_round_trip(self):
        session = Session("bb#1")
        session.title = "团队会话"
        session.append(Message(role=Role.USER, content="做点事"))
        session.artifacts.append(make_artifact("research", "结论", "正文", author_role="调研员"))
        session.team_runs.append({"teamRunId": "t1", "team": "software-dev-team", "ok": True})
        self.store.save(session)

        restored = self.store.load("bb#1")
        self.assertEqual(STORE_VERSION, 3)
        self.assertEqual(len(restored.artifacts), 1)
        self.assertEqual(restored.artifacts[0]["authorRole"], "调研员")
        self.assertEqual(restored.team_runs[0]["teamRunId"], "t1")

    def test_session_without_blackboard_reads_empty(self):
        session = Session("bb#2")
        session.append(Message(role=Role.USER, content="普通会话"))
        self.store.save(session)
        restored = self.store.load("bb#2")
        self.assertEqual(restored.artifacts, [])
        self.assertEqual(restored.team_runs, [])


if __name__ == "__main__":
    unittest.main()