"""Memory service: SQLite index + Markdown mirror + the built-in memory tools."""

import logging
import os
from typing import Any, Dict, List, Optional

from agent_core.memory.files import MemoryFiles
from agent_core.memory.store import MemoryRecord, MemoryStore
from agent_core.protocol import ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

OVERVIEW_BUDGET_CHARS = 4000

MEMORY_TOOLS: List[ToolDef] = [
    ToolDef(
        name="memory_recall",
        description=(
            "检索长期记忆。mode=overview 返回记忆索引（便宜）；"
            "mode=search 配合 keywords 做全文检索，返回最相关的若干条正文。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "mode": {"type": "string", "enum": ["overview", "search"],
                         "description": "检索模式，默认 overview"},
                "keywords": {"type": "array", "items": {"type": "string"},
                             "description": "mode=search 时的关键词"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20,
                          "description": "最多返回条数，默认 5"},
            },
            "required": [],
        },
        permission="auto",
        remote=False,
    ),
    ToolDef(
        name="memory_save",
        description=(
            "保存一条长期记忆（跨会话有效）。用于记录项目约定、架构决策、用户偏好等"
            "稳定事实；不要保存临时进度。同 title 会覆盖更新。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "简短标题，5-20 字"},
                "content": {"type": "string", "description": "记忆正文，事实性陈述"},
                "category": {"type": "string", "description": "分类，如 project_info / development_spec / pitfall"},
                "keywords": {"type": "array", "items": {"type": "string"},
                             "description": "检索关键词"},
                "scope": {"type": "string", "enum": ["project", "global"],
                          "description": "project 仅当前项目，global 全局，默认 project"},
            },
            "required": ["title", "content"],
        },
        permission="auto",
        remote=False,
    ),
    ToolDef(
        name="memory_delete",
        description="按 id 删除一条记忆（索引与 Markdown 文件同时删除）。",
        parameters={
            "type": "object",
            "properties": {
                "memory_id": {"type": "integer", "minimum": 1, "description": "记忆 id"},
            },
            "required": ["memory_id"],
        },
        permission="confirm",
        remote=False,
    ),
]


class MemoryService:
    def __init__(self, project_root: Optional[str], data_dir: str) -> None:
        self.project_root = project_root
        self.data_dir = data_dir
        os.makedirs(data_dir, exist_ok=True)

        project_memory_dir = (os.path.join(project_root, ".agent", "memories")
                              if project_root else os.path.join(data_dir, "memories"))
        global_memory_dir = os.path.join(os.path.expanduser("~"), ".agent", "memories")
        self.files = MemoryFiles(project_memory_dir, global_memory_dir)
        self.store = MemoryStore(os.path.join(data_dir, "memory.db"))
        self._overview_cache: Optional[str] = None

    # ------------------------------------------------------------------ query

    def overview_text(self) -> str:
        if self._overview_cache is not None:
            return self._overview_cache
        text = self.files.read_overview()
        if not text:
            records = self._all_records()
            if records:
                text = self.files.rebuild_overview(records)
        self._overview_cache = text[:OVERVIEW_BUDGET_CHARS]
        return self._overview_cache

    def _all_records(self) -> List[MemoryRecord]:
        return self.store.list_all()

    def search(self, keywords: List[str], limit: int = 5) -> List[MemoryRecord]:
        project_scope = self.store.search(keywords, scope="project",
                                          project_path=self.project_root, limit=limit)
        if len(project_scope) >= limit:
            return project_scope
        global_scope = self.store.search(keywords, scope="global", limit=limit)
        merged = project_scope + [r for r in global_scope if r.id not in {p.id for p in project_scope}]
        return merged[:limit]

    # ------------------------------------------------------------------ write

    def save(self, title: str, content: str, category: str = "project_info",
             keywords: Optional[List[str]] = None, scope: str = "project") -> MemoryRecord:
        record = MemoryRecord(
            title=title.strip(),
            content=content.strip(),
            category=category or "project_info",
            keywords=[k.strip() for k in (keywords or []) if k and k.strip()],
            scope="global" if scope == "global" else "project",
            project_path=None if scope == "global" else self.project_root,
        )
        record.id = self.store.save(record)
        saved = self.store.get(record.id) or record
        self.files.write(saved)
        self._overview_cache = None
        self.files.rebuild_overview(self._all_records())
        return saved

    def delete(self, memory_id: int) -> bool:
        record = self.store.get(memory_id)
        if record is None:
            return False
        if not self.store.delete(memory_id):
            return False
        self.files.remove(record)
        self._overview_cache = None
        self.files.rebuild_overview(self._all_records())
        return True

    # ------------------------------------------------------------------ tools

    def register_tools(self, registry: ToolRegistry) -> None:
        handlers = {
            "memory_recall": self._recall_handler,
            "memory_save": self._save_handler,
            "memory_delete": self._delete_handler,
        }
        for tool_def in MEMORY_TOOLS:
            registry.register(tool_def, handlers.get(tool_def.name))

    async def _recall_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        mode = args.get("mode") or "overview"
        if mode == "overview":
            text = self.overview_text()
            return ToolResult(tool_call_id=call.id, ok=True,
                              content=text or "（暂无记忆）")
        keywords = args.get("keywords") or []
        if isinstance(keywords, str):
            keywords = [keywords]
        limit = int(args.get("limit") or 5)
        records = self.search(keywords, limit=max(1, min(limit, 20)))
        if not records:
            return ToolResult(tool_call_id=call.id, ok=True, content="（没有匹配的记忆）")
        blocks = ["## [%s] %s\n%s" % (r.id, r.title, r.content.strip()) for r in records]
        return ToolResult(tool_call_id=call.id, ok=True, content="\n\n".join(blocks))

    async def _save_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        title = (args.get("title") or "").strip()
        content = (args.get("content") or "").strip()
        if not title or not content:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="title 与 content 不能为空")
        record = self.save(
            title=title,
            content=content,
            category=args.get("category") or "project_info",
            keywords=args.get("keywords") or [],
            scope=args.get("scope") or "project",
        )
        return ToolResult(tool_call_id=call.id, ok=True,
                          content="已保存记忆 #%s: %s" % (record.id, record.title))

    async def _delete_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        try:
            memory_id = int(args.get("memory_id"))
        except (TypeError, ValueError):
            return ToolResult(tool_call_id=call.id, ok=False, error="memory_id 必须是整数")
        if not self.delete(memory_id):
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="记忆 #%s 不存在" % memory_id)
        return ToolResult(tool_call_id=call.id, ok=True,
                          content="已删除记忆 #%s" % memory_id)
