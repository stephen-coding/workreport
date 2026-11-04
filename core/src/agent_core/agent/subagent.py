"""Subagent definitions and the parallel pool.

A subagent is an independent LLM conversation with its own system prompt, its
own iteration budget and a filtered tool set. Definitions use the same
frontmatter format as skills::

    ---
    name: code-reviewer
    description: 代码审查
    tools: [read_file, search_code, git_diff]
    max_iterations: 6
    ---
    # 指令
    ...

Search order (later wins): builtin -> ``~/.agent/subagents`` -> ``<project>/.agent/subagents``.
"""

import asyncio
import logging
import os
from typing import Any, Awaitable, Callable, Dict, List, Optional

from pydantic import BaseModel, Field

from agent_core.agent.loop import run_chat
from agent_core.agent.session import Session
from agent_core.native import truncate_to_budget
from agent_core.protocol import ToolCall, ToolDef, ToolResult
from agent_core.skills.loader import parse_frontmatter
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

BUILTIN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "subagents_builtin")
SUBAGENT_FILE = "SUBAGENT.md"

# Tools a subagent may use when its definition does not declare a whitelist.
# 未声明白名单时的默认只读工具集（绝不包含 subagent_dispatch）。
DEFAULT_TOOLS = ("read_file", "list_dir", "search_code", "git_log", "git_diff",
                 "memory_recall", "skill_load")

TERMINAL_EVENT = Callable[..., Awaitable[None]]


class SubagentDefinition(BaseModel):
    name: str
    description: str = ""
    tools: List[str] = Field(default_factory=list)
    max_iterations: int = 5
    body: str = ""
    scope: str = "builtin"
    path: str = ""

    def system_prompt(self) -> str:
        header = ("你现在是一个独立的子代理（%s），只负责完成被分配的目标；"
                  "完成后用简洁中文给出结论与依据，不要闲聊。" % self.name)
        if self.description:
            header += "\n职责: " + self.description
        return header + "\n\n" + self.body.strip()


class SubagentTask(BaseModel):
    id: str
    goal: str
    allowed_tools: List[str] = Field(default_factory=list)
    system_prompt: str = ""
    max_iterations: int = 5


class SubagentReport(BaseModel):
    task_id: str
    ok: bool
    summary: str
    detail: str = ""


# ---------------------------------------------------------------------------
# Loading definitions
# ---------------------------------------------------------------------------

def parse_definition(text: str, path: str, scope: str) -> Optional[SubagentDefinition]:
    meta, body = parse_frontmatter(text)
    name = str(meta.get("name") or "").strip()
    if not name:
        return None
    tools = meta.get("tools") or []
    try:
        iterations = int(str(meta.get("max_iterations") or 5))
    except ValueError:
        iterations = 5
    return SubagentDefinition(
        name=name,
        description=str(meta.get("description") or "").strip(),
        tools=[str(t) for t in tools] if isinstance(tools, list) else [str(tools)],
        max_iterations=max(1, iterations),
        body=body.strip(),
        scope=scope,
        path=path,
    )


class SubagentLoader:
    def __init__(self, project_root: Optional[str] = None,
                 global_dir: Optional[str] = None) -> None:
        self.project_root = project_root
        self.global_dir = global_dir or os.path.join(os.path.expanduser("~"), ".agent", "subagents")
        self._cache: Optional[Dict[str, SubagentDefinition]] = None

    def set_project_root(self, project_root: Optional[str]) -> None:
        if project_root != self.project_root:
            self.project_root = project_root
            self._cache = None

    def _candidate_dirs(self):
        dirs = [(BUILTIN_DIR, "builtin")]
        if self.global_dir:
            dirs.append((self.global_dir, "global"))
        if self.project_root:
            dirs.append((os.path.join(self.project_root, ".agent", "subagents"), "project"))
        return dirs

    def _read_dir(self, directory: str, scope: str) -> List[SubagentDefinition]:
        if not os.path.isdir(directory):
            return []
        found: List[SubagentDefinition] = []
        for entry in sorted(os.listdir(directory)):
            entry_path = os.path.join(directory, entry)
            if os.path.isdir(entry_path):
                definition_path = os.path.join(entry_path, SUBAGENT_FILE)
                if not os.path.isfile(definition_path):
                    continue
            elif entry.endswith(".md"):
                definition_path = entry_path
            else:
                continue
            try:
                with open(definition_path, "r", encoding="utf-8") as handle:
                    definition = parse_definition(handle.read(), definition_path, scope)
            except OSError:
                LOGGER.warning("cannot read subagent %s", definition_path)
                continue
            if definition is not None:
                found.append(definition)
        return found

    def load_all(self, refresh: bool = False) -> Dict[str, SubagentDefinition]:
        if self._cache is not None and not refresh:
            return self._cache
        definitions: Dict[str, SubagentDefinition] = {}
        for directory, scope in self._candidate_dirs():
            for definition in self._read_dir(directory, scope):
                definitions[definition.name] = definition
        self._cache = definitions
        return definitions

    def list_definitions(self) -> List[SubagentDefinition]:
        return sorted(self.load_all().values(), key=lambda d: d.name)

    def get(self, name: str) -> Optional[SubagentDefinition]:
        return self.load_all().get(name)


# ---------------------------------------------------------------------------
# Pool
# ---------------------------------------------------------------------------

Runner = Callable[[SubagentTask], Awaitable[SubagentReport]]


class SubagentPool:
    """Runs subagent tasks concurrently, queueing when the pool is full."""

    def __init__(self, max_concurrent: int = 3) -> None:
        self.max_concurrent = max(1, max_concurrent)

    async def dispatch(self, tasks: List[SubagentTask], runner: Runner,
                       on_event: Optional[TERMINAL_EVENT] = None) -> List[SubagentReport]:
        semaphore = asyncio.Semaphore(self.max_concurrent)

        async def run_one(task: SubagentTask) -> SubagentReport:
            async with semaphore:
                if on_event is not None:
                    await on_event("start", task)
                try:
                    report = await runner(task)
                except asyncio.CancelledError:
                    raise
                except Exception as exc:  # pragma: no cover - defensive
                    LOGGER.exception("subagent %s failed", task.id)
                    report = SubagentReport(task_id=task.id, ok=False,
                                            summary="子任务失败: %s" % exc)
                if on_event is not None:
                    await on_event("end", task, report)
                return report

        return list(await asyncio.gather(*(run_one(task) for task in tasks)))


# ---------------------------------------------------------------------------
# Service + tools
# ---------------------------------------------------------------------------

SUBAGENT_TOOLS: List[ToolDef] = [
    ToolDef(
        name="subagent_dispatch",
        description=("并行派发子任务给一个或多个子代理，每个子任务独立运行并返回结论。"
                     "适合同时审查多个文件、并行排查多个问题；子代理不能修改文件。"),
        parameters={
            "type": "object",
            "properties": {
                "type": {"type": "string",
                         "description": "子代理类型，如 code-reviewer / bug-hunter / test-generator"},
                "goals": {"type": "array", "items": {"type": "string"},
                          "description": "并行执行的目标列表，每个目标生成一个子任务"},
                "tools": {"type": "array", "items": {"type": "string"},
                          "description": "可选的工具白名单，覆盖子代理默认配置"},
            },
            "required": ["type", "goals"],
        },
        permission="auto",
        timeout_sec=600.0,
        remote=False,
    ),
    ToolDef(
        name="subagent_list",
        description="列出可用的子代理类型（名称、职责、默认工具）。",
        parameters={"type": "object", "properties": {}, "required": []},
        permission="auto",
        remote=False,
    ),
]


class SubagentService:
    def __init__(self, loader: SubagentLoader, registry: ToolRegistry,
                 get_config, get_provider,
                 on_event: Optional[TERMINAL_EVENT] = None) -> None:
        self.loader = loader
        self.registry = registry
        self._get_config = get_config
        self._get_provider = get_provider
        self._on_event = on_event

    def register_tools(self, registry: ToolRegistry) -> None:
        handlers = {
            "subagent_dispatch": self._dispatch_handler,
            "subagent_list": self._list_handler,
        }
        for tool_def in SUBAGENT_TOOLS:
            registry.register(tool_def, handlers.get(tool_def.name))

    # ------------------------------------------------------------- handlers

    async def _list_handler(self, call: ToolCall) -> ToolResult:
        definitions = self.loader.list_definitions()
        if not definitions:
            return ToolResult(tool_call_id=call.id, ok=True, content="（没有可用的子代理）")
        lines = ["- %s: %s（工具: %s）" % (
            definition.name, definition.description,
            ", ".join(definition.tools or DEFAULT_TOOLS))
            for definition in definitions]
        return ToolResult(tool_call_id=call.id, ok=True, content="可用子代理：\n" + "\n".join(lines))

    async def _dispatch_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        type_name = str(args.get("type") or "").strip() or "general"
        goals = self._normalise_goals(args.get("goals"))
        if not goals:
            return ToolResult(tool_call_id=call.id, ok=False, error="缺少参数 goals")

        definition = self.loader.get(type_name)
        tools = self._normalise_tools(args.get("tools")) or (
            list(definition.tools) if definition else list(DEFAULT_TOOLS))
        prompt = definition.system_prompt() if definition else (
            "你现在是一个独立的子代理，只负责完成被分配的目标；完成后用简洁中文给出结论与依据。")

        tasks = [
            SubagentTask(id="%s-%d" % (type_name, index + 1), goal=goal,
                         allowed_tools=tools, system_prompt=prompt,
                         max_iterations=definition.max_iterations if definition else 5)
            for index, goal in enumerate(goals)
        ]
        config = self._get_config()
        pool = SubagentPool(config.subagent_concurrency)
        reports = await pool.dispatch(tasks, self._run_task, self._on_event)

        ok_count = sum(1 for report in reports if report.ok)
        blocks = ["## 子代理结果（%s，成功 %d/%d）" % (type_name, ok_count, len(reports))]
        for report in reports:
            blocks.append("### %s %s\n%s" % (
                report.task_id, "OK" if report.ok else "FAILED",
                truncate_to_budget(report.summary or report.detail, 500)))
        return ToolResult(tool_call_id=call.id, ok=ok_count > 0, content="\n\n".join(blocks))

    async def _run_task(self, task: SubagentTask) -> SubagentReport:
        provider = self._get_provider()
        config = self._get_config().model_copy(
            update={"max_iterations": max(1, task.max_iterations)})
        session = Session("subagent-%s" % task.id)
        registry = self._filtered_registry(task.allowed_tools)

        async def emit_token(_delta: str) -> None:
            return None

        result = await run_chat(session, task.goal, config, provider, emit_token,
                                registry=registry, base_prompt=task.system_prompt)
        text = (result.content or "").strip()
        summary = truncate_to_budget(text, 400) if text else "（子代理没有输出）"
        return SubagentReport(task_id=task.id, ok=True, summary=summary, detail=text)

    # -------------------------------------------------------------- helpers

    def _filtered_registry(self, allowed: List[str]) -> ToolRegistry:
        filtered = ToolRegistry()
        for name in allowed:
            if name == "subagent_dispatch":
                continue  # never allow recursive fan-out
            tool_def = self.registry.get(name)
            handler = self.registry.handler(name)
            if tool_def is not None and handler is not None:
                filtered.register(tool_def, handler)
        return filtered

    @staticmethod
    def _normalise_goals(raw: Any) -> List[str]:
        if isinstance(raw, str):
            return [raw.strip()] if raw.strip() else []
        if isinstance(raw, list):
            return [str(item).strip() for item in raw if str(item).strip()]
        return []

    @staticmethod
    def _normalise_tools(raw: Any) -> List[str]:
        if isinstance(raw, str):
            return [part.strip() for part in raw.split(",") if part.strip()]
        if isinstance(raw, list):
            return [str(item).strip() for item in raw if str(item).strip()]
        return []
