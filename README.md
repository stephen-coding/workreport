<div align="center">

# Agent Coding Assistant

**IntelliJ IDEA 插件 + VS Code 扩展 · 由语言无关的 Python Agent Core 驱动**

[![Version](https://img.shields.io/badge/version-2.4.9-blue.svg)](https://github.com/stephen-coding/workreport)
[![IntelliJ](https://img.shields.io/badge/IntelliJ-2025.2--2026.2-green.svg)](https://www.jetbrains.com/idea/)
![Build](https://img.shields.io/badge/since--build-252%20%7C%20until--build-262.*-lightgrey.svg)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.9%2B-yellow.svg)](https://www.python.org/)
[![License](https://img.shields.io/badge/license-Apache--2.0-lightgrey.svg)](LICENSE)

插件 ID：`com.workreport.ai-assistant`

</div>

---

## 这是什么

一套本地优先的编码 Agent。**一个 Core，两个 Shell**：

- **Agent Core**（Python，`core/`）：唯一的“大脑”。负责模型调用、工具循环、上下文裁剪、记忆、Skill、MCP、子代理与语义索引；它**不直接碰用户文件**。
- **IntelliJ Shell**（Java 21 + Kotlin，`src/main/`）：底部 `AgentCoder` 工具窗口、diff 确认、文件/搜索/Git 工具、行内补全。
- **VS Code Shell**（TypeScript，`vscode/`）：协议对等的轻量 Shell，共享同一个 Core。

Shell 与 Core 通过 **JSON Lines RPC（stdio）** 通信：Shell 发请求，Core 推送流式事件；Core 需要读写文件时再回调 Shell 执行。因此同一个 Core 可以同时服务 IntelliJ 全家桶和 VS Code，行为一致。

此外它还保留了项目最初的目标——从 Git 提交记录生成周报/日报：这部分已从独立的“报告生成器”迁移为 Core 内置的 **Skill**，通过普通对话触发。

---

## 核心能力

| 能力 | 说明 | 主要实现 |
|---|---|---|
| 流式对话 | token 流式渲染、停止、错误分级；用户消息右侧、Agent 正文左侧，Markdown 渲染 | `ChatTranscript` / `MarkdownRenderer` / `rpc/server.py` |
| 工具循环 | 一轮 = 一次模型调用 + 该轮全部工具；默认预算 40 轮（5~300 可调） | `agent/loop.py` |
| 并行会话 | 最多 8 个标签各自独立 `sessionId` / `taskId`，事件按 `taskId` 路由；写操作在项目级串行 | `AgentChatPanel` / `ProjectWriteGate` |
| 会话持久化 | 每个会话一个 JSON，Core 重启后按需恢复；历史列表可回放、续聊、删除并回收磁盘 | `agent/store.py` / `session/*` RPC |
| 工具系统 | 9 个 Shell 侧工具 + 9 个 Core 内置工具，按 `auto/preview/confirm` 分级授权 | `tools/remote.py` / `agent/permissions.py` |
| 编辑与回退 | 写文件前 diff 确认卡；每次写前做内存快照，`revert_file` 可逐步回退 | `EditTools` / `FileSnapshots` |
| 记忆 | SQLite FTS5 检索 + Markdown 镜像；启动时把 overview 注入 system prompt | `memory/` |
| Skill | `SKILL.md` 技能包，内置日报/周报/季报/年报四个报告技能，可被用户级/项目级覆盖 | `skills/` |
| MCP | stdio 与 HTTP 两种客户端，工具以 `mcp__server__tool` 暴露，调用需确认 | `mcp/` |
| 子代理 | 主代理按角色清单派发只读专家（4 个内置角色），支持混合派发、角色级模型/输出契约/超时 | `agent/subagent.py` |
| 行内补全 | 编辑器 Ghost text（FIM），Tab 接受 / Esc 取消，1.5 s 超时静默降级 | `AgentInlineCompletionProvider` / `llm/completion.py` |
| 语义索引 | 本地 Embedding + SQLite float32 向量，tree-sitter 切块，按 sha1 增量构建 | `codeindex/` |
| 多模型切换 | 模型列表 + 独立的每模型 API Key，输入区下拉即时切换 | `ReportSettingsConfigurable` / `settings/update` |
| Rust 加速 | tokenizer 与代码切块可走 PyO3 扩展，缺失时自动回退纯 Python | `core/native/` |
| 免 Python 分发 | PyInstaller 打包 `agent-core.exe` 并塞进插件 zip | `packaging/agent-core.spec` |

---

## 架构

```
┌──────────────────────────── Shell 层（IDE 侧）────────────────────────────┐
│  IntelliJ 插件 (Java/Kotlin)                  VS Code 扩展 (TypeScript)     │
│  ├─ AgentCoder 工具窗口 / 多会话标签          ├─ Webview 对话视图           │
│  ├─ 文件 / 搜索 / Git 工具（IDE API）         ├─ Node fs / git CLI 工具     │
│  ├─ diff 确认卡 + 内存快照                    ├─ diff 确认 + 快照           │
│  └─ 行内补全 Provider                         └─ （无补全）                 │
└───────────────────────────────────┬───────────────────────────────────────┘
                 JSON Lines RPC over stdio（请求 + 通知，双向）
┌───────────────────────────────────┴───────────────────────────────────────┐
│                        Agent Core（Python 3.9+，单事件循环）               │
│  agent/      主循环 · 调度器 · 权限 · 上下文裁剪 · 状态栏 · 会话落盘       │
│  agent/subagent.py   角色清单 · 只读子代理池                               │
│  llm/        OpenAI 兼容 / Ollama · 流式 · 文本工具调用兜底 · FIM          │
│  tools/      注册表 + 远程工具代理（remote=True 转发给 Shell）             │
│  memory/     SQLite FTS5 + Markdown 镜像                                   │
│  skills/     SKILL.md 加载（builtin → 用户级 → 项目级）                    │
│  mcp/        stdio / HTTP 客户端 + 工具桥接                                │
│  codeindex/  切块 · Embedding · 向量检索                                   │
│  native/     Rust 扩展门面 + 纯 Python 降级                                │
└───────────────────────────────────────────────────────────────────────────┘
```

**职责边界**（改动代码前请先确认自己站在哪一侧）：

1. **Core 只思考与调度**，不写用户文件；`write_file` / `edit_file` / `delete_file` / `revert_file` 一律 `remote=True`，交给 Shell。
2. **Shell 只做 IDE 侧动作**：文件 IO、Git、diff 确认、终端、行内补全。Shell 不实现 Agent 策略。
3. **协议是唯一契约**：新增能力要么加 RPC 方法/通知，要么加工具定义；不在两侧各写一份逻辑（`vscode/src/settings.ts` 复刻的协议判定是唯一例外，用于发送前的即时校验）。

### RPC 一览

| 方向 | 消息 | 方法 |
|---|---|---|
| Shell → Core | 请求 | `ping`、`chat/start`、`tool/result`、`agent/confirmResult`、`inline/complete`、`index/build`、`index/status`、`session/list`、`session/get`、`session/delete` |
| Shell → Core | 通知 | `ide/hello`（握手：协议版本 + IDE 类型）、`settings/update`（模型/端点/MCP 变更即时下发）、`chat/cancel` |
| Core → Shell | 通知 | `core/hello`、`agent/ping`、`chat/token`、`chat/toolStart`、`chat/toolEnd`、`chat/notice`、`chat/subagent`、`chat/finish`、`chat/error`、`index/progress`、`tool/invoke`（Core 请求 Shell 执行工具）、`agent/confirm`（弹确认） |

帧格式是一行一个 JSON 对象：请求 `{id, method, params, ts}`，响应 `{id, ok, result}` 或
`{id, ok, error:{code, message}}`；通知省略 `id`。项目根随 `chat/start` 的 `contextSnapshot.projectRoot`
首次上报，Core 据此重绑记忆、Skill、MCP 与子代理角色目录。错误码定义在
`core/src/agent_core/protocol.py`（`LLM_ERROR`、`TOOL_TIMEOUT`、`CONTEXT_OVERFLOW`、`RATE_LIMITED` 等）。

---

## 仓库结构

```
workreport-intellij-plugin/
├── core/                                  # Agent Core（Python，IDE 无关）
│   ├── src/agent_core/
│   │   ├── agent/                         # loop / scheduler / permissions / status / store / subagent
│   │   │   └── subagents_builtin/         # 4 个只读角色：code-reviewer / bug-hunter / test-generator / general
│   │   ├── llm/                           # providers（openai_compat / ollama）+ 流式 / 裁剪 / FIM
│   │   ├── tools/                         # 注册表 + 远程工具代理
│   │   ├── memory/                        # FTS5 + Markdown 镜像
│   │   ├── skills/builtin/                # daily / weekly / quarterly / yearly-report
│   │   ├── mcp/                           # stdio + HTTP 客户端
│   │   ├── codeindex/                     # chunker / embeddings / SQLite 向量库
│   │   ├── native/                        # Rust 门面 + 纯 Python 降级
│   │   ├── rpc/server.py                  # JSON Lines 服务端（方法分发 + 事件推送）
│   │   └── protocol.py                    # 数据模型 / 信封 / 错误码
│   ├── native/                            # Rust 扩展源码（pyo3 abi3 + tiktoken + tree-sitter）
│   ├── tests/                             # unittest 套件（173 例）
│   ├── scripts/                           # 4 个协议冒烟
│   └── packaging/agent-core.spec          # PyInstaller 配置
├── src/main/java/com/workreport/
│   ├── agent/rpc/                         # CoreProcess / JsonRpcConnection / AgentCoreService / PythonLocator
│   ├── agent/tools/                       # File / Edit / Search / Git 工具 + 快照 + 写锁
│   ├── agent/ui/                          # 工具窗口 / 多会话标签 / 输入区 / 气泡 / Markdown / diff 卡
│   ├── agent/inline/                      # 行内补全客户端
│   └── plugin/                            # 模型列表设置页（settings/）+ 旧报告服务（legacy，未注册窗口）
├── src/main/kotlin/com/workreport/agent/inline/   # 平台 inline completion provider 实现
├── vscode/                                # VS Code Shell（TS）：rpc / session / panel / tools / diff
├── scripts/                               # build_plugin / build_core / build_native / verify_all
├── docs/                                  # Agent 重构详设 / 行内补全与语义索引 / vLLM 部署
├── .github/workflows/ci.yml               # core / intellij / vscode / native / core-exe 五个 job
├── pom.xml                                # Maven：IntelliJ 平台依赖 + 插件打包
└── README.md
```

---

## 快速开始

### 前置要求

| 用途 | 需要 |
|---|---|
| 只跑 Core | Python 3.9+，依赖仅 `pydantic>=2.5`、`httpx>=0.27`（`numpy` 可选，用于向量检索加速） |
| 编译 IntelliJ 插件 | JDK 21 + Maven 3.6+；**本地无需安装 IDEA**，平台依赖由 Maven 从 JetBrains 仓库拉取 |
| 编译 VS Code 扩展 | Node.js 18+ |
| 运行插件 | IntelliJ IDEA 2025.2 ~ 2026.2（`since-build=252` / `until-build=262.*`） |
| 可选加速 | Rust 工具链（cargo）；无 cargo 时自动使用纯 Python 实现 |

### 1) 启动 Core

```bash
pip install -e ./core
python -m agent_core --project-id demo --data-dir "$HOME/.agent/data/demo"
```

Core 从 stdin 读 JSON Lines 请求、向 stdout 写响应与事件，日志走 stderr。命令行只有三个参数：
`--project-id`、`--data-dir`、`--log-level`；另有自检开关 `--native-info`，打印加速后端（`rust` / `python`）后退出。

### 2) IntelliJ 插件

```bash
mvn -q -DskipTests compile        # 编译
```

在 IDE 里用 `Run` → `Plugin` 配置启动沙箱；或直接安装打包产物（见下方“构建与发布”）。
Core 的定位顺序由 `PythonLocator` 决定，**显式配置永远优先**：

1. 可执行文件：设置页「Core 可执行文件」→ `AGENT_CORE_EXE` → 插件内置 `agent-core/` → 从工作目录向上找 `core/dist`
2. 都未命中时回退源码模式：Core 目录（设置页 / `AGENT_CORE_HOME` / 向上查找 `core/`）+ 解释器（设置页 / `AGENT_PYTHON` / `python`）

首次使用：`Tools` → 「模型设置…」（或输入区工具栏的「设置」），填入端点与模型；
vLLM 部署需要 `--enable-auto-tool-choice --tool-call-parser hermes`，否则 Core 会自动降级为纯对话并打一行 `[提示]`。

### 3) VS Code 扩展

```bash
cd vscode && npm install && npm run compile
# 用 VS Code 打开 vscode/ 目录后按 F5 启动扩展开发宿主
# 或者打包安装：
npx @vscode/vsce package && code --install-extension agent-coder-0.1.0.vsix
```

不启 IDE 时可 `npm run smoke` 直接拉起真实 Core 跑一轮对话。设置项以 `agentCoder.` 为前缀，
完整说明见 [vscode/README.md](vscode/README.md)。

### 4) 一键验证

```powershell
powershell -File scripts/verify_all.ps1             # 单测 + 4 个协议冒烟 + Java 编译 + TS 编译/冒烟
powershell -File scripts/verify_all.ps1 -SkipJava   # 仅 Core 与 VS Code
powershell -File scripts/verify_all.ps1 -SkipNode   # 仅 Core 与 IntelliJ
powershell -File scripts/verify_all.ps1 -Native     # 先编译 Rust 扩展，并强制断言后端为 rust
```

冒烟脚本（`core/scripts/smoke_*.py`）不依赖真实模型：它们在本地起一个模拟 OpenAI 兼容端点，
按协议跑通工具调用、记忆、MCP、子代理四条链路。改动协议层时优先跑它们。

---

## 使用速览

### 面板

- 底部 `AgentCoder` 工具窗口；顶部标签栏 `+` 新建会话（最多 8 个），`×` 关闭后右侧标签左移补位，会话编号归还并优先复用最小空号。
- 输入区支持多行（`Enter` 发送 / `Shift+Enter` 换行）、拖动分隔条调高、附件内联文本、模型下拉、自动执行与停止。
- 工具与子代理进度以状态行插入对话流：`[工具] read_file 执行中…`、`[子代理] bug-hunter-1·qwen2.5-7b 完成（12.4s）`、`[提示] …`（非致命降级）、`[排队] …`（写操作排队）。
- 「历史」按钮列出本机保存的会话；关闭标签不等于删除历史，删除时才取消在跑任务并回收磁盘。

### 权限与编辑

| 权限 | 工具 | 行为 |
|---|---|---|
| `auto` | `read_file`、`list_dir`、`search_code`、`git_log`、`git_diff`、`revert_file` | 直接执行 |
| `preview` | `write_file`、`edit_file` | 先显示 diff 确认卡；勾选「自动执行」后跳过 |
| `confirm` | `delete_file`、MCP 工具（服务器可配 `auto_approve`） | 每次询问；用户连续拒绝 3 次即终止当前任务 |

所有写操作先做内存快照；`revert_file` 按“最后一次修改”逐步回退。并行会话的写操作经
`ProjectWriteGate` 在项目级串行，读与搜索不受影响。

### 报告生成

报告能力已是普通 Skill，不再有独立窗口：

- 输入「生成今天的日报」，或 `Tools` → 「生成今日日报」，或 VS Code 命令 `Agent: 生成日报`。
- 内置 4 个技能：`daily-report`、`weekly-report`、`quarterly-report`、`yearly-report`；
  搜索顺序为 `builtin` → `~/.agent/skills` → `<项目>/.agent/skills`，后者覆盖前者。
- 数据来源是 `git_log` 工具采集的提交记录（按日期区间），加上你自己的补充说明。

### 子代理

主代理的 system prompt 里带一份由 `SubagentLoader` 生成的角色清单与委派策略，因此**新增角色只需要放一个 `SUBAGENT.md`，不用改代码**：

| 角色 | 职责 | 工具白名单 | 轮次 | 输出 | 超时 |
|---|---|---|---|---|---|
| `code-reviewer` | 逻辑缺陷、边界、可维护性风险 | read_file / list_dir / search_code / git_diff / git_log | 6 | bullets | 240 s |
| `bug-hunter` | 按现象定位根因，给复现路径与最小修复 | 同上 | 6 | bullets | 300 s |
| `test-generator` | 设计正常/边界/异常的测试清单与用例 | read_file / list_dir / search_code | 5 | text | 300 s |
| `general` | 没有专门角色时的通用兜底 | 读类工具 + git_log / git_diff + memory_recall / skill_load | 4 | text | 180 s |

`SUBAGENT.md` 的 frontmatter 支持 `name`、`description`、`tools`、`max_iterations`，以及三个新增项：

```yaml
---
name: doc-writer
description: 文档撰写：按给定结构整理章节，输出中文 Markdown
tools: [read_file, list_dir, search_code]
max_iterations: 5
model: qwen2.5-7b
output: bullets
timeout_sec: 200
---
```

字段含义：`model` 留空表示继承主代理；`output` 取 `text` / `bullets` / `json`（非法值告警后按 `text` 处理）；
`timeout_sec` 默认 240 秒。frontmatter 不支持行内注释，值后面不要再跟 `# …`，否则注释会被当成值。

约束与语义：

- **严格只读**：三个环节（角色定义 → 任务构建 → 注册表过滤）都会剔除 `write_file`、`edit_file`、`delete_file`、`revert_file` 以及递归派发，子代理不可能改文件。
- 一次 `subagent_dispatch` 可以混合多个角色（`tasks` 列表，每项带 `type` / `goal` / `context` / `tools`），旧的 `type` + `goals` 写法仍然有效。
- 角色级 `model` 只覆盖模型名，端点与密钥继续继承主代理；超时到点返回“已跳过”的失败报告，不拖住主任务。
- 角色目录在 Core 启动时扫描并缓存，新增或修改 `SUBAGENT.md` 后需要重启 Core。

### 数据落在哪

| 内容 | 位置 |
|---|---|
| 会话 JSON | `~/.agent/data/<项目位置哈希>/sessions/*.json` |
| 记忆索引 | `~/.agent/data/<项目位置哈希>/memory.db` |
| 语义索引 | `~/.agent/data/<项目位置哈希>/code_index.db` |
| 记忆镜像 | `<项目>/.agent/memories/*.md`（与全局 `~/.agent/memories`） |
| 用户级 Skill | `~/.agent/skills/<name>/SKILL.md` |
| 项目级 Skill / MCP | `<项目>/.agent/skills/`、`<项目>/.agent/mcp.json`（优先于设置页 JSON） |
| API Key | IntelliJ PasswordSafe（按模型名分开存储）；VS Code 存在扩展设置里 |

---

## 工具清单

**Shell 侧（Core 通过 `tool/invoke` 回调，IDE 执行）**

| 工具 | 作用 | 权限 | 超时 |
|---|---|---|---|
| `read_file` | 按行范围读文本，带行号；自动识别 UTF-8 / UTF-16（BOM）/ GBK | auto | 15 s |
| `list_dir` | 列目录（不递归） | auto | 15 s |
| `search_code` | 正则搜索，返回文件与行号 | auto | 30 s |
| `git_log` | 按日期区间取提交记录 | auto | 30 s |
| `git_diff` | 工作区未提交变更 | auto | 30 s |
| `write_file` | 新建或整体覆盖（先 diff 确认） | preview | 30 s |
| `edit_file` | 精确文本替换，要求唯一匹配 | preview | 30 s |
| `delete_file` | 删除文件 | confirm | 30 s |
| `revert_file` | 按快照回退本会话对该文件的最后一次修改 | auto | 15 s |

**Core 内置（本地执行）**：`semantic_search`、`build_index`、`memory_recall`、`memory_save`、
`memory_delete`、`skill_list`、`skill_load`、`subagent_list`、`subagent_dispatch`。
MCP 工具在运行时以 `mcp__<server>__<tool>` 动态注册。

---

## 构建与发布

```powershell
# 完整打包：Rust 加速（有 cargo 时）→ PyInstaller 打包 Core → mvn package 并塞进 zip
powershell -File scripts/build_plugin.ps1

# 只重建 Core 可执行文件
powershell -File scripts/build_core.ps1

# 只编译 Rust 扩展（产出 core/src/agent_core_native.pyd，Linux 下为 .so）
powershell -File scripts/build_native.ps1
```

产物：

| 产物 | 路径 | 说明 |
|---|---|---|
| 可安装 zip | `target/workreport-ai-assistant-2.4.9.zip` | 内置 `agent-core/agent-core.exe`，用户机器**不需要 Python** |
| 插件 jar | `target/workreport-intellij-plugin-2.4.9.jar` | 插件本体，不含 Core 可执行文件 |
| Core 可执行文件 | `core/dist/agent-core.exe` | 也可单独被 `AGENT_CORE_EXE` 指定 |

只跑 `mvn package` 而不先构建 Core 时，zip 不含可执行文件，插件会回退到开发模式（需要 Python + `AGENT_CORE_HOME`）。

**CI**（`.github/workflows/ci.yml`，推送到 `develop` / `main` 或提 PR 时触发）五个 job：

| job | 矩阵 | 内容 |
|---|---|---|
| `core` | Ubuntu + Windows × Python 3.9 / 3.12 | 单测 + 4 个协议冒烟 |
| `intellij` | Ubuntu + JDK 21 | `mvn -DskipTests compile` |
| `vscode` | Node 18 + Python 3.11 | 编译 + `npm run smoke` |
| `native` | Ubuntu / Windows | 编译 Rust 扩展，并以 `AGENT_CORE_EXPECT_RUST=1` 跑单测 |
| `core-exe` | Windows | PyInstaller 打包 → `--help` / `--native-info` 自检 → 校验 zip 内确实含 `agent-core.exe` |

---

## 兼容性与版本

| 项 | 当前值 |
|---|---|
| 插件版本 | 2.4.9 |
| 目标平台（编译用） | `intellij.version = 261.25134.95`（2026.1） |
| 安装区间 | `since-build=252` / `until-build=262.*`（2025.2 ~ 2026.2） |
| Kotlin | `2.3.20-RC2`，必须与目标平台内置版本一致 |
| VS Code | `^1.85.0` |
| 验收基线 | Core 单测 **173 例**（跳过 1 个平台相关用例）、4 个协议冒烟 PASS、VS Code 编译与冒烟 PASS、IntelliJ 编译通过 |

升级平台时 `pom.xml` 的 `intellij.version`、`kotlin.version` 与 `plugin.xml` 的 `since-build` /
`until-build` 必须一起改；版本号规则与多版本测试方法见 [COMPATIBILITY.md](COMPATIBILITY.md)。

### 版本演进（v2.0 → v2.4.9）

| 版本 | 提交 | 主要变更 |
|---|---|---|
| v2.0.0 | `66a18f8` | 插件 zip 内置 Agent Core（免 Python）；设置页更名，新增可执行文件 / Core 目录 / 解释器三个覆盖项 |
| v2.1.0 | `d3e4185` | 模型列表（每模型独立 API Key）+ 输入区模型下拉 + 自动执行；Core 会话抽成项目级服务复用连接 |
| v2.2.0 | `be1b359` | 行内补全（Ghost text，FIM + sanitize + 超时降级）与语义索引；`since-build` 提到 252 |
| v2.2.1 ~ v2.2.4 | 并入 `6605347` | 端点形状优先于「类型」；端点不支持 tools 时降级；Key 一律下发；气泡分侧；输入区重做 |
| v2.3.0 | `6605347` | 多会话并行 + 写操作串行；输入区重做；Markdown 渲染；协议按端点自动判定 |
| v2.3.1 | `811b7a7` | 端点拒绝 `tools` 改为 5 分钟假释，服务端补参数后自动恢复 |
| v2.3.2 | `c0bfffb` | 关闭设置页不再重建 Core；上下文按整轮裁剪，不留孤立 `tool` 消息 |
| v2.4.0 | `fb5a7c2` | 会话落盘 + `session/*` RPC + 历史列表；迭代预算 15 → 40 |
| v2.4.1 | `afec4ac` | 删除历史会话立即回收磁盘（取消任务 → 删 JSON → 打墓碑） |
| v2.4.2 | `eba1945` | 正文里的 `<tool_call>` 解析为真实调用；文件编码自动探测，写回保持原编码 |
| v2.4.3 | `ab746c2` | 长任务裁剪保留最新一轮提问，修 `400 No user query found` |
| v2.4.4 | `1c8cd6c` | `Shift+Enter` 真正换行；任务运行中 `Enter` 降级为换行 |
| v2.4.5 | `72fa5db` | Agent 自检状态栏（轮次 / 工具次数 / 重复 / 失败 / 已改文件）；修关闭标签残留空框 |
| v2.4.6 | `e92230c` | 未知工具报错列出真实工具名，同名连错 3 次停止；会话编号归还复用 |
| v2.4.7 | `43c3785` | 传输层异常写明阶段与端点，不再出现半截 `[错误:LLM_ERROR]` |
| v2.4.8 | `9fb339e` | 上下文裁剪从 O(n²) 改为线性（150 轮 40 s → 0.27 s），会话内存上限 240 条 |
| v2.4.9 | `d9df278` | 子代理角色化派发：`model` / `output` / `timeout_sec`、`tasks` 混合派发、角色清单注入、三层只读护栏 |

M1–M8 的从零落地集中在 `a49411f` ~ `47eafe6`；更早的 `bf89f58` 是重构前最后一次 CDN 下载修复。

---

## 文档导航

| 文档 | 内容 |
|---|---|
| [USER_GUIDE.md](USER_GUIDE.md) | 面向使用者的完整手册：安装、首次配置、面板、状态行、行内补全、语义索引、报告 Skill、MCP、子代理、FAQ |
| [docs/AGENT_REFACTOR_DETAILED_DESIGN.md](docs/AGENT_REFACTOR_DETAILED_DESIGN.md) | Agent 重构详设：分层、协议、工具、记忆、Skill、MCP、子代理逐项设计与 M1–M8 验收 |
| [docs/INLINE_COMPLETION_AND_SEMANTIC_INDEX.md](docs/INLINE_COMPLETION_AND_SEMANTIC_INDEX.md) | 行内补全全链路（FIM 报文 / sanitize / 超时降级）与语义索引原理、调试手册 |
| [docs/VLLM_DEPLOYMENT_GUIDE.md](docs/VLLM_DEPLOYMENT_GUIDE.md) | vLLM / 昇腾部署 27B 模型与工具调用参数 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | IDE 版本号规则、兼容区间调整、多版本测试方法 |
| [vscode/README.md](vscode/README.md) | VS Code 扩展的设置项、命令与打包说明 |
| [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md) | **历史文档**：v2.0 重构前「日报生成器」的实现，仅作对照 |
| [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md) | **历史文档**：早期 UI / Git 采集 / AI 润色服务的架构说明 |

---

## 隐私与安全

- **本地优先**：配合 Ollama 可完全离线；Core 只与你配置的端点通信。
- **密钥加密**：IntelliJ 端 API Key 存 PasswordSafe（按模型名分开），不写进 `workReportSettings.xml`。
- **数据可控**：会话、记忆、索引全部落在本机 `~/.agent/` 与项目内 `.agent/`；删除即释放。
- **透明外发**：只有对话内容、被工具读取的代码片段、补全所需的光标前后文会发给模型端点；不上传项目全量代码。
- **写操作可控**：diff 确认与自动执行可随时切换，每次写前留快照可逐步回退，并行会话的写操作在项目级排队。

---

## 贡献

欢迎 Issue 与 PR。提 Bug 请附：IDE 与插件版本、Core 日志（stderr）、复现步骤、是否使用打包版 Core。
改协议或工具定义时请同时更新 `core/tests`、`core/scripts/smoke_*.py` 与相关文档，并确保
`scripts/verify_all.ps1` 全绿。

---

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。

---

## 致谢

[IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html) ·
[OkHttp](https://square.github.io/okhttp/) ·
[Ollama](https://ollama.ai/) ·
[Git4Idea](https://www.jetbrains.com/help/idea/git-integration.html) ·
[pyo3](https://pyo3.rs/) / [tiktoken](https://github.com/openai/tiktoken) / [tree-sitter](https://tree-sitter.github.io/tree-sitter/)

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐️ Star 支持！**

</div>
