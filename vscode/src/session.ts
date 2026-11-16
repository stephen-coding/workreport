// Core process lifecycle, settings push and notification routing.
// Core 进程生命周期、设置下发与通知路由。

import { createHash } from "crypto";
import { homedir } from "os";
import { join } from "path";
import * as vscode from "vscode";
import { locateCoreExe, locateCoreHome, locatePython } from "./locator";
import { ChatPanel } from "./panel";
import { RpcConnection, RpcMessage } from "./rpc";
import { collectSettings } from "./settings";
import { ToolRunner } from "./tools";

export class CoreSession {
  private connection?: RpcConnection;
  private currentTaskId: string | null = null;
  private readonly projectRoot: string;
  private readonly projectId: string;
  private readonly toolRunner: ToolRunner;

  constructor(private readonly context: vscode.ExtensionContext,
              private readonly panel: ChatPanel) {
    this.projectRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? process.cwd();
    this.projectId = createHash("sha1").update(this.projectRoot).digest("hex").slice(0, 12);
    this.toolRunner = new ToolRunner(this.projectRoot);
    this.panel.setHandlers({
      onSend: (text) => void this.send(text),
      onStop: () => this.stop(),
      onConfirm: (id, approved) => this.replyConfirm(id, approved),
    });
  }

  async ensureStarted(): Promise<void> {
    if (this.connection?.isAlive()) {
      return;
    }
    const executable = locateCoreExe(this.context);
    const python = locatePython();
    const coreHome = locateCoreHome(this.context);
    if (!executable && !coreHome) {
      throw new Error("未找到 core 目录或打包好的 Core。请设置 agentCoder.coreHome 或 AGENT_CORE_HOME");
    }
    if (!executable && !python) {
      throw new Error("未找到 Python（需 3.9+）。请设置 agentCoder.pythonPath 或 AGENT_PYTHON");
    }
    const dataDir = join(homedir(), ".agent", "data", this.projectId);
    const connection = new RpcConnection({
      executable,
      python,
      coreHome,
      projectId: this.projectId,
      dataDir,
    });
    connection.on("notification", (message: RpcMessage) => this.onNotification(message));
    connection.on("stderr", (chunk: string) => {
      if (chunk.toLowerCase().includes("traceback")) {
        this.panel.appendSystem(chunk.trim().slice(0, 400));
      }
    });
    connection.on("exit", (code: number | null) => {
      this.panel.setBusy(false);
      this.panel.setStatus(`Core 已退出（${code ?? "?"}）`);
      this.panel.appendSystem("[系统] Agent Core 已退出，可执行 “Agent: 重启 Agent Core”。");
    });
    this.connection = connection;
    connection.notify("ide/hello", { protocolVersion: "1.0", ide: "vscode" });
    connection.notify("settings/update", collectSettings());
    this.panel.setStatus("正在启动 Agent Core...");
  }

  async send(text: string): Promise<void> {
    if (!text.trim() || this.currentTaskId) {
      return;
    }
    try {
      await this.ensureStarted();
    } catch (error) {
      this.panel.appendSystem(`[错误] ${error instanceof Error ? error.message : String(error)}`);
      return;
    }
    const connection = this.connection;
    if (!connection) {
      return;
    }
    this.panel.appendUser(text);
    this.panel.setBusy(true);
    this.panel.setStatus("思考中...");
    try {
      const response = await connection.request("chat/start", {
        sessionId: this.projectId,
        message: text,
        contextSnapshot: this.snapshot(),
      });
      const taskId = response.result?.taskId;
      if (typeof taskId === "string") {
        this.currentTaskId = taskId;
      }
    } catch (error) {
      this.panel.appendSystem(`[错误] ${error instanceof Error ? error.message : String(error)}`);
      this.panel.finishMessage();
      this.panel.setBusy(false);
      this.panel.setStatus("出错");
    }
  }

  stop(): void {
    this.connection?.notify("chat/cancel", this.currentTaskId ? { taskId: this.currentTaskId } : {});
  }

  pushSettings(): void {
    this.connection?.notify("settings/update", collectSettings());
  }

  async restart(): Promise<void> {
    this.connection?.dispose();
    this.connection = undefined;
    this.currentTaskId = null;
    await this.ensureStarted();
  }

  dispose(): void {
    this.connection?.dispose();
    this.connection = undefined;
  }

  // ----------------------------------------------------------- notifications

  private onNotification(message: RpcMessage): void {
    const params = message.params ?? {};
    switch (message.method) {
      case "core/hello":
        this.panel.setStatus("就绪");
        this.pushSettings();
        break;
      case "chat/token":
        this.panel.appendToken(String(params.delta ?? ""));
        break;
      case "chat/toolStart":
        this.panel.appendSystem(`\n  [工具] ${String(params.toolName ?? "?")} 执行中...`);
        break;
      case "chat/toolEnd": {
        const ok = params.ok === true;
        const summary = String(params.summary ?? "").slice(0, 120);
        this.panel.appendSystem(`  [工具] ${ok ? "完成" : "失败"}${summary ? `：${summary}` : ""}`);
        break;
      }
      case "chat/subagent": {
        const id = String(params.subagentId ?? "?");
        // A role may run on its own model, so show it next to the task id.
        const model = String(params.model ?? "");
        const label = model ? `${id}·${model}` : id;
        if (params.kind === "start") {
          this.panel.appendSystem(`\n  [子代理] ${label} 开始：${String(params.goal ?? "")}`);
        } else if (params.kind === "end") {
          const ok = params.ok === true;
          const ms = Number(params.durationMs ?? 0);
          const cost = ms > 0 ? `（${(ms / 1000).toFixed(1)}s）` : "";
          const summary = String(params.summary ?? "").slice(0, 120);
          this.panel.appendSystem(`  [子代理] ${label} ${ok ? "完成" : "失败"}${cost}${summary ? `：${summary}` : ""}`);
        }
        break;
      }
      case "chat/finish":
        this.currentTaskId = null;
        this.panel.finishMessage();
        this.appendUsage(params.usageTotal);
        this.panel.setBusy(false);
        this.panel.setStatus("就绪");
        break;
      case "chat/error": {
        const error = (params.error ?? {}) as Record<string, unknown>;
        this.currentTaskId = null;
        this.panel.appendSystem(`\n[错误:${String(error.code ?? "UNKNOWN")}] ${String(error.message ?? "")}`);
        this.panel.finishMessage();
        this.panel.setBusy(false);
        this.panel.setStatus("出错");
        break;
      }
      case "chat/notice":
        this.panel.appendSystem(`\n  [提示] ${String(params.text ?? "")}`);
        break;
      case "chat/todos": {
        // The Core sends the whole checklist; render it verbatim.
        // Core 下发整张清单，这里直接渲染。
        const raw = Array.isArray(params.todos) ? params.todos : [];
        this.panel.setTodos(raw.map((item) => {
          const todo = (item ?? {}) as Record<string, unknown>;
          return { content: String(todo.content ?? ""), status: String(todo.status ?? "pending") };
        }));
        break;
      }
      case "tool/invoke":
        if (this.connection) {
          void this.toolRunner.onToolInvoke(params, this.connection);
        }
        break;
      case "agent/confirm": {
        const confirmId = String(params.confirmId ?? "");
        if (!confirmId) {
          return;
        }
        const server = String(params.server ?? "MCP");
        const tool = String(params.tool ?? "");
        const args = JSON.stringify(params.args ?? {});
        this.panel.showConfirm(confirmId, "确认 MCP 工具调用",
          `MCP 服务 ${server} 请求调用 ${tool}，参数：${args.slice(0, 300)}`);
        break;
      }
      default:
        break;
    }
  }

  private replyConfirm(confirmId: string, approved: boolean): void {
    void this.connection?.request("agent/confirmResult", { confirmId, approved });
  }

  // The Core sums usage over every round; hide the line when none was reported.
  // 用量由 Core 汇总；端点不报用量时不显示。
  private appendUsage(raw: unknown): void {
    if (!raw || typeof raw !== "object") {
      return;
    }
    const usage = raw as Record<string, unknown>;
    const input = Number(usage.in ?? 0);
    const output = Number(usage.out ?? 0);
    if (!input && !output) {
      return;
    }
    this.panel.appendSystem(
      `\n  本轮用量：${formatTokens(input)} 输入 / ${formatTokens(output)} 输出`);
  }

  private snapshot(): Record<string, unknown> {
    const snapshot: Record<string, unknown> = { project_root: this.projectRoot };
    const editor = vscode.window.activeTextEditor;
    if (editor && editor.document.uri.scheme === "file") {
      snapshot.current_file = editor.document.uri.fsPath;
    }
    return snapshot;
  }
}

function formatTokens(value: number): string {
  return value < 1000 ? String(value) : `${(value / 1000).toFixed(1)}k`;
}
