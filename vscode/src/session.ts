// Core process lifecycle, settings push and notification routing.
// Core 进程生命周期、设置下发与通知路由。

import { createHash } from "crypto";
import { homedir } from "os";
import { join } from "path";
import * as vscode from "vscode";
import { locateCoreExe, locateCoreHome, locatePython } from "./locator";
import { ArtifactEntry, ChatPanel, HistoryEntry, PendingMemory, TeamEntry, TodoItem } from "./panel";
import { RpcConnection, RpcMessage } from "./rpc";
import { collectSettings } from "./settings";
import { ensureApiKey } from "./secrets";
import { ToolRunner } from "./tools";

export class CoreSession {
  private connection?: RpcConnection;
  private currentTaskId: string | null = null;
  private readonly projectRoot: string;
  private readonly projectId: string;
  private readonly toolRunner: ToolRunner;
  // Read from SecretStorage / env once, then reused for every settings push.
  // 启动时从加密存储 / 环境变量读一次，之后每次下发复用。
  private apiKey = "";
  private readonly keyReady: Promise<void>;
  // Conversation currently shown; new tabs (and old files) keep their own id.
  // 当前显示的会话 id；新建会话或重开历史时会更换。
  private sessionId: string;

  constructor(private readonly context: vscode.ExtensionContext,
              private readonly panel: ChatPanel) {
    this.projectRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? process.cwd();
    this.projectId = createHash("sha1").update(this.projectRoot).digest("hex").slice(0, 12);
    this.sessionId = this.projectId;
    this.toolRunner = new ToolRunner(this.projectRoot);
    this.keyReady = this.loadApiKey();
    this.panel.setHandlers({
      onSend: (text) => void this.send(text),
      onStop: () => this.stop(),
      onConfirm: (id, approved) => this.replyConfirm(id, approved),
      onHistoryList: () => void this.loadHistory(),
      onHistoryOpen: (id) => void this.openSession(id),
      onHistoryDelete: (id) => void this.deleteSession(id),
      onHistorySearch: (query) => void this.searchHistory(query),
      onMemoryList: () => void this.loadMemories(),
      onMemoryResolve: (id, approve, content) => void this.resolveMemory(id, approve, content),
      onNewSession: () => this.newSession(),
      onTeamList: () => void this.loadTeams(),
      onTeamSelect: (name) => void this.setTeam(name),
      onArtifacts: () => void this.loadArtifacts(),
    });
  }

  // Migrate a plaintext key on first start; a broken SecretStorage must never
  // block the chat, so failures fall back to the environment variable.
  // 首次启动迁移明文 Key；加密存储不可用时退回环境变量，不阻断对话。
  private async loadApiKey(): Promise<void> {
    try {
      const { key, migrated } = await ensureApiKey(this.context);
      this.apiKey = key;
      if (migrated) {
        this.panel.setStatus("已把 API Key 迁移到安全存储");
      }
    } catch (error) {
      this.apiKey = (process.env.OPENAI_API_KEY ?? "").trim();
      const message = error instanceof Error ? error.message : String(error);
      this.panel.appendSystem(`[提示] API Key 读取失败（${message}），已退回环境变量`);
    }
  }

  async ensureStarted(): Promise<void> {
    if (this.connection?.isAlive()) {
      return;
    }
    await this.keyReady;
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
    // ``team`` promises the Core that this shell can render the pre-review card;
    // without it the Core falls back to one confirmation per write.
    // team 表示本 Shell 能渲染预审卡；不声明时 Core 会退化成逐次确认。
    connection.notify("ide/hello", {
      protocolVersion: "1.0",
      ide: "vscode",
      capabilities: ["ask-user", "team"],
    });
    connection.notify("settings/update", collectSettings(this.apiKey));
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
        sessionId: this.sessionId,
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
    void this.keyReady.then(() => {
      this.connection?.notify("settings/update", collectSettings(this.apiKey));
    });
  }

  // Live connection for callers that only read (inline completion); undefined
  // while the Core is down keeps typing free of prompts.
  // 供只读调用方（行内补全）使用的连接；Core 未就绪时返回 undefined，输入不受影响。
  connectionOrNull(): RpcConnection | undefined {
    return this.connection?.isAlive() ? this.connection : undefined;
  }

  // --------------------------------------------------------------- history

  // List stored conversations; starts the Core first when it is not running yet.
  // 历史列表：Core 未启动时先启动，再取列表。
  private async loadHistory(): Promise<void> {
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      const response = await connection.request("session/list", {});
      this.panel.showHistory(toEntries(response.result?.sessions));
    } catch (error) {
      this.panel.appendSystem(`[错误] 读取历史失败：${describe(error)}`);
    }
  }

  // Replay one stored conversation and keep talking to it from now on.
  // 重放一条历史会话，并把后续对话接到这条会话上。
  private async openSession(id: string): Promise<void> {
    if (!id) {
      return;
    }
    if (this.currentTaskId) {
      this.panel.appendSystem("\n  [提示] 当前任务还在跑，先停止再切换会话。");
      return;
    }
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      const response = await connection.request("session/get", { sessionId: id });
      const result = response.result ?? {};
      this.sessionId = id;
      this.panel.reset();
      for (const item of asArray(result.messages)) {
        const text = String(item.content ?? "");
        if (text.trim()) {
          this.panel.appendHistory(String(item.role ?? ""), text);
        }
      }
      this.panel.setTodos(toTodos(result.todos));
      this.panel.appendSystem("\n  已载入历史会话（已保存在本机），可以接着聊。");
      this.panel.setStatus("就绪");
    } catch (error) {
      this.panel.appendSystem(`[错误] 打开历史会话失败：${describe(error)}`);
    }
  }

  private newSession(): void {
    if (this.currentTaskId) {
      this.panel.appendSystem("\n  [提示] 当前任务还在跑，先停止再新建会话。");
      return;
    }
    // The timestamp keeps a new conversation from overwriting an old file.
    // 带时间戳的新 id，避免新建会话覆盖已有历史。
    this.sessionId = `${this.projectId}#${Date.now().toString(36)}`;
    this.panel.reset();
    this.panel.setStatus("新会话（不继承其它会话的上下文）");
  }

  private async deleteSession(id: string): Promise<void> {
    if (!id) {
      return;
    }
    const answer = await vscode.window.showWarningMessage(
      "删除这条历史会话？本机文件会一并删除，无法恢复。", { modal: true }, "删除");
    if (answer !== "删除") {
      return;
    }
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      const response = await connection.request("session/delete", { sessionId: id });
      const freed = Number(response.result?.freedBytes ?? 0);
      if (id === this.sessionId) {
        this.newSession();
      }
      // Say how much disk the delete actually reclaimed.
      // 告诉用户这次删除真正释放了多少磁盘。
      this.panel.setStatus(`已删除历史会话${freed > 0 ? `（释放 ${formatSize(freed)}）` : ""}`);
      await this.loadHistory();
    } catch (error) {
      this.panel.appendSystem(`[错误] 删除历史会话失败：${describe(error)}`);
    }
  }

  // Empty query falls back to the plain list so clearing the box restores it.
  // 查询为空时退回完整列表：清空搜索框就能恢复。
  private async searchHistory(query: string): Promise<void> {
    if (!query.trim()) {
      await this.loadHistory();
      return;
    }
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      const response = await connection.request("session/search", { query, limit: 20 });
      this.panel.showHistorySearch(toSearchEntries(response.result?.results), query);
    } catch (error) {
      this.panel.appendSystem(`[错误] 搜索历史失败：${describe(error)}`);
    }
  }

  // ---------------------------------------------------------------- memory

  private async loadMemories(): Promise<void> {
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      const response = await connection.request("memory/pending", {});
      this.panel.showMemories(toMemories(response.result?.items));
    } catch (error) {
      this.panel.appendSystem(`[错误] 读取记忆候选失败：${describe(error)}`);
    }
  }

  // Re-read after every decision so the list matches the Core.
  // 每次处理完重新拉一次，保证列表与 Core 一致。
  private async resolveMemory(id: string, approve: boolean, content?: string): Promise<void> {
    if (!id) {
      return;
    }
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      if (approve) {
        const params: Record<string, unknown> = { id };
        if (content) {
          params.content = content;
        }
        const response = await connection.request("memory/promote", params);
        const result = response.result ?? {};
        const duplicate = result.duplicate as Record<string, unknown> | undefined;
        this.panel.setStatus(`已记住：${String(result.title ?? "")}`
          + (duplicate ? `（与已有记忆 #${String(duplicate.id)} 相似，建议整理）` : ""));
      } else {
        await connection.request("memory/discard", { id });
        this.panel.setStatus("已忽略这条记忆候选");
      }
      await this.loadMemories();
    } catch (error) {
      this.panel.appendSystem(`[错误] 记忆操作失败：${describe(error)}`);
    }
  }

  // ------------------------------------------------------------------ team

  // Roster for the toolbar selector. The Core owns the list; until it answers
  // the selector only shows 「不启用」, which is also the shipped default.
  // 名册由 Core 维护；未答复前下拉只有「不启用」，这也是发布的默认值。
  private async loadTeams(): Promise<void> {
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      const response = await connection.request("team/list", {});
      const teams = toTeams(response.result?.teams);
      const active = teams.find((team) => team.active)?.name ?? "";
      this.panel.setTeams(teams, active);
    } catch (error) {
      this.panel.appendSystem(`[错误] 读取团队名簿失败：${describe(error)}`);
    }
  }

  // Switching teams is a user knob, so it is persisted and pushed like the model.
  // 团队开关属于用户配置：与模型选择一样持久化并下发。
  private async setTeam(name: string): Promise<void> {
    const target = name.trim();
    try {
      await vscode.workspace.getConfiguration("agentCoder")
        .update("activeTeam", target, vscode.ConfigurationTarget.Workspace);
    } catch (error) {
      this.panel.appendSystem(`[错误] 保存团队设置失败：${describe(error)}`);
      return;
    }
    this.pushSettings();
    this.panel.setStatus(target ? `已启用团队：${target}` : "已停用团队：写操作逐次确认");
    this.panel.appendSystem(target
      ? `\n  [团队] 已启用「${target}」：成员按 capability 授权，实现阶段先给一次预审。`
      : "\n  [团队] 已停用：回到单人逐次确认。");
    await this.loadTeams();
  }

  // Blackboard drawer: every artifact of this conversation, newest last.
  // 黑板抽屉：本会话的全部交付物。
  private async loadArtifacts(): Promise<void> {
    const connection = await this.connected();
    if (!connection) {
      return;
    }
    try {
      const response = await connection.request("team/artifacts", { sessionId: this.sessionId });
      this.panel.showArtifacts(toArtifacts(response.result?.artifacts));
    } catch (error) {
      this.panel.appendSystem(`[错误] 读取交付物失败：${describe(error)}`);
    }
  }

  // Shared "start if needed, then hand over the connection" step.
  // 历史操作共用：必要时启动 Core，再把连接交给调用方。
  private async connected(): Promise<RpcConnection | undefined> {
    try {
      await this.ensureStarted();
    } catch (error) {
      this.panel.appendSystem(`[错误] ${describe(error)}`);
      return undefined;
    }
    return this.connection;
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
        // The roster is Core-owned, so read it as soon as the Core is up.
        // 名册在 Core 里，Core 一就绪就读一次。
        void this.loadTeams();
        break;
      case "session/indexProgress":
        this.panel.setStatus(params.finished === true
          ? "就绪"
          : "正在建立会话索引，稍后可在「历史」里搜索...");
        break;
      case "memory/pendingChanged": {
        const count = Number(params.count ?? 0);
        if (count > 0) {
          this.panel.setStatus(`有 ${count} 条新的记忆候选，点「记忆」确认`);
        }
        break;
      }
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
        const subagentId = String(params.subagentId ?? "?");
        // A team member carries an alias and its capability, so the timeline reads
        // 「全栈工程师[write]」 instead of the bare role id.
        // 团队成员会带别名与能力，时间线因此能显示「全栈工程师[write]」。
        const actor = String(params.actor ?? "");
        const capability = String(params.capability ?? "");
        const model = String(params.model ?? "");
        const who = actor || subagentId;
        const label = (model ? `${who}·${model}` : who) + (capability ? `[${capability}]` : "");
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
      case "chat/artifact": {
        // Blackboard post: one line in the timeline, full text behind 「交付物」.
        // 黑板投稿：时间线一行，全文在「交付物」里看。
        const kind = String(params.kind ?? "note");
        const title = String(params.title ?? "");
        const author = String(params.authorRole ?? "");
        this.panel.appendSystem(`\n  [交付物] [${kind}] ${title || "-"}${author ? " · " + author : ""}`);
        this.panel.setStatus("有新交付物，点「交付物」查看");
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
        this.panel.setTodos(toTodos(params.todos));
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
        // One card per implementation phase (D-A): allow it and this phase's
        // writes run automatically; deletes and dangerous commands still stop.
        // 每个实现阶段一张预审卡（D-A）：允许后本阶段写入自动执行，删除与危险命令仍拦。
        if (String(params.kind ?? "") === "preapprove") {
          this.panel.showConfirm(confirmId, "团队预审", preapproveDetail(params));
          this.panel.setStatus("等待预审确认：允许后本阶段写入自动执行");
          break;
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

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function asArray(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value as Array<Record<string, unknown>> : [];
}

// The Core owns the shape; the Shell only tolerates missing fields.
// 结构由 Core 定义，Shell 只容忍缺失字段。
function toTodos(value: unknown): TodoItem[] {
  return asArray(value)
    .map((item) => ({
      content: String(item.content ?? ""),
      status: String(item.status ?? "pending"),
    }))
    .filter((todo) => todo.content.length > 0);
}

function toEntries(value: unknown): HistoryEntry[] {
  return asArray(value)
    .map((item) => ({
      id: String(item.id ?? ""),
      title: String(item.title ?? ""),
      preview: String(item.preview ?? ""),
      messageCount: Number(item.messageCount ?? 0),
      updatedAt: Number(item.updatedAt ?? 0),
    }))
    .filter((entry) => entry.id.length > 0);
}

// session/search returns sessionId/snippet instead of id/preview.
// session/search 返回 sessionId 与 snippet，而不是 id 与 preview。
function toSearchEntries(value: unknown): HistoryEntry[] {
  return asArray(value)
    .map((item) => ({
      id: String(item.sessionId ?? ""),
      title: String(item.title ?? ""),
      preview: "",
      messageCount: 0,
      updatedAt: Number(item.updatedAt ?? 0),
      snippet: String(item.snippet ?? ""),
    }))
    .filter((entry) => entry.id.length > 0);
}

function toMemories(value: unknown): PendingMemory[] {
  return asArray(value)
    .map((item) => ({
      id: String(item.id ?? ""),
      title: String(item.title ?? ""),
      content: String(item.content ?? ""),
      category: String(item.category ?? ""),
      source: String(item.source ?? ""),
    }))
    .filter((item) => item.id.length > 0);
}

// team/list carries members as a list; the selector only needs the count.
// team/list 的 members 是列表，下拉只需要人数。
function toTeams(value: unknown): TeamEntry[] {
  return asArray(value)
    .map((item) => ({
      name: String(item.name ?? ""),
      description: String(item.description ?? ""),
      members: Array.isArray(item.members) ? item.members.length : 0,
      active: item.active === true,
    }))
    .filter((team) => team.name.length > 0);
}

// team/artifacts returns the blackboard in post order; the drawer expands one.
// team/artifacts 按投稿顺序返回黑板，抽屉里展开单条。
function toArtifacts(value: unknown): ArtifactEntry[] {
  return asArray(value).map((item) => ({
    kind: String(item.kind ?? "note"),
    title: String(item.title ?? ""),
    authorRole: String(item.authorRole ?? ""),
    body: String(item.body ?? ""),
  }));
}

// Build the multi-line body of the pre-review card: plan, files, first diff.
// The Core sends these three fields; any of them may be empty.
// 预审卡正文：计划、涉及文件与首个 diff，任一可为空。
function preapproveDetail(params: Record<string, unknown>): string {
  const parts: string[] = [];
  const plan = String(params.plan ?? "").trim();
  const diff = String(params.firstDiff ?? "").trim();
  const files = Array.isArray(params.files)
    ? params.files.map((item) => String(item ?? "").trim()).filter((item) => item.length > 0)
    : [];
  if (plan) {
    parts.push("实现计划：\n" + plan);
  }
  if (files.length) {
    parts.push("涉及文件：\n" + files.map((path) => "- " + path).join("\n"));
  }
  if (diff) {
    parts.push("首个改动：\n" + diff);
  }
  return parts.length ? parts.join("\n\n") : "（未提供计划与 diff）";
}

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
