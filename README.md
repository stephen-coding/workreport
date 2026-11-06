<div align="center">

# Work Report AI Assistant

**IntelliJ IDEA 插件 · 基于 Git 历史记录生成 AI 润色的工作报告**

[![Version](https://img.shields.io/badge/version-2.3.0-blue.svg)](https://github.com/stephen-coding/workreport)
[![IntelliJ](https://img.shields.io/badge/IntelliJ-2025.2.x%2B-green.svg)](https://www.jetbrains.com/idea/)
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
- 🔒 **隐私优先**：API Key 加密存储，数据完全可控
- 📊 **Git 历史分析**：智能提取 Commit 记录，结构化展示
- 🎨 **友好界面**：集成到 IDEA 工具窗口，操作简便
- ✨ **编辑器行内补全（Ghost text）**：光标处 FIM 补全，Tab 接受 / Esc 取消
- 🔎 **语义索引**：Embedding + SQLite 本地向量检索，Agent 可跨文件找代码

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
# 1) Core 依赖
pip install "pydantic>=2.5" "httpx>=0.27"

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
powershell -File scripts/verify_all.ps1            # 单测 + 4 个协议冒烟 + Java 编译 + TS 编译/冒烟
powershell -File scripts/verify_all.ps1 -SkipJava  # 仅 Core 与 VS Code
powershell -File scripts/verify_all.ps1 -Native    # 先编译 Rust 加速，再强制断言后端为 rust
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

验收基线：Core 单测 98 用例全绿、4 个协议冒烟 PASS、VS Code 编译与冒烟 PASS、IntelliJ 编译通过，
产物与验收细节见 [详设 §12](docs/AGENT_REFACTOR_DETAILED_DESIGN.md)。

---

## 🛠️ 技术栈

- **Agent Core**: Python 3.9+（pydantic v2、httpx；可选 Rust 加速扩展）
- **IntelliJ Shell**: Java 21 + Kotlin（inline completion）+ IntelliJ Platform SDK + Maven
- **VS Code Shell**: TypeScript + Node 18
- **通信协议**: JSON-RPC 2.0 over stdio（JSON Lines）
- **兼容版本**: IntelliJ IDEA 2025.2.x - 2026.2.x（`since-build=252`，ghost text 依赖平台的 inline completion API）；VS Code 1.85+

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
5. 工具与子代理进度以状态行插入对话流，例如 `[工具] read_file 执行中...`、`[子代理] code-reviewer 完成`；`[提示] ...` 表示非致命降级（如端点不支持 tools），任务会继续
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
├── docs/                       # 详细设计
├── .github/workflows/ci.yml    # Core / IntelliJ / VS Code / 打包 CI
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
- IntelliJ IDEA 2025.2.x+（Kotlin 编译依赖平台的 inline completion API）
- Git

### 克隆项目

```bash
git clone https://github.com/stephen-coding/workreport.git
cd workreport
```

### 修改 IDEA 路径

编辑 `pom.xml`，配置你的 IntelliJ IDEA 安装路径：

```xml
<properties>
    <idea.home>D:/IntelliJ IDEA 2025.2.5</idea.home>
</properties>
```

### 编译构建

```bash
# 仅编译（开发调试）
mvn clean compile

# 打包插件（需要先构建 Core，否则 zip 不含内置可执行文件）
powershell -File scripts/build_plugin.ps1
```

构建产物位于 `target/` 目录：
- `workreport-intellij-plugin-2.3.0.jar` - 插件 JAR
- `workreport-ai-assistant-2.3.0.zip` - 可安装的 ZIP 包（内置 `agent-core/agent-core.exe`，约 30 MB）

### 运行调试

1. 打开 IntelliJ IDEA
2. 执行 `Run` → `Run` → 选择 `Plugin` 配置
3. 启动新的 IDEA 窗口进行调试

---

## 📋 核心能力

### 1. 会话与流式输出 (`AgentChatPanel` + Core RPC)

- 流式 token 渲染、停止按钮、状态提示
- 顶部标签页多会话（`AgentChatPanel` + `ChatSessionPanel`）：每个标签独立 sessionId/taskId，可同时运行，事件按 taskId 路由；状态栏显示在跑的任务数
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
- **工具调用降级**：端点拒绝 `tools`（如 vLLM 未开 `--enable-auto-tool-choice`）时去掉工具重试一次，对话继续并在面板打一行 `[提示]`；同一 `(端点, 模型)` 组合在当前 Core 进程内只提示一次
- 设置页维护模型列表（名称 / 类型 / 端点 / 模型），每个模型一把 API Key（PasswordSafe 按名称分开存储）
- 输入区工具栏的 **模型** 下拉框随时切换，切换后下一条消息即用新模型，无需重启 IDE 或 Core
- **自动执行** 勾选后写文件/删除不再弹确认，直接落盘，适合“改完直接跑”的场景（仍可用 `revert_file` 回退）

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
