# 第 03 卷 · Core 入口与 RPC 服务端

> 本卷逐函数读完 Core 进程的「骨架层」：`__main__.py`（104 行）、`agent_core/__init__.py`（47 行）、`protocol.py`（173 行）、`rpc/server.py`（890 行）。
>
> 这一层不产生业务价值，但决定了别的一切能否跑起来：**进程怎么起、帧怎么进出、请求怎么并发、任务怎么被取消、异常怎么变成错误码**。

---

## 0. 本卷地图

```
python -m agent_core --project-id p --data-dir d
│
├─ __main__.main()                     参数解析 → native-info 早退 → 配流/配日志
│   └─ asyncio.run(RpcServer(...).run())
│
├─ RpcServer.__init__                  26 个字段 + 全部工具注册（都在构造期完成）
│
├─ RpcServer.run()
│   ├─ _start_stdin_reader()           阻塞线程读 stdin → call_soon_threadsafe → asyncio.Queue
│   ├─ core/hello 通知                 协议版本 / Core 版本 / 16 个能力 / native backend
│   ├─ _reindex_sessions() 后台任务     历史会话全文索引（一次性迁移）
│   ├─ _heartbeat() 后台任务            每 3 秒 agent/ping
│   └─ while 读队列 → _dispatch()      串行处理，长任务自己 create_task
│
└─ _dispatch()
    ├─ 无 method        → 丢弃（Core 从不主动发请求）
    ├─ 无 id            → _handle_notification（settings/update、chat/cancel、ide/hello）
    ├─ inline/complete  → create_task(_run_inline)
    ├─ index/build      → create_task(_run_index_build)
    └─ 其它             → await _handle_request → 写响应（或错误）
```

---

## 1. `agent_core/__init__.py`（47 行）

已在第 02 卷看过版本链，这里只补实现细节。

### 1.1 `_packaged_version()`（13-22）

```python
base = getattr(sys, "_MEIPASS", "")
if not base:
    return ""
```

- `sys._MEIPASS` 是 **PyInstaller 运行时注入的临时解包目录**，源码直跑时不存在 → 返回 `""`。
- 读 `core-build-info.json` 的 `version` 字段。
- 捕获 `(OSError, ValueError)`：文件不存在、JSON 坏掉都退化成 `""`，**绝不让版本解析失败拖垮启动**。

### 1.2 `_resolve_version()`（25-44）

四段降级：环境变量 → 打包信息 → `importlib.metadata` → `"0.0.0-dev"`。

📌 `except Exception` 在这里是**必要的宽捕获**：`importlib.metadata.version()` 在「包未安装」「元数据损坏」「PEP 610 路径失效」等场景会抛不同异常（`PackageNotFoundError`、`KeyError`、`OSError`…）。只捕获 `PackageNotFoundError` 不够稳。

### 1.3 `__version__ = _resolve_version()`（47）

**模块导入时就解析一次**。之后 `protocol.CORE_VERSION`、CLI `--version`、`core/hello`、MCP `clientInfo` 全部读同一个值——这就是「四个地方不可能互相矛盾」的机制。

⚠️ 副作用：如果在 exe 运行期间改了环境变量，`__version__` 不会跟着变。这没问题，因为版本本来就该是构建期决定的。

---

## 2. `__main__.py`（104 行）逐函数

### 2.1 `_configure_streams()`（18-24）

```python
for stream in (sys.stdin, sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
```

| 细节 | 原因 |
|---|---|
| 三个流都要改 | stdout 是协议通道（中文内容必须 UTF-8）；stderr 是日志；stdin 是 Shell 写来的帧 |
| `errors="replace"` | 宁可把坏字节变成占位符（`U+FFFD`），也不能让一次解码异常打断整个任务 |
| 捕获 `AttributeError` | 某些被包装过的流（测试替身、重定向对象）没有 `reconfigure` |
| 捕获 `ValueError` | 流已关闭 / 已经是二进制流时 `reconfigure` 会抛 |

⚠️ 这一行**必须早于**任何 `print` 和日志初始化，否则日志里第一个中文就可能把进程搞崩。

### 2.2 `_default_log_file()`（27-30）

```python
os.path.join(default_log_dir(), "core-%s.log" % time.strftime("%Y%m%d"))
```

- 与 `usage.jsonl` 同目录（`~/.agent/logs`），这样 `AGENT_LOG_DIR` 一个变量能同时挪走日志与用量。
- 文件名带日期 → 每天一个新文件；文件内部的轮转交给 `RotatingFileHandler`。

### 2.3 `_configure_logging(level, log_file)`（33-52）

```python
handlers = [logging.StreamHandler(sys.stderr)]
if log_file:
    os.makedirs(directory, exist_ok=True)
    handlers.append(RotatingFileHandler(log_file, maxBytes=5*1024*1024, backupCount=5, encoding="utf-8"))
logging.basicConfig(level=getattr(logging, level.upper(), logging.INFO), handlers=handlers, format=...)
```

| 决策 | 理由 |
|---|---|
| 日志写 **stderr**，不写 stdout | stdout 是协议通道，混一行日志就会让 Shell 的 JSON 解析失败 |
| 双 handler | 控制台给 IDE 看实时输出，文件给「事后回溯」 |
| 5 MB × 5 | 最多占 25 MB，长期运行不会撑爆磁盘 |
| `getattr(logging, level.upper(), logging.INFO)` | `--log-level BOGUS` 时不崩，退化成 INFO |
| `encoding="utf-8"` | Windows 上默认编码不是 UTF-8，中文日志会乱码 |

### 2.4 `main()`（55-104）

#### 参数表

| 参数 | 默认 | 谁传 | 用途 |
|---|---|---|---|
| `--project-id` | `"default"` | Shell（`AgentCoreService.sessionId()`，实为 projectId） | 数据目录分层、`core/hello` |
| `--data-dir` | `~/.agent/data` | Shell | 会话库根目录 |
| `--log-level` | `INFO` | Shell / 手工 | |
| `--log-file` | `None` | 手工 | 覆盖默认日志路径 |
| `--no-log-file` | `False` | 手工 | 只打控制台 |
| `--version` | — | `argparse` 的 `action="version"` | 打印后 **exit 0** |
| `--native-info` | `False` | `build_native.ps1` 自检、诊断 | 打印 JSON 后 return |

#### 关键分支：`--native-info` 早退（86-90）

```python
if args.native_info:
    from agent_core.native import native_info
    print(json.dumps(native_info(), ensure_ascii=False))
    return
```

- 它在 `_configure_streams()` **之前**执行。为什么可以？——`native_info()` 的字段是 `backend` / `version` / 路径，通常是 ASCII；`ensure_ascii=False` 只影响非 ASCII 字符。
- 📌 极小的风险点：如果安装路径含非当前代码页字符（例如中文用户名 + GBK 控制台），`print` 仍可能抛 `UnicodeEncodeError`。这条路径只用于构建脚本自检，实际影响可以忽略；但如果你要给 `--native-info` 加中文输出，必须把 `_configure_streams()` 提到它前面。

#### 正常路径（92-100）

```python
_configure_streams()
log_file = "" if args.no_log_file else (args.log_file or _default_log_file())
_configure_logging(args.log_level, log_file)
server = RpcServer(project_id=args.project_id, data_dir=args.data_dir)
try:
    asyncio.run(server.run())
except KeyboardInterrupt:
    pass
```

三个顺序要点：

1. **配流先于配日志**：`StreamHandler(sys.stderr)` 是构造时就抓了 `sys.stderr` 对象，但编码是对象属性；先 reconfigure 才能让文件与控制台都正确。
2. **先配日志再构造 RpcServer**：构造函数里就会 `LOGGER.warning("tool ... re-registered")`、MCP/记忆的初始化也可能打日志；如果先构造，这些日志会走默认 lastResort handler（只输出 WARNING 以上，格式也不对）。
3. **`asyncio.run` 负责收尾**：它会在返回前取消并等待所有剩余任务（包括 `_run_task` 的 `finally`），所以 Ctrl+C 不会留下半截会话文件。

---

## 3. `protocol.py`（173 行）逐类

### 3.1 `Role` / `Permission`（22-32）

| 枚举 | 值 | 用途 |
|---|---|---|
| `Role` | `system` / `user` / `assistant` / `tool` | 消息角色，直接映射到两家 provider 的报文 |
| `Permission` | `auto` / `preview` / `confirm` | 工具权限，**随 `tool/invoke` 下发**，Shell 据此决定是否弹卡 |

📌 `preview` 与 `confirm` 的区别在 Shell：
- `preview`：写文件类工具，Shell 展示 diff 卡片（`DiffConfirmCard`）；
- `confirm`：终端命令类，Shell 展示命令卡片（`CommandConfirmCard`）。
两者在 Core 侧都只是「不是 auto」这一个布尔含义（见 `ToolDispatcher.needsApproval`）。

### 3.2 `ToolCall`（35-38）

```python
id: str          # 模型给的 call id（OpenAI 风格 call_xxx），用于与 tool 消息配对
name: str
arguments: Dict[str, Any]
```

⚠️ `arguments` 是**已经解析好的 dict**，不是 JSON 字符串。解析失败发生在 provider 层（`llm/providers/*`），到这里的永远是可用的 dict。

### 3.3 `Message`（41-46）

| 字段 | 何时有值 |
|---|---|
| `role` | 必有 |
| `content` | 普通消息必有；纯工具调用的 assistant 消息可能是 `None` |
| `tool_calls` | assistant 决定调工具时 |
| `tool_call_id` | role=tool 时，指回某次调用 |
| `name` | role=tool 时是工具名（部分 provider 需要） |

### 3.4 `ToolDef`（49-56）

| 字段 | 默认 | 说明 |
|---|---|---|
| `name` | 必有 | LLM 看到的名字 |
| `description` | 必有 | 进系统提示词，**写得越具体模型选得越准** |
| `parameters` | `{}` | JSON Schema |
| `permission` | `AUTO` | |
| `timeout_sec` | `30.0` | 由 `scheduler.run_parallel` 用 `wait_for` 执行 |
| `max_concurrency` | `4` | 声明式上限；实际并发由全局信号量控制 |
| `remote` | `True` | **默认 True**：绝大多数工具实现体在 Shell；内置工具显式传 `remote=False` |

### 3.5 `ToolResult`（59-63）

```python
tool_call_id: str
ok: bool
content: Optional[str]
error: Optional[str]
```

📌 约定：`ok=False` 时把「给人看的原因」写进 `error`，它会被 `run_chat` 塞进 tool 消息让模型自我修正，也会被 `chat/toolEnd.summary` 截断到 300 字给 UI。

### 3.6 `ChatConfig`（66-93）——唯一的运行时配置

| 字段 | 默认 | 备注 |
|---|---|---|
| `provider` | `ollama` | 只有两个合法值，但 Shell 会用 `ModelEndpoints.detect()` 改写 |
| `base_url` | `http://localhost:11434` | |
| `api_key` | `None` | 只有非空才下发（见 `pushSettings`） |
| `model` | `qwen2.5:7b` | |
| `temperature` | `0.2` | |
| `max_tokens` | `4096` | 单次输出上限 |
| `max_iterations` | `40` | 轮次上限 |
| `context_window` | `32000` | `0` 时回退 `DEFAULT_BUDGET=24000` |
| `tool_concurrency` | `4` | 信号量大小 |
| `subagent_concurrency` | `3` | 子代理上限 |
| `embedding_model` | `nomic-embed-text` | 语义索引 |
| `mcp_servers` | `[]` | 每次 `settings/update` 都可能变 |
| `command_allowlist` | `[]` | 空 = 用内置 `DEFAULT_ALLOWLIST` |
| `active_team` | `""` | 空 = 未启用团队，行为与单代理完全一致（默认关闭） |
| `team_max_members` | `4` | 一次团队派发的并发成员上限 |
| `team_turn_cap` | `12` | 一轮团队任务的阶段预算（防打转） |
| `team_auto_after_preapprove` | `True` | 预审通过后，本阶段的写/命令不再逐次弹卡 |

🔁 `ChatConfig.model_fields`（pydantic v2 的类属性）是 `_apply_settings` 的**白名单来源**：Shell 多发的键会被静默丢弃。给 `ChatConfig` 加字段 = 自动允许 Shell 下发该键，**不需要改 `_apply_settings`**。

### 3.7 错误码（109-119）

| 码 | 谁抛 | Shell 表现 |
|---|---|---|
| `PROTOCOL_ERROR` | `_handle_request`（未知方法、空 message、空 id） | 状态行提示 |
| `INTERNAL_ERROR` | `_dispatch` 的兜底 `except Exception` | 「出错了」 |
| `TOOL_NOT_FOUND` | `run_parallel`（未知工具） | 工具卡片红色 |
| `TOOL_PERMISSION_DENIED` | 拒绝/白名单 | 卡片显示「已拒绝」 |
| `TOOL_TIMEOUT` | `run_parallel` 的 `wait_for` 超时 | 卡片显示超时 |
| `TOOL_EXEC_ERROR` | 工具执行异常 | 红色 |
| `LLM_ERROR` | `_run_task` 兜底 | 整轮失败 |
| `CANCELLED` | 用户点「停止」 | 「已取消」 |
| `CONTEXT_OVERFLOW` | LLM 层 | 提示缩小上下文 |
| `RATE_LIMITED` | LLM 层 | 提示限流 |

### 3.8 信封四个函数（138-166）

```python
make_request(method, params, req_id)   # {"id","method","params","ts"}
make_notification(method, params)      # {"method","params","ts"}
make_response(req_id, result)          # {"id","ok":True,"result":{...}}
make_error(req_id, code, message)      # {"id","ok":False,"error":{"code","message"}}
```

- `id` 默认 `uuid4().hex`（32 位）。
- `ts` 只在 request/notification 里，响应没有时间戳。
- `dumps()` 用 `separators=(",", ":")` 去掉空格、`ensure_ascii=False` 保留中文——**一行必须没有裸换行**，否则 Shell 的逐行解析会断帧。

⚠️ **这不是严格 JSON-RPC 2.0**：没有 `"jsonrpc": "2.0"` 字段；成功/失败用顶层 `ok` 表示而不是 `result`/`error` 二选一。文档和变量名里的 "JSON-RPC-style" 就是这个意思。

---

## 4. `server.py` 结构总览

| 行区间 | 内容 |
|---|---|
| 1-54 | 导入（业务模块 + 团队） |
| 58-68 | 常量（心跳 / 确认超时 / 预审可自动写的工具） |
| 70-73 | `_current_task` ContextVar |
| 76-78 | `_error_text` |
| 81-141 | `RpcServer.__init__` |
| 143-146 | `_register_shell_tools` |
| 150-176 | IO：`_send` / `_send_notification` / `_start_stdin_reader` |
| 180-230 | `run` / `_heartbeat` |
| 234-263 | `_dispatch` |
| 265-343 | `_handle_request` / `_team_artifacts` / `_memory_promote` |
| 347-372 | 会话索引回调与重建 |
| 374-403 | 三个 future 唤醒器 |
| 405-437 | `_run_inline` / `_run_index_build` |
| 439-488 | 通知处理 / 配置合并 / MCP 刷新 |
| 490-547 | 任务生命周期：`_chat_start` / `_forget_task` / `_cancel_session_tasks` / `_bind_project_root` |
| 549-666 | `_run_task` |
| 668-744 | 后台任务、用量、记忆、清单、黑板、子代理事件 |
| 746-795 | `_invoke_shell_tool` |
| 799-890 | `_confirm_via_shell` / `_preapprove_via_shell` / `_ask_via_shell` / `_cancel` |

---

## 5. 常量与模块级变量（58-73）

```python
HEARTBEAT_SECONDS = 3.0
CONFIRM_TIMEOUT = 120.0
ASK_TIMEOUT = 1800.0
_current_task = contextvars.ContextVar("current_task", default="")
```

| 项 | 值 | 为什么是这个值 |
|---|---|---|
| `HEARTBEAT_SECONDS` | 3.0 | 让 IDE 控制台里能看到 Core 还活着；Shell 并不依赖它判断存活（`JsonRpcConnection.isAlive()` 直接看进程） |
| `CONFIRM_TIMEOUT` | 120.0 | MCP 确认：两分钟没人点就视为拒绝（安全默认） |
| `ASK_TIMEOUT` | 1800.0 | 提问：**人可以离开半小时**；超时后模型带假设继续 |
| `_current_task` | `""` | 见下 |

另外两个常量不在这个区间但同属「服务端策略」：`WRITE_TOOL_NAMES = frozenset(("write_file", "edit_file"))`（65-68，预审通过后可自动执行的写工具，`delete_file` 刻意不在其中）。

### 5.1 为什么需要 `_current_task`（73）

`todo_write`、`_publish_todos`、`_publish_artifact`、`_on_subagent_event`、`_invoke_shell_tool` 都必须在**不接收 `taskId` 参数**的情况下知道「现在是哪个任务」。三种做法：

1. 把 `task_id` 一路当参数传下去 → 要改 `run_chat`、scheduler、每个工具签名，污染业务层；
2. 存实例属性 `self.current_task` → **并发两个会话就串号**；
3. `contextvars` → 每个 `asyncio.Task` 有独立的上下文副本，天然按任务隔离。

选了 3。`_run_task` 里 `task_token = _current_task.set(task_id)`，`finally` 里 `reset(task_token)`——**必须 reset**，否则任务复用上下文（同一个 Task 内后续代码）会读到旧值。

### 5.2 `_error_text`（76-78）

```python
return str(exc).strip() or type(exc).__name__
```

`httpx` / `httpcore` 的超时异常 `str(exc)` **可能是空字符串**。没有这行，用户会看到一条「错误：」（空消息），完全无法排查。

---

## 6. `RpcServer.__init__`（82-141）逐字段

### 6.1 字段表

| 字段 | 类型 | 作用 |
|---|---|---|
| `project_id` | str | 来自 `--project-id` |
| `data_dir` | str | 来自 `--data-dir` |
| `project_root` | `Optional[str]` | 先读 `AGENT_PROJECT_ROOT`，收到 `contextSnapshot` 后再绑定 |
| `config` | `ChatConfig` | 运行时配置 |
| `store` | `SessionStore` | 会话文件读写 |
| `search` | `SessionSearch` | 全文检索（`<data_dir>/sessions.db`） |
| `sessions` | `SessionManager` | 带 `on_saved` / `on_deleted` 回调 |
| `usage` | `UsageLog` | `AGENT_USAGE_DIR` 或默认日志目录 |
| `tools` | `ToolRegistry` | 定义 + handler |
| `memory` | `MemoryService` | 依赖 `project_root` |
| `index` | `CodeIndexService` | 构造时注入 `lambda: self.config`（**延迟读取**当前配置） |
| `skills` | `SkillService` | |
| `mcp` | `MCPManager` | `set_confirm(self._confirm_via_shell)` |
| `subagents` | `SubagentService` | 子代理目录、执行与 `on_event` |
| `teams` | `TeamService` | 团队剧本、`preapprove_fn` 与 `on_artifact` |
| `_mcp_settings` | `List` | 上次生效的 MCP 配置（用于 diff） |
| `_pending_confirms` | `Dict[str, Future]` | confirmId → future |
| `_pending_asks` | `Dict[str, Future]` | askId → future |
| `_shell_capabilities` | `set` | `ide/hello` 声明 |
| `_tasks` | `Dict[str, Task]` | taskId → asyncio.Task |
| `_task_sessions` | `Dict[str, str]` | taskId → sessionId（跨会话删除时用） |
| `_pending_tools` | `Dict[str, Future]` | toolCallId → future |
| `_lines` | `Queue` | stdin 行队列 |
| `_loop` | loop | 供 stdin 线程 `call_soon_threadsafe` |
| `_out_lock` | `threading.Lock` | 保护 stdout 写入 |
| `_closing` | bool | 管道断了 |
| `_index_task` | Task | 历史索引任务 |
| `_background` | set | 强引用后台任务，防被 GC |

### 6.2 构造顺序里的三个因果

**① `index` 收 `lambda: self.config` 而不是 `self.config`**

配置会在 `settings/update` 时被**整体替换**（`self.config = ChatConfig(**data)`）。如果构造时传值，索引服务会永远拿着旧对象。传 lambda，每次用的时候现取。

**② `mcp.set_confirm(...)` 在注册工具之前**

MCP 工具是动态注册的，一旦注册，模型随时可能调用。先把确认回调接好，再刷新 MCP，才能保证「第一次调用就有确认」。

**③ `SubagentService` / `TeamService` 最后创建（127-141）**

它们需要 `self.tools`（子代理只能用已注册的工具）；`get_provider=lambda: build_provider(self.config)` 同样是延迟构造——每次子任务开始才按最新配置建 provider。团队服务在子代理服务**之后**建：它是子代理的一层编排（剧本 + 预审 + 黑板），需要拿到同一个 `SubagentService`。

### 6.3 工具注册（121-141）

```python
self._register_shell_tools()                       # SHELL_TOOLS → RemoteToolProxy
todo_tools.register_tools(self.tools, self._publish_todos)
ask_tools.register_tools(self.tools, self._ask_via_shell)
self.memory.register_tools(self.tools)
self.index.register_tools(self.tools)
self.skills.register_tools(self.tools)
self.subagents.register_tools(self.tools)
self.teams.register_tools(self.tools)
```

| 注册方 | 工具 | 执行位置 |
|---|---|---|
| `RemoteToolProxy` | `read_file` / `write_file` / `edit_file` / `search` / `run_command` / `diagnostics` / `git_*`… | Shell（`tool/invoke`） |
| `todo_tools` | `todo_write` | Core，写完回调 `_publish_todos` |
| `ask_tools` | `ask_user` | Core，回调 `_ask_via_shell` |
| `memory` | `memory_*` | Core |
| `index` | `semantic_search` / `build_index` | Core |
| `skills` | `skill_load` / `skill_list` | Core |
| `subagents` | `subagent_run` | Core（内部再调工具） |
| `teams` | `team_*`（剧本、成员、黑板） | Core（逐成员调 `subagent_run`） |

📌 注意 `_register_shell_tools` 只做一件事：把 `SHELL_TOOLS` 里每个 `ToolDef` 都用**同一个** `RemoteToolProxy` 实例注册。工具名到执行的映射被推迟到 `_invoke_shell_tool(call)`——因为执行体不在 Core。

---

## 7. IO 层（150-176）

### 7.1 `_send(payload)`（150-157）

```python
line = dumps(payload)
with self._out_lock:
    try:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()
    except (BrokenPipeError, ValueError):
        self._closing = True
```

| 细节 | 原因 |
|---|---|
| `_out_lock` 是 `threading.Lock` 不是 `asyncio.Lock` | `_send` 是**同步函数且中间没有 await**，持锁时间以微秒计，永远不会阻塞事件循环；用 `threading.Lock` 才能同时保护将来可能出现的线程调用 |
| 一次 `write` 写完「行 + 换行」 | 避免两条并发写入交错成半行 |
| 立刻 `flush` | 不 flush 就不会被 Shell 读到（管道有缓冲） |
| 捕获 `BrokenPipeError` | Shell 关掉了 IDE / 杀掉了进程：不能抛异常，只能标记 `_closing` 让主循环退出 |
| 捕获 `ValueError` | 进程退出过程中 stdout 已关闭，写会抛 `ValueError: I/O operation on closed file` |

### 7.2 `_send_notification`（159-160）

一行包装，仅此而已。**所有通知都走这条路径**，所以想加「全局出站日志」只需改这里。

### 7.3 `_start_stdin_reader`（162-176）

```python
loop = asyncio.get_running_loop()
self._loop = loop
self._lines = asyncio.Queue()

def _reader():
    try:
        for raw in sys.stdin:
            loop.call_soon_threadsafe(self._lines.put_nowait, raw)
    except Exception:
        LOGGER.exception("stdin reader crashed")
    finally:
        loop.call_soon_threadsafe(self._lines.put_nowait, None)

threading.Thread(target=_reader, name="stdin-reader", daemon=True).start()
```

| 决策 | 原因 |
|---|---|
| 用**阻塞线程**读 stdin，而不是 `loop.connect_read_pipe` | Windows 的 Proactor 事件循环对匿名管道支持差；线程 `for raw in sys.stdin` 在三平台都稳 |
| `call_soon_threadsafe` | `asyncio.Queue` 不是线程安全的，必须把「入队」调度回事件循环 |
| 结束时放 `None` 哨兵 | 主循环靠 `raw is None` 判断 EOF |
| `daemon=True` | 进程退出时不被这个线程拖住 |
| `except Exception` + `LOGGER.exception` | 读失败也要走 `finally` 放哨兵，否则主循环永远挂着 |

---

## 8. `run()`（180-225）

### 8.1 启动序列（181-198）

```python
self._start_stdin_reader()
self._send_notification("core/hello", {...})
LOGGER.info(...)
self._index_task = asyncio.create_task(self._reindex_sessions())
heartbeat = asyncio.create_task(self._heartbeat())
```

`core/hello` 的 17 个能力：

```json
["chat", "parallel-tools", "tokenizer", "codesoup", "memory", "skill", "mcp",
 "subagent", "inline", "code-index", "session-store", "terminal",
 "session-search", "memory-pending", "usage", "ask-user", "team"]
```

外加 `native: native_info()`（`{"backend": "rust"|"python", "version": ...}`）。Shell 目前只在日志与设置页展示它，但这是**跨版本兼容的探测点**：未来 Shell 可以按 capabilities 决定是否显示某个按钮。

### 8.2 主循环（201-215）

```python
while not self._closing:
    raw = await self._lines.get()
    if raw is None:
        LOGGER.info("stdin closed, shutting down")
        break
    line = raw.strip()
    if not line:
        continue
    try:
        message = json.loads(line)
    except json.JSONDecodeError:
        LOGGER.warning("drop invalid frame: %r", line[:200])
        continue
    await self._dispatch(message)
```

| 步骤 | 细节 |
|---|---|
| `raw.strip()` | 去 `\r\n`（Windows）与首尾空格 |
| 空行跳过 | Shell 调试时偶尔会多打一个换行，不能当错误 |
| 解析失败**丢帧继续** | 比崩掉整个 Core 好；`line[:200]` 防止日志被一个 1 MB 的坏行刷爆 |
| `await self._dispatch` | **串行**：一帧处理完才取下一帧（这是「`chat/start` 必须立刻返回」的根本约束） |

### 8.3 收尾（216-225）

```python
finally:
    heartbeat.cancel()
    if self._index_task is not None:
        self._index_task.cancel()
    for task in list(self._tasks.values()):
        task.cancel()
    for task in list(self._background):
        task.cancel()
    await self.mcp.shutdown()
    LOGGER.info("agent core stopped")
```

- `list(...)` 复制：`cancel` 会触发回调修改字典，边遍历边改会抛 `RuntimeError`。
- `await self.mcp.shutdown()`：MCP 子进程/连接不关，Windows 上会留孤儿进程。
- 📌 这里 **cancel 但不 await**：真正等待它们结束的是外层 `asyncio.run()`（它在关闭循环前会取消并 gather 剩余任务），所以 `_run_task` 的 `finally`（落盘会话）仍会执行。

### 8.4 `_heartbeat`（227-230）

```python
while True:
    await asyncio.sleep(HEARTBEAT_SECONDS)
    self._send_notification("agent/ping", {})
```

- 没有退出条件：靠 `cancel()` 结束。
- Shell 侧 `AgentChatPanel.onCoreMessage` **没有 `agent/ping` 分支**，这类帧会被静默丢弃（`taskId` 为空且方法不在三个全局方法里）。它的价值在日志与控制台：能看出 Core 是否还在跑。
- ⚠️ 如果事件循环被阻塞（比如同步读大文件），ping 会迟到——这正好是一个「循环被堵」的症状。

---

## 9. `_dispatch`（234-263）逐行

```python
method = message.get("method")
req_id = message.get("id")
params = message.get("params") or {}

if method is None:
    return                                   # ①
if req_id is None:
    await self._handle_notification(method, params)   # ②
    return
if method == "inline/complete":
    asyncio.create_task(self._run_inline(req_id, params))   # ③
    return
if method == "index/build":
    asyncio.create_task(self._run_index_build(req_id, params))
    return
try:
    result = await self._handle_request(method, params)
    self._send(make_response(req_id, result))
except RpcError as exc:
    self._send(make_error(req_id, exc.code, _error_text(exc)))
except Exception as exc:
    LOGGER.exception("request %s failed", method)
    self._send(make_error(req_id, ErrorCode.INTERNAL_ERROR, _error_text(exc)))
```

### 9.1 三种帧的判定顺序

| 序号 | 条件 | 结论 | 说明 |
|---|---|---|---|
| ① | 没有 `method` | 响应 | 当前 Core 不主动发请求，所以**永远走不到**；留着是为了协议对称 |
| ② | 有 `method` 没 `id` | 通知 | 不回复 |
| ③ | 有 `method` 有 `id` | 请求 | 必回一帧（成功或失败） |

⚠️ 判定顺序是 **method 优先于 id**：如果反过来，一条带 `id` 的通知会被误当请求并回一个「unknown method」。

### 9.2 为什么只有这两类请求被 `create_task`

| 请求 | 耗时 | 处理 |
|---|---|---|
| `inline/complete` | 一次 LLM 调用（最多 12 s） | 后台任务，否则打字时的补全会冻结聊天 |
| `index/build` | 遍历仓库、批量 embedding（分钟级） | 后台任务 |
| 其它全部 | 内存操作或小文件 IO（毫秒级） | 直接 await |

`chat/start` 不在其中，因为它自己**只做登记**（见 12 节），立刻返回。

### 9.3 错误映射

| 异常类型 | 响应 |
|---|---|
| `RpcError` | 用自带的 `code` |
| 其它 `Exception` | `INTERNAL_ERROR` + **完整堆栈进日志**，Shell 只拿一句话 |

📌 `RpcError` 的 `code` 必须是 `ErrorCode` 里的常量；Shell 的 `ChatSessionPanel` 会按 `code == "CANCELLED"` 之类的字符串做分支，**新增错误码要同步 Shell**。

---

## 10. `_handle_request`（265-309）

### 10.1 16 个方法 + 兜底

| 方法 | 处理函数 | 返回 | 备注 |
|---|---|---|---|
| `chat/start` | `_chat_start` | `{"taskId"}` | 立即返回 |
| `ping` | 内联 | `{"pong": true}` | Shell 当前未使用，留给手工探测 |
| `tool/result` | `_tool_result` | `{"received": true}` | 唤醒工具 future |
| `agent/confirmResult` | `_confirm_result` | `{"received": true}` | 唤醒确认 future |
| `ask/userAnswer` | `_ask_result` | `{"received": true}` | 唤醒提问 future |
| `index/status` | 内联 | 索引状态 | |
| `session/list` | 内联 | `{"sessions": [...]}` | |
| `session/get` | 内联 | 会话详情 | |
| `session/delete` | 内联 | `{"deleted","freedBytes"}` | 先取消任务 |
| `session/search` | 内联 | `{"results": [...]}` | 默认 `limit=20` |
| `memory/pending` | 内联 | `{"items": [...]}` | |
| `memory/promote` | `_memory_promote` | 记忆对象 | |
| `memory/discard` | 内联 | `{"discarded","remaining"}` | |
| `usage/report` | 内联 | 按天聚合 | 默认 7 天 |
| `team/list` | 内联 | `{"teams": [...]}` | 团队清单（Shell 选择器） |
| `team/artifacts` | `_team_artifacts` | `{"artifacts","teamRuns"}` | 重开会话时回放黑板 |
| 其它 | — | `RpcError(PROTOCOL_ERROR, "unknown method: ...")` | |

### 10.2 四个需要留意的细节

**`ping`**（268-269）：返回 `{"pong": True}`。Shell 的 `JsonRpcConnection.isAlive()` 用的是 `process.isAlive()`，所以这条路径目前只在手工联调时有用。

**`session/delete` 的先后顺序**（282-289）：

```python
self._cancel_session_tasks(session_id)
freed = self.sessions.delete(session_id)
```

注释写得很直白：先删文件的话，正在跑的任务在 `finally` 里 `sessions.save(session)` 会把文件**又写回来**。取消任务后，`_run_task` 的 `finally` 仍会执行一次 `save`——但此时 `session.deleted` 已被置位（见 `_record_usage` 里的 `not session.deleted`），保存会被跳过。

**`session/search` 的 `limit`**（290-292）：`int(params.get("limit") or 20)`。如果 Shell 传 `0`，`or` 会把它变成 `20`——这是刻意的（0 条结果没有意义）。

**`memory/promote` / `memory/discard` 的空 id**（295-302）：显式抛 `PROTOCOL_ERROR`，而不是用空字符串去查。

### 10.3 `_memory_promote`（322-343）

```python
keywords = params.get("keywords")
if isinstance(keywords, str):
    keywords = [part.strip() for part in keywords.split(",") if part.strip()]
if not isinstance(keywords, list):
    keywords = None
try:
    return self.memory.promote(pending_id, content=..., title=..., category=..., keywords=keywords)
except KeyError:
    raise RpcError(PROTOCOL_ERROR, "pending memory not found: %s" % pending_id)
except ValueError as exc:
    raise RpcError(PROTOCOL_ERROR, str(exc))
```

- **两种输入形态**：`"a,b,c"` 字符串（设置页/命令行）或 `["a","b"]` 数组（UI）。
- 分隔符是**英文逗号**，中文逗号不切分；每个片段 `strip()` 且丢掉空串。
- `KeyError` → 「找不到这条候选」；`ValueError` → 「内容不合法」（例如正文为空）。两者都映射成 `PROTOCOL_ERROR`，因为对 Shell 来说都是「你的输入有问题」。

---

## 11. 会话索引（347-372）

```python
def _on_session_saved(self, session_id, title, content, updated_at):
    self.search.index(session_id, title, content, updated_at)

def _on_session_deleted(self, session_id):
    self.search.remove(session_id)
```

- `SessionManager` 在每次保存/删除后回调这两个方法 → **索引永远与文件同步**，不需要定时扫描。
- 这两个回调是**同步**的：`index()` 写的是 SQLite，保存会话本身也在同一线程，属于「顺手写一行」。

### `_reindex_sessions`（354-364）

```python
total = await asyncio.to_thread(self._index_all_documents)
...
self._send_notification("session/indexProgress", {"done": total, "total": total, "finished": True})
```

- `to_thread`：读几百个会话文件 + 建索引是阻塞 IO。
- 失败只 `LOGGER.exception` 并 return：**索引坏了只影响搜索**，不能让 Core 起不来。
- 结束时必发 `finished: True`，Shell 收到后把状态行从「正在建立索引」改回「就绪」。

### `_index_all_documents`（366-372）

```python
def progress(done, changed):
    self._send_notification("session/indexProgress", {"done": done, "total": 0, "changed": changed})
return self.search.index_many(self.store.iter_documents(), progress)
```

`total: 0` 是刻意的：开始时还不知道总数（`iter_documents` 是生成器），Shell 只显示「已处理 N 条」。

---

## 12. 三个 future 唤醒器（374-403）

三者结构完全一致：

```python
future = self._pending_x.pop(key, None)
if future is not None and not future.done():
    future.set_result(...)
return {"received": True}
```

| 函数 | key | 结果 |
|---|---|---|
| `_tool_result` | `toolCallId` | `ToolResult(ok, content, error)` |
| `_confirm_result` | `confirmId` | `bool(approved)` |
| `_ask_result` | `askId` | `{"skipped": bool, "answers": list}` |

四个要点：

1. **`pop` 而不是 `get`**：一次等待只允许被唤醒一次，顺手清理。
2. **`not future.done()`**：可能已超时被 `wait_for` 取消，`set_result` 会抛 `InvalidStateError`。
3. **找不到 key 也算成功**：返回 `{"received": True}`。因为对 Shell 来说「我已经把结果送到了」就是成功；Core 侧早就超时了而已。
4. **`answers` 类型校验**：不是 list 就退化成 `[]`，防止 Shell 发来 `null` 让下游炸。

📌 为什么用**请求**（带 id）而不是通知回传？——Shell 需要知道 Core 收到了，否则「点了确认但卡片一直转圈」无法区分是网络丢帧还是 Core 卡住。

---

## 13. 两个「不占主循环」的请求（405-437）

### 13.1 `_run_inline`（405-423）

```python
text = await asyncio.wait_for(complete_inline(self.config, prefix, suffix, language,
                                              int(params.get("maxTokens") or 128)),
                              timeout=12.0)
self._send(make_response(req_id, {"text": text}))
except asyncio.TimeoutError:  → make_response({"text": ""})
except Exception:             → LOGGER.exception + make_response({"text": ""})
```

| 设计 | 原因 |
|---|---|
| **永远返回成功**，失败给空串 | ghost text 是「有就用、没有就算」的功能，弹错误框会毁掉打字体验 |
| 超时 12 s | Shell 侧只等 **1500 ms**（`InlineCompletionClient.TIMEOUT_MS`），12 s 只是 Core 侧防泄漏的兜底 |
| `maxTokens` 默认 128 | 补全通常只有一行，给多了纯浪费 |
| 参数里没有 `path` | Shell 会传，但 Core 的 `complete()` 不用它（模型只看前后文） |

📌 因为 Shell 先超时，通常的情况是：Shell 放弃后 Core 仍把请求跑完并把结果写进 stdout，Shell 收到一个「没人等的响应」并丢弃。这不会出错——`JsonRpcConnection` 按 id 找 future，找不到就丢。

### 13.2 `_run_index_build`（425-437）

```python
def on_progress(done, total, path):
    self._send_notification("index/progress", {...})

stats = await self.index.build(full=bool(params.get("full")), progress=on_progress)
self._send(make_response(req_id, stats))
```

与 inline 相反：**失败要回错误**，因为用户是主动点「重建索引」的，必须知道结果。

---

## 14. 通知处理（439-453）

```python
if method == "settings/update":   self._apply_settings(params)
elif method == "chat/cancel":     self._cancel(params.get("taskId"))
elif method == "ide/hello":       记录 capabilities
else:                             LOGGER.debug("unhandled notification: %s", method)
```

- **只有 3 个**：Core 对 Shell 的通知面极小。
- `else` 用 `debug`：未来协议扩展时，老 Core 遇到新通知不会刷警告。
- `chat/cancel` 的 `taskId` 允许为空 → 取消全部（见 19.3）。

---

## 15. `_apply_settings`（457-475）逐行

```python
allowed = set(ChatConfig.model_fields.keys())
data = self.config.model_dump()
data.update({k: v for k, v in params.items() if k in allowed})
try:
    self.config = ChatConfig(**data)
except Exception:
    LOGGER.exception("invalid settings payload: %s", data)
    return
servers = parse_servers(self.config.mcp_servers or [])
if [s.dict() for s in servers] != [s.dict() for s in self._mcp_settings]:
    self._mcp_settings = servers
    self._schedule_mcp_refresh()
```

### 15.1 白名单 + 合并

| 步骤 | 作用 |
|---|---|
| `allowed` | 只接受 `ChatConfig` 声明过的键 |
| `model_dump()` | 取当前全部值 |
| `update(...)` | 用新值覆盖 |
| `ChatConfig(**data)` | **重新构造**，让 pydantic 做类型校验与默认值填充 |

**为什么要合并而不是替换？** Shell 可能只发 `{"model": "x"}`（切换模型）。整体替换会把 `max_iterations`、`mcp_servers`、`command_allowlist` 全部重置成默认值。

**为什么构造失败时旧配置不受影响？** 因为赋值在构造之后：`self.config = ChatConfig(**data)` 这句里，右边的构造抛异常就不会赋值。这是「先算后写」的经典写法。

### 15.2 MCP 变化检测

```python
if [s.dict() for s in servers] != [s.dict() for s in self._mcp_settings]:
```

- 用**列表相等**比较，而不是「长度变了」——改一个 url 也要刷新。
- 顺序敏感：配置文件里换顺序会被当成变化，触发一次无害的刷新。
- 📌 `.dict()` 是 pydantic v1 的写法，v2 仍保留但已标记废弃（会有 `DeprecationWarning`）。换成 `.model_dump()` 行为等价。

### 15.3 `_schedule_mcp_refresh`（477-482）

```python
try:
    loop = asyncio.get_running_loop()
except RuntimeError:
    return
loop.create_task(self._refresh_mcp())
```

- 必须在**事件循环里**才能 `create_task`；`settings/update` 由 `_dispatch` 调用，天然在循环里。
- 兜底 `return` 是为了将来有测试直接调 `_apply_settings()`（不在循环里时不炸）。
- 不 `await`：MCP 握手可能要几秒，不能卡住这一帧。

---

## 16. `_chat_start`（490-517）

```python
session_id = params.get("sessionId") or "default"
user_input = (params.get("message") or "").strip()
if not user_input:
    raise RpcError(ErrorCode.PROTOCOL_ERROR, "empty message")

session = self.sessions.get_or_create(session_id)
if not session.title:
    session.title = make_title(user_input)
session.touch()

snapshot = params.get("contextSnapshot")
if snapshot:
    try:
        parsed = ContextSnapshot(**snapshot)
        session.last_context = parsed
        self._bind_project_root(parsed.project_root)
    except Exception:
        LOGGER.warning("ignored invalid context snapshot")

task_id = uuid.uuid4().hex[:12]
skill_name = (params.get("skill") or "").strip()
task = asyncio.create_task(self._run_task(task_id, session, user_input, skill_name))
self._tasks[task_id] = task
self._task_sessions[task_id] = session_id
task.add_done_callback(lambda _t, tid=task_id: self._forget_task(tid))
return {"taskId": task_id}
```

| 行 | 细节 |
|---|---|
| 491 | 没有 sessionId 时用 `"default"`：手工调试可以不带 |
| 492 | `strip()` 后为空 → 报错。**纯空白消息不允许**（避免空转一轮 LLM） |
| 499-500 | `make_title()` 只在**第一次**取标题：之后用户改标题不会被覆盖 |
| 507 | `_bind_project_root` 可能重建 memory/skills/mcp/subagents/teams（见 17.3） |
| 508-509 | `contextSnapshot` 字段类型不对时只警告：不能因为 Shell 多传一个字段就让整轮失败 |
| 511 | 12 位 hex：够短（日志/UI 好看），碰撞概率可忽略 |
| 513-516 | 先 `create_task` 再登记：**任务可能在登记前就跑起来**，所以 `task_id` 是参数传入的，而不是任务自己生成 |
| 516 | `lambda _t, tid=task_id:` 用默认参数**捕获当前值**——否则闭包会共享最后一个 `task_id` |

⚠️ 关于 `create_task` 与响应顺序：`create_task` 只是把协程排进队列，`_chat_start` 随即 return，`_dispatch` 紧接着写出响应，**然后**才轮到 `_run_task` 执行。所以 Shell 一定先拿到 `taskId`、再收到第一条 `chat/token`。这是整个路由机制（`byTask`）成立的前提。

---

## 17. 任务登记与取消（519-547）

### 17.1 `_forget_task`（519-521）

```python
self._tasks.pop(task_id, None)
self._task_sessions.pop(task_id, None)
```

`add_done_callback` 里调用 → 任务结束后两张表自动清空，不会无界增长。

### 17.2 `_cancel_session_tasks(session_id)`（523-532）

```python
for task_id, owner in list(self._task_sessions.items()):
    if owner != session_id:
        continue
    task = self._tasks.get(task_id)
    if task is not None and not task.done():
        task.cancel()
```

- `list(...)`：`cancel` 触发的 done callback 会改 `_task_sessions`。
- 一个会话理论上只有一个任务（Shell 侧 `currentTaskId` 挡着），但这里写成循环——**Core 不假设 Shell 的行为**。

### 17.3 `_bind_project_root`（534-547）

```python
if not project_root or project_root == self.project_root:
    return
self.project_root = project_root
self.memory = MemoryService(project_root, self.data_dir)
self.memory.register_tools(self.tools)
self.index.set_project_root(project_root)
self.skills = SkillService(SkillLoader(project_root))
self.skills.register_tools(self.tools)
self.mcp.set_project_root(project_root)
self._schedule_mcp_refresh()
self.subagents.loader.set_project_root(project_root)
self.teams.loader.set_project_root(project_root)
```

**为什么整个对象都要重建？** 因为它们是按项目路径初始化的：记忆目录 `.agent/memories`、技能目录 `.agent/skills`、索引库、MCP 配置 `.agent/mcp.json`。改路径等于换项目。

**为什么不重建 `MemoryService` 里的 `data_dir` 部分？** `data_dir` 是 `--data-dir`（项目级），与项目根无关。

⚠️ 重新 `register_tools` 会命中 `ToolRegistry.register` 的**同名警告**：

```python
if tool_def.name in self._defs:
    LOGGER.warning("tool %s re-registered", tool_def.name)
```

这是**预期行为**：`register` 会覆盖 def 与 handler（handler 非 None 时）。日志里看到一串 `re-registered` 不代表出错。

📌 触发时机：Shell 每条 `chat/start` 都带 `contextSnapshot`，但只有**第一次**（或打开另一个项目）时 `project_root` 才变化。所以「重建 + 一串警告」只会出现一次。

---

## 18. `_run_task`（549-666）

第 01 卷已逐段讲过主流程，这里只补三处与「服务端职责」相关的细节。

### 18.1 `usage_total` 与 `ok` 的初始值（573-574）

```python
usage_total: Dict[str, Any] = {}
ok = False
```

- `usage_total`：即使 `run_chat` 抛异常，`finally` 里的 `_record_usage` 也要能读到（记 0）。
- `ok`：只有成功路径把它置 `True`；取消/异常都保持 `False` → 用量文件里能区分成功与失败。

### 18.2 五个回调（576-607）

| 回调 | 发什么 | 注意 |
|---|---|---|
| `emit_token` | `chat/token` | 每 token 一条通知 |
| `emit_reasoning` | `chat/reasoning` | 3.3.0 新增；思考**不落 session、不计 usage**（579-583） |
| `on_tool_start` | `chat/toolStart` | 顺带 `tool_calls += 1`；用 `nonlocal` |
| `on_tool_end` | `chat/toolEnd` | `summary` 先 `replace("\n"," ")` 再截 300 字 |
| `on_notice` | `chat/notice` | provider 降级（如端点不支持工具）**不致命** |

`run_chat` 的调用处（616-625）把五个回调一次性接好：

```python
result = await run_chat(
    session, user_input, self.config, provider, emit_token,
    registry=self.tools, on_tool_start=on_tool_start, on_tool_end=on_tool_end,
    on_notice=on_notice,
    on_reasoning=emit_reasoning,
    ...
)
```

📌 `summary` 为什么要把换行换成空格？——它显示在工具卡片的一行里，带换行会把布局撑开。

📌 思考为什么不拼进 `chat/token`？——两者语义不同：`chat/token` 会被拼成正文并写回会话历史，思考只是「给人看的旁白」。分两路后，旧 Shell 不认识 `chat/reasoning` 就当作未知帧丢掉，对话完全不受影响。

### 18.3 `finally` 的顺序（654-666）

```python
todo_tools.reset_session(todo_token)
team_tools.reset_session(team_token)
team_tools.reset_run(run_token)
self.teams.clear_run(task_id)
# 团队记录在这里补齐 ok / toolCalls / durationMs（659-662）
_current_task.reset(task_token)
self.sessions.save(session)
self._record_usage(task_id, usage_total, started, tool_calls, ok and not session.deleted)
```

顺序不能换：

1. **先 reset contextvars**：之后的 `save` / 日志不应再误用任务上下文。
2. **再 save**：把这一轮的 user / assistant / tool 消息落盘。
3. **最后记用量**：`ok and not session.deleted`——会话被删了就不算一次成功任务。

---

## 19. 后台与回调（668-744）

### 19.1 `_spawn`（668-672）

```python
task = asyncio.create_task(coroutine)
self._background.add(task)
task.add_done_callback(self._background.discard)
```

Python 的 `asyncio` 只保留**弱引用**，一个没人持有的 task 可能被 GC 掉（表现为「任务中途消失」）。这个 set 就是强引用容器，完成时自动移除。

### 19.2 `_record_usage`（674-685）

```python
self.usage.record(task_id=..., model=self.config.model,
                  usage_in=int(usage.get("in") or 0),
                  usage_out=int(usage.get("out") or 0),
                  tool_calls=tool_calls,
                  duration_ms=int((time.monotonic() - started) * 1000),
                  ok=ok)
```

- `started` 是 `_run_task` 里的 `time.monotonic()`：**单调时钟**，不受系统时间调整影响。
- `int(... or 0)`：`usage` 可能是 `{}`。
- 每任务一行 JSONL，写在本机 `~/.agent/logs/usage.jsonl`。

### 19.3 `_capture_memories`（687-698）

```python
added = await asyncio.to_thread(memory.capture, session)
if not added: return
self._send_notification("memory/pendingChanged", {"count": len(added)})
```

- 跑在**任务结束之后**（`chat/finish` 已发出），不占用户的等待时间。
- `to_thread`：规则抽取要读写 `.agent/memories/_pending`。
- 失败只记日志——**记忆抽取永远不能影响聊天结果**。
- 通知里的 `count` 是**本次新增数**，不是总数；Shell 用它更新按钮角标。

### 19.4 `_publish_todos`（700-705）

```python
self._send_notification("chat/todos", {"taskId": _current_task.get(), "todos": todos})
```

`todo_tools` 每次写入后回调。`taskId` 从 ContextVar 取——子代理的 asyncio 任务在创建时会**复制**当前上下文，所以即使模型在子代理里调 `todo_write`，读到的仍是主任务的 id，UI 会把它显示在主任务的清单区。

### 19.5 `_publish_artifact`（707-716）

```python
self._send_notification("chat/artifact", {
    "taskId": _current_task.get(),
    "teamRunId": _current_task.get(),
    "kind": artifact.get("kind") or "note",
    "title": artifact.get("title") or "",
    "authorRole": artifact.get("authorRole") or "",
    "refs": artifact.get("refs") or [],
})
```

- 团队每次向黑板写一份产出（笔记/代码片段/清单）就推一条，Shell 可以边跑边渲染。
- 同一份产出也会存进 `session.artifacts`，重开会话时由 `team/artifacts` 回放——「实时推送 + 事后回放」两条路。

### 19.6 `_on_subagent_event`（720-744）

```python
payload = {"taskId": _current_task.get(), "teamRunId": ..., "subagentId": task.id,
           "kind": kind, "role": role, "actor": actor, "capability": capability,
           "model": getattr(task, "model", "") or "", "goal": task.goal[:200]}
if report is not None:
    payload["ok"] = report.ok
    payload["summary"] = report.summary[:300]
    payload["durationMs"] = getattr(report, "duration_ms", 0)
```

- `goal[:200]`、`summary[:300]`：UI 卡片一行放不下更多。
- `getattr(report, "duration_ms", 0)`：`report` 的字段可能随版本变化，用 `getattr` 兜底。
- `actor` / `capability` 在团队模式下换成成员显示名与归一化能力（`member.display_name()` / `normalised_capability()`），Shell 因此能显示「来自 全栈工程师 · 实现」而不是裸 role。

---

## 20. `_invoke_shell_tool`（746-795）

```python
tool_def = self.tools.get(call.name)
permission = tool_def.permission.value if tool_def else Permission.AUTO.value

if call.name == "run_command":
    command = str(call.arguments.get("command") or "")
    decision = decide_command(command, self.config.command_allowlist)
    if decision == "deny":
        return ToolResult(ok=False, error="已拒绝执行危险命令: ...")
    permission = decision

loop = asyncio.get_running_loop()
future = loop.create_future()
self._pending_tools[call.id] = future
self._send_notification("tool/invoke", {
    "taskId": _current_task.get(), "toolCallId": call.id, "toolName": call.name,
    "params": call.arguments, "permission": permission,
})
try:
    return await future
finally:
    self._pending_tools.pop(call.id, None)
```

### 20.1 命令策略留在 Core（752-766）

```python
decision = decide_command(command, self.config.command_allowlist)
```

返回值三种：`"deny"` / `"auto"` / `"confirm"`。

| 决策 | 行为 |
|---|---|
| `deny` | **根本不发给 Shell**，直接返回 `ok=False` + 中文解释（引导模型改用 `delete_file`） |
| `auto` | 下发，Shell 直接执行 |
| `confirm` | 下发，Shell 弹确认卡 |

📌 为什么在 Core 判？注释给了答案：*the policy travels with the invocation so both IDEs behave identically*。如果放在 Shell，IntelliJ 与 VS Code 就要各维护一份危险命令黑名单，迟早不一致。

第二条同类判定在 767-772：团队预审通过后，`write_file` / `edit_file` 在本阶段范围内直接降为 `AUTO`（不再逐次弹 diff 卡），`delete_file` 永远不在豁免名单里。判断靠 `self.teams.should_auto_write(taskId, path)`——范围判断在 Core，Shell 不参与。

### 20.2 `tool_def` 为 None 时用 `AUTO`

理论上走不到（`run_parallel` 已经拒绝未知工具并返回 `TOOL_NOT_FOUND`）。这里给 `AUTO` 是「不要因为查不到定义就卡住等确认」。

### 20.3 这里**没有超时**

```python
return await future
```

超时由上层 `ToolScheduler.run_parallel` 的 `asyncio.wait_for(..., timeout=tool_def.timeout_sec)` 负责。超时后协程被取消，`finally` 把 `_pending_tools[call.id]` 清掉；之后 Shell 迟到的 `tool/result` 找不到 future，返回 `{"received": true}` 并被忽略。

📌 这个分工很关键：**Core 不重复实现超时**，否则两层超时不一致会出现「明明工具已超时，卡片还转圈」。

---

## 21. 三个阻塞式交互（799-881）

### 21.1 `_confirm_via_shell`（799-817）

```python
confirm_id = uuid.uuid4().hex[:12]
future = asyncio.get_running_loop().create_future()
self._pending_confirms[confirm_id] = future
self._send_notification("agent/confirm", {"confirmId": confirm_id, "taskId": ..., 
                                          "kind": "mcp", "server": server,
                                          "tool": tool, "args": arguments})
try:
    return bool(await asyncio.wait_for(future, timeout=CONFIRM_TIMEOUT))
except asyncio.TimeoutError:
    return False
finally:
    self._pending_confirms.pop(confirm_id, None)
```

- `kind` 硬编码 `"mcp"`：目前只有 MCP 需要 Core 主动确认；文件/命令的确认走 `tool/invoke` 的 `permission`。
- **超时 = 拒绝**（安全默认）。
- 返回 `bool(...)`：Shell 可能发来 `null`。

### 21.2 `_preapprove_via_shell`（823-849）

```python
if "team" not in self._shell_capabilities:
    LOGGER.info("team preapprove skipped: shell did not advertise team")
    return False
preapprove_id = uuid.uuid4().hex[:12]
self._send_notification("agent/confirm", {
    "confirmId": preapprove_id, "taskId": ..., "kind": "preapprove",
    "preapproveId": preapprove_id, "plan": ..., "firstDiff": ..., "files": [...],
})
try:
    return bool(await asyncio.wait_for(future, timeout=CONFIRM_TIMEOUT))
except asyncio.TimeoutError:
    return False
```

- 与 MCP 确认**共用** `agent/confirm` / `agent/confirmResult` 与 `_pending_confirms`，靠 `kind` 区分（`"mcp"` / `"preapprove"`）。
- **没声明 `team` 能力就不发**：旧 Shell 会把预审当成 MCP 确认卡（对着看不到的计划回答「MCP」），所以直接 `False`，退化成逐写确认。
- `False` 不是错误：它只表示「本阶段不预审」，每次写依旧走 diff 卡。

### 21.3 `_ask_via_shell`（856-881）

```python
if "ask-user" not in self._shell_capabilities:
    LOGGER.info("ask_user skipped: shell did not advertise ask-user")
    return None
ask_id = uuid.uuid4().hex[:12]
...
answered = False
try:
    reply = await asyncio.wait_for(future, timeout=ASK_TIMEOUT)
    answered = True
    return reply
except asyncio.TimeoutError:
    return None
finally:
    self._pending_asks.pop(ask_id, None)
    if not answered:
        self._send_notification("ask/withdraw", {"askId": ask_id, "taskId": ...})
```

| 分支 | 结果 |
|---|---|
| Shell 没声明 `ask-user` | **直接返回 None**，不建 future、不发通知；模型带假设继续 |
| 用户回答 | 返回 `{"skipped","answers"}` |
| 超时（30 分钟） | 返回 `None` + 发 `ask/withdraw` |
| 任务被取消 | `wait_for` 抛 `CancelledError`（不是 `TimeoutError`）→ 穿过 try，`finally` 里 `answered=False` → 发 `ask/withdraw` |

📌 为什么 `answered` 要用变量记录？因为 `return reply` 之后 `finally` 仍会执行，必须区分「正常拿到答案」和「超时/取消」。

---

## 22. `_cancel`（883-890）

```python
if task_id:
    task = self._tasks.get(task_id)
    if task:
        task.cancel()
    return
for task in list(self._tasks.values()):
    task.cancel()
```

- 带 `taskId`：只取消那一个（Shell 点「停止」）。
- 不带：取消全部（Shell 退出前的兜底）。
- 找不到 id：**静默**。任务可能刚好自己结束了，报错没有意义。

---

## 23. 谁能取消谁（生命周期全景）

| 取消者 | 被取消者 | 路径 | 后果 |
|---|---|---|---|
| 用户点「停止」 | 一个任务 | `chat/cancel` → `_cancel(taskId)` | `CancelledError` → `chat/error(CANCELLED)` → `finally` 落盘 |
| 删除会话 | 该会话全部任务 | `session/delete` → `_cancel_session_tasks` | 同上，且 `session.deleted` 阻止回写 |
| 工具超时 | 工具等待 | `scheduler` 的 `wait_for` | `TOOL_TIMEOUT`，工具卡片红 |
| MCP 确认超时 | 确认等待 | `wait_for(120s)` | 视为拒绝 |
| 预审超时 / 旧 Shell | 预审等待 | `wait_for(120s)` 或能力开关直接 `False` | 不预审，退回逐写确认 |
| 提问超时 | 提问等待 | `wait_for(1800s)` | 返回 None + `ask/withdraw` |
| stdin 关闭 | 一切 | `run()` 的 `finally` | 依次 cancel 任务/后台/索引心跳，关 MCP |
| 标签页关闭 | 该标签的等待 | Shell 侧 `ToolCallCard.decide(false)` / `AskCard.skip()` | Core 收到「拒绝/跳过」，不依赖取消 |

---

## 24. 十条易错点

1. **`_send` 里不能有 `print`**：stdout 只允许协议帧。
2. **`_dispatch` 里不要 `await` 长任务**：会让 `tool/result`/`chat/cancel` 排在一个 LLM 请求后面。
3. **`create_task` 必须留引用**：否则可能被 GC（`_spawn` 就是为此存在）。
4. **`ContextVar` 必须 reset**：否则同一 Task 后续逻辑读到旧 `taskId`。
5. **`_chat_start` 里不能 `await` 业务**：一旦 await，响应就会晚于第一条 `chat/token`，Shell 的路由表来不及登记。
6. **`_apply_settings` 必须 merge**：整体替换会把 Shell 没发的字段打回默认值。
7. **构造失败要保证旧配置不变**：先算后写。
8. **`except Exception` 的宽捕获在这层是刻意的**：协议层的目标是不让任何单帧崩掉进程。
9. **`agent/ping` 在 Shell 侧没人处理**：不要指望它做重连检测。
10. **`session/get`/`session/search` 是同步 IO**：会话很大时会在事件循环里读盘，期间心跳与流式 token 都会被推迟。要优化的话，这两处是第一批该改成 `to_thread` 的地方。

---

## 25. 自测清单

- 打开 `server.py`，只看 `_dispatch`，说出「一条 `tool/result` 请求」从进来到被谁消费的完整路径。
- 解释：为什么 `inline/complete` 用 `create_task`，而 `session/list` 不用？
- 解释：`_pending_tools` / `_pending_confirms` / `_pending_asks` 三个字典的 key 分别由谁生成？为什么不是同一个 id？
- 在 `run()` 的 `finally` 里，如果漏掉 `await self.mcp.shutdown()`，Windows 上会出现什么现象？
- 把 `ChatConfig` 加一个字段 `reasoning_effort`，列出你需要改的所有文件（提示：Core 只改一处，Shell 要改三处）。
- 用 `--native-info` 和 `--version` 各跑一次，观察它们分别走的是哪条早退路径。

```powershell
cd core
python -m agent_core --version
python -m agent_core --native-info
python -c "import sys; sys.path.insert(0,'src'); from agent_core.protocol import *; print(dumps(make_notification('x',{'k':'中文'})))"
```

最后一条应当输出 `{"method":"x","params":{"k":"中文"},"ts":...}`（中文不被转义、无空格、无换行）。

---

## 26. 下一卷

第 04 卷进入真正的业务循环：`agent/loop.py` 的 `run_chat`、`agent/scheduler.py` 的并发与超时、`agent/status.py` 的状态栏渲染、`agent/compression.py` 的裁剪、`agent/permissions.py` 的连续拒绝计数、`agent/commands.py` 的命令判定、`agent/todos.py` 的清单模型、`agent/ask.py` 的提问协议、`agent/subagent.py` 的 523 行子代理。
