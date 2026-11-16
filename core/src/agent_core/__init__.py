"""Agent Core — IDE-agnostic agent engine for the coding assistant plugin.

Entry point: ``python -m agent_core --project-id <id> --data-dir <dir>``.
Communicates with the IDE shells over JSON Lines using a JSON-RPC-style
envelope (custom fields, not strict JSON-RPC 2.0).
"""

import json
import os
import sys


def _packaged_version() -> str:
    """Read the version stamped into the bundle by packaging/agent-core.spec."""
    base = getattr(sys, "_MEIPASS", "")
    if not base:
        return ""
    try:
        with open(os.path.join(base, "core-build-info.json"), "r", encoding="utf-8") as fh:
            return str(json.load(fh).get("version") or "").strip()
    except (OSError, ValueError):
        return ""


def _resolve_version() -> str:
    """One number for the CLI, ``core/hello`` and MCP ``clientInfo``.

    Priority: build-injected env var (release builds) -> version stamped into the
    packaged exe -> installed distribution metadata -> a dev marker for source
    checkouts. Keeping the resolution here means protocol.py and __main__.py can
    never drift from pom.xml.
    """
    injected = os.environ.get("AGENT_CORE_VERSION", "").strip()
    if injected:
        return injected
    packaged = _packaged_version()
    if packaged:
        return packaged
    try:
        from importlib.metadata import version

        return version("agent-core")
    except Exception:  # package not installed (source checkout)
        return "0.0.0-dev"


__version__ = _resolve_version()
