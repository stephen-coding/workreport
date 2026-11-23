"""End-to-end smoke test for the agent team (playbook, pre-approval, dispatch).

The mock LLM plays three roles:
  * the PM: team_preapprove -> team_dispatch -> team_post -> final answer;
  * a readonly member (调研员) that just answers;
  * a writable member (全栈工程师) that calls write_file once.

The script checks that the playbook and roster reach the PM prompt, that the
pre-review card is raised exactly once, that the approved phase turns the member
write into ``permission=auto`` (D-A) while the card still carries the actor, and
that blackboard artifacts reach the Shell.

Usage: ``python core/scripts/smoke_team.py``
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

REQUESTS = []
REQUESTS_LOCK = threading.Lock()


def _system_of(body):
    messages = body.get("messages") or []
    return (messages[0].get("content") or "") if messages else ""


def _tool_texts(body):
    return [m.get("content") or "" for m in (body.get("messages") or [])
            if m.get("role") == "tool"]


def _is_member_request(body):
    # Members carry the team header prepended by TeamService._member_prompt.
    return _system_of(body).startswith("你是团队「")


def _sse(chunks):
    return chunks


def _tool_round(name, arguments):
    return [
        {"choices": [{"delta": {"tool_calls": [{
            "index": 0, "id": "call_" + name,
            "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)},
        }]}}]},
        {"choices": [{"delta": {}, "finish_reason": "tool_calls"}]},
    ]


def _final(text):
    return [{"choices": [{"delta": {"content": text}}]},
            {"choices": [{"delta": {}, "finish_reason": "stop"}]}]


PREAPPROVE_ROUND = _tool_round("team_preapprove", {
    "plan": "给 core 加一层缓存", "first_diff": "+ cache = {}", "files": []})
DISPATCH_ROUND = _tool_round("team_dispatch", {
    "tasks": [{"role": "全栈工程师", "goal": "实现缓存", "writes": ["core/cache.py"]}],
})
POST_ROUND = _tool_round("team_post", {
    "kind": "impl", "title": "缓存已实现", "body": "新增 core/cache.py", "author_role": "全栈工程师"})
MEMBER_WRITE_ROUND = _tool_round("write_file", {"path": "core/cache.py", "content": "cache = {}\n"})


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        with REQUESTS_LOCK:
            REQUESTS.append(body)
        tool_texts = _tool_texts(body)
        # Only the latest tool result decides the next step: earlier ones stay in the
        # transcript and would otherwise re-trigger the same branch forever.
        last_tool = tool_texts[-1] if tool_texts else ""

        if _is_member_request(body):
            chunks = _final("成员结论：已完成。") if tool_texts else MEMBER_WRITE_ROUND
        elif "预审已通过" in last_tool:
            chunks = DISPATCH_ROUND
        elif "团队执行结果" in last_tool:
            chunks = POST_ROUND
        elif "已写入黑板" in last_tool:
            chunks = _final("团队已完成：实现 + 记录。")
        elif tool_texts:
            chunks = _final("预审未通过，改为人工确认。")
        else:
            chunks = PREAPPROVE_ROUND

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

    data_dir = tempfile.mkdtemp(prefix="agent-smoke-team-")
    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke-team",
         "--data-dir", data_dir],
        cwd=SRC_DIR, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, text=True, encoding="utf-8", bufsize=1,
    )

    received = []
    received_lock = threading.Lock()
    answered = set()

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

    def snapshot():
        with received_lock:
            return list(received)

    def wait_for(predicate, timeout=30.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for message in snapshot():
                if predicate(message):
                    return message
            time.sleep(0.05)
        return None

    # The Shell is this script: answer confirm cards and tool invocations.
    stop = threading.Event()

    def responder():
        while not stop.is_set():
            for message in snapshot():
                method = message.get("method")
                params = message.get("params") or {}
                if method == "agent/confirm":
                    confirm_id = params.get("confirmId")
                    if confirm_id in answered:
                        continue
                    answered.add(confirm_id)
                    send({"id": "cf-" + confirm_id, "method": "agent/confirmResult",
                          "params": {"confirmId": confirm_id, "approved": True}})
                elif method == "tool/invoke":
                    call_id = params.get("toolCallId")
                    if call_id in answered:
                        continue
                    answered.add(call_id)
                    send({"id": "tr-" + call_id, "method": "tool/result",
                          "params": {"toolCallId": call_id, "ok": True,
                                     "result": "已写入 core/cache.py"}})
            time.sleep(0.03)

    threading.Thread(target=responder, daemon=True).start()

    failures = []
    try:
        if not wait_for(lambda m: m.get("method") == "core/hello", timeout=15):
            print("FAIL: no core/hello")
            return 2
        hello = wait_for(lambda m: m.get("method") == "core/hello")
        if "team" not in (hello["params"].get("capabilities") or []):
            failures.append("core/hello does not advertise team")
        print("OK core/hello advertises team")

        # A modern shell advertises the optional cards it can render; without this the Core
        # deliberately skips the pre-approval checkpoint and falls back to per-write confirms.
        # 新 Shell 先声明能渲染的可选卡片；不声明时 Core 会跳过预审，退化为逐次确认。
        send({"method": "ide/hello", "params": {
            "protocolVersion": "1.0", "ide": "smoke",
            "capabilities": ["ask-user", "team"],
        }})

        send({"method": "settings/update", "params": {
            "provider": "openai_compat",
            "base_url": "http://127.0.0.1:%d" % port,
            "model": "mock",
            "active_team": "software-dev-team",
        }})
        send({"id": "chat-1", "method": "team/list", "params": {}})
        teams = wait_for(lambda m: m.get("id") == "chat-1", timeout=10)
        if not teams or not teams.get("result", {}).get("teams"):
            failures.append("team/list returned no teams")
        else:
            active = [t for t in teams["result"]["teams"] if t["active"]]
            if not active or active[0]["name"] != "software-dev-team":
                failures.append("team/list did not mark the active team")
            else:
                print("OK team/list:", [m["role"] for m in active[0]["members"]])

        send({"id": "chat-2", "method": "chat/start", "params": {
            "sessionId": "smoke-team", "message": "给 core 加一层缓存"}})
        finish = wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"), timeout=90)
        if finish is None:
            failures.append("chat did not finish")
        elif finish["method"] == "chat/error":
            failures.append("chat/error: %r" % (finish.get("params"),))
        else:
            print("OK chat/finish:", finish["params"]["fullText"][:40])

        confirms = [m["params"] for m in snapshot() if m.get("method") == "agent/confirm"]
        preapproves = [c for c in confirms if c.get("kind") == "preapprove"]
        print("confirm cards:", [(c.get("kind"), c.get("preapproveId")) for c in confirms])
        if len(preapproves) != 1:
            failures.append("expected exactly 1 preapprove card, got %d" % len(preapproves))

        invokes = [m["params"] for m in snapshot() if m.get("method") == "tool/invoke"]
        writes = [i for i in invokes if i.get("toolName") == "write_file"]
        if not writes:
            failures.append("member never wrote a file")
        else:
            write = writes[0]
            if write.get("permission") != "auto":
                failures.append("approved phase did not auto-approve the write: %r"
                                % write.get("permission"))
            if write.get("member") != "全栈工程师":
                failures.append("write card is not labelled with the member: %r"
                                % write.get("member"))
            print("OK member write: permission=%s member=%s"
                  % (write.get("permission"), write.get("member")))
        if any(i.get("toolName") == "team_dispatch" for i in invokes):
            failures.append("team_dispatch leaked to the Shell")

        artifacts = [m["params"] for m in snapshot() if m.get("method") == "chat/artifact"]
        print("artifacts:", [(a.get("kind"), a.get("authorRole")) for a in artifacts])
        if not any(a.get("kind") == "impl" for a in artifacts):
            failures.append("no impl artifact reached the Shell")

        events = [m["params"] for m in snapshot() if m.get("method") == "chat/subagent"]
        if not events:
            failures.append("no chat/subagent events")
        elif any(not e.get("teamRunId") for e in events):
            failures.append("member event has no teamRunId")
        elif not any(e.get("actor") == "全栈工程师" for e in events):
            failures.append("member event is not labelled with the actor")
        else:
            print("OK member events:", [(e.get("actor"), e.get("capability")) for e in events])

        with REQUESTS_LOCK:
            main_requests = [b for b in REQUESTS if not _is_member_request(b)]
            member_requests = [b for b in REQUESTS if _is_member_request(b)]
        if not main_requests:
            failures.append("PM sent no request")
        else:
            system = _system_of(main_requests[0])
            if "本团队由项目经理" not in system:
                failures.append("playbook missing from the PM prompt")
            if "全栈工程师" not in system:
                failures.append("roster missing from the PM prompt")
            print("OK PM prompt carries playbook + roster")
        if not member_requests:
            failures.append("member sent no request")
        else:
            names = set()
            for body in member_requests:
                for tool in body.get("tools") or []:
                    names.add(tool.get("function", {}).get("name"))
            print("member tools:", sorted(name for name in names if name))
            if "write_file" not in names:
                failures.append("write member did not receive write_file")
            if "delete_file" in names:
                failures.append("deletion was granted to a member")
            if "subagent_dispatch" in names:
                failures.append("recursion was offered to a member")
    finally:
        stop.set()
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
        print("--- diagnostics ---")
        print("methods:", [m.get("method") or ("resp:" + str(m.get("id")))
                           for m in snapshot()])
        print("tool invokes:", [(m["params"].get("toolName"), m["params"].get("permission"))
                                for m in snapshot() if m.get("method") == "tool/invoke"])
        print("member events:", [(m["params"].get("kind"), m["params"].get("actor"))
                                 for m in snapshot() if m.get("method") == "chat/subagent"])
        with REQUESTS_LOCK:
            print("llm requests:", len(REQUESTS),
                  "members:", sum(1 for b in REQUESTS if _is_member_request(b)))
        for failure in failures:
            print("FAIL:", failure)
        return 1
    print("TEAM SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())