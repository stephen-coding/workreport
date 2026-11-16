"""End-to-end smoke test for the v3.0.0 Core features.

One chat against a mock OpenAI-compatible SSE server, then the three new RPCs:
  1. the user turn carries two instructions, so the Core must capture two
     pending memories after ``chat/finish`` (and push ``memory/pendingChanged``);
  2. ``memory/promote`` moves one into long-term memory, ``memory/discard``
     drops the other;
  3. ``session/search`` finds the conversation that was just saved and
     ``usage/report`` shows the task in the local statistics.

Usage: ``python core/scripts/smoke_v3.py``
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

USER_MESSAGE = "以后都用中文写提交信息。不要用 wget 下载依赖。"
ANSWER = "好的，记下了：提交信息用中文，下载依赖不用 wget。"

SSE = [
    {"choices": [{"delta": {"content": ANSWER}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for chunk in SSE:
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

    root = tempfile.mkdtemp(prefix="agent-smoke-v3-")
    data_dir = os.path.join(root, "data")
    log_dir = os.path.join(root, "logs")
    project_root = os.path.join(root, "project")
    os.makedirs(project_root, exist_ok=True)

    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"
    env["AGENT_LOG_DIR"] = log_dir
    env["AGENT_USAGE_DIR"] = log_dir

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-v3",
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
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            if predicate(message):
                return message
        return None

    def call(req_id, method, params=None):
        send({"id": req_id, "method": method, "params": params or {}})
        return wait_for(lambda m: m.get("id") == req_id, timeout=15)

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
            "sessionId": "v3-1",
            "message": USER_MESSAGE,
            "contextSnapshot": {"project_root": project_root},
        }})
        if not wait_for(lambda m: m.get("id") == "chat-1", timeout=10):
            failures.append("chat/start not answered")

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                          timeout=30)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error %r" % (finish["params"],))
        else:
            print("OK chat/finish")

        changed = wait_for(lambda m: m.get("method") == "memory/pendingChanged", timeout=15)
        if changed is None:
            failures.append("no memory/pendingChanged notification")
        else:
            print("OK memory/pendingChanged", changed["params"].get("count"))

        pending = call("pending-1", "memory/pending")
        items = ((pending or {}).get("result") or {}).get("items") or []
        print("OK memory/pending", len(items))
        if len(items) != 2:
            failures.append("expected 2 candidates, got %d" % len(items))

        if items:
            promoted = call("promote-1", "memory/promote", {"id": items[0]["id"]})
            result = (promoted or {}).get("result") or {}
            print("OK memory/promote", json.dumps(result, ensure_ascii=False))
            if not result.get("memoryId"):
                failures.append("memory/promote returned no memoryId")
        if len(items) > 1:
            discarded = call("discard-1", "memory/discard", {"id": items[1]["id"]})
            if not ((discarded or {}).get("result") or {}).get("discarded"):
                failures.append("memory/discard did not discard")

        remaining = call("pending-2", "memory/pending")
        left = ((remaining or {}).get("result") or {}).get("items") or []
        if left:
            failures.append("pending list not empty after promote+discard: %r" % (left,))
        else:
            print("OK pending list empty")

        found = call("search-1", "session/search", {"query": "中文写提交信息", "limit": 5})
        results = ((found or {}).get("result") or {}).get("results") or []
        print("OK session/search", len(results))
        if not any(item.get("sessionId") == "v3-1" for item in results):
            failures.append("session/search missed the saved conversation: %r" % (results,))

        report = call("usage-1", "usage/report", {"days": 7})
        stats = (report or {}).get("result") or {}
        print("OK usage/report", json.dumps(stats, ensure_ascii=False)[:200])
        if int(stats.get("tasks") or 0) < 1:
            failures.append("usage/report counted no task")
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
        shutil.rmtree(root, ignore_errors=True)

    if failures:
        for failure in failures:
            print("FAIL:", failure)
        return 1
    print("V3 SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
