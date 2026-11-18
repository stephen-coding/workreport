# Privacy Policy / 隐私政策

**Plugin:** Agent Coding Assistant (`com.workreport.ai-assistant`)
**Vendor:** WeeklyReport
**Last updated / 最后更新:** 2026-11-18

---

## 1. 一句话版本

**本插件没有任何遥测。** 插件作者不收集、不上传、不接收你的任何数据。
所有外发内容只会发往 **你自己在设置页里填写的模型端点**（本地 Ollama，或你选择的云端 API）。

This plugin has **no telemetry**. The author never collects or receives your data.
Network traffic goes only to the model endpoint that **you** configure.

---

## 2. 谁会看到你的数据

| 环节 | 数据流向 | 说明 |
|---|---|---|
| 插件 ↔ Agent Core | 本机进程间（stdin/stdout JSON-RPC） | Core 是随插件分发的本地子进程，不出机器 |
| Agent Core ↔ 模型端点 | HTTP/HTTPS | 端点由你在 `Settings → Tools → 模型设置` 中填写 |
| 插件 ↔ JetBrains | 无 | 不向 JetBrains 上报使用统计 |
| 插件 ↔ 作者 | 无 | 没有统计、没有崩溃上报、没有版本探活 |

**关键点**：如果端点填的是 `http://localhost:11434`（Ollama），数据不会离开你的计算机。
如果填的是云端地址（OpenAI、Anthropic、自建网关等），数据会发送给该服务的运营方，
其处理方式受**该服务方的隐私政策**约束，与本插件无关。

---

## 3. 具体会发送什么

只有在一次对话或一次补全请求中，被明确读取的内容才会外发：

| 内容 | 触发方式 |
|---|---|
| 你输入的对话消息 | 发送消息时 |
| 工具读取的文件内容与片段 | 模型调用 `read_file` / `search_text` / `search_symbol` 等工具时 |
| 目录结构 | 模型提出需要了解工程结构时 |
| `git log` / `git diff` 的输出 | 模型调用 `git_log` / `git_diff` 工具时 |
| 命令输出 | 你确认执行 `run_command` 后 |
| MCP 工具返回结果 | 你配置并启用了 MCP 服务时 |
| 会话摘要 | 上下文超预算时，由本地 Core 生成摘要后再发送 |
| 行内补全的前后文片段 | 编辑器触发 ghost text 时（仅前后若干行） |

不会外发：API Key、密码、IDE 配置、未打开的文件、被 `.gitignore` 规则排除且未被工具读取的内容。

> **提示**：工具可以读取你仓库里的任何文本文件。若仓库内含密钥、客户数据或个人信息，
> 请在对话中明确要求模型跳过相关路径，或先把这些文件排除在工作目录之外。

---

## 4. 本地会写入什么

| 路径 | 内容 | 是否可删 |
|---|---|---|
| `~/.agent/data/<projectId>/` | 会话历史（含 todos）、代码索引 `code_index.db`、记忆库 | 可删，删后会话与索引重建 |
| `~/.agent/logs/` | 运行日志（按天滚动）、`usage.jsonl` 用量记录（仅本地，不上传） | 可删 |
| `~/.agent/memories/`、`~/.agent/subagents/` | 全局记忆与子代理定义 | 可删 |
| `<项目>/.agent/` | 项目级记忆、`mcp.json`、项目级 Skills / Subagents | 可删，建议纳入版本控制前先检查 |
| IDE `PasswordSafe`（service `WorkReportPlugin`） | 各模型配置的 API Key | 删掉对应模型配置后需手动清理，见第 6 节 |
| `workReportSettings.xml`（IDE 配置目录） | 模型列表、端点、并发与预算等偏好 | 可删，删后回默认值 |

所有文件都是**明文**（API Key 除外，它存放在 IDE 密码库中）。请自行按需加密磁盘或限制目录权限。

---

## 5. 第三方组件

| 组件 | 作用 | 是否联网 |
|---|---|---|
| 内置 Agent Core（Python，打包为本地可执行文件） | Agent 循环、工具调度、记忆与索引 | 仅连接你配置的模型端点 |
| OkHttp | 插件内 HTTP 客户端 | 同上 |
| 你配置的 MCP 服务 | 额外工具能力 | 由该服务的配置决定 |

插件不包含广告 SDK、分析 SDK 或崩溃上报 SDK。

---

## 6. 如何删除你的数据

1. **会话与索引**：关闭 IDE，删除 `~/.agent/data/<projectId>/`（`<projectId>` 为项目路径哈希）。
2. **全部本地数据**：删除 `~/.agent/` 与项目下的 `.agent/` 目录。
3. **API Key**：在设置页删除对应模型配置；如怀疑残留，可在 IDE 的 `PasswordSafe`
   中删除服务名 `WorkReportPlugin` 下的条目（密码库文件位于 IDE 配置目录的 `c.pwd` / `kdbx`）。
4. **卸载插件**：`Settings → Plugins → 卸载`，本地数据不会自动删除，需按上面步骤手动清理。

---

## 7. 变更与联系

本政策的修改会随插件版本发布并更新本文件的日期。
问题、数据删除请求或安全报告请提交到：
<https://github.com/stephen-coding/workreport/issues>

---

## English Summary

- **No telemetry.** The vendor receives nothing.
- **One destination:** the model endpoint you configure. Local Ollama keeps data on your machine;
  a cloud endpoint sends it to that provider, under that provider's own policy.
- **What is sent:** your messages, and only the file contents / command output / git data that tools
  actually read during the conversation, plus optional inline-completion context.
- **What is stored locally:** conversations, code index, memories and logs under `~/.agent/`,
  project-level settings under `<project>/.agent/`, and API keys in the IDE `PasswordSafe`.
- **Deleting data:** remove those directories, or uninstall the plugin and clean them up manually.
- **Third parties:** none beyond the model endpoint you choose and any MCP server you configure.
