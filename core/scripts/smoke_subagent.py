"""End-to-end smoke test for the subagent pool.

The mock LLM answers the main chat with a ``subagent_dispatch`` tool call for two
goals; requests whose system prompt says "子代理" are answered directly as the
subagent's conclusion. The script then checks the event stream and the final
answer.

Usage: ``python core/scripts/smoke_subagent.py``
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

REQUESTS = []  # recorded request bodies


def _sse(chunks):
    return chunks


def _dispatch_round():
    return [
        {"choices": [{"delta": {"tool_calls": [{
            "index": 0, "id": "call_sub",
            "function": {
                "name": "subagent_dispatch",
                "arguments": json.dumps({
                    "type": "code-reviewer",
                    "goals": ["审查 A 模块", "审查 B 模块"],
                }, ensure_ascii=False),
            },
        }]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    ]


FINAL_ROUND = [
    {"choices": [{"delta": {"content": "两个子代理都已完成审查。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        REQUESTS.append(body)
        messages = body.get("messages") or []
        system = messages[0].get("content") or "" if messages else ""
        last_role = (messages[-1] if messages else {}).get("role")

        if "子代理" in system:
            chunks = [{"choices": [{"delta": {"content": "子代理结论：未发现严重问题。"}}]},
                      {"choices": [{"delta": {}, "finish_reason": "stop"}]}]
        elif last_role == "tool":
            chunks = FINAL_ROUND
        else:
            chunks = _dispatch_round()

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

    data_dir = tempfile.mkdtemp(prefix="agent-smoke-subagent-")
    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-subagent",
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
    try:
        if not wait_for(lambda m: m.get("method") == "core/hello", timeout=15):
            print("FAIL: no core/hello")
            return 2
        print("OK core/hello")

        send({"method": "settings/update", "params": {
            "provider": "openai_compat",
            "base_url": "http://127.0.0.1:%d" % port,
            "model": "mock",
            "subagent_concurrency": 2,
        }})
        send({"id": "chat-1", "method": "chat/start", "params": {
            "sessionId": "smoke-subagent", "message": "并行审查两个模块",
        }})
        if not wait_for(lambda m: m.get("id") == "chat-1", timeout=10):
            failures.append("chat/start not answered")

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"), timeout=60)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error: %r" % (finish.get("params"),))
        else:
            print("OK chat/finish:", finish["params"]["fullText"][:60])

        with received_lock:
            events = [m["params"] for m in received
                      if m.get("method") == "chat/subagent"]
        starts = [e for e in events if e.get("kind") == "start"]
        ends = [e for e in events if e.get("kind") == "end"]
        print("subagent events:", [(e.get("kind"), e.get("subagentId")) for e in events])
        if len(starts) != 2:
            failures.append("expected 2 subagent starts, got %d" % len(starts))
        if len(ends) != 2:
            failures.append("expected 2 subagent ends, got %d" % len(ends))
        if any(not e.get("ok") for e in ends):
            failures.append("a subagent reported failure")

        invoke = wait_for(lambda m: m.get("method") == "tool/invoke", timeout=1)
        if invoke:
            failures.append("subagent_dispatch was forwarded to the shell: %r"
                            % (invoke.get("params", {}).get("toolName"),))

        subagent_requests = [b for b in REQUESTS
                             if "子代理" in ((b.get("messages") or [{}])[0].get("content") or "")]
        if len(subagent_requests) != 2:
            failures.append("expected 2 subagent LLM requests, got %d" % len(subagent_requests))
        tool_names = set()
        for body in subagent_requests:
            for tool in body.get("tools") or []:
                tool_names.add(tool.get("function", {}).get("name"))
        print("subagent tools:", sorted(tool_names))
        if "subagent_dispatch" in tool_names:
            failures.append("recursive subagent_dispatch was offered to a subagent")
        if not tool_names:
            failures.append("subagent received no tools")
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
    print("SUBAGENT SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
