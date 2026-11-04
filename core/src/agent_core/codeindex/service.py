"""Semantic code index: incremental build plus retrieval tools.

The index lives in ``<data-dir>/code_index.db`` next to the memory database and
is scoped per project root, so several projects can share one Core data dir.
"""

import asyncio
import hashlib
import logging
import os
from typing import Any, Callable, Dict, List, Optional

from agent_core.codeindex.chunker import chunk_file, iter_source_files
from agent_core.codeindex.embeddings import Embedder
from agent_core.codeindex.store import CodeStore
from agent_core.protocol import ChatConfig, ErrorCode, RpcError, ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

TOOL_OUTPUT_CHARS = 1200

INDEX_TOOLS: List[ToolDef] = [
    ToolDef(
        name="semantic_search",
        description=(
            "按语义检索代码库（embedding + 余弦相似度），适合“这段逻辑在哪实现”这类"
            "关键词搜不到的问题。首次使用前若索引为空，先调用 build_index。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "自然语言或代码片段"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20,
                          "description": "返回条数，默认 6"},
                "path_filter": {"type": "string",
                                "description": "只返回路径包含该子串的代码块"},
            },
            "required": ["query"],
        },
        permission="auto",
        remote=False,
    ),
    ToolDef(
        name="build_index",
        description=(
            "构建/增量更新语义索引。修改过多文件后可调用；full=true 会清空重建。"
            "返回索引的文件数、代码块数与使用的 embedding 模型。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "full": {"type": "boolean", "description": "是否清空后全量重建，默认 false"},
            },
            "required": [],
        },
        permission="auto",
        remote=False,
    ),
]


class CodeIndexService:
    def __init__(self, project_root: Optional[str], data_dir: str,
                 get_config: Callable[[], ChatConfig]) -> None:
        self.project_root = project_root
        self.data_dir = data_dir
        self.get_config = get_config
        os.makedirs(data_dir, exist_ok=True)
        self.store = CodeStore(os.path.join(data_dir, "code_index.db"))
        self._lock: Optional[asyncio.Lock] = None
        self._building = False

    def set_project_root(self, project_root: Optional[str]) -> None:
        if project_root and project_root != self.project_root:
            self.project_root = project_root

    # ---------------------------------------------------------------- helpers

    def _config(self) -> ChatConfig:
        return self.get_config()

    def _embedder(self) -> Embedder:
        config = self._config()
        return Embedder(config, getattr(config, "embedding_model", "") or "")

    def status(self) -> Dict[str, Any]:
        stats = self.store.stats(self.project_root or "")
        stats["building"] = self._building
        stats["root"] = self.project_root or ""
        return stats

    # ------------------------------------------------------------------ build

    async def build(self, full: bool = False,
                    progress: Optional[Callable[[int, int, str], None]] = None) -> Dict[str, Any]:
        root = self.project_root
        if not root or not os.path.isdir(root):
            raise RpcError(ErrorCode.PROTOCOL_ERROR, "未绑定项目根目录，无法构建索引")
        if self._lock is None:
            self._lock = asyncio.Lock()
        async with self._lock:
            self._building = True
            try:
                config = self._config()
                embedder = self._embedder()
                model = embedder.model
                if not model:
                    raise RpcError(ErrorCode.PROTOCOL_ERROR,
                                   "未配置 Embedding 模型（设置页 → 语义索引）")
                stored = self.store.stats(root)
                if full or (stored["chunks"] and stored["model"] and stored["model"] != model):
                    # Vectors from another embedding model are not comparable.
                    self.store.clear(root)

                files = list(iter_source_files(root))
                known = self.store.file_hashes(root)
                pending = []
                for relative in files:
                    content = _read_text(os.path.join(root, relative))
                    if content is None:
                        continue
                    digest = hashlib.sha1(content.encode("utf-8", "ignore")).hexdigest()
                    if known.get(relative) != digest:
                        pending.append((relative, content, digest))

                self.store.prune(root, files)
                embedded_chunks = 0
                for index, (relative, content, digest) in enumerate(pending, start=1):
                    chunks = chunk_file(relative, content)
                    if not chunks:
                        self.store.replace_file(root, relative, digest, None, [], [], model)
                    else:
                        vectors = await embedder.embed([chunk.embed_text for chunk in chunks])
                        embedded_chunks += self.store.replace_file(
                            root, relative, digest, chunks[0].lang, chunks, vectors, model)
                    if progress is not None:
                        progress(index, len(pending), relative)

                stats = self.store.stats(root)
                stats.update({"root": root, "scanned": len(files),
                              "changed": len(pending), "embeddedChunks": embedded_chunks})
                LOGGER.info("code index rebuilt: %s files=%s chunks=%s",
                            root, stats["files"], stats["chunks"])
                return stats
            finally:
                self._building = False

    # ----------------------------------------------------------------- search

    async def search(self, query: str, limit: int = 6,
                     path_filter: str = "") -> List[Dict[str, Any]]:
        query = (query or "").strip()
        if not query:
            return []
        root = self.project_root
        if not root:
            return []
        embedder = self._embedder()
        stats = self.store.stats(root)
        if not stats["chunks"]:
            return []
        vectors = await embedder.embed([query])
        if not vectors or not vectors[0]:
            return []
        limit = max(1, min(int(limit or 6), 20))
        hits = self.store.search(root, vectors[0], embedder.model, limit=limit * 3)
        if path_filter:
            needle = path_filter.replace("\\", "/").lower()
            hits = [hit for hit in hits if needle in str(hit["path"]).lower()]
        return hits[:limit]

    # ------------------------------------------------------------------ tools

    def register_tools(self, registry: ToolRegistry) -> None:
        handlers = {
            "semantic_search": self._search_handler,
            "build_index": self._build_handler,
        }
        for tool_def in INDEX_TOOLS:
            registry.register(tool_def, handlers.get(tool_def.name))

    async def _search_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        query = str(args.get("query") or "").strip()
        if not query:
            return ToolResult(tool_call_id=call.id, ok=False, error="query 不能为空")
        try:
            hits = await self.search(query, int(args.get("limit") or 6),
                                     str(args.get("path_filter") or ""))
        except RpcError as exc:
            return ToolResult(tool_call_id=call.id, ok=False, error=str(exc))
        if not hits:
            stats = self.store.stats(self.project_root or "")
            if not stats["chunks"]:
                return ToolResult(tool_call_id=call.id, ok=True,
                                  content="（语义索引为空，先调用 build_index 建立索引）")
            return ToolResult(tool_call_id=call.id, ok=True, content="（没有匹配的代码块）")
        blocks = []
        for hit in hits:
            blocks.append("### %s:%s (行 %s-%s, 相似度 %s)\n```%s\n%s\n```" % (
                hit["path"], hit["symbol"] or "block", hit["start_line"], hit["end_line"],
                hit["score"], hit["lang"] or "", str(hit["text"])[:TOOL_OUTPUT_CHARS]))
        return ToolResult(tool_call_id=call.id, ok=True, content="\n\n".join(blocks))

    async def _build_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        try:
            stats = await self.build(full=bool(args.get("full")))
        except RpcError as exc:
            return ToolResult(tool_call_id=call.id, ok=False, error=str(exc))
        return ToolResult(tool_call_id=call.id, ok=True, content=(
            "索引已更新：%s 个文件 / %s 个代码块，模型 %s，扫描 %s 个文件，本次更新 %s 个"
            % (stats["files"], stats["chunks"], stats["model"], stats["scanned"],
               stats["changed"])))


def _read_text(path: str) -> Optional[str]:
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as handle:
            return handle.read()
    except OSError:
        return None
