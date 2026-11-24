"""Agent teams: named groups of role-library subagents orchestrated by the PM.

A team is a *thin orchestration layer* over the existing ``SUBAGENT.md`` role
library.  Members are references to roles plus a **capability grant**
(``readonly`` / ``write`` / ``command``) and an optional display alias, so users
can freely compose new teams without copying role bodies.  The team body is a
*playbook* injected into the PM (main agent) prompt while the team is active.

Search order (later wins): builtin -> ``~/.agent/teams`` -> ``<project>/.agent/teams``.

Design references: ``docs/AGENT_TEAM_DETAILED_DESIGN.md`` (D-A pre-review,
D-B default off, D-E free composition).  Python 3.9 compatible.
"""

import contextvars
import logging
import os
import re
from typing import Any, Awaitable, Callable, Dict, List, Optional

from pydantic import BaseModel, Field

from agent_core import diagnostics
from agent_core.agent.blackboard import (
    append_artifact,
    make_artifact,
    render_artifacts,
    select_artifacts,
)
from agent_core.agent.session import Session
from agent_core.agent.subagent import (
    CAPABILITIES,
    CAPABILITY_EXTRA_TOOLS,
    CAPABILITY_READONLY,
    SubagentDefinition,
    SubagentService,
    SubagentTask,
)
from agent_core.native import truncate_to_budget
from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

BUILTIN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "teams_builtin")
TEAM_FILE = "TEAM.md"

REPORT_TOKENS = 500
_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n?(.*)$", re.S)


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------

class TeamMember(BaseModel):
    """One role referenced by a team, plus the capability the team grants it."""

    role: str
    capability: str = CAPABILITY_READONLY
    alias: str = ""
    model: str = ""
    phases: List[str] = Field(default_factory=list)

    def normalised_capability(self) -> str:
        cap = (self.capability or "").strip().lower()
        return cap if cap in CAPABILITIES else CAPABILITY_READONLY

    def display_name(self) -> str:
        return (self.alias or self.role).strip()


class TeamDefinition(BaseModel):
    name: str
    description: str = ""
    lead: str = "项目经理"
    members: List[TeamMember] = Field(default_factory=list)
    body: str = ""
    scope: str = "builtin"
    path: str = ""

    def member(self, role_or_alias: str) -> Optional[TeamMember]:
        """Resolve a member by role, alias or display name."""
        target = (role_or_alias or "").strip()
        if not target:
            return None
        for item in self.members:
            if target in (item.role, item.alias, item.display_name()):
                return item
        return None


# ---------------------------------------------------------------------------
# Parsing (frontmatter with a nested ``members`` list)
# ---------------------------------------------------------------------------

def _strip_quotes(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def _scalar(value: str) -> Any:
    value = _strip_quotes(value)
    if value.startswith("[") and value.endswith("]"):
        return [_strip_quotes(part) for part in value[1:-1].split(",") if part.strip()]
    return value


def _parse_members(raw_members: List[Dict[str, Any]]) -> List[TeamMember]:
    members: List[TeamMember] = []
    for item in raw_members:
        role = str(item.get("role") or "").strip()
        if not role:
            continue
        phases = item.get("phases") or []
        if isinstance(phases, str):
            phases = [part.strip() for part in phases.split(",") if part.strip()]
        members.append(TeamMember(
            role=role,
            capability=str(item.get("capability") or CAPABILITY_READONLY),
            alias=str(item.get("alias") or ""),
            model=str(item.get("model") or ""),
            phases=[str(p) for p in phases] if isinstance(phases, list) else [],
        ))
    return members


def parse_team(text: str, path: str, scope: str) -> Optional[TeamDefinition]:
    """Parse a TEAM.md file. Returns None when there is no ``name``.

    ``members`` is a nested list; the shared ``skills.loader.parse_frontmatter``
    only understands flat lists, so the block is parsed here.
    """
    match = _FRONTMATTER_RE.match((text or "").lstrip("\ufeff"))
    if not match:
        return None
    block, body = match.group(1), match.group(2)
    meta: Dict[str, Any] = {}
    raw_members: List[Dict[str, Any]] = []
    current: Optional[Dict[str, Any]] = None
    in_members = False
    for raw_line in block.splitlines():
        line = raw_line.rstrip()
        stripped = line.strip()
        if not stripped:
            continue
        indent = len(line) - len(line.lstrip(" "))
        if stripped == "members:":
            in_members = True
            current = None
            continue
        if in_members and stripped.startswith("- "):
            current = {}
            raw_members.append(current)
            item = stripped[2:]
            if ":" in item:
                key, _, value = item.partition(":")
                current[key.strip()] = _scalar(value)
            continue
        if in_members and current is not None and indent > 0 and ":" in stripped:
            key, _, value = stripped.partition(":")
            current[key.strip()] = _scalar(value)
            continue
        if indent == 0 and ":" in stripped and not stripped.startswith("- "):
            in_members = False
            current = None
        if ":" in stripped:
            key, _, value = stripped.partition(":")
            meta[key.strip()] = _scalar(value)

    name = str(meta.get("name") or "").strip()
    if not name:
        return None
    return TeamDefinition(
        name=name,
        description=str(meta.get("description") or "").strip(),
        lead=str(meta.get("lead") or "项目经理").strip() or "项目经理",
        members=_parse_members(raw_members),
        body=body.strip(),
        scope=scope,
        path=path,
    )


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------

class TeamLoader:
    def __init__(self, project_root: Optional[str] = None,
                 global_dir: Optional[str] = None) -> None:
        self.project_root = project_root
        self.global_dir = global_dir or os.path.join(os.path.expanduser("~"), ".agent", "teams")
        self._cache: Optional[Dict[str, TeamDefinition]] = None

    def set_project_root(self, project_root: Optional[str]) -> None:
        if project_root != self.project_root:
            self.project_root = project_root
            self._cache = None

    def _candidate_dirs(self):
        dirs = [(BUILTIN_DIR, "builtin")]
        if self.global_dir:
            dirs.append((self.global_dir, "global"))
        if self.project_root:
            dirs.append((os.path.join(self.project_root, ".agent", "teams"), "project"))
        return dirs

    def _read_dir(self, directory: str, scope: str) -> List[TeamDefinition]:
        if not os.path.isdir(directory):
            return []
        found: List[TeamDefinition] = []
        for entry in sorted(os.listdir(directory)):
            entry_path = os.path.join(directory, entry)
            if os.path.isdir(entry_path):
                team_path = os.path.join(entry_path, TEAM_FILE)
                if not os.path.isfile(team_path):
                    continue
            elif entry.endswith(".md"):
                team_path = entry_path
            else:
                continue
            try:
                with open(team_path, "r", encoding="utf-8") as handle:
                    team = parse_team(handle.read(), team_path, scope)
            except OSError:
                LOGGER.warning("cannot read team %s", team_path)
                continue
            if team is not None:
                found.append(team)
        return found

    def load_all(self, refresh: bool = False) -> Dict[str, TeamDefinition]:
        if self._cache is not None and not refresh:
            return self._cache
        teams: Dict[str, TeamDefinition] = {}
        for directory, scope in self._candidate_dirs():
            for team in self._read_dir(directory, scope):
                if team.name in teams:
                    LOGGER.info("team %s overridden by %s scope", team.name, scope)
                teams[team.name] = team
        self._cache = teams
        return teams

    def list_teams(self) -> List[TeamDefinition]:
        return sorted(self.load_all().values(), key=lambda team: team.name)

    def get(self, name: str) -> Optional[TeamDefinition]:
        return self.load_all().get((name or "").strip())


# ---------------------------------------------------------------------------
# Tool definitions
# ---------------------------------------------------------------------------

TEAM_TOOLS: List[ToolDef] = [
    ToolDef(
        name="team_list",
        description="列出可用的 Agent 团队（成员、角色、能力档）与当前启用状态。",
        parameters={"type": "object", "properties": {}, "required": []},
        permission=Permission.AUTO,
        remote=False,
    ),
    ToolDef(
        name="team_dispatch",
        description=(
            "向当前启用的团队成员派发任务并汇总结果。members 只能取自团队 roster；"
            "每个子任务要自带 goal（目标/范围/非目标/文件/验收标准），成员看不到你的对话。"
            "写/命令成员按团队授予的 capability 执行，实现阶段前请先 team_preapprove。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "tasks": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "role": {"type": "string", "description": "成员 role 或 alias"},
                            "goal": {"type": "string", "description": "该子任务的目标与验收标准"},
                            "context": {"type": "string", "description": "可选：只对该成员有效的背景/文件线索"},
                            "writes": {"type": "array", "items": {"type": "string"},
                                       "description": "可选：该成员本次负责的文件/接口（用于冲突防护）"},
                        },
                        "required": ["role", "goal"],
                    },
                    "description": "混合派发列表，每项一个成员任务",
                },
                "context": {"type": "string", "description": "可选：所有成员共享的背景"},
            },
            "required": ["tasks"],
        },
        permission=Permission.AUTO,
        timeout_sec=900.0,
        remote=False,
    ),
    ToolDef(
        name="team_preapprove",
        description=(
            "实现阶段开始前发起一次预审检查点：把计划与首个 diff 交用户确认一次。"
            "通过后本阶段内写/命令成员的常规写与命令自动执行；delete_file、"
            "白名单外命令与危险命令仍逐次确认/拒绝。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "plan": {"type": "string", "description": "实现计划与影响面"},
                "first_diff": {"type": "string", "description": "首个改动的 diff 或摘要"},
                "files": {"type": "array", "items": {"type": "string"},
                          "description": "本阶段将改动的文件（留空=本阶段全部写都算预审范围）"},
            },
            "required": ["plan"],
        },
        permission=Permission.AUTO,
        remote=False,
    ),
    ToolDef(
        name="team_post",
        description="向团队黑板写入一条交付物（research/plan/impl/test/review/note），供 PM 与成员共享。",
        parameters={
            "type": "object",
            "properties": {
                "kind": {"type": "string",
                         "enum": ["research", "plan", "impl", "test", "review", "note"]},
                "title": {"type": "string"},
                "body": {"type": "string"},
                "author_role": {"type": "string", "description": "作者（成员 role/alias）"},
                "refs": {"type": "array", "items": {"type": "string"},
                         "description": "相关文件/位置引用"},
            },
            "required": ["kind", "body"],
        },
        permission=Permission.AUTO,
        remote=False,
    ),
    ToolDef(
        name="team_read",
        description="读取团队黑板上的交付物，可按 kind 或作者过滤。",
        parameters={
            "type": "object",
            "properties": {
                "kind": {"type": "string"},
                "author_role": {"type": "string"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 200},
            },
            "required": [],
        },
        permission=Permission.AUTO,
        remote=False,
    ),
]

# The running session/team-run of the current task; bound by the server per chat.
_active_session: contextvars.ContextVar[Optional[Session]] = contextvars.ContextVar(
    "team_session", default=None)
_current_run: contextvars.ContextVar[str] = contextvars.ContextVar("team_run", default="")

PreapproveFn = Callable[[Dict[str, Any]], Awaitable[bool]]
ArtifactFn = Callable[[Dict[str, Any]], None]


def bind_session(session: Session):
    """Make ``session`` the target of team_post/team_read for the current task."""
    return _active_session.set(session)


def reset_session(token) -> None:
    _active_session.reset(token)


def bind_run(run_id: str):
    """Tag the current task with its team-run id (used by preapprove state)."""
    return _current_run.set(run_id or "")


def reset_run(token) -> None:
    _current_run.reset(token)


# ---------------------------------------------------------------------------
# Service
# ---------------------------------------------------------------------------

class TeamService:
    def __init__(self, loader: TeamLoader, subagents: SubagentService, get_config,
                 preapprove_fn: Optional[PreapproveFn] = None,
                 on_artifact: Optional[ArtifactFn] = None,
                 on_event: Optional[Callable[..., Awaitable[None]]] = None) -> None:
        self.loader = loader
        self.subagents = subagents
        self._get_config = get_config
        self._preapprove_fn = preapprove_fn
        self._on_artifact = on_artifact
        self._on_event = on_event
        # run_id -> set of approved files (empty set means "whole phase").
        self._preapproved: Dict[str, set] = {}
        # run_id -> member tasks already dispatched, against ``team_turn_cap``.
        self._dispatched: Dict[str, int] = {}

    # ------------------------------------------------------------- wiring

    def register_tools(self, registry: ToolRegistry) -> None:
        handlers = {
            "team_list": self._list_handler,
            "team_dispatch": self._dispatch_handler,
            "team_preapprove": self._preapprove_handler,
            "team_post": self._post_handler,
            "team_read": self._read_handler,
        }
        for tool_def in TEAM_TOOLS:
            registry.register(tool_def, handlers.get(tool_def.name))

    # ------------------------------------------------------------- prompt

    def active_team(self) -> Optional[TeamDefinition]:
        name = (self._get_config().active_team or "").strip()
        if not name:
            return None
        team = self.loader.get(name)
        if team is None:
            LOGGER.warning("active team %r not found", name)
        return team

    def active_prompt_text(self) -> Optional[str]:
        """Playbook + roster injected as the PM base prompt; None when disabled."""
        team = self.active_team()
        if team is None:
            return None
        parts = [team.body.strip()] if team.body.strip() else []
        parts.append(self.roster_text(team))
        return "\n\n".join(part for part in parts if part)

    def roster_text(self, team: Optional[TeamDefinition] = None) -> str:
        team = team or self.active_team()
        if team is None:
            return ""
        lines = ["当前启用的团队：%s（%s）" % (team.name, team.description),
                 "成员（由项目经理=你负责编排）："]
        for member in team.members:
            role_def = self.subagents.loader.get(member.role)
            tools = self._effective_tools(member, role_def)
            desc = role_def.description if role_def else "角色库缺少定义"
            lines.append("- %s（role=%s，能力=%s）：%s；工具 %s" % (
                member.display_name(), member.role, member.normalised_capability(),
                desc, ", ".join(tools) or "无"))
        lines.extend([
            "编排规则：",
            "1. 按请求选择必要阶段，不要为凑流程启动全员；跳过有实质风险的验证时说明原因；",
            "2. 调研结论/计划/实现/测试/审查用 team_post 写黑板，跨成员交接引用对应交付物；",
            "3. 实现阶段开始前调用 team_preapprove 做一次预审；通过后本阶段写/命令自动执行；",
            "4. 不并行修改相同文件或耦合接口；相关研究集中派发；",
            "5. 成员看不到你的对话，goal 必须自带目标、范围、非目标、文件与验收标准。",
        ])
        return "\n".join(lines)

    # ------------------------------------------------------------- tools

    def list_payload(self) -> List[Dict[str, Any]]:
        """Machine-readable roster for the Shell (`team/list`)."""
        active = (self._get_config().active_team or "").strip()
        payload: List[Dict[str, Any]] = []
        for team in self.loader.list_teams():
            payload.append({
                "name": team.name,
                "description": team.description,
                "lead": team.lead,
                "scope": team.scope,
                "active": team.name == active,
                "members": [
                    {
                        "role": member.role,
                        "alias": member.alias,
                        "displayName": member.display_name(),
                        "capability": member.normalised_capability(),
                        "available": self.subagents.loader.get(member.role) is not None,
                    }
                    for member in team.members
                ],
            })
        return payload

    async def _list_handler(self, call: ToolCall) -> ToolResult:
        teams = self.loader.list_teams()
        if not teams:
            return ToolResult(tool_call_id=call.id, ok=True, content="（没有可用的团队）")
        active = (self._get_config().active_team or "").strip()
        lines = ["可用团队："]
        for team in teams:
            mark = "  [已启用]" if team.name == active else ""
            members = ", ".join("%s(%s)" % (m.display_name(), m.normalised_capability())
                                for m in team.members)
            lines.append("- %s%s：%s\n  成员：%s" % (team.name, mark, team.description, members))
        return ToolResult(tool_call_id=call.id, ok=True, content="\n".join(lines))

    async def _dispatch_handler(self, call: ToolCall) -> ToolResult:
        team = self.active_team()
        if team is None:
            # The model asked for a team run although no team is enabled: say so in the log,
            # because the transcript only shows a failed tool call.
            # 未启用团队却派发：对话区只显示工具失败，日志里写清原因。
            LOGGER.warning("team_dispatch refused: no active team "
                           "(active_team=%r)", getattr(self._get_config(), "active_team", ""))
            diagnostics.emit("dispatch_error", tool="team_dispatch", reason="no active team",
                             activeTeam=(getattr(self._get_config(), "active_team", "") or None))
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="未启用团队：请先在设置中选择 active_team")
        args: Dict[str, Any] = call.arguments or {}
        raw_tasks = self._normalise_requests(args)
        if not raw_tasks:
            LOGGER.warning("team_dispatch called without tasks")
            diagnostics.emit("dispatch_error", tool="team_dispatch",
                             reason="missing tasks", team=team.name,
                             args=diagnostics.brief(args))
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="缺少参数：需要 tasks 列表（每项含 role 与 goal）")

        shared = str(args.get("context") or "").strip()
        tasks, errors = self._build_tasks(team, raw_tasks, shared)
        for issue in errors:
            LOGGER.warning("team %s dispatch: %s", team.name, issue)
            diagnostics.emit("dispatch_error", tool="team_dispatch", team=team.name,
                             reason=issue)
        cap = max(1, int(getattr(self._get_config(), "team_max_members", 4) or 4))
        if len(tasks) > cap:
            errors.append("超过 team_max_members=%d，本次只派发前 %d 个成员" % (cap, cap))
            tasks = tasks[:cap]
        # ``team_turn_cap`` bounds the whole run, not one dispatch: a model that keeps
        # re-dispatching would otherwise burn the endpoint budget without progress.
        # team_turn_cap 限制整轮运行（不只单次派发），防止反复派发打转。
        left = self._budget_left(_current_run.get())
        if left <= 0:
            LOGGER.warning("team %s dispatch refused: team_turn_cap=%d reached",
                           team.name, self._turn_cap())
            diagnostics.emit("dispatch_error", tool="team_dispatch", team=team.name,
                             reason="turn cap reached", cap=self._turn_cap())
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="本团队运行已达到 team_turn_cap=%d 个成员任务；"
                                    "请先收尾或新建会话再继续。" % self._turn_cap())
        if len(tasks) > left:
            errors.append("受 team_turn_cap 限制，本次只派发前 %d 个成员" % left)
            tasks = tasks[:left]
        if not tasks:
            detail = ("；".join(errors)) if errors else "没有可派发的成员"
            return ToolResult(tool_call_id=call.id, ok=False, error=detail)

        reports = await self.subagents.run_tasks(tasks, self._on_event)
        self._dispatched[_current_run.get()] = (
            self._dispatched.get(_current_run.get(), 0) + len(tasks))
        ok_count = sum(1 for report in reports if report.ok)
        diagnostics.emit("dispatch_end", tool="team_dispatch", team=team.name,
                         roles=[task.role for task in tasks], okCount=ok_count,
                         total=len(reports), ok=ok_count > 0)
        blocks = ["## 团队执行结果（%s，成功 %d/%d）" % (team.name, ok_count, len(reports))]
        for report in reports:
            blocks.append("### %s %s（%.1fs%s）\n%s" % (
                report.task_id, "OK" if report.ok else "FAILED",
                report.duration_ms / 1000.0,
                "·" + report.model if report.model else "",
                truncate_to_budget(report.summary or report.detail, REPORT_TOKENS)))
        if errors:
            blocks.append("未派发/告警：" + "；".join(errors))
        return ToolResult(tool_call_id=call.id, ok=ok_count > 0, content="\n\n".join(blocks))

    async def _preapprove_handler(self, call: ToolCall) -> ToolResult:
        if self.active_team() is None:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="未启用团队：预审只对团队成员生效")
        args: Dict[str, Any] = call.arguments or {}
        files = args.get("files")
        if isinstance(files, str):
            files = [part.strip() for part in files.split(",") if part.strip()]
        if not isinstance(files, list):
            files = []
        payload = {
            "plan": str(args.get("plan") or "").strip(),
            "files": [str(item).strip() for item in files if str(item).strip()],
            "firstDiff": str(args.get("first_diff") or args.get("firstDiff") or "").strip(),
        }
        if self._preapprove_fn is None:
            return ToolResult(tool_call_id=call.id, ok=True,
                              content="当前 Shell 不支持预审确认；成员写/命令仍逐次确认。")
        try:
            approved = bool(await self._preapprove_fn(payload))
        except Exception:  # pragma: no cover - a broken Shell must not fail the task
            LOGGER.exception("preapprove request failed")
            approved = False
        run_id = _current_run.get()
        if approved:
            self._preapproved[run_id] = {self._norm_path(item) for item in payload["files"]}
            return ToolResult(tool_call_id=call.id, ok=True,
                              content="预审已通过：本阶段写/命令自动执行（delete 与危险命令仍需确认）。")
        self._preapproved.pop(run_id, None)
        return ToolResult(tool_call_id=call.id, ok=True,
                          content="预审未通过：成员写/命令仍逐次确认。")

    async def _post_handler(self, call: ToolCall) -> ToolResult:
        session = _active_session.get()
        if session is None:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="当前没有可写入黑板的会话")
        args: Dict[str, Any] = call.arguments or {}
        body = str(args.get("body") or "")
        title = str(args.get("title") or "")
        if not body.strip() and not title.strip():
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="需要 title 或 body 至少一项")
        artifact = make_artifact(
            args.get("kind"), title, body,
            author_role=str(args.get("author_role") or args.get("authorRole") or ""),
            refs=args.get("refs"),
        )
        append_artifact(session.artifacts, artifact)
        if self._on_artifact is not None:
            try:
                self._on_artifact(artifact)
            except Exception:  # pragma: no cover - a broken UI must not fail the tool
                pass
        return ToolResult(tool_call_id=call.id, ok=True,
                          content="已写入黑板：[%s] %s（%s）" % (
                              artifact["kind"], artifact["title"] or "(无标题)",
                              artifact["authorRole"] or "未知成员"))

    async def _read_handler(self, call: ToolCall) -> ToolResult:
        session = _active_session.get()
        if session is None:
            return ToolResult(tool_call_id=call.id, ok=False,
                              error="当前没有可读取黑板的会话")
        args: Dict[str, Any] = call.arguments or {}
        try:
            limit = int(args.get("limit") or 50)
        except (TypeError, ValueError):
            limit = 50
        items = select_artifacts(
            session.artifacts,
            kind=str(args.get("kind") or ""),
            author_role=str(args.get("author_role") or args.get("authorRole") or ""),
            limit=limit,
        )
        return ToolResult(tool_call_id=call.id, ok=True, content=render_artifacts(items))

    # ------------------------------------------------------------- preapprove state

    def should_auto_write(self, run_id: str, path: str) -> bool:
        """True when member writes to ``path`` may skip the diff card (D-A)."""
        if not getattr(self._get_config(), "team_auto_after_preapprove", True):
            return False
        files = self._preapproved.get(run_id or "")
        if files is None:
            return False
        if not files:
            return True
        return self._norm_path(path) in files

    def clear_preapprove(self, run_id: str) -> None:
        self._preapproved.pop(run_id or "", None)

    def clear_run(self, run_id: str) -> None:
        """Drop every per-run record once the chat task ends."""
        self.clear_preapprove(run_id)
        self._dispatched.pop(run_id or "", None)

    def _turn_cap(self) -> int:
        return max(1, int(getattr(self._get_config(), "team_turn_cap", 12) or 12))

    def _budget_left(self, run_id: str) -> int:
        return self._turn_cap() - self._dispatched.get(run_id or "", 0)

    # ------------------------------------------------------------- helpers

    def _build_tasks(self, team: TeamDefinition, raw_tasks: List[Dict[str, Any]],
                     shared: str) -> "tuple[List[SubagentTask], List[str]]":
        tasks: List[SubagentTask] = []
        errors: List[str] = []
        counters: Dict[str, int] = {}
        for item in raw_tasks:
            role_ref = str(item.get("role") or item.get("type") or "").strip()
            goal = str(item.get("goal") or "").strip()
            if not goal:
                continue
            member = team.member(role_ref)
            if member is None:
                errors.append("团队成员不存在: %s" % (role_ref or "(空)"))
                continue
            role_def = self.subagents.loader.get(member.role)
            if role_def is None:
                errors.append("角色库缺少定义: %s（role=%s）" % (member.display_name(), member.role))
                continue
            context = "\n".join(part for part in (
                shared, str(item.get("context") or "").strip()) if part)
            full_goal = goal if not context else "%s\n\n背景与相关线索：\n%s" % (goal, context)
            writes = item.get("writes")
            if isinstance(writes, str):
                writes = [part.strip() for part in writes.split(",") if part.strip()]
            if isinstance(writes, list) and writes:
                full_goal += "\n\n本成员负责的文件/接口：" + ", ".join(str(w) for w in writes)
            counters[member.role] = counters.get(member.role, 0) + 1
            tasks.append(self._make_task(team, member, role_def, full_goal,
                                         counters[member.role]))
        return tasks, errors

    def _make_task(self, team: TeamDefinition, member: TeamMember,
                   role_def: SubagentDefinition, goal: str, index: int) -> SubagentTask:
        capability = member.normalised_capability()
        return SubagentTask(
            id="%s-%d" % (member.role, index),
            goal=goal,
            role=member.role,
            allowed_tools=self._effective_tools(member, role_def),
            capability=capability,
            system_prompt=self._member_prompt(team, member, role_def),
            max_iterations=role_def.max_iterations,
            model=member.model or role_def.model,
            output=role_def.output,
            timeout_sec=role_def.timeout_sec,
        )

    def _effective_tools(self, member: TeamMember,
                         role_def: Optional[SubagentDefinition]) -> List[str]:
        base = list(role_def.allowed_tools()) if role_def else []
        for extra in CAPABILITY_EXTRA_TOOLS.get(member.normalised_capability(), ()):
            if extra not in base:
                base.append(extra)
        return base

    def _member_prompt(self, team: TeamDefinition, member: TeamMember,
                       role_def: SubagentDefinition) -> str:
        capability = member.normalised_capability()
        header = ("你是团队「%s」的成员：%s（角色 %s，能力 %s）。"
                  "只负责被分配的目标，完成后交付结论与交付物；跨成员任务由项目经理协调。"
                  % (team.name, member.display_name(), member.role, capability))
        if capability in ("write", "command"):
            header += ("\n你可以修改文件/执行命令：改文件前先 read_file 确认原文，改完自行验证；"
                       "不要越界改动其他成员负责的文件或接口。")
        else:
            header += "\n你是只读成员，不能修改文件；只给出结论、依据与可执行建议。"
        return header + "\n\n" + role_def.system_prompt()

    @staticmethod
    def _normalise_requests(args: Dict[str, Any]) -> List[Dict[str, Any]]:
        raw = args.get("tasks")
        if isinstance(raw, list):
            return [item for item in raw if isinstance(item, dict)]
        role = str(args.get("role") or args.get("type") or "").strip()
        goal = str(args.get("goal") or "").strip()
        if role and goal:
            return [{"role": role, "goal": goal}]
        return []

    @staticmethod
    def _norm_path(path: str) -> str:
        value = (path or "").strip().replace("\\", "/")
        while value.startswith("./"):
            value = value[2:]
        return value