# Agent 面板 UI 3.1：设计令牌与组件清单

> 3.1.0 把 Agent 面板的对话区从「`JEditorPane` + 整段 HTML 重建」换成「Markdown 块模型 + 原生 Swing 绘制」，
> 并统一了标签条、状态行、输入区、空态、弹窗与确认卡片的视觉语言。
>
> 本文记录**设计令牌**与**组件边界**，一方面作为 IntelliJ 端后续修改的依据，另一方面供 VS Code 端对齐
> （令牌名与语义一一对应，只是色值来源换成 `--vscode-*`）。实施背景见 `ROADMAP_TO_3.0.md`，
> 用户视角的操作说明见 `USER_GUIDE.md` §4。

---

## 1. 分层

```text
ui/design/    UiTheme（颜色/字体）、UiMetrics（间距/圆角/行高）、UiIcons（图标）、UiAnim（动画）、UiButton
ui/markdown/  MarkdownBlock（块模型）、InlineSpan（行内模型）、MarkdownParser（解析）、IncrementalParser（增量）
ui/chat/      BlockView / ChatRowView / MeasuredComponent（测量契约）
              块视图 7 种、消息容器 5 种、ChatView（容器 + 流式）、BlockListLayout（布局）
              EmptyState、ToolCallCard、AskCard、ClassicTranscript（回退实现）
ui/picker/    PickerPopup（历史 / 记忆共用）
ui/           AgentChatPanel（标签条、图标组、弹窗接线）、ChatSessionPanel（会话内状态行 + 卡片兜底）
              ChatTranscript（门面，公开 API 不变）、ChatComposer、TodoStrip、SessionTabsBar
```

渲染流水线：

```text
Core token 流 ─► ChatView.appendAssistantToken ─► 40 ms 合并窗口
             ─► IncrementalParser.feed（只重解析尾块）
             ─► 块对象 diff（前缀复用） ─► 只重建/重绘变化的 BlockView
             ─► BlockListLayout 按宽度取缓存高度 ─► repaint 受影响的矩形
```

---

## 2. 颜色令牌（`UiTheme`）

全部是 `JBColor`（浅色 / 深色双值），在**绘制时**解析，切换明/暗/高对比主题无需重建组件。

| 令牌 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `surface()` | `UIUtil.getPanelBackground()` | 同（实时） | 面板底 |
| `RAISED` | `#FFFFFF` | `#2B2D30` | 消息卡片、确认卡片、输入区浮动卡片 |
| `SUBTLE` | `#F4F6F9` | `#313438` | 代码块、chip、参数块、次要按钮 |
| `BORDER` | `Borders.color` → `#DBDFE6` | `#3A3D41` | 统一 1 px 描边 |
| `text()` | `UIUtil.getLabelForeground()` | 同（实时） | 正文 |
| `muted()` | `UIUtil.getContextHelpForeground()` | 同（实时） | 元信息、次要行、占位符 |
| `ACCENT` | `Link.activeForeground` → `#3574F0` | `#4B8BFF` | 选中、发送、链接、用户气泡 |
| `ACCENT_SOFT` | accent @ 30 alpha | accent @ 48 alpha | 选中 pill、用户气泡（深色） |
| `accentSoft(a)` | accent @ a | accent @ a | 焦点环（`a≈90`）等一次性半透明 |
| `HOVER` | `#EAF0FA` | `#34383D` | 行/按钮 hover 底 |
| `OK` | `Notifications.successForeground` → `#2E7D32` | `#73B27A` | 完成勾、允许态 |
| `WARN` | `Notifications.warningForeground` → `#B26B00` | `#D9A343` | 待确认状态条、记忆重复提示 |
| `ERROR` | `Notifications.errorForeground` → `#C0392B` | `#F08A8A` | 错误行、拒绝态 |
| `ERROR_SOFT` | `#FDECEC` | `#3A2626` | 错误行浅底 |

规则：

1. 面板背景、正文、次要文字必须**实时解析**（`surface()/text()/muted()`），不要缓存成常量。
2. 带主题键的颜色一律用 `JBColor.namedColor(键, fallback)`，平台键缺失时仍有合理配色。
3. 不新增「只适合浅色」的字面色；确需叠加（例如主色按钮 hover 提亮）用 `withAlpha(Color.WHITE, 46)`。

## 3. 字体与行高

按 LAF 缓存，基础字体变化时自动重建（`UiTheme.refresh()` + `UIManager` 的 `lookAndFeel` 监听会让所有窗口重绘）。

| 令牌 | 定义 | 用途 |
|---|---|---|
| `font()` | `UIUtil.getLabelFont()` | 正文 |
| `small()` | `font - 1` | 元信息、chip、行内动作 |
| `bold()` | `font` + BOLD | 步骤块标题、强调 |
| `mono()` | `Font.MONOSPACED`，`max(11, font-1)` | 代码块、工具参数 |
| `h1()/h2()/h3()` | `font +4 / +2 / 0`，全部 BOLD | 标题块 |
| `UiMetrics.lineHeight(f)` | `round(metrics.height × 1.15)` | 段落 / 列表 / 表格单元格行高 |

## 4. 尺寸令牌（`UiMetrics`，全部经 `JBUI.scale`）

| 类别 | 值 |
|---|---|
| 间距阶 | `xs 4 / s 6 / m 8 / l 12 / xl 16 / xxl 20` |
| 圆角 | 气泡 12、卡片 10、chip 8、输入框 12、按钮 6 |
| 描边 | 统一 1 px；焦点环 2 px `accentSoft(90)`；状态条 3 px |
| 滚动 | 单元增量 `scale(40)` |
| 气泡最大宽度 | 可视宽度 72% |
| 代码块折叠阈值 | 18 行 |
| 折叠更早消息 | 保留最近 120 块，每次展开 40 块 |
| 流式合并窗口 | 40 ms |
| Picker 行高 | 44 px（副标题 60 px）；宽度 380（历史）/ 420（记忆） |

## 5. 组件清单

### 5.1 块视图（`ui/chat`，实现 `BlockView` + `MeasuredComponent`）

| 组件 | 职责 |
|---|---|
| `HeadingView` | h1/h2/h3 字号层级 + 上间距 |
| `ParagraphView` | `InlineLayout` 按宽度重排：粗体/斜体/删除线/行内代码/链接混排，链接 hover 变手型并可点开 |
| `ListView` | 悬挂缩进：marker 列固定宽度、每级 +16 px、续行对齐正文、有序号右对齐 |
| `CodeBlockView` | `SUBTLE` 底 + 圆角 + 语言 chip + 复制按钮；> 18 行折叠为「展开全部 N 行」，展开按需上语法高亮 |
| `QuoteView` | 左侧 3 px `accent@40%` 竖条 + `muted` 正文 |
| `TableView` | 自绘网格：表头 `SUBTLE` 底、单元格按宽度换行、行高取该行最高单元格 |
| `DividerView` | 1 px `BORDER` |

测量契约：`MeasuredComponent.heightFor(width)` 按宽度缓存；`BlockListLayout` 只让被修改的块失效，
把「每次全量测量」变成均摊 O(1)。

### 5.2 消息容器（`ui/chat`，继承 `ChatRowView`）

| 组件 | 职责 |
|---|---|
| `UserMessageView` | 右对齐、主色气泡（深色主题转 `ACCENT_SOFT` + 正常文字）、最大宽度 72%、附件 chip 行 |
| `AgentMessageView` | 左对齐卡片：头部 `Agent` chip + 模型名 + token + 耗时；hover 浮出「复制」（`ui.message.copy`，1.2 s 后变「已复制」）；流式期尾块闪烁光标（Timer 只在流式期运行） |
| `StepGroupView` | 连续 `[工具]` / `[子代理]` 行合并成一条可折叠时间线；折叠态一行 `N 步 · 耗时 · 状态` |
| `NoticeView` | 提示行：左侧 3 px `WARN` 条 + `SUBTLE` 底 |
| `ErrorView` | 错误行：左侧 3 px `ERROR` 条 + `ERROR_SOFT` 底 + 「复制错误」 |
| `ToolCallCard` | MCP 确认卡片：标题 + 描述 + 参数（`mono`，最多 4 行，超出加 `…`）+ `[拒绝] [允许]`；`Enter` 允许 / `Esc` 拒绝；`addNotify` 抢焦点，`removeNotify` 视为拒绝（不让 Core 一直等）；已答后按钮替换为「已允许 / 已拒绝」 |

### 5.3 容器与布局

| 组件 | 职责 |
|---|---|
| `ChatView` | 流式合并（40 ms）、尾块光标、贴底检测、「回到底部 ↓」胶囊、`KEEP_ROWS=120` 折叠 |
| `BlockListLayout` | `LayoutManager2`：按宽度缓存高度，只失效被改的块 |
| `TranscriptView` | 实现接口（`addHint/addUserMessage/appendAssistantToken/.../addCard`），`ClassicTranscript` 为回退实现 |
| `ChatTranscript` | 门面，公开 API 与 3.0 一致（仅**新增** `addCard`），`ChatSessionPanel` 无需感知实现 |
| `SessionTabsBar` | 自绘 pill 标签（选中指示条 140 ms 补间、运行中 1.2 s 呼吸点、hover 关闭、`Ctrl+Tab` / `Ctrl+1..8`、溢出折叠 `»`）、右侧图标组与「记忆」角标 |
| `EmptyState` | 产品图标 + 标题 + 4 张建议卡（点击填入输入框） |
| `ChatComposer` | 浮动卡片 + 焦点环 + 自绘 chip + 圆形发送；公开 API 不变 |
| `TodoStrip` | 自绘 chevron 与状态标记（环形 / 半环 / 勾），不再依赖 `☐ ◐ ☑` 字形 |
| `PickerPopup` | 历史 / 记忆共用：顶部搜索（300 ms 防抖）、行内 hover 动作（从右往左排布）、`↑↓` 选行 / `Enter` 打开 / `Esc` 关闭、空结果文案、底部快捷键提示 |
| `ClassicTranscript` | `-Dagent.ui.classic=true` 时的 3.0 实现（`MessageRow` + `MarkdownRenderer.toHtml`），3.2.0 删除 |

## 6. 图标

`src/main/resources/icons/`：`agent` / `attach` / `history` / `memory`，各带 `_dark.svg`；
其余动作（复制、重试、删除、搜索、设置、加号、chevron、勾、关闭、发送箭头、停止方块）由 `UiIcons`
**程序化绘制**或复用平台 `AllIcons`，避免为一次性图形增加资源文件。

规则：加载失败退化为空图标并继续运行（`UiIcons.icon` 不抛异常）；所有图标尺寸经 `JBUI.scale`，200% 缩放下不错位。

## 7. 动画（`UiAnim`）

单 `Timer` 驱动、离屏不跑、单帧目标 ≤ 4 ms。

| 场景 | 规格 |
|---|---|
| 按钮 / 图标 hover | 底色 alpha 0→1，120 ms |
| 标签切换 | pill 指示条 140 ms ease-out |
| 运行中呼吸点 | 1200 ms 循环（alpha 0.35→1） |
| 新消息入场 | 160 ms 淡入 + 上移 4 px |
| 「回到底部」胶囊 | 120 ms 淡入 |
| 步骤块展开 | 140 ms 高度补间（> 200 px 直接跳变） |

## 8. 键盘与可达性

| 位置 | 按键 |
|---|---|
| 会话标签 | `Ctrl+Tab` 循环、`Ctrl+1..8` 直选、中键关闭 |
| 输入区 | `Enter` 发送、`Shift+Enter` 换行、`Ctrl+Enter` 发送 |
| 历史 / 记忆弹窗 | `↑`/`↓` 选行、`Enter` 打开、`Esc` 关闭、`Tab` 到搜索框 |
| MCP 确认卡片 | `Enter` 允许、`Esc` 拒绝 |
| 提问卡片 | `Enter` 提交、`Esc` 跳过；「其它…」行内 `JTextField` 正常编辑 |

- 所有动作都有 tooltip；自绘控件维护 hit-test 矩形，鼠标不落在矩形上则不改光标。
- 文本可选可复制：每块安装 `TransferHandler` 输出纯文本（代码块保留缩进），消息级「复制」兜底。
- 绘图不使用固定像素：间距、圆角、图标全部 `JBUI.scale`。

## 9. 回退与兼容

| 开关 | 行为 |
|---|---|
| `-Dagent.ui.classic=true` | 走 `ClassicTranscript`（3.0 的 HTML 渲染），其余面板元素保持 3.1 外观；3.2.0 删除该分支 |
| 解析异常 | 该块降级为纯文本块并记一次日志，不抛到 EDT |
| 面板不可见 | MCP 确认回退到模态对话框（`transcript.isShowing()` 为假时）；模型提问回退到逐题「选择 / 输入」对话框 |
| 平台差异（252–262） | 只用稳定 API：`JBUI` / `JBColor` / `UIUtil` / `AllIcons` / `JBPopupFactory` / `EditorTextField` |

## 10. 性能门槛

| 指标 | 目标 |
|---|---|
| EDT 单次任务 | < 8 ms |
| 流式期间 CPU | < 8% |
| 240 条会话内存增量 | < 60 MB |
| 标签切换 | < 50 ms |
| 4000 字 / 3 代码块 / 2 表格的首屏 | < 300 ms |

## 11. i18n

新增文案统一前缀 `ui.*`（`ui.chat.*` / `ui.composer.*` / `ui.empty.*` / `ui.steps.*` / `ui.code.*` /
`ui.message.*` / `ui.picker.*` / `ui.confirm.*` / `ui.ask.*`），同时写入 `messages/AgentBundle.properties` 与
`AgentBundle_en.properties`，两文件键必须等量（`verify_all.ps1` 会校验）。Core 的提示词与工具描述仍保持中文。
