# Changelog

Mirror of `plugin.xml` `<change-notes>`: every released version gets one section here, and the
Marketplace entry is copied from it. Core, Shell and VS Code Shell share the numbered release.

## 3.3.0（开发中）

Agent 团队：可创建、启用并由项目经理编排多成员协作；默认关闭，不启用时行为与 3.1.2 逐帧一致。

- **内置团队**：`software-dev-team` = 调研员（只读）+ 全栈工程师（可写）+ 测试工程师（可写+终端）+ 代码审查员（只读）；团队说明与协作指令逐字取自需求，作为 PM 剧本注入主代理提示词。
- **自由组合**：团队只是对角色的引用 + 能力授权，`TEAM.md` 支持 builtin → `~/.agent/teams` → `<项目>/.agent/teams` 逐级覆盖；同一角色可在 A 团队只读、在 B 团队授权写。
- **能力模型**：`readonly` / `write` / `command` 三档只对启用团队的成员生效；临时 `subagent_dispatch` 仍然只读（D1 不被削弱），`delete_file` 永不随能力授予。
- **预审检查点（D-A）**：实现阶段前 `team_preapprove` 出一次「计划 + 首个 diff」确认卡；通过后本阶段常规写自动执行，删除、白名单外命令与危险命令仍逐次确认/拒绝。
- **团队黑板**：`team_post`/`team_read` 传递研究、计划、实现、测试、审查交付物，随会话落盘（store v3），重开会话可回看。
- **协议（只增不改）**：`ChatConfig` 新增 `active_team` / `team_max_members` / `team_turn_cap` / `team_auto_after_preapprove`；新增 `team/list`、`team/artifacts`；`chat/subagent` 扩 `teamRunId`/`actor`/`capability`，新增 `chat/artifact`，`tool/invoke` 可带 `member`，`agent/confirm` 新增 `kind="preapprove"`，`core/hello` 能力加 `team`。
- **IntelliJ 接入**：输入区新增「团队」下拉（Core 的 `team/list` 名册，含人数与说明）与「交付物」按钮（`team/artifacts` → 列表→只读全文窗）；预审卡是 `ToolCallCard` 的特化（计划 / 文件 / diff，多行可滚）；成员时间线显示「全栈工程师[write]」；i18n 新增 `ui.team.*` / `ui.preapprove.*`（265 键中英等量）。
- **VS Code 对齐**：新增 `agentCoder.activeTeam` 设置项（与 IntelliJ 的 `settings/update.active_team` 字段对等，`check-settings` 断言 14 项一致）；Webview 团队下拉 + 「交付物」抽屉 + 预审卡（标题 + 多行正文）；`ide/hello` 声明 `team` 能力。
- **兼容护栏**：Core 只在 Shell 声明 `team` 能力时才发预审卡；旧 Shell 收不到也不会把预审误当 MCP 确认。
- **性能基线**：`bench_core.py` 新增帧数 / 字节 / 编解码占比三项指标并支持 `--stream-chunks`；800 段流式实测 1601 帧 / 152.8 KB，JSON 编解码仅占墙钟 2.0%，据此在 ROADMAP §7.4 定下「继续 JSON Lines」的结论。
- **设置页重做**：一列到底的 `FormBuilder` + 表格换成圆角卡片分节（模型与端点 / Agent 行为 / 编辑器与索引 / Git 日报 / MCP 服务 / 命令白名单 / Core 运行环境）。模型改为「左侧列表 + 右侧表单」：新增类型下拉、`JBPasswordField` 密码框与「当前模型」下拉（不必回聊天面板切换）；API Key 字段留空即保持不变，MCP 配置边输边做 JSON 校验，保存时对空名 / 重名 / 缺端点直接报错。
- **输入区**：发送与停止合并为一个按钮（运行中变方块并切换提示），工具栏用细竖线分成「附件 / 模型·团队·交付物 / 自动」三组；状态行耗时在运行期每秒刷新。
- **流畅度**：Core 事件统一归一到 EDT——此前 `chat/todos` / `chat/artifact` / `chat/finish` 在 RPC 读线程直接改 Swing 组件；流式 token 改为入队合批、每批只投递一次 EDT 任务；输入框自增高 60 ms 去抖，底部滚动请求合并，长文本输入与高速流式不再堆积 EDT 队列。
- **思考过程**：此前 Core 只读 `delta.content`，vLLM `--reasoning-parser` / DeepSeek / Qwen3 写在 `delta.reasoning_content`（部分端点用 `delta.reasoning`）与 Ollama 的 `message.thinking` 里的思考被整段丢弃，界面上看不到。现在两个流式累加器各自解析思考字段，经新增的 `chat/reasoning` 通知（`{taskId, delta}`，与 `chat/token` 并行、顺序先于正文）下发；Shell 新增 `ReasoningView` 折成正文上方一行，思考期间展开随到随看，正文一开始就收起为「思考过程 · 12.4s · 3204 字」，点标题仍是**全文**（折叠只隐藏、绝不截断）。历史消息与 `chat/token` 格式不受影响。
- **工具时间线改英文**：`[工具] read_file 执行中...` / `完成|失败` / `[子代理] ... 开始：` / `[确认] MCP 服务 ...` 换成开发者日志风格——`read_file · src/main/App.java · done · 0.4s · summary`、`agent 全栈工程师[write] · start|done|failed · 3.2s · summary`、`mcp server · tool · approval required`、`queued · waiting for session 2 · edit_file`；参数取 `path/command/query/pattern/...` 中第一个命中项，空白压成单空格、超长截断到 120 字。步骤组标题也随之变成 `3 steps · running|done|failed`（`ui.steps.*` 中英两个包同值）。
- **状态不再靠中文关键词嗅探**：删除 `ChatView.isRunningTool(String)` 的 `contains("执行中")`/`contains("开始：")` 与 `StepGroupView` 的 `contains("失败")`，改为 `addToolLine(text, running, failed)` / `add()` 结构化传参；并行调用用 `activeSteps` 计数，「还在跑」不再被先返回的那个工具提前置为 done；`chat/finish` 与 `chat/cancel` 清空在途表，步骤组不会永远停在 running。
- **团队开关修复**：冷启动时 Core 还没起来、`team/list` 名册为空，按钮按 `!teams.isEmpty()` 置灰，而 `UiButton` 的 `isEnabled()` 判断直接把点击吞掉——表现就是「点了没反应」，且名册只在面板构造时拉一次，Core 起来后也不会自愈。现在按钮只随 `busy` 置灰，点击时按需启动 Core 并拉名册；名册为空改为时间线提示「读不到团队名册」，`core/hello` 后自动刷新一次（与 VS Code 对齐），重启 IDE 也能显示上次保存的团队名。
- **验证**：Core 单测 **298 例**全绿（含 D1 只读回归护栏，新增 4 例 `reasoning_content` / `thinking` 分流与「思考先于正文」顺序断言）、9 个协议冒烟 PASS（含 `smoke_team.py`：预审一次 → 成员写自动执行 → 黑板回传）；`mvn -DskipTests compile` 与 `mvn test`（15 例）通过，`npm run compile` 通过，`node scripts/check-settings.js` = SETTINGS PARITY OK。

## 3.1.2

修复标签条上「新建会话」（`+`）与「设置」按钮不显示。

- **图标资源**：`SessionTabsBar` 取的是 `icons/add.svg` 与 `icons/settings.svg`，但包里一直只有
  `agent` / `attach` / `history` / `memory` 四个图标；`UiIcons.icon()` 在文件缺失时退化成
  `EmptyIcon`，两个按钮就成了看不见、只能靠悬停底色判断位置的空白。
- **补齐资源**：新增 `add.svg` / `add_dark.svg`、`settings.svg` / `settings_dark.svg`，与既有图标
  同一套线宽（1.4~1.6）和明/暗取色（`#6B7178` / `#A9AEB4`）。
- **防回归**：`UiIcons.icon()` 加载失败时写 `WARN` 日志而不是静默退化；新增 `UiIconsResourceTest`
  校验面板引用的每个图标都带 `_dark` 变体。

## 3.1.1

适配 IntelliJ IDEA 2026.3（构建号 `263.*`），无功能变化。

- **平台基线**：`pom.xml` 的 `intellij.version` 从 `261.25134.95`（2026.1）升到 `263.7307.259`（2026.3），
  `until-build` 从 `262.*` 提到 `263.*`，安装区间变成 2025.2 ~ 2026.3。
- **Kotlin**：`kotlin.version` 跟平台内置的 `kotlin-stdlib` 一致，`2.3.20-RC2` → `2.4.21-RC`；
  `kotlinx-coroutines-core-jvm` 同步到 `1.11.0-intellij-1`，`org.jetbrains:annotations` 同步到 `26.1.0`。
- **构建工具链**：2026.3 平台的 class 文件版本是 69（Java 25），`mvn compile` 改用 JDK 25（本机可借用 IDE 的
  JBR 25）；`source/target` 仍是 21，插件字节码不变。`verify_all.ps1` / `build_plugin.ps1` / CI 都加了
  JDK 25 检查或改用 JDK 25。
- **Git 依赖**：263 把 Git 插件拆成 frontend / shared / backend，`com.jetbrains.intellij.vcs:git` 只剩插件描述符，
  新增 `git-backend` / `git-shared` 两个 `provided` 依赖提供 `git4idea.*`。
- **核心包版本**：`pom.xml`、`plugin.xml` 与内置 Core 可执行文件同步 3.1.1（`agent-core --version` 与
  `core/hello.coreVersion` 都应是 3.1.1）。

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
