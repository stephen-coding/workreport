"""Markdown mirror of the memory store plus the overview index.

Every memory lives in two places: a row in SQLite (fast search) and a Markdown
file under ``.agent/memories`` (readable, diff-able, greppable). ``overview.md``
holds the cheap one-line-per-memory index injected into the system prompt.
"""

import os
import re
from typing import Iterable, List

from agent_core.memory.store import MemoryRecord

OVERVIEW_NAME = "overview.md"
_SLUG_RE = re.compile(r"[^0-9A-Za-z\u4e00-\u9fff_-]+")


def slugify(text: str, limit: int = 40) -> str:
    slug = _SLUG_RE.sub("-", text.strip()).strip("-")
    return (slug or "memory")[:limit]


def render_markdown(record: MemoryRecord) -> str:
    keywords = ", ".join(record.keywords)
    return (
        "---\n"
        "id: %s\n"
        "title: %s\n"
        "category: %s\n"
        "keywords: [%s]\n"
        "scope: %s\n"
        "updated_at: %s\n"
        "---\n\n"
        "# %s\n\n%s\n"
    ) % (record.id, record.title, record.category, keywords, record.scope,
         record.updated_at, record.title, record.content.strip())


class MemoryFiles:
    def __init__(self, project_dir: str, global_dir: str) -> None:
        self.project_dir = project_dir
        self.global_dir = global_dir

    # ------------------------------------------------------------------ paths

    def _dir_for(self, scope: str) -> str:
        return self.global_dir if scope == "global" else self.project_dir

    def _ensure_dir(self, path: str) -> None:
        os.makedirs(path, exist_ok=True)

    def path_for(self, record: MemoryRecord) -> str:
        directory = self._dir_for(record.scope)
        name = "%s-%s.md" % (record.id if record.id is not None else "new",
                             slugify(record.title))
        return os.path.join(directory, name)

    # ------------------------------------------------------------------ write

    def write(self, record: MemoryRecord) -> str:
        directory = self._dir_for(record.scope)
        self._ensure_dir(directory)
        path = self.path_for(record)
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(render_markdown(record))
        return path

    def remove(self, record: MemoryRecord) -> None:
        path = self.path_for(record)
        if os.path.exists(path):
            try:
                os.remove(path)
            except OSError:
                pass

    def rebuild_overview(self, records: Iterable[MemoryRecord]) -> str:
        lines: List[str] = ["# 记忆概览", ""]
        grouped: dict = {}
        for record in records:
            grouped.setdefault(record.category, []).append(record)
        for category in sorted(grouped):
            lines.append("## %s" % category)
            for record in sorted(grouped[category], key=lambda r: r.title):
                suffix = " (global)" if record.scope == "global" else ""
                lines.append("- [%s] %s%s" % (record.id, record.title, suffix))
            lines.append("")

        project_overview = os.path.join(self.project_dir, OVERVIEW_NAME)
        self._ensure_dir(self.project_dir)
        text = "\n".join(lines).rstrip() + "\n"
        with open(project_overview, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        return text

    # ------------------------------------------------------------------- read

    def read_overview(self) -> str:
        path = os.path.join(self.project_dir, OVERVIEW_NAME)
        if not os.path.exists(path):
            return ""
        try:
            with open(path, "r", encoding="utf-8") as handle:
                return handle.read().strip()
        except OSError:
            return ""
