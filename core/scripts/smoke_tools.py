"""End-to-end smoke test for the M2 tool loop.

Starts a mock OpenAI-compatible SSE server (first round: one read_file tool
call; second round: final text), spawns the agent core, answers the tool
invocation, and verifies the whole chat finishes.

Usage: ``python core/scripts/smoke_tools.py``
"""

import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CORE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(CORE_DIR, "src")

TOOL_ROUND_SSE = [
    {"choices": [{"delta": {"tool_calls": [{
        "index": 0, "id": "call_1",
        "function": {"name": "read_file", "arguments": "{\"path\": \"README.md\"}"},
    }]}}]},
    {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
]

FINAL_ROUND_SSE = [
    {"choices": [{"delta": {"content": "已读取 README，内容开头是项目说明。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        messages = body.get("messages") or []
        # The self-check status bar is appended as a trailing user message, so the
        # last role is not a reliable signal — look for any tool result instead.
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        chunks = FINAL_ROUND_SSE if has_tool_result else TOOL_ROUND_SSE

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for chunk in chunks:
            self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, *args):
        pass


def main() -> int:
    server = ThreadingHTTPServer(("127.0.0.1", 0), MockLLMHandler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print("mock LLM on port", port)

    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"
    # A fresh data dir per run: a reused session would already hold tool results from
    # the previous run and the mock would skip straight to the final answer.
    # 每次跑用干净的 data 目录：复用旧会话会让模拟服务看到历史工具结果而直接收尾。
    data_dir = tempfile.mkdtemp(prefix="agent-smoke-tools-")

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-tools",
         "--data-dir", data_dir],
        cwd=SRC_DIR, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, text=True, encoding="utf-8", bufsize=1,
    )

    incoming = queue.Queue()

    def reader():
        for line in proc.stdout:
            incoming.put(line.strip())

    threading.Thread(target=reader, daemon=True).start()

    def send(payload):
        proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        proc.stdin.flush()

    def wait_for(predicate, timeout=30.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                line = incoming.get(timeout=max(0.05, deadline - time.time()))
            except queue.Empty:
                continue
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if predicate(msg):
                return msg
        return None

    failures = []
    try:
        if not wait_for(lambda m: m.get("method") == "core/hello", timeout=15):
            print("FAIL: no core/hello")
            return 2
        print("OK core/hello")

        send({"method": "settings/update", "params": {
            "provider": "openai_compat",
            "base_url": "http://127.0.0.1:%d" % port,
            "model": "mock",
        }})
        send({"id": "chat-1", "method": "chat/start", "params": {
            "sessionId": "smoke-tools", "message": "读一下 README",
        }})
        if not wait_for(lambda m: m.get("id") == "chat-1", timeout=10):
            failures.append("chat/start not answered")

        invoke = wait_for(lambda m: m.get("method") == "tool/invoke", timeout=20)
        if not invoke:
            failures.append("no tool/invoke received")
        else:
            params = invoke["params"]
            print("OK tool/invoke:", params["toolName"], params.get("params"))
            send({"id": "tr-1", "method": "tool/result", "params": {
                "toolCallId": params["toolCallId"],
                "ok": True,
                "result": "README 内容（模拟）",
            }})
            if not wait_for(lambda m: m.get("id") == "tr-1", timeout=5):
                failures.append("tool/result not acknowledged")

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"), timeout=30)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error: %r" % (finish["params"],))
        else:
            print("OK chat/finish:", finish["params"]["fullText"][:60])
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
        for f in failures:
            print("FAIL:", f)
        return 1
    print("TOOL SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
