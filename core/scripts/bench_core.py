"""Local performance baseline for the Core (ROADMAP §7.4).

Measures the four numbers the roadmap tracks, against a mock OpenAI-compatible
endpoint and a throw-away data directory:

  cold start      process spawn -> ``core/hello``            target < 3.0 s
  idle RSS        10 s after start                          target < 120 MB
  single round    ``chat/start`` -> ``chat/finish``          target < 300 ms
  session list    ``session/list`` over 1000 conversations   target < 150 ms

The protocol codec is reported as well: wire frames, wire bytes, ``chat/token``
frames and the share of wall time spent in JSON encode/decode.  Those numbers
decide whether a binary format would help at all: if the codec share stays in the
low single digits, the cost sits in frame *count* and payload *size*, not in the
format.

The one-time search index build is reported as well, but it is measured before
the list benchmark: running them together would measure disk contention rather
than the endpoint.

CI records the numbers instead of gating on them: machine differences would
otherwise turn a real run red.  Exit code is non-zero only when the Core cannot
be started at all.

Usage:
    python core/scripts/bench_core.py            # human readable table
    python core/scripts/bench_core.py --json out.json
"""

import argparse
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

TARGETS = {
    "coldStartMs": 3000.0,
    "idleRssMb": 120.0,
    "roundTripMs": 300.0,
    "sessionListMs": 150.0,
}
HISTORY_SESSIONS = 1000
IDLE_SECONDS = 10.0

SSE = [
    {"choices": [{"delta": {"content": "pong"}}]},
    {"choices": [{"delta": {}, "finish_reason": "stop"}]},
]
# Rebuilt from ``--stream-chunks`` so the token-frame count matches a real answer
# instead of the two-chunk default.
STREAM_SSE = list(SSE)


def build_stream(chunks: int):
    """One delta per chunk, like a slow endpoint streaming a long reply."""
    body = [{"choices": [{"delta": {"content": "字"}}]} for _ in range(max(0, chunks))]
    body.append({"choices": [{"delta": {}, "finish_reason": "stop"}]})
    return body


class MockLLMHandler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for chunk in STREAM_SSE:
            self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, *args):
        pass


def rss_mb(pid: int) -> float:
    """Resident memory of the Core process, without requiring psutil."""
    try:
        import psutil  # type: ignore

        return psutil.Process(pid).memory_info().rss / (1024 * 1024)
    except Exception:
        pass
    if os.name == "nt":
        return _windows_rss_mb(pid)
    try:
        with open("/proc/%d/status" % pid, "r", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    return float(line.split()[1]) / 1024.0
    except OSError:
        pass
    return 0.0


def _windows_rss_mb(pid: int) -> float:
    import ctypes
    from ctypes import wintypes

    class Counters(ctypes.Structure):
        _fields_ = [
            ("cb", wintypes.DWORD),
            ("PageFaultCount", wintypes.DWORD),
            ("PeakWorkingSetSize", ctypes.c_size_t),
            ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
            ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t),
            ("PeakPagefileUsage", ctypes.c_size_t),
        ]

    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    process = ctypes.windll.kernel32.OpenProcess(0x0400 | 0x0010, False, pid)
    if not process:
        return 0.0
    try:
        ok = ctypes.windll.psapi.GetProcessMemoryInfo(
            process, ctypes.byref(counters), counters.cb)
        return counters.WorkingSetSize / (1024 * 1024) if ok else 0.0
    finally:
        ctypes.windll.kernel32.CloseHandle(process)


def seed_sessions(directory: str, count: int) -> None:
    """The list endpoint reads every file, so the baseline needs real files."""
    root = os.path.join(directory, "sessions")
    os.makedirs(root, exist_ok=True)
    body = "这是一段用来占位的会话正文，长度接近真实对话。" * 6
    for index in range(count):
        payload = {
            "version": 2,
            "id": "bench-%04d" % index,
            "title": "基准会话 %d" % index,
            "updatedAt": 1_700_000_000_000 + index,
            "messages": [
                {"role": "user", "content": "问题 %d" % index},
                {"role": "assistant", "content": body},
            ],
            "todos": [],
        }
        with open(os.path.join(root, "bench-%04d.json" % index), "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False)


def main() -> int:
    parser = argparse.ArgumentParser(description="Core performance baseline")
    parser.add_argument("--json", default="", help="also write the numbers to this file")
    parser.add_argument("--sessions", type=int, default=HISTORY_SESSIONS)
    parser.add_argument("--idle-seconds", type=float, default=IDLE_SECONDS)
    parser.add_argument("--stream-chunks", type=int, default=0,
                        help="delta frames per answer (0 = the short default)")
    args = parser.parse_args()

    global STREAM_SSE
    STREAM_SSE = build_stream(args.stream_chunks) if args.stream_chunks else list(SSE)

    mock = ThreadingHTTPServer(("127.0.0.1", 0), MockLLMHandler)
    port = mock.server_address[1]
    threading.Thread(target=mock.serve_forever, daemon=True).start()

    root = tempfile.mkdtemp(prefix="agent-bench-")
    data_dir = os.path.join(root, "data")
    seed_sessions(data_dir, args.sessions)

    env = dict(os.environ)
    env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"
    env["AGENT_LOG_DIR"] = os.path.join(root, "logs")
    env["AGENT_USAGE_DIR"] = os.path.join(root, "logs")

    started = time.perf_counter()
    proc = subprocess.Popen(
        [sys.executable, "-m", "agent_core", "--project-id", "bench",
         "--data-dir", data_dir, "--no-log-file"],
        cwd=SRC_DIR, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, text=True, encoding="utf-8", bufsize=1,
    )
    incoming: queue.Queue = queue.Queue()
    # Protocol cost accounting: frames and bytes both ways, plus the time spent
    # turning lines into dicts and dicts into lines.
    # 协议开销统计：双向帧数/字节数，以及 JSON 编解码本身花掉的时间。
    stats = {"framesIn": 0, "framesOut": 0, "bytesIn": 0, "bytesOut": 0,
             "decodeMs": 0.0, "encodeMs": 0.0, "tokenFrames": 0}

    def reader():
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            parsed_at = time.perf_counter()
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            stats["decodeMs"] += (time.perf_counter() - parsed_at) * 1000
            stats["framesIn"] += 1
            stats["bytesIn"] += len(line.encode("utf-8"))
            if message.get("method") == "chat/token":
                stats["tokenFrames"] += 1
            incoming.put(message)

    threading.Thread(target=reader, daemon=True).start()

    def send(payload):
        encoded_at = time.perf_counter()
        text = json.dumps(payload, ensure_ascii=False)
        stats["encodeMs"] += (time.perf_counter() - encoded_at) * 1000
        stats["framesOut"] += 1
        stats["bytesOut"] += len(text.encode("utf-8"))
        proc.stdin.write(text + "\n")
        proc.stdin.flush()

    def wait_for(predicate, timeout=60.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                message = incoming.get(timeout=max(0.05, deadline - time.time()))
            except queue.Empty:
                continue
            if predicate(message):
                return message
        return None

    numbers = {"coldStartMs": 0.0, "idleRssMb": 0.0, "roundTripMs": 0.0,
               "sessionListMs": 0.0, "indexMs": 0.0, "coreVersion": "",
               "sessions": args.sessions, "wireFrames": 0, "wireBytes": 0,
               "tokenFrames": 0, "codecMs": 0.0, "codecSharePct": 0.0}
    try:
        hello = wait_for(lambda m: m.get("method") == "core/hello", timeout=60)
        if hello is None:
            print("FAIL: the Core never said core/hello", file=sys.stderr)
            return 2
        numbers["coldStartMs"] = (time.perf_counter() - started) * 1000
        numbers["coreVersion"] = str((hello.get("params") or {}).get("coreVersion") or "")

        # The first-run migration reads the same files the list endpoint does, so
        # it has to finish first for a meaningful list number.
        index_started = time.perf_counter()
        wait_for(lambda m: m.get("method") == "session/indexProgress"
                 and bool((m.get("params") or {}).get("finished")), timeout=180)
        numbers["indexMs"] = (time.perf_counter() - index_started) * 1000

        send({"method": "settings/update", "params": {
            "provider": "openai_compat",
            "base_url": "http://127.0.0.1:%d" % port,
            "model": "bench",
        }})

        # The codec share is measured over warm-up + one round + one list call: the
        # idle wait and the index build would only dilute the ratio.
        # 编解码占比只在预热+一轮+一次列表的窗口里统计，避免空闲与建索引稀释分母。
        bench_started = time.perf_counter()

        # Warm up once so the round trip measures the loop, not the first import.
        send({"id": "warm", "method": "chat/start",
              "params": {"sessionId": "bench-warm", "message": "ping"}})
        wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"), timeout=60)

        round_started = time.perf_counter()
        send({"id": "round", "method": "chat/start",
              "params": {"sessionId": "bench-round", "message": "ping"}})
        if wait_for(lambda m: m.get("method") in ("chat/finish", "chat/error"),
                    timeout=60) is None:
            print("FAIL: the round never finished", file=sys.stderr)
            return 2
        numbers["roundTripMs"] = (time.perf_counter() - round_started) * 1000

        list_started = time.perf_counter()
        send({"id": "list", "method": "session/list", "params": {}})
        answer = wait_for(lambda m: m.get("id") == "list", timeout=60)
        numbers["sessionListMs"] = (time.perf_counter() - list_started) * 1000
        listed = len(((answer or {}).get("result") or {}).get("sessions") or [])

        bench_elapsed_ms = (time.perf_counter() - bench_started) * 1000
        codec_ms = stats["encodeMs"] + stats["decodeMs"]
        numbers["wireFrames"] = stats["framesIn"] + stats["framesOut"]
        numbers["wireBytes"] = stats["bytesIn"] + stats["bytesOut"]
        numbers["tokenFrames"] = stats["tokenFrames"]
        numbers["codecMs"] = codec_ms
        numbers["codecSharePct"] = (codec_ms / bench_elapsed_ms * 100.0) \
            if bench_elapsed_ms > 0 else 0.0

        time.sleep(max(0.0, args.idle_seconds))
        numbers["idleRssMb"] = rss_mb(proc.pid)
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        mock.shutdown()
        shutil.rmtree(root, ignore_errors=True)

    print("Agent Core baseline (core %s, %d stored sessions, %d listed)"
          % (numbers["coreVersion"], args.sessions, listed))
    print("%-16s %12s %12s %s" % ("metric", "value", "target", "status"))
    for key, label, unit in (("coldStartMs", "cold start", "ms"),
                             ("idleRssMb", "idle RSS", "MB"),
                             ("roundTripMs", "single round", "ms"),
                             ("sessionListMs", "session/list", "ms"),
                             ("indexMs", "index build", "ms")):
        value = numbers[key]
        target = TARGETS.get(key)
        if target is None:
            print("%-16s %10.1f %-2s %10s %-2s %s"
                  % (label, value, unit, "-", "", "info"))
            continue
        status = "ok" if value <= target else "over"
        print("%-16s %10.1f %-2s %10.0f %-2s %s"
              % (label, value, unit, target, unit, status))

    print("%-16s %10.0f    %s" % ("wire frames", numbers["wireFrames"], "in + out"))
    print("%-16s %10.1f KB %s"
          % ("wire bytes", numbers["wireBytes"] / 1024.0, "in + out"))
    print("%-16s %10.0f    %s"
          % ("token frames", numbers["tokenFrames"], "chat/token"))
    print("%-16s %10.1f %%  %s"
          % ("codec share", numbers["codecSharePct"], "of warm+round+list"))

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(numbers, handle, ensure_ascii=False, indent=2)
        print("written to", args.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
