"""SQLite storage for embedded code chunks.

Vectors are stored as normalised float32 blobs; search is a brute-force dot
product (cosine) over the rows of the current project and embedding model.
numpy is used when available, with a pure-Python fallback so the packaged Core
keeps working without extra dependencies.
"""

import array
import logging
import math
import sqlite3
import time
from typing import Dict, List, Optional, Sequence, Tuple

LOGGER = logging.getLogger(__name__)

try:  # pragma: no cover - depends on the runtime environment
    import numpy as _np
except Exception:  # ImportError / broken wheel
    _np = None

SCHEMA = """
CREATE TABLE IF NOT EXISTS code_files (
  project_path TEXT NOT NULL,
  path TEXT NOT NULL,
  file_hash TEXT NOT NULL,
  updated_at INTEGER,
  PRIMARY KEY (project_path, path)
);
CREATE TABLE IF NOT EXISTS code_chunks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  project_path TEXT NOT NULL,
  path TEXT NOT NULL,
  lang TEXT,
  symbol TEXT NOT NULL DEFAULT '',
  start_line INTEGER NOT NULL,
  end_line INTEGER NOT NULL,
  file_hash TEXT NOT NULL,
  text TEXT NOT NULL,
  vector BLOB NOT NULL,
  dim INTEGER NOT NULL,
  model TEXT NOT NULL,
  updated_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_code_chunks_path ON code_chunks(project_path, path);
CREATE INDEX IF NOT EXISTS idx_code_chunks_model ON code_chunks(project_path, model);
"""


def normalize(vector: Sequence[float]) -> List[float]:
    norm = math.sqrt(sum(float(v) * float(v) for v in vector))
    if norm <= 0.0:
        return [0.0] * len(vector)
    return [float(v) / norm for v in vector]


def pack(vector: Sequence[float]) -> bytes:
    return array.array("f", normalize(vector)).tobytes()


def unpack(blob: bytes) -> List[float]:
    values = array.array("f")
    values.frombytes(blob)
    return list(values)


class CodeStore:
    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        self._cache: Optional[Tuple[Tuple[str, str, int, int], object, List[dict]]] = None
        with self._connect() as connection:
            connection.executescript(SCHEMA)

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=10.0)
        connection.row_factory = sqlite3.Row
        return connection

    # ------------------------------------------------------------------ write

    def file_hashes(self, project_path: str) -> Dict[str, str]:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT path, file_hash FROM code_files WHERE project_path = ?",
                (project_path,)).fetchall()
        return {row["path"]: row["file_hash"] for row in rows}

    def replace_file(self, project_path: str, path: str, file_hash: str,
                     lang: Optional[str], chunks: List[object],
                     vectors: List[List[float]], model: str) -> int:
        now = int(time.time())
        rows = []
        for chunk, vector in zip(chunks, vectors):
            if not vector:
                continue
            rows.append((project_path, path, lang, chunk.symbol, chunk.start_line,
                         chunk.end_line, file_hash, chunk.text, pack(vector),
                         len(vector), model, now))
        with self._connect() as connection:
            connection.execute(
                "DELETE FROM code_chunks WHERE project_path = ? AND path = ?",
                (project_path, path))
            connection.execute(
                "INSERT INTO code_files(project_path, path, file_hash, updated_at) "
                "VALUES (?,?,?,?) ON CONFLICT(project_path, path) "
                "DO UPDATE SET file_hash = excluded.file_hash, "
                "updated_at = excluded.updated_at",
                (project_path, path, file_hash, now))
            connection.executemany(
                "INSERT INTO code_chunks(project_path, path, lang, symbol, start_line, "
                "end_line, file_hash, text, vector, dim, model, updated_at) "
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        self._cache = None
        return len(rows)

    def prune(self, project_path: str, keep_paths: Sequence[str]) -> int:
        keep = set(keep_paths)
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT DISTINCT path FROM code_files WHERE project_path = ?",
                (project_path,)).fetchall()
            stale = [row["path"] for row in rows if row["path"] not in keep]
            for path in stale:
                connection.execute(
                    "DELETE FROM code_chunks WHERE project_path = ? AND path = ?",
                    (project_path, path))
                connection.execute(
                    "DELETE FROM code_files WHERE project_path = ? AND path = ?",
                    (project_path, path))
        if stale:
            self._cache = None
        return len(stale)

    def clear(self, project_path: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM code_chunks WHERE project_path = ?",
                               (project_path,))
            connection.execute("DELETE FROM code_files WHERE project_path = ?",
                               (project_path,))
        self._cache = None

    # ------------------------------------------------------------------- read

    def stats(self, project_path: str) -> Dict[str, object]:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT COUNT(*) AS chunks, COUNT(DISTINCT path) AS files, "
                "MAX(dim) AS dim, MAX(updated_at) AS updated_at, MAX(model) AS model "
                "FROM code_chunks WHERE project_path = ?", (project_path,)).fetchone()
            files = connection.execute(
                "SELECT COUNT(*) AS total FROM code_files WHERE project_path = ?",
                (project_path,)).fetchone()
        return {
            "chunks": int(row["chunks"] or 0),
            "files": int(files["total"] or 0),
            "dim": int(row["dim"] or 0),
            "model": row["model"] or "",
            "updatedAt": int(row["updated_at"] or 0),
            "numpy": _np is not None,
        }

    def search(self, project_path: str, vector: Sequence[float], model: str,
               limit: int = 8) -> List[Dict[str, object]]:
        if not vector:
            return []
        matrix, rows = self._load(project_path, model)
        if not rows:
            return []
        query = normalize(vector)
        if _np is not None:
            scores = matrix @ _np.asarray(query, dtype="float32")
            order = scores.argsort()[::-1][:limit]
            hits = [(int(i), float(scores[i])) for i in order]
        else:
            scored = [(index, _dot(matrix[index], query)) for index in range(len(rows))]
            scored.sort(key=lambda item: item[1], reverse=True)
            hits = scored[:limit]
        results = []
        for index, score in hits:
            row = rows[index]
            results.append({
                "path": row["path"],
                "symbol": row["symbol"],
                "start_line": row["start_line"],
                "end_line": row["end_line"],
                "lang": row["lang"],
                "score": round(float(score), 4),
                "text": row["text"],
            })
        return results

    def _load(self, project_path: str, model: str):
        with self._connect() as connection:
            meta = connection.execute(
                "SELECT COUNT(*) AS chunks, IFNULL(MAX(id), 0) AS max_id "
                "FROM code_chunks WHERE project_path = ? AND model = ?",
                (project_path, model)).fetchone()
            key = (project_path, model, int(meta["chunks"] or 0), int(meta["max_id"] or 0))
            if self._cache is not None and self._cache[0] == key:
                return self._cache[1], self._cache[2]
            rows = connection.execute(
                "SELECT path, lang, symbol, start_line, end_line, text, vector, dim "
                "FROM code_chunks WHERE project_path = ? AND model = ? ORDER BY id",
                (project_path, model)).fetchall()
        records = [dict(row) for row in rows]
        if not records:
            self._cache = (key, None, [])
            return None, []
        dim = int(records[0]["dim"] or 0)
        usable = [record for record in records if int(record["dim"] or 0) == dim]
        if not usable:
            self._cache = (key, None, [])
            return None, []
        if _np is not None:
            matrix = _np.frombuffer(b"".join(record["vector"] for record in usable),
                                    dtype="float32").reshape(len(usable), dim)
        else:
            matrix = [unpack(record["vector"]) for record in usable]
        self._cache = (key, matrix, usable)
        return matrix, usable


def _dot(left: Sequence[float], right: Sequence[float]) -> float:
    return sum(a * b for a, b in zip(left, right))
