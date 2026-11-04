"""MCP integration: config parsing, JSON-RPC clients and the tool bridge."""

from agent_core.mcp.client import MCPError, build_client
from agent_core.mcp.config import (
    MCPServerConfig,
    load_project_config,
    merge_configs,
    parse_servers,
)
from agent_core.mcp.manager import MCPManager, tool_name

__all__ = [
    "MCPError",
    "MCPManager",
    "MCPServerConfig",
    "build_client",
    "load_project_config",
    "merge_configs",
    "parse_servers",
    "tool_name",
]
