<div align="center">

# Work Report AI Assistant

**IntelliJ IDEA 插件 · 基于 Git 历史记录生成 AI 润色的工作报告**

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/stephen-coding/workreport)
[![IntelliJ](https://img.shields.io/badge/IntelliJ-2025.3.x-green.svg)](https://www.jetbrains.com/idea/)
[![Java](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)

</div>

---

## 📖 简介

**Work Report AI Assistant** 是一款 IntelliJ IDEA 插件，自动采集 Git 提交历史，通过 AI 大模型润色生成专业的日报、周报、季报和年报。

### ✨ 主要特性

- 🚀 **自动化报告生成**：一键生成日报/周报/季报/年报
- 🤖 **AI 智能润色**：支持本地 Ollama 或云端 LLM API
- 💬 **多轮对话优化**：支持持续对话，基于上下文理解进行 iterative 优化
- 🔒 **隐私优先**：API Key 加密存储，数据完全可控
- 📊 **Git 历史分析**：智能提取 Commit 记录，结构化展示
- 🎨 **友好界面**：集成到 IDEA 工具窗口，操作简便

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

验收基线：Core 单测 82 用例全绿、4 个协议冒烟 PASS、VS Code 编译与冒烟 PASS、IntelliJ 编译通过，
产物与验收细节见 [详设 §12](docs/AGENT_REFACTOR_DETAILED_DESIGN.md)。

---

## 🛠️ 技术栈

- **Agent Core**: Python 3.9+（pydantic v2、httpx；可选 Rust 加速扩展）
- **IntelliJ Shell**: Java 21 + IntelliJ Platform SDK + Maven
- **VS Code Shell**: TypeScript + Node 18
- **通信协议**: JSON-RPC 2.0 over stdio（JSON Lines）
- **兼容版本**: IntelliJ IDEA 2023.3.x - 2026.2.x；VS Code 1.85+

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

1. 打开 `Settings` → `Tools` → `Work Report AI Assistant`
2. 填写以下配置：

   - **LLM Provider**: 选择服务商（Ollama / DeepSeek / Qwen 等）
   - **API URL**: 模型服务地址
     - 本地 Ollama: `http://localhost:11434/api/generate`
     - 云端 API: 服务商提供的地址
   - **Model Name**: 模型名称（如 `qwen2.5:7b`, `deepseek-chat`）
   - **API Key**: 云服务 API 密钥（本地 Ollama 可为空）

3. 点击 `Apply` 保存配置

### 2. 使用 Agent 面板

1. 底部工具栏打开 **AgentCoder** 工具窗口（首次发送时自动启动 Core，状态栏显示进度）
2. 直接提需求，例如：
   - 「看下 `AgentChatPanel` 里 confirm 消息是怎么回传给 Core 的」
   - 「修复 `subagent.py` 里工具白名单漏掉 `git_log` 的问题，改完跑下测试」
   - 「生成今天的日报」（也可用 `Tools` → `生成今日日报` 菜单）
3. 写文件/删除先弹 diff 卡片，默认需要确认（由 Core 的 permission 级别控制）；
   拒绝后不落盘，也可让 Agent 调 `revert_file` 撤回上一次修改
4. MCP 工具调用会弹 Yes/No 确认；连续拒绝 3 次 Core 会自动终止当前任务
5. 工具与子代理进度以状态行插入对话流，例如 `[工具] read_file 执行中...`、`[子代理] code-reviewer 完成`

---

## 🏗️ 项目结构

```
workreport-intellij-plugin/
├── core/                       # Agent Core（Python，IDE 无关）
│   ├── src/agent_core/
│   │   ├── agent/              # 主循环、调度器、权限、Subagent
│   │   ├── llm/                # OpenAI 兼容 + Ollama Provider
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
│   ├── agent/                  # rpc / tools / ui / actions
│   └── plugin/                 # settings（含 MCP 配置）
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
- IntelliJ IDEA 2025.3.x
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
- `workreport-intellij-plugin-1.0.0.jar` - 插件 JAR
- `workreport-ai-assistant-1.0.0.zip` - 可安装的 ZIP 包（内置 `agent-core/agent-core.exe`，约 17 MB）

### 运行调试

1. 打开 IntelliJ IDEA
2. 执行 `Run` → `Run` → 选择 `Plugin` 配置
3. 启动新的 IDEA 窗口进行调试

---

## 📋 核心能力

### 1. 会话与流式输出 (`AgentChatPanel` + Core RPC)

- 流式 token 渲染、停止按钮、状态提示
- 工具事件（`chat/toolStart` / `chat/toolEnd`）与子代理事件（`chat/subagent`）以状态行展示
- 工具在 Core 侧并行调度，界面按事件到达顺序追加

### 2. 工具与编辑

- 文件读写、搜索、Git 日志/diff、终端、诊断信息由 IDE 侧执行
- 写文件/删除前显示 diff 确认卡，所有写操作先做快照，支持 `revert_file` 回退

### 3. 记忆 / Skill / MCP / Subagent

- 记忆：SQLite FTS5 检索 + Markdown 镜像，启动时注入 overview
- Skill：`SKILL.md` 内置日报/周报/月报/季报/年报，按场景自动匹配
- MCP：stdio / HTTP 服务器接入，工具以 `mcp__server__tool` 暴露，支持设置页 JSON 配置
- Subagent：`code-reviewer` / `bug-hunter` / `test-generator` 并行子代理，工具白名单隔离

---

## 🔐 隐私与安全

- ✅ **本地优先**: 支持完全离线使用（配合 Ollama 本地模型）
- ✅ **加密存储**: API Key 使用 IntelliJ PasswordSafe 加密
- ✅ **透明收集**: 仅收集 Git 提交历史，不上传其他数据
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
