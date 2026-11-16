// Webview chat panel (same protocol/UX as the IntelliJ panel).
// Webview 聊天面板（与 IntelliJ 面板同一协议与交互）。

import * as vscode from "vscode";

type MessageHandler = (text: string) => void;
type StopHandler = () => void;
type ConfirmHandler = (id: string, approved: boolean) => void;
type HistoryHandler = () => void;
type HistoryOpenHandler = (id: string) => void;
type HistoryDeleteHandler = (id: string) => void;
type HistorySearchHandler = (query: string) => void;
type MemoryResolveHandler = (id: string, approve: boolean, content?: string) => void;

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
  // Search hits carry a <mark>-wrapped snippet instead of a message count.
  // 搜索命中带一段 <mark> 包住的正文片段，而不是消息条数。
  snippet?: string;
}

// One review candidate from .agent/memories/_pending (memory/pending).
// 待确认的记忆候选（memory/pending）。
export interface PendingMemory {
  id: string;
  title: string;
  content: string;
  category: string;
  source: string;
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
  private onHistorySearch?: HistorySearchHandler;
  private onMemoryList?: HistoryHandler;
  private onMemoryResolve?: MemoryResolveHandler;
  private onNewSession?: HistoryHandler;

  constructor(private readonly extensionUri: vscode.Uri) {}

  setHandlers(handlers: {
    onSend?: MessageHandler;
    onStop?: StopHandler;
    onConfirm?: ConfirmHandler;
    onHistoryList?: HistoryHandler;
    onHistoryOpen?: HistoryOpenHandler;
    onHistoryDelete?: HistoryDeleteHandler;
    onHistorySearch?: HistorySearchHandler;
    onMemoryList?: HistoryHandler;
    onMemoryResolve?: MemoryResolveHandler;
    onNewSession?: HistoryHandler;
  }): void {
    this.onSend = handlers.onSend;
    this.onStop = handlers.onStop;
    this.onConfirm = handlers.onConfirm;
    this.onHistoryList = handlers.onHistoryList;
    this.onHistoryOpen = handlers.onHistoryOpen;
    this.onHistoryDelete = handlers.onHistoryDelete;
    this.onHistorySearch = handlers.onHistorySearch;
    this.onMemoryList = handlers.onMemoryList;
    this.onMemoryResolve = handlers.onMemoryResolve;
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
      } else if (type === "historySearch") {
        this.onHistorySearch?.(String(message.query ?? ""));
      } else if (type === "memoryList") {
        this.onMemoryList?.();
      } else if (type === "memoryResolve") {
        const content = typeof message.content === "string" ? message.content : undefined;
        this.onMemoryResolve?.(String(message.id ?? ""), Boolean(message.approve), content);
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

  // Search results replace the list rows; the query is echoed for the header.
  // 搜索结果替换列表行，query 回显给标题。
  showHistorySearch(entries: HistoryEntry[], query: string): void {
    this.post({ type: "historySearch", entries, query });
  }

  // Memory review candidates (memory/pending).
  // 记忆候选（memory/pending）。
  showMemories(items: PendingMemory[]): void {
    this.post({ type: "memory", items });
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
  #historySearch { width: 100%; box-sizing: border-box; margin: 4px 0;
                   background: var(--vscode-input-background); color: var(--vscode-input-foreground);
                   border: 1px solid var(--vscode-input-border, #555); }
  #memory { max-height: 45vh; overflow-y: auto; margin-bottom: 6px;
            border: 1px solid var(--vscode-input-border, #555); }
  .mrow { padding: 5px 6px; border-bottom: 1px solid var(--vscode-panel-border, #444); }
  .mtitle { font-weight: bold; }
  .mtext { word-break: break-word; }
  .mmeta { color: var(--vscode-descriptionForeground); font-size: 11px; }
  .mact { display: flex; gap: 4px; margin-top: 3px; }
  .mact button { padding: 2px 8px; }
  mark { background: transparent; font-weight: bold; color: inherit; }
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
    <button id="memoryBtn">记忆</button>
    <button id="newBtn">新会话</button>
  </div>
  <div id="history" hidden>
    <input id="historySearch" placeholder="搜索历史会话：标题与正文" />
    <div id="historyList"></div>
  </div>
  <div id="memory" hidden></div>
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
  const historyList = document.getElementById("historyList");
  const historySearch = document.getElementById("historySearch");
  const historyBtn = document.getElementById("historyBtn");
  const memoryBox = document.getElementById("memory");
  const memoryBtn = document.getElementById("memoryBtn");
  const newBtn = document.getElementById("newBtn");
  const input = document.getElementById("input");
  const send = document.getElementById("send");
  const stop = document.getElementById("stop");
  let current = null;
  let todoItems = [];
  let todosCollapsed = false;
  let historyEntries = [];
  let historySearchTimer = null;
  let memoryItems = [];

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

  // session/search wraps hits in <mark>; build real nodes instead of innerHTML.
  // session/search 用 <mark> 包住命中片段，这里用 DOM 节点拼，不拼 innerHTML。
  function snippetNode(text) {
    const div = document.createElement("div");
    div.className = "hmeta";
    let mark = null;
    for (const part of String(text || "").split(/(<mark>|<\/mark>)/)) {
      if (part === "<mark>") {
        mark = document.createElement("mark");
        div.appendChild(mark);
      } else if (part === "</mark>") {
        mark = null;
      } else if (part) {
        (mark || div).appendChild(document.createTextNode(part));
      }
    }
    return div;
  }

  // One row per stored conversation: click to reopen, × to delete.
  // 每条历史一行：点击重开，× 删除。
  function renderHistory(entries) {
    historyEntries = Array.isArray(entries) ? entries : [];
    historyList.textContent = "";
    if (!historyEntries.length) {
      const empty = document.createElement("div");
      empty.className = "system";
      empty.textContent = historySearch.value.trim() ? "没有匹配的会话，换个关键词试试。" : "还没有历史会话";
      historyList.appendChild(empty);
    } else {
      for (const entry of historyEntries) {
        const row = document.createElement("div");
        row.className = "hrow";
        row.title = entry.preview || entry.title || "";
        const main = document.createElement("div");
        main.className = "hmain";
        const title = document.createElement("div");
        title.className = "htitle";
        title.textContent = entry.title || "未命名会话";
        main.appendChild(title);
        if (entry.snippet) {
          main.appendChild(snippetNode(entry.snippet));
        } else {
          const meta = document.createElement("div");
          meta.className = "hmeta";
          meta.textContent = timeText(entry.updatedAt) + " · " + (entry.messageCount || 0) + " 条消息";
          main.appendChild(meta);
        }
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
        historyList.appendChild(row);
      }
    }
    historyBox.hidden = false;
  }

  // One memory candidate: title, sentence, origin, then 确认 / 编辑 / 忽略.
  // 一条记忆候选：标题、原文、来源与三个操作。
  function renderMemories(items) {
    memoryItems = Array.isArray(items) ? items : [];
    memoryBox.textContent = "";
    if (!memoryItems.length) {
      const empty = document.createElement("div");
      empty.className = "system";
      empty.textContent = "没有待确认的记忆候选";
      memoryBox.appendChild(empty);
      memoryBox.hidden = false;
      return;
    }
    for (const item of memoryItems) {
      const row = document.createElement("div");
      row.className = "mrow";
      const title = document.createElement("div");
      title.className = "mtitle";
      title.textContent = item.title || item.content || "";
      const text = document.createElement("div");
      text.className = "mtext";
      text.textContent = item.content || "";
      const meta = document.createElement("div");
      meta.className = "mmeta";
      meta.textContent = (item.source === "correction" ? "纠正" : "偏好") + " · " + (item.category || "");
      const actions = document.createElement("div");
      actions.className = "mact";
      actions.appendChild(memoryButton("确认", item, false));
      actions.appendChild(memoryButton("编辑", item, true));
      actions.appendChild(memoryButton("忽略", item, null));
      row.append(title, text, meta, actions);
      memoryBox.appendChild(row);
    }
    memoryBox.hidden = false;
  }

  // approve === null means discard; true opens the inline editor.
  // approve === null 表示忽略；true 打开行内编辑。
  function memoryButton(label, item, approve) {
    const button = document.createElement("button");
    button.textContent = label;
    button.addEventListener("click", () => {
      if (approve === true) {
        startMemoryEdit(item, button.closest(".mrow"));
      } else {
        vscode.postMessage({ type: "memoryResolve", id: item.id, approve: approve === false });
      }
    });
    return button;
  }

  function startMemoryEdit(item, row) {
    row.textContent = "";
    const area = document.createElement("textarea");
    area.value = item.content || "";
    area.rows = 2;
    area.style.width = "100%";
    const actions = document.createElement("div");
    actions.className = "mact";
    const save = document.createElement("button");
    save.textContent = "保存";
    save.addEventListener("click", () => {
      const text = area.value.trim();
      if (!text) return;
      vscode.postMessage({ type: "memoryResolve", id: item.id, approve: true, content: text });
    });
    const cancel = document.createElement("button");
    cancel.textContent = "取消";
    cancel.addEventListener("click", () => renderMemories(memoryItems));
    actions.append(save, cancel);
    row.append(area, actions);
    area.focus();
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
    historyBox.hidden = false;
    const query = historySearch.value.trim();
    historyList.textContent = "加载中…";
    vscode.postMessage(query ? { type: "historySearch", query } : { type: "historyList" });
  });
  // Typing re-issues the query 300 ms after the last keystroke (same as IntelliJ).
  // 输入停 300 ms 后再发查询，与 IntelliJ 侧一致。
  historySearch.addEventListener("input", () => {
    if (historySearchTimer) clearTimeout(historySearchTimer);
    historySearchTimer = setTimeout(() => {
      const query = historySearch.value.trim();
      if (!query) {
        renderHistory(historyEntries);
        return;
      }
      vscode.postMessage({ type: "historySearch", query });
    }, 300);
  });
  memoryBtn.addEventListener("click", () => {
    if (!memoryBox.hidden) {
      memoryBox.hidden = true;
      return;
    }
    memoryBox.hidden = false;
    memoryBox.textContent = "加载中…";
    vscode.postMessage({ type: "memoryList" });
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
        memoryBox.hidden = true;
        break;
      case "history":
        renderHistory(message.entries);
        break;
      case "historySearch":
        renderHistory(message.entries);
        break;
      case "memory":
        renderMemories(message.items);
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
