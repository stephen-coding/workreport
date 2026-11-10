"""Subagent definitions and the parallel pool.

A subagent is an independent LLM conversation with its own system prompt, its
own iteration budget and a filtered tool set. Definitions use the same
frontmatter format as skills::

    ---
    name: code-reviewer
    description: 代码审查
    tools: [read_file, search_code, git_diff]
    max_iterations: 6
    output: bullets
    model: qwen2.5-7b
    timeout_sec: 240
    ---
    # 指令
    ...

``output`` 取 text / bullets / json，``model`` 留空即继承主代理的模型，
``timeout_sec`` 是单任务墙钟超时。
子代理一律只读：写工具与 subagent_dispatch 会被剔除，改动文件仍由主代理做。

Search order (later wins): builtin -> ``~/.agent/subagents`` -> ``<project>/.agent/subagents``.
"""

import asyncio
import json
import logging
import os
import time
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

# Subagents are read-only in this version: even a stale definition that lists a write
# tool has it stripped here, so no role can edit files behind the main agent's back.
# 本版本子代理一律只读：旧定义里即使写了写工具也会在这里剔除，改文件仍由主代理统一做。
WRITE_TOOLS = frozenset(("write_file", "edit_file", "delete_file", "revert_file",
                         "apply_patch", "create_file"))

DEFAULT_TIMEOUT_SEC = 240.0
REPORT_TOKENS = 500

# Output contracts: a role that reports back as JSON is far easier to merge than
# free-form prose, and "bullets" keeps review findings one line each.
# 输出契约：JSON 便于主代理合并去重，bullets 让每条审查意见一行一条。
OUTPUT_CONTRACTS = {
    "text": "",
    "bullets": "输出格式：按要点逐行列出（必要时附文件路径与行号），最后给一段不超过 3 行的结论。",
    "json": ("输出格式：只输出一个 JSON 对象，字段为 summary（字符串）、"
             "findings（数组，每项含 location / issue / severity / fix）、confidence（0-1）。"
             "不要包裹 Markdown 代码块。"),
}

TERMINAL_EVENT = Callable[..., Awaitable[None]]


class SubagentDefinition(BaseModel):
    name: str
    description: str = ""
    tools: List[str] = Field(default_factory=list)
    max_iterations: int = 5
    # Empty model means "inherit the main agent's endpoint/model".
    # 留空即继承主代理的模型；填了就用同一个端点的另一个模型跑这个角色。
    model: str = ""
    output: str = "text"
    timeout_sec: float = DEFAULT_TIMEOUT_SEC
    body: str = ""
    scope: str = "builtin"
    path: str = ""

    def allowed_tools(self) -> List[str]:
        declared = self.tools or list(DEFAULT_TOOLS)
        return [name for name in declared
                if name not in WRITE_TOOLS and name != "subagent_dispatch"]

    def system_prompt(self) -> str:
        header = ("你现在是一个独立的子代理（%s），只负责完成被分配的目标；"
                  "完成后用简洁中文给出结论与依据，不要闲聊。" % self.name)
        if self.description:
            header += "\n职责: " + self.description
        contract = OUTPUT_CONTRACTS.get(self.output)
        if contract:
            header += "\n" + contract
        return header + "\n\n" + self.body.strip()


class SubagentTask(BaseModel):
    id: str
    goal: str
    role: str = "general"
    allowed_tools: List[str] = Field(default_factory=list)
    system_prompt: str = ""
    max_iterations: int = 5
    model: str = ""
    output: str = "text"
    timeout_sec: float = DEFAULT_TIMEOUT_SEC


class SubagentReport(BaseModel):
    task_id: str
    ok: bool
    summary: str
    detail: str = ""
    role: str = ""
    model: str = ""
    duration_ms: int = 0


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
    output = str(meta.get("output") or "text").strip().lower()
    if output not in OUTPUT_CONTRACTS:
        LOGGER.warning("subagent %s has unknown output %r, using text", name, output)
        output = "text"
    try:
        timeout_sec = float(str(meta.get("timeout_sec") or DEFAULT_TIMEOUT_SEC))
    except ValueError:
        timeout_sec = DEFAULT_TIMEOUT_SEC
    return SubagentDefinition(
        name=name,
        description=str(meta.get("description") or "").strip(),
        tools=[str(t) for t in tools] if isinstance(tools, list) else [str(tools)],
        max_iterations=max(1, iterations),
        model=str(meta.get("model") or "").strip(),
        output=output,
        timeout_sec=max(1.0, timeout_sec),
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
        description=("向只读子代理派发任务，拿回结论供你汇总。两种用法：\n"
                     "① tasks 列表：一次混合派发多个角色，如 "
                     "[{type: code-reviewer, goal: ...}, {type: bug-hunter, goal: ...}]；\n"
                     "② type + goals：给同一个角色派多个并行目标（旧用法）。\n"
                     "子代理看不到你的对话，goal 要自带文件路径与背景；子代理不能修改文件。"),
        parameters={
            "type": "object",
            "properties": {
                "tasks": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "type": {"type": "string", "description": "子代理角色名"},
                            "goal": {"type": "string", "description": "该子任务的目标"},
                            "context": {"type": "string",
                                        "description": "可选：只对这个子任务有效的背景与文件线索"},
                            "tools": {"type": "array", "items": {"type": "string"},
                                      "description": "可选：覆盖工具白名单（写工具会被忽略）"},
                        },
                        "required": ["type", "goal"],
                    },
                    "description": "混合派发列表，每项一个独立子任务",
                },
                "type": {"type": "string",
                         "description": "子代理角色名（与 goals 搭配，如 code-reviewer）"},
                "goals": {"type": "array", "items": {"type": "string"},
                          "description": "并行执行的目标列表，每个目标一个子任务（与 type 搭配）"},
                "context": {"type": "string",
                            "description": "可选：所有子任务共享的背景（如项目根、已确认现象）"},
            },
            "required": [],
        },
        permission="auto",
        timeout_sec=900.0,
        remote=False,
    ),
    ToolDef(
        name="subagent_list",
        description="列出可用的子代理角色（职责、默认工具、输出格式、轮次预算）。",
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

    # ------------------------------------------------------------- catalog

    def catalog_text(self) -> str:
        """Role list + delegation policy, injected into the main agent's prompt.

        Keeping this here (instead of hard-coding role names in loop.py) means a new
        SUBAGENT.md shows up in the main agent's instructions without a code change.
        角色清单与委派策略由加载器生成：新增一个 SUBAGENT.md 就会自动出现在主代理的
        提示词里，不需要改 loop.py。
        """
        definitions = self.loader.list_definitions()
        if not definitions:
            return ""
        lines = ["可委派的子代理（只读，不能改文件）："]
        for definition in definitions:
            extra = "，模型 %s" % definition.model if definition.model else ""
            lines.append("- %s：%s（工具 %s；输出 %s；最多 %d 轮%s）" % (
                definition.name, definition.description,
                ", ".join(definition.allowed_tools()) or "无",
                definition.output, definition.max_iterations, extra))
        lines.extend([
            "何时委派：",
            "1. 需要同时从多个角度或多批文件得到结论（审查 / 找 bug / 补测试）时，"
            "一次 subagent_dispatch 用 tasks 列表混合派发；",
            "2. 单个文件的小改动、明确的一步操作，自己动手即可，不要为几行改动派发；",
            "3. 子代理看不到你的对话，goal 必须写清目标、文件路径和你已确认的现象；",
            "4. 子代理只给结论与依据，改文件仍由你统一执行。",
        ])
        return "\n".join(lines)

    # ------------------------------------------------------------- handlers

    async def _list_handler(self, call: ToolCall) -> ToolResult:
        definitions = self.loader.list_definitions()
        if not definitions:
            return ToolResult(tool_call_id=call.id, ok=True, content="（没有可用的子代理）")
        lines = []
        for definition in definitions:
            lines.append("- %s: %s（工具: %s；输出: %s；最多 %d 轮%s）" % (
                definition.name, definition.description,
                ", ".join(definition.allowed_tools()) or "无",
                definition.output, definition.max_iterations,
                "；模型: " + definition.model if definition.model else ""))
        return ToolResult(tool_call_id=call.id, ok=True,
                          content="可用子代理（均只读）：\n" + "\n".join(lines))

    async def _dispatch_handler(self, call: ToolCall) -> ToolResult:
        args: Dict[str, Any] = call.arguments or {}
        requests = self._normalise_requests(args)
        if not requests:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="缺少参数：请用 tasks 列表，或 type + goals")

        shared = str(args.get("context") or "").strip()
        tasks = self._build_tasks(requests, shared)
        if not tasks:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="缺少参数：每个子任务都要有 goal")

        config = self._get_config()
        pool = SubagentPool(config.subagent_concurrency)
        reports = await pool.dispatch(tasks, self._run_task, self._on_event)

        ok_count = sum(1 for report in reports if report.ok)
        roles = "+".join(dict.fromkeys(task.role for task in tasks))
        blocks = ["## 子代理结果（%s，成功 %d/%d）" % (roles, ok_count, len(reports))]
        for report in reports:
            blocks.append("### %s %s（%.1fs%s）\n%s" % (
                report.task_id, "OK" if report.ok else "FAILED",
                report.duration_ms / 1000.0,
                "·" + report.model if report.model else "",
                truncate_to_budget(report.summary or report.detail, REPORT_TOKENS)))
        return ToolResult(tool_call_id=call.id, ok=ok_count > 0, content="\n\n".join(blocks))

    async def _run_task(self, task: SubagentTask) -> SubagentReport:
        started = time.monotonic()
        try:
            report = await asyncio.wait_for(self._execute(task), timeout=task.timeout_sec)
        except asyncio.TimeoutError:
            LOGGER.warning("subagent %s timed out after %.0fs", task.id, task.timeout_sec)
            report = SubagentReport(
                task_id=task.id, ok=False, role=task.role, model=task.model,
                summary="子代理超时：%.0f 秒内没有给出结论，已跳过。" % task.timeout_sec)
        report.role = task.role
        # Show the effective model: a role without its own model inherits the main one.
        # 报告里写实际生效的模型，未单独指定时就等于主代理当前的模型。
        report.model = task.model or (self._get_config().model or "")
        report.duration_ms = int((time.monotonic() - started) * 1000)
        return report

    async def _execute(self, task: SubagentTask) -> SubagentReport:
        provider = self._get_provider()
        base = self._get_config()
        # A role may pin its own model; everything else (endpoint, key, limits) is
        # inherited, so one endpoint can serve a cheap reviewer and a strong planner.
        # 角色可以单独指定模型，端点/密钥等其余参数继续继承主配置。
        config = base.model_copy(update={
            "max_iterations": max(1, task.max_iterations),
            "model": task.model or base.model,
        })
        session = Session("subagent-%s" % task.id)
        registry = self._filtered_registry(task.allowed_tools)

        async def emit_token(_delta: str) -> None:
            return None

        result = await run_chat(session, task.goal, config, provider, emit_token,
                                registry=registry, base_prompt=task.system_prompt)
        text = (result.content or "").strip()
        summary = truncate_to_budget(text, REPORT_TOKENS) if text else "（子代理没有输出）"
        return SubagentReport(task_id=task.id, ok=True, summary=summary, detail=text)

    # -------------------------------------------------------------- helpers

    def _normalise_requests(self, args: Dict[str, Any]) -> List[Dict[str, Any]]:
        """Accept both ``tasks`` (mixed roles) and the legacy ``type`` + ``goals``."""
        raw_tasks = args.get("tasks")
        if isinstance(raw_tasks, list):
            requests = [item for item in raw_tasks if isinstance(item, dict)]
            if requests:
                return requests
        type_name = str(args.get("type") or "").strip() or "general"
        return [{"type": type_name, "goal": goal}
                for goal in self._normalise_goals(args.get("goals"))]

    def _build_tasks(self, requests: List[Dict[str, Any]], shared: str) -> List[SubagentTask]:
        """Turn dispatch requests into tasks, one role definition each."""
        counters: Dict[str, int] = {}
        tasks: List[SubagentTask] = []
        for item in requests:
            role = str(item.get("type") or "general").strip() or "general"
            goal = str(item.get("goal") or "").strip()
            if not goal:
                continue
            definition = self.loader.get(role)
            counters[role] = counters.get(role, 0) + 1
            context = "\n".join(part for part in (
                shared, str(item.get("context") or "").strip()) if part)
            if context:
                goal = "%s\n\n背景与相关线索：\n%s" % (goal, context)
            declared = self._normalise_tools(item.get("tools"))
            tools = declared or (definition.allowed_tools() if definition
                                 else list(DEFAULT_TOOLS))
            tasks.append(SubagentTask(
                id="%s-%d" % (role, counters[role]),
                goal=goal,
                role=role,
                # A per-task override is still read-only: write tools never pass.
                # 即便调用方临时指定了工具，写工具也一律被剔掉。
                allowed_tools=[name for name in tools
                               if name not in WRITE_TOOLS and name != "subagent_dispatch"],
                system_prompt=self._prompt_for(definition),
                max_iterations=definition.max_iterations if definition else 5,
                model=definition.model if definition else "",
                output=definition.output if definition else "text",
                timeout_sec=definition.timeout_sec if definition else DEFAULT_TIMEOUT_SEC,
            ))
        return tasks

    @staticmethod
    def _prompt_for(definition: Optional[SubagentDefinition]) -> str:
        if definition is not None:
            return definition.system_prompt()
        return ("你现在是一个独立的子代理，只负责完成被分配的目标；"
                "完成后用简洁中文给出结论与依据。你不能修改文件。")

    def _filtered_registry(self, allowed: List[str]) -> ToolRegistry:
        filtered = ToolRegistry()
        for name in allowed:
            if name == "subagent_dispatch" or name in WRITE_TOOLS:
                continue  # no recursive fan-out, no writes from a subagent
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
