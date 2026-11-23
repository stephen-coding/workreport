# 升级路线详设：v2.4.9 → v3.0.0

> 本文是实施文档，不是宣传稿。每一节都给出**改动位置、接口/字段、默认值、兼容性、验收标准**，
> 照着就能写代码；与实现冲突时以代码为准，并回改本文。

| 项 | 值 |
|---|---|
| 基线版本 | 2.4.9（提交 `07c7a5f`） |
| 目标版本 | 3.0.0 |
| 周期 | 约 8 周（2026-11-16 → 2027-01-08） |
| 适用仓库 | IntelliJ Shell（`src/main/`）、Agent Core（`core/`）、VS Code Shell（`vscode/`） |
| 验收基线 | 单测 173 例 + 4 协议冒烟 + Java/TS 编译全绿；每个版本不得回退 |

---

## 0. 决策基线（已拍板，后续不再讨论）

| 编号 | 决策 | 影响 |
|---|---|---|
| D1 | 子代理**保持严格只读**，不开放受控写 | 写文件始终由主代理执行；`WRITE_TOOLS` 三层过滤继续存在 |
| D2 | 终端工具**白名单内 auto、其余 confirm**，危险命令直接拒绝 | 权限策略在 Core 一侧计算，Shell 只按 `permission` 决定是否弹窗 |
| D3 | **先上架 IntelliJ Marketplace**，VS Code 延后到功能对齐之后 | 3.0.0 的对外发布物只有插件 zip |
| D4 | 遥测**只做本地统计**，不做任何上报 | 用量写本地 JSONL，不新增网络出口 |

补充约束（沿用既有工程规范）：

- Core 不直接写用户文件，所有文件/终端动作经 Shell 执行；协议是唯一契约。
- 版本号规则：新增能力升 minor，修复/收口升 patch；每次发布必须同步 README / USER_GUIDE / 详设 / `pom.xml` / `plugin.xml`。
- 提交按约定式提交拆分，push 由维护者手动执行。

---

## 1. 现状基线与瓶颈

| 维度 | 现状 | 瓶颈 |
|---|---|---|
| 代码规模 | 生产代码约 14.4k 行（Core 5.8k、IntelliJ 7.1k、TS 1.1k、Rust 0.4k），测试 3.5k 行 | 无 UI 自动化测试、无 RPC 契约快照 |
| 上下文 | `DEFAULT_BUDGET = 24000` 硬编码（`core/src/agent_core/llm/context.py`） | 与模型真实窗口无关：128k 模型浪费，8k 模型易 400 |
| 用量 | `usage` 已随 `chat/finish` 下发（`rpc/server.py`），仅最后一轮 | 两端 UI 均未展示，且多轮不聚合 |
| 网络 | 无重试/退避（全仓 0 处 `retry`） | 一次 429/断连即任务失败 |
| 闭环 | 9 个 Shell 工具，**无终端、无诊断** | Agent 改完无法自测，用户仍需手动验证 |
| 多端 | VS Code 无行内补全、无 Embedding 设置、Key 明文 | 两端能力不对等 |
| 发布 | 三分支 ahead 1 未 push；CI 监听 `main`（主分支为 `master`）；About 为旧文案 | 发布链路断裂 |

---

## 2. 版本总览

| 版本 | 主题 | 周期 | 核心交付 | 预计人日 |
|---|---|---|---|---|
| v2.4.10 | 发布收口 | 第 1 周（2 天） | push、CI 分支、About、Core 版本单一来源、文档口径 | 2 |
| v2.5.0 | 成本与稳定性 | 第 2 周 | 上下文按模型窗口、用量可视化、重试退避、日志落盘、两端设置对齐 | 6 |
| v2.6.0 | 闭环能力 | 第 3–4 周 | `run_command`、`diagnostics`、Plan/TODO | 12 |
| v2.7.0 | 多端对齐 | 第 5–6 周 | VS Code 行内补全、设置对齐、SecretStorage、历史会话 UI | 10 |
| v3.0.0 | 产品化 | 第 7–8 周 | 记忆沉淀、会话检索、Marketplace 上架、性能基线、i18n、本地统计 | 14 |

依赖关系：2.5.0 的用量聚合是 3.0.0 本地统计的前置；2.6.0 的终端工具是 3.0.0 性能基线脚本的前置；
2.7.0 的 VS Code 对齐必须在 Marketplace 上架（D3）之前完成，否则会二次发版。

---

## 3. v2.4.10 · 发布收口

### 3.1 远程分支与快进同步

- 现状：`develop` / `master` / `feature/agent-core` 均指向 `07c7a5f`，各自 ahead 1。
- 动作：`git push origin develop master feature/agent-core`（由维护者执行，不代推）。
- **验收**：`git rev-list --left-right --count origin/master...master` 为 `0 0`。

### 3.2 CI 触发分支对齐

- 文件：`.github/workflows/ci.yml` 第 5 行。
- 改动：`branches: [develop, main]` → `branches: [develop, master]`。
- **验收**：推送 master 后 `core` / `intellij` / `vscode` / `native` / `core-exe` 五个 job 均被触发。

### 3.3 仓库元数据（About）

- 动作：仓库首页 → About 齿轮，填写 Description / Topics（文案见交付说明），Website 留空。
- 不在仓库内产生提交，**无法通过 git 同步**。
- **验收**：仓库标题摘要与 README 首段一致，Topics 至少 8 个。

### 3.4 Core 版本号单一来源

问题：`core/src/agent_core/__init__.py` 写死 `0.1.0`，与插件 `2.4.9` 脱节，`core/hello` 上报的版本无法用于排查。

设计：

```python
# core/src/agent_core/__init__.py
def _resolve_version() -> str:
    injected = os.environ.get("AGENT_CORE_VERSION", "").strip()   # 发布构建注入
    if injected:
        return injected
    packaged = _packaged_version()        # exe 内嵌 core-build-info.json
    if packaged:
        return packaged
    try:
        from importlib.metadata import version
        return version("agent-core")      # pip 安装
    except Exception:
        return "0.0.0-dev"                # 源码检出

__version__ = _resolve_version()
```

- `core/pyproject.toml` 的 `version` 保持 `0.1.0`（pip 安装时用），发布构建时由脚本导出环境变量覆盖。
- `core/packaging/agent-core.spec` 把 `AGENT_CORE_VERSION` 写入 `core-build-info.json` 并打进包内，
  这样 exe 在无环境变量时也能报出正确版本。
- `scripts/build_core.ps1` 用 `[System.IO.File]::ReadAllText(..., UTF8)` 读 `pom.xml` 的 `<version>`，
  设置 `$env:AGENT_CORE_VERSION` 后再调用 PyInstaller。
- `core/src/agent_core/protocol.py` 的 `CORE_VERSION = __version__`，因此 `core/hello` 的 `coreVersion`
  与 MCP `clientInfo.version` 自动跟随，**不需要新增字段**。
- **验收**：`agent-core --version` 输出 `agent-core 2.4.10`，与 jar 内 `plugin.xml` 版本一致。

> 注：`--version` 参数为本次新增（`__main__.py`），不影响既有 `--native-info`。

### 3.5 文档口径修正

- README「职责边界」第 2 条现写“文件 IO、Git、diff 确认、终端、行内补全”，但终端工具尚不存在。
- 改为“文件 IO、Git、diff 确认、行内补全”，并链接本文 §5.1（终端工具计划）。
- 同时修正 `core/src/agent_core/__init__.py` 文档字符串中的 “JSON-RPC 2.0”，与 README 口径统一为“JSON Lines 自定义信封”。

---

## 4. v2.5.0 · 成本与稳定性

### 4.1 上下文预算按模型窗口

**问题**：`loop.py` 每轮调用 `trim_history(messages)`，预算固定 24000 token。

**设计**

1. `ChatConfig` 新增字段：

```python
context_window: int = 32000        # 模型上下文窗口，0 表示用默认
```

2. `llm/context.py` 新增：

```python
DEFAULT_BUDGET = 24000
BUDGET_RATIO = 0.75                # 预留 25% 给回复与工具输出

def budget_for(context_window: int) -> int:
    if not context_window or context_window <= 0:
        return DEFAULT_BUDGET
    return max(4000, min(int(context_window * BUDGET_RATIO), 200000))
```

3. `loop.py` 调用点改为 `trim_history(messages, budget_for(config.context_window))`。
4. 设置页（`ReportSettingsConfigurable`）在「对话与 Agent」分组新增「上下文窗口（tokens）」，
   默认 32000，范围 4000–200000；`ReportSettingsState.contextWindow` 持久化。
5. `AgentCoreService.pushSettings` 下发 `context_window`；VS Code `agentCoder.contextWindow` 同步。
6. 兼容：旧设置 XML 中该字段为 0 → 读回默认 32000（与 `maxIterations` 相同处理）。

**验收**

- 8k 窗口模型：把 `context_window=8000` 后跑 15 轮工具，不出现 `400 context length`。
- 128k 窗口模型：长文档审计可保留 40+ 轮历史，不再提前丢弃。
- 单测：`test_context.py` 新增 `budget_for` 边界用例（0 / 4000 / 32000 / 超上限）。

### 4.2 用量可视化与多轮聚合

**现状**：`run_chat` 返回最后一轮的 `usage`；多轮工具调用时用量被低估。

**设计**

1. `ChatResult` 增加聚合字段（`llm/base.py`）：

```python
usage: Optional[Dict[str, Any]]      # 兼容：最后一轮
usage_total: Optional[Dict[str, Any]]  # {"in": n, "out": n, "total": n}
```

2. `loop.py` 每轮把 `result.usage` 累加进 `usage_total`（in/out，Ollama 与 OpenAI 字段名差异在
   `llm/streaming.py` 已归一为 `in` / `out`）。
3. `chat/finish` payload 增加 `usageTotal`；保留 `usage` 兼容旧 Shell。
4. IntelliJ：`ChatSessionPanel` 在完成状态行追加 `本轮 1.2k in / 0.4k out`；`ChatSessionPanel` 维护
   会话累计值，显示在标签 tooltip。
5. VS Code：`session.ts` 同构处理。
6. 格式化规则：`<1000` 显示原值，`>=1000` 显示 `1.2k`，保留一位小数。

**验收**

- 一轮 3 次工具调用、4 次模型请求后，状态行显示的是累计值而非最后一次。
- `usage` 缺失（端点不返回 usage）时不显示，不报错。

### 4.3 传输层重试与退避

**现状**：`llm/transport.py` 失败即抛 `LLM_ERROR`。

**设计**

```python
RETRY_STATUS = {429, 500, 502, 503, 504}
MAX_RETRIES = 2
BACKOFF_SEC = (0.5, 1.5)          # 第 1 次、第 2 次重试前的等待
```

- 可重试：上述状态码、`httpx.ConnectError`、`httpx.ReadTimeout`（尚未收到任何 token）。
- 不可重试：401/403/404、已开始流式输出（避免重复 token 与工具副作用）、用户取消。
- 重试耗尽的错误信息追加 `（已自动重试 2 次）`，保留阶段/端点/处置建议。
- **验收**：用本地模拟端点依次返回 503 → 503 → 200，任务最终成功且只渲染一次输出；
  流式开始后断连不重试，直接给出可读错误。

### 4.4 日志落盘与轮转

**现状**：只写 stderr，IDE 控制台滚动后难以回溯。

**设计**

- `__main__.py` 新增参数：`--log-file <path>`、`--no-log-file`。
- 默认路径：`~/.agent/logs/core-YYYYMMDD.log`；`RotatingFileHandler(maxBytes=5MB, backupCount=5)`。
- 保留 stderr 输出（IDE 可见），文件为补充。
- 日志格式不变：`%(asctime)s %(levelname)s %(name)s: %(message)s`。
- **验收**：连续运行产生多个 5MB 分段且总量不超过 30MB；`--no-log-file` 时不创建任何文件。

### 4.5 两端设置对齐

IntelliJ 端 `pushSettings` 现缺 `tool_concurrency` / `subagent_concurrency` / `temperature` /
`max_tokens` / `context_window`，导致两端行为不一致。

| 字段 | IntelliJ 来源 | VS Code 键 | 默认 |
|---|---|---|---|
| `tool_concurrency` | 设置页「工具并发」 | `agentCoder.toolConcurrency` | 4 |
| `subagent_concurrency` | 设置页「子代理并发」 | `agentCoder.subagentConcurrency` | 3 |
| `temperature` | 设置页「温度」 | `agentCoder.temperature` | 0.2 |
| `max_tokens` | 设置页「单轮最大输出」 | `agentCoder.maxTokens` | 4096 |
| `context_window` | §4.1 | `agentCoder.contextWindow` | 32000 |

**验收**：同一配置在两端下发给 Core 的 `settings/update` 内容一致（可加一个协议冒烟断言）。

---

## 5. v2.6.0 · 闭环能力

> 本版本是收益最大的一版：Agent 从“能改”变成“能验证”。

### 5.1 `run_command` 终端工具（D2）

**ToolDef**（`core/src/agent_core/tools/remote.py`）

```python
ToolDef(
    name="run_command",
    description="在项目根目录执行一条命令并返回输出（默认 120 秒超时）。"
                "白名单内的构建/测试命令直接执行，其余命令需要用户确认。",
    parameters=_schema({
        "command": {"type": "string"},
        "cwd":     {"type": "string", "description": "相对项目根的子目录（可选）"},
        "timeout_sec": {"type": "integer", "minimum": 1, "maximum": 600},
    }, ["command"]),
    permission=Permission.CONFIRM,   # 兜底；运行时按白名单下发 effective permission
    timeout_sec=130,
)
```

**权限计算（Core 侧，策略不下放）**

`rpc/server.py::_invoke_shell_tool` 中：

```python
if call.name == "run_command":
    decision = decide_command(self.config, str(call.arguments.get("command", "")))
    # decision ∈ {"auto", "confirm", "deny"}
```

- 危险命令（`deny`）：`rm -rf`、`del /f /s`、`format `、`shutdown`、`git reset --hard`、`git push`、
  `mkfs`、`:(){`、`curl | sh`、`Invoke-Expression`。命中直接返回失败，**不弹窗**，错误文案说明原因。
- 白名单前缀（`auto`，来自 `ChatConfig.command_allowlist`）：
  `mvn `、`gradle `、`npm `、`npx `、`pnpm `、`yarn `、`pytest`、`python -m unittest`、`python -m pytest`、
  `cargo `、`go test`、`git status`、`git diff`、`git log`、`git branch`、`ls`、`dir`、`cat `、`type `、
  `node -v`、`java -version`、`dotnet test`。
- 其余为 `confirm`。
- `tool/invoke` 的 `permission` 字段改为下发**计算后的值**（字段语义从“静态定义”变为“本次生效值”，
  Shell 无需改动判断逻辑）。
- `ChatConfig.command_allowlist: List[str]` 默认值为上面的列表；设置页提供多行文本框覆盖，
  VS Code 提供数组设置项。

**Shell 实现**

- IntelliJ：新增 `CommandTools.java`，`ProcessBuilder` 启动；工作目录 = `projectRoot/cwd`，
  且 `cwd` 必须落在项目根内（复用 `FileTools.resolve` 的校验思路）；
  合并 stderr 到 stdout；输出截断到 8000 字符（保留头 60% + 尾 40%，中间标注被截断的行数）；
  超时后 `destroyForcibly` 并返回“已终止（超时 N 秒）”。
- VS Code：`vscode/src/tools.ts` 新增同名分发，用 `child_process.spawn` + `AbortSignal.timeout`。
- 平台差异：Windows 通过 `cmd /c`，Unix 通过 `/bin/sh -c`；命令字符串原样传入，不做 shell 转义改写。

**验收（端到端剧本）**

1. 对话：“跑一下 core 的单测”。Agent 调用 `run_command` 执行 `python -m unittest discover -s tests`
   （白名单内，不弹窗），读到 173 例通过。
2. 人为制造失败用例后重试：Agent 能读到失败堆栈、定位文件、修改、复跑通过。
3. 请求 `rm -rf .` → 直接拒绝并说明“危险命令不执行”，模型改走 `delete_file` 的单文件确认流程。
4. 请求 `git push` → 拒绝（D4 约束：推送由人执行）。

### 5.2 `diagnostics` 诊断工具

**ToolDef**

```python
ToolDef(
    name="diagnostics",
    description="返回项目中编译错误与检查告警（可选按路径过滤），最多 50 条。",
    parameters=_schema({
        "path": {"type": "string", "description": "文件或目录（可选，缺省为整个项目）"},
        "severity": {"type": "string", "enum": ["error", "warning", "all"]},
    }),
    permission=Permission.AUTO,
    timeout_sec=30,
)
```

**返回结构**（`ToolResult.content` 为 JSON 字符串，便于模型精确引用）

```json
{"count": 2, "truncated": false, "items": [
  {"file": "src/A.java", "line": 42, "column": 9, "severity": "error",
   "message": "cannot find symbol", "source": "javac"}
]}
```

**实现**

- IntelliJ：`com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl#getHighlights` 或
  `DiagnosticsCollector`（按当前平台 API 可用性选择，实现时以编译通过为准），过滤 `HighlightSeverity`。
- VS Code：`vscode.languages.getDiagnostics()`，按 URI 前缀过滤项目内文件。
- 只读，无确认。

**验收**：故意写一行语法错误后对话“看看现在有什么编译错误”，Agent 能返回该文件与行号，
并在修复后再次调用确认归零。

### 5.3 Plan / TODO 清单

**工具**（Core 内置，注册在 `agent/` 下，`remote=False`）

```python
ToolDef(
    name="todo_write",
    description="创建或更新本次任务的任务清单（整表覆盖）。复杂任务开始前先列清单，完成一项改一项。",
    parameters=_schema({
        "todos": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "id": {"type": "string"},
                "content": {"type": "string"},
                "status": {"type": "string",
                           "enum": ["pending", "in_progress", "completed", "cancelled"]},
            },
            "required": ["id", "content", "status"],
        }},
    }, ["todos"]),
    permission=Permission.AUTO,
    remote=False,
)
```

- 存储：`Session.todos: List[Dict]`，随会话 JSON 落盘（`agent/store.py` 版本号 +1，旧会话无此字段时
  读为 `[]`）。
- 事件：每次写入推送 `chat/todos`，payload `{"taskId", "todos": [...]}`。
- IntelliJ：`ChatSessionPanel` 顶部折叠区渲染清单（`☐ / ◐ / ☑`），完成后自动折叠。
- VS Code：webview 顶部同构区域。
- Prompt：`SYSTEM_PROMPT` 增加一条——“任务需要 3 步以上时，先用 `todo_write` 列清单，
  开始做某项时置为 `in_progress`，完成后置为 `completed`；同一时间最多一个 `in_progress`。”

**与现有约束的关系**

- 清单只影响展示与模型自律，不消耗额外轮次；`max_iterations` 语义不变。
- 会话取消时，`in_progress` 项保持不变（保持真实状态），不自动置为 cancelled。

**验收**：让 Agent 做“给 3 个文件加同一段注释”，全程能看到清单从 3 个 pending 逐步走到 completed。

---

## 6. v2.7.0 · 多端对齐

### 6.1 VS Code 行内补全

- 新文件：`vscode/src/inline.ts`。
- 注册：`vscode.languages.registerInlineCompletionItemProvider({ pattern: "**" }, provider)`。
- 触发：文档变更后 300 ms 防抖；`CancellationToken` 取消在途请求。
- 请求：复用现有 `inline/complete`（参数 `prefix` / `suffix` / `file`），超时 1.5 s 静默返回空。
- 开关：`agentCoder.inlineCompletion.enabled`（默认 true）。
- 限制：单文件 > 512 KB、二进制、无 Core 连接时直接返回空。
- **验收**：停笔 300 ms 出现 ghost text，Tab 接受、Esc 取消；Core 未启动时输入无卡顿。

### 6.2 设置项对齐

补齐 `agentCoder.embeddingModel`、`agentCoder.autoAcceptEdits`（已有）、`contextWindow`、
`temperature`、`maxTokens`、`commandAllowlist`；`settings/update` 的字段集合与 IntelliJ 完全一致
（见 §4.5 表）。

### 6.3 API Key 迁移到 SecretStorage

- 新键：`context.secrets`（`agentCoder.apiKey`）。
- 迁移：`activate()` 时若 settings 中有明文 key 且 secrets 为空 → 写入 secrets → 清空 settings 值 →
  状态栏提示“已把 API Key 迁移到安全存储”。
- 读取顺序：secrets → `OPENAI_API_KEY` 环境变量 → 空。
- **验收**：设置面板不再显示明文；卸载重装后 secrets 仍有效；旧用户首次启动自动迁移且对话不中断。

### 6.4 历史会话 UI

- 面板顶部「历史」按钮 → 列表（时间、首条提问摘要、消息数、创建/更新时间）。
- 操作：回放、继续、删除（带确认与释放空间提示），全部走既有 `session/list|get|delete`。
- **验收**：重启 VS Code 后列表仍在；删除后磁盘文件消失。

### 6.5 两端能力矩阵（发布前核对）

| 能力 | IntelliJ | VS Code（2.7.0 后） |
|---|---|---|
| 流式对话 / 工具 / 记忆 / Skill / MCP / 子代理 | 有 | 有 |
| 行内补全 | 有 | **有（新增）** |
| 语义索引设置 | 有 | **有（新增）** |
| 多会话标签 | 有 | 单会话（接受差异，记入 FAQ） |
| 历史会话 | 有 | **有（新增）** |
| Key 加密存储 | PasswordSafe | **SecretStorage（新增）** |

---

## 7. v3.0.0 · 产品化

### 7.1 记忆沉淀（候选 → 人工确认 → 转正）

**动机**：现在只有模型主动调用 `memory_save`，跨会话学习几乎不发生。

**设计（避免污染是关键）**

1. 会话结束时（`chat/finish` 之后，后台异步）抽取候选记忆：
   - 规则一：用户消息中出现“记住 / 以后都 / 不要 / 必须”等指令句 → 必选。
   - 规则二：同一轮内被用户纠正的事实（“不对，应该是…”）→ 候选。
   - 规则三：其余不做（宁可少，不可脏）。
2. 候选写入 `<项目>/.agent/memories/_pending/<sessionId>.md`，带 `source` / `created` 头部；
   **不进入 `memory_overview`**，不影响后续对话。
3. 新增 RPC：`memory/pending`（列出）、`memory/promote`（转正到 `memories/`）、`memory/discard`。
4. UI：「历史」旁新增「记忆」页签，列出待确认条目，支持编辑后确认。
5. 转正时做去重：`MemoryStore` 用 FTS5 相似度（top-1 距离阈值）提示“可能与已有条目重复”。

**验收**：一次会话中出现 2 条“以后都用中文写提交信息”类指令，结束后记忆页签出现 2 条候选；
确认后 `memory_overview` 在下一次对话中出现该条。

### 7.2 会话全文检索

- `agent/store.py` 增加 `sessions_fts`（FTS5，列：`session_id`、`title`、`content`），
  保存会话时增量更新；首次启动为历史会话建索引（一次性迁移，带进度通知）。
- 新增 RPC `session/search`，参数 `{"query": "...", "limit": 20}`，返回 `{sessionId, title, snippet, updatedAt}`。
- UI：历史面板顶部搜索框，输入即搜（300 ms 防抖），命中片段高亮。
- **验收**：搜“prefix cache”能命中两周前会话中的相关段落，点击直达该会话。

### 7.3 发布与分发（D3：先 IntelliJ）

- Marketplace 上架：
  - `plugin.xml` 增加 `<change-notes>`（每个版本一段，来源 `CHANGELOG.md`）。
  - 产物校验：zip 内 `agent-core.exe` 存在、`--version` 与 `plugin.xml` 一致、`--native-info` 为 `rust`。
  - 上传 zip → 审核 → 发布；保留一份 GitHub Release 附件。
- 自动更新：由 IntelliJ 平台接管，不自行实现。
- VS Code：2.7.0 完成后评估上架，本轮不做。
- **验收**：Marketplace 页面可安装，安装后无需 Python 即可对话。

### 7.4 性能基线

新增 `scripts/bench_core.py`（本地、可重复）：

| 指标 | 采集方式 | 目标 |
|---|---|---|
| 冷启动 | 进程启动 → 收到 `core/hello` | < 3.0 s |
| 空闲内存 RSS | 启动后 10 s | < 120 MB |
| 单轮延迟（mock provider） | `chat/start` → `chat/finish` | < 300 ms |
| 千条会话列表 | `session/list` | < 150 ms |

- CI 只记录、不 gate（避免机器差异导致假红）；连续两周超标再定门槛。

基线实测（Windows 11 / Python 3.9 / 千条会话，三次运行取范围）：

| 指标 | 实测 | 目标 |
|---|---|---|
| 冷启动 | 343 ~ 398 ms | < 3.0 s |
| 空闲内存 RSS | 97.4 ~ 97.7 MB | < 120 MB |
| 单轮延迟（mock provider） | 20.6 ~ 22.1 ms | < 300 ms |
| 千条会话列表 | 105 ~ 110 ms | < 150 ms |
| 首次建索引（info） | 77 ~ 86 ms | — |

千条会话列表的达标来自两处改动：`SessionStore.list_sessions()` 用 8 线程并行读文件；
首次建索引改用单连接批量写入（`SessionSearch.index_many`），并在 `bench_core.py` 里
先等 `session/indexProgress` 的 `finished` 再测列表，避免把磁盘争用算进端点耗时。

#### 传输格式：保持 JSON Lines（实测结论）

`bench_core.py` 在 3.3.0 增补了协议侧指标：双向帧数、字节数、`chat/token` 帧数，以及
JSON 编解码耗时占（预热+一轮+列表）墙钟时间的比例。实测（Windows 11 / 200 条会话）：

| 场景 | 帧数 | 字节数 | `chat/token` 帧 | 编解码占比 | 单轮延迟 |
|---|---|---|---|---|---|
| 短回答（2 段） | 21 | 7.7 KB | 2 | 0.1% | 24.7 ms |
| 长回答（800 段 ×2 轮） | 1601 | 152.8 KB | 1582 | 2.0% | 46.2 ms |

结论：**不引入 protobuf**。即使按 800 段长回答压测，JSON 编解码也只占 2% 墙钟；
成本大头是**帧数量**（1582 次进程唤醒 + 1582 次 UI 回调）而不是字节数。换二进制格式
只能把 152.8 KB 降到 60~90 KB、2% 降到 1% 左右，却要付出三端 codegen、exe 体积、
丢可读排障，以及 proto3 默认值破坏「部分字段合并」语义的代价（`active_team: ""`、
`usage=None` 都会被静默当成零值）。

性价比更高的四步（保持 JSON，均已列入待办）：
1. `chat/token` 节流合并（攒 16~32 ms 或 ≥32 字符发一帧），帧数直接降一个数量级；
2. 大 payload（文件内容/diff/搜索结果）改「引用 ID + 按需拉取」，不塞单帧；
3. 超阈值大帧 gzip + `"enc":"gzip"` 标记，旧 Shell 忽略字段照旧读明文；
4. 大 payload 只 `dumps` 一次，避免重复序列化。

重新评估的门槛（满足任一才考虑二进制）：单帧 P95 > 64 KB 且每秒 > 10 帧；要支持远程
Core（跨机器/容器）；出现第三个客户端需要强 schema；实测编解码占比 > 15%。真要上，
先用 MessagePack/CBOR（无需 codegen），并同时切换成**长度前缀分帧**（否则 base64
的 +33% 会吃掉全部收益）。

### 7.5 国际化

- 范围：仅 Shell UI 文案（IntelliJ `AgentBundle.properties` + `AgentBundle_en.properties`；
  VS Code `l10n` 或 `package.nls.json`）。
- 不翻译：Core 的提示词、工具描述（模型侧中文可接受，且翻译会破坏 prompt 稳定性）。
- **验收**：IDE 语言切英文后，面板按钮/状态行/设置页标签为英文，对话内容不受影响。

实现（3.0.0）：

- IntelliJ：`com.workreport.agent.AgentBundle`（`DynamicBundle`）+
  `resources/messages/AgentBundle.properties`（中文基线）/ `AgentBundle_en.properties`（英文），
  共 117 个键；`plugin.xml` 用 `<resource-bundle>` 让动作文案与设置页标题也随语言切换。
- 覆盖范围：面板按钮与状态行（`AgentChatPanel`）、输入区与菜单（`ChatComposer`）、
  会话状态（`ChatSessionPanel`）、任务清单（`TodoStrip`）、设置页表头/分组/字段标签。
- 未覆盖（刻意保留中文）：设置页括号里的长说明与 tooltip、Core 的提示词与工具描述。
- 3.3.0 补充：工具 / 子代理进度行**刻意不走 bundle**，统一为英文日志风格（`read_file · path · done · 0.4s`），
  因为它是面向开发者的时间线；思考行标题走 `ui.reasoning.title` / `ui.reasoning.chars`（中英两个包都有）。
- VS Code：`panel.ts` 的 Webview 文案仍为中文，等其单独上架前再补 `package.nls.json`。

### 7.6 本地使用统计（D4）

- 文件：`~/.agent/logs/usage.jsonl`，每任务一行：

```json
{"ts":"2026-12-01T10:11:12Z","taskId":"...","model":"qwen2.5-7b",
 "in":1240,"out":380,"toolCalls":5,"durationMs":18342,"ok":true}
```

- 写入时机 `chat/finish` / `chat/error`；单文件超过 10 MB 轮转为 `usage-YYYYMM.jsonl`。
- 不新增任何网络请求；不做用户标识。
- 提供 `usage/report`（本地聚合，返回近 7/30 天统计）供 UI 展示，**默认关闭**，用户主动点击才计算。

---

## 8. 协议变更汇总

### 8.1 新增请求（Shell → Core）

| 方法 | 参数 | 引入版本 |
|---|---|---|
| `session/search` | `query`, `limit` | 3.0.0 |
| `memory/pending` | — | 3.0.0 |
| `memory/promote` | `id`, `content?` | 3.0.0 |
| `memory/discard` | `id` | 3.0.0 |
| `usage/report` | `days` | 3.0.0 |

### 8.2 新增通知（Core → Shell）

| 方法 | 负载 | 引入版本 |
|---|---|---|
| `chat/todos` | `taskId`, `todos[]` | 2.6.0 |
| `chat/reasoning` | `taskId`, `delta`（`delta.reasoning_content` / `delta.reasoning` / Ollama `message.thinking`） | 3.3.0 |
| `memory/pendingChanged` | `count` | 3.0.0 |
| `session/indexProgress` | `done`, `total`, `changed`, `finished` | 3.0.0 |

### 8.3 字段变更（向后兼容，只增不改）

| 位置 | 字段 | 版本 |
|---|---|---|
| `chat/finish` | `usageTotal` | 2.5.0 |
| `tool/invoke` | `permission` 语义改为“本次生效值” | 2.6.0 |
| `core/hello` | `coreVersion` 自动跟随包版本（字段原本已存在） | 2.4.10 |
| `session/get` | `todos` | 2.6.0 |
| CLI | `--version`、`--log-file`、`--no-log-file` | 2.4.10 / 2.5.0 |

兼容原则：Shell 必须忽略未知字段；Core 必须容忍缺失字段（默认值兜底）。

---

## 9. 兼容性与迁移

| 对象 | 迁移动作 | 风险 |
|---|---|---|
| 设置 XML（`workReportSettings.xml`） | 新字段读取时 0/空 → 默认值（沿用 `maxIterations` 写法） | 低 |
| 会话 JSON | 增加 `version` 与 `todos`；旧文件读为 `todos: []` | 低 |
| 会话 FTS 索引 | 首次启动全量建索引，带进度通知；失败可重试 | 中（大会话目录耗时） |
| VS Code 旧 Key | secrets 为空时自动迁移并清空明文 | 中（迁移失败需回退提示） |
| Core 版本 | 构建注入，`--version` 与插件一致 | 低 |

数据目录不变：`~/.agent/data/<项目位置哈希>/`。

---

## 10. 测试与验收策略

| 层级 | 范围 | 门禁 |
|---|---|---|
| 单元测试 | 预算计算、用量聚合、重试判定、命令权限判定、TODO 存储、FTS 检索 | 全绿，新增功能必须有对应用例 |
| 协议冒烟 | `smoke_tools` / `smoke_memory` / `smoke_mcp` / `smoke_subagent` + 新增 `smoke_command` | 全绿 |
| 契约测试 | 新增 `test_protocol_contract.py`：对 `chat/finish` / `tool/invoke` / `chat/todos` 的字段做快照断言 | 全绿 |
| 编译 | `mvn -q -DskipTests compile`、`npm run compile` | 通过 |
| 端到端剧本 | §5.1 四个剧本、§5.2、§5.3、§6.1、§7.1、§7.2 | 手工执行并记录到 Release 说明 |
| CI | 五个 job，覆盖 master | 全绿 |

新增冒烟脚本约定：沿用现有模式（本地模拟 OpenAI 兼容端点 + 临时 data 目录），
**不使用“最后一条消息角色”判断收尾**，统一用“历史中是否已有 tool 结果”。

---

## 11. 风险登记

| # | 风险 | 概率 | 影响 | 应对 |
|---|---|---|---|---|
| R1 | 终端工具被用于危险命令 | 中 | 高 | 危险命令硬拒绝 + 白名单 + confirm 兜底；拒绝路径写单测 |
| R2 | 上下文预算调到过大导致端点 400 | 中 | 中 | 上限 200k、系数 0.75、错误提示明确指向该设置 |
| R3 | 重试导致重复工具副作用 | 低 | 高 | 流式已开始不重试；重试只覆盖请求建立阶段 |
| R4 | VS Code SecretStorage 迁移失败 | 低 | 中 | 失败保留明文并提示，不阻断对话 |
| R5 | 会话 FTS 首次建索引阻塞启动 | 中 | 中 | 后台任务 + 进度事件；超过 5 s 提示可跳过 |
| R6 | 记忆沉淀污染长期记忆 | 中 | 中 | 只生成候选、必须人工确认；去重提示 |
| R7 | 30 MB 包体导致启动慢 | 中 | 中 | §7.4 基线；必要时压缩打包内容（排除测试资源） |

---

## 12. 排期

| 周 | 日期 | 版本 | 交付 |
|---|---|---|---|
| W1 | 11-16 ~ 11-20 | v2.4.10 | push / CI / About / Core 版本 / 文档口径 |
| W2 | 11-23 ~ 11-27 | v2.5.0 | 预算 / 用量 / 重试 / 日志 / 设置对齐 |
| W3–W4 | 11-30 ~ 12-11 | v2.6.0 | `run_command` / `diagnostics` / Plan-TODO |
| W5–W6 | 12-14 ~ 12-25 | v2.7.0 | VS Code 补全 / 设置 / SecretStorage / 历史 |
| W7–W8 | 12-28 ~ 01-08 | v3.0.0 | 记忆沉淀 / 检索 / 上架 / 基线 / i18n / 统计 |

每周末做一次 `verify_all.ps1` 全量回归，未绿不进入下一版本。

---

## 13. 交付清单（按版本勾选）

### v2.4.10

- [x] `ci.yml` 触发分支改为 `[develop, master]`
- [ ] GitHub About 更新（Description + Topics，仓库设置页手动）
- [x] `agent_core.__version__` 支持环境变量注入
- [x] `--version` 参数与 `core/hello.coreVersion`
- [x] `build_core.ps1` 注入版本
- [x] README 终端表述与 `__init__.py` 文档字符串修正
- [ ] 三分支 push（维护者手动）

### v2.5.0

- [x] `ChatConfig.context_window` + `budget_for` + 调用点
- [x] `usage_total` 聚合与 `usageTotal` 事件
- [x] 两端用量展示
- [x] 重试退避 + 错误文案
- [x] 日志文件与轮转
- [x] 设置页四项 + 双端下发对齐
- [x] `test_context` / `test_streaming` / `test_transport_errors` 补充用例

### v2.6.0

- [x] `run_command` 定义 + 权限判定 + 两端实现
- [x] 危险命令单测 + 白名单单测
- [x] `smoke_command.py`
- [x] `diagnostics` 两端实现
- [x] `todo_write` + `chat/todos` + 会话落盘 + 两端 UI
- [x] `SYSTEM_PROMPT` 计划条目
- [ ] 四个端到端剧本通过（自动化已覆盖 §5.1 剧本 1/3/4；剧本 2 需真实模型手工执行）

### v2.7.0

- [x] `vscode/src/inline.ts`
- [x] 设置项补齐与 `settings/update` 一致性断言
- [x] SecretStorage 迁移
- [x] 历史会话 UI
- [x] 能力矩阵核对表归档到 `vscode/README.md`

### v3.0.0

- [x] 记忆候选生成与确认流程（3 个 RPC + UI）
- [x] 会话 FTS + `session/search` + 搜索框
- [x] Marketplace 上架与 `change-notes`（`change-notes` 已写入；上传审核需维护者在 Marketplace 页面操作）
- [x] `scripts/bench_core.py` + 基线数据
- [x] i18n（Shell 文案：按钮/状态行/设置页标签，见 §7.5）
- [x] `usage.jsonl` 本地统计与 `usage/report`
- [x] 全量回归 + Release 说明（241 例单测 OK/跳过 1 + 7 个协议冒烟 + IntelliJ 编译 + VS Code 编译/RPC 冒烟全绿；Release 说明见 `CHANGELOG.md`，发版时直接粘贴）
