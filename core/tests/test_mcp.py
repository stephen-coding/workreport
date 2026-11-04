"""Tests for the MCP bridge: config merging, stdio client and tool registration."""

import asyncio
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from agent_core.mcp import (  # noqa: E402
    MCPError,
    MCPManager,
    MCPServerConfig,
    load_project_config,
    merge_configs,
    parse_servers,
    tool_name,
)
from agent_core.mcp.client import StdioMCPClient, _flatten_result, _parse_body  # noqa: E402
from agent_core.protocol import Permission, ToolCall  # noqa: E402
from agent_core.tools.registry import ToolRegistry  # noqa: E402

FAKE_SERVER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake_mcp_server.py")


def fake_config(**kwargs):
    params = dict(name="fake", transport="stdio", command=sys.executable,
                  args=[FAKE_SERVER], auto_approve=True)
    params.update(kwargs)
    return MCPServerConfig(**params)


class TestConfigParsing(unittest.TestCase):
    def test_parse_list_shape(self):
        configs = parse_servers([
            {"name": "a", "command": "npx", "args": ["-y", "server"]},
            {"name": "b", "transport": "http", "url": "http://localhost:9000/mcp"},
        ])
        self.assertEqual([c.name for c in configs], ["a", "b"])
        self.assertEqual(configs[0].args, ["-y", "server"])
        self.assertEqual(configs[1].url, "http://localhost:9000/mcp")

    def test_parse_name_map_shape(self):
        configs = parse_servers({"github": {"command": "gh-mcp", "auto_approve": True}})
        self.assertEqual(len(configs), 1)
        self.assertEqual(configs[0].name, "github")
        self.assertTrue(configs[0].auto_approve)

    def test_disabled_servers_are_dropped(self):
        merged = merge_configs([], [MCPServerConfig(name="off", enabled=False),
                                   MCPServerConfig(name="on")])
        self.assertEqual([c.name for c in merged], ["on"])

    def test_project_config_wins(self):
        settings = [MCPServerConfig(name="dup", command="from-settings")]
        project = [MCPServerConfig(name="dup", command="from-project")]
        merged = merge_configs(project, settings)
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0].command, "from-project")

    def test_load_project_config(self):
        tmp = tempfile.mkdtemp(prefix="agent-mcp-")
        try:
            os.makedirs(os.path.join(tmp, ".agent"))
            path = os.path.join(tmp, ".agent", "mcp.json")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write('{"mcpServers": [{"name": "srv", "command": "run"}]}')
            configs = load_project_config(tmp)
            self.assertEqual(configs[0].name, "srv")
        finally:
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)

    def test_load_project_config_missing_is_empty(self):
        self.assertEqual(load_project_config(None), [])
        self.assertEqual(load_project_config(os.path.join(tempfile.gettempdir(), "nope-x")), [])


class TestHelpers(unittest.TestCase):
    def test_tool_name_is_slugged(self):
        self.assertEqual(tool_name("my server", "read-file"), "mcp__my_server__read_file")

    def test_flatten_error_result_raises(self):
        with self.assertRaises(MCPError):
            _flatten_result({"isError": True, "content": [{"type": "text", "text": "bad"}]})

    def test_parse_sse_body(self):
        body = 'event: message\ndata: {"result": {"tools": []}}\n\n'
        self.assertEqual(_parse_body(body), {"result": {"tools": []}})


class TestStdioClient(unittest.TestCase):
    def test_lifecycle(self):
        async def scenario():
            client = StdioMCPClient(fake_config())
            await client.start()
            try:
                self.assertEqual(client.server_info.get("name"), "fake-mcp")
                tools = await client.list_tools()
                self.assertEqual({t["name"] for t in tools}, {"echo", "boom"})
                self.assertEqual(await client.call_tool("echo", {"text": "hi"}), "echo:hi")
                with self.assertRaises(MCPError):
                    await client.call_tool("boom", {})
            finally:
                await client.close()

        asyncio.run(scenario())

    def test_missing_command_is_reported(self):
        async def scenario():
            client = StdioMCPClient(MCPServerConfig(name="broken"))
            with self.assertRaises(MCPError):
                await client.start()

        asyncio.run(scenario())


class TestManager(unittest.TestCase):
    def test_refresh_registers_tools_and_calls_them(self):
        async def scenario():
            registry = ToolRegistry()
            manager = MCPManager()
            await manager.refresh(registry, [fake_config()])
            try:
                self.assertIn("mcp__fake__echo", registry.names())
                self.assertIn("mcp__fake__boom", registry.names())
                tool_def = registry.get("mcp__fake__echo")
                self.assertEqual(tool_def.permission, Permission.AUTO)
                self.assertFalse(tool_def.remote)

                result = await registry.handler("mcp__fake__echo")(
                    ToolCall(id="c1", name="mcp__fake__echo", arguments={"text": "yo"}))
                self.assertTrue(result.ok)
                self.assertEqual(result.content, "echo:yo")

                failed = await registry.handler("mcp__fake__boom")(
                    ToolCall(id="c2", name="mcp__fake__boom", arguments={}))
                self.assertFalse(failed.ok)
                self.assertIn("boom", failed.error)
                self.assertEqual(manager.summary()["servers"][0]["tools"], 2)
            finally:
                await manager.shutdown()

        asyncio.run(scenario())

    def test_confirm_denied_skips_call(self):
        async def scenario():
            registry = ToolRegistry()
            manager = MCPManager()
            calls = []

            async def deny(server, tool, arguments):
                calls.append((server, tool))
                return False

            manager.set_confirm(deny)
            await manager.refresh(registry, [fake_config(auto_approve=False)])
            try:
                self.assertEqual(registry.get("mcp__fake__echo").permission,
                                 Permission.CONFIRM)
                result = await registry.handler("mcp__fake__echo")(
                    ToolCall(id="c1", name="mcp__fake__echo", arguments={"text": "x"}))
                self.assertFalse(result.ok)
                self.assertIn("用户拒绝", result.error)
                self.assertEqual(calls, [("fake", "echo")])
            finally:
                await manager.shutdown()

        asyncio.run(scenario())

    def test_failed_server_is_recorded_and_disabled(self):
        async def scenario():
            registry = ToolRegistry()
            manager = MCPManager()
            broken = MCPServerConfig(name="broken", command=sys.executable,
                                     args=[os.path.join(tempfile.gettempdir(), "no-such.py")])
            await manager.refresh(registry, [broken])
            try:
                self.assertFalse(manager.summary()["servers"][0]["ok"])
                self.assertEqual(registry.names(), [])
            finally:
                await manager.shutdown()

        asyncio.run(scenario())


if __name__ == "__main__":
    unittest.main()
