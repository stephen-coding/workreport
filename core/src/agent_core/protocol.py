"""Protocol models and envelope helpers (JSON-RPC-style envelope over JSON Lines).

All wire messages are plain dicts; the dataclasses below are the typed views used
inside the Core. Python 3.9 compatible (no PEP 604 unions).
"""

import json
import time
import uuid
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from agent_core import __version__


# ---------------------------------------------------------------------------
# Domain models
# ---------------------------------------------------------------------------

class Role(str, Enum):
    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"
    TOOL = "tool"


class Permission(str, Enum):
    AUTO = "auto"
    PREVIEW = "preview"
    CONFIRM = "confirm"


class ToolCall(BaseModel):
    id: str
    name: str
    arguments: Dict[str, Any] = Field(default_factory=dict)


class Message(BaseModel):
    role: Role
    content: Optional[str] = None
    tool_calls: Optional[List[ToolCall]] = None
    tool_call_id: Optional[str] = None
    name: Optional[str] = None


class ToolDef(BaseModel):
    name: str
    description: str
    parameters: Dict[str, Any] = Field(default_factory=dict)
    permission: Permission = Permission.AUTO
    timeout_sec: float = 30.0
    max_concurrency: int = 4
    remote: bool = True


class ToolResult(BaseModel):
    tool_call_id: str
    ok: bool
    content: Optional[str] = None
    error: Optional[str] = None


class ChatConfig(BaseModel):
    """Runtime configuration pushed by the IDE via ``settings/update``."""

    provider: str = "ollama"  # "ollama" | "openai_compat"
    base_url: str = "http://localhost:11434"
    api_key: Optional[str] = None
    model: str = "qwen2.5:7b"
    temperature: float = 0.2
    max_tokens: int = 4096
    max_iterations: int = 40
    context_window: int = 32000  # model window in tokens; 0 falls back to DEFAULT_BUDGET
    tool_concurrency: int = 4
    subagent_concurrency: int = 3
    embedding_model: str = "nomic-embed-text"
    mcp_servers: List[Dict[str, Any]] = Field(default_factory=list)
    # Empty means "use agent_core.agent.commands.DEFAULT_ALLOWLIST"; the shells
    # send their own list when the user overrides it.
    # 留空时使用内置白名单；用户覆盖后由 Shell 下发完整列表。
    command_allowlist: List[str] = Field(default_factory=list)


class ContextSnapshot(BaseModel):
    """Context pushed by the IDE together with ``chat/start``."""

    project_root: Optional[str] = None
    current_file: Optional[str] = None
    selection: Optional[str] = None
    open_files: List[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Error codes
# ---------------------------------------------------------------------------

class ErrorCode:
    PROTOCOL_ERROR = "PROTOCOL_ERROR"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
    TOOL_PERMISSION_DENIED = "TOOL_PERMISSION_DENIED"
    TOOL_TIMEOUT = "TOOL_TIMEOUT"
    TOOL_EXEC_ERROR = "TOOL_EXEC_ERROR"
    LLM_ERROR = "LLM_ERROR"
    CANCELLED = "CANCELLED"
    CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"
    RATE_LIMITED = "RATE_LIMITED"


class RpcError(Exception):
    """A protocol-level error carrying a stable error code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


# ---------------------------------------------------------------------------
# Envelope helpers
# ---------------------------------------------------------------------------

def _now_ms() -> int:
    return int(time.time() * 1000)


def make_request(method: str, params: Optional[Dict[str, Any]] = None,
                 req_id: Optional[str] = None) -> Dict[str, Any]:
    return {
        "id": req_id or uuid.uuid4().hex,
        "method": method,
        "params": params or {},
        "ts": _now_ms(),
    }


def make_notification(method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    return {
        "method": method,
        "params": params or {},
        "ts": _now_ms(),
    }


def make_response(req_id: str, result: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    return {"id": req_id, "ok": True, "result": result or {}}


def make_error(req_id: Optional[str], code: str, message: str) -> Dict[str, Any]:
    return {"id": req_id, "ok": False, "error": {"code": code, "message": message}}


def dumps(payload: Dict[str, Any]) -> str:
    """Serialize one envelope to a single JSON line (no raw newlines)."""
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


# Resolved once in agent_core/__init__.py so the CLI, the hello frame and the
# MCP clientInfo always report the same number.
# 版本号只在包初始化时解析一次，命令行、hello 帧与 MCP clientInfo 因此不会互相矛盾。
PROTOCOL_VERSION = "1.0"
CORE_VERSION = __version__
