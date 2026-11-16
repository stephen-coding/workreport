"""End-to-end smoke test for ``run_command`` policy.

Three chats against a mock OpenAI-compatible SSE server:
  1. ``git status``        -> tool/invoke carries ``permission=auto`` (allowlist)
  2. ``python deploy.py``  -> tool/invoke carries ``permission=confirm``
  3. ``git push origin master`` -> no tool/invoke at all; the model receives the
     refusal text as the tool result.

Usage: ``python core/scripts/smoke_command.py``
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

# Swapped between chats; the mock serves the current command on its first round.
STATE = {"command": "git status"}
BODIES = []

FINAL_ROUND_SSE = [
    {"choices": [{"delta": {"content": "命令执行完毕，已根据输出继续。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


def tool_round(command: str):
    arguments = json.dumps({"command": command}, ensure_ascii=False)
    return [
        {"choices": [{"delta": {"tool_calls": [{
            "index": 0, "id": "call_cmd",
            "function": {"name": "run_command", "arguments": arguments},
        }]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    ]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        messages = body.get("messages") or []
        BODIES.append(messages)
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        chunks = FINAL_ROUND_SSE if has_tool_result else tool_round(STATE["command"])

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
    data_dir = tempfile.mkdtemp(prefix="agent-smoke-command-")

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-command",
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

    def drain(seconds=1.0):
        """Drop notifications that belong to the previous chat."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                incoming.get(timeout=0.05)
            except queue.Empty:
                return

    failures = []

    def run_chat(session: str, command: str, expect_invoke: bool) -> None:
        STATE["command"] = command
        drain(0.3)
        send({"id": "chat-" + session, "method": "chat/start", "params": {
            "sessionId": session, "message": "跑一下命令：" + command,
        }})
        if not wait_for(lambda m: m.get("id") == "chat-" + session, timeout=10):
            failures.append("%s: chat/start not answered" % command)
            return
        if expect_invoke:
            invoke = wait_for(lambda m: m.get("method") == "tool/invoke", timeout=20)
            if not invoke:
                failures.append("%s: no tool/invoke" % command)
                return
            params = invoke["params"]
            print("OK tool/invoke", params["toolName"], "permission=",
                  params.get("permission"))
            if params["toolName"] != "run_command":
                failures.append("%s: unexpected tool %s" % (command, params["toolName"]))
            if params.get("permission") != STATE["expected"]:
                failures.append("%s: permission %r != %r"
                                % (command, params.get("permission"), STATE["expected"]))
            if params.get("params", {}).get("command") != command:
                failures.append("%s: command not forwarded verbatim" % command)
            send({"id": "tr-" + session, "method": "tool/result", "params": {
                "toolCallId": params["toolCallId"], "ok": True, "result": "exit 0",
            }})
            wait_for(lambda m: m.get("id") == "tr-" + session, timeout=5)

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                          timeout=30)
        if finish is None:
            failures.append("%s: chat did not finish" % command)
        elif finish["method"] == "chat/error":
            failures.append("%s: chat/error %r" % (command, finish["params"]))
        else:
            print("OK chat/finish", command)

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

        STATE["expected"] = "auto"
        run_chat("cmd-auto", "git status", expect_invoke=True)
        STATE["expected"] = "confirm"
        run_chat("cmd-confirm", "python deploy.py", expect_invoke=True)
        run_chat("cmd-deny", "git push origin master", expect_invoke=False)

        # The refusal must reach the model as the tool result.
        denied = any(
            any(m.get("role") == "tool" and "已拒绝执行危险命令" in (m.get("content") or "")
                for m in messages)
            for messages in BODIES
        )
        if denied:
            print("OK dangerous command refused and reported back to the model")
        else:
            failures.append("deny: model never saw the refusal text")
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
    print("COMMAND SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
