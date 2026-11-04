"""MCP manager: connect servers, expose their tools, restart on failure."""

import asyncio
import logging
import re
from typing import Any, Awaitable, Callable, Dict, List, Optional

from agent_core.mcp.client import CALL_TIMEOUT, MCPError, build_client
from agent_core.mcp.config import MCPServerConfig, load_project_config, merge_configs
from agent_core.protocol import Permission, ToolCall, ToolDef, ToolResult
from agent_core.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

MAX_RESTARTS = 3
_SLUG_RE = re.compile(r"[^0-9A-Za-z_]+")

ConfirmFn = Callable[[str, str, Dict[str, Any]], Awaitable[bool]]


def tool_name(server: str, tool: str) -> str:
    server_slug = _SLUG_RE.sub("_", server).strip("_") or "server"
    tool_slug = _SLUG_RE.sub("_", tool).strip("_") or "tool"
    return ("mcp__%s__%s" % (server_slug, tool_slug))[:64]


class MCPManager:
    def __init__(self, project_root: Optional[str] = None) -> None:
        self.project_root = project_root
        self.registry: Optional[ToolRegistry] = None
        self.settings_configs: List[MCPServerConfig] = []
        self.clients: Dict[str, Any] = {}
        self.status: Dict[str, Dict[str, Any]] = {}
        self._confirm: Optional[ConfirmFn] = None

    # ------------------------------------------------------------ lifecycle

    def set_confirm(self, confirm: ConfirmFn) -> None:
        self._confirm = confirm

    def set_project_root(self, project_root: Optional[str]) -> None:
        self.project_root = project_root

    async def refresh(self, registry: Optional[ToolRegistry] = None,
                      settings: Optional[List[MCPServerConfig]] = None) -> None:
        if registry is not None:
            self.registry = registry
        if settings is not None:
            self.settings_configs = settings

        await self.shutdown_clients()
        configs = merge_configs(load_project_config(self.project_root), self.settings_configs)
        if not configs:
            LOGGER.info("no MCP servers configured")
            return
        LOGGER.info("starting %d MCP server(s)", len(configs))
        await asyncio.gather(*(self._start_one(config) for config in configs),
                             return_exceptions=True)

    async def shutdown_clients(self) -> None:
        for name, client in list(self.clients.items()):
            try:
                await client.close()
            except Exception:  # pragma: no cover - best effort
                LOGGER.debug("closing MCP server %s failed", name, exc_info=True)
        self.clients.clear()

    async def shutdown(self) -> None:
        await self.shutdown_clients()

    async def _start_one(self, config: MCPServerConfig) -> None:
        previous = self.status.get(config.name) or {}
        attempts = int(previous.get("attempts") or 0) + 1
        if attempts > MAX_RESTARTS and not previous.get("ok"):
            self.status[config.name] = dict(previous, attempts=attempts)
            LOGGER.warning("MCP server %s disabled after %d failures", config.name, attempts - 1)
            return
        try:
            client = build_client(config)
            await client.start()
            tools = await client.list_tools()
        except Exception as exc:
            self.status[config.name] = {"ok": False, "attempts": attempts, "error": str(exc)}
            LOGGER.warning("MCP server %s failed (%d/%d): %s",
                           config.name, attempts, MAX_RESTARTS, exc)
            return
        self.clients[config.name] = client
        self.status[config.name] = {"ok": True, "attempts": attempts, "tools": len(tools)}
        self._register(config, tools)

    # --------------------------------------------------------------- tools

    def _register(self, config: MCPServerConfig, tools: List[Dict[str, Any]]) -> None:
        if self.registry is None:
            return
        for tool in tools:
            if not isinstance(tool, dict) or not tool.get("name"):
                continue
            raw_name = str(tool["name"])
            schema = tool.get("inputSchema")
            tool_def = ToolDef(
                name=tool_name(config.name, raw_name),
                description="[MCP:%s] %s" % (config.name,
                                            tool.get("description") or raw_name),
                parameters=schema if isinstance(schema, dict)
                else {"type": "object", "properties": {}},
                permission=Permission.AUTO if config.auto_approve else Permission.CONFIRM,
                timeout_sec=CALL_TIMEOUT,
                remote=False,
            )
            self.registry.register(tool_def, self._make_handler(config, raw_name))

    def _make_handler(self, config: MCPServerConfig, raw_name: str):
        async def handler(call: ToolCall) -> ToolResult:
            if not config.auto_approve and self._confirm is not None:
                granted = await self._confirm(config.name, raw_name, call.arguments or {})
                if not granted:
                    return ToolResult(tool_call_id=call.id, ok=False,
                                      error="用户拒绝：MCP 工具 %s 未获授权" % raw_name)
            client = self.clients.get(config.name)
            if client is None:
                await self._start_one(config)
                client = self.clients.get(config.name)
            if client is None:
                detail = (self.status.get(config.name) or {}).get("error") or "不可用"
                return ToolResult(tool_call_id=call.id, ok=False,
                                  error="MCP 服务 %s %s" % (config.name, detail))
            try:
                content = await client.call_tool(raw_name, call.arguments or {})
                return ToolResult(tool_call_id=call.id, ok=True, content=content)
            except MCPError as exc:
                return ToolResult(tool_call_id=call.id, ok=False, error=str(exc))
            except Exception as exc:  # pragma: no cover - defensive
                LOGGER.exception("MCP tool %s failed", raw_name)
                return ToolResult(tool_call_id=call.id, ok=False, error=str(exc))

        return handler

    # -------------------------------------------------------------- status

    def summary(self) -> Dict[str, Any]:
        return {
            "servers": [
                dict(status, name=name, tools=self.status.get(name, {}).get("tools", 0))
                for name, status in self.status.items()
            ]
        }
