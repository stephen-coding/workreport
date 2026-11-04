"""Split source files into embeddable chunks.

Functions / classes detected by ``codesoup`` become one chunk each; anything
else falls back to overlapping line windows so every file is still searchable.
"""

import os
from dataclasses import dataclass
from typing import Iterator, List, Optional

from agent_core.native import detect_language, parse_text

# Directories that never belong to the source index.
IGNORED_DIRS = {
    ".git", ".idea", ".gradle", ".venv", "venv", "env", "node_modules",
    "__pycache__", "target", "build", "dist", "out", "vendor", ".next",
    ".nuxt", "coverage", ".mypy_cache", ".pytest_cache", ".tox", "bin", "obj",
}

# Only text/code files are indexed; binaries and lock files are skipped.
INDEXED_EXTENSIONS = {
    ".py", ".java", ".kt", ".kts", ".scala", ".go", ".rs", ".c", ".h",
    ".cpp", ".cc", ".hpp", ".cs", ".ts", ".tsx", ".js", ".jsx", ".vue",
    ".rb", ".php", ".swift", ".dart", ".lua", ".sh", ".ps1", ".sql",
    ".md", ".rst", ".html", ".css", ".scss", ".yml", ".yaml", ".toml",
    ".gradle", ".xml", ".json",
}

MAX_FILE_BYTES = 400 * 1024
MAX_CHUNK_CHARS = 2400
WINDOW_LINES = 60
WINDOW_OVERLAP = 10


@dataclass
class Chunk:
    path: str          # project-relative path with forward slashes
    lang: Optional[str]
    symbol: str        # function/class name, or "" for window chunks
    start_line: int    # 1-based
    end_line: int
    text: str          # body stored and shown to the model
    embed_text: str    # text sent to the embedding model (with a header)

    def identity(self) -> str:
        return "%s|%s|%d" % (self.path, self.symbol, self.start_line)


def is_indexable(path: str) -> bool:
    ext = os.path.splitext(path)[1].lower()
    return ext in INDEXED_EXTENSIONS


def iter_source_files(root: str) -> Iterator[str]:
    """Yield project-relative paths of indexable files, skipping vendor dirs."""
    if not root or not os.path.isdir(root):
        return
    for current, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS and not d.startswith(".git")]
        for name in files:
            if not is_indexable(name):
                continue
            absolute = os.path.join(current, name)
            try:
                if os.path.getsize(absolute) > MAX_FILE_BYTES:
                    continue
            except OSError:
                continue
            relative = os.path.relpath(absolute, root).replace("\\", "/")
            yield relative


def chunk_file(relative_path: str, content: str) -> List[Chunk]:
    if not content.strip():
        return []
    lang = detect_language(relative_path, content)
    lines = content.splitlines()
    chunks: List[Chunk] = []

    if lang:
        try:
            parsed = parse_text(content, lang)
        except Exception:  # pragma: no cover - parser failure must not break indexing
            parsed = None
        for node in getattr(parsed, "functions", []) or []:
            start = max(1, int(node.start_line))
            end = min(len(lines), int(node.end_line))
            if end < start:
                continue
            body = "\n".join(lines[start - 1:end]).strip()
            if len(body) < 16:
                continue
            chunks.append(_make_chunk(relative_path, lang, node.name, start, end,
                                      body[:MAX_CHUNK_CHARS]))

    if chunks:
        return chunks

    for start in range(0, len(lines), WINDOW_LINES - WINDOW_OVERLAP):
        window = lines[start:start + WINDOW_LINES]
        body = "\n".join(window).strip()
        if len(body) < 40:
            continue
        chunks.append(_make_chunk(relative_path, lang, "", start + 1,
                                  start + len(window), body[:MAX_CHUNK_CHARS]))
    return chunks


def _make_chunk(path: str, lang: Optional[str], symbol: str, start: int,
                end: int, body: str) -> Chunk:
    header = "%s (%s:%d-%d)" % (path, symbol or "block", start, end)
    return Chunk(
        path=path,
        lang=lang,
        symbol=symbol,
        start_line=start,
        end_line=end,
        text=body,
        embed_text=header + "\n" + body,
    )
