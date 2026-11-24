"""End-to-end smoke test for ``todo_write``.

Two chats against a mock OpenAI-compatible SSE server:
  1. the model writes a two-item checklist and then **rewrites it** once the first item
     is done - the Core must broadcast both versions and keep the newest;
  2. ``session/get`` must return the rewritten list after the task finished;
  3. a second chat clears the list with ``todos: []``: that must succeed, broadcast an
     empty list and persist as empty (it used to be rejected).

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

TODOS_FIRST = [
    {"id": "1", "content": "给 A.java 加注释", "status": "in_progress"},
    {"id": "2", "content": "给 B.java 加注释", "status": "pending"},
]

TODOS_SECOND = [
    {"id": "1", "content": "给 A.java 加注释", "status": "completed"},
    {"id": "2", "content": "给 B.java 加注释", "status": "in_progress"},
]

CLEAR_MESSAGE = "清空清单"

FINAL_ROUND_SSE = [
    {"choices": [{"delta": {"content": "清单已建立，继续执行。"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]


def todo_round(todos, call_id):
    arguments = json.dumps({"todos": todos}, ensure_ascii=False)
    return [
        {"choices": [{"delta": {"tool_calls": [{
            "index": 0, "id": call_id,
            "function": {"name": "todo_write", "arguments": arguments},
        }]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    ]


def _todo_writes(messages):
    """How many todo_write calls the conversation already carries."""
    total = 0
    for message in messages:
        for call in (message.get("tool_calls") or []):
            if ((call.get("function") or {}).get("name")) == "todo_write":
                total += 1
    return total


def _wants_clear(messages):
    return any(message.get("role") == "user"
               and CLEAR_MESSAGE in (message.get("content") or "")
               for message in messages)


def plan(messages):
    """Next streamed answer, decided from what the conversation already contains."""
    writes = _todo_writes(messages)
    if _wants_clear(messages):
        return todo_round([], "call_todo_clear") if writes == 2 else FINAL_ROUND_SSE
    if writes == 0:
        return todo_round(TODOS_FIRST, "call_todo_1")
    if writes == 1:
        return todo_round(TODOS_SECOND, "call_todo_2")
    return FINAL_ROUND_SSE


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        chunks = plan(body.get("messages") or [])

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
            seen.append(msg)
            if predicate(msg):
                return msg
        return None

    failures = []
    # Keep every parsed frame so a sequence can be asserted after the wait.
    # 所有解析过的帧都留档，便于在等待之后断言完整序列。
    seen = []

    def collected_todos():
        return [frame.get("params") or {} for frame in seen
                if frame.get("method") == "chat/todos"]

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

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                          timeout=30)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error %r" % (finish["params"],))
        else:
            print("OK chat/finish")

        # Both writes must reach the Shell, newest last: a model that only writes once
        # is exactly the bug this smoke now guards against.
        # 两次写入都必须到达 Shell，且后者在后：只写一次就是这个用例要防的 bug。
        # chat/start only acks the launch, so the check runs after the task finished;
        # waiting for chat/finish also drains every earlier frame into ``seen``.
        # chat/start 只回执“已启动”，所以要等任务收尾后再断言；等待 finish 时
        # 顺带把之前所有帧都收进 seen。
        updates = collected_todos()
        print("OK chat/todos", len(updates), "updates")
        if [item.get("todos") for item in updates] != [TODOS_FIRST, TODOS_SECOND]:
            failures.append("chat/todos sequence %r"
                            % ([item.get("todos") for item in updates],))
        if any(not item.get("taskId") for item in updates):
            failures.append("chat/todos carried no taskId")

        send({"id": "get-1", "method": "session/get", "params": {"sessionId": "todo-1"}})
        answer = wait_for(lambda m: m.get("id") == "get-1", timeout=10)
        if answer is None:
            failures.append("session/get not answered")
        else:
            stored = ((answer.get("result") or {}).get("todos")) or []
            print("OK session/get todos", json.dumps(stored, ensure_ascii=False))
            if stored != TODOS_SECOND:
                failures.append("persisted todos %r != %r" % (stored, TODOS_SECOND))

        # Second chat, same session: an empty array is the documented way to clear the
        # checklist and must not fail.
        # 第二次对话（同一会话）：空数组表示清空，必须成功而不是报错。
        send({"id": "chat-2", "method": "chat/start", "params": {
            "sessionId": "todo-1", "message": CLEAR_MESSAGE,
        }})
        if not wait_for(lambda m: m.get("id") == "chat-2", timeout=10):
            failures.append("clear chat/start not answered")

        cleared = wait_for(lambda m: m.get("method") == "chat/todos", timeout=20)
        if cleared is None:
            failures.append("clear was not broadcast")
        else:
            got = (cleared.get("params") or {}).get("todos")
            print("OK chat/todos clear", json.dumps(got, ensure_ascii=False))
            if got != []:
                failures.append("clear broadcast %r" % (got,))

        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                          timeout=30)
        if finish is None:
            failures.append("clear chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("clear chat/error %r" % (finish["params"],))

        send({"id": "get-2", "method": "session/get", "params": {"sessionId": "todo-1"}})
        answer = wait_for(lambda m: m.get("id") == "get-2", timeout=10)
        if answer is None:
            failures.append("clear session/get not answered")
        else:
            stored = ((answer.get("result") or {}).get("todos")) or []
            print("OK session/get after clear", json.dumps(stored, ensure_ascii=False))
            if stored != []:
                failures.append("cleared todos %r != []" % (stored,))
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
