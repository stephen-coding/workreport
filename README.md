<div align="center">

# Work Report AI Assistant

**IntelliJ IDEA 插件 · 基于 Git 历史记录生成 AI 润色的工作报告**

[![Version](https://img.shields.io/badge/version-2.4.0-blue.svg)](https://github.com/stephen-coding/workreport)
[![IntelliJ](https://img.shields.io/badge/IntelliJ-2025.2--2026.2-green.svg)](https://www.jetbrains.com/idea/)
![Build](https://img.shields.io/badge/since--build-252%20%7C%20until--build-262.*-lightgrey.svg)
[![Java](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)

</div>

---

## 📖 简介

**Work Report AI Assistant** 是一款 IntelliJ IDEA 插件，自动采集 Git 提交历史，通过 AI 大模型润色生成专业的日报、周报、季报和年报。

### ✨ 主要特性

- 🚀 **自动化报告生成**：一键生成日报/周报/季报/年报
- 🤖 **AI 智能润色**：支持本地 Ollama 或云端 LLM API
- 💬 **多轮对话优化**：支持持续对话，基于上下文理解进行 iterative 优化
- 🗂️ **多会话并行**：顶部标签页最多 8 个会话同时跑，通知按 taskId 各回各家
- 🕘 **历史会话**：关掉标签也不丢，随时从「历史」列表重新打开接着聊；会话以 JSON 落盘在本机，重启 IDE / 重启 Core 后仍在
- 🔁 **更大的迭代预算**：单个任务默认 40 轮（每轮一次模型调用 + 该轮全部工具），设置页可在 5~300 之间调整
- 🔒 **隐私优先**：API Key 加密存储，数据完全可控
- 📊 **Git 历史分析**：智能提取 Commit 记录，结构化展示
- 🎨 **友好界面**：集成到 IDEA 工具窗口，操作简便
- ✨ **编辑器行内补全（Ghost text）**：光标处 FIM 补全，Tab 接受 / Esc 取消
- 🔎 **语义索引**：Embedding + SQLite 本地向量检索，Agent 可跨文件找代码

---

## 📚 文档导航

| 文档 | 内容 |
|---|---|
| [USER_GUIDE.md](USER_GUIDE.md) | 面向使用者的完整手册：术语、安装、首次配置、面板与状态行、行内补全、语义索引、报告 Skill、MCP、FAQ |
| [COMPATIBILITY.md](COMPATIBILITY.md) | IntelliJ 版本号规则、兼容区间调整与多版本测试方法 |
| [TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md) | 模块职责、数据流转、扩展点注册、打包分发与关键设计决策 |
| [TECHNICAL_ARCHITECTURE.md](TECHNICAL_ARCHITECTURE.md) | 早期架构的实现说明（UI / Git 采集 / AI 润色服务），保留作为历史参考 |
| [docs/AGENT_REFACTOR_DETAILED_DESIGN.md](docs/AGENT_REFACTOR_DETAILED_DESIGN.md) | Agent 重构详设与 M1–M8 逐项验收 |
| [docs/INLINE_COMPLETION_AND_SEMANTIC_INDEX.md](docs/INLINE_COMPLETION_AND_SEMANTIC_INDEX.md) | 行内补全全链路（FIM 报文 / sanitize / 超时降级）与语义索引原理、调试手册 |
| [docs/VLLM_DEPLOYMENT_GUIDE.md](docs/VLLM_DEPLOYMENT_GUIDE.md) | vLLM / 昇腾部署 27B 模型与工具调用参数 |
| [vscode/README.md](vscode/README.md) | VS Code 扩展（Agent Coder）的设置项、命令与打包说明 |

---

## 🧠 Agent 架构（当前主干）

插件已从“日报生成器”重构为类 Qoder 的编码 Agent：

```
IntelliJ 插件 (Java)  ─┐
                       ├─ JSON Lines RPC (stdio) ─→  Agent Core (Python)
VS Code 扩展 (TS)     ─┘                              ├─ LLM: OpenAI 兼容 / Ollama
                                                       ├─ 工具：并行调度 + 权限（auto/preview/confirm）
                                                       ├─ 编辑：diff 确认 + 快照回退
                                                       ├─ 记忆：SQLite FTS5 + Markdown 镜像
                                                       ├─ Skill：SKILL.md（日报/周报/季报/年报已迁移）
                                                       ├─ MCP：stdio / HTTP 客户端 + 工具桥接
                                                       └─ Subagent：并行池 + 内置 code-reviewer 等
```

- Core 只负责思考与调度，**不直接写用户文件**；文件/终端/Git 由 Shell 执行。
- 同一个 Core 同时服务 IntelliJ 全家桶与 VS Code，协议对等。
- 详设见 [docs/AGENT_REFACTOR_DETAILED_DESIGN.md](docs/AGENT_REFACTOR_DETAILED_DESIGN.md)。

### 快速启动

```bash
# 1) Core 依赖（以可编辑模式装入当前解释器）
pip install -e ./core
# 等价的最小依赖：pip install "pydantic>=2.5" "httpx>=0.27"
# 语义索引提速（可选）：pip install numpy，缺失时自动回退纯 Python 点积

# 2) IntelliJ 插件
mvn -q -DskipTests compile

# 3) VS Code 扩展
cd vscode && npm install && npm run compile
```

环境变量（可选）：`AGENT_PYTHON`、`AGENT_CORE_HOME`、`AGENT_CORE_EXE`。
打包 Core（免 Python 运行）：`powershell -File scripts/build_core.ps1`。
编译 Rust 加速（可选，自动降级）：`powershell -File scripts/build_native.ps1`。

### 一键验证

```powershell
powershell -File scripts/verify_all.ps1             # 单测 + 4 个协议冒烟 + Java 编译 + TS 编译/冒烟
powershell -File scripts/verify_all.ps1 -SkipJava   # 仅 Core 与 VS Code
powershell -File scripts/verify_all.ps1 -SkipNode   # 仅 Core 与 IntelliJ
powershell -File scripts/verify_all.ps1 -Native     # 先编译 Rust 加速，再强制断言后端为 rust
```

### 里程碑状态（M1–M8 全部完成）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | Core 骨架 + JSON Lines RPC + LLM Provider + Agent 循环 + IntelliJ 聊天面板 | 完成 |
| M2 | 工具系统 + 并行调度 + Shell 端 9 个工具 | 完成 |
| M3 | Rust 加速扩展（tokenizer / codesoup）+ 纯 Python 降级 | 完成（已实际编译验证） |
| M4 | 编辑 diff 确认 + 快照回退 + 记忆系统 | 完成 |
| M5 | Skill 系统 + 日报/周报/月报/季报/年报迁移 | 完成 |
| M6 | MCP 集成（stdio / HTTP）+ 设置页配置 + confirm 通道 | 完成 |
| M7 | Subagent 并行池 + 3 个内置子代理 | 完成 |
| M8 | VS Code Shell + PyInstaller 分发 + CI + 一键验证 | 完成 |

验收基线：Core 单测 **125 用例全绿**（跳过 1 个平台相关用例，单轮约 80 s）、4 个协议冒烟 PASS、
VS Code 编译与冒烟 PASS、IntelliJ 编译通过，产物与验收细节见 [详设 §12](docs/AGENT_REFACTOR_DETAILED_DESIGN.md)。

### 版本演进（v2.0 → v2.4）

M1–M8 解决的是「能跑通」，之后的提交把它推到「日常可用」，逐版本对应如下（括号内为提交短哈希）：

| 版本 | 提交 | 主要变更 |
|---|---|---|
| v2.0.0 | `66a18f8`、`c15c369` | 插件 zip 内置 Agent Core（免 Python）；设置页改名 Agent Coding Assistant，面板加「设置」入口；新增 Core 可执行文件 / Core 目录 / Python 解释器三个覆盖项，留空自动探测；每条消息前重下发 settings |
| v2.1.0 | `d3e4185` | 设置页改为模型列表（每模型独立 API Key 走 PasswordSafe）；面板顶部模型下拉 + 「自动执行」；Core 会话抽成项目级 `AgentCoreService`，面板复用同一连接 |
| v2.2.0 | `be1b359`、`694d349` | 编辑器行内补全（Ghost text，FIM + sanitize + 超时降级）；语义索引（tree-sitter 切块 / Embedding / SQLite float32 向量 + 增量构建）；`since-build` 由 233 提到 252；全仓术语统一为「行内补全（Ghost text）」 |
| v2.2.1 ~ v2.2.4 | 未单独提交（仅本地补丁构建） | 2.2.1 端点与「类型」冲突时以端点为准 + 端点不支持 tools 时降级纯对话；2.2.2 API Key 一律下发（即使类型选 Ollama）；2.2.3 对话区改为左右分侧气泡；2.2.4 输入区重做。**这四次补丁的改动全部并入 v2.3.0 的 `6605347`**，所以正文里出现的「v2.2.x 起」标注指这些补丁构建 |
| v2.3.0 | `6605347` | 多会话并行（标签页 + 按 taskId 路由）与 `ProjectWriteGate` 写操作串行；输入区重做（4~14 行自适应 / 分隔条 / 附件 / 拖拽）；Markdown 渲染 + 左右分侧气泡；LLM 协议按端点形状自动判定，端点不支持 tools 时降级为纯对话；设置入口提升到 `Tools` 菜单 |
| v2.3.1 | `811b7a7` | 端点拒绝 `tools` 后不再「一票否决」：改为 5 分钟假释，服务端补上 `--enable-auto-tool-choice` 后自动重新探测，无需重启 IDE；降级提示改为每个 `(端点, 模型)` 在一个 Core 进程内只出现一次 |
| v2.3.2 | `c0bfffb` | 关闭设置页不再重建 Core：模型 / 端点 / MCP 由 `settings/update` 实时下发，只有改了 Core 路径才重建——修掉「打开一次设置页就把所有会话上下文清空」；上下文裁剪改为按「轮」整轮丢弃，不再留下孤立 `tool` 消息被端点 400 |
| v2.4.0 | 本次 | 会话落盘：每个会话一个 JSON（`~/.agent/data/<项目哈希>/sessions/`），Core 重启后自动按需恢复；新增 `session/list` / `session/get` / `session/delete` 三个 RPC 与面板「历史」列表（回放 / 继续聊 / 删除），关标签不等于删历史；迭代预算默认 15 → **40**，IntelliJ 设置页新增「最大迭代轮次」（5~300），VS Code 的 `agentCoder.maxIterations` 同步为 40 |

更早的 Agent Core 从零落地（即 M1–M8 对应的实现）集中在 `a49411f` ~ `47eafe6` 这一串提交，
`bf89f58` 则是重构前最后一个 JetBrains CDN 下载修复。

---

## 🛠️ 技术栈

- **Agent Core**: Python 3.9+（pydantic v2、httpx；numpy 可选，用于语义检索矩阵加速；可选 Rust 加速扩展）
- **IntelliJ Shell**: Java 21 + Kotlin（行内补全 Provider）+ IntelliJ Platform SDK + Maven
- **VS Code Shell**: TypeScript + Node 18
- **通信协议**: JSON-RPC 2.0 over stdio（JSON Lines）
- **构建目标平台**: `intellij.version = 261.25134.95`（2026.1）从 JetBrains 仓库拉取，Kotlin 2.3.20-RC2 与平台内置版本对齐；无需本地安装 IDEA 路径
- **兼容版本**: IntelliJ IDEA 2025.2 - 2026.2（`since-build=252` / `until-build=262.*`，行内补全依赖平台的 inline completion API）；VS Code 1.85+

---

## 📦 安装方式

### 方式一：从 JetBrains Marketplace 安装（推荐）

1. 打开 IntelliJ IDEA
2. 进入 `Settings` → `Plugins` → `Marketplace`
3. 搜索 "Work Report AI Assistant"
4. 点击 `Install` 并重启 IDE

### 方式二：手动安装

1. 从 [Releases](https://github.com/stephen-coding/workreport/releases) 下载最新版本的 `.zip` 文件
2. 打开 IntelliJ IDEA
3. 进入 `Settings` → `Plugins` → ⚙️ → `Install Plugin from Disk...`
4. 选择下载的 `.zip` 文件
5. 重启 IDE

### 方式三：本地打包（内置 Agent Core，免 Python）

```powershell
powershell -File scripts/build_plugin.ps1
```

这一条命令会依次完成：编译 Rust 加速（有 cargo 时）→ 用 PyInstaller 打包 `core/dist/agent-core(.exe)` →
`mvn package` 并把可执行文件打进 zip 的 `agent-core/` 目录。产物为
`target/workreport-ai-assistant-<version>.zip`，安装后插件启动时自动使用内置 Core，用户机器上
**不需要安装 Python**；仅 `mvn package` 而不先构建 Core 时，zip 不含可执行文件，将回退到
开发模式（需 `AGENT_CORE_HOME` + Python）。

---

## 🧩 VS Code 扩展（Agent Coder）

同一个 Agent Core 同时驱动 VS Code：`vscode/` 是与其协议对等的 Shell 扩展（要求 VS Code `^1.85.0`）。
编辑、工具、记忆、Skill、MCP、子代理均在 Core 侧执行，扩展只负责 IDE 侧动作（文件 IO、git CLI、
diff 确认、终端），与 IntelliJ 插件共用同一个 Core 和同一套 RPC。

### 方式一：开发模式（F5）

```bash
cd vscode
npm install
npm run compile
```

用 VS Code 打开 `vscode/` 目录后按 `F5` 启动「扩展开发宿主」窗口，新窗口左侧活动栏会出现
**Agent Coder** 图标，点开即对话面板（webview 视图 `agentCoder.chatView`）。
不启 IDE 时可用 `npm run smoke` 直接拉起真实 Core 跑一轮对话做冒烟。

### 方式二：打包成 .vsix 安装

```bash
cd vscode && npm install && npm run compile
npx @vscode/vsce package                 # 产物 vscode/agent-coder-0.1.0.vsix
code --install-extension agent-coder-0.1.0.vsix
```

> 该扩展尚未发布到 Marketplace，目前只能走上面两种方式安装。

### Core 的定位规则

- **优先用打包好的可执行文件**：`AGENT_CORE_EXE` → `<扩展>/bin/agent-core(.exe)` →
  上层 `core/dist/agent-core(.exe)` → `<工作区>/core/dist/agent-core(.exe)`，命中即无需 Python
- **回退到 Python 源码模式**：解释器取 `agentCoder.pythonPath` → `AGENT_PYTHON` → `python`（Windows）/
  `python3`；Core 目录取 `agentCoder.coreHome` → `AGENT_CORE_HOME` → 上层 `core/` → `<工作区>/core`，
  以 `src/agent_core/__main__.py` 是否存在为准

### 配置与命令

在 `Settings` 搜索 `agentCoder`：`provider`（`ollama` / `openai_compat`）、`baseUrl`、`model`、
`apiKey`（建议改用 `OPENAI_API_KEY` 环境变量）、`maxIterations`（默认 15）、`toolConcurrency`（4）、
`subagentConcurrency`（3）、`autoAcceptEdits`（默认 false，跳过 diff 确认）、`mcpServers`
（项目内 `.agent/mcp.json` 优先）。

命令面板可执行：`Agent: 打开对话面板`、`Agent: 生成日报`、`Agent: 重启 Agent Core`。
端点与「类型」冲突时同样以端点形状为准（`vscode/src/settings.ts` 复刻了 Core 的协议判定）。

### 与 IntelliJ 侧的差异

- 没有行内补全（Ghost text）——扩展未注册 inline completion 提供者
- 没有 Embedding 模型设置项，语义索引走 Core 默认值
- API Key 存在 VS Code 明文设置项里，不是 PasswordSafe 加密存储

完整说明见 [vscode/README.md](vscode/README.md)。

---

## 🚀 快速开始

### 1. 配置 LLM API

首次使用需配置 AI 模型参数：

1. 打开 Agent 面板输入区工具栏的 **设置** 按钮（或 `Tools` → 「模型设置…」、`Settings` → `Tools` → `Agent Coding Assistant（模型设置）`）
2. 填写以下配置：

   - **LLM Provider**: 选择服务商（Ollama / DeepSeek / Qwen 等）
   - **API URL**: 模型服务地址
     - 本地 Ollama: `http://localhost:11434/api/generate`
     - 云端 API: 服务商提供的地址
     - vLLM: `http://<host>:<port>/v1/chat/completions`；启动时需带 `--enable-auto-tool-choice --tool-call-parser hermes`，否则插件会自动降级为纯对话模式
   - **Model Name**: 模型名称（如 `qwen2.5:7b`, `deepseek-chat`）
   - **API Key**: 云服务 API 密钥（本地 Ollama 可为空）
   - **Embedding 模型**: 语义索引用的向量模型（默认 `nomic-embed-text`；OpenAI 兼容端点为 `text-embedding-3-small`）
   - **编辑器行内补全（Ghost text）**: 勾选后启用行内补全（默认开启）

> **类型与端点冲突时以端点为准**（v2.2.1 起）：端点以 `/api/...` 结尾按 Ollama 协议、含 `/v1/chat/completions` 或 `/v1` 按 OpenAI 兼容协议，填错「类型」不会再拼出 `.../v1/chat/completions/api/chat` 这类 404 地址。
>
> **Key 一律下发**（v2.2.2 起）：只要该模型填了 API Key 就会带上 Bearer 头，即使「类型」选了 Ollama——New API 这类网关不会再报 `Invalid token`。
>
> 自建 vLLM / 昇腾部署 27B 模型、工具调用参数怎么加：见 [docs/VLLM_DEPLOYMENT_GUIDE.md](docs/VLLM_DEPLOYMENT_GUIDE.md)。

3. 点击 `Apply` 保存配置

### 2. 使用 Agent 面板

1. 底部工具栏打开 **AgentCoder** 工具窗口（首次发送时自动启动 Core，状态栏显示进度）
2. 直接提需求，例如：
   - 「看下 `AgentChatPanel` 里 confirm 消息是怎么回传给 Core 的」
   - 「修复 `subagent.py` 里工具白名单漏掉 `git_log` 的问题，改完跑下测试」
   - 「生成今天的日报」（也可用 `Tools` → `生成今日日报` 菜单）
3. 写文件/删除先弹 diff 卡片，需要确认；输入区工具栏勾选 **自动执行** 后不再弹窗（可直接改代码）
4. MCP 工具调用会弹 Yes/No 确认；连续拒绝 3 次 Core 会自动终止当前任务
5. 工具与子代理进度以状态行插入对话流，例如 `[工具] read_file 执行中...`、`[子代理] code-reviewer 完成`；`[提示] ...` 表示非致命降级（如端点不支持 tools，5 分钟后会自动重试），任务会继续
6. 输入区支持多行输入（`Enter` 发送 / `Shift+Enter` 换行）、拖动上方分隔线调整高度、「附件」添加文件作为上下文（文本内联，二进制只给路径）
7. 顶部标签栏点 `+` 新建会话（最多 8 个），**多个会话可同时各跑一个任务**；状态栏右侧显示 `N 个任务并行运行中`。写文件类工具在项目内排队执行（对话里会打 `[排队]` 提示），读文件/搜索不受影响

---

## 🏗️ 项目结构

```
workreport-intellij-plugin/
├── core/                       # Agent Core（Python，IDE 无关）
│   ├── src/agent_core/
│   │   ├── agent/              # 主循环、调度器、权限、Subagent
│   │   ├── llm/                # OpenAI 兼容 + Ollama Provider + FIM 补全
│   │   ├── codeindex/          # 语义索引：切块 / Embedding / SQLite 向量库
│   │   ├── mcp/                # MCP 客户端 / 管理器
│   │   ├── memory/             # SQLite FTS5 + Markdown 记忆
│   │   ├── skills/             # SKILL.md 加载器 + 4 个内置报告 Skill
│   │   ├── tools/              # 工具注册表 + 远程代理
│   │   ├── rpc/                # JSON Lines 服务端
│   │   └── native/             # Rust 扩展门面 + 纯 Python 降级
│   ├── native/                 # Rust 扩展源码（pyo3 + tiktoken/tree-sitter）
│   ├── tests/                  # unittest 套件 + 假 MCP 服务
│   ├── scripts/                # 协议冒烟脚本
│   └── packaging/              # PyInstaller spec
├── src/main/java/com/workreport/
│   ├── agent/                  # rpc（共享 Core 会话）/ tools / ui / inline / actions
│   └── plugin/                 # settings（含 MCP、Embedding 与 ghost text 开关）
├── src/main/kotlin/com/workreport/
│   └── agent/inline/           # 编辑器 InlineCompletionProvider
├── vscode/                     # VS Code Shell（TypeScript）
├── scripts/                    # build_plugin.ps1 / build_core.ps1 / build_native.ps1 / verify_all.ps1
├── docs/                       # 重构详设 / 行内补全与语义索引 / vLLM 部署指南
├── .github/workflows/ci.yml    # CI 共 5 个 job（core / intellij / vscode / native / core-exe）
├── USER_GUIDE.md               # 面向使用者的完整手册
├── COMPATIBILITY.md            # IDE 版本兼容策略
├── TECHNICAL_DESIGN.md         # 技术设计与模块职责
├── TECHNICAL_ARCHITECTURE.md   # 早期架构说明（历史参考）
├── pom.xml                     # Maven 项目配置
└── README.md
```

---

## 🔧 开发指南

### 环境要求

- JDK 21
- Maven 3.6+
- Python 3.9+（运行 Agent Core；仅依赖 pydantic v2 与 httpx）
- Node.js 18+（构建 VS Code 扩展）
- Git
- 本地**无需安装 IntelliJ IDEA**：构建时由 Maven 从 JetBrains 仓库拉取目标平台（首次下载较慢）
- 装包运行调试时需 IntelliJ IDEA 2025.2 - 2026.2 中任一版本（行内补全依赖平台 inline completion API）

### 克隆项目

```bash
git clone https://github.com/stephen-coding/workreport.git
cd workreport
```

### 调整目标平台与兼容区间

`pom.xml` 不读取本地 IDEA 安装路径，目标平台由 `intellij.version` 决定；`plugin.xml` 的
`since-build` / `until-build` 决定可安装的 IDE 区间。升级平台时这三项需一起改：

```xml
<!-- pom.xml -->
<intellij.version>261.25134.95</intellij.version>
<!-- src/main/resources/META-INF/plugin.xml -->
<idea-version since-build="252" until-build="262.*"/>
```

`kotlin.version` 必须与目标平台内置的 Kotlin 版本一致（当前 `2.3.20-RC2`），否则 Kotlin 编译会报
元数据版本不匹配。版本号规则与多版本测试方法见 [COMPATIBILITY.md](COMPATIBILITY.md)。

### 编译构建

```bash
# 仅编译（开发调试）
mvn clean compile

# 打包插件（需要先构建 Core，否则 zip 不含内置可执行文件）
powershell -File scripts/build_plugin.ps1
```

构建产物位于 `target/` 目录：
- `workreport-intellij-plugin-2.4.0.jar` - 插件 JAR
- `workreport-ai-assistant-2.4.0.zip` - 可安装的 ZIP 包（内置 `agent-core/agent-core.exe`，约 30.5 MB）

### 运行调试

1. 打开 IntelliJ IDEA
2. 执行 `Run` → `Run` → 选择 `Plugin` 配置
3. 启动新的 IDEA 窗口进行调试

---

## 📋 核心能力

### 1. 会话与流式输出 (`AgentChatPanel` + Core RPC)

- 流式 token 渲染、停止按钮、状态提示
- 顶部标签页多会话（`AgentChatPanel` + `ChatSessionPanel`）：每个标签独立 sessionId/taskId，可同时运行，事件按 taskId 路由；状态栏显示在跑的任务数
- 历史会话（`+` 右侧的「历史」按钮）：按最近更新倒序列出本机保存的会话，点开即回放（只回放用户提问与 Agent 正文，工具调用不展示），可直接接着聊；每行右侧 `×` 删除（带确认）。**关闭标签只是关掉界面，历史仍在本机**，重启 IDE / 重启 Core 后照样在
- 左右分侧气泡流：用户消息在右、Agent 回答在左（Markdown 标题/列表/表格/代码块）
- 输入区（`ChatComposer`）：多行自适应（4~14 行）、分隔条可拖动、附件 chip 可增删；附件正文内联进消息，路径写入 `contextSnapshot.open_files`
- 工具事件（`chat/toolStart` / `chat/toolEnd`）与子代理事件（`chat/subagent`）以状态行展示
- 工具在 Core 侧并行调度，界面按事件到达顺序追加
- 写类工具（`write_file` / `edit_file` / `delete_file` / `revert_file`）经 `ProjectWriteGate` 项目级排队，避免并行会话互相覆盖、多个 diff 弹窗撞车

### 2. 工具与编辑

- 文件读写、搜索、Git 日志/diff、终端、诊断信息由 IDE 侧执行
- 写文件/删除前显示 diff 确认卡，所有写操作先做快照，支持 `revert_file` 回退

### 3. 记忆 / Skill / MCP / Subagent

- 记忆：SQLite FTS5 检索 + Markdown 镜像，启动时注入 overview
- Skill：`SKILL.md` 内置日报/周报/月报/季报/年报，按场景自动匹配
- MCP：stdio / HTTP 服务器接入，工具以 `mcp__server__tool` 暴露，支持设置页 JSON 配置
- Subagent：`code-reviewer` / `bug-hunter` / `test-generator` 并行子代理，工具白名单隔离

### 4. 多模型切换与自动执行

- **入口**：`Tools` 菜单 → 「模型设置…」，或输入区工具栏的「设置」；也可 `Ctrl+Shift+A` 搜“模型设置”
- **协议自动识别**：按端点形状判定 Ollama / OpenAI 兼容，与设置页「类型」冲突时以端点为准
- **工具调用降级**：端点拒绝 `tools`（如 vLLM 未开 `--enable-auto-tool-choice`）时去掉工具重试一次，对话继续并在面板打一行 `[提示]`；同一 `(端点, 模型)` 组合在当前 Core 进程内只提示一次，且 **5 分钟后自动重新尝试带工具**（服务端补上参数后无需重启 IDE）
- 设置页维护模型列表（名称 / 类型 / 端点 / 模型），每个模型一把 API Key（PasswordSafe 按名称分开存储）
- 输入区工具栏的 **模型** 下拉框随时切换，切换后下一条消息即用新模型，无需重启 IDE 或 Core
- **自动执行** 勾选后写文件/删除不再弹确认，直接落盘，适合“改完直接跑”的场景（仍可用 `revert_file` 回退）
- **最大迭代轮次**（设置页「对话与 Agent」分组，默认 40，可填 5~300）：一轮 = 一次模型调用 + 该轮派发的全部工具；用满后 Agent 会提示已达到上限，回复「继续」即可接着跑

### 5. 编辑器行内补全（Ghost text）

- 基于平台 `com.intellij.inline.completion.provider` 扩展点（Kotlin `AgentInlineCompletionProvider`），停笔 300 ms 后触发
- 光标前后文经 `inline/complete` 发给 Core，由当前模型做 FIM（fill-in-the-middle）补全：Ollama 走 `/api/generate` 的 `suffix`，OpenAI 兼容走 `/v1/completions`
- **Tab** 接受 / **Esc** 取消；Core 不可用、端点不支持 FIM 或超时（1.5 s）时静默返回空，不打扰输入
- 补全请求在 Core 侧是后台任务，不会阻塞聊天流；设置页「编辑器内行内补全」可随时关掉

### 6. 语义索引（code-index）

- 只内置 **Embedding 模型**，不引入独立向量数据库：向量以 float32 BLOB 存在本地 SQLite，检索用归一化点积（numpy 存在时矩阵批量加速，否则纯 Python 回退）
- 切块：优先用 Rust 加速的 tree-sitter 按函数切，失败回退 60 行 / 10 行重叠窗口；跳过 vendor、node_modules 等目录
- 增量：按文件 sha1 比对，只重算变更文件；换 Embedding 模型自动整库重建；`index/status` 可查文件/块数与进度
- 工具：`semantic_search`（语义检索代码块）、`build_index`（按需重建），Agent 会在找代码时自动调用
- 索引存放在 `~/.agent/data/<项目哈希>/`，模型变更或想强制重建时可删库重来

---

## 🔐 隐私与安全

- ✅ **本地优先**: 支持完全离线使用（配合 Ollama 本地模型）
- ✅ **加密存储**: API Key 使用 IntelliJ PasswordSafe 加密
- ✅ **透明收集**: 仅收集 Git 提交历史，不上传其他数据；语义索引与补全只把光标前后文/代码块发给**你自己配置**的模型端点
- ✅ **用户控制**: 所有配置项完全由用户掌控

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

### 提交 Bug

请提供：
- IntelliJ IDEA 版本
- 插件版本号
- 详细复现步骤
- 错误日志截图

### 功能建议

请说明：
- 功能场景描述
- 预期行为
- 可能的实现方案

---

## 📄 许可证

Apache License 2.0

---

## 📬 联系方式

- **GitHub Issues**: [提交问题](https://github.com/stephen-coding/workreport/issues)
- **Email**: stephenliuforlife@gmail.com

---

## 🙏 致谢

感谢以下开源项目：

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [OkHttp](https://square.github.io/okhttp/)
- [Ollama](https://ollama.ai/)
- [Git4Idea](https://www.jetbrains.com/help/idea/git-integration.html)

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐️ Star 支持！**

</div>
