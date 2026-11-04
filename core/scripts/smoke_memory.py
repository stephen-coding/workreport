"""End-to-end smoke test for the built-in memory tools.

The mock LLM asks for ``memory_save`` on the first round and answers with text
on the second. Memory tools run inside the Core, so the script fails if a
``tool/invoke`` notification is sent to the shell.

Usage: ``python core/scripts/smoke_memory.py``
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

TOOL_ROUND_SSE = [
    {"choices": [{"delta": {"tool_calls": [{
        "index": 0, "id": "call_mem",
        "function": {
            "name": "memory_save",
            "arguments": json.dumps({"title": "冒烟记忆", "content": "来自记忆冒烟测试",
                                     "keywords": ["smoke"]}, ensure_ascii=False),
        },
    }]}}]},
    {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
]

FINAL_ROUND_SSE = [
    {"choices": [{"delta": {"content": "已记录记忆。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        last_role = (body.get("messages") or [{}])[-1].get("role")
        chunks = FINAL_ROUND_SSE if last_role == "tool" else TOOL_ROUND_SSE

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

    data_dir = tempfile.mkdtemp(prefix="agent-smoke-memory-")
    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-memory",
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
        # Buffer every frame so later assertions never lose an earlier message.
        # 缓存全部帧，避免后续断言把早到的消息弄丢。
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
        }})
        send({"id": "chat-1", "method": "chat/start", "params": {
            "sessionId": "smoke-memory", "message": "记一下这条事实",
        }})
        if not wait_for(lambda m: m.get("id") == "chat-1", timeout=10):
            failures.append("chat/start not answered")

        invoke = wait_for(lambda m: m.get("method") == "tool/invoke", timeout=3)
        if invoke:
            failures.append("memory tool was forwarded to the shell: %r"
                            % (invoke.get("params", {}).get("toolName"),))

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"), timeout=30)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error: %r" % (finish["params"],))
        else:
            print("OK chat/finish:", finish["params"]["fullText"][:60])

        memory_dir = os.path.join(data_dir, "memories")
        db_path = os.path.join(data_dir, "memory.db")
        if not os.path.exists(db_path):
            failures.append("memory.db was not created")
        memories = os.listdir(data_dir) if os.path.isdir(data_dir) else []
        print("data dir:", memories)
        if not os.path.isdir(memory_dir):
            # project_root is unknown here, so files land under <data>/memories
            failures.append("memory markdown directory missing")
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
    print("MEMORY SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
