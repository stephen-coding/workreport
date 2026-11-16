// Webview chat panel (same protocol/UX as the IntelliJ panel).
// Webview 聊天面板（与 IntelliJ 面板同一协议与交互）。

import * as vscode from "vscode";

type MessageHandler = (text: string) => void;
type StopHandler = () => void;
type ConfirmHandler = (id: string, approved: boolean) => void;
type HistoryHandler = () => void;
type HistoryOpenHandler = (id: string) => void;
type HistoryDeleteHandler = (id: string) => void;

// One row of the Core-owned checklist (chat/todos).
// Core 维护的任务清单的一行（chat/todos）。
export interface TodoItem {
  content: string;
  status: string;
}

// One stored conversation as returned by ``session/list``.
// 历史列表的一条（来自 session/list）。
export interface HistoryEntry {
  id: string;
  title: string;
  preview: string;
  messageCount: number;
  updatedAt: number;
}

export class ChatPanel implements vscode.WebviewViewProvider {
  static readonly viewId = "agentCoder.chatView";

  private view?: vscode.WebviewView;
  private readonly pending: Array<Record<string, unknown>> = [];
  private onSend?: MessageHandler;
  private onStop?: StopHandler;
  private onConfirm?: ConfirmHandler;
  private onHistoryList?: HistoryHandler;
  private onHistoryOpen?: HistoryOpenHandler;
  private onHistoryDelete?: HistoryDeleteHandler;
  private onNewSession?: HistoryHandler;

  constructor(private readonly extensionUri: vscode.Uri) {}

  setHandlers(handlers: {
    onSend?: MessageHandler;
    onStop?: StopHandler;
    onConfirm?: ConfirmHandler;
    onHistoryList?: HistoryHandler;
    onHistoryOpen?: HistoryOpenHandler;
    onHistoryDelete?: HistoryDeleteHandler;
    onNewSession?: HistoryHandler;
  }): void {
    this.onSend = handlers.onSend;
    this.onStop = handlers.onStop;
    this.onConfirm = handlers.onConfirm;
    this.onHistoryList = handlers.onHistoryList;
    this.onHistoryOpen = handlers.onHistoryOpen;
    this.onHistoryDelete = handlers.onHistoryDelete;
    this.onNewSession = handlers.onNewSession;
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
    view.webview.options = {
      enableScripts: true,
      localResourceRoots: [this.extensionUri],
    };
    view.webview.html = this.render();
    for (const message of this.pending.splice(0)) {
      void view.webview.postMessage(message);
    }
    view.webview.onDidReceiveMessage((message: Record<string, unknown>) => {
      const type = String(message.type ?? "");
      if (type === "send") {
        this.onSend?.(String(message.text ?? ""));
      } else if (type === "stop") {
        this.onStop?.();
      } else if (type === "confirm") {
        this.onConfirm?.(String(message.id ?? ""), Boolean(message.approved));
      } else if (type === "historyList") {
        this.onHistoryList?.();
      } else if (type === "historyOpen") {
        this.onHistoryOpen?.(String(message.id ?? ""));
      } else if (type === "historyDelete") {
        this.onHistoryDelete?.(String(message.id ?? ""));
      } else if (type === "newSession") {
        this.onNewSession?.();
      }
    });
  }

  private post(message: Record<string, unknown>): void {
    if (this.view) {
      void this.view.webview.postMessage(message);
    } else {
      // The view may not be resolved yet; replay once it appears.
      // 视图尚未解析时先缓存，解析后补发。
      this.pending.push(message);
    }
  }

  appendUser(text: string): void {
    this.post({ type: "append", role: "user", text });
  }

  appendToken(delta: string): void {
    this.post({ type: "delta", text: delta });
  }

  finishMessage(): void {
    this.post({ type: "end" });
  }

  appendSystem(text: string): void {
    this.post({ type: "append", role: "system", text });
  }

  setBusy(busy: boolean): void {
    this.post({ type: "busy", value: busy });
  }

  setStatus(text: string): void {
    this.post({ type: "status", text });
  }

  // Replace the whole checklist; an empty list hides the area.
  // 整表覆盖任务清单；为空时隐藏该区域。
  setTodos(todos: TodoItem[]): void {
    this.post({ type: "todos", todos });
  }

  // Clear the transcript and checklist (new or reopened conversation).
  // 清空对话与清单（新建或重开会话）。
  reset(): void {
    this.post({ type: "reset" });
  }

  // Replay one stored turn while reopening a conversation.
  // 重放历史会话的一轮：角色与正文来自 session/get。
  appendHistory(role: string, text: string): void {
    this.post({ type: "append", role, text });
  }

  showHistory(entries: HistoryEntry[]): void {
    this.post({ type: "history", entries });
  }

  showConfirm(id: string, title: string, detail: string): void {
    this.post({ type: "confirm", id, title, detail });
  }

  private render(): string {
    const nonce = Array.from({ length: 16 }, () =>
      "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(Math.random() * 36)]).join("");
    return `<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy"
      content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';" />
<style>
  body { font-family: var(--vscode-font-family); font-size: 13px; margin: 0; padding: 8px;
         display: flex; flex-direction: column; height: 100vh; box-sizing: border-box;
         color: var(--vscode-foreground); background: var(--vscode-sideBar-background); }
  #status { color: var(--vscode-descriptionForeground); margin-bottom: 6px; font-size: 12px; }
  #todos { border-bottom: 1px solid var(--vscode-panel-border, #444); margin-bottom: 6px;
           padding-bottom: 4px; max-height: 25vh; overflow-y: auto; }
  .todo-head { color: var(--vscode-descriptionForeground); cursor: pointer; font-size: 12px; }
  .todo-row { padding-left: 10px; }
  .todo-row.active { font-weight: bold; }
  #log { white-space: pre-wrap; word-break: break-word; overflow-y: auto; flex: 1; min-height: 80px; }
  #bar { display: flex; gap: 6px; margin-bottom: 6px; }
  #history { max-height: 40vh; overflow-y: auto; margin-bottom: 6px;
             border: 1px solid var(--vscode-input-border, #555); }
  .hrow { display: flex; align-items: center; gap: 6px; padding: 4px 6px; cursor: pointer; }
  .hrow:hover { background: var(--vscode-list-hoverBackground); }
  .hmain { flex: 1; min-width: 0; }
  .htitle { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .hmeta { color: var(--vscode-descriptionForeground); font-size: 11px; }
  .hdel { padding: 0 6px; background: transparent; color: var(--vscode-descriptionForeground); }
  .hdel:hover { color: var(--vscode-errorForeground); }
  .user { color: var(--vscode-textLink-foreground); margin-top: 8px; }
  .system { color: var(--vscode-descriptionForeground); font-style: italic; }
  .confirm { border: 1px solid var(--vscode-input-border, #555); padding: 6px; margin: 6px 0; }
  .confirm button { margin-right: 6px; }
  #input { width: 100%; box-sizing: border-box; height: 48px; resize: vertical;
           background: var(--vscode-input-background); color: var(--vscode-input-foreground);
           border: 1px solid var(--vscode-input-border, #555); }
  #row { display: flex; gap: 6px; margin-top: 6px; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground);
           border: none; padding: 4px 10px; cursor: pointer; }
  button:disabled { opacity: .5; cursor: default; }
</style>
</head>
<body>
  <div id="status">正在启动 Agent Core...</div>
  <div id="bar">
    <button id="historyBtn">历史</button>
    <button id="newBtn">新会话</button>
  </div>
  <div id="history" hidden></div>
  <div id="todos" hidden></div>
  <div id="log"></div>
  <textarea id="input" placeholder="描述你的任务，例如：帮我解释当前文件的登录流程"></textarea>
  <div id="row">
    <button id="send">发送</button>
    <button id="stop" disabled>停止</button>
  </div>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const log = document.getElementById("log");
  const status = document.getElementById("status");
  const todoBox = document.getElementById("todos");
  const historyBox = document.getElementById("history");
  const historyBtn = document.getElementById("historyBtn");
  const newBtn = document.getElementById("newBtn");
  const input = document.getElementById("input");
  const send = document.getElementById("send");
  const stop = document.getElementById("stop");
  let current = null;
  let todoItems = [];
  let todosCollapsed = false;

  const TODO_MARKS = {
    pending: "☐",
    in_progress: "◐",
    completed: "☑",
    cancelled: "☒",
  };

  function todoDone(item) {
    return item.status === "completed" || item.status === "cancelled";
  }

  function renderTodos() {
    todoBox.textContent = "";
    if (!todoItems.length) {
      todoBox.hidden = true;
      return;
    }
    todoBox.hidden = false;
    const done = todoItems.filter(todoDone).length;
    const head = document.createElement("div");
    head.className = "todo-head";
    head.textContent = (todosCollapsed ? "▸ " : "▾ ") + "任务清单 " + done + "/" + todoItems.length
      + (todosCollapsed && done === todoItems.length ? "（已完成）" : "");
    head.addEventListener("click", () => {
      todosCollapsed = !todosCollapsed;
      renderTodos();
    });
    todoBox.appendChild(head);
    if (todosCollapsed) return;
    for (const item of todoItems) {
      const row = document.createElement("div");
      row.className = "todo-row" + (item.status === "in_progress" ? " active" : "");
      row.textContent = (TODO_MARKS[item.status] || "☐") + " " + item.content;
      todoBox.appendChild(row);
    }
  }

  function block(role, text) {
    const div = document.createElement("div");
    div.className = role;
    div.textContent = text;
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
    return div;
  }

  function timeText(ms) {
    if (!ms) return "";
    return new Date(ms).toLocaleString();
  }

  // One row per stored conversation: click to reopen, × to delete.
  // 每条历史一行：点击重开，× 删除。
  function renderHistory(entries) {
    historyBox.textContent = "";
    if (!Array.isArray(entries) || !entries.length) {
      const empty = document.createElement("div");
      empty.className = "system";
      empty.textContent = "还没有历史会话";
      historyBox.appendChild(empty);
    } else {
      for (const entry of entries) {
        const row = document.createElement("div");
        row.className = "hrow";
        row.title = entry.preview || entry.title || "";
        const main = document.createElement("div");
        main.className = "hmain";
        const title = document.createElement("div");
        title.className = "htitle";
        title.textContent = entry.title || "未命名会话";
        const meta = document.createElement("div");
        meta.className = "hmeta";
        meta.textContent = timeText(entry.updatedAt) + " · " + (entry.messageCount || 0) + " 条消息";
        main.appendChild(title);
        main.appendChild(meta);
        const remove = document.createElement("button");
        remove.className = "hdel";
        remove.textContent = "×";
        remove.title = "删除这条历史记录";
        remove.addEventListener("click", (event) => {
          event.stopPropagation();
          vscode.postMessage({ type: "historyDelete", id: entry.id });
        });
        row.addEventListener("click", () => vscode.postMessage({ type: "historyOpen", id: entry.id }));
        row.appendChild(main);
        row.appendChild(remove);
        historyBox.appendChild(row);
      }
    }
    historyBox.hidden = false;
  }

  send.addEventListener("click", () => {
    const text = input.value.trim();
    if (!text) return;
    input.value = "";
    vscode.postMessage({ type: "send", text });
  });
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      send.click();
    }
  });
  stop.addEventListener("click", () => vscode.postMessage({ type: "stop" }));
  historyBtn.addEventListener("click", () => {
    if (!historyBox.hidden) {
      historyBox.hidden = true;
      return;
    }
    historyBox.textContent = "加载中…";
    historyBox.hidden = false;
    vscode.postMessage({ type: "historyList" });
  });
  newBtn.addEventListener("click", () => vscode.postMessage({ type: "newSession" }));

  window.addEventListener("message", (event) => {
    const message = event.data;
    switch (message.type) {
      case "append":
        if (message.role === "user") {
          block("user", "你：" + message.text);
          current = block("assistant", "助手：");
        } else if (message.role === "assistant") {
          block("assistant", "助手：" + message.text);
          current = null;
        } else {
          block("system", message.text);
        }
        break;
      case "reset":
        log.textContent = "";
        current = null;
        todoItems = [];
        todosCollapsed = false;
        renderTodos();
        historyBox.hidden = true;
        break;
      case "history":
        renderHistory(message.entries);
        break;
      case "delta":
        if (!current) current = block("assistant", "助手：");
        current.textContent += message.text;
        log.scrollTop = log.scrollHeight;
        break;
      case "end":
        current = null;
        break;
      case "status":
        status.textContent = message.text;
        break;
      case "todos": {
        todoItems = Array.isArray(message.todos) ? message.todos : [];
        // Fold away once everything is finished, same as the IntelliJ strip.
        // 全部完成后自动折叠，与 IntelliJ 侧一致。
        todosCollapsed = todoItems.length > 0 && todoItems.every(todoDone);
        renderTodos();
        break;
      }
      case "busy":
        send.disabled = !!message.value;
        stop.disabled = !message.value;
        break;
      case "confirm": {
        const card = document.createElement("div");
        card.className = "confirm";
        card.textContent = message.detail || "确认操作";
        const yes = document.createElement("button");
        yes.textContent = "允许";
        const no = document.createElement("button");
        no.textContent = "拒绝";
        yes.addEventListener("click", () => {
          vscode.postMessage({ type: "confirm", id: message.id, approved: true });
          card.remove();
        });
        no.addEventListener("click", () => {
          vscode.postMessage({ type: "confirm", id: message.id, approved: false });
          card.remove();
        });
        card.appendChild(yes);
        card.appendChild(no);
        log.appendChild(card);
        log.scrollTop = log.scrollHeight;
        break;
      }
      default:
        break;
    }
  });
</script>
</body>
</html>`;
  }
}
