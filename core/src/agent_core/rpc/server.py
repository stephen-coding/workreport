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
import uuid
from typing import Any, Dict, Optional

from agent_core.agent.loop import run_chat
from agent_core.agent.session import Session, SessionManager
from agent_core.agent.subagent import SubagentLoader, SubagentService
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

LOGGER = logging.getLogger(__name__)

HEARTBEAT_SECONDS = 3.0
CONFIRM_TIMEOUT = 120.0

# The task id of the chat currently being executed (used when forwarding tool
# invocations so the IDE can group UI cards per task).
# 当前执行的 chat 任务 id，供转发工具调用时携带给 IDE 分组展示。
_current_task = contextvars.ContextVar("current_task", default="")


class RpcServer:
    def __init__(self, project_id: str, data_dir: str) -> None:
        self.project_id = project_id
        self.data_dir = data_dir
        self.project_root = os.environ.get("AGENT_PROJECT_ROOT") or None
        self.config = ChatConfig()
        self.sessions = SessionManager()
        self.tools = ToolRegistry()
        self.memory = MemoryService(self.project_root, data_dir)
        self.index = CodeIndexService(self.project_root, data_dir, lambda: self.config)
        self.skills = SkillService(SkillLoader(self.project_root))
        self.mcp = MCPManager(self.project_root)
        self.mcp.set_confirm(self._confirm_via_shell)
        self._mcp_settings: List[Any] = []
        self._pending_confirms: Dict[str, asyncio.Future] = {}
        self._tasks: Dict[str, asyncio.Task] = {}
        self._pending_tools: Dict[str, asyncio.Future] = {}
        self._lines: Optional[asyncio.Queue] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._out_lock = threading.Lock()
        self._closing = False
        self._register_shell_tools()
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
                             "code-index"],
            "native": native_info(),
        })
        LOGGER.info("agent core %s started (project=%s)", CORE_VERSION, self.project_id)

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
            for task in list(self._tasks.values()):
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
            result = await self._handle_request(method, params)
            self._send(make_response(req_id, result))
        except RpcError as exc:
            self._send(make_error(req_id, exc.code, str(exc)))
        except Exception as exc:  # pragma: no cover - defensive
            LOGGER.exception("request %s failed", method)
            self._send(make_error(req_id, ErrorCode.INTERNAL_ERROR, str(exc)))

    async def _handle_request(self, method: str, params: Dict[str, Any]) -> Dict[str, Any]:
        if method == "chat/start":
            return await self._chat_start(params)
        if method == "ping":
            return {"pong": True}
        if method == "tool/result":
            return self._tool_result(params)
        if method == "agent/confirmResult":
            return self._confirm_result(params)
        if method == "index/status":
            return self.index.status()
        raise RpcError(ErrorCode.PROTOCOL_ERROR, "unknown method: %s" % method)

    def _tool_result(self, params: Dict[str, Any]) -> Dict[str, Any]:
        tool_call_id = params.get("toolCallId") or ""
        result = ToolResult(
            tool_call_id=tool_call_id,
            ok=bool(params.get("ok")),
            content=params.get("result"),
            error=params.get("error"),
        )
        future = self._pending_tools.pop(tool_call_id, None)
        if future is not None and not future.done():
            future.set_result(result)
        return {"received": True}

    def _confirm_result(self, params: Dict[str, Any]) -> Dict[str, Any]:
        confirm_id = params.get("confirmId") or ""
        future = self._pending_confirms.pop(confirm_id, None)
        if future is not None and not future.done():
            future.set_result(bool(params.get("approved")))
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
            self._send(make_error(req_id, exc.code, str(exc)))
        except Exception as exc:  # pragma: no cover - defensive
            LOGGER.exception("index build failed")
            self._send(make_error(req_id, ErrorCode.INTERNAL_ERROR, str(exc)))

    async def _handle_notification(self, method: str, params: Dict[str, Any]) -> None:
        if method == "settings/update":
            self._apply_settings(params)
        elif method == "chat/cancel":
            self._cancel(params.get("taskId"))
        elif method == "ide/hello":
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
        task.add_done_callback(lambda _t: self._tasks.pop(task_id, None))
        return {"taskId": task_id}

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

    async def _run_task(self, task_id: str, session: Session, user_input: str,
                        skill_name: str = "") -> None:
        provider = build_provider(self.config)
        task_token = _current_task.set(task_id)

        async def emit_token(delta: str) -> None:
            self._send_notification("chat/token", {"taskId": task_id, "delta": delta})

        async def on_tool_start(call: ToolCall) -> None:
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

        try:
            result = await run_chat(
                session, user_input, self.config, provider, emit_token,
                registry=self.tools, on_tool_start=on_tool_start, on_tool_end=on_tool_end,
                memory_overview=self.memory.overview_text(),
                skill_instruction=self.skills.instruction_for(user_input, skill_name),
            )
            self._send_notification("chat/finish", {
                "taskId": task_id,
                "fullText": result.content,
                "usage": result.usage,
            })
        except asyncio.CancelledError:
            self._send_notification("chat/error", {
                "taskId": task_id,
                "error": {"code": ErrorCode.CANCELLED, "message": "已取消"},
            })
        except RpcError as exc:
            self._send_notification("chat/error", {
                "taskId": task_id,
                "error": {"code": exc.code, "message": str(exc)},
            })
        except Exception as exc:  # pragma: no cover - defensive
            LOGGER.exception("chat task %s failed", task_id)
            self._send_notification("chat/error", {
                "taskId": task_id,
                "error": {"code": ErrorCode.LLM_ERROR, "message": str(exc)},
            })
        finally:
            _current_task.reset(task_token)

    # Forward subagent lifecycle events so the Shell can show progress.
    # 转发子代理生命周期事件，供 Shell 展示进度。
    async def _on_subagent_event(self, kind: str, task, report=None) -> None:
        payload = {
            "taskId": _current_task.get(),
            "subagentId": task.id,
            "kind": kind,
            "goal": task.goal[:200],
        }
        if report is not None:
            payload["ok"] = report.ok
            payload["summary"] = report.summary[:300]
        self._send_notification("chat/subagent", payload)

    async def _invoke_shell_tool(self, call: ToolCall) -> ToolResult:
        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        self._pending_tools[call.id] = future
        tool_def = self.tools.get(call.name)
        self._send_notification("tool/invoke", {
            "taskId": _current_task.get(),
            "toolCallId": call.id,
            "toolName": call.name,
            "params": call.arguments,
            "permission": tool_def.permission.value if tool_def else Permission.AUTO.value,
        })
        try:
            return await future
        finally:
            self._pending_tools.pop(call.id, None)

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
            return False
        finally:
            self._pending_confirms.pop(confirm_id, None)

    def _cancel(self, task_id: Optional[str]) -> None:
        if task_id:
            task = self._tasks.get(task_id)
            if task:
                task.cancel()
            return
        for task in list(self._tasks.values()):
            task.cancel()
