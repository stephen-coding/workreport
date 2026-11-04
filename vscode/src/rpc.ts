// JSON Lines RPC connection to the Agent Core (same protocol as the IntelliJ Shell).
// 与 Agent Core 的 JSON Lines RPC 连接（与 IntelliJ Shell 同一协议）。

import { ChildProcessWithoutNullStreams, spawn } from "child_process";
import { dirname } from "path";
import { EventEmitter } from "events";

export interface RpcMessage {
  id?: string;
  method?: string;
  params?: Record<string, unknown>;
  ok?: boolean;
  result?: Record<string, unknown>;
  error?: { code?: string; message?: string };
}

interface Pending {
  resolve: (message: RpcMessage) => void;
  reject: (error: Error) => void;
}

export interface SpawnOptions {
  python?: string;
  coreHome?: string;
  executable?: string;
  projectId: string;
  dataDir: string;
}

export class RpcConnection extends EventEmitter {
  private readonly process: ChildProcessWithoutNullStreams;
  private readonly pending = new Map<string, Pending>();
  private buffer = "";
  private nextId = 0;
  private disposed = false;

  constructor(options: SpawnOptions) {
    super();
    const args = ["--project-id", options.projectId, "--data-dir", options.dataDir];
    if (options.executable) {
      this.process = spawn(options.executable, args, {
        cwd: dirname(options.executable),
        env: { ...process.env, PYTHONIOENCODING: "utf-8" },
      });
    } else {
      const coreHome = options.coreHome ?? "";
      this.process = spawn(
        options.python ?? "python3",
        ["-m", "agent_core", ...args],
        {
          cwd: coreHome,
          env: {
            ...process.env,
            PYTHONPATH: `${coreHome}/src`,
            PYTHONIOENCODING: "utf-8",
          },
        },
      );
    }
    this.process.stdout.setEncoding("utf-8");
    this.process.stdout.on("data", (chunk: string) => this.onData(chunk));
    this.process.stderr.setEncoding("utf-8");
    this.process.stderr.on("data", (chunk: string) => this.emit("stderr", chunk));
    this.process.on("exit", (code) => {
      for (const [, pending] of this.pending) {
        pending.reject(new Error(`agent core exited (${code})`));
      }
      this.pending.clear();
      if (!this.disposed) {
        this.emit("exit", code);
      }
    });
  }

  private onData(chunk: string): void {
    this.buffer += chunk;
    let index = this.buffer.indexOf("\n");
    while (index >= 0) {
      const line = this.buffer.slice(0, index).trim();
      this.buffer = this.buffer.slice(index + 1);
      if (line) {
        this.dispatch(line);
      }
      index = this.buffer.indexOf("\n");
    }
  }

  private dispatch(line: string): void {
    let message: RpcMessage;
    try {
      message = JSON.parse(line) as RpcMessage;
    } catch {
      this.emit("stderr", `drop invalid frame: ${line.slice(0, 200)}`);
      return;
    }
    if (message.method) {
      this.emit("notification", message);
      return;
    }
    if (message.id) {
      const pending = this.pending.get(message.id);
      if (!pending) {
        return;
      }
      this.pending.delete(message.id);
      if (message.ok === false) {
        const text = message.error?.message ?? "rpc request failed";
        pending.reject(new Error(text));
      } else {
        pending.resolve(message);
      }
    }
  }

  request(method: string, params: Record<string, unknown>): Promise<RpcMessage> {
    const id = `req-${++this.nextId}`;
    const payload = { id, method, params };
    return new Promise<RpcMessage>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.write(payload);
    });
  }

  notify(method: string, params: Record<string, unknown>): void {
    this.write({ method, params });
  }

  private write(payload: unknown): void {
    if (this.disposed || this.process.stdin.destroyed) {
      return;
    }
    this.process.stdin.write(`${JSON.stringify(payload)}\n`);
  }

  isAlive(): boolean {
    return !this.disposed && this.process.exitCode === null;
  }

  dispose(): void {
    this.disposed = true;
    try {
      this.process.stdin.end();
    } catch {
      // best effort
    }
    this.process.kill();
  }
}
