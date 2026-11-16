# Changelog

Mirror of `plugin.xml` `<change-notes>`: every released version gets one section here, and the
Marketplace entry is copied from it. Core, Shell and VS Code Shell share the numbered release.

## 3.1.0

对话渲染与界面视觉重做（`docs/UI_OVERHAUL.md` 记录了设计令牌与组件清单）。

- **渲染内核**：不再用 `JEditorPane` + `MarkdownRenderer.toHtml` 重建整段 HTML。Markdown 先解析成
  块模型（标题 / 段落 / 列表 / 代码块 / 引用 / 表格 / 分割线），再由自绘块视图排版；流式只重解析
  尾部块（`IncrementalParser`，40 ms 合并窗口），长回答不再掉帧、不跳滚动条。
- **布局**：`BlockListLayout` 按宽度缓存每块高度，只失效被修改的块；超过 120 块折叠为一行
  「查看更早的 N 条消息」，分批展开。
- **消息容器**：用户消息右对齐气泡、助手卡片（角色 chip + 元信息 + hover 复制）、步骤组
  （连续的 `[工具]`/`[子代理]` 行合并为可折叠时间线）、提示/错误细状态行。
- **面板**：`SessionTabsBar` 自绘标签（选中指示条、运行中呼吸点、hover 关闭、`Ctrl+Tab`/`Ctrl+1..8`
  切换、溢出折叠为 `»`）、状态行下移到输入区上方、`EmptyState` 建议卡、浮动卡片输入区
  （焦点环、圆形发送、自绘附件 chip）、`TodoStrip` 自绘状态标记。
- **弹窗**：历史与记忆共用 `PickerPopup`（顶部搜索 + hover 动作 + ↑↓/Enter/Esc），记忆的
  `duplicate` 提示改为行内黄色小字。
- **MCP 确认**：模态对话框改为对话区内的 `ToolCallCard`，Enter 允许 / Esc 拒绝；面板不在屏幕上时
  仍用对话框兜底。
- **主题**：所有颜色走 `JBColor` 在绘制时解析，跟随明/暗/高对比主题即时切换。
- **回退开关**：`-Dagent.ui.classic=true` 仍可退回 3.0 的 HTML 渲染（3.2.0 删除）。
- **模型提问**：新增阻塞式 `ask_user` 工具（1~3 题、每题 2~4 个选项 + 自定义答案）。Core 发 `ask/user`、Shell 回 `ask/userAnswer`，`AskCard` 插在对话区（`Enter` 提交 / `Esc` 跳过）；交互式工具独占一轮，避免“用户还在选、文件已被改”；Shell 在 `ide/hello` 声明 `ask-user`，未声明或超时（30 分钟）时降级为“带假设继续”，`chat/cancel` 会撤销卡片（`ask/withdraw`）。`todo_write` 之后，Agent 又多了一条“先问清楚再动手”的路径。
- 版本号收口：`pom.xml`、`plugin.xml` 同步 3.1.0；`plugin.xml` 的 `toolWindow` 补上图标；新增 `core/scripts/smoke_ask.py` 端到端冒烟与 `tests/test_ask.py`。

## 3.0.0

产品化收口（ROADMAP §7）。

- **记忆沉淀**：对话里出现「以后都…」「不要…」这类偏好，或「不是…而是…」这类纠正时，
  Core 用规则抽取候选，落到 `<项目>/.agent/memories/_pending/`，不进 `memory_overview`。
  面板「记忆」里可确认 / 编辑 / 忽略（`memory/pending`、`memory/promote`、`memory/discard`），
  确认时对已有记忆做相似度提示。
- **会话全文检索**：`sessions.db` 中的 `sessions_text` 镜像 + FTS5（trigram，不支持时回退
  `LIKE`），保存会话时增量索引，首次启动后台全量建索引并上报 `session/indexProgress`。
  `session/search` 返回带 `<mark>` 的片段，历史弹窗顶部搜索框 300 ms 防抖即时搜索。
- **本地用量统计**：`~/.agent/logs/usage.jsonl` 每任务一行，超过 10 MB 轮转为
  `usage-YYYYMM.jsonl`；`usage/report` 按需聚合近 7/30 天，不上报、不做用户标识。
- **性能基线**：`core/scripts/bench_core.py` 记录冷启动、空闲 RSS、单轮延迟、千条会话列表；
  千条列表从 211 ms 降到 105~110 ms（并行读取 + 单连接批量建索引）。
- **i18n**：Shell 按钮、状态行、设置页标签走 `AgentBundle`（`properties` / `_en.properties`），
  IDE 切英文即为英文；Core 提示词与工具描述保持中文。
- Marketplace `change-notes` + 版本号收口（`pom.xml`、`plugin.xml` 同步 3.0.0）。

## 2.7.0

- VS Code：行内补全（`inline.ts`）、设置项补齐与 `settings/update` 一致性断言、
  API Key 迁移到 SecretStorage、历史会话面板。
- 能力矩阵核对表归档到 `vscode/README.md`。

## 2.6.0

- `run_command` 工具：白名单直接执行、其余逐次确认、`rm -rf` / `git push` / `format` 等
  危险命令直接拒绝；两端实现与单测。
- `diagnostics` 工具、`todo_write` 工具 + `chat/todos` 事件 + 会话落盘 + 两端清单 UI。

## 2.5.0

- 上下文预算（`contextWindow`，按 75% 折算历史预算）；用量聚合与 `usageTotal`；
  传输层重试退避；日志落盘与轮转；设置页四项与双端下发对齐。

## 2.4.10

- CI 覆盖 `develop` / `master`；About 文案；`agent_core.__version__` 支持环境变量注入；
  `--version` 与 `core/hello.coreVersion` 一致。
