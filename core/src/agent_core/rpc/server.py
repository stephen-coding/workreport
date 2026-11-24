"""JSON-RPC 2.0 server over stdio (JSON Lines).

Reads frames from stdin on a dedicated thread and dispatches them inside the
asyncio event loop; writes frames back to stdout. All logging goes to stderr so
stdout stays a clean protocol channel.
"""

import asyncio
import contextvars
import json
import logging
import os
import sys
import threading
import time
import uuid
from typing import Any, Dict, List, Optional

from agent_core.agent import ask as ask_tools
from agent_core.agent import subagent as subagent_tools
from agent_core.agent import team as team_tools
from agent_core.agent import todos as todo_tools
from agent_core.agent.commands import decide as decide_command
from agent_core import diagnostics
from agent_core.agent.loop import SYSTEM_PROMPT, run_chat
from agent_core.agent.search import SessionSearch
from agent_core.agent.session import Session, SessionManager, make_title
from agent_core.agent.store import SessionStore
from agent_core.agent.subagent import SubagentLoader, SubagentService
from agent_core.agent.team import TeamLoader, TeamService
from agent_core.codeindex import CodeIndexService
from agent_core.llm import build_provider
from agent_core.llm.completion import complete as complete_inline
from agent_core.mcp import MCPManager, parse_servers
from agent_core.memory import MemoryService
from agent_core.native import native_info
from agent_core.protocol import (
    CORE_VERSION,
    PROTOCOL_VERSION,
    ChatConfig,
    ContextSnapshot,
    ErrorCode,
    Permission,
    RpcError,
    ToolCall,
    ToolResult,
    dumps,
    make_error,
    make_notification,
    make_response,
)
from agent_core.skills import SkillLoader, SkillService
from agent_core.tools.registry import ToolRegistry
from agent_core.tools.remote import SHELL_TOOLS, RemoteToolProxy
from agent_core.usage import UsageLog, default_log_dir

LOGGER = logging.getLogger(__name__)

HEARTBEAT_SECONDS = 3.0
CONFIRM_TIMEOUT = 120.0
# A question to the user has no short deadline: people walk away. chat/cancel still
# interrupts the wait, and the Shell gets ask/withdraw so the card does not linger.
# 提问不设短超时：人会离开。需要中断时走 chat/cancel，卡片会收到 ask/withdraw 收起。
ASK_TIMEOUT = 1800.0

# Tools whose diff card disappears once the implementation phase was pre-approved
# (D-A). ``delete_file`` is deliberately absent: deletion is never auto-approved.
# 预审通过后可自动执行的写工具；delete_file 不在其中，删除始终逐次确认。
WRITE_TOOL_NAMES = frozenset(("write_file", "edit_file"))

# The task id of the chat currently being executed (used when forwarding tool
# invocations so the IDE can group UI cards per task).
# 当前执行的 chat 任务 id，供转发工具调用时携带给 IDE 分组展示。
_current_task = contextvars.ContextVar("current_task", default="")


def _error_text(exc: BaseException) -> str:
    """Never hand the Shell an empty message: httpcore timeouts carry none."""
    return str(exc).strip() or type(exc).__name__


class RpcServer:
    def __init__(self, project_id: str, data_dir: str) -> None:
        self.project_id = project_id
        self.data_dir = data_dir
        self.project_root = os.environ.get("AGENT_PROJECT_ROOT") or None
        self.config = ChatConfig()
        self.store = SessionStore(data_dir)
        self.search = SessionSearch(os.path.join(data_dir, "sessions.db"))
        self.sessions = SessionManager(self.store,
                                       on_saved=self._on_session_saved,
                                       on_deleted=self._on_session_deleted)
        self.usage = UsageLog(os.environ.get("AGENT_USAGE_DIR") or default_log_dir())
        self.tools = ToolRegistry()
        self.memory = MemoryService(self.project_root, data_dir)
        self.index = CodeIndexService(self.project_root, data_dir, lambda: self.config)
        self.skills = SkillService(SkillLoader(self.project_root))
        self.mcp = MCPManager(self.project_root)
        self.mcp.set_confirm(self._confirm_via_shell)
        self._mcp_settings: List[Any] = []
        self._pending_confirms: Dict[str, asyncio.Future] = {}
        # askId -> future; the Shell answers with ask/userAnswer.
        # askId → future：Shell 用 ask/userAnswer 回答。
        self._pending_asks: Dict[str, asyncio.Future] = {}
        # What the Shell advertised in ide/hello. A Shell that does not know ask/user
        # would leave the turn waiting, so ask_user degrades to "no UI" instead.
        # Shell 在 ide/hello 里声明支持的能力；没声明就不发 ask/user，避免白等一轮。
        self._shell_capabilities: set = set()
        self._tasks: Dict[str, asyncio.Task] = {}
        # taskId -> sessionId, so deleting a conversation can stop its running task.
        # taskId → sessionId：删除历史会话时据此取消该会话在跑的任务。
        self._task_sessions: Dict[str, str] = {}
        self._pending_tools: Dict[str, asyncio.Future] = {}
        self._lines: Optional[asyncio.Queue] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._out_lock = threading.Lock()
        self._closing = False
        self._index_task: Optional[asyncio.Task] = None
        # Fire-and-forget work (memory capture) outlives the chat task itself.
        # 后台任务（记忆候选抽取）需要持有引用，否则可能被回收。
        self._background: set = set()
        self._register_shell_tools()
        todo_tools.register_tools(self.tools, self._publish_todos)
        ask_tools.register_tools(self.tools, self._ask_via_shell)
        self.memory.register_tools(self.tools)
        self.index.register_tools(self.tools)
        self.skills.register_tools(self.tools)
        self.subagents = SubagentService(
            SubagentLoader(self.project_root), self.tools,
            get_config=lambda: self.config,
            get_provider=lambda: build_provider(self.config),
            on_event=self._on_subagent_event,
        )
        self.subagents.register_tools(self.tools)
        self.teams = TeamService(
            TeamLoader(self.project_root), self.subagents,
            get_config=lambda: self.config,
            preapprove_fn=self._preapprove_via_shell,
            on_artifact=self._publish_artifact,
            on_event=self._on_subagent_event,
        )
        self.teams.register_tools(self.tools)

    def _register_shell_tools(self) -> None:
        proxy = RemoteToolProxy(self._invoke_shell_tool)
        for tool_def in SHELL_TOOLS:
            self.tools.register(tool_def, proxy)

    # ------------------------------------------------------------------ io

    def _send(self, payload: Dict[str, Any]) -> None:
        line = dumps(payload)
        with self._out_lock:
            try:
                sys.stdout.write(line + "\n")
                sys.stdout.flush()
            except (BrokenPipeError, ValueError):
                self._closing = True

    def _send_notification(self, method: str, params: Optional[Dict[str, Any]] = None) -> None:
        self._send(make_notification(method, params))

    def _start_stdin_reader(self) -> None:
        loop = asyncio.get_running_loop()
        self._loop = loop
        self._lines = asyncio.Queue()

        def _reader() -> None:
            try:
                for raw in sys.stdin:
                    loop.call_soon_threadsafe(self._lines.put_nowait, raw)
            except Exception:  # pragma: no cover - defensive
                LOGGER.exception("stdin reader crashed")
            finally:
                loop.call_soon_threadsafe(self._lines.put_nowait, None)

        threading.Thread(target=_reader, name="stdin-reader", daemon=True).start()

    # ----------------------------------------------------------------- run

    async def run(self) -> None:
        self._start_stdin_reader()
        self._send_notification("core/hello", {
            "protocolVersion": PROTOCOL_VERSION,
            "coreVersion": CORE_VERSION,
            "projectId": self.project_id,
            "capabilities": ["chat", "parallel-tools", "tokenizer", "codesoup",
                             "memory", "skill", "mcp", "subagent", "inline",
                             "code-index", "session-store", "terminal",
                             "session-search", "memory-pending", "usage",
                             "ask-user", "team"],
            "native": native_info(),
        })
        LOGGER.info("agent core %s started (project=%s)", CORE_VERSION, self.project_id)

        # Indexing historical conversations reads many files, so it runs in a
        # worker thread and reports progress; a failure only costs search.
        # 历史会话建索引读大量文件，放到工作线程并上报进度；失败只影响检索。
        self._index_task = asyncio.create_task(self._reindex_sessions())

        heartbeat = asyncio.create_task(self._heartbeat())
        try:
            while not self._closing:
                raw = await self._lines.get()
                if raw is None:
                    LOGGER.info("stdin closed, shutting down")
                    break
                line = raw.strip()
                if not line:
                    continue
                try:
                    message = json.loads(line)
                except json.JSONDecodeError:
                    LOGGER.warning("drop invalid frame: %r", line[:200])
                    continue
                await self._dispatch(message)
        finally:
            heartbeat.cancel()
            if self._index_task is not None:
                self._index_task.cancel()
            for task in list(self._tasks.values()):
                task.cancel()
            for task in list(self._background):
                task.cancel()
            await self.mcp.shutdown()
            LOGGER.info("agent core stopped")

    async def _heartbeat(self) -> None:
        while True:
            await asyncio.sleep(HEARTBEAT_SECONDS)
            self._send_notification("agent/ping", {})

    # ------------------------------------------------------------- dispatch

    async def _dispatch(self, message: Dict[str, Any]) -> None:
        method = message.get("method")
        req_id = message.get("id")
        params = message.get("params") or {}

        if method is None:
            return  # response to a core-initiated request; nothing to do yet

        if req_id is None:
            await self._handle_notification(method, params)
            return

        # Long-running requests run as tasks so the stdin loop keeps serving
        # chat/cancel, tool/result and other inline completion calls.
        # 长耗时请求放到后台任务，避免阻塞 stdin 循环（否则编辑器补全会卡住聊天）。
        if method == "inline/complete":
            asyncio.create_task(self._run_inline(req_id, params))
            return
        if method == "index/build":
            asyncio.create_task(self._run_index_build(req_id, params))
            return

        try:
            started = time.monotonic()
            result = await self._handle_request(method, params)
            elapsed = time.monotonic() - started
            # A slow request parks the stdin loop for everything else; name it in the log.
            # 慢请求会堵住 stdin 循环，其余请求（含 tool/result）都等着：记下方法名与耗时。
            if elapsed > 2.0:
                LOGGER.warning("slow request %s took %.1fs", method, elapsed)
            else:
                LOGGER.debug("request %s ok in %.0fms", method, elapsed * 1000)
            self._send(make_response(req_id, result))
        except RpcError as exc:
            self._send(make_error(req_id, exc.code, _error_text(exc)))
        except Exception as exc:  # pragma: no cover - defensive
            LOGGER.exception("request %s failed", method)
            self._send(make_error(req_id, ErrorCode.INTERNAL_ERROR, _error_text(exc)))

    async def _handle_request(self, method: str, params: Dict[str, Any]) -> Dict[str, Any]:
        if method == "chat/start":
            return await self._chat_start(params)
        if method == "ping":
            return {"pong": True}
        if method == "tool/result":
            return self._tool_result(params)
        if method == "agent/confirmResult":
            return self._confirm_result(params)
        if method == "ask/userAnswer":
            return self._ask_result(params)
        if method == "index/status":
            return self.index.status()
        if method == "session/list":
            return {"sessions": self.sessions.list_sessions()}
        if method == "session/get":
            return self.sessions.detail(str(params.get("sessionId") or ""))
        if method == "session/delete":
            session_id = str(params.get("sessionId") or "")
            # Stop the task first: its finally-block would otherwise compete with the
            # unlink and could leave the file behind on a slow disk.
            # 先停任务再删文件：任务收尾的那一刻可能把文件又写回来。
            self._cancel_session_tasks(session_id)
            freed = self.sessions.delete(session_id)
            return {"deleted": freed > 0, "freedBytes": freed}
        if method == "session/search":
            limit = int(params.get("limit") or 20)
            return {"results": self.search.search(str(params.get("query") or ""), limit)}
        if method == "memory/pending":
            return {"items": self.memory.pending_list()}
        if method == "memory/promote":
            return self._memory_promote(params)
        if method == "memory/discard":
            pending_id = str(params.get("id") or "")
            if not pending_id:
                raise RpcError(ErrorCode.PROTOCOL_ERROR, "empty id")
            discarded = self.memory.discard(pending_id)
            return {"discarded": discarded, "remaining": len(self.memory.pending_list())}
        if method == "usage/report":
            return self.usage.report(int(params.get("days") or 7))
        if method == "team/list":
            return {"teams": self.teams.list_payload()}
        if method == "team/artifacts":
            return self._team_artifacts(params)
        raise RpcError(ErrorCode.PROTOCOL_ERROR, "unknown method: %s" % method)

    def _team_artifacts(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Blackboard replay for a reopened conversation; unknown id reads empty."""
        session = self.sessions.get(str(params.get("sessionId") or "default"))
        items = session.artifacts if session is not None else []
        kind = str(params.get("kind") or "").strip().lower()
        runs = session.team_runs if session is not None else []
        return {
            "artifacts": [item for item in items if not kind or item.get("kind") == kind],
            "teamRuns": list(runs),
        }

    def _memory_promote(self, params: Dict[str, Any]) -> Dict[str, Any]:
        pending_id = str(params.get("id") or "")
        if not pending_id:
            raise RpcError(ErrorCode.PROTOCOL_ERROR, "empty id")
        keywords = params.get("keywords")
        if isinstance(keywords, str):
            keywords = [part.strip() for part in keywords.split(",") if part.strip()]
        if not isinstance(keywords, list):
            keywords = None
        try:
            return self.memory.promote(
                pending_id,
                content=(params.get("content") or None),
                title=(params.get("title") or None),
                category=(params.get("category") or None),
                keywords=keywords,
            )
        except KeyError:
            raise RpcError(ErrorCode.PROTOCOL_ERROR,
                           "pending memory not found: %s" % pending_id)
        except ValueError as exc:
            raise RpcError(ErrorCode.PROTOCOL_ERROR, str(exc))

    # ---------------------------------------------------------- session index

    def _on_session_saved(self, session_id: str, title: str, content: str,
                          updated_at: int) -> None:
        self.search.index(session_id, title, content, updated_at)

    def _on_session_deleted(self, session_id: str) -> None:
        self.search.remove(session_id)

    async def _reindex_sessions(self) -> None:
        """One-time migration: index conversations saved by older versions."""
        try:
            total = await asyncio.to_thread(self._index_all_documents)
        except Exception:  # pragma: no cover - a broken index must not stop the Core
            LOGGER.exception("session reindex failed")
            return
        if total:
            LOGGER.info("session index ready (%d conversations)", total)
        self._send_notification("session/indexProgress",
                                {"done": total, "total": total, "finished": True})

    def _index_all_documents(self) -> int:
        def progress(done: int, changed: int) -> None:
            # Progress only; the Shell shows a one-line hint while it runs.
            self._send_notification("session/indexProgress",
                                    {"done": done, "total": 0, "changed": changed})

        return self.search.index_many(self.store.iter_documents(), progress)

    def _tool_result(self, params: Dict[str, Any]) -> Dict[str, Any]:
        tool_call_id = params.get("toolCallId") or ""
        result = ToolResult(
            tool_call_id=tool_call_id,
            ok=bool(params.get("ok")),
            content=params.get("result"),
            error=params.get("error"),
        )
        future = self._pending_tools.pop(tool_call_id, None)
        if future is None:
            # Late, duplicated or a wrong id: the Core already timed the call out, so the
            # result is dropped and the user sees a timeout with no explanation.
            # 迟到/重复/错 id：Core 已经判超时，结果被丢弃，界面只看到超时。
            LOGGER.warning("tool/result dropped: unknown call id %r (ok=%s)",
                           tool_call_id, result.ok)
            diagnostics.emit("remote_orphan", call=tool_call_id, ok=result.ok,
                             error=diagnostics.brief(result.error))
        elif future.done():
            LOGGER.warning("tool/result arrived after call %r was already settled", tool_call_id)
            diagnostics.emit("remote_orphan", call=tool_call_id, ok=result.ok, late=True)
        else:
            LOGGER.debug("tool/result %s ok=%s", tool_call_id, result.ok)
            future.set_result(result)
        return {"received": True}

    def _confirm_result(self, params: Dict[str, Any]) -> Dict[str, Any]:
        confirm_id = params.get("confirmId") or ""
        future = self._pending_confirms.pop(confirm_id, None)
        if future is not None and not future.done():
            future.set_result(bool(params.get("approved")))
        return {"received": True}

    def _ask_result(self, params: Dict[str, Any]) -> Dict[str, Any]:
        ask_id = params.get("askId") or ""
        future = self._pending_asks.pop(ask_id, None)
        if future is not None and not future.done():
            answers = params.get("answers")
            future.set_result({
                "skipped": bool(params.get("skipped")),
                "answers": answers if isinstance(answers, list) else [],
            })
        return {"received": True}

    async def _run_inline(self, req_id: Any, params: Dict[str, Any]) -> None:
        """Answer one ghost-text request; never raises into the dispatch loop."""
        try:
            text = await asyncio.wait_for(
                complete_inline(
                    self.config,
                    str(params.get("prefix") or ""),
                    str(params.get("suffix") or ""),
                    str(params.get("language") or ""),
                    int(params.get("maxTokens") or 128),
                ),
                timeout=12.0,
            )
            self._send(make_response(req_id, {"text": text}))
        except asyncio.TimeoutError:
            self._send(make_response(req_id, {"text": ""}))
        except Exception:  # pragma: no cover - defensive
            LOGGER.exception("inline completion failed")
            self._send(make_response(req_id, {"text": ""}))

    async def _run_index_build(self, req_id: Any, params: Dict[str, Any]) -> None:
        def on_progress(done: int, total: int, path: str) -> None:
            self._send_notification("index/progress", {
                "done": done, "total": total, "path": path})

        try:
            stats = await self.index.build(full=bool(params.get("full")), progress=on_progress)
            self._send(make_response(req_id, stats))
        except RpcError as exc:
            self._send(make_error(req_id, exc.code, _error_text(exc)))
        except Exception as exc:  # pragma: no cover - defensive
            LOGGER.exception("index build failed")
            self._send(make_error(req_id, ErrorCode.INTERNAL_ERROR, _error_text(exc)))

    async def _handle_notification(self, method: str, params: Dict[str, Any]) -> None:
        if method == "settings/update":
            self._apply_settings(params)
        elif method == "chat/cancel":
            self._cancel(params.get("taskId"))
        elif method == "ide/hello":
            # The Shell tells us which optional notifications it can render; ask/user is
            # only sent to a Shell that understands it.
            # Shell 声明能渲染哪些可选通知；ask/user 只发给认识它的 Shell。
            capabilities = params.get("capabilities")
            if isinstance(capabilities, list):
                self._shell_capabilities = {str(item) for item in capabilities}
            LOGGER.info("ide hello: %s", params)
        else:
            LOGGER.debug("unhandled notification: %s", method)

    # ------------------------------------------------------------- handlers

    def _apply_settings(self, params: Dict[str, Any]) -> None:
        allowed = set(ChatConfig.model_fields.keys())
        # Merge onto the current config so partial payloads (e.g. switching only the model)
        # keep max_iterations / mcp_servers / temperature instead of resetting to defaults.
        # 合并到现有配置：只切换模型等字段时，不清空 max_iterations / mcp_servers 等已有值。
        data = self.config.model_dump()
        data.update({k: v for k, v in params.items() if k in allowed})
        try:
            self.config = ChatConfig(**data)
            LOGGER.info("settings updated: provider=%s model=%s",
                        self.config.provider, self.config.model)
        except Exception:
            LOGGER.exception("invalid settings payload: %s", data)
            return

        servers = parse_servers(self.config.mcp_servers or [])
        if [s.dict() for s in servers] != [s.dict() for s in self._mcp_settings]:
            self._mcp_settings = servers
            self._schedule_mcp_refresh()

    def _schedule_mcp_refresh(self) -> None:
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:  # pragma: no cover - called outside the loop
            return
        loop.create_task(self._refresh_mcp())

    async def _refresh_mcp(self) -> None:
        try:
            await self.mcp.refresh(self.tools, self._mcp_settings)
        except Exception:  # pragma: no cover - never break the chat loop
            LOGGER.exception("MCP refresh failed")

    async def _chat_start(self, params: Dict[str, Any]) -> Dict[str, Any]:
        session_id = params.get("sessionId") or "default"
        user_input = (params.get("message") or "").strip()
        if not user_input:
            raise RpcError(ErrorCode.PROTOCOL_ERROR, "empty message")

        session = self.sessions.get_or_create(session_id)
        # The first message names the conversation in the history list.
        # 第一条消息决定会话在历史列表里的标题。
        if not session.title:
            session.title = make_title(user_input)
        session.touch()
        snapshot = params.get("contextSnapshot")
        if snapshot:
            try:
                parsed = ContextSnapshot(**snapshot)
                session.last_context = parsed
                self._bind_project_root(parsed.project_root)
            except Exception:
                LOGGER.warning("ignored invalid context snapshot")

        task_id = uuid.uuid4().hex[:12]
        skill_name = (params.get("skill") or "").strip()
        task = asyncio.create_task(self._run_task(task_id, session, user_input, skill_name))
        self._tasks[task_id] = task
        self._task_sessions[task_id] = session_id
        task.add_done_callback(lambda _t, tid=task_id: self._forget_task(tid))
        return {"taskId": task_id}

    def _forget_task(self, task_id: str) -> None:
        self._tasks.pop(task_id, None)
        self._task_sessions.pop(task_id, None)

    def _cancel_session_tasks(self, session_id: str) -> None:
        """Cancel every running task that belongs to one conversation."""
        if not session_id:
            return
        for task_id, owner in list(self._task_sessions.items()):
            if owner != session_id:
                continue
            task = self._tasks.get(task_id)
            if task is not None and not task.done():
                task.cancel()

    def _bind_project_root(self, project_root: Optional[str]) -> None:
        """Rebind memory to the IDE project once the shell reports it."""
        if not project_root or project_root == self.project_root:
            return
        self.project_root = project_root
        self.memory = MemoryService(project_root, self.data_dir)
        self.memory.register_tools(self.tools)
        self.index.set_project_root(project_root)
        self.skills = SkillService(SkillLoader(project_root))
        self.skills.register_tools(self.tools)
        self.mcp.set_project_root(project_root)
        self._schedule_mcp_refresh()
        self.subagents.loader.set_project_root(project_root)
        self.teams.loader.set_project_root(project_root)

    async def _run_task(self, task_id: str, session: Session, user_input: str,
                        skill_name: str = "") -> None:
        provider = build_provider(self.config)
        task_token = _current_task.set(task_id)
        # todo_write writes into the session of the running chat.
        # todo_write 写入当前会话的清单，任务结束随会话落盘。
        todo_token = todo_tools.bind_session(session)
        # Team tools share the same session; the run id scopes pre-approval.
        # 团队工具写入同一会话；run id 用于限定预审通过的范围。
        team_token = team_tools.bind_session(session)
        run_token = team_tools.bind_run(task_id)
        team = self.teams.active_team()
        team_record: Optional[Dict[str, Any]] = None
        if team is not None:
            team_record = {
                "teamRunId": task_id,
                "team": team.name,
                "startedAt": int(time.time() * 1000),
                "members": [member.role for member in team.members],
            }
            session.team_runs.append(team_record)
            del session.team_runs[:-20]
        started = time.monotonic()
        tool_calls = 0
        usage_total: Dict[str, Any] = {}
        ok = False
        error_text = ""
        # One JSONL trace per task: tool calls, shell round trips, dispatch failures.
        # 每个任务一份 JSONL：工具调用、Shell 往返、派发失败；完成后打印摘要与文件路径。
        trace = diagnostics.start(task_id, session.session_id)
        trace_token = diagnostics.bind(trace)
        if trace is not None:
            trace.event("task_start", model=self.config.model,
                        provider=self.config.provider,
                        team=(team.name if team is not None else None),
                        skill=(skill_name or None), tools=len(self.tools.names()),
                        message=diagnostics.brief(user_input, 300))

        async def emit_token(delta: str) -> None:
            self._send_notification("chat/token", {"taskId": task_id, "delta": delta})

        async def emit_reasoning(delta: str) -> None:
            # Thinking (reasoning_content / Ollama thinking) travels on its own channel so the
            # Shell can fold it above the answer instead of mixing it into the text.
            # 思考过程走独立通道：Shell 把它折在正文上方，而不是混进正文。
            self._send_notification("chat/reasoning", {"taskId": task_id, "delta": delta})

        async def on_tool_start(call: ToolCall) -> None:
            nonlocal tool_calls
            tool_calls += 1
            self._send_notification("chat/toolStart", {
                "taskId": task_id,
                "toolCallId": call.id,
                "toolName": call.name,
                "args": call.arguments,
            })

        async def on_tool_end(call: ToolCall, result: ToolResult) -> None:
            summary = (result.content or result.error or "").strip().replace("\n", " ")
            self._send_notification("chat/toolEnd", {
                "taskId": task_id,
                "toolCallId": call.id,
                "ok": result.ok,
                "summary": summary[:300],
            })

        async def on_notice(text: str) -> None:
            # Provider degradations (e.g. endpoint without tool support) are surfaced
            # as a non-fatal notice instead of failing the whole task.
            self._send_notification("chat/notice", {"taskId": task_id, "text": text})

        try:
            team_prompt = self.teams.active_prompt_text() if team is not None else None
            # The playbook is appended to the default prompt, not replacing it: the
            # team still needs the tool-use and reporting rules of the base agent.
            # 剧本追加到默认提示词之后（不是替换），否则会丢掉工具使用与回报规范。
            base_prompt = (SYSTEM_PROMPT + "\n\n" + team_prompt) if team_prompt else None
            catalog = None if team_prompt else self.subagents.catalog_text()
            result = await run_chat(
                session, user_input, self.config, provider, emit_token,
                registry=self.tools, on_tool_start=on_tool_start, on_tool_end=on_tool_end,
                on_notice=on_notice,
                on_reasoning=emit_reasoning,
                memory_overview=self.memory.overview_text(),
                skill_instruction=self.skills.instruction_for(user_input, skill_name),
                base_prompt=base_prompt,
                subagent_catalog=catalog,
            )
            usage_total = result.usage_total if isinstance(result.usage_total, dict) else {}
            ok = True
            self._send_notification("chat/finish", {
                "taskId": task_id,
                "fullText": result.content,
                "usage": result.usage,
                "usageTotal": result.usage_total,
            })
            # Only a finished round can teach a memory: a cancelled one may have
            # stopped in the middle of the user's thought.
            # 只有正常结束的一轮才可能漏出可沉淀的记忆；取消的那轮可能只说了一半。
            self._spawn(self._capture_memories(session))
        except asyncio.CancelledError:
            error_text = "cancelled"
            self._send_notification("chat/error", {
                "taskId": task_id,
                "error": {"code": ErrorCode.CANCELLED, "message": "已取消"},
            })
        except RpcError as exc:
            error_text = _error_text(exc)
            self._send_notification("chat/error", {
                "taskId": task_id,
                "error": {"code": exc.code, "message": error_text},
            })
        except Exception as exc:  # pragma: no cover - defensive
            error_text = _error_text(exc)
            LOGGER.exception("chat task %s failed", task_id)
            self._send_notification("chat/error", {
                "taskId": task_id,
                "error": {"code": ErrorCode.LLM_ERROR, "message": error_text},
            })
        finally:
            todo_tools.reset_session(todo_token)
            team_tools.reset_session(team_token)
            team_tools.reset_run(run_token)
            self.teams.clear_run(task_id)
            duration_ms = int((time.monotonic() - started) * 1000)
            if team_record is not None:
                team_record["ok"] = ok
                team_record["toolCalls"] = tool_calls
                team_record["durationMs"] = duration_ms
            _current_task.reset(task_token)
            if trace is not None:
                trace.finish(ok and not session.deleted, error=error_text,
                             durationMs=duration_ms, tokens=usage_total or None,
                             team=(team.name if team is not None else None))
            diagnostics.reset(trace_token)
            self.sessions.save(session)
            self._record_usage(task_id, usage_total, started, tool_calls,
                               ok and not session.deleted)

    def _spawn(self, coroutine) -> None:
        """Keep a strong reference: a bare create_task may be collected."""
        task = asyncio.create_task(coroutine)
        self._background.add(task)
        task.add_done_callback(self._background.discard)

    def _record_usage(self, task_id: str, usage: Dict[str, Any], started: float,
                      tool_calls: int, ok: bool) -> None:
        """One line per task in ~/.agent/logs/usage.jsonl (never leaves the machine)."""
        self.usage.record(
            task_id=task_id,
            model=self.config.model,
            usage_in=int(usage.get("in") or 0),
            usage_out=int(usage.get("out") or 0),
            tool_calls=tool_calls,
            duration_ms=int((time.monotonic() - started) * 1000),
            ok=ok,
        )

    async def _capture_memories(self, session: Session) -> None:
        """Rule-based candidate extraction; runs after chat/finish, off the chat path."""
        memory = self.memory
        try:
            added = await asyncio.to_thread(memory.capture, session)
        except Exception:  # pragma: no cover - memory capture must never break chat
            LOGGER.exception("memory capture failed for %s", session.session_id)
            return
        if not added:
            return
        LOGGER.info("captured %d pending memories from %s", len(added), session.session_id)
        self._send_notification("memory/pendingChanged", {"count": len(added)})

    def _publish_todos(self, todos: List[Dict[str, str]]) -> None:
        """Push the checklist to the Shell after every todo_write."""
        self._send_notification("chat/todos", {
            "taskId": _current_task.get(),
            "todos": todos,
        })

    def _publish_artifact(self, artifact: Dict[str, Any]) -> None:
        """Push a blackboard deliverable so the Shell can render it live."""
        self._send_notification("chat/artifact", {
            "taskId": _current_task.get(),
            "teamRunId": _current_task.get(),
            "kind": artifact.get("kind") or "note",
            "title": artifact.get("title") or "",
            "authorRole": artifact.get("authorRole") or "",
            "refs": artifact.get("refs") or [],
        })

    # Forward subagent lifecycle events so the Shell can show progress.
    # 转发子代理生命周期事件，供 Shell 展示进度。
    async def _on_subagent_event(self, kind: str, task, report=None) -> None:
        team = self.teams.active_team()
        role = getattr(task, "role", "") or ""
        actor, capability = role, getattr(task, "capability", "") or ""
        if team is not None:
            member = team.member(role)
            if member is not None:
                actor = member.display_name()
                capability = member.normalised_capability()
        payload = {
            "taskId": _current_task.get(),
            "teamRunId": _current_task.get() if team is not None else "",
            "subagentId": task.id,
            "kind": kind,
            "role": role,
            "actor": actor,
            "capability": capability,
            "model": getattr(task, "model", "") or "",
            "goal": task.goal[:200],
        }
        if report is not None:
            payload["ok"] = report.ok
            payload["summary"] = report.summary[:300]
            payload["durationMs"] = getattr(report, "duration_ms", 0)
        self._send_notification("chat/subagent", payload)
        trace = diagnostics.current()
        if trace is not None:
            if kind == "start":
                trace.count("subagents")
        diagnostics.emit("subagent", kind=kind, id=task.id, role=role,
                         capability=capability or None,
                         ms=(getattr(report, "duration_ms", 0) if report is not None else None),
                         ok=(report.ok if report is not None else None),
                         summary=(report.summary if report is not None else None))

    async def _invoke_shell_tool(self, call: ToolCall) -> ToolResult:
        tool_def = self.tools.get(call.name)
        permission = tool_def.permission.value if tool_def else Permission.AUTO.value
        # Terminal commands are judged here, not in the Shell: the policy travels
        # with the invocation so both IDEs behave identically.
        # 终端命令的判定留在 Core：策略随调用一起下发，两端 Shell 行为一致。
        if call.name == "run_command":
            command = str(call.arguments.get("command") or "")
            decision = decide_command(command, self.config.command_allowlist)
            if decision == "deny":
                LOGGER.info("refused dangerous command: %s", command[:200])
                return ToolResult(
                    tool_call_id=call.id,
                    ok=False,
                    error=(
                        "已拒绝执行危险命令: %s\n"
                        "删除文件请改用 delete_file（逐个确认）；"
                        "推送与历史重写由人执行，Agent 不代为执行。" % command.strip()[:200]
                    ),
                )
            permission = decision
        elif call.name in WRITE_TOOL_NAMES and self.teams.active_team() is not None:
            # D-A: an approved checkpoint covers this phase's routine writes only.
            # 预审通过只豁免本阶段的常规写；未预审、跨范围、删除均保持原有确认。
            path = str(call.arguments.get("path") or "")
            if self.teams.should_auto_write(_current_task.get(), path):
                permission = Permission.AUTO.value

        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        self._pending_tools[call.id] = future
        started = time.monotonic()
        payload = {
            "taskId": _current_task.get(),
            "toolCallId": call.id,
            "toolName": call.name,
            "params": call.arguments,
            "permission": permission,
        }
        # Label member writes so the Shell can show "来自 全栈工程师" on the card.
        # 成员发起的写带上 actor，Shell 在卡片上标注来源。
        member = subagent_tools.current_member()
        if member:
            team = self.teams.active_team()
            found = team.member(member) if team is not None else None
            payload["member"] = found.display_name() if found is not None else member
        self._send_notification("tool/invoke", payload)
        diagnostics.emit("remote_invoke", tool=call.name, call=call.id,
                         permission=permission, member=member or None,
                         args=diagnostics.brief(call.arguments),
                         pending=len(self._pending_tools))
        try:
            return await future
        finally:
            self._pending_tools.pop(call.id, None)
            # A cancelled future means the scheduler hit the tool timeout (or the task was
            # cancelled) while the Shell never answered: the usual cause of "the tool never
            # runs" is a dialog nobody saw, a dead connection or a busy IDE.
            # future 被取消 = 到超时也没等到 Shell 回答：常见于无人查看的确认卡、连接已断或 IDE 正忙。
            if not future.done() or future.cancelled():
                waited = time.monotonic() - started
                LOGGER.warning(
                    "tool/invoke %s (%s) got no reply in %.1fs (cancelled=%s, pending=%d); "
                    "check the IDE dialog, the core connection and the shell capabilities %s",
                    call.id, call.name, waited, future.cancelled(), len(self._pending_tools),
                    sorted(self._shell_capabilities))
                diagnostics.emit("remote_timeout", tool=call.name, call=call.id,
                                 waitedSec=round(waited, 1), cancelled=future.cancelled(),
                                 pending=len(self._pending_tools),
                                 shellCapabilities=sorted(self._shell_capabilities))

    # Ask the shell to confirm a sensitive local action (e.g. an MCP tool call).
    # 请求 Shell 确认敏感操作（例如 MCP 工具调用），超时视为拒绝。
    async def _confirm_via_shell(self, server: str, tool: str,
                                 arguments: Dict[str, Any]) -> bool:
        confirm_id = uuid.uuid4().hex[:12]
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending_confirms[confirm_id] = future
        self._send_notification("agent/confirm", {
            "confirmId": confirm_id,
            "taskId": _current_task.get(),
            "kind": "mcp",
            "server": server,
            "tool": tool,
            "args": arguments,
        })
        try:
            return bool(await asyncio.wait_for(future, timeout=CONFIRM_TIMEOUT))
        except asyncio.TimeoutError:
            # Nobody answered the card: the tool call fails for a reason the user cannot
            # see in the transcript, so put it in the log instead of failing silently.
            # 确认卡无人应答：工具会因为看不到的原因失败，这里把它写进日志。
            LOGGER.warning("MCP confirm timed out after %.0fs: %s.%s",
                           CONFIRM_TIMEOUT, server, tool)
            diagnostics.emit("confirm_timeout", kind="mcp", server=server, tool=tool,
                             timeoutSec=CONFIRM_TIMEOUT)
            return False
        finally:
            self._pending_confirms.pop(confirm_id, None)

    # One pre-review checkpoint for an implementation phase (decision D-A).
    # The answer reuses agent/confirmResult; a Shell that cannot render it
    # simply times out and the per-edit cards stay in place.
    # 实现阶段的一次预审：应答走 agent/confirmResult；不支持的 Shell 超时后仍逐次确认。
    async def _preapprove_via_shell(self, payload: Dict[str, Any]) -> bool:
        # An older shell would render this as an MCP confirmation card (and answer "MCP" to a
        # plan it cannot show), so without the capability the phase falls back to one
        # confirmation per write instead.
        # 旧 Shell 会把它当成 MCP 确认卡（对着看不到的计划回答「MCP」），因此没声明 team
        # 能力时直接返回 False，退化成逐次确认。
        if "team" not in self._shell_capabilities:
            LOGGER.info("team preapprove skipped: shell did not advertise team")
            return False
        preapprove_id = uuid.uuid4().hex[:12]
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending_confirms[preapprove_id] = future
        self._send_notification("agent/confirm", {
            "confirmId": preapprove_id,
            "taskId": _current_task.get(),
            "kind": "preapprove",
            "preapproveId": preapprove_id,
            "plan": str(payload.get("plan") or ""),
            "firstDiff": str(payload.get("firstDiff") or ""),
            "files": list(payload.get("files") or []),
        })
        try:
            return bool(await asyncio.wait_for(future, timeout=CONFIRM_TIMEOUT))
        except asyncio.TimeoutError:
            LOGGER.warning("preapprove card timed out after %.0fs", CONFIRM_TIMEOUT)
            diagnostics.emit("confirm_timeout", kind="preapprove",
                             timeoutSec=CONFIRM_TIMEOUT)
            return False
        finally:
            self._pending_confirms.pop(preapprove_id, None)

    # Ask the user a question and wait for the answer (the ``ask_user`` tool).
    # Returns None when no Shell can render the card, so the model continues with
    # assumptions instead of waiting for an answer that will never come.
    # 向用户提问并等答复（``ask_user`` 工具）。没有能渲染卡片的 Shell 时返回 None，
    # 让模型带着假设继续，而不是干等一个永远不会来的回答。
    async def _ask_via_shell(self, questions: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        if "ask-user" not in self._shell_capabilities:
            LOGGER.info("ask_user skipped: shell did not advertise ask-user")
            return None
        ask_id = uuid.uuid4().hex[:12]
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending_asks[ask_id] = future
        self._send_notification("ask/user", {
            "askId": ask_id,
            "taskId": _current_task.get(),
            "questions": questions,
        })
        answered = False
        try:
            reply = await asyncio.wait_for(future, timeout=ASK_TIMEOUT)
            answered = True
            return reply
        except asyncio.TimeoutError:
            LOGGER.warning("ask_user timed out after %.0fs (%d question(s))",
                           ASK_TIMEOUT, len(questions))
            diagnostics.emit("ask_timeout", questions=len(questions),
                             timeoutSec=ASK_TIMEOUT)
            return None
        finally:
            self._pending_asks.pop(ask_id, None)
            if not answered:
                # Cancelled or timed out: tell the Shell to drop the card.
                # 被取消或超时：通知 Shell 收起卡片。
                self._send_notification("ask/withdraw", {
                    "askId": ask_id, "taskId": _current_task.get()})

    def _cancel(self, task_id: Optional[str]) -> None:
        if task_id:
            task = self._tasks.get(task_id)
            if task:
                task.cancel()
            return
        for task in list(self._tasks.values()):
            task.cancel()
