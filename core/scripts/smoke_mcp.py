"""End-to-end smoke test for the MCP bridge.

Starts two MCP servers from ``tests/fake_mcp_server.py``: one auto-approved and
one that must be confirmed by the shell. The mock LLM asks for both tools in the
first round; the script answers ``agent/confirm`` and then checks that the tool
outputs ("echo:...") reached the second LLM request.

Usage: ``python core/scripts/smoke_mcp.py``
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CORE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(CORE_DIR, "src")
FAKE_SERVER = os.path.join(CORE_DIR, "tests", "fake_mcp_server.py")

ROUNDS = []  # recorded request bodies, for the post-run assertions


def _tool_round():
    calls = [
        {"index": 0, "id": "call_auto", "function": {
            "name": "mcp__auto__echo",
            "arguments": json.dumps({"text": "hi"}),
        }},
        {"index": 1, "id": "call_ask", "function": {
            "name": "mcp__ask__echo",
            "arguments": json.dumps({"text": "there"}),
        }},
    ]
    return [
        {"choices": [{"delta": {"tool_calls": calls}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    ]


FINAL_ROUND = [
    {"choices": [{"delta": {"content": "两个 MCP 工具都执行完成。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        ROUNDS.append(body)
        last_role = (body.get("messages") or [{}])[-1].get("role")
        chunks = FINAL_ROUND if last_role == "tool" else _tool_round()

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for chunk in chunks:
            self.wfile.write(("data: " + json.dumps(chunk) + "\n").encode("utf-8") + b"\n")
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, *args):
        pass


def main() -> int:
    server = ThreadingHTTPServer(("127.0.0.1", 0), MockLLMHandler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print("mock LLM on port", port)

    data_dir = tempfile.mkdtemp(prefix="agent-smoke-mcp-")
    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-mcp",
         "--data-dir", data_dir],
        cwd=SRC_DIR, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, text=True, encoding="utf-8", bufsize=1,
    )

    received = []
    received_lock = threading.Lock()

    def reader():
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            with received_lock:
                received.append(message)

    threading.Thread(target=reader, daemon=True).start()

    def send(payload):
        proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        proc.stdin.flush()

    def wait_for(predicate, timeout=30.0):
        deadline = time.time() + timeout
        cursor = 0
        while time.time() < deadline:
            with received_lock:
                window = received[cursor:]
                cursor = len(received)
            for message in window:
                if predicate(message):
                    return message
            time.sleep(0.05)
        return None

    failures = []
    confirms = []
    try:
        if not wait_for(lambda m: m.get("method") == "core/hello", timeout=15):
            print("FAIL: no core/hello")
            return 2
        print("OK core/hello")

        servers = [
            {"name": "auto", "transport": "stdio", "command": sys.executable,
             "args": [FAKE_SERVER], "auto_approve": True},
            {"name": "ask", "transport": "stdio", "command": sys.executable,
             "args": [FAKE_SERVER], "auto_approve": False},
        ]
        send({"method": "settings/update", "params": {
            "provider": "openai_compat",
            "base_url": "http://127.0.0.1:%d" % port,
            "model": "mock",
            "mcp_servers": servers,
        }})
        # MCP servers connect asynchronously after settings/update.
        # MCP 服务在 settings/update 之后异步连接，这里给一点握手时间。
        time.sleep(2.0)

        send({"id": "chat-1", "method": "chat/start", "params": {
            "sessionId": "smoke-mcp", "message": "调用两个 echo 工具",
        }})
        if not wait_for(lambda m: m.get("id") == "chat-1", timeout=10):
            failures.append("chat/start not answered")

        confirm = wait_for(lambda m: m.get("method") == "agent/confirm", timeout=15)
        if confirm is None:
            failures.append("no agent/confirm for the non-approved server")
        else:
            params = confirm.get("params") or {}
            confirms.append(params)
            print("OK agent/confirm:", params.get("server"), params.get("tool"))
            send({"id": "confirm-1", "method": "agent/confirmResult", "params": {
                "confirmId": params.get("confirmId"), "approved": True,
            }})

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"), timeout=30)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error: %r" % (finish.get("params"),))
        else:
            print("OK chat/finish:", finish["params"]["fullText"][:60])

        invoke = wait_for(lambda m: m.get("method") == "tool/invoke", timeout=2)
        if invoke:
            failures.append("MCP tool was forwarded to the shell: %r"
                            % (invoke.get("params", {}).get("toolName"),))

        tool_messages = []
        for body in ROUNDS:
            for message in body.get("messages") or []:
                if message.get("role") == "tool":
                    tool_messages.append(message.get("content") or "")
        joined = "\n".join(tool_messages)
        if "echo:hi" not in joined:
            failures.append("auto-approved MCP result missing: %r" % joined[:300])
        if "echo:there" not in joined:
            failures.append("confirmed MCP result missing: %r" % joined[:300])
        print("tool messages:", joined.replace("\n", " | ")[:200])

        if len(confirms) != 1:
            failures.append("expected exactly one confirm, got %d" % len(confirms))
        elif confirms[0].get("server") != "ask":
            failures.append("confirm asked for the wrong server: %r" % (confirms[0].get("server"),))
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        server.shutdown()
        shutil.rmtree(data_dir, ignore_errors=True)

    if failures:
        for failure in failures:
            print("FAIL:", failure)
        return 1
    print("MCP SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
