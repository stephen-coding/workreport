"""Minimal MCP client (JSON-RPC 2.0) without an SDK dependency.

The official ``mcp`` package requires Python 3.10+, while the Core targets 3.9,
so the stdio transport is implemented directly over subprocess pipes. Both
transports speak the same request/response envelope.
"""

import asyncio
import json
import logging
import os
from typing import Any, Dict, List, Optional

import httpx

from agent_core.mcp.config import MCPServerConfig
from agent_core.protocol import CORE_VERSION

LOGGER = logging.getLogger(__name__)

PROTOCOL_VERSION = "2024-11-05"
CALL_TIMEOUT = 30.0


class MCPError(Exception):
    """Raised for transport or protocol failures."""


def _flatten_result(result: Dict[str, Any]) -> str:
    if not isinstance(result, dict):
        return str(result)
    if result.get("isError"):
        raise MCPError(_text_of(result) or "工具返回错误")
    text = _text_of(result)
    if text:
        return text
    structured = result.get("structuredContent")
    if structured is not None:
        return json.dumps(structured, ensure_ascii=False)
    return json.dumps(result, ensure_ascii=False)


def _text_of(result: Dict[str, Any]) -> str:
    blocks = result.get("content")
    if not isinstance(blocks, list):
        return ""
    parts: List[str] = []
    for block in blocks:
        if not isinstance(block, dict):
            continue
        if block.get("type") == "text":
            parts.append(str(block.get("text") or ""))
        elif block.get("type") == "resource":
            parts.append(json.dumps(block.get("resource"), ensure_ascii=False))
        else:
            parts.append(json.dumps(block, ensure_ascii=False))
    return "\n".join(part for part in parts if part)


class _BaseClient:
    def __init__(self, config: MCPServerConfig) -> None:
        self.config = config
        self.server_info: Dict[str, Any] = {}

    async def start(self) -> None:  # pragma: no cover - interface
        raise NotImplementedError

    async def list_tools(self) -> List[Dict[str, Any]]:  # pragma: no cover - interface
        raise NotImplementedError

    async def call_tool(self, name: str, arguments: Dict[str, Any]) -> str:  # pragma: no cover
        raise NotImplementedError

    async def close(self) -> None:  # pragma: no cover - interface
        raise NotImplementedError

    @staticmethod
    def _initialize_params() -> Dict[str, Any]:
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "agent-core", "version": CORE_VERSION},
        }


class StdioMCPClient(_BaseClient):
    def __init__(self, config: MCPServerConfig) -> None:
        super().__init__(config)
        self._proc: Optional[asyncio.subprocess.Process] = None
        self._reader_task: Optional[asyncio.Task] = None
        self._pending: Dict[int, asyncio.Future] = {}
        self._next_id = 0
        self._write_lock = asyncio.Lock()

    async def start(self) -> None:
        if not self.config.command:
            raise MCPError("stdio 传输缺少 command")
        env = dict(os.environ)
        env.update(self.config.env)
        try:
            self._proc = await asyncio.create_subprocess_exec(
                self.config.command, *self.config.args,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                env=env,
            )
        except OSError as exc:
            raise MCPError("无法启动 MCP 进程 %s: %s" % (self.config.command, exc))
        self._reader_task = asyncio.create_task(self._read_loop())
        result = await self._request("initialize", self._initialize_params())
        self.server_info = result.get("serverInfo") or {}
        await self._notify("notifications/initialized", {})

    async def _read_loop(self) -> None:
        assert self._proc is not None and self._proc.stdout is not None
        while True:
            try:
                line = await self._proc.stdout.readline()
            except asyncio.CancelledError:
                break
            if not line:
                break
            try:
                message = json.loads(line.decode("utf-8", errors="replace"))
            except ValueError:
                continue
            message_id = message.get("id")
            if message_id is None:
                continue
            future = self._pending.pop(message_id, None)
            if future is None or future.done():
                continue
            if "error" in message:
                future.set_exception(MCPError(str(message["error"])))
            else:
                future.set_result(message.get("result") or {})
        for future in list(self._pending.values()):
            if not future.done():
                future.set_exception(MCPError("MCP 进程已退出"))
        self._pending.clear()

    async def _send(self, payload: Dict[str, Any]) -> None:
        if self._proc is None or self._proc.stdin is None:
            raise MCPError("MCP 进程未启动")
        data = (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8")
        async with self._write_lock:
            self._proc.stdin.write(data)
            await self._proc.stdin.drain()

    async def _request(self, method: str, params: Dict[str, Any]) -> Dict[str, Any]:
        self._next_id += 1
        request_id = self._next_id
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        await self._send({"jsonrpc": "2.0", "id": request_id,
                          "method": method, "params": params})
        try:
            return await asyncio.wait_for(future, timeout=CALL_TIMEOUT)
        except asyncio.TimeoutError:
            raise MCPError("MCP 调用超时: %s" % method)
        finally:
            self._pending.pop(request_id, None)

    async def _notify(self, method: str, params: Dict[str, Any]) -> None:
        await self._send({"jsonrpc": "2.0", "method": method, "params": params})

    async def list_tools(self) -> List[Dict[str, Any]]:
        result = await self._request("tools/list", {})
        tools = result.get("tools")
        return tools if isinstance(tools, list) else []

    async def call_tool(self, name: str, arguments: Dict[str, Any]) -> str:
        result = await self._request("tools/call", {"name": name, "arguments": arguments})
        return _flatten_result(result)

    async def close(self) -> None:
        if self._reader_task is not None:
            self._reader_task.cancel()
            self._reader_task = None
        if self._proc is not None:
            try:
                if self._proc.stdin is not None:
                    self._proc.stdin.close()
                self._proc.terminate()
                await asyncio.wait_for(self._proc.wait(), timeout=3)
            except Exception:  # pragma: no cover - best effort
                try:
                    self._proc.kill()
                except Exception:
                    pass
            self._proc = None


class HttpMCPClient(_BaseClient):
    """Streamable-HTTP transport: one POST per request, session id echoed back."""

    def __init__(self, config: MCPServerConfig) -> None:
        super().__init__(config)
        self._client: Optional[httpx.AsyncClient] = None
        self._session_id: Optional[str] = None
        self._next_id = 0

    async def start(self) -> None:
        if not self.config.url:
            raise MCPError("http 传输缺少 url")
        self._client = httpx.AsyncClient(timeout=CALL_TIMEOUT)
        result = await self._request("initialize", self._initialize_params())
        self.server_info = result.get("serverInfo") or {}

    def _headers(self) -> Dict[str, str]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        return headers

    async def _request(self, method: str, params: Dict[str, Any]) -> Dict[str, Any]:
        if self._client is None or not self.config.url:
            raise MCPError("HTTP MCP 客户端未启动")
        self._next_id += 1
        payload = {"jsonrpc": "2.0", "id": self._next_id, "method": method, "params": params}
        try:
            response = await self._client.post(self.config.url, json=payload,
                                               headers=self._headers())
        except httpx.HTTPError as exc:
            raise MCPError("HTTP MCP 请求失败: %s" % exc)
        self._session_id = response.headers.get("Mcp-Session-Id") or self._session_id
        if response.status_code >= 400:
            raise MCPError("HTTP MCP %s 返回 %s" % (method, response.status_code))
        message = _parse_body(response.text)
        if "error" in message:
            raise MCPError(str(message["error"]))
        return message.get("result") or {}

    async def list_tools(self) -> List[Dict[str, Any]]:
        result = await self._request("tools/list", {})
        tools = result.get("tools")
        return tools if isinstance(tools, list) else []

    async def call_tool(self, name: str, arguments: Dict[str, Any]) -> str:
        result = await self._request("tools/call", {"name": name, "arguments": arguments})
        return _flatten_result(result)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None


def _parse_body(body: str) -> Dict[str, Any]:
    """Accept a plain JSON body or an SSE stream of ``data:`` frames."""
    text = (body or "").strip()
    if not text:
        return {}
    if text.startswith("{"):
        try:
            return json.loads(text)
        except ValueError:
            return {}
    last: Dict[str, Any] = {}
    for line in text.splitlines():
        if line.startswith("data:"):
            chunk = line[5:].strip()
            if not chunk or chunk == "[DONE]":
                continue
            try:
                parsed = json.loads(chunk)
            except ValueError:
                continue
            if isinstance(parsed, dict):
                last = parsed
    return last


def build_client(config: MCPServerConfig) -> _BaseClient:
    if config.transport == "http":
        return HttpMCPClient(config)
    return StdioMCPClient(config)
