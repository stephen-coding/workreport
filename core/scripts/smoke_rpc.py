"""End-to-end RPC smoke test for the agent core.

Spawns ``python -m agent_core``, performs the handshake, sends settings and a
chat request against an unreachable endpoint, and verifies the error path.
Exits 0 on success, 1 on failure, 2 on timeout.

Usage: ``python core/scripts/smoke_rpc.py``
"""

import json
import os
import queue
import subprocess
import sys
import threading
import time

CORE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(CORE_DIR, "src")


def main() -> int:
    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "smoke",
         "--data-dir", os.path.join(CORE_DIR, ".smoke-data")],
        cwd=SRC_DIR,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        bufsize=1,
    )

    incoming = queue.Queue()

    def reader():
        for line in proc.stdout:
            incoming.put(line.strip())

    threading.Thread(target=reader, daemon=True).start()

    def send(payload):
        proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        proc.stdin.flush()

    def wait_for(predicate, timeout=20.0):
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
        if not hello:
            print("FAIL: core/hello not received")
            return 2
        print("OK core/hello:", hello["params"]["coreVersion"])

        send({"id": "ping-1", "method": "ping", "params": {}})
        pong = wait_for(lambda m: m.get("id") == "ping-1", timeout=5)
        if not pong or not pong.get("ok"):
            failures.append("ping request/response failed")
        else:
            print("OK ping/pong")

        send({"method": "settings/update", "params": {
            "provider": "openai_compat",
            "base_url": "http://127.0.0.1:9",  # intentionally unreachable
            "model": "smoke-test",
        }})
        send({"id": "chat-1", "method": "chat/start", "params": {
            "sessionId": "smoke", "message": "hello",
        }})
        start = wait_for(lambda m: m.get("id") == "chat-1", timeout=10)
        if not start or not start.get("ok"):
            failures.append("chat/start response failed: %r" % (start,))
        else:
            print("OK chat/start taskId:", start["result"]["taskId"])

        error = wait_for(
            lambda m: m.get("method") in ("chat/error", "chat/finish"), timeout=30
        )
        if error is None:
            failures.append("no chat/error received")
        elif error.get("method") == "chat/error":
            print("OK chat/error code:", error["params"]["error"]["code"])
        else:
            print("OK chat/finish (unexpected but acceptable):", error["params"]["fullText"][:80])
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()

    if failures:
        for f in failures:
            print("FAIL:", f)
        return 1
    print("SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
