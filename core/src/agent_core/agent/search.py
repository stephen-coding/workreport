"""Full-text search over stored conversations (ROADMAP §7.2).

One SQLite file per data directory holds a plain mirror of every conversation
(``sessions_text``) plus an FTS5 index over it (``sessions_fts``).  The mirror
exists because the searches that matter are Chinese ones, and FTS5's default
tokenizer treats a whole CJK run as a single token; the ``trigram`` tokenizer
matches substrings instead, and the mirror lets short queries fall back to LIKE.

会话全文检索：sessions_text 存正文副本，sessions_fts 用 trigram 分词支持中文子串，
FTS 不可用或查询太短时退回 LIKE 匹配。
"""

import logging
import os
import re
import sqlite3
from typing import Any, Dict, Iterable, List

LOGGER = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions_text(
  session_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
"""

# ``trigram`` (SQLite 3.34+) tokenises every 3 characters, so a CJK substring
# query actually matches.  Older builds raise OperationalError and we keep LIKE.
FTS_SCHEMA = """
CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
  session_id UNINDEXED, title, content, tokenize='trigram'
);
"""

DEFAULT_LIMIT = 20
MAX_LIMIT = 50
SNIPPET_TOKENS = 16
# A conversation can be long; the index only needs what a search can hit.
MAX_INDEX_CHARS = 200_000
MARK_OPEN = "<mark>"
MARK_CLOSE = "</mark>"
_WS_RE = re.compile(r"\s+")


class SessionSearch:
    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        self.fts_enabled = False
        # sqlite3 will not create the directory for us and the data dir may not
        # exist yet on the very first start.
        # 首次启动时数据目录可能还不存在，sqlite 不会替我们建目录。
        directory = os.path.dirname(os.path.abspath(db_path))
        if directory:
            os.makedirs(directory, exist_ok=True)
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
                # No trigram/FTS5 module in this build: LIKE still works.
                LOGGER.warning("FTS5 trigram unavailable, session search uses LIKE")

    # ------------------------------------------------------------------ write

    def indexed_updated_at(self, session_id: str) -> int:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT updated_at FROM sessions_text WHERE session_id = ?",
                (session_id,),
            ).fetchone()
        return int(row["updated_at"]) if row else 0

    def index(self, session_id: str, title: str, content: str,
              updated_at: int) -> bool:
        """Insert or refresh one conversation; returns False when already current."""
        if not session_id:
            return False
        with self._connect() as connection:
            return self._index_one(connection, session_id, title, content, updated_at)

    def index_many(self, documents: Iterable[Dict[str, Any]], progress=None) -> int:
        """Index a batch in one connection: the first-run migration has a thousand
        small files and opening a database per file dominates the cost.

        首次建索引时复用同一个连接，否则每份会话都要开关一次数据库。
        """
        changed = 0
        seen = 0
        with self._connect() as connection:
            for document in documents:
                seen += 1
                if self._index_one(connection, str(document.get("id") or ""),
                                   str(document.get("title") or ""),
                                   str(document.get("content") or ""),
                                   int(document.get("updatedAt") or 0)):
                    changed += 1
                if progress is not None and seen % 25 == 0:
                    progress(seen, changed)
        return seen

    def _index_one(self, connection: sqlite3.Connection, session_id: str, title: str,
                   content: str, updated_at: int) -> bool:
        if not session_id:
            return False
        content = (content or "")[:MAX_INDEX_CHARS]
        row = connection.execute(
            "SELECT updated_at FROM sessions_text WHERE session_id = ?",
            (session_id,),
        ).fetchone()
        if row is not None and int(row["updated_at"]) >= int(updated_at):
            return False
        connection.execute(
            "INSERT INTO sessions_text(session_id, title, content, updated_at) "
            "VALUES (?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET "
            "title = excluded.title, content = excluded.content, "
            "updated_at = excluded.updated_at",
            (session_id, title or "", content, int(updated_at)),
        )
        if self.fts_enabled:
            connection.execute("DELETE FROM sessions_fts WHERE session_id = ?",
                               (session_id,))
            connection.execute(
                "INSERT INTO sessions_fts(session_id, title, content) VALUES (?,?,?)",
                (session_id, title or "", content),
            )
        return True

    def remove(self, session_id: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM sessions_text WHERE session_id = ?", (session_id,))
            if self.fts_enabled:
                connection.execute("DELETE FROM sessions_fts WHERE session_id = ?",
                                   (session_id,))

    # ------------------------------------------------------------------- read

    def search(self, query: str, limit: int = DEFAULT_LIMIT) -> List[Dict[str, Any]]:
        text = _WS_RE.sub(" ", (query or "")).strip()
        if not text:
            return []
        limit = max(1, min(int(limit or DEFAULT_LIMIT), MAX_LIMIT))
        terms = [term for term in text.split(" ") if len(term) >= 3]
        if self.fts_enabled and terms:
            try:
                return self._search_fts(terms, limit)
            except sqlite3.OperationalError:
                LOGGER.warning("session FTS query failed, falling back to LIKE",
                               exc_info=True)
        return self._search_like(text, limit)

    def _search_fts(self, terms: List[str], limit: int) -> List[Dict[str, Any]]:
        match = " OR ".join('"%s"' % term.replace('"', " ") for term in terms)
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT session_id, title, "
                "snippet(sessions_fts, 2, ?, ?, '…', ?) AS snippet, "
                "(SELECT updated_at FROM sessions_text t "
                " WHERE t.session_id = sessions_fts.session_id) AS updated_at "
                "FROM sessions_fts WHERE sessions_fts MATCH ? "
                "ORDER BY bm25(sessions_fts) LIMIT ?",
                (MARK_OPEN, MARK_CLOSE, SNIPPET_TOKENS, match, limit),
            ).fetchall()
        return [{
            "sessionId": row["session_id"],
            "title": row["title"] or "",
            "snippet": row["snippet"] or "",
            "updatedAt": int(row["updated_at"] or 0),
        } for row in rows]

    def _search_like(self, text: str, limit: int) -> List[Dict[str, Any]]:
        needle = "%" + text + "%"
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT session_id, title, content, updated_at FROM sessions_text "
                "WHERE title LIKE ? OR content LIKE ? "
                "ORDER BY updated_at DESC LIMIT ?",
                (needle, needle, limit),
            ).fetchall()
        return [{
            "sessionId": row["session_id"],
            "title": row["title"] or "",
            "snippet": _snippet(row["content"] or "", text),
            "updatedAt": int(row["updated_at"] or 0),
        } for row in rows]


def _snippet(content: str, query: str, width: int = 80) -> str:
    """Highlight the first hit with the same markers the FTS path uses."""
    flat = _WS_RE.sub(" ", content).strip()
    index = flat.lower().find(query.lower())
    if index < 0:
        return flat[:width * 2]
    start = max(0, index - width)
    end = min(len(flat), index + len(query) + width)
    head = "…" if start > 0 else ""
    tail = "…" if end < len(flat) else ""
    return "%s%s%s%s%s%s" % (
        head, flat[start:index], MARK_OPEN, flat[index:index + len(query)],
        MARK_CLOSE, flat[index + len(query):end] + tail)
