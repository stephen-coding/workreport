"""Team blackboard: structured artifacts shared between the PM and members.

A team member cannot see the main conversation, so hand-off happens through
*deliverables* instead of free-form prose: research notes, a plan, an
implementation summary, a test report and a review verdict.  Each artifact is a
small dict persisted with the session (``Session.artifacts``), so a reopened
conversation still shows what the team produced.

Kept free of heavy imports so both the session store and the team tools can reuse
``normalize_artifacts`` without import cycles.  Python 3.9 compatible.
"""

import time
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

# Artifact vocabularies; the Shell renders these in order on the blackboard.
ARTIFACT_KINDS = ("research", "plan", "impl", "test", "review", "note")

# Keep the blackboard from growing without bound in a very long team run.
MAX_ARTIFACTS = 200
TITLE_MAX_CHARS = 120
BODY_MAX_CHARS = 6000


class Artifact(BaseModel):
    """One deliverable posted to the team blackboard."""

    kind: str = "note"
    title: str = ""
    body: str = ""
    author_role: str = ""
    produced_at: int = 0
    refs: List[str] = Field(default_factory=list)


def _clean_kind(raw: Any) -> str:
    kind = str(raw or "note").strip().lower()
    return kind if kind in ARTIFACT_KINDS else "note"


def _clean_refs(raw: Any) -> List[str]:
    if isinstance(raw, str):
        raw = [part.strip() for part in raw.split(",") if part.strip()]
    if not isinstance(raw, list):
        return []
    return [str(item).strip() for item in raw if str(item).strip()]


def normalize_artifacts(raw: Any) -> List[Dict[str, Any]]:
    """Coerce a persisted/partial list into clean artifact dicts (old files: [])."""
    if not isinstance(raw, list):
        return []
    cleaned: List[Dict[str, Any]] = []
    for entry in raw:
        if not isinstance(entry, dict):
            continue
        title = str(entry.get("title") or "").strip()
        body = str(entry.get("body") or "")
        if not title and not body.strip():
            continue
        cleaned.append({
            "kind": _clean_kind(entry.get("kind")),
            "title": title[:TITLE_MAX_CHARS],
            "body": body[:BODY_MAX_CHARS],
            "authorRole": str(entry.get("authorRole") or entry.get("author_role") or "").strip(),
            "producedAt": int(entry.get("producedAt") or entry.get("produced_at") or 0),
            "refs": _clean_refs(entry.get("refs")),
        })
    return cleaned[-MAX_ARTIFACTS:]


def make_artifact(kind: Any, title: Any, body: Any,
                  author_role: str = "", refs: Any = None) -> Dict[str, Any]:
    """Build one normalized artifact dict (always stamped with the current time)."""
    return {
        "kind": _clean_kind(kind),
        "title": str(title or "").strip()[:TITLE_MAX_CHARS],
        "body": str(body or "")[:BODY_MAX_CHARS],
        "authorRole": str(author_role or "").strip(),
        "producedAt": int(time.time() * 1000),
        "refs": _clean_refs(refs),
    }


def append_artifact(artifacts: List[Dict[str, Any]], artifact: Dict[str, Any]) -> Dict[str, Any]:
    """Append in place, trimming the oldest entries past the ceiling."""
    artifacts.append(artifact)
    if len(artifacts) > MAX_ARTIFACTS:
        del artifacts[:len(artifacts) - MAX_ARTIFACTS]
    return artifact


def select_artifacts(artifacts: List[Dict[str, Any]], kind: Optional[str] = None,
                     author_role: Optional[str] = None,
                     limit: int = 50) -> List[Dict[str, Any]]:
    """Filter the blackboard for the ``team_read`` tool."""
    wanted_kind = (kind or "").strip().lower()
    wanted_author = (author_role or "").strip()
    picked: List[Dict[str, Any]] = []
    for item in artifacts:
        if wanted_kind and item.get("kind") != wanted_kind:
            continue
        if wanted_author and item.get("authorRole") != wanted_author:
            continue
        picked.append(item)
    return picked[-max(1, limit):]


def render_artifacts(items: List[Dict[str, Any]]) -> str:
    """Text form for a tool result: one block per deliverable."""
    if not items:
        return "（黑板为空）"
    lines: List[str] = ["团队黑板（%d 条）：" % len(items)]
    for item in items:
        refs = item.get("refs") or []
        ref_text = "；引用: " + ", ".join(refs) if refs else ""
        lines.append("[%s] %s（%s）%s\n%s" % (
            item.get("kind") or "note",
            item.get("title") or "(无标题)",
            item.get("authorRole") or "未知成员",
            ref_text,
            (item.get("body") or "").strip(),
        ))
    return "\n\n".join(lines)