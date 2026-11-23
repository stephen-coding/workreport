# 代码逐行导读 · 总索引

> 目标：把本仓库 **183 个源文件 / 35,502 行代码** 逐个讲清楚——每一行在做什么、为什么这样写、不这样写会出什么问题。
>
> 本索引是唯一入口；正文按「一次请求的生命周期 → 各层逐文件」分卷组织。每卷独立可读，但卷与卷之间有明确的先后依赖。

---

## 0. 怎么用这份导读

1. **先读第 01 卷**。它把 `回车 → Java Shell → JSON-RPC → Python Core → LLM → 工具回调 → 渲染` 整条链路串一遍，建立心智模型。不读这一卷，后面每一卷都会「只见树木」。
2. **再按卷号顺序读**。卷号即依赖顺序：构建装配 → Core 入口 → 循环 → LLM → 工具 → 状态 → UI → 其它 Shell。
3. **左边 IDE 右边文档**。文档里的行号锚点形如 `ChatSessionPanel.java:498-567`，括号内是「函数名 + 起始行」；直接 `Ctrl+Shift+F`/`Ctrl+G` 跳过去对照源码。
4. **每卷末尾有「自测清单」**。照着跑一遍命令、观察一遍现象，比读十遍代码有用。
5. **不要背代码**。要记住的是：谁拥有状态、谁在哪个线程、失败时回退到哪条路径——这三件事决定了 90% 的 bug。

### 标注约定

| 记号 | 含义 |
|---|---|
| `路径:12-89` | 文件内的行号范围（含两端），以当前 HEAD 为准 |
| `函数名()` | 后跟起始行号时，表示该函数的定义处 |
| ⚠️ | 易错点 / 历史事故 / 平台限制 |
| 🔁 | 跨文件、跨进程的对应关系（一处改动必须同步另一处） |
| 📌 | 设计决策及其理由（为什么不是另一种写法） |

---

## 1. 三分钟心智模型

```
┌─────────────────────────── IntelliJ / VS Code Shell（IDE 内） ───────────────────────────┐
│  ChatSessionPanel（每个标签一份）                                                        │
│    ├── ChatTranscript  → ChatView（块渲染）/ ClassicTranscript（HTML 回退）               │
│    ├── ChatComposer    → 输入、附件、模型下拉、自动执行开关                              │
│    ├── TodoStrip       → 任务清单                                                        │
│    └── StatusRow       → 状态行 + 用量 chip                                              │
│  ToolDispatcher        → 11 个本地工具的真实实现（文件/搜索/Git/编辑/命令/诊断）         │
│  AgentCoreService      → 项目级单例：进程 + 连接 + 设置下发                              │
└────────────────────────────────────────┬─────────────────────────────────────────────────┘
                                         │ stdin/stdout，逐行 JSON（JSON Lines RPC）
┌────────────────────────────────────────┴─────────────────────────────────────────────────┐
│                            Agent Core（独立进程，Python）                                │
│  RpcServer（890 行，全协议入口）                                                        │
│    ├── SessionManager / SessionStore   → 会话历史（JSON v2，带 todos）                   │
│    ├── SessionSearch（SQLite trigram） → 历史会话全文检索                                │
│    ├── MemoryService（pending→promote）→ 长期记忆                                        │
│    ├── CodeIndexService（chunker+embedding）→ 语义检索                                   │
│    ├── SkillService / MCPManager / SubagentService / UsageLog                            │
│    └── ToolRegistry → 内置工具 + RemoteToolProxy（转发给 Shell）                          │
│  run_chat()（200 行）→ provider.chat_stream() ⇄ ToolScheduler                            │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**所有权一句话版**：

- **Shell 拥有环境**：文件系统、PSI/编译诊断、Git、终端、用户界面、确认弹窗。
- **Core 拥有策略**：LLM 调用、上下文裁剪、轮次预算、工具并发、命令白名单、记忆与检索。
- **策略随调用下发**：Core 在 `tool/invoke` 里带 `permission`，Shell 只负责按它弹卡并执行（`permissions.py` 注释原文：*The Shell owns the actual prompting*）。
- **状态只落一处**：会话历史在 Core 磁盘；UI 状态（草稿、标签标题、展开态）在 Shell 内存；`ReportSettingsState` 是唯一配置源。

---

## 2. 全局编号体系（读代码前必须先分清）

| 编号 | 生成方 | 格式 | 作用域 | 出现位置 |
|---|---|---|---|---|
| `projectId` | Shell | IDE 项目 location hash | 一个项目 | Core 进程启动参数 `--project-id`、数据目录 `~/.agent/data/<projectId>`、`core/hello` |
| `sessionId` | Shell | `<projectId>#<uuid 前 8 位>` | 一个聊天标签 | `chat/start`、`session/list|get|delete`、Core 会话文件 |
| `taskId` | Core | `uuid4.hex[:12]` | 一次发送 | 所有 `chat/*` 通知、`tool/invoke`、`agent/confirm`、`ask/user`；Shell 用它路由到标签 |
| `toolCallId` | LLM 提供方或 Core | 模型给的 id | 一次工具调用 | `chat/toolStart|toolEnd`、`tool/invoke` → `tool/result` 配对 |
| `confirmId` | Core | `uuid4.hex[:12]` | 一次 MCP 确认 | `agent/confirm` → `agent/confirmResult` |
| `askId` | Core | `uuid4.hex[:12]` | 一次提问 | `ask/user` → `ask/userAnswer`、超时 `ask/withdraw` |

⚠️ **命名陷阱**：`AgentCoreService.sessionId()` 返回的其实是 **projectId**（`project.getLocationHash()`），它被传给 `CoreProcess` 当 `--project-id`。真正的聊天会话 id 是 `AgentChatPanel.openSession()` 里拼出来的 `projectId#uuid8`。读代码时看到 `sessionId` 先确认是哪一个。

---

## 3. 分卷目录

| 卷 | 主题 | 覆盖源码 | 状态 |
|---|---|---|---|
| [01](01-request-lifecycle.md) | **一次请求的完整生命周期**（主线，先读） | composer → RPC → server → loop → scheduler → tool → 渲染 | ✅ 已完成 |
| [02](02-build-and-assembly.md) | **构建与装配**：插件描述、依赖、打包、脚本 | `pom.xml`、`plugin.xml`、`assembly/*`、`scripts/*.ps1`、`vscode/package.json` | ✅ 已完成 |
| [03](03-core-entry-and-rpc-server.md) | **Core 入口与 RPC 服务端逐函数** | `__main__.py`、`rpc/server.py`、`protocol.py` | ✅ 已完成 |
| [04](04-agent-loop-and-subagents.md) | **Agent 循环、调度、子代理、团队编排、清单** | `agent/{loop,scheduler,status,compression,permissions,commands,todos,ask,subagent,team,blackboard}.py` | ✅ 已完成 |
| [05](05-llm-layer.md) | **LLM 层：提供方、流式、重试、上下文预算** | `llm/{base,streaming,transport,context,tool_text,completion}.py`、`llm/providers/*` | ✅ 已完成 |
| [06](06-tool-system.md) | **工具系统：注册、调度、权限、Java 侧 11 个实现** | `tools/{registry,remote}.py`、`agent/{scheduler,commands,permissions,compression}.py`、`agent/tools/*.java`、`ui/{Diff,Command}ConfirmCard.java` | ✅ 已完成 |
| [07](07-state-memory-index.md) | **状态、记忆、检索、索引、MCP、Skills、用量** | `agent/{session,store,search,todos}.py`、`memory/*`、`codeindex/*`、`mcp/*`、`skills/*`、`usage.py`、`native/*` | ✅ 已完成 |
| [08](08-java-ui.md) | **Java UI：块渲染、卡片、面板、设计系统** | `ui/**`（含 `ui/chat/*`、`ui/markdown/*`、`ui/design/*`、`ui/picker/*`）、`AgentBundle.java`、`inline/*`、`src/test/**/ui/**` | ✅ 已完成 |
| [09](09-shells-and-verification.md) | **其它 Shell 与验证体系**（VS Code 扩展、测试、冒烟、跨层不变量） | `vscode/src/*.ts`、`vscode/scripts/*`、`core/tests/*`、`core/scripts/*`、`core/native/pyproject.toml` | ✅ 已完成 |
| [10](10-legacy-report.md) | **旧功能本体：日报/周报生成与设置页**（哪些代码还活着、两套系统的交叉点、缺陷分层） | `plugin/**`（Git 日志、AI 润色、设置页、报告面板、隐私弹窗）+ `agent/tools/GitTools.java`、`agent/rpc/AgentCoreService.java` 的交叉部分 | ✅ 已完成 |

> 卷 01–10 已全部完成，共 22,309 行正文（另加本索引 377 行）。卷 10 附有「三步判定活代码」方法与按影响分层的缺陷清单，建议与卷 01、卷 06 对照阅读。

---

## 4. 全量文件地图

行数为当前 HEAD 实测值；「卷」列指向讲解该文件的卷号。

> 行数口径：按文件里的实际代码行统计，**不计文件末尾的空行**。IDE 右下角显示的行号可能比表中数值多 1，这是统计口径差异，不是代码变了。

### 4.1 IntelliJ Shell（Java 81 文件 + Kotlin 1 文件，17,659 行）

| 文件 | 行 | 卷 |
|---|---:|---:|
| `src/main/kotlin/com/workreport/agent/inline/AgentInlineCompletionProvider.kt` | 80 | 08 |
| `src/main/java/com/workreport/agent/AgentBundle.java` | 24 | 08 |
| `src/main/java/com/workreport/agent/actions/GenerateReportAction.java` | 42 | 10 |
| `src/main/java/com/workreport/agent/actions/OpenSettingsAction.java` | 21 | 10 |
| `src/main/java/com/workreport/agent/inline/InlineCompletionClient.java` | 54 | 08 |
| `src/main/java/com/workreport/agent/rpc/AgentCoreService.java` | 228 | 01 |
| `src/main/java/com/workreport/agent/rpc/CoreProcess.java` | 148 | 01 |
| `src/main/java/com/workreport/agent/rpc/JsonRpcConnection.java` | 137 | 01 |
| `src/main/java/com/workreport/agent/rpc/PythonLocator.java` | 181 | 01 |
| `src/main/java/com/workreport/agent/tools/CommandTools.java` | 181 | 06 |
| `src/main/java/com/workreport/agent/tools/DiagnosticsTools.java` | 151 | 06 |
| `src/main/java/com/workreport/agent/tools/EditTools.java` | 206 | 06 |
| `src/main/java/com/workreport/agent/tools/FileSnapshots.java` | 45 | 06 |
| `src/main/java/com/workreport/agent/tools/FileText.java` | 153 | 06 |
| `src/main/java/com/workreport/agent/tools/FileTools.java` | 116 | 06 |
| `src/main/java/com/workreport/agent/tools/GitTools.java` | 72 | 06 |
| `src/main/java/com/workreport/agent/tools/ProjectWriteGate.java` | 59 | 06 |
| `src/main/java/com/workreport/agent/tools/SearchTools.java` | 113 | 06 |
| `src/main/java/com/workreport/agent/tools/ToolDispatcher.java` | 128 | 01 / 06 |
| `src/main/java/com/workreport/agent/tools/ToolResult.java` | 14 | 06 |
| `src/main/java/com/workreport/agent/ui/AgentChatPanel.java` | 1,037 | 08 |
| `src/main/java/com/workreport/agent/ui/AgentToolWindowFactory.java` | 22 | 08 |
| `src/main/java/com/workreport/agent/ui/ChatAttachment.java` | 155 | 01 |
| `src/main/java/com/workreport/agent/ui/ChatComposer.java` | 993 | 01 / 08 |
| `src/main/java/com/workreport/agent/ui/ChatSessionPanel.java` | 1,329 | 01 / 08 |
| `src/main/java/com/workreport/agent/ui/ChatTranscript.java` | 102 | 01 / 08 |
| `src/main/java/com/workreport/agent/ui/CommandConfirmCard.java` | 62 | 08 |
| `src/main/java/com/workreport/agent/ui/DiffConfirmCard.java` | 108 | 08 |
| `src/main/java/com/workreport/agent/ui/MarkdownRenderer.java` | 389 | 08 |
| `src/main/java/com/workreport/agent/ui/SessionTabsBar.java` | 692 | 08 |
| `src/main/java/com/workreport/agent/ui/StatusRow.java` | 161 | 08 |
| `src/main/java/com/workreport/agent/ui/TodoStrip.java` | 279 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/AgentMessageView.java` | 274 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/AskCard.java` | 627 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/BlockListLayout.java` | 137 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/BlockPanel.java` | 121 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/BlockView.java` | 119 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/ChatRowView.java` | 51 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/ChatView.java` | 736 | 01 / 08 |
| `src/main/java/com/workreport/agent/ui/chat/ClassicTranscript.java` | 514 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/CodeBlockView.java` | 177 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/DividerView.java` | 26 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/EmptyState.java` | 210 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/ErrorView.java` | 11 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/HeadingView.java` | 41 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/InlineLayout.java` | 261 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/ListView.java` | 83 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/MeasuredComponent.java` | 60 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/NoticeView.java` | 172 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/ParagraphView.java` | 33 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/QuoteView.java` | 39 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/ReasoningView.java` | 210 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/StepGroupView.java` | 206 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/TableView.java` | 116 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/TextCopy.java` | 51 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/ToolCallCard.java` | 387 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/TranscriptView.java` | 49 | 08 |
| `src/main/java/com/workreport/agent/ui/chat/UserMessageView.java` | 119 | 08 |
| `src/main/java/com/workreport/agent/ui/design/UiAnim.java` | 99 | 08 |
| `src/main/java/com/workreport/agent/ui/design/UiButton.java` | 344 | 08 |
| `src/main/java/com/workreport/agent/ui/design/UiIcons.java` | 190 | 08 |
| `src/main/java/com/workreport/agent/ui/design/UiMetrics.java` | 89 | 08 |
| `src/main/java/com/workreport/agent/ui/design/UiTheme.java` | 193 | 08 |
| `src/main/java/com/workreport/agent/ui/markdown/IncrementalParser.java` | 101 | 08 |
| `src/main/java/com/workreport/agent/ui/markdown/InlineSpan.java` | 93 | 08 |
| `src/main/java/com/workreport/agent/ui/markdown/MarkdownBlock.java` | 41 | 08 |
| `src/main/java/com/workreport/agent/ui/markdown/MarkdownParser.java` | 360 | 08 |
| `src/main/java/com/workreport/agent/ui/picker/PickerPopup.java` | 573 | 08 |
| `src/main/java/com/workreport/plugin/model/GitCommitEntry.java` | 18 | 10 |
| `src/main/java/com/workreport/plugin/privacy/PrivacyNoticeUtil.java` | 80 | 10 |
| `src/main/java/com/workreport/plugin/service/GitLogCollectorService.java` | 282 | 10 |
| `src/main/java/com/workreport/plugin/service/ReportPolishService.java` | 451 | 10 |
| `src/main/java/com/workreport/plugin/settings/ModelEndpoints.java` | 53 | 10 |
| `src/main/java/com/workreport/plugin/settings/ModelProfile.java` | 47 | 10 |
| `src/main/java/com/workreport/plugin/settings/ModelProfiles.java` | 117 | 10 |
| `src/main/java/com/workreport/plugin/settings/ReportSettingsConfigurable.java` | 928 | 10 |
| `src/main/java/com/workreport/plugin/settings/ReportSettingsState.java` | 139 | 10 |
| `src/main/java/com/workreport/plugin/settings/SettingsCard.java` | 188 | 10 |
| `src/main/java/com/workreport/plugin/ui/ReportToolWindowFactory.java` | 23 | 10 |
| `src/main/java/com/workreport/plugin/ui/ReportToolWindowPanel.java` | 754 | 10 |
| `src/test/java/com/workreport/agent/ui/design/UiIconsResourceTest.java` | 35 | 08 |
| `src/test/java/com/workreport/agent/ui/markdown/MarkdownParserTest.java` | 149 | 08 |

### 4.2 Python Agent Core（91 文件 / 15,332 行）

| 文件 | 行 | 卷 |
|---|---:|---:|
| `core/src/agent_core/__init__.py` | 47 | 03 |
| `core/src/agent_core/__main__.py` | 104 | 01 / 03 |
| `core/src/agent_core/protocol.py` | 173 | 01 / 03 |
| `core/src/agent_core/usage.py` | 162 | 07 |
| `core/src/agent_core/rpc/__init__.py` | 1 | 03 |
| `core/src/agent_core/rpc/server.py` | 890 | 01 / 03 |
| `core/src/agent_core/agent/__init__.py` | 1 | 04 |
| `core/src/agent_core/agent/loop.py` | 200 | 01 / 04 |
| `core/src/agent_core/agent/scheduler.py` | 108 | 01 / 04 |
| `core/src/agent_core/agent/status.py` | 123 | 04 |
| `core/src/agent_core/agent/compression.py` | 56 | 04 |
| `core/src/agent_core/agent/permissions.py` | 29 | 04 |
| `core/src/agent_core/agent/commands.py` | 130 | 04 |
| `core/src/agent_core/agent/todos.py` | 128 | 04 |
| `core/src/agent_core/agent/ask.py` | 194 | 04 |
| `core/src/agent_core/agent/subagent.py` | 583 | 04 |
| `core/src/agent_core/agent/team.py` | 733 | 04 |
| `core/src/agent_core/agent/blackboard.py` | 126 | 04 |
| `core/src/agent_core/agent/session.py` | 226 | 07 |
| `core/src/agent_core/agent/store.py` | 249 | 07 |
| `core/src/agent_core/agent/search.py` | 211 | 07 |
| `core/src/agent_core/llm/__init__.py` | 44 | 05 |
| `core/src/agent_core/llm/base.py` | 178 | 05 |
| `core/src/agent_core/llm/streaming.py` | 181 | 05 |
| `core/src/agent_core/llm/transport.py` | 135 | 05 |
| `core/src/agent_core/llm/context.py` | 116 | 05 |
| `core/src/agent_core/llm/tool_text.py` | 175 | 05 |
| `core/src/agent_core/llm/completion.py` | 148 | 05 |
| `core/src/agent_core/llm/providers/ollama.py` | 196 | 05 |
| `core/src/agent_core/llm/providers/openai_compat.py` | 206 | 05 |
| `core/src/agent_core/tools/__init__.py` | 1 | 06 |
| `core/src/agent_core/tools/registry.py` | 40 | 06 |
| `core/src/agent_core/tools/remote.py` | 181 | 01 / 06 |
| `core/src/agent_core/memory/__init__.py` | 7 | 07 |
| `core/src/agent_core/memory/store.py` | 260 | 07 |
| `core/src/agent_core/memory/files.py` | 105 | 07 |
| `core/src/agent_core/memory/pending.py` | 331 | 07 |
| `core/src/agent_core/memory/service.py` | 254 | 07 |
| `core/src/agent_core/codeindex/__init__.py` | 5 | 07 |
| `core/src/agent_core/codeindex/chunker.py` | 120 | 07 |
| `core/src/agent_core/codeindex/embeddings.py` | 124 | 07 |
| `core/src/agent_core/codeindex/store.py` | 225 | 07 |
| `core/src/agent_core/codeindex/service.py` | 223 | 07 |
| `core/src/agent_core/mcp/__init__.py` | 21 | 07 |
| `core/src/agent_core/mcp/client.py` | 281 | 07 |
| `core/src/agent_core/mcp/config.py` | 87 | 07 |
| `core/src/agent_core/mcp/manager.py` | 147 | 07 |
| `core/src/agent_core/skills/__init__.py` | 6 | 07 |
| `core/src/agent_core/skills/loader.py` | 183 | 07 |
| `core/src/agent_core/skills/service.py` | 67 | 07 |
| `core/src/agent_core/native/__init__.py` | 92 | 07 |
| `core/src/agent_core/native/tokenizer.py` | 73 | 07 |
| `core/src/agent_core/native/codesoup.py` | 195 | 07 |
| `core/tests/fake_mcp_server.py` | 71 | 09 |
| `core/tests/test_ask.py` | 174 | 09 |
| `core/tests/test_blackboard.py` | 111 | 09 |
| `core/tests/test_codeindex.py` | 128 | 09 |
| `core/tests/test_commands.py` | 68 | 09 |
| `core/tests/test_completion.py` | 59 | 09 |
| `core/tests/test_context.py` | 135 | 09 |
| `core/tests/test_llm_providers.py` | 329 | 09 |
| `core/tests/test_mcp.py` | 189 | 09 |
| `core/tests/test_memory.py` | 127 | 09 |
| `core/tests/test_memory_pending.py` | 203 | 09 |
| `core/tests/test_native.py` | 163 | 09 |
| `core/tests/test_scheduler.py` | 91 | 09 |
| `core/tests/test_session_search.py` | 120 | 09 |
| `core/tests/test_session_store.py` | 228 | 09 |
| `core/tests/test_skills.py` | 132 | 09 |
| `core/tests/test_status.py` | 151 | 09 |
| `core/tests/test_streaming.py` | 117 | 09 |
| `core/tests/test_subagent.py` | 364 | 09 |
| `core/tests/test_team.py` | 374 | 09 |
| `core/tests/test_todos.py` | 147 | 09 |
| `core/tests/test_tool_text.py` | 149 | 09 |
| `core/tests/test_transport_errors.py` | 195 | 09 |
| `core/tests/test_unknown_tool.py` | 96 | 09 |
| `core/tests/test_usage.py` | 98 | 09 |
| `core/tests/test_usage_totals.py` | 66 | 09 |
| `core/tests/test_version.py` | 27 | 09 |
| `core/scripts/bench_core.py` | 344 | 09 |
| `core/scripts/smoke_ask.py` | 244 | 09 |
| `core/scripts/smoke_command.py` | 213 | 09 |
| `core/scripts/smoke_mcp.py` | 217 | 09 |
| `core/scripts/smoke_memory.py` | 178 | 09 |
| `core/scripts/smoke_rpc.py` | 127 | 09 |
| `core/scripts/smoke_subagent.py` | 237 | 09 |
| `core/scripts/smoke_team.py` | 334 | 09 |
| `core/scripts/smoke_todos.py` | 186 | 09 |
| `core/scripts/smoke_tools.py` | 170 | 09 |
| `core/scripts/smoke_v3.py` | 203 | 09 |

### 4.3 VS Code Shell（TypeScript，10 文件 / 2,511 行）

| 文件 | 行 | 卷 |
|---|---:|---:|
| `vscode/src/extension.ts` | 46 | 09 |
| `vscode/src/rpc.ts` | 148 | 09 |
| `vscode/src/locator.ts` | 73 | 09 |
| `vscode/src/panel.ts` | 774 | 09 |
| `vscode/src/session.ts` | 669 | 09 |
| `vscode/src/tools.ts` | 521 | 09 |
| `vscode/src/diff.ts` | 40 | 09 |
| `vscode/src/inline.ts` | 123 | 09 |
| `vscode/src/secrets.ts` | 47 | 09 |
| `vscode/src/settings.ts` | 70 | 09 |

### 4.4 构建与配置文件（不在上面的代码统计里，但同样逐行讲）

| 文件 | 行 | 卷 |
|---|---:|---:|
| `pom.xml` | 310 | 02 |
| `src/main/resources/META-INF/plugin.xml` | 194 | 02 |
| `assembly/plugin-assembly.xml` | 58 | 02 |
| `scripts/build_core.ps1` | 37 | 02 |
| `scripts/build_native.ps1` | 137 | 02 |
| `scripts/build_plugin.ps1` | 82 | 02 |
| `scripts/publish_plugin.ps1` | 159 | 02 |
| `scripts/verify_all.ps1` | 101 | 02 |
| `scripts/verify_marketplace.ps1` | 79 | 02 |
| `core/pyproject.toml` | 30 | 02 |
| `core/native/pyproject.toml` | 15 | 09 |
| `core/packaging/agent-core.spec` | 76 | 02 |
| `vscode/package.json` | 166 | 02 |
| `.github/workflows/ci.yml` | 166 | 02 |
| `vscode/tsconfig.json` | 14 | 09 |
| `vscode/scripts/smoke-rpc.js` | 142 | 09 |
| `vscode/scripts/check-settings.js` | 59 | 09 |

---

## 5. 术语表

| 术语 | 含义 |
|---|---|
| Shell | IDE 侧的宿主：IntelliJ 插件（Java）或 VS Code 扩展（TypeScript） |
| Core | 独立的 Python 进程 `agent_core`，通过 stdio 与 Shell 通信 |
| 帧 / frame | 一行 JSON，即一条完整的请求、响应或通知 |
| 通知（notification） | 没有 `id` 的帧，不期待回包 |
| 请求（request） | 带 `id` 的帧，等待同 `id` 的响应 |
| 一轮（round / turn） | 一次 LLM 调用 + 该次返回的所有工具执行；`max_iterations` 限制轮数上限 |
| 任务（task） | 一次 `chat/start` 到 `chat/finish|error` 的完整执行，由 `taskId` 标识 |
| 远程工具 | `ToolDef.remote=True` 的工具，实现体在 Shell，经 `tool/invoke` 转发 |
| 内置工具 | 实现在 Core 内的工具（memory/skill/subagent/todo/semantic_search/build_index 等） |
| pending 记忆 | 从对话里规则抽取、等待用户 promoted 的候选记忆 |
| EDT | IntelliJ 的事件分发线程；所有 Swing 改动必须在它上面 |
| 池线程 | `ApplicationManager.executeOnPooledThread`，阻塞操作（工具执行）在这里跑 |

---

## 6. 自己动手验证（保持文档与代码同步）

```powershell
# Core 全量单测（约 298 例，1 分钟内）
cd core; python -m unittest discover -s tests

# 协议冒烟（每个都不需要真实 LLM）
cd core; python scripts/smoke_rpc.py
cd core; python scripts/smoke_tools.py

# 插件编译（JDK 25）
mvn -q -DskipTests compile

# 全量回归
powershell -File scripts/verify_all.ps1
```

改动代码后如果行号发生漂移，以函数名定位；本导读在每处引用都同时给出函数名。

---

## 7. 先从哪一行开始？

如果只有 30 分钟，按这个顺序读：

1. `ChatSessionPanel.sendMessage()`（Java，498 行）
2. `RpcServer._dispatch()`（Python，234 行）
3. `RpcServer._run_task()`（Python，549 行）
4. `run_chat()`（Python，90 行）
5. `ToolScheduler.run_parallel()`（Python，51 行）
6. `ToolDispatcher.onToolInvoke()`（Java，51 行）

这 6 个函数就是整个系统的骨架；其余 3 万行都在为它们服务。
