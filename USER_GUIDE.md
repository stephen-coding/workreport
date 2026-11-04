# Agent Coding Assistant 使用手册

> 适用版本：**2.2.0** ｜ 适用 IDE：**IntelliJ IDEA 2025.2 ~ 2026.2**（`since-build=252`）
>
> 本手册面向使用者。想了解内部实现（RPC 协议、FIM 补全原理、向量检索细节），
> 请看 [技术详情文档](docs/INLINE_COMPLETION_AND_SEMANTIC_INDEX.md)。

---

## 0. 先说明一个术语：什么叫「行内补全（Ghost text）」

**Ghost text** 是编辑器领域的通用叫法：补全结果不出现在弹窗里，而是以**灰色文字**直接绘制在光标位置。
因为颜色很淡、像一层薄薄的影子叠在代码上，英文才取了 ghost（影绰之物）这个名字；
JetBrains 官方文档称它为 **Inline Completion**，本插件统一译作 **「行内补全」**。

> 早期文档里出现过直译的「幽灵补全」，属于误译，已全部废弃。

它的行为和普通代码补全的区别：

| | 普通代码补全（IDE 自带） | 行内补全（本插件） |
|---|---|---|
| 结果形态 | 下拉候选列表 | 光标后的灰色整段文字 |
| 内容来源 | 符号表 / 模板 | 大模型按"光标前后文"生成 |
| 接受方式 | Enter / Tab 选中 | **Tab** 整段接受、**Esc** 取消 |
| 适用场景 | 补一个标识符 | 补一整行/一整段（含后续几行） |

本手册后续统一写「行内补全」，配置项名称与 IDE 界面保持一致。

---

## 1. 插件能做什么

| 能力 | 说明 | 入口 |
|---|---|---|
| 对话式编码 | 描述任务，Agent 自己读文件、搜代码、改代码、跑验证 | 底部 **AgentCoder** 工具窗口 |
| 多模型切换 | 设置页维护模型列表，面板下拉框随时切换，无需重启 IDE | 面板右上「模型:」下拉 |
| 工具执行 | 读写文件、正则搜索、Git 日志/diff、目录浏览，写操作先看 diff | 对话中自动触发 |
| 自动执行 | 勾选后写文件不再逐次确认，直接落盘（仍可 `revert_file` 回退） | 面板右上「自动执行」 |
| 行内补全 | 光标处灰色补全，Tab 接受 / Esc 取消 | 编辑器内自动触发 |
| 语义索引 | 用自然语言"这段逻辑在哪实现"来检索代码，不依赖关键词 | 对话中自动调用 |
| 报告生成 | 内置日报/周报/季报/年报 4 个 Skill | 对话，或菜单 `Tools` → `生成今日日报` |
| MCP 接入 | 挂载外部 MCP 服务，工具以 `mcp__服务名__工具名` 暴露 | 设置页 MCP JSON |
| 记忆 | 跨会话记住偏好与结论（本地 SQLite） | 对话中自动 |
| 子代理 | code-reviewer / bug-hunter / test-generator 并行跑审查任务 | 对话中自动 |

---

## 2. 安装

### 2.1 前置要求

| 条件 | 说明 |
|---|---|
| IDE 版本 | IntelliJ IDEA 2025.2 ~ 2026.2（构建号 `252.*` ~ `262.*`），更早版本无法加载 |
| Python | **不需要安装**：zip 内已内置 `agent-core/agent-core.exe` |
| Git4Idea | IDEA 内置，无需单独安装 |
| 项目 | 建议在 Git 仓库内使用（Git 工具与报告 Skill 依赖它） |
| 模型端点 | 本地 Ollama 或任意 OpenAI 兼容服务（云端/自建网关均可） |

### 2.2 安装步骤

1. 拿到 `workreport-ai-assistant-2.2.0.zip`（Release 下载，或本地 `powershell -File scripts/build_plugin.ps1` 生成）
2. IDEA → `Settings` → `Plugins` → ⚙️ → `Install Plugin from Disk...`
3. 选择该 zip → `Restart IDE`
4. 重启后底部出现 **AgentCoder** 工具窗口；菜单 `Tools` 下多出 `生成今日日报`

> **升级提醒**：如果版本号没变（例如都是 2.2.0），IDEA 可能仍显示旧缓存。
> 让版本号变化（或先卸载再装）才能确保界面与代码一致。

---

## 3. 首次配置

### 3.1 打开设置页

两种方式等价：

- `Settings` → `Tools` → **Agent Coding Assistant**
- Agent 面板右下角 **「设置」** 按钮

### 3.2 配置模型（必做）

设置页顶部是一张**模型列表**表格，一行为一个模型：

| 列 | 说明 | 示例 |
|---|---|---|
| 名称 | 自定义标识，面板下拉框显示这个名称，也是 API Key 的存储键 | `本地Qwen`、`DeepSeek` |
| 类型（ollama / openai_compat） | `ollama` 走本地 Ollama 协议；含 `openai` 字样即按 OpenAI 兼容处理 | `ollama` |
| 端点 | **聊天**接口的完整地址（行内补全与 Embedding 地址由它自动推导） | `http://localhost:11434/api/chat` |
| 模型 | 模型标识，必须与服务端一致 | `qwen2.5-coder:7b` |
| API Key | 云端必填；本地 Ollama 留空。已保存的显示为 `******`，留空或保持原样表示不修改 | `sk-...` |

点击 **添加模型** / **删除选中** 维护列表；`Apply` 时会校验（名称、类型、端点、模型都不能为空）。

**典型配置**

```text
本地 Ollama：
  名称=本地Qwen  类型=ollama  端点=http://localhost:11434/api/chat  模型=qwen2.5-coder:7b  Key=（空）

DeepSeek（OpenAI 兼容）：
  名称=DeepSeek  类型=openai_compat  端点=https://api.deepseek.com/v1/chat/completions  模型=deepseek-chat  Key=sk-...
```

> 端点必须是**完整路径**，不能只填域名，否则会拿到 HTML 页面导致解析失败。

### 3.3 其他配置项

| 配置项 | 作用 | 默认 |
|---|---|---|
| Git 作者过滤 | 报告 Skill 采集 Git 日志时只统计该作者；留空=所有人。建议与 `git config user.name` 完全一致 | 空 |
| MCP 服务（JSON） | 挂载 MCP 服务器，见 [§8](#8-mcp-接入) | 空 |
| 编辑器内行内补全（Ghost text） | 行内补全总开关 | 开启 |
| Embedding 模型 | 语义索引用向量模型。Ollama 推荐 `nomic-embed-text`；OpenAI 兼容用 `text-embedding-3-small` | `nomic-embed-text` |
| Core 可执行文件 / Core 目录 / Python 解释器 | 仅开发调试需要；插件 zip 已内置 Core，一般留空 | 空 |

**保存后何时生效**：点 `Apply`/`OK` 后，插件会在下一条消息时重建 Core 进程；面板模型下拉框会立即刷新。

---

## 4. AgentCoder 面板

```
┌──────────────────────────────────────────────────────────────┐
│ 就绪                                  模型: [本地Qwen ▾] □自动执行 │
├──────────────────────────────────────────────────────────────┤
│ 你：把 LoginService 里的同步调用改成异步                          │
│ 助手：先看一下现有实现……                                         │
│   [工具] read_file 执行中...                                     │
│   [工具] 完成：LoginService.java (120 行)                        │
│   [工具] edit_file 执行中...                                     │
│   [工具] 完成：已应用修改                                         │
│ 助手：已完成，改动点如下……                                        │
├──────────────────────────────────────────────────────────────┤
│ [输入框：向 Agent 描述你的任务……            ]  [设置][停止][发送] │
└──────────────────────────────────────────────────────────────┘
```

### 4.1 操作要点

- **发送**：输入框回车或点「发送」。同一时刻只允许一个任务，任务未结束时的发送会被忽略
- **停止**：点「停止」发送 `chat/cancel`，当前任务立即终止，已产生的输出保留
- **模型**：下拉切换即时生效（下发 `settings/update`），无需重启 IDE 或 Core
- **自动执行**：勾选后写文件/删除不再弹 diff 确认；适合"改完直接跑"，出错可用 `revert_file` 回退
- **设置**：打开设置页；关闭后若当前没有任务在跑，会重建 Core 以应用新配置

### 4.2 界面上的状态行

| 状态行 | 含义 |
|---|---|
| `[工具] xxx 执行中...` / `[工具] 完成：…` / `失败` | 工具调用进度（Core 通知 `chat/toolStart` / `chat/toolEnd`） |
| `[子代理] xxx 开始：…` / `完成` / `失败` | 子代理并行任务 |
| `[确认] MCP 服务 … 请求调用 …` + 弹窗 | MCP 工具调用需要你点「允许 / 拒绝」 |
| `[错误:CODE] 说明` | Core 返回的错误（如 `LLM_ERROR`、`TOOL_TIMEOUT`） |

### 4.3 常用对话模板

```text
# 理解代码
看下 AgentChatPanel 里 confirm 消息是怎么回传给 Core 的，用列表说明

# 改代码 + 自测
修复 subagent.py 里工具白名单漏掉 git_log 的问题，改完跑对应单测

# 语义找代码（会自动用语义索引）
这段"权限被拒绝后降级"的逻辑在哪实现的？

# 生成报告
生成今天的日报
帮我写本周周报，重点突出重构
生成 2026 年第一季度季报
```

---

## 5. 行内补全（Ghost text）

### 5.1 怎么用

1. 确认设置页勾选了「编辑器内行内补全（Ghost text）」（默认开启）
2. 在本地文件里正常写代码，**停笔约 0.3 秒**
3. 光标后出现灰色文字：
   - **Tab** 接受整段
   - **Esc** 取消
   - 继续输入则自动忽略该建议

### 5.2 触发条件（不满足就不会补全）

| 条件 | 说明 |
|---|---|
| 文件在本地文件系统 | 远程/内存文件跳过 |
| 文件 ≤ 512 KB | 大文件跳过，避免拖慢输入 |
| 光标前有内容 | 空文件开头不会补全 |
| 模型支持 FIM | Ollama 需支持 `/api/generate` 的 `suffix`；OpenAI 兼容端点需有 `/v1/completions` |
| Core 可用 | 首次触发需要启动 Core，稍等几秒 |

### 5.3 中文提示词对不上？

行内补全**不是**把整段对话发过去，而是像"让模型接着写"：

- 发送内容：光标前 **4000 字符** + 光标后 **1200 字符**
- 期望返回：只返回要插入光标处的那一段
- 模型温度固定较低（0.1），并限制 `max_tokens≈128`，避免长篇大论

因此它擅长补完当前函数/语句，不擅长"帮我设计一个模块"——那种需求请用对话面板。

### 5.4 模型建议

| 场景 | 推荐 |
|---|---|
| 本地、离线 | `qwen2.5-coder:7b` / `codellama` 等支持 FIM 的补全模型 |
| 云端 | 支持 `/v1/completions` 且支持 `suffix` 的端点和模型 |
| 只支持 chat 的模型 | 行内补全不会报错，但基本不会给建议（静默返回空） |

---

## 6. 语义索引

### 6.1 它解决什么问题

`search_code` 是**正则**搜索：你得知道关键词。
`semantic_search` 是**语义**搜索：用自然语言描述意图即可，例如"用户登录后刷新 token 的逻辑"。

两者互补：知道名字用正则，只知道意图用语义。

### 6.2 怎么触发

- **自动**：对话里问"…在哪实现的""…相关代码"，Agent 会自行调用 `semantic_search`
- **手动建库**：说"重建代码索引"或"刷新索引"，Agent 调用 `build_index`；`full=true` 为清空重建
- 索引为空时 `semantic_search` 会提示先建索引

### 6.3 收录范围

| 规则 | 值 |
|---|---|
| 收录文件类型 | `.py .java .kt .kts .scala .go .rs .c/.h/.cpp .cs .ts/.tsx .js/.jsx .vue .rb .php .swift .dart .lua .sh .ps1 .sql .md .rst .html .css/.scss .yml/.yaml .toml .gradle .xml .json` |
| 跳过目录 | `.git .idea .gradle .venv venv node_modules __pycache__ target build dist out vendor .next .nuxt coverage .mypy_cache .pytest_cache .tox bin obj` |
| 单文件上限 | 400 KB |
| 单块上限 | 2400 字符，整块参与检索（返回时按 1200 字符截断展示） |

### 6.4 增量与重建

- 每个文件按 **sha1** 比对，只有内容变化的文件才会重新切块 + 重新算向量
- 已删除的文件会被自动清理（prune）
- **换 Embedding 模型会自动整库重建**（不同模型的向量不可比较）

### 6.5 数据存在哪

```text
~/.agent/data/<项目哈希>/
├── memory.db        # 记忆（SQLite FTS5）
├── memories/        # 记忆的 Markdown 镜像
└── code_index.db    # 语义索引（code_files / code_chunks 两表，向量以 BLOB 存同一文件）
```

> 没有独立的向量数据库进程：向量就是 SQLite 里的一列，这也是插件"零额外部署"的原因。

### 6.6 效果不佳怎么办

1. 对话里说"重建代码索引"（或 `build_index full=true`）
2. 确认设置页的 Embedding 模型与端点可用（`ollama list` 里能看到该模型）
3. 新增文件类型不在白名单里时，需要改 Core 的 `codeindex/chunker.py`（开发者操作）

---

## 7. 报告生成（内置 Skill）

| 报告 | 默认日期范围 | 输出板块 |
|---|---|---|
| 日报 `daily-report` | 今天 | 今日完成工作 / 明日计划 / 遇到的问题 |
| 周报 `weekly-report` | 本周一 ~ 今天 | 本周工作内容 / 下周计划 / 需要协调的问题 |
| 季报 `quarterly-report` | 本季度首日 ~ 今天 | 本季度主要成果 / 项目进展总结 / 下季度规划 / 风险与挑战 |
| 年报 `yearly-report` | 1 月 1 日 ~ 今天 | 年度工作回顾 / 年度成果总结 / 来年目标 / 个人成长与反思 |

**两种用法**

1. 菜单 `Tools` → `生成今日日报`：面板自动预填并发送（当前有任务时会提示稍后再试）
2. 对话里直接说：`生成今天的日报`、`写本周周报`、`生成 2025 年报`

**建议**：先在设置页把「Git 作者过滤」填成你的 `git config user.name`，
否则会把别人的提交也算进来；草稿生成后仍可在对话里继续改（如"再精简一点"）。

---

## 8. MCP 接入

### 8.1 配置位置

- 设置页 **MCP 服务（JSON）**：个人配置，跟着 IDE 走
- 项目内 `.agent/mcp.json`：团队共享，建议纳入版本控制；与设置页**合并**，同名以项目文件优先

### 8.2 JSON 写法（两种都支持）

```json
[
  { "name": "filesystem", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem"] }
]
```

```json
{
  "filesystem": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem"] }
}
```

### 8.3 使用与安全

- 挂载后工具以 `mcp__filesystem__read_file` 的形式出现在 Agent 工具列表
- 每次调用都会弹窗确认（显示服务名、工具名、参数）
- 连续拒绝 3 次，Core 会终止当前任务，避免反复骚扰
- MCP 服务由你本机启动，插件不会替你在外部注册任何服务

---

## 9. 记忆与子代理

**记忆**

- 工具：`memory_recall` / `memory_save` / `memory_delete`
- 存本地 SQLite FTS5，同时在 `memories/` 下保留 Markdown 镜像，便于人工查看
- 会话开始时会注入记忆概览；说"记住…"/"忘掉…"即可维护

**子代理**

- 内置 3 个：`code-reviewer`（代码审查）、`bug-hunter`（缺陷排查）、`test-generator`（测试生成）
- 由 `subagent_dispatch` 并行调度，每个子代理只能访问白名单工具（禁递归派生）
- 进度以 `[子代理] …` 状态行展示

---

## 10. 隐私与数据

| 数据 | 去向 | 存储位置 |
|---|---|---|
| 聊天请求 | 你配置的模型端点 | 不落盘（会话内存在内存） |
| 光标前后文（行内补全） | 你配置的模型端点 | 不落盘 |
| 代码块（语义索引） | 你配置的 Embedding 端点 | 向量存 `~/.agent/data/<项目哈希>/code_index.db` |
| 记忆 | 不发送（除被召回注入对话时） | `memory.db` + `memories/*.md` |
| API Key | 不发送给第三方 | IntelliJ PasswordSafe（系统密钥链，按模型名分开存） |
| Git 历史 | 仅在报告 Skill 中经 `git_log` 读取，随报告发送给模型端点 | 不落盘 |

- 换端点后，涉及报告的功能会重新弹一次隐私确认（`resetPrivacyIfEndpointChanged`）
- 想彻底清空本地数据：关闭 IDE 后删除 `~/.agent/data/<项目哈希>/` 即可（索引与记忆一起清）

---

## 11. 常见问题

### 11.1 面板一直显示「正在启动 Agent Core...」

1. 确认 zip 里有 `agent-core/agent-core.exe`（解压后可见）
2. 杀毒软件可能拦截新解压的可执行文件，加白名单后重启 IDE
3. 开发模式（未打包 Core）需要 `AGENT_CORE_HOME` + Python 3.9+

### 11.2 提示「未找到 Agent Core」

说明既没找到内置 exe，也没找到 Python。检查设置页三个路径项，或设置环境变量
`AGENT_CORE_EXE` / `AGENT_CORE_HOME` / `AGENT_PYTHON`。

### 11.3 模型下拉框是空的

设置页模型列表至少要有**一行完整配置**（名称/类型/端点/模型都非空），点 `Apply` 后回到面板即可看到。

### 11.4 报错 `LLM_ERROR` / 403 / 解析到 HTML

- 端点必须写到接口层级（`.../v1/chat/completions` 或 `.../api/chat`）
- 403 多为 Key 无权访问该模型，换模型或换 Key

### 11.5 行内补全不出现

| 可能原因 | 排查 |
|---|---|
| 总开关关闭 | 设置页勾选「编辑器内行内补全（Ghost text）」 |
| 文件不满足条件 | 远程文件、>512 KB 文件不补全 |
| 端点不提供 FIM | Ollama 模型不支持 `suffix`，或云端没有 `/v1/completions` |
| 刚启动 Core | 首次触发需等 Core 起来，继续输入即可 |
| 有报错弹窗？ | 设计上不会弹——失败一律静默 |

### 11.6 语义检索没结果 / 结果过时

1. 对话里说"重建代码索引"
2. 确认 Embedding 模型已在本机 Ollama 拉取（`ollama pull nomic-embed-text`）
3. 文件类型不在白名单或位于忽略目录时不会被收录

### 11.7 自动执行开了，怎么回退

在对话里说"把 xxx 文件回退到修改前"，Agent 调用 `revert_file`（基于本会话内存快照，只能回退本会话最后一次修改）。

### 11.8 关掉 IDE 后任务还在跑吗

不会。Core 是随插件启动的子进程，IDE 关闭时一起退出；未完成的会话不会自动续跑。

---

## 12. 附录

### 12.1 Agent 可用的工具

| 工具 | 作用 | 权限 |
|---|---|---|
| `read_file` | 读文件（可指定行范围） | 自动 |
| `list_dir` | 列目录（不递归） | 自动 |
| `search_code` | 正则搜索 | 自动 |
| `git_log` | 指定日期范围的提交记录 | 自动 |
| `git_diff` | 工作区未提交变更 | 自动 |
| `write_file` | 新建/覆盖文件 | 需确认（自动执行时跳过） |
| `edit_file` | 精确文本替换 | 需确认（自动执行时跳过） |
| `delete_file` | 删除文件 | 需确认 |
| `revert_file` | 回退本会话最后一次修改 | 自动 |
| `semantic_search` | 语义检索代码块 | 自动 |
| `build_index` | 增量/全量重建索引 | 自动 |
| `memory_recall` / `memory_save` / `memory_delete` | 记忆读写 | 自动 |
| `skill_list` / `skill_load` | 查看与加载 Skill | 自动 |
| `subagent_dispatch` / `subagent_list` | 派发与查看子代理 | 自动 |
| `mcp__<server>__<tool>` | MCP 外部工具 | 逐次确认 |

### 12.2 文件与配置位置

| 内容 | 位置 |
|---|---|
| 插件本体 | IDEA 配置目录 `plugins/` 下 |
| Core 可执行文件 | 插件安装目录 `agent-core/agent-core.exe` |
| 设置项 | IDE 配置（`ReportSettingsState`，XML 持久化） |
| API Key | 系统密钥链（PasswordSafe），按模型名分开存 |
| 记忆 / 索引 | `~/.agent/data/<项目哈希>/` |
| 项目级 MCP | `<项目>/.agent/mcp.json` |

### 12.3 源码构建（开发者）

```powershell
# 只编译插件
mvn -q -DskipTests compile

# 一键打包含内置 Core 的 zip（需要 Python 3.9+；有 cargo 时顺便编译 Rust 加速）
powershell -File scripts/build_plugin.ps1

# 全量校验：Core 单测 + 4 个协议冒烟 + Java 编译 + VS Code 编译/冒烟
powershell -File scripts/verify_all.ps1
```

产物：`target/workreport-ai-assistant-<版本>.zip`

### 12.4 想深入了解实现

- 技术详情：[docs/INLINE_COMPLETION_AND_SEMANTIC_INDEX.md](docs/INLINE_COMPLETION_AND_SEMANTIC_INDEX.md)
- 总体详设：[docs/AGENT_REFACTOR_DETAILED_DESIGN.md](docs/AGENT_REFACTOR_DETAILED_DESIGN.md)
- 兼容性说明：[COMPATIBILITY.md](COMPATIBILITY.md)
