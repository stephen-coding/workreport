# 第 06 卷 · 工具系统：从 `tool/invoke` 落到磁盘

> 本卷读完 15 个文件，共 **1,969 行**：
>
> **Core 侧（Python，550 行）**：`tools/registry.py`(41) · `tools/remote.py`(182) · `agent/scheduler.py`(109) · `agent/commands.py`(131) · `agent/permissions.py`(30) · `agent/compression.py`(57)
>
> **Shell 侧（Java，1,419 行）**：`tools/ToolDispatcher.java`(127) · `tools/ToolResult.java`(15) · `tools/FileTools.java`(117) · `tools/FileText.java`(154) · `tools/EditTools.java`(207) · `tools/FileSnapshots.java`(46) · `tools/ProjectWriteGate.java`(60) · `tools/SearchTools.java`(114) · `tools/CommandTools.java`(182) · `tools/GitTools.java`(73) · `tools/DiagnosticsTools.java`(152) · `ui/DiffConfirmCard.java`(109) · `ui/CommandConfirmCard.java`(63)
>
> 再加 3 个测试文件共 257 行。
>
> 第 04 卷讲「主循环怎么跑一轮」，第 05 卷讲「模型怎么把 `tool_calls` 吐出来」。本卷回答最后一个问题：**一个 `ToolCall` 从 Core 出发，经过哪几层，最后是怎么变成一次真实的 `Files.write` / `ProcessBuilder.start` / `GitLineHandler` 的**，以及出错时它怎么把话说清楚。

---

## 0. 本卷读什么

### 0.1 十五个文件的分工

| 文件 | 行数 | 一句话职责 | 在哪一侧 |
|---|---|---|---|
| `tools/registry.py` | 41 | 工具名 → 定义 + 处理函数的字典 | Core |
| `tools/remote.py` | 182 | 11 个远程工具的定义 + 转发代理 | Core |
| `agent/scheduler.py` | 109 | 并发执行一组 `ToolCall`、逐个超时兜底 | Core |
| `agent/commands.py` | 131 | `run_command` 的三态策略：auto / confirm / deny | Core |
| `agent/permissions.py` | 30 | 连续被拒的计数与熔断信号 | Core |
| `agent/compression.py` | 57 | 把过大的工具输出压到 1800 token 内 | Core |
| `tools/ToolDispatcher.java` | 127 | 收到 `tool/invoke`，选工具、排队、执行、回传 | Shell |
| `tools/ToolResult.java` | 15 | `(ok, content, error)` 三元组 | Shell |
| `tools/FileTools.java` | 117 | `read_file` / `list_dir` + 路径越界防护 | Shell |
| `tools/FileText.java` | 154 | 四级编码探测与按原编码写回 | Shell |
| `tools/EditTools.java` | 207 | 四个写类工具 + 落盘前 flush + VFS 刷新 | Shell |
| `tools/FileSnapshots.java` | 46 | 每个文件最近 5 份内存快照 | Shell |
| `tools/ProjectWriteGate.java` | 60 | 项目级公平写锁，跨会话串行 | Shell |
| `tools/SearchTools.java` | 114 | 正则文本搜索，带目录/扩展名黑名单 | Shell |
| `tools/CommandTools.java` | 182 | 起子进程、抓输出、超时强杀、三段截断 | Shell |
| `tools/GitTools.java` | 73 | `git_log` / `git_diff` | Shell |
| `tools/DiagnosticsTools.java` | 152 | 从 daemon 高亮里捞 error/warning | Shell |
| `ui/DiffConfirmCard.java` | 109 | 写文件前的模态 diff 确认框 | Shell |
| `ui/CommandConfirmCard.java` | 63 | 执行命令前的模态确认框 | Shell |

### 0.2 依赖方向

```
agent/loop.py ──┬─→ agent/scheduler.py ──→ tools/registry.py
                ├─→ agent/compression.py ─→ native/(tokenizer, codesoup)
                └─→ agent/permissions.py

rpc/server.py ──┬─→ tools/remote.py (SHELL_TOOLS / RemoteToolProxy)
                ├─→ agent/commands.py (decide_command)
                └─→ tools/registry.py

Shell:
ChatSessionPanel.java ──→ ToolDispatcher.java ──┬─→ FileTools ──→ FileText
                                                ├─→ EditTools ──┬─→ FileText
                                                │               ├─→ FileSnapshots
                                                │               └─→ DiffConfirmCard
                                                ├─→ SearchTools ─→ FileText
                                                ├─→ CommandTools ─┬─→ CommandConfirmCard
                                                │                 └─→ FileText
                                                ├─→ GitTools
                                                ├─→ DiagnosticsTools
                                                └─→ ProjectWriteGate（项目级 @Service）
```

**两侧没有共享代码，只有两份契约**：

1. **工具名清单**：`remote.py` 的 `SHELL_TOOLS`（11 个）必须与 `ToolDispatcher.dispatch()` 的 `switch`（11 个 `case`）一一对应。少一个 → Core 以为有这个工具，Shell 回「未知工具」；多一个 → 模型根本看不见它。
2. **错误文案前缀**：Core 的 `loop.py` 用 `error.startswith("未知工具")` 判断「模型编了工具名」。Shell 的 `default` 分支必须继续以「未知工具: 」开头，否则熔断逻辑失联（§16.2）。

### 0.3 本卷只关心三个问题

| 问题 | 答案落在 |
|---|---|
| 一次工具调用在两端之间跑了几趟？谁在等？ | §4、§6.3 |
| 「要不要问用户」这件事由谁决定？ | §2.4、§4.2、§6.2、§15 |
| 工具出错 / 超时 / 输出太大，分别由谁收拾？ | §5、§17 |

### 0.4 一条贯穿全卷的不变量

> **Core 决定「要不要问」，Shell 决定「怎么问」。**

`tool/invoke` 的 `permission` 字段是 Core 算出来的**生效权限**，Shell 只照做：`auto` 直接跑，`preview` 弹 diff，`confirm` 弹确认。唯一的例外是面板上的「自动执行」开关，它是**用户级**的覆盖，且只对文件写工具生效（§6.2 的真值表会把这件事说死）。

---

## 1. `tools/registry.py`（40 行）：两张字典

```python
class ToolRegistry:
    def __init__(self) -> None:
        self._defs: Dict[str, ToolDef] = {}
        self._handlers: Dict[str, ToolHandler] = {}
```

定义与处理函数**分开存**，这是本文件唯一的设计决策：模型看到的是 `_defs`（会被序列化成 JSON 塞进请求的 `tools` 字段），执行用的是 `_handlers`（Python 可调用对象）。

### 1.1 `register`（23-28）

```python
if tool_def.name in self._defs:
    LOGGER.warning("tool %s re-registered", tool_def.name)
self._defs[tool_def.name] = tool_def
if handler is not None:
    self._handlers[tool_def.name] = handler
```

三个细节，每个都会在运行时留下痕迹：

1. **重复注册只是 warning，不拒绝。** 后注册的**整个覆盖**前者。`_bind_project_root` 在拿到 IDE 项目根后会重新 `self.memory.register_tools(self.tools)`——于是日志里必然出现成串的 `tool memory_save re-registered`。这是**正常现象**，不是故障。
2. **`handler is None` 时不清空旧 handler。** 也就是说「只更新定义、保留实现」是可行的；反过来「想删掉实现」做不到（没有 `unregister`）。
3. **没有加锁。** `register` 全部发生在 `AgentServer.__init__` 与 `_bind_project_root`，而后者只在处理 `chat/start` 的协程里跑——事件循环单线程，天然互斥。一旦将来有人在 `executeOnPooledThread` 之类的线程里注册工具，这张字典就有并发写风险。

### 1.2 四个只读方法

| 方法 | 返回 | 调用方 |
|---|---|---|
| `get(name)` | `ToolDef` 或 `None` | `scheduler.run_one`、`_invoke_shell_tool` |
| `handler(name)` | 可调用对象或 `None` | `scheduler.run_one` |
| `defs()` | `list(self._defs.values())`，新列表 | `loop.run_chat` → 请求的 `tools` 字段 |
| `names()` | **排序后**的名字列表 | `unknown_tool_error` |

`defs()` 返回的是**新 list、旧对象**：`loop` 把它直接交给 provider 序列化，所以一轮请求内工具集是快照——即使执行到一半 MCP 刷新注册了新工具，本轮也看不见。

`names()` 排序这件事有副作用：**未知工具提示里的可用工具名是字母序的**，`ask_user` 会排在 `read_file` 前面。这跟「按重要性排序」的直觉相反，但对模型来说字母序更稳定（同样的工具集永远给同样的提示，便于命中缓存）。

### 1.3 两个「声明了但没人读」的字段

`protocol.ToolDef` 里有：

```python
max_concurrency: int = 4
remote: bool = True
```

- `max_concurrency`：**并发度由 `ChatConfig.tool_concurrency` 统一控制**，`scheduler` 只读 config，从不读每个工具自己的这个字段。想给某个工具单独限流，现在做不到。
- `remote`：只是「这是个要过 IDE 的工具」的标记，`scheduler` 的分派依据是 `handler is None`，不是它。真正的作用是给 MCP 测试用（`test_mcp.py` 断言 `self.assertFalse(tool_def.remote)`），以及读代码时区分两类工具。

> 🔎 这是典型的「预留字段」：声明在协议里，实现留在下一版。读代码时如果看到某个字段没有任何读取方，先怀疑它是预留，而不是以为有隐藏逻辑。

---

## 2. `tools/remote.py`（181 行）：11 个远程工具

### 2.1 `_schema`（15-16）

```python
def _schema(properties, required=None):
    return {"type": "object", "properties": properties, "required": required or []}
```

注意 `required or []`：传 `None` 与传 `[]` 等价，**不会**因为漏传而出现 `required: null`（某些严格的端点会因此 400）。

### 2.2 全表

| # | 工具名 | permission（声明值） | timeout | 必填参数 | 只读？ |
|---|---|---|---|---|---|
| 1 | `read_file` | `auto` | 15s | `path` | 是 |
| 2 | `list_dir` | `auto` | 15s | — | 是 |
| 3 | `search_code` | `auto` | 30s | `pattern` | 是 |
| 4 | `git_log` | `auto` | 30s | `start_date`、`end_date` | 是 |
| 5 | `git_diff` | `auto` | 30s | — | 是 |
| 6 | `write_file` | `preview` | 30s | `path`、`content` | 否 |
| 7 | `edit_file` | `preview` | 30s | `path`、`old_string`、`new_string` | 否 |
| 8 | `delete_file` | `confirm` | 30s | `path` | 否 |
| 9 | `revert_file` | `auto` | 15s | `path` | 否（但无对话框） |
| 10 | `diagnostics` | `auto` | 30s | — | 是 |
| 11 | `run_command` | `confirm`（**兜底值**） | 130s | `command` | 否 |

`timeout_sec` 的用法在 `scheduler.run_one`：

```python
result = await asyncio.wait_for(handler(call), timeout=tool_def.timeout_sec)
```

所以这列是 **Core 单方面等待的上限**，不是 Shell 的执行上限。两侧的超时并不相等（§12.5 会拿 `run_command` 算一次）。

`run_command` 的 130s 是刻意比 Shell 默认的 120s 多 10s：

- Core 等 130s；
- Shell 的 `CommandTools` 默认 120s 会把子进程杀掉、返回一条**成功**结果；
- 于是「超时」这件事在正常情况下由 Shell 包装成文本回传，Core 的 `wait_for` 只在进程卡死或 Shell 崩溃时才触发。

### 2.3 三处「描述与实现不一致」

工具描述是**给模型看的**，也是最容易与代码腐化的地方。逐条对过：

| 工具 | 描述怎么写 | 实现实际怎么做 | 后果 |
|---|---|---|---|
| `read_file` | 「超大文件会截断」 | `size > 512KB` 时 **直接 fail**（`FileTools.readFile`），不做任何截断 | 模型会以为能拿到部分内容，实际拿到一条错误；正确做法是带 `start_line/end_line` 重试 |
| `diagnostics` | 「最多 50 条」 | 确实 `MAX_ITEMS=50`，但**只覆盖已加载文档** | 没打开过的文件里有多少错误，它一律不知道 |
| `write_file` | 「执行前会向用户展示 diff 确认」 | 面板勾了「自动执行」时**不弹**（`autoAccept` 覆盖 preview） | 用户以为「总要确认」，实际可以静默写 |

描述没法「自动测试」，只能靠人读。**这是读代码时值得专门做一次对照检查的地方**——模型的所有行为都由这些字符串决定。

### 2.4 `permission` 的真实语义

`Permission` 只有三个值（`protocol.py` 29-32）：

```python
class Permission(str, Enum):
    AUTO = "auto"
    PREVIEW = "preview"
    CONFIRM = "confirm"
```

三者的差别**完全在 Shell 侧**实现，Core 只透传字符串。`_invoke_shell_tool` 里是这样取值的：

```python
permission = tool_def.permission.value if tool_def else Permission.AUTO.value
```

→ **找不到定义时默认 `auto`**。这是个宽松兜底：理论上任何「注册了但定义丢了」的情况都会变成免确认执行。实际不会发生（`tools.get(call.name)` 在 `scheduler` 里已经判过非空），但读到这里应该记住这个方向——**失败时倾向于放行**。

`run_command` 的 `confirm` 是兜底值，因为：

```python
if call.name == "run_command":
    decision = decide_command(command, self.config.command_allowlist)
    if decision == "deny": ...
    permission = decision          # auto / confirm，覆盖定义里的值
```

于是**同一个工具每次调用的权限都可能不同**：`mvn -q test` 是 `auto`，`python scripts/deploy.py` 是 `confirm`，`rm -rf .` 连 Shell 都到不了（Core 直接返回错误）。

### 2.5 `RemoteToolProxy`（174-181）

```python
class RemoteToolProxy:
    def __init__(self, invoke: InvokeFn) -> None:
        self._invoke = invoke

    async def __call__(self, call: ToolCall) -> ToolResult:
        return await self._invoke(call)
```

三个要点：

1. **一个实例服务 11 个工具**（`_register_shell_tools` 里 `proxy = RemoteToolProxy(self._invoke_shell_tool)` 然后循环 `register(tool_def, proxy)`）。所以代理本身**无法从 `self` 知道自己在跑哪个工具**，工具身份只在 `call.name` 里。
2. `__call__` 让实例变成「可调用的处理函数」，满足 `ToolHandler = Callable[[ToolCall], Awaitable[ToolResult]]`。这是最省事的适配方式，不需要 `functools.partial`。
3. 它**不做任何错误包裹**：`_invoke_shell_tool` 抛什么就往上抛什么。异常最终由 `scheduler.run_one` 的 `except Exception` 兜住，变成 `ok=False, error=str(exc)`。

---

## 3. 注册全景：Core 里到底有多少个工具

`AgentServer.__init__`（`server.py` 100-125）按固定顺序注册：

| 来源 | 工具名 | 执行位置 |
|---|---|---|
| `_register_shell_tools()` | `read_file`、`list_dir`、`search_code`、`git_log`、`git_diff`、`write_file`、`edit_file`、`delete_file`、`revert_file`、`diagnostics`、`run_command` | **IDE Shell** |
| `todo_tools.register_tools` | `todo_write` | Core |
| `ask_tools.register_tools` | `ask_user` | Core（经 Shell 渲染卡片） |
| `memory.register_tools` | `memory_save`、`memory_recall`、`memory_delete` | Core |
| `index.register_tools` | `semantic_search`、`build_index` | Core |
| `skills.register_tools` | `skill_list`、`skill_load` | Core |
| `subagents.register_tools` | `subagent_dispatch`、`subagent_list` | Core（内部再起子循环） |
| MCP 管理器 | 动态，数量取决于配置的 server | Core（MCP 客户端） |

**固定的 22 个 + N 个 MCP 工具**。本卷只讲前 11 个（Shell 侧），其余在 07 卷逐组展开；`ask_user` 与 `todo_write` 因为和调度强相关，会在 §5.4 提前用到。

> 🔎 `ask_user` 的 `timeout_sec=1800.0`（30 分钟）。它不是「工具慢」，而是**应该慢**：这是唯一一个人类在环里的工具，超时设短了会把等人回答当成故障。

---

## 4. 一次工具调用的线上时序

这是全卷最该背下来的一张图。以 `edit_file` 为例，正常路径 8 步：

```
① loop.py           scheduler.run_parallel([call]) 
② scheduler         run_one → handler(call) == RemoteToolProxy.__call__
③ remote.py         await invoke(call) → server._invoke_shell_tool(call)
④ server.py         permission="preview" → create future → _pending_tools[call.id] = future
                    → _send_notification("tool/invoke", {taskId, toolCallId, toolName, params, permission})
                    → await future            ← 协程在这里挂起
⑤ Shell             JsonRpcConnection.dispatch → notificationHandlers → ChatSessionPanel.onCoreMessage
                    case "tool/invoke" → toolDispatcher.onToolInvoke(params, conn, title, notice)
⑥ ToolDispatcher    executeOnPooledThread { writeGate.acquire → dispatch → EditTools.editFile
                                            → connection.request("tool/result", payload) }
⑦ server.py         _dispatch → _handle_request("tool/result") → _tool_result
                    → _pending_tools.pop(id) → future.set_result(result) → 回 {"received": true}
⑧ server.py         ③ 处 await future 返回 → ToolResult 一路回到 scheduler → loop 写入 TOOL 消息
```

### 4.1 这条链路里只有一处「阻塞」

第 ⑥ 步的 `executeOnPooledThread` 内部是**真阻塞**：`DiffConfirmCard.confirm` 会 `invokeAndWait` 到 EDT 弹模态框，等用户点按钮；`CommandTools.execute` 会 `process.waitFor(120, SECONDS)`。所以整条链路里唯一的阻塞发生在 Shell 的后台线程池上，**Core 侧全程是协程挂起**——一个会话在等用户点「确认」时，其他会话的 token 流照常跑。

### 4.2 一次调用会跑几趟

| 方向 | 报文 | 有没有 `id` | 是否等待回复 |
|---|---|---|---|
| Core → Shell | `tool/invoke`（通知） | **没有** | — |
| Shell → Core | `tool/result`（请求） | 有 | **不等**（返回的 `CompletableFuture` 被丢弃） |
| Core → Shell | `{"received": true}`（响应） | 有 | — |

`ToolDispatcher` 的最后一行是：

```java
connection.request("tool/result", payload);
```

返回值被直接丢掉。这个选择是对的（结果已经交出去了，等回执没有意义），但有一个副作用要看 `JsonRpcConnection.request`：

```java
String id = UUID.randomUUID().toString();
CompletableFuture<JsonObject> future = new CompletableFuture<>();
pending.put(id, future);
```

`pending` 只在**收到响应**或**连接关闭**时被清理。而这里丢弃了 future → **每次工具调用都会在 `pending` 里留下一对（UUID, 未完成的 future）**，直到连接断开。一次长会话跑几千次工具调用就是几千个残留条目，量级不大但无上界。修法很简单（`request(...).orTimeout(...)` 或者干脆提供一个 `notify` 风格的 `requestNoReply`），记在这里当**待改进项**。

### 4.3 Shell 侧「静默丢包」的两个入口

```java
case "tool/invoke": {
    JsonRpcConnection conn = coreService.connectionOrNull();
    if (conn != null) {
        toolDispatcher.onToolInvoke(...);
    }
    break;
}
```

- **`conn == null`**（Core 进程刚好重启）→ 通知被丢弃，Core 侧没有任何回调，只能等 `timeout_sec` 到期由 `wait_for` 报「工具执行超时」。
- **`toolCallId` 为空**（报文异常）→ `_tool_result` 里 `_pending_tools.pop("")` 取不到 future，只回 `{"received": true}`，同样只能等超时。

两处都没有「立刻告诉 Core 我这边坏了」的路径。这是有意的简化：**工具协议没有「拒绝受理」这一档**，只有「结果」与「超时」。

### 4.4 取消时会发生什么

用户在 Shell 点「停止」→ `chat/cancel` → `task.cancel()` → 正在 `await future` 的 `_invoke_shell_tool` 抛 `CancelledError`：

```python
try:
    return await future
finally:
    self._pending_tools.pop(call.id, None)
```

future 被摘掉，`scheduler.run_one` 里的 `except asyncio.CancelledError: raise` 让取消继续上抛。**但 Shell 那边的线程池任务不会停**——`ProcessBuilder` 起的子进程、已经打开的 diff 弹窗都还在。等它跑完发 `tool/result` 时，Core 已经把 future 删了，这条结果就静静地消失了。

---

## 5. `agent/scheduler.py`（108 行）：并发、超时、熔断

### 5.1 两个模块级常量

```python
MAX_NAME_HINTS = 12
INTERACTIVE_TOOLS = frozenset({"ask_user"})
```

`MAX_NAME_HINTS=12` 是提示长度的取舍：工具少的时候全列（22 个里的前 12 个），多的时候 `", ".join(names[:12]) + " 等 22 个"`。为什么不是全列？因为这段文本会**进入下一轮请求的上下文并被反复携带**，列全 22 个的收益（模型本来就知道有哪些工具，`tools` 字段里就有）远低于每轮多付的 token。

### 5.2 `unknown_tool_error`（31-43）

```python
def unknown_tool_error(registry, name):
    names = registry.names()
    hint = ", ".join(names[:MAX_NAME_HINTS])
    if len(names) > MAX_NAME_HINTS:
        hint += " 等 %d 个" % len(names)
    return "未知工具: %s。可用工具: %s" % (name, hint or "无")
```

docstring 里写了一段真实故障：

> 只报「未知工具: bash」等于让模型盲猜；把真实工具名列出来，它才能一次改对。

配套的测试在 `test_unknown_tool.py`：它用一个 `StubbornProvider` 每轮都返回 `bash`，验证（a）错误里带真实名字、（b）连续 3 次（`UNKNOWN_TOOL_LIMIT`）后整轮停止。

`hint or "无"` 这个兜底是为**空注册表**准备的：`names()` 返回 `[]` 时 `join` 得到空串，提示会变成残缺的「可用工具: 」，加个「无」才读得通。

### 5.3 `run_parallel`（51-108）逐段

```python
semaphore = asyncio.Semaphore(self.max_concurrency)

async def run_one(call: ToolCall) -> ToolResult:
    tool_def = self.registry.get(call.name)
    if tool_def is None:
        return ToolResult(tool_call_id=call.id, ok=False,
                          error=unknown_tool_error(self.registry, call.name))
    handler = self.registry.handler(call.name)
    if handler is None:
        return ToolResult(tool_call_id=call.id, ok=False, error="工具未实现: " + call.name)

    async with semaphore:
        if on_start is not None:
            await on_start(call)
        try:
            result = await asyncio.wait_for(handler(call), timeout=tool_def.timeout_sec)
        except asyncio.TimeoutError:
            result = ToolResult(tool_call_id=call.id, ok=False, error="工具执行超时")
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            LOGGER.exception("tool %s failed", call.name)
            result = ToolResult(tool_call_id=call.id, ok=False, error=str(exc))

    if on_end is not None:
        await on_end(call, result)
    return result
```

逐点拆：

1. **两个「查不到」都在拿信号量之前返回**——未知工具不占并发位。否则模型连发 8 个 `bash` 会把真正的工具挤在外面等。
2. **`unknown_tool_error` 与 `"工具未实现"` 是两种不同的错**：前者是模型编了名字，后者是**注册表里有定义但没有 handler**（开发期半成品）。文案不同，`loop` 的熔断只看前者。
3. **`on_start` 在信号量内**：`chat/toolStart` 通知要等真正轮到自己才发。所以 Shell 上看到的开始行顺序 = 实际开始执行的顺序，而不是模型给出的顺序（IntelliJ 是 `read_file · src/…`，VS Code 仍为 `[工具] read_file 执行中...`）。
4. **超时只影响这一支**：`wait_for` 取消的是这个 handler 的协程，同批其他工具照常跑（`test_scheduler.test_timeout_is_isolated` 钉住了这条：slow 超时、fast 成功）。
5. **`CancelledError` 必须重抛**：它是 `BaseException` 的子类（3.8+），本来不会被 `except Exception` 捕获，这里显式写出来是为了**防止将来有人把 `except Exception` 改成 `except BaseException`**。语义上「用户取消」不是「工具失败」，不能吞成一条错误结果。
6. **`except Exception` 标了 `pragma: no cover`**：测试不覆盖这条，因为正常路径不该走到。`str(exc)` 直接进 `error`，意味着**任何异常类型的文本都会原样给模型看**（包括 Python 堆栈消息片段）。
7. **`on_end` 在信号量外**：结果已经出来了，回调慢一点不该占着并发位。

### 5.4 交互式工具的独占（94-102）

```python
interactive = [call for call in calls if call.name in INTERACTIVE_TOOLS]
rest = [call for call in calls if call.name not in INTERACTIVE_TOOLS]

for call in interactive:
    store([call], await run_batch([call]))
if rest:
    store(rest, await run_batch(rest))
```

注释把原因说得很清楚：

> 需要等人回答的工具。它们独占一轮：否则用户还在选，文件已经被改了。

即：模型在同一轮里既 `ask_user` 又 `edit_file` 时，**先问、拿到答案、再改**。如果反过来并行，用户看到的卡片描述的是「改之前的代码」，等他点完，代码已经变了。

注意 `interactive` 是**逐个串行**的（`for call in interactive` 每次 `await` 一个）。同一轮里两个 `ask_user` 会依次弹两张卡，而不是并排弹——这也是刻意的，避免同一屏出现两个等待中的问题。

### 5.5 结果回填的顺序契约（104-108）

```python
return [results[call.id] if call.id in results
        else ToolResult(tool_call_id=call.id, ok=False, error="工具未执行")
        for call in calls]
```

- 返回列表**按入参顺序**，不按完成顺序。`loop` 用它 `zip(result.tool_calls, tool_results)` 逐条写 TOOL 消息——顺序错了，`tool_call_id` 就会串。
- `results` 是 `dict[id, ToolResult]`，所以**同一个 id 出现两次会被后者覆盖**（模型极少数情况会复用 id）。
- `"工具未执行"` 是兜底：理论上走不到（每支都会写 `results`），但 `asyncio.gather` 里某支若被意外取消到没有返回值，这里保证列表长度与 `calls` 一致。

> ⚠️ `gather` 默认 `return_exceptions=False`：某一支抛出未捕获异常时，`gather` 会**立刻抛给调用方**，其他支的结果丢弃。这里之所以安全，是因为 `run_one` 内部已经把 `Exception` 全兜住了，唯一能逃出来的是 `CancelledError`（整轮取消），那本来就该中断。

---

## 6. `ToolDispatcher.java`（128 行）：Shell 侧的总入口

### 6.1 一个 dispatcher 属于一个会话

```java
private final ProjectWriteGate writeGate;   // 项目级 @Service
private final FileTools fileTools;
private final SearchTools searchTools;
private final GitTools gitTools;
private final EditTools editTools;          // ← 内含 FileSnapshots
private final CommandTools commandTools;
private final DiagnosticsTools diagnosticsTools;
```

构造点在 `ChatSessionPanel.java:110`：

```java
this.toolDispatcher = new ToolDispatcher(project);
```

于是**每个聊天标签页一个 dispatcher**，进而：

| 状态 | 作用域 | 后果 |
|---|---|---|
| `EditTools.snapshots` | **会话**（每个 dispatcher 一份） | 会话 A 改的文件，会话 B 的 `revert_file` 找不到快照 |
| `ProjectWriteGate` | **项目**（`@Service(PROJECT)`） | 所有会话的写操作共用一个锁 |

**这个不对称是有意的**：撤销只应该撤销「我这个对话刚才做的事」，而写冲突必须跨会话解决。但它有个副作用——用户会在 B 会话里看到「没有可回退的快照」，而文件明明刚被 Agent 改过（是另一个会话改的）。

### 6.2 `needsApproval` 真值表

```java
boolean autoAccept = ReportSettingsState.getInstance().autoAcceptEdits;
boolean writeOp = WRITE_TOOLS.contains(toolName);
boolean needsApproval = !"auto".equals(permission) && !(writeOp && autoAccept);
```

`WRITE_TOOLS = {write_file, edit_file, delete_file, revert_file}`。把 3 × 2 × 2 种组合铺开：

| 工具 | 声明 permission | `autoAccept` | `writeOp` | **实际是否弹窗** |
|---|---|---|---|---|
| `read_file` / `list_dir` / `search_code` / `git_log` / `git_diff` / `diagnostics` | `auto` | 任意 | false | 否 |
| `run_command`（白名单内） | `auto`（被改写） | 任意 | false | 否 |
| `run_command`（其余） | `confirm`（被改写） | **true** | false | **是** |
| `revert_file` | `auto` | 任意 | true | 否 |
| `write_file` / `edit_file` | `preview` | false | true | 是（diff） |
| `write_file` / `edit_file` | `preview` | true | true | **否** |
| `delete_file` | `confirm` | false | true | 是（diff，空内容） |
| `delete_file` | `confirm` | **true** | true | **否** |

三条结论：

1. **「自动执行」只对文件写操作生效。** 注释写得很明确：「终端命令始终按 Core 本次下发的权限处理，不会因自动接受而免确认。」
2. **`delete_file` 会被「自动执行」一起免掉。** 它声明的是 `confirm`，但 `WRITE_TOOLS` 包含它、`autoAccept` 一开就整类跳过。也就是说：**用户勾了「自动执行」，Agent 删文件不再问**。这是全卷最该记住的一个风险点。
3. **`permission` 字段缺失时默认 `auto`**（`params.has("permission") ? ... : "auto"`）。报文异常 → 免确认执行，失败方向同样是「放行」。

### 6.3 `executeOnPooledThread` 里的顺序

```java
ApplicationManager.getApplication().executeOnPooledThread(() -> {
    if (writeOp) {
        String waitedFor = writeGate.acquire(label);
        if (waitedFor != null && notice != null) {
            notice.accept("queued · waiting for session " + waitedFor + " · " + toolName);
        }
    }
    ToolResult result;
    try {
        result = dispatch(toolName, args, needsApproval);
    } catch (Exception e) {
        LOG.warn("tool " + toolName + " failed", e);
        result = ToolResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
    } finally {
        if (writeOp) {
            writeGate.release();
        }
    }
    ...
    connection.request("tool/result", payload);
});
```

六个要点：

1. **为什么必须换线程**：`DiffConfirmCard.confirm` 用 `invokeAndWait` 切到 EDT 弹模态框；如果在 EDT 上调用 `invokeAndWait`，会直接死锁（EDT 等 EDT）。所以 dispatch 的**唯一正确调用上下文**就是后台线程。
2. **`acquire` 没有超时**：如果前一个会话卡在一个没人关的 diff 弹窗上，后续所有写操作无限等待。
3. **排队只提示不中断**：`notice` 把 `queued · waiting for session 2 · edit_file` 写进对话区
   （工具时间线的英文日志风格）。它是一行普通步骤（`running=false`），不参与状态嗅探。
4. **`release` 在 `finally`**：`dispatch` 抛异常也不会漏放锁。
5. **异常兜底只保留 message**：`ToolResult.fail(e.getMessage())`，堆栈只进日志（`LOG.warn`）。所以模型看到的错误往往是一句没有上下文的 Java 消息——这也是各工具为什么都自己 `try/catch IOException` 并拼中文的原因。
6. **`label` 兜底**：`sessionLabel` 为空时统一叫 `session`，于是排队提示里可能两行都是同一个名字。

### 6.4 `dispatch`（100-127）：11 个 case 与一处字符串契约

```java
switch (toolName) {
    case "read_file":     return fileTools.readFile(args);
    case "list_dir":      return fileTools.listDir(args);
    case "search_code":   return searchTools.searchCode(args);
    case "git_log":       return gitTools.log(args);
    case "git_diff":      return gitTools.diff(args);
    case "write_file":    return editTools.writeFile(args, needsApproval);
    case "edit_file":     return editTools.editFile(args, needsApproval);
    case "delete_file":   return editTools.deleteFile(args, needsApproval);
    case "revert_file":   return editTools.revertFile(args);
    case "run_command":   return commandTools.runCommand(args, needsApproval);
    case "diagnostics":   return diagnosticsTools.diagnostics(args);
    default:              return ToolResult.fail("未知工具: " + toolName);
}
```

**跨端契约**：`default` 必须以「未知工具: 」开头，因为 `loop.py` 是这样判的：

```python
if not tool_result.ok and (tool_result.error or "").startswith("未知工具"):
    unknown_tools[call.name] += 1
```

而 Core 自己的 `unknown_tool_error` 输出的是「未知工具: bash。可用工具: ...」。两处前缀一致 → 同一个熔断计数器同时管「Core 不认识的工具」和「Shell 不认识的工具」。改文案时**必须两端一起改**，否则 Shell 侧的未知工具永远不会触发熔断。

注意 `revert_file` 拿不到 `needsApproval`：它**永远不需要确认**（撤销本身是降低风险的操作），但也因此可以被模型用来「悄悄把文件改回去」。

### 6.5 回传报文

```java
Map<String, Object> payload = new HashMap<>();
payload.put("toolCallId", toolCallId);
payload.put("ok", result.ok());
payload.put("result", result.content());
payload.put("error", result.error());
```

字段名与 `_tool_result` 一一对应（`result` → `content`，不是 `content`）。`null` 值会被 Gson 序列化成 `null` 而不是省略，Core 侧 `params.get("result")` 得到 `None` —— `ToolResult(content=None)` 是合法的，`loop` 里 `tool_result.content or ""` 会兜住。

---

## 7. `ProjectWriteGate.java`（59 行）：项目级公平锁

```java
@Service(Service.Level.PROJECT)
public final class ProjectWriteGate {
    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile String owner;
    private volatile int waiting;
```

### 7.1 为什么需要它

类注释给出了两条理由，都是**用户可见**的问题：

1. 「两个 Agent 同时改同一项目会互相覆盖」——并行会话各自 `FileText.write`，后写的赢，前一个的 diff 已经确认过却失效了。
2. 「多个 diff 弹窗也无法分辨来源」——两个模态框同时弹，用户不知道该点哪个。

粒度选择：**只锁写**。读和搜仍然完全并行，因为 `read_file` 不会改变任何状态。

### 7.2 `ReentrantLock(true)` 的公平性

公平锁保证等待者按 FIFO 拿锁，避免「后到的会话连续抢到锁、前一个会话饿死」。代价是吞吐略低 + 每次 `lock()` 可能有一次线程切换，对「几秒一次写」这个量级完全无所谓。

### 7.3 `acquire` 的三条路径

```java
public @Nullable String acquire(@NotNull String who) {
    if (lock.tryLock()) {
        owner = who;
        return null;                     // ① 空闲：直接拿到，不用提示
    }
    String previous = owner;             // ② 有人在写：记下名字
    waiting++;
    try {
        lock.lock();
    } finally {
        waiting--;
    }
    owner = who;
    return previous == null ? "另一个会话" : previous;
}
```

- 返回值语义：**`null` = 没等**，非 `null` = 等过、这是挡在前面的会话名。
- `previous` 在读的时候可能已被前一个持有者清成 `null`（见下），于是退化成「另一个会话」。
- `waiting` 只增只减、**没有任何读取方**（`waiting()` 方法也没人调）。它是为将来「在 UI 上显示排队人数」留的钩子。

### 7.4 `release` 的一个小竞态

```java
public void release() {
    owner = null;
    lock.unlock();
}
```

先清 `owner` 再解锁。在这个极短的窗口里：

- 另一个线程 `tryLock()` 失败 → `previous = null` → 排队提示变成「另一个会话」；
- `owner()` 会返回 `null`，尽管锁还被持有。

两者都只影响**提示文案**，不影响正确性。想消掉也简单（先 unlock 再清 owner，或用一个不可变的持有者对象），但收益极低。

---

## 8. `FileTools.java`（116 行）+ `FileText.java`（153 行）

这一对是「读」的基础设施。`FileTools` 管路径与行号，`FileText` 管字节与字符。

### 8.1 `resolve`（93-104）：唯一的越界防线

```java
public Path resolve(String rawPath) {
    Path base = projectRoot();
    if (base == null || rawPath == null || rawPath.isBlank()) {
        return null;
    }
    Path candidate = Paths.get(rawPath);
    if (!candidate.isAbsolute()) {
        candidate = base.resolve(rawPath);
    }
    candidate = candidate.normalize();
    return candidate.startsWith(base) ? candidate : null;
}
```

逐点：

1. `normalize()` 消掉 `..` 与 `.`，所以 `src/../../etc/passwd` 会被压成 `/etc/passwd` 并被 `startsWith` 拦下。
2. **`startsWith` 是路径段比较**，不是字符串前缀：`/proj2` 不会因为 `/proj` 是前缀而被放行。
3. **不解析符号链接 / Windows junction**。项目里放一个指向 `C:\` 的目录链接，`resolve("link/secret.txt")` 会通过检查，然后真的读到项目外。这是个**真实边界缺口**，只是默认场景下没人这么干。
4. `Paths.get` 遇到非法字符会抛 `InvalidPathException`（运行时异常）——最终由 `ToolDispatcher` 的 `catch (Exception)` 兜成一条 `fail`，文案是 JDK 给的英文。
5. 所有工具都走它：`read_file`、`list_dir`、`search_code`、`write_file`、`edit_file`、`delete_file`、`revert_file`、`diagnostics`、`run_command` 的 `cwd`。**一处防线，九处受益**。

### 8.2 `readFile`（28-69）

顺序很重要：先查大小，再解码，再切行。

```java
long size = Files.size(path);
if (size > MAX_FILE_BYTES) {                       // 512KB
    return ToolResult.fail("文件过大（" + size + " 字节），请用 start_line/end_line 指定范围");
}
```

⚠️ **这条建议是错的**：大小检查在读文件**之前**，带了 `start_line/end_line` 也照样被拒。模型照提示重试会一直失败。真要读大文件，只能换策略（`search_code` 定位、或 `run_command` 跑 `more`/`head`）。

```java
int start = args.has("start_line") ? Math.max(1, args.get("start_line").getAsInt()) : 1;
int end = args.has("end_line")
        ? Math.min(lines.size(), args.get("end_line").getAsInt())
        : lines.size();
if (start > lines.size() || start > end) {
    return ToolResult.ok("（无匹配行；文件共 " + lines.size() + " 行）");
}
```

- `start` 用 `max(1, ...)` 夹下界，`end` 用 `min(size, ...)` 夹上界 → **不是错误，是静默夹紧**。`start_line=100, end_line=200` 在 50 行的文件里返回「无匹配行」而不是报错。
- `end < start`（例如 `end_line=0`）同样落到「无匹配行」。
- 返回值是 `ok`，模型会把它当成事实而不是错误。

输出格式：

```java
for (int i = start; i <= end; i++) {
    sb.append(i).append('→').append(lines.get(i - 1)).append('\n');
}
if (!decoded.isPlainUtf8()) {
    sb.append("（文件编码 " + decoded.label() + "，已按原编码解码）\n");
}
```

**行号用 Unicode 箭头 `→`（U+2192）**，不是 `->`。这个字符一路被下游依赖：`compression.strip_line_numbers` 的正则是 `^\s*\d+\u2192`，剥不掉前缀就没法做结构压缩。换字符 = 悄悄停掉 read_file 的压缩能力。

末尾总会多一个 `\n`（哪怕只读一行）。`splitLines` 的语义是「结尾换行不多出一行」，所以 `read_file` 的 `N→` 行数 == 文件行数，两边一致。

### 8.3 `listDir`（71-89）

```java
try (Stream<Path> stream = Files.list(dir)) {
    List<String> entries = new ArrayList<>();
    stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
            .limit(MAX_LIST_ENTRIES)                 // 300
            .forEach(p -> entries.add(
                    Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString()));
    return ToolResult.ok(entries.isEmpty() ? "（空目录）" : String.join("\n", entries));
}
```

- 先排序再 `limit(300)`：截掉的是**字母序靠后**的条目，且**没有任何截断标记**。目录里有 400 个文件时，模型会以为只有 300 个。
- 目录加 `/` 后缀，靠这一个字符区分文件与目录——没有 `isDirectory` 字段。
- 不递归、不显示大小与时间。想知道更深的结构，只能继续 `list_dir`。

### 8.4 `FileText.decode`（61-86）：四级探测

```java
if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) → UTF-8 (BOM)
if (startsWith(bytes, 0xFF, 0xFE))         → UTF-16LE (BOM)
if (startsWith(bytes, 0xFE, 0xFF))         → UTF-16BE (BOM)
if (decodeStrict(bytes, UTF_8) != null)    → UTF-8
if (decodeStrict(bytes, GBK) != null)      → GBK
else                                       → 有损 UTF-8（替换非法序列）
```

四个设计判断：

1. **BOM 优先于一切。** 带 BOM 的 UTF-8 如果按严格解码也能过，但会被判成「无 BOM 的普通 UTF-8」，写回时就**丢了 BOM**——某些 Windows 工具（旧版 VS、部分 `.bat`）会因为丢 BOM 而乱码。所以 BOM 必须先判。
2. **`decodeStrict` 用 `REPORT` 而不是 `REPLACE`。** 这是「探测」能成立的前提：只有解码器**报错**，才知道这个编码不对。`onMalformedInput(REPORT)` + `onUnmappableCharacter(REPORT)` 两个都要设，漏一个就会有非法序列被静默替换。
3. **GBK 在 UTF-8 之后。** 反过来不行：GBK 的字节几乎总能「解」成某个字符（它是双字节编码，容错率高），先试 GBK 会把真正的 UTF-8 中文解成乱码。
4. **最后一道是有损 UTF-8**，理由是「保留可读部分，而不是让整次读取失败」。二进制文件被误读时，你会看到一堆 `\uFFFD`——这是**正常现象**，不是 bug。

`GBK = Charset.isSupported("GBK") ? Charset.forName("GBK") : null`：极少数精简 JRE 不带 GBK，此时退化为「UTF-8 或乱码」。

### 8.5 `FileText.write`（90-107）：按原编码写回

```java
Charset charset = original == null ? StandardCharsets.UTF_8 : original.charset;
boolean bom = original != null && original.bom;
byte[] body = text.getBytes(charset);
if (!bom) { Files.write(path, body); return; }
byte[] head = UTF_16BE ? FE FF : UTF_16LE ? FF FE : EF BB BF;
```

- `original == null` 的含义是「文件原本不存在」→ **新文件用 UTF-8 无 BOM**。
- 写回时**重建 BOM**，且按 charset 选对应的 BOM 字节序。
- 注意 `text.getBytes(GBK)` 对不可映射字符会变成 `?`——**有损**。理论上模型写入一个 GBK 表达不了的字符（如 emoji），该字符会静默变成问号。`Files.write` 不报错，工具返回「已写入」。

### 8.6 `splitLines`（111-129）：与 `Files.readAllLines` 对齐

```java
if (start < text.length()) {
    lines.add(text.substring(start));
}
```

这一句是整个函数的重点：`"a\nb\n"` → `[a, b]`（2 行），而不是 `[a, b, ""]`（3 行）。如果这里多出一行空行，`read_file` 的 `N→` 行号就会比编辑器里多 1，模型的「第 42 行」就永远差一行。

`\r\n` 与 `\r` 都算换行，且 `\r\n` 只消耗一次（`i++`）。

> 🔎 `FileTools.readDecoded`（108-110）**没有任何调用方**——`EditTools` 自己写了 `readDecodedOrNull`。属于同类里的冗余方法。

---

## 9. `EditTools.java`（206 行）：四个写工具

### 9.1 统一骨架

四个方法的共同顺序（以 `writeFile` 为例）：

```
校验参数 → resolve 路径 → flushDocuments() → 解码读旧内容
        → [确认] → snapshots.capture(旧内容) → 写 → refreshVfs → 返回文案
```

三个「顺序即正确性」的点：

1. **`flushDocuments()` 在读旧内容之前**：

```java
ApplicationManager.getApplication().invokeAndWait(
        () -> FileDocumentManager.getInstance().saveAllDocuments());
```

把用户**未保存**的编辑器内容先落盘，否则 Agent 读到的是磁盘上的旧版本，diff 会把用户刚打的字全部算成「删除」。注意是 `saveAllDocuments()`——**会保存整个项目所有脏文档**，不只是目标文件。副作用：用户以为自己没保存的草稿被写盘了。

2. **确认在快照之前**：用户拒绝时直接 `return ToolResult.fail("用户拒绝：未修改 X")`，**不留快照**。如果反过来，一次被拒的编辑会让 `revert_file` 有机会「回退到完全相同的状态」，白占一个快照位（每文件只有 5 个）。

3. **写后 `refreshVfs`**：

```java
ApplicationManager.getApplication().invokeAndWait(() -> {
    VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile());
    if (file != null) { file.refresh(false, false); }
});
```

不走这一步，VFS 里还是旧内容，编辑器/PSI 不会重新解析——紧接着调 `diagnostics` 会看不到新错误。`refresh(false, false)` = 非递归、不异步。

### 9.2 `writeFile`（30-61）

- 缺参报「缺少参数 path / content」——两个参数合成一句，模型可能只补一个。
- `Files.createDirectories(path.getParent())`：**允许新建多级目录**。这意味着模型可以用 `write_file` 造出 `a/b/c/d.txt`。
- 返回文案里带 `newContent.length()`（**字符数**，不是字节数）与编码注解：`已写入 src/X.java（1024 字符，编码 GBK）`。
- 新建文件时 `previous == null` → `oldContent = ""` → diff 卡片顶部显示 `+（新文件）`；快照里记的是**空串**。于是 `revert_file` 对一个新建的文件会把它**写成空文件**，而不是删除它。

### 9.3 `editFile`（63-110）：三处拒绝与一个换行陷阱

```java
if (oldText.isEmpty()) return fail("old_string 不能为空");
...
int first = oldContent.indexOf(oldText);
if (first < 0) return fail("未找到 old_string，请先用 read_file 确认原文（注意缩进与换行）");
if (!replaceAll && oldContent.indexOf(oldText, first + 1) >= 0)
    return fail("old_string 匹配到多处，请提供更长的上下文或设置 replace_all=true");
```

- **空 `old_string` 被拒**：否则 `indexOf("")` 返回 0，会在文件头插入内容。
- **多处匹配被拒**（`replace_all=false` 时）：这是防止「想改一处结果改了十处」。错误文案直接给出了两条出路。
- 替换是**字面量** `String.replace`，不是正则。`$1`、`\n` 都不会被解释。
- 成功文案里那个括号拼接看着别扭但没错：`(replaceAll ? "（替换全部匹配" : "（1 处")` + `(isPlainUtf8 ? "）" : "，编码 X）")`。

⚠️ **换行陷阱（实战最容易踩）**：`read_file` 输出的行永远以 `\n` 连接，而 `editFile` 在**解码后的原始文本**上做 `indexOf`。文件是 CRLF 时：

| 模型手里的 `old_string` | 文件真实内容 | `indexOf` |
|---|---|---|
| `"int a = 1;\nint b = 2;"`（从 read_file 抄的） | `"int a = 1;\r\nint b = 2;"` | **-1 → 未找到** |
| `"int a = 1;"`（单行，不含换行） | 同上 | 命中 |

所以「未找到 old_string」在 CRLF 仓库里极为常见，而错误提示里的「注意缩进与换行」正是冲着这个来的。规避方式：`old_string` 尽量**不含换行**，或用 `replace_all=true` 让模型一次改完。

### 9.4 `deleteFile`（112-138）

```java
if (needsApproval && !DiffConfirmCard.confirm(project, path, oldContent, "")) {
    return ToolResult.fail("用户拒绝：未删除 " + relative(path));
}
snapshots.capture(path, oldContent);
Files.delete(path);   // 不是 deleteIfExists
```

- 确认框复用 **diff 卡片**，`after` 传空串 → 卡片显示 `-（删除文件）` + 全部旧行（截到 800 行）。
- `Files.delete` 在文件已被外部删除时抛 `NoSuchFileException` → 返回「删除失败: ...」。幂等性不如 `deleteIfExists`。
- 快照在删除前拍下，所以 `revert_file` **能恢复被删文件的内容**（但恢复的是文件，不是它的权限/时间戳）。
- 成功文案特意写了「（可用 revert_file 恢复）」——这是在**给模型提示下一步**。

### 9.5 `revertFile`（140-164）

```java
if (!snapshots.has(path)) return fail("没有可回退的快照: " + relative(path));
String previous = snapshots.pop(path);
...
Files.createDirectories(path.getParent());
FileText.write(path, previous, readDecodedOrNull(path));
```

- **先 `has` 再 `pop`**：`pop` 在没快照时返回 `null`，但这里想给出带文件名的错误文案，所以先判一次。
- `readDecodedOrNull(path)` 在文件已被删除时返回 `null` → 按 UTF-8 写回。**于是「GBK 文件 → 删除 → 回退」会把编码变成 UTF-8**。
- 没有 `flushDocuments()`：回退本身就是覆盖，不需要先保存用户改动（但这也意味着**回退会丢掉用户未保存的编辑**）。

> 🔎 `writeFile` / `editFile` 在 `FileText.write` 抛 `IOException` 时，快照已经拍下了，但文件并没变。此时 `revert_file` 会写回「与当前完全相同的内容」，并宣称「已回退」。无害，但逻辑上是噪音。

---

## 10. `FileSnapshots.java`（45 行）：只有 5 份、只在内存

```java
private static final int MAX_PER_FILE = 5;
private final Map<String, Deque<String>> snapshots = new ConcurrentHashMap<>();

public void capture(Path path, String content) {
    Deque<String> stack = snapshots.computeIfAbsent(key(path), k -> new ConcurrentLinkedDeque<>());
    stack.push(content == null ? "" : content);
    while (stack.size() > MAX_PER_FILE) { stack.pollLast(); }
}
```

- **`push` + `pollLast`** = 新内容进头、**最老的一份从头尾挤出**。5 份之后，最早的修改再也回不去。
- **`pop` = `poll()`** 取头部 = 最近一份。所以 `revert_file` 可以连续调用 5 次，逐级回退。
- **key 是绝对规范化路径字符串**：`toAbsolutePath().normalize().toString()`。Windows 盘符大小写不同（`D:\` vs `d:\`）会算出**不同**的 key——同一个文件可能有两份互不相干的快照栈。
- **纯内存**：IDE 重启 / Core 重启后全部消失。`revert_file` 只对「本次会话内、本会话改过的」文件有效。
- `clear(path)` 存在但**无人调用**。
- `ConcurrentHashMap + ConcurrentLinkedDeque` 保证线程安全，但 `has()` 与 `pop()` 之间不是原子的——两个会话同时对同一文件 `revert_file` 时，可能一个成功一个拿到 `null`。因为写工具都过 `ProjectWriteGate`，实际不会并发到这里。

---

## 11. `SearchTools.java`（113 行）：正则搜索

### 11.1 四个常量

| 常量 | 值 | 作用 |
|---|---|---|
| `DEFAULT_MAX_RESULTS` | 50 | 不传 `max_results` 时的上限 |
| `MAX_SCAN_BYTES` | 1 MB | **单个文件**超过就跳过 |
| `SKIP_DIRS` | `.git`、`.idea`、`.gradle`、`target`、`build`、`dist`、`out`、`node_modules` | 目录黑名单 |
| `SKIP_EXTENSIONS` | 20 个 | `jar/class/png/jpg/.../lock` |

`max_results` 被夹在 `[1, 200]`：`Math.max(1, Math.min(200, ...))`。给了 0 或负数也变成 1（而不是「不限」）。

### 11.2 三个入口分支

```java
Path root = args.has("path") ? fileTools.resolve(...) : fileTools.projectRoot();
if (root == null || (!Files.isDirectory(root) && !Files.isRegularFile(root)))
    return fail("搜索路径不存在");
if (Files.isRegularFile(root)) {           // ② 传入的是单个文件
    scanFile(root, root.getFileName().toString(), regex, max, results);
    return ok(...);
}
// ③ 目录：Files.walk + 提前停止
try (Stream<Path> walk = Files.walk(root)) {
    Iterator<Path> it = walk.iterator();
    while (it.hasNext() && results.size() < max) {
        Path file = it.next();
        if (Files.isDirectory(file) || shouldSkip(file)) continue;
        scanFile(file, root.relativize(file).toString(), regex, max, results);
    }
}
```

三个细节：

1. **模型经常把文件当搜索路径传**——`path` 指向一个 `.java` 文件时，不报错，直接搜这一个文件。注释明确写了这个动机。
2. **`Files.walk` 是惰性的**，`results.size() >= max` 时 `while` 退出，但 `try-with-resources` 会关闭目录流。这一点很关键：不关的话 Windows 上会句柄泄漏。
3. **`shouldSkip` 检查路径的每一段**：

```java
private boolean shouldSkip(Path file) {
    for (Path part : file) {
        if (SKIP_DIRS.contains(part.toString())) return true;
    }
    return isBinaryName(file);
}
```

所以任何**祖先目录**叫 `build` / `out` / `target` 的文件都被跳——包括 `src/build/README.md` 这种误伤。

### 11.3 `scanFile`（82-97）

```java
if (Files.size(file) > MAX_SCAN_BYTES || isBinaryName(file)) return;
lines = FileText.splitLines(FileText.read(file).text());
...
results.add(display + ":" + (i + 1) + ": " + lines.get(i).trim());
```

- 用 `FileText` 解码，所以 GBK / UTF-16 日志也能搜到（这是它与 grep 的差别之一）。
- 单个文件 > 1MB 就**静默跳过**，不报不提示。大日志文件里的匹配会消失。
- 输出格式 `路径:行号: 内容`，但 **`trim()` 掉了缩进**：模型无法直接把这一行当 `old_string` 用（缩进没了）。

### 11.4 与 `read_file` 的两个不一致

| 项 | `read_file` / `diagnostics` | `search_code` |
|---|---|---|
| 路径分隔符 | `replace('\\', '/')` | **原样**（Windows 上是 `src\main\...`） |
| 行内容 | 保留缩进 | `trim()` |

分隔符不一致会让模型在两份结果里看到同一个文件的两种写法，进而**多读一次文件**。修法就一行（`display.replace('\\', '/')`），记在这里当待改进项。

### 11.5 性能与超时

- 每次调用都**从根重新走一遍目录树**，没有索引、没有缓存。本项目 183 个源文件无感；上万文件的项目里会明显卡。
- Core 侧 `timeout_sec=30`：超时后 `wait_for` 取消协程，但 **Java 侧的 `Files.walk` 不会停**（没有中断检查），它会继续跑完，只是结果没人要。
- `.venv`、`.pnpm-store`、`.next` 之类不在 `SKIP_DIRS` 里 → 会被扫（单文件限 1MB，所以不会爆内存，但会慢）。

---

## 12. `CommandTools.java`（181 行）：唯一能起进程的工具

### 12.1 参数校验（39-66）

```java
if (!args.has("command")) return fail("缺少参数 command");
String command = args.get("command").getAsString().trim();
if (command.isEmpty()) return fail("command 不能为空");
Path cwd = fileTools.projectRoot();
if (args.has("cwd") && !...isBlank()) {
    Path resolved = fileTools.resolve(...);          // 越界防护
    if (resolved == null) return fail("工作目录无效或越出项目根目录");
    if (!Files.isDirectory(resolved)) return fail("工作目录不存在: " + ...);
    cwd = resolved;
}
int timeout = timeoutSeconds(args);
if (needsApproval && !CommandConfirmCard.confirm(project, command, cwd)) {
    return fail("用户拒绝：未执行命令 " + command);
}
return execute(command, cwd, timeout);
```

- `cwd` 也走 `resolve`，所以 `cwd=../..` 会被拦住。这是继 Core 命令策略之后的**第二道路径防护**。
- 拒绝文案以「用户拒绝」开头——与 `permissions.DENIAL_PREFIXES` 对齐，于是**连续 3 次拒绝会熔断整轮**（§16.1）。
- `timeoutSeconds`：缺省 120，夹 `[1, 600]`，非数字（`getAsInt` 抛异常）回落默认。

### 12.2 `shellLaunch`（128-133）：两个平台、两种 quoting

```java
if (isWindows()) {
    return Arrays.asList("cmd.exe", "/d", "/s", "/c", "chcp 65001>nul & " + command);
}
return Arrays.asList("/bin/sh", "-c", command);
```

- `/d` 跳过 AutoRun 注册表脚本，`/s` 修改引号处理，`/c` 执行后退出。
- **`chcp 65001>nul &`**：把子进程的代码页切成 UTF-8，`>nul` 吞掉「Active code page: 65001」这行噪音。不过很多 Windows 工具（Maven、部分 JDK 版本）仍然按 GBK 输出，所以解码仍靠 `FileText` 的 GBK 兜底。
- **命令字符串原样传入，不做转义**（注释明确写了）。这带来一个真实限制：**命令里含双引号时可能被拆错**。`ProcessBuilder` 在 Windows 上会按 Java 规则给含空格的参数加引号、用反斜杠转义内部引号，而 `cmd.exe` 不认反斜杠转义。含引号的命令（例如 `echo "a b"`）具体被拆成什么，取决于 JDK 与 cmd 的解析细节，不是这里能保证的。

### 12.3 `execute`（80-122）：子进程的完整生命周期

```java
ProcessBuilder builder = new ProcessBuilder(shellLaunch(command));
builder.directory(cwd.toFile());
builder.redirectErrorStream(true);      // ① stderr 合并进 stdout
process = builder.start();

ByteArrayOutputStream captured = new ByteArrayOutputStream();
Thread reader = new Thread(() -> drain(process.getInputStream(), captured), "agent-command-reader");
reader.setDaemon(true);
reader.start();
```

**为什么必须单独开一个线程读输出？** 管道缓冲区只有几十 KB。子进程输出超过缓冲区且没人读时，它会**阻塞在 write 上**，永远不退出，`waitFor` 只能等到超时。所以读线程是必需品，不是优化。

```java
private void drain(InputStream stream, ByteArrayOutputStream sink) {
    byte[] buffer = new byte[8192];
    while ((read = input.read(buffer)) >= 0) {
        if (sink.size() >= MAX_CAPTURE_BYTES) continue;   // 继续读、但不再存
        sink.write(buffer, 0, Math.min(read, MAX_CAPTURE_BYTES - sink.size()));
    }
}
```

- `continue` 而不是 `break`：**必须把管道里的数据读掉**，否则子进程照样阻塞。于是超过 2MB 之后的输出被丢弃，但进程能跑完。
- `Math.min(read, remaining)` 防止最后一次写入越界。
- `IOException` 被静默忽略（「进程被杀了，已捕获的部分仍然有用」）。

三条退出路径：

| 情况 | 动作 | 返回 | `ok` |
|---|---|---|---|
| 正常结束 | `join(reader)` → 取 `exitValue()` | 输出（截断）+ `退出码: N` | **true** |
| 超时 | `destroyForcibly()` → `join(reader)` | `已终止（超时 N 秒）` + 部分输出 | **true** |
| 被中断 | `destroyForcibly()` | `执行被中断` | false |

⚠️ **两个「反直觉」**：

1. **超时返回 `ok=true`。** 因为工具确实执行了、也拿到了输出。代价是模型看不到任何「失败信号」——它读到的第一行是「已终止（超时 120 秒）」，如果它没仔细看，会以为命令跑完了。
2. **非零退出码也是 `ok=true`**，只在末尾追加「（命令未成功，请根据上面的输出定位原因）」。这个语义是一致的：`ok` 表示「工具调用成功」，不表示「命令成功」。模型必须学会读退出码。

> 🔎 `destroyForcibly()` 是 `SIGKILL` / `TerminateProcess`，**不会**杀掉子进程自己 fork 的进程（`mvn` 起的 `java` 子进程可能残留）。这是 `ProcessBuilder` 的通用限制，不是本文件的 bug。

### 12.4 `truncate`（169-180）：头 3200 + 尾 4800

```java
int tailChars = MAX_OUTPUT_CHARS - HEAD_CHARS;      // 8000 - 3200 = 4800
String head = output.substring(0, HEAD_CHARS);
String tail = output.substring(output.length() - tailChars);
String omitted = output.substring(HEAD_CHARS, output.length() - tailChars);
return head + "\n...（中间省略 " + omittedLines + " 行，共 " + totalLines + " 行）...\n" + tail;
```

**为什么留这么多尾巴？** 注释：失败堆栈与 `BUILD FAILURE` 都在输出末尾。编译错误的 `[ERROR]` 行也在末尾——头部的 `[INFO] Scanning for projects...` 反而是最没用的。

这里的截断与 `SearchTools` / `GitTools` 的「只留头」相反，是**按信息价值**决定的，不是随手写的。

### 12.5 三层超时叠加

| 层 | 值 | 谁在等 |
|---|---|---|
| Shell 子进程 | `timeout_sec`（默认 120，最大 600） | `process.waitFor` |
| Core 工具超时 | **固定 130s** | `asyncio.wait_for` |
| 上层 | 无 | — |

**致命组合**：模型传 `timeout_sec=600`（比如跑完整测试套件），Shell 会等 10 分钟，但 **Core 130 秒就放弃了**：

1. 130s：`scheduler` 报「工具执行超时」，模型收到失败；
2. 同一时刻：`_invoke_shell_tool` 的 future 被 pop；
3. 600s：Shell 的进程才被杀，发回的 `tool/result` 落到空处。

后果是**一个孤儿进程继续占用 CPU/磁盘最多 10 分钟**，而模型已经在重试另一条命令。`run_command` 参声里的上限 600 与 Core 固定 130 完全脱钩——这是全卷最值得优先修的一处（要么让 Core 超时跟着参数走，要么把参数上限压到 120）。

---

## 13. `GitTools.java`（72 行）：两个只读工具

### 13.1 `log`（27-43）

```java
LocalDate start = LocalDate.parse(args.get("start_date").getAsString());
LocalDate end = LocalDate.parse(args.get("end_date").getAsString());
if (end.isBefore(start)) return fail("end_date 早于 start_date");
GitLogCollectorService service = new GitLogCollectorService();
return ToolResult.ok(service.formatEntries(service.collect(project, start, end, "daily")));
```

- `LocalDate.parse` 是**严格 ISO**：`2026/11/01`、`2026-11-1`、`11-01` 全部抛 `DateTimeParseException` → 被外层 `catch (Exception)` 变成「git_log 失败: ...」。模型必须给 `yyyy-MM-dd`。
- 复用旧日报功能的收集器（`reportType="daily"`）：**同一个工具与旧功能共用一条 git 调用链**，日报怎么算的，`git_log` 就怎么算（10 卷细看）。
- 外层 `catch (Exception)` 包住了一切（包括空仓库、git 不存在等），统一文案。

### 13.2 `diff`（45-66）

```java
GitRepository repository = firstRepository();      // 只取 getRepositories().get(0)
GitLineHandler handler = new GitLineHandler(project, repository.getRoot(), GitCommand.DIFF);
if (args.has("path")) handler.addParameters(args.get("path").getAsString());
GitCommandResult result = Git.getInstance().runCommand(handler);
if (!result.success()) return fail("git diff 失败: " + ...);
...
if (output.length() > 20000) output = output.substring(0, 20000) + "\n...(已截断)";
```

四个限制：

1. **只看第一个仓库。** 多模块项目（`getRepositories()` 多个）里，只有第一个的 diff 可见。
2. **只算未暂存的改动。** 裸 `git diff` 不含 `--staged`，模型执行过 `git add` 之后会看到「（无未提交变更）」——实际上有改动，只是在索引里。
3. **`path` 原样透传给 git**，没有走 `resolve`。好处是 git 自己会处理相对路径；坏处是它可以指到项目外（`git diff -- ../other`），虽然只读、危害有限，但**与其它八个工具的路径策略不一致**。
4. **截断留头不留尾**：20000 字符之后全部丢掉。与 `CommandTools` 留尾的策略相反——长 diff 里最关键的信息往往在最后一个文件。

---

## 14. `DiagnosticsTools.java`（151 行）：向 daemon 借眼睛

### 14.1 整体形状

```java
ApplicationManager.getApplication().invokeAndWait(() ->
        ApplicationManager.getApplication().runReadAction(() ->
                collect(root, scope, errorsOnly, found)));
```

- 必须 `invokeAndWait`：`MarkupModel` 不是线程安全的，只能在 EDT 上碰。
- 又套了一层 `runReadAction`：读取 PSI/文档需要读锁。在 EDT 上已经是写锁，再要读锁是允许的（重入），不是死锁。
- 因为 `invokeAndWait`，**这个工具会把后台线程停住，直到 EDT 空闲**。EDT 正忙（比如在跑一个大索引）时，这里就是在等。

### 14.2 `collect`（83-131）：只认「已加载的文档」

```java
for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
    Document document = editor.getDocument();
    if (!visited.add(document)) continue;                 // 同一文档可能被多个编辑器打开
    VirtualFile file = manager.getFile(document);
    if (file == null || !file.isInLocalFileSystem()) continue;
    Path path = Paths.get(file.getPath()).normalize();
    if (!path.startsWith(root) || !path.startsWith(filter)) continue;
    MarkupModel markup = DocumentMarkupModel.forDocument(document, project, false);
    ...
}
```

⚠️ **这是全卷最容易被误解的行为**：诊断只来自 `EditorFactory.getAllEditors()`——**IDE 里已经打开过的文件**。

| 场景 | 结果 |
|---|---|
| 用户打开了 `A.java`，里面有 3 个错误 | 能看到 |
| 同项目的 `B.java` 也有错误，但没打开 | **看不到** |
| 刚 `git clone` 打开项目，什么都没点开 | 返回空 → 模型以为「没有错误」 |
| 文件被打开过又关了 | 不可见（editor 已释放） |

注释把它当优点写：「这正是用户正在看的文件，也是唯一有高亮的集合。」对「修完代码确认错误消失」这个用法是对的；对「全项目体检」就是错的。**模型必须被告知这个限制**，否则它会拿一个残缺结果下结论。

`filter` 的用法是「路径同时是 root 和 filter 的前缀」：`path` 参数传一个**文件**也有效（文件路径 `startsWith` 自身）。

### 14.3 严重度与位置

```java
if (severity.compareTo(HighlightSeverity.ERROR) >= 0) return "error";
if (severity.compareTo(HighlightSeverity.WARNING) >= 0) return "warning";
return null;                                            // INFO / WEAK_WARNING 丢弃

int offset = Math.max(0, Math.min(info.getStartOffset(), document.getTextLength()));
int line = document.getLineNumber(offset) + 1;
int column = offset - document.getLineStartOffset(line - 1) + 1;
```

- `compareTo` 是按数值比较（`ERROR=400 > WARNING=300 > INFO=200 > WEAK_WARNING=100`），所以 `INFORMATION` 与 `WEAK_WARNING` 被过滤掉，只剩 error / warning 两类值。
- `offset` 被夹在 `[0, textLength]`：**文档正在被编辑时高亮偏移可能越界**，不夹的话 `getLineNumber` 会抛 `IndexOutOfBoundsException`，整个工具失败。
- `column` 是**字符偏移**，不是显示列（制表符按 1 算）。
- `source` 取 `getInspectionToolId()`，为 null 时写 `"daemon"`——所以这个字段可能是检查器名，也可能是字面量 `daemon`。

### 14.4 排序、截断与 `count` 的含义

```java
found.sort(comparing(file).thenComparingInt(line));
boolean truncated = found.size() > MAX_ITEMS;          // 50
List<JsonObject> items = truncated ? found.subList(0, MAX_ITEMS) : found;
payload.addProperty("count", items.size());            // ← 截断后的条数
payload.addProperty("truncated", truncated);
```

- 排序键是**文件名字符串**，不是路径深度；同一文件内按行号。
- `count` 是**返回条数**，不是总条数。总数只能从 `truncated=true` 推断「至少 51 条」。想报告「共 N 个错误」的模型会误报成 50。
- 截断按字母序保留前 50 条 → 如果 `z/` 目录里的错误更多，`a/` 里的小问题会把它挤掉。

---

## 15. `agent/commands.py`（130 行）：终端命令的三态策略

这是本卷唯一一个**纯函数**的模块，也是最能体现「策略与执行分离」的地方：Core 算出 `auto/confirm/deny`，Shell 只负责弹框或执行。

### 15.1 两个清单

```python
DEFAULT_ALLOWLIST = ["mvn", "mvnw", "gradle", "gradlew", "npm", "npx", "pnpm", "yarn",
                     "pytest", "python -m unittest", "python -m pytest", ...
                     "git status", "git diff", "git log", "git branch", "git show",
                     "ls", "dir", "cat", "type", "node -v", "java -version"]

DENY_PATTERNS = ["rm -rf", "rm -fr", "rm -r /", "rm --recursive", "del /f", "del /s", "del /q",
                 "rd /s", "rmdir /s", "format ", "shutdown", "reboot", "mkfs", "diskpart",
                 ":(){", "git reset --hard", "git push", "git clean -", "git branch -d",
                 "curl | sh", "curl|sh", "wget | sh", "wget|sh", "invoke-expression", "iex("]
```

两张表都是**意图分类**，不是「命令解析」：

| 选择 | 后果 | 理由 |
|---|---|---|
| 白名单是**前缀**匹配 | 新的子命令自动可用（`mvn -q test`） | 宽进 |
| 黑名单是**子串**匹配 | `ls && rm -rf .` 这种链式也能拦 | 严出 |
| `git push` 整体拒绝 | 推送不代劳（系统提示里也写了） | 不可逆 |
| `git clean -` 拒绝 | 连 `git clean -n`（演练）也拒 | 宁可误杀 |

### 15.2 `_matches_prefix`（104-111）：整词前缀

```python
if segment == prefix or segment.startswith(prefix + " "):
    return True
```

`prefix + " "` 这个空格是整个函数的重点：

| 命令段 | 前缀 | 结果 |
|---|---|---|
| `mvn -q test` | `mvn` | ✅ 命中 |
| `mvn` | `mvn` | ✅ 命中（`==` 分支） |
| `mvnw` | `mvn` | ❌ 不命中（没有空格）→ confirm |
| `lsblk` | `ls` | ❌ 不命中 |

注释里的「`mvn` 不会误吞 `mvnw`」就是这个意思。注意注释说「结尾空格可选」，代码里是靠 `==` 处理的，不是靠 `strip`。

### 15.3 `decide`（114-130）：四条规则的先后

```python
def decide(command: object, allowlist: Optional[List[str]] = None) -> str:
    text = normalize(command)                     # 1. 空白折叠
    if not text:
        return DENY                               # 2. 空命令直接拒
    lowered = text.lower()
    for pattern in DENY_PATTERNS:
        if pattern in lowered:
            return DENY                           # 3. 黑名单最优先
    for piped in lowered.split("|")[1:]:
        if _PIPE_INTERPRETER.match(piped.strip()):
            return DENY                           # 4. 管道接解释器
    prefixes = allowlist if allowlist else DEFAULT_ALLOWLIST
    segments = [part.strip() for part in _SEGMENT_SPLIT.split(lowered) if part.strip()]
    if segments and all(_matches_prefix(part, prefixes) for part in segments):
        return AUTO                               # 5. 每一段都在白名单内
    return CONFIRM
```

**优先级很重要**：黑名单 > 管道检查 > 白名单。所以 `mvn test && rm -rf target` 是 `deny`，不是 `confirm`；也不是「白名单先命中前半段」就放行。

那个 `segments and` 不能省：全空白的分段列表会算出 `all([]) == True`，直接变成 `auto`。

`_SEGMENT_SPLIT` 把 `&&`、`||`、`;`、`|`、`&`、`\n` 都当分段符，并且要求**每一段**都命中白名单：

| 命令 | 结果 | 哪一段失败 |
|---|---|---|
| `git status && git diff` | auto | — |
| `git status && python deploy.py` | confirm | 第二段 |
| `mvn -q test` | auto | — |
| `mvn -q test\nrm -rf .` | deny | 黑名单（不是分段） |
| `echo hi` | confirm | 唯一一段 |

### 15.4 四个真实缺口

1. **子串匹配会误杀**：`echo "rm -rf is dangerous"` 会被拒（字符串里有 `rm -rf`）。模型想写一句带这个词的 echo 时会连续失败。
2. **`git push` 子串也拦 `git push --dry-run`**，以及提交说明里带 `git push` 的命令。
3. **只防命令行文本，不防载荷**：`python -c "import shutil;shutil.rmtree('.', ignore_errors=True)"` 不含任何黑名单词 → 落到 `confirm`（需要人点「执行」），但**不会**被 deny。真正的安全边界是「人工确认」，不是这个列表。
4. **自定义白名单是「替换」而非「追加」**：`ChatConfig.command_allowlist` 非空时完全取代内置表；但**空列表 ≠ 全禁**，`allowlist if allowlist else DEFAULT_ALLOWLIST` 让空列表回落内置表。于是用户想「什么都不免确认」在协议上无法表达。

---

## 16. `agent/permissions.py`（29 行）+ 两道熔断

### 16.1 拒绝计数

```python
DENIAL_PREFIXES = ("用户拒绝", "user denied", "denied by user")

class PermissionTracker:
    def __init__(self, max_consecutive_denials: int = 3) -> None: ...
    def record_result(self, ok, error="") -> bool:
        if ok or not self._is_denial(error):
            self.consecutive_denials = 0
            return False
        self.consecutive_denials += 1
        return self.consecutive_denials >= self.max_consecutive_denials
```

三个关键点：

1. **靠文案识别**：`error.strip().lower().startswith(prefix.lower())`。前三个字是「用户拒绝」就算——所以 Shell 里所有拒绝文案必须以这四个字开头（`EditTools` 的「用户拒绝：未写入 X」、`CommandTools` 的「用户拒绝：未执行命令 X」）。
2. **「连续」才算**：任何一次成功或**非拒绝类**错误都会清零。模型改个参数重试就能无限试下去。
3. **每轮一个 tracker**：`run_chat` 开头 `tracker = PermissionTracker()`，跨轮不继承。

### 16.2 两道熔断对照

| | 未知工具熔断 | 拒绝熔断 |
|---|---|---|
| 计数键 | `call.name`（分工具） | 全局连续计数 |
| 阈值 | `UNKNOWN_TOOL_LIMIT = 3` | `max_consecutive_denials = 3` |
| 识别方式 | `error.startswith("未知工具")` | `error` 以「用户拒绝」开头 |
| 命中后 | 「模型反复要求调用不存在的工具…」 | 「连续多次未获得授权，已停止工具执行…」 |
| 计数会清零吗 | **不会**（直到本轮结束） | 会（成功/其他错误即清） |

「未知工具」不清零是故意的：同一个名字在本轮里累计出现 3 次就停，哪怕中间穿插了成功调用——因为这类失败几乎总是同一个幻觉循环。

### 16.3 一个漏网之鱼

Core 自己拒绝危险命令时的文案是：

```
已拒绝执行危险命令: rm -rf .
删除文件请改用 delete_file（逐个确认）；推送与历史重写由人执行，Agent 不代为执行。
```

开头是「已拒绝」而不是「用户拒绝」→ **不计入 PermissionTracker**；也不是「未知工具」→ 不进另一个计数器。于是模型反复尝试 `rm -rf` 时，三道防护（黑名单、拒绝计数、未知工具）**都不触发**，只能靠 `max_iterations=40` 兜底。改两个字的文案就能修（把开头改成「用户拒绝：已拒绝执行危险命令…」），但会把「策略拒绝」与「人拒绝」混为一谈，需要先想清楚语义。

---

## 17. 三层截断：谁在削工具的输出

一次 `run_command` 的输出要过三道关：

```
① CommandTools.truncate   8000 字符（头 3200 + 尾 4800）
② compress_tool_output    1800 token（只留头 + 截断标记）
③ trim_history            整轮丢弃（带 tool_calls 的 assistant + 对应 tool 消息）
```

### 17.1 第一层：工具自己

| 工具 | 上限 | 策略 |
|---|---|---|
| `read_file` | 512KB | **超了就失败**（不截断） |
| `list_dir` | 300 条 | 静默截断，无标记 |
| `search_code` | `max_results`（默认 50，最大 200）；单文件 1MB | 早停 |
| `run_command` | 捕获 2MB，返回 8000 字符 | 头 + 尾，中间省略行数 |
| `git_diff` | 20000 字符 | 只留头 |
| `diagnostics` | 50 条 | 标记 `truncated` |

### 17.2 第二层：`compression.py`（56 行）

```python
TOOL_OUTPUT_BUDGET = 1800
HEAD_SHARE = 0.55

def compress_tool_output(tool_name, content, path=None, budget=TOOL_OUTPUT_BUDGET):
    if not content or count_tokens(content) <= budget:
        return content
    if tool_name == "read_file":
        source = strip_line_numbers(content)
        language = _language_hint(path, source)
        if language:
            outline = outline_of(path or "", source)
            if outline:
                head = truncate_to_budget(source, int(budget * HEAD_SHARE))       # 990
                tail = truncate_to_budget(outline, max(1, budget - int(budget * HEAD_SHARE)))  # 810
                return "%s\n\n...（文件过长，以下仅列出结构概览）...\n%s" % (head, tail)
    return truncate_to_budget(content, budget)
```

四个要点：

1. **只有 `read_file` 有「结构感」**：剥掉 `12→` 行号前缀 → `detect_language` → `outline_of`（基于 native/codesoup，Rust 或 Python 回退）→ 前 990 token 原文 + 810 token 的函数/类结构概览。模型于是既看到开头、又知道后面有哪些函数。
2. **其他工具只有「砍头」**：`truncate_to_budget` 用二分找最大的 `text[:mid]` 并追加截断标记——**保留前缀，丢掉后缀**。
3. ⚠️ **这与 `CommandTools` 的「保留尾部」直接冲突**：8000 字符的构建输出通常远超 1800 token，于是刚被精心保留下来的 `BUILD FAILURE` 尾巴又被这里砍掉。想让「保留尾部」真正生效，这一层需要针对 `run_command` 单独处理。
4. **只在 `ok=True` 时压缩**：错误路径是 `content = "ERROR: " + error`，原样进会话（错误通常很短，不需要压缩）。

### 17.3 第三层：`trim_history`

见 05 卷 §5：预算不足时按**整轮**丢（assistant 的 `tool_calls` + 对应 `tool` 消息一起走），永不丢 `user`。所以一次很长的调试对话后，中间的工具结果会成片消失——模型「忘了」曾经读过哪个文件，就是这个机制造成的。

---

## 18. 两个确认卡片

### 18.1 `DiffConfirmCard`（108 行）

```java
public static boolean confirm(Project project, Path path, String before, String after) {
    boolean[] accepted = {false};
    ApplicationManager.getApplication().invokeAndWait(() -> { ... dialog.showAndGet() ... });
    return accepted[0];
}
```

- **阻塞式**：后台线程上 `invokeAndWait` 到 EDT 弹模态框，返回时已经知道用户点了什么。注释里特意写了「有意阻塞」。
- `boolean[] accepted` 是「lambda 内写外部变量」的标准绕法（Java 闭包只能捕获 effectively final）。
- 尺寸固定 900×520，等宽字体 12pt，不自动换行。

`renderDiff`（55-107）不是真正的 diff 算法，而是**单块变更**：

```java
// 1. 找公共前缀 prefix
// 2. 找公共后缀 suffix
// 3. 打印 prefix 前 3 行上下文
// 4. 中间全部标 "- "，然后全部标 "+ "
// 5. 打印 suffix 后 3 行上下文
```

后果：**文件里两处相隔很远的修改，中间所有未变的行都会被同时标成删除+添加**。800 行上限一到就截断，用户可能根本看不到真正的改动。`edit_file` 每次只改一处，通常看不出问题；`write_file` 整体覆盖一个大文件时，这个卡片基本只能告诉你「文件被重写了」。

`split("\n", -1)` 的 `-1` 保留末尾空串——所以「内容以换行结尾」与「不以换行结尾」在 diff 里会有区别（多一个空行元素），前缀/后缀匹配因此不会被误判。

### 18.2 `CommandConfirmCard`（62 行）

- 720×160，`setLineWrap(true)`（命令可能很长）。
- 顶部显示「工作目录: `cwd`」，中间是命令原文，底部灰色小字「Agent 请求执行以下命令；允许后才会运行。」
- 按钮文案：**执行 / 跳过**。点「跳过」= 返回 false = 「用户拒绝」→ 进入拒绝计数。
- **没有「本次会话不再询问」选项**——唯一的全局开关是面板上的「自动执行」，而它只对写类工具生效（§6.2），所以命令确认**每次都会弹**。

---

## 19. 测试与验证

### 19.1 三个 Core 测试文件（255 行）

| 文件 | 行 | 锁住什么 |
|---|---|---|
| `test_commands.py` | 68 | 7 个用例：白名单、需确认、空白折叠、链路逐段、危险拒绝、空命令、自定义白名单 |
| `test_scheduler.py` | 91 | 4 个用例：真并行（4×0.1s < 0.35s）、未知工具、超时隔离、start/end 回调顺序 |
| `test_unknown_tool.py` | 96 | 4 个用例：错误里带真实工具名、scheduler 同一提示、反复未知工具 3 次后停、每条 tool 消息都带「可用工具」 |

三个值得注意的测试细节：

1. `test_scheduler.test_runs_tools_in_parallel` 用**墙钟时间**断言并行（`elapsed < 0.35`）。这种测试在负载高的 CI 上会抖，阈值给得宽（4 个 0.1s 串行需 0.4s，阈值 0.35）。
2. `test_unknown_tool` 断言 `provider.rounds == UNKNOWN_TOOL_LIMIT`——精确到「第 3 轮就停」，而不是「3 轮左右」。
3. `test_commands.test_custom_allowlist_replaces_the_default` 明确钉住「空列表回落默认」这个反直觉行为，也是§15.4 第 4 条的来源。

### 19.2 Java 侧：零测试

```
src/test/java/.../UiIconsResourceTest.java     35 行
src/test/java/.../MarkdownParserTest.java     149 行
```

**11 个工具类、1,238 行，没有任何单元测试。** 最需要测试的几块——`FileText` 的四级编码探测、`splitLines` 的尾换行、`resolve` 的越界、`truncate` 的头尾拼接——全部只能靠手测。想验证它们，最小成本的命令是：

| 想验证 | 怎么验 |
|---|---|
| GBK 读回 | 新建 GBK 文件 → `read_file` 看末尾是否提示编码 → `edit_file` 后 `git diff` 看字节是否变 UTF-8 |
| CRLF 不丢 | 在 CRLF 仓库里让模型 `edit_file`（含换行）→ 看是否返回「未找到 old_string」 |
| 路径越界 | `read_file path=../../../etc/hosts` |
| 超时 | `run_command command="ping -n 20 127.0.0.1" timeout_sec=2` → 应返回「已终止（超时 2 秒）」且 `ok` 为 true |

---

## 20. 十一条易错点

1. **`read_file` 说「超大文件会截断」，实际是直接失败**（512KB），且 `start_line/end_line` 也救不了。
2. **CRLF 文件的 `edit_file` 经常「未找到 old_string」**：`read_file` 输出统一用 `\n`，而匹配是在原始文本上做的。
3. **`delete_file` 会被「自动执行」免确认**（它在 `WRITE_TOOLS` 里，`autoAccept` 覆盖了 `confirm`）。
4. **`run_command` 的超时上限 600s 与 Core 的固定 130s 脱钩** → 超时后 Shell 进程变成孤儿继续跑。
5. **`run_command` 超时/非零退出码都返回 `ok=true`**，模型必须自己读「已终止」「退出码: N」。
6. **`diagnostics` 只看已打开的编辑器**：没点开过的文件里的错误它一个都看不到。
7. **`diagnostics.count` 是截断后的条数**，不是总数。
8. **`search_code` 在 Windows 上返回反斜杠路径、且 `trim()` 掉了缩进**，与 `read_file` 格式不一致。
9. **`git_diff` 只看第一个仓库、只看未暂存改动、截断只留头。**
10. **`revert_file` 的快照是内存的、会话级的、每文件 5 份**：重启即丢，跨会话不可见。
11. **Core 拒绝危险命令的文案「已拒绝…」不触发任何熔断**，模型可以反复重试到轮次耗尽。

另外两个不在工具层但会直接坑到工具层的点：

- **`ToolDef.max_concurrency` / `remote` 是死字段**；并发只能全局调（`ChatConfig.tool_concurrency`）。
- **`connection.request("tool/result", …)` 忽略了返回的 future**，每次工具调用在 `JsonRpcConnection.pending` 里留下一条永不清理的记录。

---

## 21. 自测：先预测，再回去验证

1. 面板勾上「自动执行」后，让模型 `delete_file a.txt`。会不会弹确认框？
2. 文件是 CRLF，模型用 `read_file` 拷了两行做 `old_string`，`edit_file` 会成功还是失败？为什么提示里要写「注意缩进与换行」？
3. 模型一轮里同时调用 `ask_user` 和 `edit_file`。执行顺序是什么？如果反过来并行，会发生什么事故？
4. `run_command command="gradlew build" timeout_sec=500`，Core 在第几秒放弃？Shell 在第几秒杀进程？中间能发生什么？
5. 两个会话同时 `edit_file` 同一个文件。谁先执行？第二个会话的对话区里会多出一行什么文字？
6. 会话 A 改了 `X.java`，用户在会话 B 里输入「撤销刚才的修改」。`revert_file` 会成功吗？
7. 模型连续 5 次尝试 `rm -rf build`。三道防护里哪一道会拦住它？
8. 一个 2000 行的输出为 `BUILD SUCCESS` 的构建日志，模型最终看到的是什么？`BUILD SUCCESS` 还在吗？

---

## 22. 下一卷预告

本卷只讲了「工具怎么执行」。还有一大半工具在 Core 里自己跑：

- **状态与清单**：`todo_write` 怎么把清单写进会话并推给 Shell；
- **记忆**：`memory_save/recall/delete` + 待确认队列的晋升与丢弃；
- **检索**：会话历史索引（`session/search`）与代码索引（`semantic_search` / `build_index`）；
- **MCP**：动态注册的工具名、客户端生命周期、敏感调用的二次确认；
- **Skills**：`skill_list` / `skill_load` 与 SKILL.md 的加载；
- **子代理**：`subagent_dispatch` 内部再跑一个 `run_chat`；
- **用量**：`usage.jsonl` 与 `usage/report`。

第 07 卷：**状态、记忆、检索、MCP、Skills 与用量**。



