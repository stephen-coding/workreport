// Webview chat panel (same protocol/UX as the IntelliJ panel).
// Webview 聊天面板（与 IntelliJ 面板同一协议与交互）。

import * as vscode from "vscode";

type MessageHandler = (text: string) => void;
type StopHandler = () => void;
type ConfirmHandler = (id: string, approved: boolean) => void;

// One row of the Core-owned checklist (chat/todos).
// Core 维护的任务清单的一行（chat/todos）。
export interface TodoItem {
  content: string;
  status: string;
}

export class ChatPanel implements vscode.WebviewViewProvider {
  static readonly viewId = "agentCoder.chatView";

  private view?: vscode.WebviewView;
  private readonly pending: Array<Record<string, unknown>> = [];
  private onSend?: MessageHandler;
  private onStop?: StopHandler;
  private onConfirm?: ConfirmHandler;

  constructor(private readonly extensionUri: vscode.Uri) {}

  setHandlers(handlers: {
    onSend?: MessageHandler;
    onStop?: StopHandler;
    onConfirm?: ConfirmHandler;
  }): void {
    this.onSend = handlers.onSend;
    this.onStop = handlers.onStop;
    this.onConfirm = handlers.onConfirm;
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
         color: var(--vscode-foreground); background: var(--vscode-sideBar-background); }
  #status { color: var(--vscode-descriptionForeground); margin-bottom: 6px; font-size: 12px; }
  #todos { border-bottom: 1px solid var(--vscode-panel-border, #444); margin-bottom: 6px;
           padding-bottom: 4px; max-height: 25vh; overflow-y: auto; }
  .todo-head { color: var(--vscode-descriptionForeground); cursor: pointer; font-size: 12px; }
  .todo-row { padding-left: 10px; }
  .todo-row.active { font-weight: bold; }
  #log { white-space: pre-wrap; word-break: break-word; overflow-y: auto; height: calc(100vh - 130px); }
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

  window.addEventListener("message", (event) => {
    const message = event.data;
    switch (message.type) {
      case "append":
        if (message.role === "user") {
          block("user", "你：" + message.text);
          current = block("assistant", "助手：");
        } else {
          block("system", message.text);
        }
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
