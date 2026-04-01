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

## 🛠️ 技术栈

- **开发语言**: Java 21
- **开发框架**: IntelliJ Platform Plugin SDK
- **构建工具**: Maven 3.6+
- **HTTP 客户端**: OkHttp 4.12.0
- **兼容版本**: IntelliJ IDEA 2025.3.x (build 253.*)

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

### 2. 生成工作报告

1. 在 IDEA 底部工具栏点击 **WorkReport** 工具窗口
2. 选择报告类型：
   - **日报**: 今日提交
   - **周报**: 本周提交
   - **月报**: 本月提交
   - **季报**: 本季度提交
   - **年报**: 本年提交
3. 点击 `采集 Git 日志` 获取提交记录并生成草稿
4. 点击 `AI 润色` 开始第一次润色（开启对话模式）
5. 润色完成后，可在下方对话输入框继续输入优化指令：
   - 「精简一点」
   - 「换个更正式的说法」
   - 「强调技术难点」
   - 「扩展这部分内容」
6. 点击 `发送` 继续优化，反复迭代直到满意
7. 可一键复制或导出报告

---

## 🏗️ 项目结构

```
workreport-intellij-plugin/
├── src/main/java/com/workreport/plugin/
│   ├── model/                  # 数据模型
│   │   └── GitCommitEntry.java    Git 提交记录实体
│   ├── service/                # 业务服务层
│   │   ├── GitLogCollectorService.java    Git 日志采集器
│   │   └── ReportPolishService.java       AI 润色服务
│   ├── ui/                     # 用户界面
│   │   ├── ReportToolWindowFactory.java   工具窗口工厂
│   │   └── ReportToolWindowPanel.java     主面板组件
│   ├── settings/               # 设置管理
│   │   ├── ReportSettingsConfigurable.java  设置页面
│   │   └── ReportSettingsState.java         持久化状态
│   └── privacy/                # 隐私安全
│       └── PrivacyNoticeUtil.java           隐私提示工具
├── src/main/resources/
│   └── META-INF/
│       └── plugin.xml          # 插件描述符
├── assembly/                   # Maven 打包配置
├── pom.xml                     # Maven 项目配置
└── README.md                   # 项目文档
```

---

## 🔧 开发指南

### 环境要求

- JDK 21
- Maven 3.6+
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
# 编译项目
mvn clean compile

# 打包插件
mvn clean package
```

构建产物位于 `target/` 目录：
- `workreport-intellij-plugin-1.0.0.jar` - 插件 JAR
- `workreport-ai-assistant-1.0.0.zip` - 可安装的 ZIP 包

### 运行调试

1. 打开 IntelliJ IDEA
2. 执行 `Run` → `Run` → 选择 `Plugin` 配置
3. 启动新的 IDEA 窗口进行调试

---

## 📋 核心功能

### 1. Git 日志采集 (`GitLogCollectorService`)

- 基于时间范围过滤提交记录
- 支持多种粒度：日/周/月/季/年
- 提取 Commit Hash、作者、日期、提交信息

### 2. AI 润色服务 (`ReportPolishService`)

- 调用 LLM API 优化文本表达
- 支持流式响应处理（打字机效果）
- **新增：对话历史模式**，支持多轮持续优化
- **新增：上下文理解**，AI 能记住前序对话内容
- 自动重试机制
- 错误处理与用户提示

### 3. 设置管理 (`ReportSettingsState`)

- 基于 `PersistentStateComponent` 持久化配置
- 安全存储 API Key
- 支持导入/导出配置

### 4. UI 界面 (`ReportToolWindowPanel`)

- 简洁的工具窗口设计
- **新增：对话输入区**，支持自定义优化指令
- **新增：新对话按钮**，一键清空历史记录
- 实时进度反馈（流式打字机效果）
- 状态智能提示（颜色动态变化）
- 一键复制功能
- 异常提示弹窗

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
