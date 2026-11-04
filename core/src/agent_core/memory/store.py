"""SQLite-backed memory index with an FTS5 full-text index.

The store is the query side; the Markdown files under ``.agent/memories`` are
the human-readable side (see :mod:`agent_core.memory.files`).
"""

import json
import logging
import sqlite3
import time
from typing import List, Optional

from pydantic import BaseModel, Field

LOGGER = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  category TEXT NOT NULL,
  keywords TEXT NOT NULL,
  scope TEXT NOT NULL,
  project_path TEXT,
  created_at INTEGER,
  updated_at INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_identity
  ON memories(title, scope, IFNULL(project_path, ''));
"""

FTS_SCHEMA = """
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
  title, content, keywords, content='memories', content_rowid='id'
);
CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
  INSERT INTO memories_fts(rowid, title, content, keywords)
  VALUES (new.id, new.title, new.content, new.keywords);
END;
CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
  INSERT INTO memories_fts(memories_fts, rowid, title, content, keywords)
  VALUES ('delete', old.id, old.title, old.content, old.keywords);
END;
CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
  INSERT INTO memories_fts(memories_fts, rowid, title, content, keywords)
  VALUES ('delete', old.id, old.title, old.content, old.keywords);
  INSERT INTO memories_fts(rowid, title, content, keywords)
  VALUES (new.id, new.title, new.content, new.keywords);
END;
"""


class MemoryRecord(BaseModel):
    id: Optional[int] = None
    title: str
    content: str
    category: str = "project_info"
    keywords: List[str] = Field(default_factory=list)
    scope: str = "project"  # project | global
    project_path: Optional[str] = None
    created_at: int = 0
    updated_at: int = 0


class MemoryStore:
    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        self.fts_enabled = False
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=5.0)
        connection.row_factory = sqlite3.Row
        return connection

    def _init_schema(self) -> None:
        with self._connect() as connection:
            connection.executescript(SCHEMA)
            try:
                connection.executescript(FTS_SCHEMA)
                self.fts_enabled = True
            except sqlite3.OperationalError:
                # FTS5 is missing from this Python build; LIKE search is used.
                LOGGER.warning("FTS5 unavailable, falling back to LIKE search")

    # ------------------------------------------------------------------ write

    def save(self, record: MemoryRecord) -> int:
        now = int(time.time())
        keywords = json.dumps(record.keywords, ensure_ascii=False)
        with self._connect() as connection:
            row = connection.execute(
                "SELECT id FROM memories WHERE title = ? AND scope = ? "
                "AND IFNULL(project_path, '') = IFNULL(?, '')",
                (record.title, record.scope, record.project_path),
            ).fetchone()
            if row is None:
                cursor = connection.execute(
                    "INSERT INTO memories(title, content, category, keywords, scope, "
                    "project_path, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    (record.title, record.content, record.category, keywords,
                     record.scope, record.project_path, now, now),
                )
                return int(cursor.lastrowid)
            connection.execute(
                "UPDATE memories SET content = ?, category = ?, keywords = ?, updated_at = ? "
                "WHERE id = ?",
                (record.content, record.category, keywords, now, row["id"]),
            )
            return int(row["id"])

    def delete(self, memory_id: int) -> bool:
        with self._connect() as connection:
            cursor = connection.execute("DELETE FROM memories WHERE id = ?", (memory_id,))
            return cursor.rowcount > 0

    def get(self, memory_id: int) -> Optional[MemoryRecord]:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM memories WHERE id = ?", (memory_id,)).fetchone()
        return _row_to_record(row) if row else None

    # ------------------------------------------------------------------- read

    def list_all(self, scope: Optional[str] = None,
                 project_path: Optional[str] = None) -> List[MemoryRecord]:
        query = "SELECT * FROM memories"
        clauses: List[str] = []
        params: List[object] = []
        if scope:
            clauses.append("scope = ?")
            params.append(scope)
        if project_path:
            clauses.append("IFNULL(project_path, '') = ?")
            params.append(project_path)
        if clauses:
            query += " WHERE " + " AND ".join(clauses)
        query += " ORDER BY updated_at DESC"
        with self._connect() as connection:
            rows = connection.execute(query, params).fetchall()
        return [_row_to_record(row) for row in rows]

    def search(self, keywords: List[str], scope: Optional[str] = None,
               project_path: Optional[str] = None, limit: int = 8) -> List[MemoryRecord]:
        terms = [k.strip() for k in keywords if k and k.strip()]
        if not terms:
            return self.list_all(scope, project_path)[:limit]

        with self._connect() as connection:
            if self.fts_enabled:
                match = " OR ".join('"%s"' % t.replace('"', ' ') for t in terms)
                try:
                    rows = connection.execute(
                        "SELECT m.* FROM memories m JOIN memories_fts f ON f.rowid = m.id "
                        "WHERE memories_fts MATCH ? "
                        "AND (? IS NULL OR m.scope = ?) "
                        "AND (? IS NULL OR IFNULL(m.project_path, '') = ?) "
                        "ORDER BY bm25(memories_fts) LIMIT ?",
                        (match, scope, scope, project_path, project_path, limit),
                    ).fetchall()
                    return [_row_to_record(row) for row in rows]
                except sqlite3.OperationalError:
                    LOGGER.warning("FTS query failed, falling back to LIKE", exc_info=True)

            like = " OR ".join(["title LIKE ? OR content LIKE ? OR keywords LIKE ?"] * len(terms))
            params: List[object] = []
            for term in terms:
                needle = "%" + term + "%"
                params.extend([needle, needle, needle])
            params.extend([scope, scope, project_path, project_path, limit])
            rows = connection.execute(
                "SELECT * FROM memories WHERE (%s) "
                "AND (? IS NULL OR scope = ?) "
                "AND (? IS NULL OR IFNULL(project_path, '') = ?) "
                "ORDER BY updated_at DESC LIMIT ?" % like,
                params,
            ).fetchall()
        return [_row_to_record(row) for row in rows]


def _row_to_record(row: sqlite3.Row) -> MemoryRecord:
    try:
        keywords = json.loads(row["keywords"] or "[]")
    except (TypeError, ValueError):
        keywords = []
    return MemoryRecord(
        id=row["id"],
        title=row["title"],
        content=row["content"],
        category=row["category"],
        keywords=keywords if isinstance(keywords, list) else [],
        scope=row["scope"],
        project_path=row["project_path"],
        created_at=row["created_at"] or 0,
        updated_at=row["updated_at"] or 0,
    )
