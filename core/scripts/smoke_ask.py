"""End-to-end smoke test for ``ask_user`` (the blocking question tool).

One chat against a mock OpenAI-compatible SSE server:
  1. the Shell announces ``ask-user`` in ``ide/hello``;
  2. the model calls ``ask_user`` with two questions;
  3. the Core must broadcast ``ask/user`` and *not* finish the turn;
  4. the script answers with ``ask/userAnswer``;
  5. the tool result fed back to the model must contain the picked labels, and the
     turn finishes normally.

Also covers the degradation path: without the capability the tool must fail fast
instead of waiting for an answer that will never come.

Usage: ``python core/scripts/smoke_ask.py``
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

QUESTIONS = [
    {
        "header": "缓存策略",
        "question": "缓存放哪一层？",
        "options": [
            {"label": "进程内 LRU", "description": "简单，重启即失效"},
            {"label": "Redis", "description": "多实例共享，多一处依赖"},
        ],
        "multiSelect": False,
    },
    {
        "header": "过期时间",
        "question": "过期时间怎么定？",
        "options": [{"label": "跟着会话走"}, {"label": "固定 10 分钟"}],
        "multiSelect": False,
    },
]

ANSWERS = [
    {"index": 0, "picked": ["进程内 LRU"], "custom": ""},
    {"index": 1, "picked": [], "custom": "按天过期"},
]

# Filled by the mock server with the tool message it was asked to continue from.
TOOL_CONTENT = {"text": ""}

FINAL_ROUND_SSE = [
    {"choices": [{"delta": {"content": "按你选的策略继续。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


def ask_round():
    arguments = json.dumps({"questions": QUESTIONS}, ensure_ascii=False)
    return [
        {"choices": [{"delta": {"tool_calls": [{
            "index": 0, "id": "call_ask",
            "function": {"name": "ask_user", "arguments": arguments},
        }]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    ]


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        messages = body.get("messages") or []
        tool_messages = [m for m in messages if m.get("role") == "tool"]
        if tool_messages:
            TOOL_CONTENT["text"] = tool_messages[-1].get("content") or ""
            chunks = FINAL_ROUND_SSE
        else:
            chunks = ask_round()

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
    data_dir = tempfile.mkdtemp(prefix="agent-smoke-ask-")

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-ask",
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
        hello = wait_for(lambda m: m.get("method") == "core/hello", timeout=15)
        if hello is None:
            print("FAIL: no core/hello")
            return 2
        print("OK core/hello")
        if "ask-user" not in (hello.get("params") or {}).get("capabilities", []):
            failures.append("core/hello does not advertise ask-user")

        # The Shell only gets ask/user when it says it can render it.
        send({"method": "ide/hello", "params": {
            "protocolVersion": "1.0", "ide": "smoke", "capabilities": ["ask-user"],
        }})
        send({"method": "settings/update", "params": {
            "provider": "openai_compat",
            "base_url": "http://127.0.0.1:%d" % port,
            "model": "mock",
        }})

        send({"id": "chat-1", "method": "chat/start", "params": {
            "sessionId": "ask-1", "message": "帮我加一层缓存",
        }})
        if not wait_for(lambda m: m.get("id") == "chat-1", timeout=10):
            failures.append("chat/start not answered")

        request = wait_for(lambda m: m.get("method") == "ask/user", timeout=20)
        if request is None:
            failures.append("no ask/user notification")
        else:
            params = request["params"]
            print("OK ask/user", params.get("askId"))
            if not params.get("askId"):
                failures.append("ask/user carried no askId")
            if not params.get("taskId"):
                failures.append("ask/user carried no taskId")
            got = params.get("questions") or []
            if len(got) != 2 or got[0].get("header") != "缓存策略":
                failures.append("ask/user questions malformed: %r" % (got,))
            send({"id": "answer-1", "method": "ask/userAnswer", "params": {
                "askId": params.get("askId"), "skipped": False, "answers": ANSWERS,
            }})
            if not wait_for(lambda m: m.get("id") == "answer-1", timeout=10):
                failures.append("ask/userAnswer not acknowledged")

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                          timeout=30)
        if finish is None:
            failures.append("chat did not finish after the answer")
        elif finish["method"] == "chat/error":
            failures.append("chat/error %r" % (finish["params"],))
        else:
            print("OK chat/finish")

        content = TOOL_CONTENT["text"]
        print("tool result:", content.replace("\n", " | ")[:160])
        if "进程内 LRU" not in content:
            failures.append("tool result lost the picked option: %r" % content)
        if "按天过期" not in content:
            failures.append("tool result lost the custom answer: %r" % content)

        # Degradation path: no capability means the tool fails fast, never hangs.
        send({"method": "ide/hello", "params": {"ide": "smoke", "capabilities": []}})
        TOOL_CONTENT["text"] = ""
        send({"id": "chat-2", "method": "chat/start", "params": {
            "sessionId": "ask-2", "message": "再问一次",
        }})
        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                          timeout=20)
        if finish is None:
            failures.append("second chat did not finish without the capability")
        elif finish["method"] == "chat/error":
            failures.append("second chat errored %r" % (finish["params"],))
        else:
            print("OK degraded chat/finish")
            if "没有可以接收提问的界面" not in TOOL_CONTENT["text"]:
                failures.append("degraded tool result unexpected: %r" % TOOL_CONTENT["text"])
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
    print("ASK SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
