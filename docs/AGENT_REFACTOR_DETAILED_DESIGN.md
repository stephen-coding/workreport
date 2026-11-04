# Agent 辅助编码插件详细设计说明书（DD v1.0）

**依据**：方案 v3（Python 核心 · 多语言混合架构）
**范围**：RPC 协议、Python Core、Rust 扩展、IntelliJ Shell、VSCode Shell、配置与安全
**兼容目标**：IntelliJ 全家桶（build 233–262）+ VSCode；辅助编码语言：py/rust/js/ts/java/go/c/cpp 等

---

## 1. 总体架构与进程模型

### 1.1 组件职责

| 组件 | 语言 | 职责 | 明确不做 |
|---|---|---|---|
| Agent Core | Python 3.10+ | LLM 调用、Agent 循环、并行调度、Subagent、MCP、记忆、Skill、RPC 服务端 | 不直接读写用户文件系统（除自身数据目录）；不依赖任何 IDE API |
| Rust 扩展 | Rust(PyO3) | token 精确计数、tree-sitter 多语言解析 | 不参与业务编排 |
| IntelliJ Shell | Java 21 | UI、diff 预览、PSI 工具、终端、文件写、设置 | 不做 LLM 调用 |
| VSCode Shell | TypeScript | 同上（VSCode 生态实现） | 不做 LLM 调用 |

### 1.2 进程生命周期

```
IDE 打开项目
  → Shell 探测运行时（Python 路径/发行产物）
  → 拉起 Core 子进程（--project-id --data-dir 参数）
  → Core 发 core/hello → Shell 回 ide/hello（版本握手）
  → 常驻，3s 心跳；断线 → Shell 自动重启 Core → Core 从数据目录恢复会话快照
IDE 关闭项目/退出
  → Shell 发 shutdown 通知 → Core 落盘会话快照 → 优雅退出
```

### 1.3 目录结构

```
workreport-agent/
├── core/                          # Python Agent Core
│   ├── pyproject.toml             # uv 管理，requires-python>=3.10
│   ├── src/agent_core/
│   │   ├── rpc/{server,protocol,connection}.py
│   │   ├── llm/{providers/,streaming,context,router}.py
│   │   ├── agent/{loop,scheduler,subagent,permissions}.py
│   │   ├── tools/{registry,builtin/,remote}.py
│   │   ├── mcp/{manager,bridge}.py
│   │   ├── memory/{sqlite_store,files,vector}.py
│   │   ├── skills/loader.py
│   │   └── native/                # Rust 扩展 crate（maturin）
│   │       ├── tokenizer/
│   │       ├── codesoup/
│   │       └── sse_fast/
│   ├── skills/                    # 内置 skills（daily-report 等）
│   └── tests/
├── intellij/                      # Java 21 Shell（原项目改造）
│   └── src/main/java/com/workreport/agent/{rpc,tools,ui,settings,actions,legacy}
├── vscode/                        # TypeScript Shell
│   └── src/{extension,panel,tools,diff,settings}.ts
├── scripts/                       # CI：maturin wheels、PyInstaller、协议一致性测试
└── docs/                          # 设计文档与 ADR
```

---

## 2. RPC 协议详设

### 2.1 传输与帧格式

- 传输：**stdio**，双向全双工
- 帧：**JSON Lines**——每行一个完整 UTF-8 JSON 对象，`\n` 分隔；禁止消息体含裸换行
- 单帧上限 16MB（大文件内容走分块：文件工具返回 {truncated, totalChars} 而非超限字节）

### 2.2 信封结构

```json
{ "id": "uuid-v4", "method": "chat/token", "params": { }, "ts": 1762228800000 }
```

- **请求-响应型**：Core 发 `tool/invoke` 等，Shell 必须回：`{"id": "同请求id", "ok": true, "result": {...}}` 或 `{"id": "...", "ok": false, "error": {"code": "TOOL_TIMEOUT", "message": "..."}}`
- **通知型**（无响应）：`chat/token`、`chat/toolProgress`、`chat/finish` 等
- **双向可发**：任意一侧都可以是请求方（diff 确认由 Core 发起并阻塞等待）

### 2.3 版本协商与心跳

```
Core → { "method": "core/hello", "params": {"protocolVersion": "1.0", "coreVersion": "0.1.0", "capabilities": ["mcp", "subagent", "memory", "skill", "parallel-tools"]} }
IDE  → { "method": "ide/hello", "params": {"protocolVersion": "1.0", "ide": "idea|webstorm|pycharm|vscode", "tools": ["read_file", ...]} }
心跳：Core → { "method": "agent/ping" }，IDE 回 { "id": ..., "ok": true, "result": {"pong": true} }，3s 间隔，10s 超时判死
```

### 2.4 消息全集

| 方向 | 方法 | 类型 | 关键参数 |
|---|---|---|---|
| IDE→Core | `chat/start` | 请求 | `{sessionId, message, contextSnapshot}` → 返回 `{taskId}` |
| IDE→Core | `chat/cancel` | 通知 | `{taskId?}`（缺省取消全部） |
| IDE→Core | `settings/update` | 通知 | `{settings}` |
| IDE→Core | `tool/result` | 请求 | `{taskId, toolCallId, ok, result?, error?}` |
| IDE→Core | `ui/event` | 通知 | `{taskId, event, payload}`（diff 接受/拒绝、确认结果） |
| Core→IDE | `chat/token` | 通知 | `{taskId, delta}` |
| Core→IDE | `chat/toolStart` | 通知 | `{taskId, toolCallId, toolName, args}` |
| Core→IDE | `chat/toolProgress` | 通知 | `{taskId, toolCallId, progressText}` |
| Core→IDE | `chat/toolEnd` | 通知 | `{taskId, toolCallId, ok, summary}` |
| Core→IDE | `chat/finish` | 通知 | `{taskId, fullText, usage}` |
| Core→IDE | `chat/error` | 通知 | `{taskId, error}` |
| Core→IDE | `tool/invoke` | 请求 | `{taskId, toolCallId, toolName, params}` → 响应 `tool/result` |
| Core→IDE | `edit/previewDiff` | 请求 | `{taskId, toolCallId, filePath, diff, snapshots}` → 响应 `{accepted: bool}`（阻塞等用户） |
| Core→IDE | `agent/confirm` | 请求 | `{taskId, message, options[], defaultOption}` → 响应 `{selected}` |

### 2.5 错误码表

| 码 | 含义 | 处理 |
|---|---|---|
| `PROTOCOL_ERROR` | 帧/版本不符 | 记录并断开 |
| `TOOL_NOT_FOUND` | 工具未注册 | 错误注入 LLM，让它换工具 |
| `TOOL_PERMISSION_DENIED` | 权限拒绝 | 同上 + 通知用户 |
| `TOOL_TIMEOUT` | 单工具超时 | 取消该分支，继续其它并行分支 |
| `TOOL_EXEC_ERROR` | 工具执行异常 | 错误注入 LLM 自纠一次 |
| `LLM_ERROR` | 模型/网络错误 | 用户可见错误，保留会话可重试 |
| `CANCELLED` | 用户取消 | 静默终止 |
| `CONTEXT_OVERFLOW` | 上下文超预算 | 触发自动压缩后重试 |

### 2.6 关键时序：一次含编辑的 chat

```mermaid
sequenceDiagram
    participant U as 用户
    participant S as Shell(IDE)
    participant C as Core(Python)
    participant L as LLM
    U->>S: 输入"把登录函数改成异步"
    S->>C: chat/start(message, contextSnapshot)
    C->>L: chat(messages+工具)
    L-->>C: tool_calls=[read_file(LoginService.java)]
    C->>C: 调度器启动并行分支
    C->>S: chat/toolStart + tool/invoke(read_file)
    S-->>C: tool/result(ok, content)
    C->>L: chat(工具结果注入)
    L-->>C: tool_calls=[edit_file(old, new)]
    C->>S: edit/previewDiff(阻塞等待)
    S->>U: 展示 diff 卡片
    U->>S: 点击接受
    S-->>C: {accepted: true}
    S->>S: 应用编辑
    C->>L: chat(编辑完成)
    L-->>C: 文本结论
    C->>S: chat/token* + chat/finish
```

---

## 3. Python Core 详设

### 3.1 核心数据模型（pydantic v2）

```python
class Role(StrEnum): SYSTEM="system"; USER="user"; ASSISTANT="assistant"; TOOL="tool"

class ToolCall(BaseModel):
    id: str
    name: str
    arguments: dict[str, Any]

class Message(BaseModel):
    role: Role
    content: str | None = None
    tool_calls: list[ToolCall] | None = None   # role=assistant 且含调用时
    tool_call_id: str | None = None            # role=tool 时回填
    name: str | None = None                    # role=tool 时的工具名

class ToolDef(BaseModel):
    name: str
    description: str                           # prompt 质量关键，模板化评审
    parameters: dict[str, Any]                 # JSON Schema
    permission: Literal["auto","preview","confirm"] = "auto"
    timeout_sec: float = 30.0
    max_concurrency: int = 4                   # 该工具自身并发上限
    remote: bool = True                        # True=转发 Shell 执行

class ToolResult(BaseModel):
    tool_call_id: str
    ok: bool
    content: str | None = None                 # 注入 LLM 的文本
    error: str | None = None

class ContextSnapshot(BaseModel):              # Shell 传来的上下文
    project_root: str
    current_file: FileInfo | None = None
    selection: str | None = None
    cursor: Position | None = None
    open_files: list[FileInfo] = []
    diagnostics: list[Diagnostic] = []

class ChatConfig(BaseModel):                   # 每次 chat 生效的运行配置
    provider: Literal["openai_compat","ollama"]
    model: str; temperature: float; max_tokens: int
    max_iterations: int = 15
    tool_concurrency: int = 4
    subagent_concurrency: int = 3
    permissions: PermissionsConfig
```

### 3.2 LLM 层

**Provider 接口**（`llm/providers/base.py`）：

```python
class LLMProvider(Protocol):
    provider_id: str
    async def chat_stream(self, messages: list[Message], tools: list[ToolDef] | None,
                          config: ChatConfig,
                          on_token: Callable[[str], Awaitable[None]],
                          on_tool_call: Callable[[ToolCall], Awaitable[None]]) -> ChatResult: ...
    def count_tokens(self, text: str) -> int: ...
```

- `openai_compat`：`openai` SDK `AsyncOpenAI(base_url=...)`，覆盖 DeepSeek/Qwen/vLLM 等一切兼容端点
- `ollama`：`ollama` SDK `AsyncClient`，base_url 可配
- `ChatResult` = `{content, tool_calls, finish_reason, usage{in,out}}`
- **router**：单 provider 故障转移策略（同 provider 换模型 → 异 provider），首版实现"失败重试 1 次 + 切换 Ollama 兜底"

**SSE 流式与 tool_calls 累积状态机**（`llm/streaming.py`）：

OpenAI 兼容流的 `delta.tool_calls` 是**增量数组**，按 `index` 累积：

```
accumulator: dict[int, Slot]; Slot = {id?, name?, args_buffer: str}
on_chunk(delta):
  for tc in delta.tool_calls:
    slot = accumulator[tc.index]
    slot.id ||= tc.id                      # 仅首 chunk 携带
    if tc.function.name: slot.name = tc.function.name
    if tc.function.arguments: slot.args_buffer += tc.function.arguments
on_stream_end():
  for slot in sorted(accumulator):
    tool_call = ToolCall(slot.id, slot.name, json.loads(slot.args_buffer))
    await on_tool_call(tool_call)
```

Ollama 流的 `message.tool_calls` 为**整对象**（非增量），但参数可能在多 chunk 中重复携带——按 `name+index` 键合并，`arguments` 为 JSON 对象时深合并、为字符串时累积拼接后统一解析。两种格式都做 `[DONE]` 与 `finish_reason` 终止判定；解析失败的单 chunk 记 warning 不中断流。

**上下文预算与压缩**（`llm/context.py`）：
- 预算分配：system prompt 固定 ≤ 2k → 工具定义 ≤ 2k → 记忆/Skill ≤ 2k → 历史 → 上下文快照 → 预留输出 2k
- 计数走 `native.tokenizer`（tiktoken-rs）；历史超限触发压缩：保留最近 3 轮，更早的 assistant 工具轮次折叠为 `[工具摘要] 读 X → 改 Y` 一句话（由一次低成本 LLM 调用生成摘要，失败则截断）

### 3.3 Agent 引擎

**循环状态机**（`agent/loop.py`）：

```python
async def run_chat(session: Session, user_msg: str, config: ChatConfig, emit: Emitter):
    messages = await assemble(session, user_msg)        # 记忆召回+skill指令+快照
    for i in range(config.max_iterations):
        if session.cancel_flag: raise Cancelled()
        resp = await provider.chat_stream(messages, tools=registry.schemas(), ...)
        if resp.tool_calls:
            results = await scheduler.run_parallel(resp.tool_calls, session)
            messages += to_messages(resp, results)       # assistant(tool_calls) + tool 结果
        else:
            return resp                                 # 流式已由 on_token 发出
    raise IterationLimitExceeded()                      # 兜底：让 LLM 输出当前进展小结
```

状态：`IDLE → THINKING → EXECUTING → THINKING … → RESPONDING → IDLE`，`CANCELLED` 任意态可达。

**并行调度器**（`agent/scheduler.py`）：

```
run_parallel(tool_calls, session) -> list[ToolResult]:
  1. 按 ToolDef 声明与调用参数构建 DAG（默认无依赖 → 全并行）
  2. 拓扑分层；同层并发 = min(config.tool_concurrency, 各工具 max_concurrency 余量)
  3. 每个分支 asyncio.create_task + asyncio.timeout(独立超时)
  4. 分支取消：只取消本 task，不传播异常；结果按原顺序返回
  5. 远程工具 → rpc 客户端请求；本地工具（memory_*/skill_load）→ 直接协程
  6. 全程经 emit 向 Shell 发 toolStart/toolProgress/toolEnd
```

**Subagent 池**（`agent/subagent.py`）：

```python
class SubagentTask(BaseModel):
    id: str
    goal: str
    allowed_tools: list[str]
    system_prompt: str
    max_iterations: int = 5

class SubagentPool:
    def __init__(self, max_concurrent: int = 3): ...
    async def dispatch(self, tasks: list[SubagentTask], emit) -> list[SubagentReport]
    # 每个 subagent = 独立 asyncio.Task + 独立 LLM 会话 + 独立迭代上限
    # 池满排队；结果 SubagentReport{task_id, ok, summary(进主上下文前先压缩), detail}
```

内置 subagent 定义（`.agent/subagents/*.md`，与 SKILL.md 同格式）：`code-reviewer`、`bug-hunter`、`test-generator`。

### 3.4 工具系统

**注册与声明**（`tools/registry.py`）：

```python
@tool(name="read_file", permission="auto", timeout_sec=10, max_concurrency=8,
      description="读取文件指定行范围（1 起始，含两端）。大文件请配合 list 行数使用。")
async def read_file(path: str, start_line: int = 1, end_line: int | None = None) -> ToolResult: ...
```

- pydantic 签名 → 自动生成 JSON Schema；执行期校验参数
- **工具清单**：

| 工具 | 类型 | 权限 | 位置 |
|---|---|---|---|
| `read_file` / `list_dir` / `search_code` / `search_symbol` / `git_status` / `git_diff` / `git_log` / `get_diagnostics` | 只读 | auto | Shell |
| `write_file` / `edit_file` / `delete_file` | 编辑 | preview | Shell |
| `run_terminal` / `run_test` | 执行 | confirm | Shell |
| `open_file` / `goto_symbol` | IDE 操作 | auto | Shell |
| `skill_load` / `memory_recall` / `memory_save` / `memory_delete` / `subagent_dispatch` | 内置 | auto/confirm | Core |

- **权限引擎**（`agent/permissions.py`）：`auto` 直通；`preview` 走 `edit/previewDiff` 阻塞确认（会话级"本次自动接受"开关）；`confirm` 走 `agent/confirm`；命令白名单匹配失败即拒。连续 3 次权限拒绝 → 终止循环并汇报。

### 3.5 记忆系统

**SQLite schema**（`memory/sqlite_store.py`，stdlib sqlite3，Core 数据目录）：

```sql
CREATE TABLE memories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL, content TEXT NOT NULL,
  category TEXT NOT NULL,
  keywords TEXT NOT NULL,               -- JSON 数组
  scope TEXT NOT NULL,                  -- project | global
  project_path TEXT,
  created_at INTEGER, updated_at INTEGER
);
CREATE VIRTUAL TABLE memories_fts USING fts5(title, content, keywords,
        content='memories', content_rowid='rowid');
```

**文件侧**（`memory/files.py`）：每条记忆同时落 Markdown——项目 `.agent/memories/*.md`、全局 `~/.agent/memories/*.md`；`.agent/memories/overview.md` 为层级树索引（标题+分类一行一条），Core 启动时解析为 overview 结构。

**检索两级**：
1. `memory_recall(mode="overview")` → 返回 overview 文本（便宜）
2. `mode="search", keywords` → FTS5 全文 + 关键词加权，Top-N 返回；可选 `vector.py`（usearch + 本地 embedding）做语义召回，首版关闭

### 3.6 MCP 集成（`mcp/manager.py`）

```python
class MCPServerConfig(BaseModel):
    name: str
    transport: Literal["stdio","sse","http"]
    command: str | None = None; args: list[str] | None = None
    url: str | None = None
    enabled: bool = True
    auto_approve: bool = False
```

- 官方 `mcp` SDK；启动即 `list_tools` → 经 `bridge.py` 转 `ToolDef` 注册进 registry（权限：`auto_approve ? auto : confirm`）
- 生命周期：启动失败不阻塞主流程；调用超时熔断 30s；进程异常退出自动重启 ≤ 3 次后禁用并通知
- 配置源：项目 `.agent/mcp.json`（版本管理、团队共享）+ IDE 设置页（个人私有），合并时项目级优先

### 3.7 Skill 系统

**SKILL.md 规范**：

```markdown
---
name: daily-report
description: 生成日报/周报/季报/年报；聚合今日 Git 提交并 AI 润色
scenarios: ["生成日报", "今日工作汇报", "写周报"]
tools: [git_log, run_terminal]        # 可选：建议工具白名单
---
# 指令
1. 调用 git_log 采集用户指定日期范围的提交
2. 按板块组织：今日完成/明日计划/遇到的问题
3. 保留技术术语，不捏造事实
```

- `skills/loader.py`：扫描 `~/.agent/skills/` 与项目 `.agent/skills/`；`skill_load(name)` 把正文注入 messages；名称冲突项目级优先
- **日报 Skill**：旧 Java 端 4 套 prompt（daily/weekly/quarterly/yearly）原文迁移为 4 个 skill 文件，参数化报告类型；Git 采集由 Shell `git_log` 工具承担（对应旧 `GitLogCollectorService` 改造）

---

## 4. Rust 扩展详设

### 4.1 边界原则

每个扩展必须有**纯 Python 降级路径**（构建失败/平台无 wheel 时可用）：
- `tokenizer` 降级：字符数 ÷ 4 启发式估算
- `codesoup` 降级：返回空解析，上下文裁剪回退到全文截断，符号查询回退 Shell 端 PSI

### 4.2 `native.tokenizer`（tiktoken-rs + PyO3）

```python
count_tokens(text: str, model: str = "gpt-4o") -> int
truncate_to_budget(text: str, budget_tokens: int, model: str) -> str   # 回退到句子边界
```

实现要点：`PyO3` + `py.allow_threads`（释放 GIL）；模型→编码映射表内置（gpt-4o/o200k、cl100k 兼容 Ollama 模型默认用 o200k）。

### 4.3 `native.codesoup`（tree-sitter）

```python
class FunctionNode: name: str; signature: str; start_line: int; end_line: int; language: str
class ParsedFile:
    language: str
    functions: list[FunctionNode]
    query(s_expr: str) -> list[Node]        # tree-sitter query，返回行号区间+捕获文本

parse_text(content: str, language: str) -> ParsedFile
detect_language(path: str, content: str) -> str | None
```

- grammar 内置：python、rust、javascript、typescript、java、go、c、cpp、csharp（每个 grammar 独立 feature，按需加载解析器，惰性初始化）
- 用途：① 上下文函数级切片（只送相关函数入 LLM）② `search_code` 的结构化增强 ③ edit_file 的 diff 前后结构校验（改后 AST 可解析才允许提交）

### 4.4 构建与集成

- maturin 产 `native-{pyver}-{os}-{arch}.whl`，CI 矩阵 3 OS × 2 arch
- Core 启动时 import 失败 → 降级路径 + 通知 Shell 展示"性能扩展未加载"提示

#### 4.4.1 实际落地方式

- 构建入口：`scripts/build_native.ps1`（自动探测 cargo / MinGW，编译后把产物复制为 `core/src/agent_core_native.pyd|so`）
- 工具链：Windows 需 C++ 链接器，推荐 MinGW-w64（`winget install BrechtSanders.WinLibs.POSIX.UCRT`）配 `stable-x86_64-pc-windows-gnu`；有 VC++ 时也可用默认 msvc
- **abi3 陷阱**：PyO3 的 `abi3` 在 windows-gnu 上只链接 `python3.dll`，而该转发层不导出 `PyCMethod_New`，链接会失败。因此 `abi3` 改为可选 feature：Windows 默认关闭（按当前解释器版本链接），Linux/macOS 默认开启
- 覆盖语义：Rust 与 Python 实现必须同形——函数名带类型前缀（`Greeter.hello`）、类型声明本身也进大纲、`tsx` 归入 `typescript`、未知语言回退 Python 表；`truncate_to_budget` 先为截断标记预留 token 预算，保证结果不超预算
- 验收：`scripts/verify_all.ps1 -Native` 先编译再跑全部用例，并置 `AGENT_CORE_EXPECT_RUST=1` 强制断言后端为 rust；`agent-core --native-info` 可打印打包产物实际加载的后端
- 打包：`packaging/agent-core.spec` 检测到 `.pyd/.so` 时自动作为 binary 打入单文件 exe

---

## 5. IntelliJ Shell 详设

### 5.1 类结构

```
com.workreport.agent/
├── rpc/
│   ├── CoreProcess.java          # ProcessBuilder 拉起/守护/重启
│   ├── PythonLocator.java        # 探测：设置指定 → 发行产物 → PATH → 报错引导
│   ├── JsonRpcConnection.java    # stdin/stdout 行读写（读线程 → EDT 分发）
│   └── protocol/                 # DTO：ChatStartReq/ToolInvokeReq/DiffPreviewReq/…
├── tools/
│   ├── ToolDispatcher.java       # tool/invoke → 各工具实现（switch 路由 + 异步执行）
│   ├── FileTools.java            # VirtualFile 读写 + 内存快照（撤销用）
│   ├── SearchTools.java          # PSI 符号 + ProjectFileIndex 全文搜索
│   ├── GitTools.java             # 改造自 GitLogCollectorService（新增 status/diff）
│   ├── TerminalTools.java        # ToolWindowManager terminal API + 白名单校验
│   └── DiagnosticsTools.java     # Problems 收集（CodeInsightProblemsUtil）
├── ui/
│   ├── AgentChatPanel.java       # 改造自 ReportToolWindowPanel（消息区重写）
│   ├── MarkdownRenderer.java     # 轻量 MD→StyledText（标题/粗体/列表/代码块高亮）
│   ├── ToolCallCard.java         # 并行执行卡（taskId 分组，进度+折叠参数/结果）
│   └── DiffConfirmCard.java      # DiffContent 展示 + 接受/拒绝
├── settings/
│   ├── AgentSettingsState.java   # 扩展自 ReportSettingsState（见 §7）
│   └── AgentSettingsConfigurable.java
├── actions/
│   ├── ExplainAction.java / FixAction.java / TestGenAction.java / ReviewAction.java
│   └── FixDiagnosticIntention.java   # Alt+Enter 意图
└── legacy/
    └── ReportSkillTrigger.java   # 旧 UI 入口改为：向 Core 发"生成日报"指令
```

### 5.2 现有代码改造对照表

| 现有类 | 动作 |
|---|---|
| `ReportPolishService` | 删除全部 LLM/SSE 逻辑；保留 `notifyError` 通知工具 |
| `GitLogCollectorService` | 改为 `GitTools` 的实现体，接口适配 tool/invoke 参数 |
| `ReportSettingsState` | 更名扩展 `AgentSettingsState`，新增字段（§7） |
| `ReportToolWindowPanel` | 重构为 `AgentChatPanel`，复用输入区/发送按钮骨架 |
| `ReportSettingsConfigurable` | 重写为 Agent 设置页（模型/并发/权限/Python/MCP） |
| `PrivacyNoticeUtil` | 保留，文案改"代码外传确认" |
| `ReportToolWindowFactory` | 指向新面板类，toolWindow id 改 `AgentCoder` |
| plugin.xml | 新增 `<actions>` 组、`IntentionAction` 注册；依赖不变 |

### 5.3 UI 行为详设

- 消息区：用户/助手气泡 + Markdown 渲染；助手消息内嵌多张 `ToolCallCard`（并行时同时出现、各自滚动进度）
- `edit/previewDiff` 到达：插入 `DiffConfirmCard`（左右对比 + 接受/拒绝/接受全部），期间输入框可继续（消息级阻塞而非全局阻塞）
- 取消：面板"停止"按钮 → `chat/cancel`
- 会话持久化由 Core 负责（重启 IDE 恢复历史，Shell 只渲染）

---

## 6. VSCode Shell 详设

```
vscode/src/
├── extension.ts     # 激活 → 探测 Python/产物 → spawn Core → 建立 RPC
├── rpc.ts           # JSON Lines 收发（Node stream 逐行解析）
├── panel.ts         # Webview 聊天面板（同协议，渲染与 IntelliJ 对等）
├── tools.ts         # workspace API 文件工具、内置 Terminal、LSP 诊断
├── diff.ts          # 原生 diff 编辑器组件承载 previewDiff
└── settings.ts      # settings.json 贡献点
```

- 与 IntelliJ Shell **协议对等**（同一验收清单）；Core 进程管理逻辑一致（检测 python3/python → 内置产物）
- 编辑应用：`WorkspaceEdit`；文件快照走 `TextDocument.save` 前内容

---

## 7. 配置模型详设

### 7.1 Settings（IDE 设置页 ↔ `settings/update`）

```json
{
  "provider": "ollama",
  "baseUrl": "http://localhost:11434",
  "model": "qwen2.5:7b",
  "apiKeyInPasswordSafe": true,
  "temperature": 0.2,
  "maxTokens": 8192,
  "maxIterations": 15,
  "toolConcurrency": 4,
  "subagentConcurrency": 3,
  "pythonPath": "",
  "permissions": {
    "autoAcceptEdits": false,
    "commandWhitelist": ["npm test", "cargo test", "pytest", "mvn test"],
    "commandDenylist": ["rm -rf", "git push -f", "format"]
  },
  "mcpServers": [],
  "privacyAccepted": false
}
```

- Java 端字段持久化沿用 `@State` + `Storage`；API Key 走 PasswordSafe（沿用 `retrieveApiKey`）
- `privacyAccepted` 在 endpoint 变更时重置（沿用 `resetPrivacyIfEndpointChanged`）

### 7.2 `.agent/mcp.json` 示例

```json
{ "mcpServers": { "github": { "transport": "http", "url": "https://api.githubcopilot.com/mcp/" } } }
```

---

## 8. 安全设计详设

| 级别 | 工具 | 流程 |
|---|---|---|
| auto | 只读/IDE 操作 | 直接执行 |
| preview | write/edit/delete | Core 生成 diff+快照 → `edit/previewDiff` 阻塞 → 接受才落盘；路径必须落在 `project_root` 内（`Path.resolve` 后前缀校验） |
| confirm | run_terminal/run_test/git push/memory_delete | `agent/confirm` 弹窗，命令需过白/黑名单；会话内"总是允许"记忆于 Core 会话级 |

- Core 进程自身：数据目录 `~/.agent/data` 与项目 `.agent/` 之外无写权限（代码层拒绝）
- API Key 永不进入 messages（settings 下发时仅传引用标记）
- 敏感信息过滤：LLM 错误响应中的 `Authorization` 等头不进日志

---

## 9. 错误处理与可靠性

- 所有错误按 §2.5 错误码分类；工具错误首轮注入 LLM 自纠，二次失败终止并汇报
- Core 崩溃：Shell 监听进程退出 → 自动重启 → 会话快照恢复（最近 50 条消息 + 进行中任务标记为失败）
- 网络重试：LLM 调用指数退避 2 次，之后切 `router` 故障转移
- 日志：Core 写数据目录 `logs/core.log`（轮转）；Shell 用 IDEA 日志系统；协议层 debug 开关脱敏记录

---

## 10. 测试详设

| 层 | 工具 | 内容 |
|---|---|---|
| Python 单测 | pytest + pytest-asyncio | 循环状态机（Mock LLM 预设 tool_calls 序列：读→改→完成/上限/取消）、调度器（依赖图、超时、分支取消）、权限引擎、SSE 分片拼装（OpenAI/Ollama 样本集，含 tool_calls 跨 chunk） |
| Rust 单测 | cargo test | tokenizer 计数对拍 tiktoken 参考值；codesoup 解析/query 对各语言 fixture |
| 协议一致性 | scripts/protocol-conformance | 双向消息序列 golden 对比；TS/Java/Python 三方同测例 |
| Shell 测试 | JUnit / vitest | 工具实现（临时 fixture 项目）、快照撤销、Python 探测 |
| 端到端 | 手动脚本 + fixture | "找 bug → 改 → 验证"全流程；日报 skill 与旧功能等价性验收 |

---

## 11. 实施任务分解（对应里程碑）

- **M1 Core 地基**：Python 骨架（pyproject+uv）→ RPC server/client 最小实现 → OpenAI/Ollama Provider + SSE 状态机 → 循环 v0（无工具）→ IntelliJ Shell 聊天面板跑通 → 旧 `ReportPolishService` 退役
- **M2 工具与并行**：registry + ToolDef → Shell 端 read/search/git/diagnostics → `tool/invoke` 链路 → 调度器并行 → ToolCallCard 多卡 UI
- **M3 Rust 扩展**：tokenizer crate → codesoup crate（先 python/java/rust/js/ts 五 grammar）→ 上下文预算接入
- **M4 编辑与记忆**：write/edit/delete + diff 确认卡 + 快照撤销 → 记忆系统（SQLite+FTS+md 文件）→ `memory_*` 工具
- **M5 Skill 迁移**：SKILL.md 加载器 → 日报 skill 4 件套 → 旧 UI 入口切换
- **M6 MCP**：manager + bridge + mcp.json → 设置页编辑 → 熔断重启
- **M7 Subagent 并行**：subagent_dispatch + 池 → 内置 3 定义 → 并行验收
- **M8 VSCode + 分发**：VSCode Shell → PyInstaller CI → 全家桶兼容矩阵冒烟

---

## 12. 实施结果与验收（DD v1.0 全部落地）

八个里程碑均已完成，代码与验证脚本均可重复执行。

| 里程碑 | 主要产物 | 验收方式 |
|---|---|---|
| M1 Core 地基 | `core/src/agent_core/{rpc,llm,agent}`、`agent/rpc/{CoreProcess,JsonRpcConnection,PythonLocator}.java`、`agent/ui/AgentChatPanel.java`、`plugin.xml` 接线 | `core/tests` 单测 + `vscode`/Java 冒烟最小对话 |
| M2 工具与并行 | `tools/registry.py`、`agent/scheduler.py`、Shell 端 9 个工具实现 | `scripts/smoke_tools.py`（真实 Core + mock LLM） |
| M3 Rust 扩展 | `core/native/`（pyo3 + tiktoken-rs + tree-sitter）、`agent_core/native/` 门面 + 纯 Python 降级 | `scripts/build_native.ps1` 实际编译 + `test_native.py`（Rust/Python 双后端对拍）；无 cargo 时自动降级 |
| M4 编辑与记忆 | `write/edit/delete` + diff 确认 + 快照回退、`memory/`（SQLite FTS5 + Markdown 镜像） | `scripts/smoke_memory.py` + 单测 |
| M5 Skill 迁移 | `skills/builtin/{daily,weekly,monthly,quarterly,annual}-report/SKILL.md`、旧入口 action | `smoke-rpc.js` 断言 `daily-report` 注入 |
| M6 MCP | `mcp/{client,config,manager}.py`、`agent/confirm` ↔ `agent/confirmResult`、设置页 MCP JSON、`tests/test_mcp.py` | `scripts/smoke_mcp.py`（auto + ask 双服务） |
| M7 Subagent | `agent/subagent.py`、3 个内置 `SUBAGENT.md`、并行池 + 工具白名单（禁递归） | `scripts/smoke_subagent.py` + `tests/test_subagent.py` |
| M8 VSCode + 分发 | `vscode/`（TS Shell：rpc/locator/session/tools/diff/panel）、`packaging/agent-core.spec`、`scripts/{build_core,verify_all}.ps1`、`.github/workflows/ci.yml` | `npm run compile` + `npm run smoke`；`agent-core.exe` 端到端冒烟 |

### 12.1 一键验收

```powershell
powershell -File scripts/verify_all.ps1
```

当前结果：

- Core 单测 **78 个用例全绿**（`python -m unittest discover -s tests`）
- 协议冒烟 4 个全部 PASS：`smoke_tools` / `smoke_memory` / `smoke_mcp` / `smoke_subagent`
- VS Code：`tsc` 编译通过，`smoke-rpc.js` 与真实 Core 跑通并校验 skill 注入
- IntelliJ：`mvn -q -DskipTests compile` 通过
- 打包：PyInstaller 产物 `core/dist/agent-core(.exe)` 可启动，`AGENT_CORE_EXE` 模式下冒烟同样通过（证明 SKILL.md / SUBAGENT.md 已随包分发）

### 12.2 与详设的差异说明

- 单测框架使用标准库 `unittest`（而非 pytest），避免引入额外依赖，语义覆盖范围与 §10 一致。
- Rust 加速层保留完整源码与门面接口，构建机无 cargo 时自动走纯 Python 路径，行为对等；有 cargo 时 `scripts/build_native.ps1` 已实测可产出并被加载（详见 §4.4.1）。
- 跨 Shell 的协议一致性以「同一 Core + 两套 Shell 冒烟脚本」验证，未单独维护 golden 消息文件。
