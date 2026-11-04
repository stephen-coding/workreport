"""A tiny MCP stdio server used by the tests.

Speaks just enough JSON-RPC for the client: initialize, tools/list, tools/call.
"""

import json
import sys


TOOLS = [
    {
        "name": "echo",
        "description": "Echo the given text",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "boom",
        "description": "Always fails",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def reply(request_id, result=None, error=None):
    payload = {"jsonrpc": "2.0", "id": request_id}
    if error is not None:
        payload["error"] = error
    else:
        payload["result"] = result or {}
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        message = json.loads(line)
        method = message.get("method")
        request_id = message.get("id")
        if method == "initialize":
            reply(request_id, {"protocolVersion": "2024-11-05",
                               "capabilities": {"tools": {}},
                               "serverInfo": {"name": "fake-mcp", "version": "0.1"}})
        elif method == "notifications/initialized":
            continue
        elif method == "tools/list":
            reply(request_id, {"tools": TOOLS})
        elif method == "tools/call":
            params = message.get("params") or {}
            name = params.get("name")
            arguments = params.get("arguments") or {}
            if name == "echo":
                reply(request_id, {"content": [{"type": "text",
                                                "text": "echo:" + str(arguments.get("text", ""))}]})
            elif name == "boom":
                reply(request_id, {"isError": True,
                                   "content": [{"type": "text", "text": "boom"}]})
            else:
                reply(request_id, error={"code": -32601, "message": "unknown tool"})
        elif request_id is not None:
            reply(request_id, error={"code": -32601, "message": "unknown method"})


if __name__ == "__main__":
    main()
