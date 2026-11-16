"""End-to-end smoke test for ``todo_write``.

One chat against a mock OpenAI-compatible SSE server:
  1. the model calls ``todo_write`` with a two-item checklist;
  2. the Core must broadcast ``chat/todos`` with that list and its taskId;
  3. ``session/get`` must return the same list after the task finished, i.e. the
     checklist is part of the conversation on disk.

Usage: ``python core/scripts/smoke_todos.py``
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

TODOS = [
    {"id": "1", "content": "给 A.java 加注释", "status": "in_progress"},
    {"id": "2", "content": "给 B.java 加注释", "status": "pending"},
]

FINAL_ROUND_SSE = [
    {"choices": [{"delta": {"content": "清单已建立，继续执行。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


def todo_round():
    arguments = json.dumps({"todos": TODOS}, ensure_ascii=False)
    return [
        {"choices": [{"delta": {"tool_calls": [{
            "index": 0, "id": "call_todo",
            "function": {"name": "todo_write", "arguments": arguments},
        }]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    ]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        messages = body.get("messages") or []
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        chunks = FINAL_ROUND_SSE if has_tool_result else todo_round()

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
    data_dir = tempfile.mkdtemp(prefix="agent-smoke-todos-")

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-todos",
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
            "sessionId": "todo-1", "message": "给 A.java 与 B.java 加注释",
        }})
        if not wait_for(lambda m: m.get("id") == "chat-1", timeout=10):
            failures.append("chat/start not answered")

        update = wait_for(lambda m: m.get("method") == "chat/todos", timeout=20)
        if update is None:
            failures.append("no chat/todos notification")
        else:
            params = update["params"]
            got = params.get("todos") or []
            print("OK chat/todos", json.dumps(got, ensure_ascii=False))
            if got != TODOS:
                failures.append("chat/todos payload %r != %r" % (got, TODOS))
            if not params.get("taskId"):
                failures.append("chat/todos carried no taskId")

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                          timeout=30)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error %r" % (finish["params"],))
        else:
            print("OK chat/finish")

        send({"id": "get-1", "method": "session/get", "params": {"sessionId": "todo-1"}})
        answer = wait_for(lambda m: m.get("id") == "get-1", timeout=10)
        if answer is None:
            failures.append("session/get not answered")
        else:
            stored = ((answer.get("result") or {}).get("todos")) or []
            print("OK session/get todos", json.dumps(stored, ensure_ascii=False))
            if stored != TODOS:
                failures.append("persisted todos %r != %r" % (stored, TODOS))
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
    print("TODOS SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
