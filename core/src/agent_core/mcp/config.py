"""MCP server configuration models and project-level ``.agent/mcp.json`` loading.

Two sources are merged, project level first:
* ``<project>/.agent/mcp.json`` (shared with the team, version controlled)
* the ``mcp_servers`` array pushed by the IDE via ``settings/update``
"""

import json
import logging
import os
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

LOGGER = logging.getLogger(__name__)

PROJECT_CONFIG = os.path.join(".agent", "mcp.json")


class MCPServerConfig(BaseModel):
    name: str
    transport: str = "stdio"  # stdio | http
    command: Optional[str] = None
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    url: Optional[str] = None
    enabled: bool = True
    auto_approve: bool = False


def _coerce(entry: Dict[str, Any]) -> Optional[MCPServerConfig]:
    name = entry.get("name")
    if not name:
        return None
    return MCPServerConfig(
        name=str(name),
        transport=str(entry.get("transport") or "stdio"),
        command=entry.get("command"),
        args=[str(a) for a in (entry.get("args") or [])],
        env={str(k): str(v) for k, v in (entry.get("env") or {}).items()},
        url=entry.get("url"),
        enabled=bool(entry.get("enabled", True)),
        auto_approve=bool(entry.get("auto_approve", False)),
    )


def parse_servers(raw: Any) -> List[MCPServerConfig]:
    """Accept ``[{...}]`` and ``{"name": {...}}`` shapes."""
    configs: List[MCPServerConfig] = []
    if isinstance(raw, list):
        for entry in raw:
            if isinstance(entry, dict):
                config = _coerce(entry)
                if config:
                    configs.append(config)
    elif isinstance(raw, dict):
        for name, entry in raw.items():
            if isinstance(entry, dict):
                payload = dict(entry)
                payload.setdefault("name", name)
                config = _coerce(payload)
                if config:
                    configs.append(config)
    return configs


def load_project_config(project_root: Optional[str]) -> List[MCPServerConfig]:
    if not project_root:
        return []
    path = os.path.join(project_root, PROJECT_CONFIG)
    if not os.path.isfile(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError):
        LOGGER.warning("cannot read MCP config %s", path, exc_info=True)
        return []
    return parse_servers(data.get("mcpServers") if isinstance(data, dict) else data)


def merge_configs(project: List[MCPServerConfig],
                  settings: List[MCPServerConfig]) -> List[MCPServerConfig]:
    merged: Dict[str, MCPServerConfig] = {config.name: config for config in settings}
    for config in project:
        merged[config.name] = config  # project level wins
    return [config for config in merged.values() if config.enabled]
