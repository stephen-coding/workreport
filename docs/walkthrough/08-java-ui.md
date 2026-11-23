# 第 08 卷 · Java UI：块渲染、卡片、面板与设计系统

> 本卷覆盖 `src/main/java/com/workreport/agent/ui/**` 的全部 48 个文件，外加 `AgentBundle`（24 行）
> 与 `agent/inline/**` 的补全客户端（134 行）。合计 **51 个文件 / 12,400 行**（不含 2 个测试文件，
> 它们属于卷 09）。这是整个项目里体量最大、耦合最密的一块：它既是最外层（用户唯一直接接触的
> 表面），又是最内层的终点（Core 的每一个 token 最终都落成这里的几个像素）。
>
> 读法建议：先读 §0 的分层图与文件分工表，再按 §1 → §2 → §3 的顺序跟一次真实的「用户按 Enter →
> 屏幕出现答案」，然后回头读 §5–§10 的渲染细节。§15–§17 是横向切片（线程、度量、契约），
> 可以在读完纵向流程后当检查表用。

---

## §0 卷导览

### 0.1 统计与分组

| 目录 | 文件数 | 行数 | 职责 |
| --- | ---: | ---: | --- |
| `ui/`（根） | 12 | 5,329 | 工具窗口装配、会话容器、输入框、标签条、Markdown HTML 回退、待办/状态条、附件、确认卡片 |
| `ui/chat/` | 26 | 4,830 | 对话区的两套实现（块渲染 / 经典 HTML）、行基类、布局、9 种块视图、6 张卡片（含 ReasoningView） |
| `ui/design/` | 5 | 915 | 尺寸与字体（`UiMetrics`）、颜色与字体（`UiTheme`）、动画（`UiAnim`）、图标（`UiIcons`）、自绘按钮（`UiButton`） |
| `ui/markdown/` | 4 | 595 | 块数据模型（`MarkdownBlock`）、行内片段（`InlineSpan`）、解析器（`MarkdownParser`）、增量包装（`IncrementalParser`） |
| `ui/picker/` | 1 | 573 | 通用可搜索弹层（历史会话、待确认记忆共用） |
| `agent/`（根） | 1 | 24 | `AgentBundle`：i18n 入口 |
| `agent/inline/` | 2 | 134 | 行内补全：Java 侧的 `InlineCompletionClient` + Kotlin 侧的 `AgentInlineCompletionProvider` |

单文件前六名（占全部 UI 代码的 44%）：

```
1,329  ui/ChatSessionPanel.java      会话：发送、14 路事件分发、确认/提问、用量
1,037  ui/AgentChatPanel.java        容器：多标签、历史、记忆、设置、事件路由
  993  ui/ChatComposer.java          输入：多行自适应、附件、模型下拉、悬停动画
  736  ui/chat/ChatView.java         对话区：40ms 合流、双脏标记、按批卸载旧行、回到底部胶囊
  692  ui/SessionTabsBar.java        标签条：全自绘布局、收缩算法、溢出菜单
  627  ui/chat/AskCard.java          提问卡：最多 3 题、单选/多选/自定义输入
```

### 0.2 分层图

```
                       ┌───────────────────────────────────────────┐
                       │ AgentToolWindowFactory  (22)              │
                       │  createToolWindowContent → new Panel      │
                       └───────────────────┬───────────────────────┘
                                           │
┌──────────────────────────────────────────▼──────────────────────────────────────────┐
│ AgentChatPanel (1,037)  SimpleToolWindowPanel implements Disposable                  │
│  ├─ SessionTabsBar (692)          ── 标签、+、历史、记忆角标、设置、溢出 »            │
│  ├─ JPanel(CardLayout)            ── 每个会话一张卡，按 sessionId 切换               │
│  │    └─ ChatSessionPanel (1,329) ── 一个会话的全部状态                              │
│  │         ├─ ChatTranscript (102) ── 门面：经典/新渲染二选一                         │
│  │         │    ├─ ChatView (736) ── 新：块渲染 + 行卸载 + 回到底部                  │
│  │         │    └─ ClassicTranscript (514) ── 旧：JEditorPane + MarkdownRenderer     │
│  │         ├─ TodoStrip (279)     ── 顶部待办清单                                     │
│  │         ├─ StatusRow (161)     ── 底部状态行 + 呼吸点                              │
│  │         └─ ChatComposer (993)  ── 输入框 + 附件 + 模型下拉 + 发送/停止             │
│  └─ PickerPopup (573)             ── 历史/记忆共用的搜索弹层                          │
└─────────────────────────────────────────────────────────────────────────────────────┘

渲染栈（token 落地的路径）：
  Core ── chat/token ──▶ ChatSessionPanel.onCoreMessage (613)
       ──▶ ChatTranscript.appendAssistantToken (60)
       ──▶ ChatView.appendAssistantToken (141)  [40ms 合流]
       ──▶ AgentMessageView.setMarkdown (54)
       ──▶ IncrementalParser.feed (36) → MarkdownParser.parseWithOffsets (44)
       ──▶ BlockPanel.setBlocks (33) → BlockListLayout (137)
       ──▶ BlockView 子类.paintBlock (MeasuredComponent.paintComponent 47)
       ──▶ InlineLayout.paint (124) → java.awt.font.TextLayout
```

### 0.3 三条渲染线，为什么并存

| 线路 | 入口 | 实现 | 状态 |
| --- | --- | --- | --- |
| 新块渲染 | `ChatView` | 自绘 `BlockView` 家族 + `InlineLayout`（Java2D `TextLayout`） | 默认 |
| 经典回退 | `ClassicTranscript` | `JEditorPane` 渲染 `MarkdownRenderer.toHtml` 生成的 HTML 3.2 | `-Dagent.ui.classic=true` 或 `agent.ui.renderer=classic` 时启用，计划 3.2.0 删除 |
| 行内补全 | `AgentInlineCompletionProvider` | Kotlin 内联提示，走 `InlineCompletionClient` | 与对话区无耦合 |

`ChatTranscript`（102 行）只做一件事：把 `TranscriptView` 的 13 个方法转发给当前选中的实现。
它不是抽象工厂，而是一个 `final` 字段 + 转发，理由见 §5.1。

### 0.4 记号约定

- 📌 设计决策：为什么这样做，以及不做会怎样。
- ⚠️ 陷阱：读代码时容易误解、或代码里真实存在的缺陷（本卷会把已确认的缺陷标出来）。
- 🔁 跨文件契约：这一处的字符串/字段/行为必须与另一处一致，改一处必须改另一处。
- 代码块标注的 `// 行号区间` 一律指**当前文件**的行号，`1-based`、闭区间。

### 0.5 本卷不覆盖的东西

- `src/test` 下的两个测试（`MarkdownParserTest` 149 行、`UiIconsResourceTest` 35 行）→ 卷 09。
- 工具实现与权限（`agent/tools`，11 文件 1,238 行）→ 卷 06；`ToolDispatcher` 在这里只是被调用。
- RPC 连接与 Core 生命周期（`agent/rpc`，4 文件 694 行）→ 卷 03。
- Core 侧如何产生 `chat/token` 等事件 → 卷 04。

---

## §1 装配与容器：`AgentToolWindowFactory` + `AgentChatPanel`

### 1.1 `AgentToolWindowFactory`（22 行）

整个工具窗口的插头只有三行有效代码：

```java
// AgentToolWindowFactory.java:13-21
public final class AgentToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AgentChatPanel panel = new AgentChatPanel(project);
        Disposer.register(project, panel);
        toolWindow.getContentManager().addContent(
                ContentFactory.getInstance().createContent(panel, "Agent", false));
    }
}
```

三点值得记住：

1. **`Disposer.register(project, panel)`**：`AgentChatPanel` 实现 `Disposable`，随项目关闭释放。
   这是 IDEA 里唯一可靠的「进程退出前一定被调用」的钩子之一，`AgentChatPanel.dispose()`（996）
   在这里被间接调用。
2. **`createContent(panel, "Agent", false)`** 的第三个参数 `isLockable = false`：内容页不可被锁定，
   避免用户把 Agent 面板拆成独立可锁定视图后与单例语义冲突。
3. 没有 `ToolWindowFactory.init()`、没有 `shouldBeAvailable()`：插件认为任何项目都该有 Agent 面板。
   📌 好处是「打开即用」，代价是无法按项目类型隐藏。

### 1.2 `AgentChatPanel` 的字段：五个集合各管一件事

```java
// AgentChatPanel.java:56-93
56  public final class AgentChatPanel extends SimpleToolWindowPanel implements Disposable {
58      private static final Logger LOG = Logger.getInstance(AgentChatPanel.class);
67      private final SessionTabsBar tabsBar = new SessionTabsBar();
68      private final JPanel cards = new JPanel(new CardLayout());
70      private final Map<String, ChatSessionPanel> sessions = new LinkedHashMap<>();
73      private final Map<String, ChatSessionPanel> byTask = new ConcurrentHashMap<>();
75      private final ChatSessionPanel.Owner sessionOwner = new SessionOwner();
81      private List<HistoryEntry> historyEntries = new ArrayList<>();
85      private List<PendingMemory> memoryItems = new ArrayList<>();
88      private final Map<String, String> memoryWarnings = new HashMap<>();
92      private final Set<Integer> usedNumbers = new HashSet<>();
93      private final Map<String, Integer> sessionNumbers = new HashMap<>();
```

逐个说明「为什么是这个类型」：

- **`sessions` 用 `LinkedHashMap`（70）**：标签的**视觉顺序等于插入顺序**。用户看到的第 n 个标签，
  就是 `values()` 迭代出来的第 n 个元素，`sessionAt(index)` 直接按下标取。换成 `HashMap` 会出现
  「点第 2 个标签切到第 3 个会话」这类不可复现的 bug。
- **`byTask` 用 `ConcurrentHashMap`（73）**：写发生在 EDT（`onTaskBound`），读发生在 Core 事件
  分发线程（`onCoreMessage`，871）。跨线程读写必须用并发容器，否则可能读到 `null` 或死循环
  （`HashMap` 并发扩容的经典问题）。
- **`usedNumbers` + `sessionNumbers`（92-93）**：标签显示的是「会话 1 / 会话 2」，不是 UUID。
  两个集合分工：`sessionNumbers` 是 `sessionId → 编号`（关闭时查），`usedNumbers` 是编号占用表
  （新建时找空号）。为什么不只留一个？因为 `nextSessionNumber()` 需要「最小可用正整数」，
  只有占用集合能 O(n) 找到空洞。
- **`memoryWarnings`（88）**：`memoryId → 提示文案`。这是「Core 返回 duplicate 但 UI 不删行」
  的实现载体，详见 §1.9。
- **`sessionOwner`（75）**：`ChatSessionPanel` 不认识 `AgentChatPanel`，只认识 `Owner` 接口
  （`ChatSessionPanel.java:44`）。反向依赖被接口切断，`ChatSessionPanel` 才能被单独测试。

### 1.3 构造与 `initUI`（95-114）

```java
// AgentChatPanel.java:105-113（示意，逐行见源码）
private void initUI() {
    SimpleToolWindowPanel 的 body = BorderLayout：
      NORTH → tabsBar
      CENTER → cards
    setContent(...)
}
```

`SimpleToolWindowPanel` 自带工具栏与内容的组合能力；这里用 `BorderLayout` 手搭，是因为标签条要
常驻、`cards` 要占满剩余空间，用不到 `SimpleToolWindowPanel` 的工具栏槽位。

构造顺序很重要：`cards` 先于 `openSession()` 可用，`tabsBar.setListener(...)`（131）必须先挂，
否则首次 `rebuildTabs` 时点击回调是空的。

### 1.4 标签生命周期：`openSession` / `nextSessionNumber` / `selectSession` / `closeSession`

**`openSession`（161-179）**：

```java
// 语义等价代码
if (sessions.size() >= MAX_SESSIONS) {          // MAX_SESSIONS = 8（62）
    setStatus(AgentBundle.message("status.tooManySessions"));
    return;
}
String id = projectId + "#" + UUID.randomUUID().toString().substring(0, 8);  // 170-171
int number = nextSessionNumber();
ChatSessionPanel session = new ChatSessionPanel(project, id, AgentBundle.message("tab.session", number), sessionOwner);
sessionNumbers.put(id, number);
usedNumbers.add(number);
sessions.put(id, session);
cards.add(session, id);
selectSession(session);
rebuildTabs();
```

- **`sessionId = projectId#uuid8`（170-171）**：带上 `projectId` 是为了让 Core 侧的多项目会话
  天然分区；`uuid8` 只取前 8 位是够用的（8 位十六进制 = 4×10⁹，单项目并发 ≤ 8 个标签），
  同时让日志可读。
- **`MAX_SESSIONS = 8`（62）**：不是技术限制，是「超过 8 个标签就开始互相挤压」的产品判断。
  超限时不是静默失败，而是走 `setStatus` 提示（986）。

**`nextSessionNumber`（181-188）**：从 1 开始递增，找到第一个不在 `usedNumbers` 里的数。
关闭「会话 2」后再新建，会复用 2 而不是变成「会话 9」。📌 这避免了用户对「编号只增不减」的困惑，
代价是编号不再唯一标识会话（`sessionId` 才是）。

**`selectSession`（190-198）**：`cards.show(cards, session.sessionId())` + `rebuildTabs()` +
`session.focusInput()`。切标签必须把焦点送回输入框，否则用户按 Enter 什么也不会发生。

**`closeSession`（200-242）**：顺序是**先记后删**——

```java
int slot = indexOf(session);             // ① 记下当前是第几个标签
int number = sessionNumbers.remove(session.sessionId());
session.dispose();                       // ② 释放定时器、给未答复的确认/提问兜底
sessions.remove(session.sessionId());
byTask.values().remove(session);          // ③ 反向索引也要清，否则 byTask 永远指向已 dispose 的对象
usedNumbers.remove(number);               // ④ 归还编号
cards.remove(session);
if (sessions.isEmpty()) openSession();    // ⑤ 永远至少留一个会话
else selectSession(sessionAt(Math.min(slot, sessions.size() - 1)));  // ⑥ 后继标签补位
rebuildTabs();
```

⚠️ 第 ③ 步容易被漏。`byTask` 是 `taskId → session`，若只 `sessions.remove` 而不清 `byTask`，
关闭标签后 Core 再发一条 `chat/token`，`onCoreMessage` 会路由到一个已 `dispose` 的
`ChatSessionPanel`，在已移除的组件上 `repaint`——通常不崩，但会白白创建行对象，且 `currentTaskId`
永远不会被清，导致「这个 taskId 永久占用一个 map 槽」。代码在 200-242 里显式做了
`byTask.values().remove(session)`，这是正确写法。

**`rebuildTabs`（244-256）**：每帧重建 `List<SessionTabsBar.Tab>`，把一个 `Tab` 的标题与
`running` 状态从会话里抽出来。这是典型的「UI 状态由数据推导」：

```java
List<Tab> tabs = new ArrayList<>(sessions.size());
int selected = 0, i = 0;
for (ChatSessionPanel s : sessions.values()) {
    tabs.add(new Tab(s.displayTitle(), s.isBusy()));   // running → 标签上的呼吸点
    if (s == current) selected = i;
    i++;
}
tabsBar.setTabs(tabs, selected);
```

📌 「每帧重建」听起来低效，但 8 个对象、每次重建只发生在用户操作之后（开关标签、标题变化、
忙碌态变化），不在渲染循环里。用 diff 反而会引入状态不同步的 bug。

### 1.5 会话内部接口 `Owner`（`SessionOwner`，1009-1036）

`ChatSessionPanel.Owner`（`ChatSessionPanel.java:44-54`）定义五个回调：

| 方法 | 触发点 | 容器侧动作 |
| --- | --- | --- |
| `onTaskBound(session, taskId)` | `chat/start` 回包成功（545-552） | `byTask.put(taskId, session)` |
| `onTaskCleared(session)` | `finishTask()`（1182） | `byTask.remove(...)` |
| `onBusyChanged(session)` | `setBusy(boolean)`（1229） | `rebuildTabs()`（刷新呼吸点） |
| `onTitleChanged(session)` | `updateTitleFrom(text)`（212） | `rebuildTabs()`（刷新标签标题） |
| `openSettings()` | 输入框设置按钮 / 状态行 | 转调 `AgentChatPanel.openSettings()` |

🔁 **`onTaskBound` 必须在第一个 token 之前完成**。`ChatSessionPanel.sendMessage` 的
`.thenAccept` 里先 `currentTaskId = taskId` 再 `owner.onTaskBound(this, taskId)`（549-552），
注释写明「先登记 taskId，容器后续才能把属于本会话的事件路由过来」。但严格说这两步之间存在
极窄的竞态窗口：`chat/start` 的响应与第一条 `chat/token` 可能在同一批消息里到达，
若 token 的处理线程恰好插在赋值与 `byTask.put` 之间，就会命中 `AgentChatPanel.onCoreMessage`
里那条 `LOG.debug("unrouted core event ...")` 兜底分支。实践上几乎不可见（RPC 框架按消息顺序
串行回调），但读代码时要认得这条 debug 日志的含义。

### 1.6 事件路由：`onCoreMessage`（871-921）

这是整个容器的**唯一入口**。Core 的所有通知都从这里进来，再按 `taskId` 分给会话：

```java
// 语义结构
JsonObject params = message.getAsJsonObject("params");
String taskId = params.has("taskId") ? params.get("taskId").getAsString() : null;

if (taskId == null) {
    switch (method) {
        case "core/hello":              // Core 就绪：推设置、启动一次 startCoreAsync
        case "memory/pendingChanged":   // 待确认记忆数量变化 → tabsBar.setPendingMemories(n) + 状态行
        case "session/indexProgress":   // 建索引进度 → 状态行百分比
        default: LOG.debug(...)
    }
    return;
}
ChatSessionPanel target = byTask.get(taskId);
if (target == null) { LOG.debug("unrouted core event {} task={}", method, taskId); return; }
target.onCoreMessage(method, params);
```

两个设计点：

1. **无 `taskId` 的事件只有三种**（`core/hello`、`memory/pendingChanged`、`session/indexProgress`）。
   其余无 `taskId` 的事件一律 debug 掉——这是有意的「宁可丢日志，不可污染某个会话」。
2. **同一个方法名可能在两个层面处理**：`chat/*` 走会话，`memory/*`、`core/*` 走容器。
   改事件名时必须两处一起改（🔁 见 §17.2 的事件表）。

### 1.7 历史会话：`HistoryEntry` + 弹层 + 300ms 防抖

**数据模型 `HistoryEntry`（268-308）** 是 `final` 类而非 record，因为它的构造里要做
「把 Core 返回的 snippet 转成可显示的 HTML」这件有副作用的工作：

```java
// 268-308 关键字段（按用途分组）
id / title / preview          → 行标题与副标题
snippetHtml                   → 命中高亮片段（已被转成 <b>）
sizeBytes / updatedAt         → 右侧 chip（formatSize 807 / formatTime 837）
```

`highlightSnippet(raw)`（322-340）做两件事：
1. 把 Core 给的 `<mark>…</mark>` 换成 `<b>…</b>`（Swing 的 HTML 3.2 与 `JLabel` 都不认 `<mark>`）；
2. **转义其余所有标签**（`escapeHtml` 342-345）。⚠️ 如果不转义，会话标题里出现 `<script>`
   或 `<table>` 就会破坏弹层布局——这是「把不可信文本塞进 HTML 组件」的经典漏洞形态。
`stripTags(html)`（347-353）用于需要纯文本的地方（如 tooltip）。

**`showHistory`（368-390）→ `showHistoryPopup`（392-415）**：

```java
PickerPopup picker = new PickerPopup(UiMetrics.scale(560), "搜索历史会话", "没有匹配的会话",
        PickerPopup.defaultFooter());
picker.setHeader(historyHeaderText(historyEntries, ""));
picker.setRows(historyRows(historyEntries));
picker.setPickHandler(this::openHistory);
picker.setSearchHandler(this::runHistorySearch);
JBPopup popup = picker.createPopup();
historyPicker = picker;                 // 字段：用于竞态校验
popup.showUnderneathOf(anchor);
```

`historyRows`（417-440）给每行挂两个动作：**打开**与**删除**（`RowAction(label, danger=true, handler)`）。
`PickerPopup` 的行模型见 §12。

**`runHistorySearch`（453-491）** 是本文件里最值得学的 40 行：

```java
453  private void runHistorySearch(String query) {
       // ① 300ms 防抖：每次击键重设定时器，只有停顿够久才发请求
       // ② 请求发出前记下「这是哪个 picker、哪个 query」
       // ③ 回包时三重校验：
482      if (historyPicker != picker || !popup.isVisible() || !query.equals(picker.query())) return;
       // ④ 通过校验才 setRows + setHeader
     }
```

- **为什么要 300ms**：`history/search` 会走一次 Core 的全文索引查询，逐键调用会把 Core 打满。
- **为什么回包要校验**：用户可能在请求飞行期间（a）关掉弹层、（b）重新打开一个新弹层、
  （c）继续输入改变 query。三者任一发生，旧结果都不该覆盖新界面。三个条件分别对应这三种情况。
  📌 这是「异步搜索 + 可变 UI」的标准正确写法，比在请求里加序号更直观。

**`openHistory`（721-749）**：`core.history/load` → `parseHistoryMessages`（817-835）→
`addHistoryTab`（751-765）。注意它是**新建一个标签**再把历史消息灌进去（`applyHistory`），
而不是复用当前会话——因为当前会话可能正在跑任务，历史会话必须是「只读回放」语义。

**`deleteHistory`（767-805）**：先弹确认（删除不可撤销），确认后调 `core.history/delete`，
成功后从 `historyEntries` 移除并刷新行。⚠️ 它删的是**磁盘上的会话记录**，不是当前标签。

### 1.8 待确认记忆：`PendingMemory` 与「duplicate 不删行」

`PendingMemory`（506-532）对应 Core 的 `memory/pending` 列表项，字段大致为
`id / kind / text / source / createdAt`。其中 `sourceLabel()`（527-531）把来源枚举翻成中文：

```java
// 527-531
String sourceLabel() {
    return "correction".equals(source) ? "纠正" : "自动提取";
}
```

⚠️ **这是一处真实的契约错位**：Core 侧写入的来源值是 `user_correction`（见卷 07 §记忆写入），
而这里只判 `"correction"`。两者不一致的后果不是崩溃，而是**纠正类记忆在 UI 上被显示成「自动提取」**。
读到这里应当记住：跨语言的枚举字符串是这套代码里最脆弱的边界（§17.2 有清单）。

`resolveMemory`（666-709）是本文件里逻辑最密的一段：

```java
666  private void resolveMemory(PendingMemory item, boolean approve, String content) {
       // ① 组 payload：{memoryId, approve, content?}
       // ② core 调用 memory/resolve
       // ③ 若 result.status == "duplicate"：
697        memoryWarnings.put(item.id, AgentBundle.message("memory.duplicate.warning"));
698        refreshMemoryRows();     // ★ 保留行，只加黄色提示
           return;
       // ④ 其它情况：memoryItems.remove(item) + refreshMemoryRows()
     }
```

📌 **为什么 duplicate 要保留行**：用户点「批准」时，Core 可能发现「已存在同义记忆」而拒绝写入。
如果 UI 直接把行删掉，用户会以为写成功了；保留行 + 黄字提示「与已有记忆重复，未写入」，
用户才有机会改写或放弃。这是一个「失败必须可见」的正面例子。

`editMemory`（655-664）：收集新内容（用户取消则原样返回），确认后调用
`resolveMemory(item, true, newContent)`——**编辑即批准**，不额外问一次。

`memoryRows`（595-634）里 `warning` 字段的取值就是 `memoryWarnings.get(item.id)`，
`PickerPopup` 会把非空 `warning` 渲染成行内的一行黄字（§12.3）。

### 1.9 设置：只有「三路径变化」才重启 Core

```java
// AgentChatPanel.java:923-956
923  private void openSettings() {
       // ① 快照三个路径：可执行文件 / Core 主目录 / Python 解释器
       String exe0 = state.getCoreExecutable(); home0 = ...; py0 = ...;
       // ② 交给 IDEA 的设置对话框（配置页 id：com.workreport.settings）
       if (!showSettingsDialog(project, "com.workreport.settings")) return;
       // ③ 重新读取
       boolean pathChanged = !exe0.equals(state.getCoreExecutable()) || !home0.equals(...) || !py0.equals(...);
       if (pathChanged) {
           if (任一标签 busy) { setStatus("有任务在跑，稍后再改 Core 路径"); return; }
           coreService.restart();
       } else {
           coreService.pushSettings();     // 只推设置，不重启
       }
     }
```

三点：

1. **为什么只比三条路径**：模型名、MCP 开关、超时等都能通过 `settings/push` 热更新（§2.9 的
   `pushSettings(conn)` 每次发送前还会再推一次）；而「Core 在哪、用哪个 Python 跑」只能重启进程。
   把「要不要重启」的判据收敛到三个字符串，比「任何设置变化都重启」体验好一个量级。
2. **busy 时拒绝重启**：重启会杀掉正在跑的会话。UI 的选择是**明确拒绝并提示**，而不是排队
   （排队需要在 Core 空闲时回调，复杂度不值）。
3. 设置对话框返回 `false`（用户取消）时直接返回，不产生任何副作用。

### 1.10 其余入口

| 方法 | 行 | 说明 |
| --- | --- | --- |
| `find(Project)` | 962-975 | 从 `ToolWindowManager` 取 Agent 工具窗口内容，返回第一个 `AgentChatPanel`。供 `AnAction`、行内补全等「拿不到面板引用」的调用方使用 |
| `sendPresetMessage(text)` | 977-984 | 外部（如空状态卡片、行内补全）向当前会话投喂一句话：`session.composer.setText(text)` + `triggerSend()` |
| `setStatus(text)` | 986-994 | 转发给当前会话的状态行（`StatusRow`） |
| `dispose()` | 996-1007 | 遍历 `sessions` 逐个 `dispose()`，再 `tabsBar` 解绑。必须幂等 |
| `startCoreAsync()` | 846-858 | 后台线程 `coreService.ensureStarted()`，失败时不弹窗，只 `setStatus` + 日志 |

📌 `startCoreAsync` 的「失败不弹窗」是刻意的：Core 未就绪是常见状态（首次安装、正在下载），
弹模态框会阻断整个 IDE。状态行足够表达。

---

## §2 会话：`ChatSessionPanel`（1,329 行）

一个 `ChatSessionPanel` = 一个标签 = 一条独立的 Core 会话（`sessionId`）。它持有：
对话区门面、待办条、状态行、输入框，以及**两张待答复表的引用**（确认卡、提问卡）。
它不认识容器，只通过 `Owner` 回调；它也不认识 Core，只通过 `AgentCoreService` 发 RPC。

### 2.1 字段与装配

```java
// ChatSessionPanel.java:45-101（按用途重排）
45  public final class ChatSessionPanel extends JPanel {
51      public interface Owner { onTaskBound / onTaskCleared / onBusyChanged / onTitleChanged / openSettings }
63      private static final Logger LOG = ...;
72      private final ChatTranscript transcript = new ChatTranscript();
73      private final TodoStrip todoStrip = new TodoStrip();
74      private final StatusRow statusRow = new StatusRow();
81      private final Map<String, ToolCallCard> pendingConfirms = new HashMap<>();
84      private final Map<String, AskCard> pendingAsks = new HashMap<>();
```

其余字段（构造里赋值）按职责：

| 字段 | 作用 | 为什么必须是字段 |
| --- | --- | --- |
| `project` / `sessionId` / `owner` | 身份与回调 | 生命周期内不变 |
| `coreService` | RPC 门面 | 每次发送前要 `connectionOrNull()`，连接可能重连 |
| `toolDispatcher` | 处理 `tool/invoke` | 跨会话串行写盘（§6 卷） |
| `composer` (`ChatComposer`) | 输入 | 需要被外部 `setText` / `reset` / `setBusy` |
| `currentTaskId` | 当前任务 | **`null` 即空闲**，是所有「能否再发」判断的依据 |
| `turnStartMs` / `turnIn` / `turnOut` | 本轮计时与用量 | 每次发送清零，`chat/finish` 后显示 |
| `busy` | 忙碌态 | 与 `currentTaskId != null` 略有重叠，但 busy 还覆盖「连接中」 |
| `metaLabel` | 右上角「耗时 · ↑1.2k ↓800」 | `refreshMeta` 更新 |

`initUI()`（117-156）的布局决策：

```
┌──────────────────────────────────────────┐
│ TodoStrip（有待办时才可见）               │  ← 顶部，随 chat/todos 频繁更新
├──────────────────────────────────────────┤
│ OnePixelSplitter(true, 0.70f)            │  ← 竖向分割，对话区 70%
│   上：ChatTranscript                     │
│   下：ChatComposer（MIN 4 行 / MAX 14 行）│
├──────────────────────────────────────────┤
│ StatusRow（一行状态 + 呼吸点）            │  ← 底部，常驻
└──────────────────────────────────────────┘
```

📌 **用 `OnePixelSplitter` 而不是固定高度**：输入框会长到 14 行（§3.8），对话区必须让位；
但让位多少由用户拖拽记忆，不由代码猜。`0.70f` 只是首帧比例。

### 2.2 一次发送的完整时序（498-567）

这是本卷最重要的一段代码，逐段拆开：

```java
498  private void sendMessage(String text, List<Path> attachments) {
499      if (currentTaskId != null) {
500          transcript.addNotice("当前会话还有任务在跑，请等它结束或点「停止」。");
501          return;
502      }
```

**① 入口守卫**：以 `currentTaskId` 而非 `busy` 判断「正在跑」。`busy` 还包含「正在连接 Core」
这类没有 taskId 的阶段，允许在连接阶段再次回车会导致两个 `chat/start` 抢同一个会话。

```java
503-509   text 归一化：null → ""、trim、空文本且无附件 → 直接返回
510-514   projectRoot = project.getBasePath()；names = 每个附件的显示名（ChatAttachment.displayName）
```

**② 先上屏，再发送**（515）：

```java
515  transcript.addUserMessage(text.isEmpty() ? "（仅附件）" : text, names);
516  composer.reset();
517  updateTitleFrom(text);            // 首句成为标签标题 + owner.onTitleChanged
520  turnStartMs = System.currentTimeMillis();
521-525  turnElapsedMs = 0; turnIn = 0; turnOut = 0; toolTraces.clear(); activeSteps = 0;
526  refreshMeta();                    // chip 归零
527  setBusy(true);
528  setStatus(AgentBundle.message("status.thinking"));
```

📌 **顺序不可调换**：先上屏用户消息 → 再 `composer.reset()`，否则用户会看到自己刚打的字先消失、
半秒后气泡才出现。`updateTitleFrom` 必须在 `reset` 之后（它读的是入参 text，不是输入框）。

**③ 后台线程做三件事**（531-545）：

```java
531  ApplicationManager.getApplication().executeOnPooledThread(() -> {
533      ensureStarted();                            // 阻塞直到 Core 进程 + 连接可用
534      JsonRpcConnection conn = coreService.connectionOrNull();
535-537  if (conn == null) throw new IOException("Agent Core 未就绪");
540      coreService.pushSettings(conn);             // ★ 每次发送都重推一次设置
541-544  params = { sessionId, message: ChatAttachment.buildPrompt(...), contextSnapshot: ... }
545      conn.request("chat/start", params).thenAccept(...)
     });
```

- **为什么在池化线程里 `ensureStarted`**：首次调用要启动 Python 子进程并等握手，可能几秒。
  放 EDT 会整窗卡死。
- **为什么每次都 `pushSettings`**：用户在设置页改了模型/MCP 后，不重启 Core 也要生效。
  代价是每次发送多一个 RPC，换来「改完即生效」。（`openSettings` §1.9 里那条
  「三路径才重启」正是这条的配套设计。）
- **`message` 不是纯文本**：`ChatAttachment.buildPrompt` 会把附件正文以代码围栏拼进消息体，
  并把相对路径写进提示（§13.1）。`contextSnapshot` 只带路径，不带正文。

**④ 回包处理**（545-565）：

```java
545  conn.request("chat/start", params).thenAccept(response -> {
546      ok = response.has("ok") && response.get("ok").getAsBoolean();
547-552  if (ok) { taskId = response.result.taskId; currentTaskId = taskId; owner.onTaskBound(this, taskId); }
553-555  else onChatErrorJson(response);
556      }).exceptionally(ex -> {
557          transcript.addErrorMessage("[错误] " + ex.getMessage());
558          finishTask();
     });
```

⚠️ 注意 `exceptionally` 里**没有** `setStatus(error)`，只有 `finishTask()`。而 `chat/error`
事件分支（744-754）两者都有。轻微不一致：`chat/start` 本身失败时状态行会停在
`status.ready`（由 `finishTask` 复位），而不是 `status.error`。不影响功能，但读日志时会疑惑。

### 2.3 上下文快照 `buildContextSnapshot`（586-607）

```java
586  private Map<String, Object> buildContextSnapshot(List<Path> attachments) {
587-591  snapshot["project_root"] = project.getBasePath()
592-598  editor = FileEditorManager.getSelectedTextEditor()
         → snapshot["current_file"] = file.getPath()
599-605  若 attachments 非空：snapshot["open_files"] = [路径...]
606      return snapshot;
}
```

三个字段的语义：

| 字段 | 含义 | Core 侧用途 |
| --- | --- | --- |
| `project_root` | 项目根 | 工具的相对路径基准、写入白名单 |
| `current_file` | 当前编辑器文件 | 让模型「看到你在看哪个文件」 |
| `open_files` | 本轮附件路径 | 与 `message` 里的围栏内容对应，供工具直读 |

⚠️ **`open_files` 实际是「附件列表」而不是「打开的标签页」**。字段名与语义有偏差——
如果 Core 侧把它当作 `open_files` 展示给模型（例如「用户当前打开了这些文件」），
就会误导模型认为这些文件在编辑器里开着。这是命名遗留，改动需同步 Core（🔁 §17.2）。

### 2.4 停止 `onStop`（573-584）

```java
575  public void onStop() {
576-580  taskId = currentTaskId; conn = coreService.connectionOrNull();
         if (conn == null || taskId == null) return;
581-583  conn.notify("chat/cancel", { taskId });
}
```

📌 **用 `notify` 而不是 `request`**：取消是「尽力而为」，不需要回包。Core 收到后会在
合适的检查点抛出取消，并以 `chat/error`（code=cancelled）或 `chat/finish` 收尾。
UI 侧**不**在 `onStop` 里立刻 `finishTask()`——否则真实收尾事件到达时会再跑一次，
把还没上屏的 token 丢掉。

### 2.5 14 路事件分发 `onCoreMessage`（613-758）

入口由容器按 `taskId` 路由（§1.6）。第一层是**跨线程快速路径**（619-630）：读线程上只把流式两类
（`chat/token`、`chat/reasoning`）直接交给对话区——它在内部合批，不逐条投递 EDT 任务；其余全部
`invokeLater` 切到 EDT，再进 `switch (method)`：

| # | method | 行 | 行为 |
| --- | --- | --- | --- |
| 1 | `chat/token` | 632-636 | `params.delta` → `transcript.appendAssistantToken(delta)`（空串也照发，下游会忽略） |
| 2 | `chat/reasoning` | 637-641 | `params.delta` → `transcript.appendReasoningToken(delta)`；思考单独折成一行（§10.9） |
| 3 | `tool/invoke` | 642-650 | **不走 UI**：`toolDispatcher.onToolInvoke(params, conn, displayTitle(), transcript::addToolLine)` |
| 4 | `chat/toolStart` | 651-657 | `emitToolStart` → `read_file · src/main/App.java`（工具名 + 最有辨识度的一个参数） |
| 5 | `chat/toolEnd` | 658-663 | `emitToolEnd` → `read_file · done · 0.4s · 摘要`（`done` / `failed`，摘要截 120） |
| 6 | `chat/subagent` | 664-702 | `agent code-reviewer · qwen3 [review] · start`；`end`：`· done · 12.4s · summary` |
| 7 | `chat/artifact` | 703-713 | 团队黑板投稿：一行 `ui.team.artifact.posted` 提示 + 点亮输入区的「交付物」入口 |
| 8 | `chat/notice` | 714-720 | 非空才 `addNotice(text)`（INFO 语气） |
| 9 | `chat/todos` | 721-723 | `todoStrip.setItems(parseTodos(params))` |
| 10 | `agent/confirm` | 724-726 | `handleConfirm(params)`（§2.6） |
| 11 | `ask/user` | 727-729 | `handleAsk(params)`（§2.7） |
| 12 | `ask/withdraw` | 730-737 | `pendingAsks.remove(askId).withdraw()`（卡片留下、标记已取消） |
| 13 | `chat/finish` | 738-743 | `finishAssistant()` → `appendUsage` → `finishTask()` → 状态行 `status.ready` |
| 14 | `chat/error` | 744-754 | 取 `error.code/message` → `[错误:CODE] msg` → `finishTask()` → `status.error` |

三条容易忽略的细节：

1. **`tool/invoke` 与 `chat/toolStart` 是两条不同的通道**：前者是「Core 请求 IDE 执行工具」
   （反向调用，要回包），后者只是「通知 UI 某个工具开始了」（单向）。`tool/invoke` 分支
   完全不上屏，上屏由 `toolDispatcher` 通过 `transcript::addToolLine` 回调完成。
2. **工具行是英文开发者日志，状态走参数**：`emitToolStart/emitToolEnd` 只拼 `tool · arg`、
   `tool · done · 0.4s · summary` 这类英文串；「还在跑 / 失败」由
   `addToolLine(text, running, failed)` 的两个布尔参数带下去（§6.7），不再靠 `contains("执行中")`
   嗅探。并行调用用 `activeSteps` 计数，全部收尾才熄灭 running；`[错误:CODE]` 仍是中文，
   因为错误文本来自 Core。
3. **`default:` 静默**（755-756）。Core 新增事件而 UI 未升级时不会报错，只是不显示——3.3.0 的
   `chat/reasoning` 正是靠这条让 3.2 的 Shell 静默忽略（§17.1）。

### 2.6 确认：`handleConfirm` / `askConfirmInDialog` / `sendConfirmResult`

```java
792  private void handleConfirm(JsonObject params) {
         confirmId / kind / server / tool / args ← params
         if ("preapprove".equals(kind)) { handlePreapprove(confirmId, params); return; }   // 团队预审卡（§2.12）
         invokeLater(() -> {
             ToolCallCard card = new ToolCallCard(server, tool, detail);
             card.setDecisionHandler(approved -> sendConfirmResult(confirmId, approved));
             if (transcript.isShowing() && transcript.addCard(card)) {     // 卡片挂在可见的对话流里
                 pendingConfirms.put(confirmId, card);
                 return;
             }
             askConfirmInDialog(confirmId, server, tool, detail);            // 兜底才弹框
         });
     }
```

- **「卡片优先，对话框兜底」**：卡片留在对话流里，用户能看到「这次调用是为什么发生的」；
  经典渲染或会话不在屏幕上（`transcript.isShowing()` 为假）时挂不上卡（`TranscriptView.addCard`
  默认返回 `false`，§5.2），于是退回模态框。
- **`pendingConfirms` 是「未答复集合」**：会话 `dispose` 时逐个 `decide(false)`（1312-1328），
  保证 Core 不会永久等一个已经没人能点的对话框。
- `askConfirmInDialog`（893-900）用 IDEA 的 `Messages.showYesNoDialog` 语义（带 detail 的
  多行提示），**Yes = 允许**、No/关闭 = 拒绝。

### 2.7 提问：`handleAsk` / `buildAskCard` / `askInDialog`

```java
922  private void handleAsk(JsonObject params) {
         askId ← params
         if (askId.isEmpty() || questions 不是数组) return;
         invokeLater(() -> {
             AskCard card = buildAskCard(params);
             if (card.isEmpty()) { sendAskResult(askId, /*skipped*/); return; }   // ★ 空题直接跳过
             card.setAnswerHandler(answer -> { pendingAsks.remove(askId); sendAskResult(askId, answer); });
             if (transcript.isShowing() && transcript.addCard(card)) { pendingAsks.put(askId, card); return; }
             askInDialog(askId, params.getAsJsonArray("questions"));
         });
     }
```

- **空题直接 skipped**（922-947）：Core 偶尔会发出「只有 header 没有正文」的问题
  （模型生成了空问题）。如果不跳过，用户会看到一张只有标题、按 Enter 也无意义的卡片，
  而 Core 会一直等到超时。这一句是全文件最省事的健壮性补丁。
- **`buildAskCard`（949-979）**：把 `params.questions[]` 映射成 `question(header, text, labels,
  descriptions, multi, allowCustom)`。`labels` 与 `descriptions` 等长对齐；不足处留空串
  （`AskCard.ensureLayout` 213 起，描述兜底在 233 的 `i < q.descriptions.size() ? ... : ""`）。
- **`askInDialog`（1085-1148）** 的三条降级规则：
  1. **多选降级为单选**：Swing `Messages` 系列没有原生多选对话框，用 `showChooseDialog`
     只能选一个；降级后提交的 `picked` 里只有一个元素。
  2. **「其它」用文本输入对话框**：用户选「其它」后再弹一次输入。
  3. **取消 = skipped**（不是「拒绝」）：语义是「按你的假设继续」，而不是「别做了」。

### 2.8 待办与用量

**`parseTodos`（763-790）** 是 `static` 的，因为两处都用：`chat/todos` 事件与
`session/get` 的冷启动回填（历史会话重开时也要恢复待办）。它接受 `container.todos[]`，
每项取 `content` + `status`（`pending` / `in_progress` / `completed`）。
📌 `TodoStrip` 自己不认识 JSON——解析与渲染分开，`TodoStrip` 才能被单测/预览。

**`appendUsage`（1201-1218）**：

```java
1203  private void appendUsage(JsonObject params) {
          params.usageTotal（{in, out}）缺失或非对象 → 直接返回
1210-1212  turnIn = in；turnOut = out；refreshMeta()
1213-1217  两者都非零时追加一行「本轮用量：X 输入 / Y 输出」
}
```

3.3.0 起 Core 在 `chat/finish` 里带的是 **`usageTotal`（整轮聚合后的 in/out）**，不再是单次调用的
`usage`；所以面板用 `=` 覆盖而不是累加——分段用量（子代理、多轮工具调用）已经由 Core 加好。
`formatTokens`（1220-1227）把 ≥1000 的值压成 `1.2k`，1 位小数。

**`refreshMeta`（166-186）**：拼「耗时 1.2s · ↑1.2k ↓0.8k」。没有用量时只显示耗时；
没有开始时间时显示空串。`setMeta` 转发给 `AgentMessageView`（§10.4），所以元信息挂在**卡片头部**，
不是状态行。

### 2.9 模型与自动接受

| 方法 | 行 | 行为 |
| --- | --- | --- |
| `refreshModelCombo` | 278-290 | 从设置读模型列表 → `composer.setModels(names, active)` |
| `switchModel` | 291-300 | 写回 settings state → `coreService.pushSettings()`（不重启） |
| `setAutoAccept` | 301-311 | 同上：写 state + push |

📌 这两个方法都**不重建 Core**，与 §1.9 的「三路径才重启」一致。

### 2.10 输入框高度协商 `applyComposerHeight`（1246-1266）

```java
1246  private void applyComposerHeight(int desired, boolean force) {
          int total = conversationSplit.getHeight();              // 分隔条总高（<=0 直接返回）
          int current = composer.getHeight();                     // 当前像素高度
          if (!force && !first && desired <= current + 4) return;  // ★ 只长不缩（除非 force / 首帧）
          double share = Math.max(0.12, Math.min(0.80, (double) desired / total));
          invokeLater(() -> conversationSplit.setProportion((float) (1.0 - share)));
      }
```

四条规则合起来的意思是：

1. **增长立即跟随**（输入到第 5 行就长高）；
2. **收缩滞后**（删字不立刻缩，避免打字时窗口上下抖动）；
3. **`force` 用于程序化重置**（发送后 `reset()` → 回到 4 行）；
4. **`+4` 的阈值**：小于 4px 的变化不值得一次布局；比例还夹在 `0.12~0.80`，防止拖到只剩一行。

⚠️ 这里的 `desired` 来自 `ChatComposer.refreshHeight` 的 `HeightSink`（§3.8），而
`ChatComposer.chromeHeight()` 里有一个硬编码 `JBUI.scale(18)`（602-610）。改输入框内边距时
必须同时改这两处，否则会出现「永远差 18px、滚动条闪一下」的现象。

### 2.11 释放 `dispose`（1312-1328）

```java
1312  public void dispose() {
          onStop(); metaTicker.stop();                          // 先取消任务、停计时器
          for (ToolCallCard c : pendingConfirms.values()) c.decide(false);   // 一律拒绝
          pendingConfirms.clear();
          for (AskCard a : pendingAsks.values()) a.skip();                   // 一律跳过
          pendingAsks.clear();
          transcript.dispose();                                              // 停 flushTimer
      }
```

📌 **「关闭标签」必须给 Core 一个答复**。否则 Core 的那个 `await` 会占住一个并发槽，
最终因超时失败，并在日志里留下「无人应答」的噪音。拒绝/跳过是最保守的选择：
拒绝不会产生副作用，跳过会让模型继续而不是卡住。

`ComposerListener`（1268-1310）只是把 `ChatComposer` 的八个回调转成方法调用：
`onSend → sendMessage`、`onStop → onStop`、`onOpenSettings → owner.openSettings()`、
`onModelChanged → switchModel`、`onAutoAcceptChanged → setAutoAccept`、
`onTeamChanged → switchTeam`、`onTeamRequested → requestTeams(true)`、
`onArtifactsRequested → showArtifacts`。

### 2.12 团队：下拉、预审卡与「交付物」抽屉

3.3.0 把团队开关做成了**配置而非 RPC**，几个新方法各自只做一件事：

| 方法 | 行 | 行为 |
| --- | --- | --- |
| `refreshTeamButton` | 312-326 | `team/list`（异步）→ `applyTeams`；Core 未启动则直接显示「不启用」 |
| `requestTeams` | 327-347 | 先 `ensureStarted` 再 `team/list`（`get(8, SECONDS)`）；`thenShowMenu` 时接着弹下拉 |
| `applyTeams` | 348-382 | 把 `result.teams[]` 映射成 `TeamOption(name, description, members)`，`active=true` 的那项回填给输入区 |
| `switchTeam` | 383-395 | 写 `ReportSettingsState.activeTeam` → `pushSettings()`（与换模型同一条通道） |
| `showArtifacts` | 396-427 | `team/artifacts { sessionId }` → `openArtifactsPopup`（按类别列出条目） |
| `showArtifactDialog` | 456-484 | 只读多行查看窗（计划 / diff / 补丁说明），`760×460` + 等宽字体 |
| `handlePreapprove` | 832-892 | `agent/confirm kind="preapprove"`：计划 + 文件清单 + 首个 diff 拼成正文，一张卡管一个阶段 |

三条值得记住的设计：

1. **切团队不重启 Core**：`active_team` 只是 `settings/update` 里的一个字段（03 卷 §4 的「通知处理 / 配置合并」一段），
   所以与换模型同路；副作用是 Core 未启动时切不了团队（`pushSettings` 需要连接）。
2. **预审卡只豁免「声明范围」**：允许后本阶段的写变为自动，删除与危险命令仍会逐次拦——
   这个判断在 Core 侧（`ToolDispatcher` 每写入前问一次团队），UI 只负责「一张卡一段正文」。
3. **交付物是拉取不是推送**：`chat/artifact` 只点亮入口并打一行提示，全文要用户点开才
   `team/artifacts` 拉一次——这样一阶段几十条投稿也不会反复刷新界面。

> 📌 输入区侧对应 `ChatComposer.setTeams`（258-270）、`showTeamMenu`（278-319）、
> `setArtifactsAvailable`（329-331），以及 `Listener` 里新增的三个回调（61-71）。

---

## §3 输入：`ChatComposer`（993 行）

一个输入框，为什么值得 993 行？因为它同时是：多行自适应文本域、附件管理器、模型选择器、
自动接受开关、发送/停止按钮、拖放目标、焦点动画的宿主。它是整个 UI 里唯一**高度会变**的组件，
所以也是唯一需要和分隔条谈判的组件。

### 3.1 对外接口：两个回调接口

```java
// ChatComposer.java:50-92
50  public interface Listener {                 // 3.3.0 起 8 个回调
        void onSend(String text, List<Path> attachments);
        void onStop();
        void onOpenSettings();
        void onModelChanged(String modelName);
        void onAutoAcceptChanged(boolean autoAccept);
        void onTeamChanged(String teamName);    // 空名 = 关闭团队
        void onTeamRequested();                 // 名册在 Core，面板拉取后回填 setTeams
        void onArtifactsRequested();            // 打开黑板「交付物」
    }
90  public interface HeightSink {
        void onDesiredHeight(int desired, boolean force);
    }
```

📌 **`HeightSink` 单独拆出来**，而不是塞进 `Listener`：会话面板里「处理业务」与「调整布局」
是两件事，混在一个接口里会让 `ComposerListener` 变成 8 个方法的垃圾桶。现在
`applyComposerHeight`（§2.10）只关心高度，`sendMessage` 只关心发送。

### 3.2 组件树

```
ChatComposer (JPanel, BorderLayout)
├─ attachmentStrip (JPanel + WrapLayout)  ← 附件 chip 流式换行，空时高度 0
└─ ComposerBox (JPanel, 自绘边框)         ← 焦点淡入淡出的圆角框
   └─ BorderLayout
      ├─ CENTER : InputArea (JTextArea 子类，自绘 placeholder)
      └─ SOUTH  : toolbar
                  ├─ 左：attach 按钮（回形针图标）→ showAttachMenu
                  ├─ 中：模型下拉（文字 + ChevronIcon）→ showModelMenu
                  ├─ 中：自动接受开关
                  ├─ 右：设置按钮（齿轮）→ listener.onOpenSettings()
                  └─ 右：发送/停止按钮（ArrowIcon ⇄ 停止方块，UiButton）
```

`attachmentStrip` 用自定义 `WrapLayout`（720-757）而不是 `FlowLayout`，原因在 §3.7。

### 3.3 发送判定 `triggerSend`（369-378）

```java
369  public void triggerSend() {
370      if (busy) return;                                               // ① 忙时直接丢弃
373      String text = input.getText().trim();
374      if (text.isEmpty() && attachments.isEmpty()) return;            // ② 空发不动
377      listener.onSend(text, List.copyOf(attachments));                // ③ 传副本
378  }
```

- **① 忙碌时只 `return`，不在这里停止**：同一个按钮的双语义发生在**按钮自己的 Action**（`165-170`）：`if (busy) { listener.onStop(); return; } triggerSend();`。分工很明确：按钮负责「此刻这个键是什么意思」，`triggerSend` 只负责「能不能发」。回车路径（§3.4）也复用 `triggerSend`，因此不会误触停止。
- **② `trim()` 之后再判空**：只输入空格等于没输入。
- **③ 传副本**：`reset()` 会清空 `attachments`，如果传引用，后台线程读到的会是空列表。
  这是「跨线程共享可变集合」的典型防御。

### 3.4 键盘绑定 `installInputBindings`（562-584）

| 按键 | 空闲时 | 忙碌时 |
| --- | --- | --- |
| `Enter` | 发送 | **插入换行**（574-580 `input.replaceSelection("\n")`） |
| `Ctrl+Enter` | 发送 | 插入换行 |
| `Shift+Enter` | 插入换行 | 插入换行 |
| `Esc`（`InputArea` 内） | 无 | 无 |

📌 **忙碌时把 Enter 改成换行**是这套 UI 里最贴合直觉的一个决定：用户想「边等边补充下一句」时
不需要先找 Shift。代价是忙碌时「回车发送」失效，但那时本来也发不出去。

### 3.5 附件：三条入口 + 一条去重

| 入口 | 方法 | 说明 |
| --- | --- | --- |
| 回形针菜单 | `showAttachMenu`（393-402）→ `pickFiles`（404-418） | `FileChooser` 多选 |
| 「加当前文件」 | `addCurrentEditorFile`（420-426） | 取 `FileEditorManager.getSelectedFiles()` 的第一个 |
| 拖放 | `installDropSupport`（430-457） | `TransferHandler.canImport` 只接受 `DataFlavor.javaFileListFlavor` |

```java
459  private void addAttachment(Path path) {
         if (path == null || !Files.isRegularFile(path)) return;
         if (attachments.contains(path)) return;                     // ★ 去重
         attachments.add(path);
         refreshAttachments();
     }
```

`refreshAttachments`（471-484）重建 chip 列表：每个附件一个 `ChipView`（761-858），
显示 `shortName(path)`（488-491，只保留文件名，超过 28 字符截断）与一个关闭叉。

⚠️ **去重按 `Path.equals`**，而 `Path` 在 Windows 上**大小写不敏感但 `equals` 敏感**
（`C:\a\B.txt` 与 `c:\A\b.txt` 不相等）。同一个文件通过「拖放」与「选择文件」两种方式
加入时可能重复。影响很小（会在提示里出现两次围栏），但读代码时要知道这是已知行为。

### 3.6 模型菜单与自动接受

- `setModels(names, active)`（241-250）：保存列表与当前项，刷新 toolbar 上的文字。
  `active` 不在 `names` 里时也照显示——**宁可显示一个未知模型名，也不要静默回退**，
  否则用户会以为设置没生效。
- `showModelMenu`（540-558）：`JBPopupFactory.createPopupMenu` 风格的单选菜单，
  选中后 `listener.onModelChanged(name)`。
- `setAutoAccept(value)`（252-254）+ `onAutoAcceptChanged`：自动接受工具调用的开关。
  🔁 它只是 UI 开关，真正的权限判定在 Core（卷 06），UI 侧不做任何拦截。
- `setTeams`（258-270）/ `showTeamMenu`（278-319）/ `setArtifactsAvailable`（329-331）：
  团队下拉与「交付物」入口，行为见 §2.12。

### 3.7 `WrapLayout`（720-757）：为什么要重写 `preferredLayoutSize`

`FlowLayout` 的 `preferredLayoutSize` 假设**所有子组件排在一行**，只有在容器**已经被布局过**
之后才考虑换行。结果：附件 chip 增加到第二行时，`attachmentStrip` 的首选高度仍然是 1 行，
于是 chip 被裁掉一半，直到下一次布局才恢复。

`WrapLayout` 的做法（725-756）：

```java
726  public Dimension preferredLayoutSize(Container target) {
         synchronized (target.getTreeLock()) {
             int maxWidth = target.getWidth();
             if (maxWidth <= 0 && parent != null) maxWidth = parent.getWidth();   // 首帧：先问父宽
             int limit = maxWidth - insets.left - insets.right;
             int rowWidth = 0, rowHeight = 0, totalHeight = 0;
             for (每个可见子组件) {
                 if (rowWidth > 0 && limit > 0 && rowWidth + hgap + w > limit) {
                     totalHeight += rowHeight + vgap; rowWidth = 0; rowHeight = 0;
                 }
                 rowWidth += (rowWidth == 0 ? 0 : hgap) + w; rowHeight = max(rowHeight, h);
             }
             return new Dimension(maxWidth, totalHeight + rowHeight + insets.top + insets.bottom);
         }
     }
```

📌 关键点是 **「用当前宽度算真实行数」**，而 `FlowLayout` 用的是「历史布局结果」。
代价是该布局不是纯函数（依赖 `target.getWidth()`），所以容器宽度变化时必须 `revalidate`。

### 3.8 高度协商 `refreshHeight`（611-633）

```java
611  private void refreshHeight() {
         int width = input.getWidth();                          // 宽度未知时取 160 或 parent-46
         input.setSize(width, Short.MAX_VALUE);                 // ① 先给足够宽，让换行算法展开
         int visible = clamp(lineHeight()*MIN_ROWS, lineHeight()*MAX_ROWS, content);
         inputScroll.setPreferredSize(new Dimension(10, visible + 4));
         boolean force = collapseAfterSend; collapseAfterSend = false;   // ② reset() 置位 → 强制收回
         int desired = chromeHeight() + visible;                // ③ 再把 chrome 加回去
         if (heightSink != null && (force || desired > lastNotifiedHeight + 4)) {
             lastNotifiedHeight = desired;
             heightSink.onDesiredHeight(desired, force);        // ④ 告诉分隔条
         }
     }
```

- **① 先设宽再取高**：`JTextArea` 的高度依赖换行，而换行依赖宽度。必须先 `setSize(width, MAX)`
  让它按当前宽算好行数，`getPreferredSize().height` 才有意义。这与 §5.4 `MeasuredComponent`
  的「先宽度后高度」是同一个道理。
- **② `collapseAfterSend`**：`reset()` 置位，下一次 `refreshHeight` 读到后立刻复位，
  于是「发送后收回 4 行」只需一个布尔值，不必给 `refreshHeight` 加参数。
- **`chromeHeight()`（602-610）** 里包含工具栏高、边框、附件条、上下内边距，其中有一个硬编码
  `JBUI.scale(18)`。⚠️ 这是整个 UI 里最不该存在的魔数（§19.2）。

### 3.9 `InputArea`（862-902）：按宽度降级的 placeholder

`JTextArea` 没有 placeholder。这里的实现是**自绘**：

```java
864  private static final String HINT = AgentBundle.message("composer.hint");
877  protected void paintComponent(Graphics g) {
         super.paintComponent(g);
         if (document 非空) return;                            // ① 有内容就不画
         String hint = HINT;
         if (宽度放不下) hint = "composer.hint.short";          // ② 窄工具窗降为短文案
         if (还放不下)  hint = "composer.hint.tiny";
         g2.setColor(UiTheme.muted());                         // ③ 颜色直接用主题色
     }
```
三级降级（`composer.hint` → `.short` → `.tiny`）的成本很低，
但避开了「工具窗口拖窄后提示被截成半个字」这类兼容性抱怨。

### 3.10 `ComposerBox`（644-715）：焦点动画

- `focusGained` / `focusLost`（653-663）→ `animateFocus(target)`（666-671）→ `UiAnim.fade`。
  边框颜色在 `BORDER` 与 `ACCENT` 之间过渡，**不是瞬变**。
- `paintComponent`（673-693）画圆角边框（`UiMetrics.radiusInput()`）+ 可选外发光。
- `paintKeyHint`（695-714）在框的右下角画 `Enter 发送 · Shift+Enter 换行`（`ui.composer.keys`）。
  ⚠️ 它与发送按钮、模型下拉共享同一行的右侧空间，在窄宽度下会重叠（§19.3）。

### 3.11 三个自绘图标

`ArrowIcon`（906-931）、`StopIcon`（935-963）与 `ChevronIcon`（967-992）都是自绘 `Icon`，
而不是 SVG：因为要在按钮 hover/pressed 时随文字颜色变化，而 `UiIcons` 缓存的是固定色的位图。
📌 这是一个有意识的取舍：**能用图标资源的地方用资源（`UiIcons`），需要跟主题色动态变的用自绘**。

---

## §4 标签条：`SessionTabsBar`（692 行）

这是全项目唯一一个**完全自绘、内部没有任何子组件**的容器：`TabsArea` 是一个 `JComponent`，
所有标签、关闭叉、溢出按钮、呼吸点全部在 `paintComponent` 里画，点击命中靠一个
`List<Rectangle> bounds` 反查。为什么不用 `JBTabbedPane`？因为要做到三件原生标签做不到的事：

1. **宽度收缩算法**：标签多时按比例压缩，而不是直接出左/右箭头滚动；
2. **状态呼吸点**：运行中的会话标签上有一个 40ms 心跳的小圆点；
3. **选中/悬停的两层淡入**：底色与底部 2px 指示条各自有独立动画曲线。

### 4.1 结构

```java
// SessionTabsBar.java
26  public final class SessionTabsBar extends JPanel {
30      public interface Listener { onSelect(int) / onClose(int) / onNewSession() / onHistory(Component) / onMemory(Component) / onSettings() }
46      public static final class Tab { String title; boolean running; }     // 不可变数据
58      private final TabsArea tabs = new TabsArea();                          // 自绘区
67      public SessionTabsBar() { ... }                                       // 右侧图标按钮 + tabs
```

布局是 `BorderLayout`：`CENTER = tabs`，`EAST = 一排图标按钮`（新建、历史、记忆、设置）。
按钮用 `iconButton(iconName, tipKey)`（124-129）生成：`UiButton` + `UiIcons.icon(name)` +
`TooltipManager` 提示。

📌 **为什么标签区自己一个类**：`TabsArea` 需要 `doLayout`（计算矩形）、`paintComponent`（绘制）、
鼠标监听（命中）三件事共享同一份 `bounds`。拆成 `SessionTabsBar` 的方法会变成 6 个字段的散装代码。

### 4.2 输入：`setTabs` 与状态缓存

```java
137  public void setTabs(List<Tab> values, int selected) {
         tabs.updateTabs(values, selected, pendingMemories);
     }
143  public void setPendingMemories(int count) {
         pendingMemories = count;                        // 角标数字
         // 只重绘记忆按钮的图标（BadgeIcon 读这个值），不重建全部标签
     }
```

`updateTabs`（688-691）只是三行赋值 + `revalidate/repaint`，真正的计算推迟到 `layoutTabs`
与 `paintComponent`。这是自绘组件的通用套路：**输入只存数据，几何在绘制前算**。

`Tab` 是 `public static final class`（46-56）而不是 `record`，因为它要给外部构造一个
「标题 + 是否运行中」的轻量载体，且字段可读不可改（无 setter）。

### 4.3 布局：`layoutTabs`（294-342）的收缩算法

这是本文件最值得逐行读的 48 行。目标：在 `width` 内放 `count` 个标签，每个宽度尽量均分，
但下限 58px（再窄标题就没意义）、上限 180px（再宽就是浪费），放不下时把多出的标签藏进 `»`。

```java
294  private void layoutTabs() {
         int width = getWidth();
         int count = tabs.size();
         if (count == 0) { bounds.clear(); return; }

         int gap = gap();                                  // 标签间距
         int per = (width - gap * (count - 1)) / count;     // ① 理想均分
         per = Math.max(minTabWidth(), Math.min(maxTabWidth(), per));   // ② 夹在 58..180

         int fit = count;
         int avail = width;
         while (fit > 1 && fit * (per + gap) > avail) {     // ③ 逐步减少可见数
             fit--;
             per = Math.max(minTabWidth(), avail / fit - gap);
         }
         if (fit < count) avail -= overflowWidth();         // ④ 溢出时给 » 留位

         firstVisible = clampIndex(selected - fit + 1, 0, count - fit);   // ⑤ 保证选中项可见
         // ⑥ 逐个 setBounds(bounds[i], ...)，只对 [firstVisible, firstVisible+fit) 赋值
     }
```

五个要点：

1. **① 先按均分算 `per`，不是先判断是否溢出**。因为大多数情况下不溢出，先把常见路径做快。
2. **② 夹逼必须在循环前**：否则 `per` 可能小于 `minTabWidth`，循环条件 `fit*(per+gap) > avail`
   会立刻为真，一上来就把标签藏掉。
3. **③ 循环里两个变量同时变**：`fit` 减一后 `per` 变大（分母小了），这才是「少显示几个、
   每个宽一点」的正确形态。只减 `fit` 不减 `per` 会得到一堆窄标签。
4. **④ `overflowWidth()`（287-292）约 22px**：`»` 不是一个真正的标签，它占的宽度要从可用宽度里扣。
5. **⑤ `firstVisible` 的计算**：`selected - fit + 1` 的意思是「让选中项落在最后一个可见位」，
   这样向右切标签时视口会跟着滚动；`clamp` 到 `[0, count-fit]` 保证首尾不越界。

`doLayout`（345-349）与 `ensureLayout`（351-356）：前者调 `layoutTabs`，后者在 `paintComponent`
前做一次「宽度变了就重算」的兜底（因为自绘组件不一定会收到 `doLayout`）。

### 4.4 绘制：`paintTab`（385-451）

一个标签从下往上叠四层：

| 层 | 条件 | 颜色/几何 |
| --- | --- | --- |
| 悬停底 | `hoverFade[slot] > 0` | `UiTheme.HOVER`，alpha = fade |
| 选中底 | `selectionFade`（140ms 淡入） | `UiTheme.SUBTLE` |
| 底部指示条 | 选中 | `ACCENT`，高 **2px**，宽 = 标签宽 - 2×内边距 |
| 标题 + 呼吸点 + 关闭叉 | 总是 | 标题 `ellipsize`，叉透明度见下 |

**关闭叉的透明度规则**（本文件里最细的一条产品规则）：

```
若鼠标悬停在该标签上，或该标签正处于「关闭中」 → 1.0
否则若该标签被选中                        → 0.55
否则                                      → 0.0（不画）
```

📌 效果：未选中的标签平时看不到叉，鼠标移上去才出现；选中的标签常驻一个淡叉。
这避免了 8 个标签同时满屏叉号的噪声。

**呼吸点**（`syncPulse` 251-274）：只要**任一**标签 `running`，就启动一个 40ms 的 `Timer`，
每帧推进一个相位，在标题左侧画一个透明度随相位变化的小圆点；没有运行中的标签时停掉定时器。
```java
251  private void syncPulse() {
         boolean any = anyRunning();
         if (any && pulseTimer == null) { pulseTimer = new Timer(40, e -> { phase += ...; repaint(); }); pulseTimer.start(); }
         else if (!any && pulseTimer != null) { pulseTimer.stop(); pulseTimer = null; }
     }
```
⚠️ **全局只允许一个定时器**。如果每个标签各启动一个 40ms 定时器，8 个标签就是每秒 200 次重绘，
IDE 会在低配机器上明显掉帧。`removeNotify`（628-636）必须停表——组件被移除后
`repaint()` 仍然会排队，定时器不停就是永久泄漏。

### 4.5 交互：`installMouse`（489-600）

| 区域 | 左键 | 中键 |
| --- | --- | --- |
| 标签主体 | `listener.onSelect(index)` | `listener.onClose(index)` |
| 关闭叉 | `listener.onClose(index)` | — |
| `»` | `showOverflowMenu(point)`（612-626） | — |
| 空白处 | 无 | 无 |

`updateHover(Point)`（545-600）负责两件事：设置光标形状、推进每个 slot 的 `hoverFade`。

⚠️ **这里有一处真实的索引混用**：`hoverFade` 数组以 **slot**（可视位置，`index - firstVisible`）
为下标，而 `hoverIndex` 以**全局 index** 为值，代码里两者直接相减/相比（如
`hoverIndex - firstVisible`）。在「没有溢出、`firstVisible == 0`」时两者相等，所以绝大多数
情况下看不到问题；一旦标签多到溢出、且鼠标悬停在右侧标签上，就可能出现**高亮错位到相邻标签**。
修法是把所有索引统一到 slot 域（或统一到全局 index 域）。→ 参见 §19.1。

### 4.6 快捷键（158-199）

| 按键 | 行为 |
| --- | --- |
| `Ctrl+1` … `Ctrl+8` | 选中第 n 个标签（超出范围忽略） |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | `step(+1)` / `step(-1)`（181-198），循环切换 |

📌 快捷键里**没有**「新建标签」和「关闭标签」：`Ctrl+T` / `Ctrl+W` 在 IDEA 里已经有全局含义
（新建/关闭编辑器标签），抢过来会制造冲突。用户只能用鼠标或工具栏按钮。

### 4.7 溢出菜单 `showOverflowMenu`（612-626）

只列出**当前不可见**的标签（`[0, firstVisible)` ∪ `[firstVisible+fit, count)`），
点击后 `listener.onSelect(globalIndex)`——注意传回去的必须是**全局 index**，因为
`onSelect` 最终会调到 `sessionAt(index)`（§1.4），而它按 `sessions.values()` 的下标取。
🔁 这是「slot 域 ↔ index 域」第二个必须小心的转换点（第一个是 §4.5）。

### 4.8 角标 `BadgeIcon`（638-687）

记忆按钮的图标上叠一个数字角标（待确认记忆条数）。`paintIcon` 里画红色小圆 + 白字，
数字 > 99 时显示 `99+`。📌 它是 `Icon` 而不是 `JComponent`，因为要直接交给 `UiButton.setIcon`，
而 `UiButton` 不管理子组件的布局。

---

## §5 门面与基座：七个文件搭起渲染骨架

§6–§10 的所有视图都建立在下面这七个文件上。理解它们只需要回答三个问题：
**谁决定宽度？谁决定高度？什么时候重算？**

```
ChatTranscript (102)       门面：12 个方法转发给 impl
   └─ TranscriptView (49)  接口：12 个方法的契约
        └─ ChatView (736)  实现：行容器 + 流式合流 + 行卸载
             └─ content : JPanel + BlockListLayout (137)
                  └─ ChatRowView (51) / MeasuredComponent (60)
                       ├─ AgentMessageView → BlockPanel (121) → BlockView (119)
                       └─ NoticeView / UserMessageView / StepGroupView / 各卡片
```

### 5.1 `ChatTranscript`（102 行）：一个不抽象的工厂

```java
// ChatTranscript.java:18-46
18  public final class ChatTranscript extends JPanel {
        private final TranscriptView impl;
22      public ChatTranscript() {
            impl = classicMode() ? new ClassicTranscript() : new ChatView();
            setLayout(new BorderLayout());
            add((Component) impl, BorderLayout.CENTER);
        }
29      private static boolean classicMode() {
            return Boolean.getBoolean("agent.ui.classic")
                    || "classic".equals(System.getProperty("agent.ui.renderer"));
        }
```

后面的 12 个方法（48-93）全部是 `impl.xxx(...)` 一行转发。

📌 **为什么不把选择逻辑搬到调用方**：`ChatSessionPanel` 有 12 处调用，如果每处都判断
「用哪个实现」，开关就变成 12 个分支。门面把「选哪个实现」收敛到构造函数里的一次判断，
调用方永远不知道有两个实现。这是**门面模式最纯粹的用法：不抽象行为，只隔离选择**。

两个细节：

1. **两个系统属性都要认**（29-33）：`-Dagent.ui.classic=true` 是给用户的手动开关，
   `-Dagent.ui.renderer=classic` 是给插件内部/CI 的枚举式开关。
2. **`setSuggestionSink`（40-46）用 `instanceof ChatView` 判断**，而不是在接口上加方法：
   空状态卡片只有新渲染有，经典实现没有对应的 UI。这里选择「静默忽略」而不是
   `throw new UnsupportedOperationException`——因为降级到经典渲染时抛异常会让整个面板白屏，
   而忽略只是少一个小功能。

### 5.2 `TranscriptView`（49 行）：12 个方法的真实契约

```java
// TranscriptView.java:10-49
10  public interface TranscriptView {
        void addHint(String text);
        void addUserMessage(String text, List<String> attachmentNames);
        void appendAssistantToken(String delta);
21      default void appendReasoningToken(String delta) { }   // ★ 没有思考行的渲染器直接忽略
        void addToolLine(String text);
29      default void addToolLine(String text, boolean running, boolean failed) {
            addToolLine(text);                              // ★ 旧渲染退回纯文本
        }
        void addNotice(String text);
        void addErrorMessage(String text);
        void finishAssistant();
42      default boolean addCard(ChatRowView row) { return false; }
        void clear();
        void dispose();
    }
```

📌 **两个 `default` 是协议只增不改的关键**：`appendReasoningToken` 给 `ClassicTranscript` 一个
空实现，旧渲染因此不需要改；`addToolLine(text, running, failed)` 回退到单参版本，一行文本
照样能上屏。新增通知也遵循同一原则——先给默认行为，再让新渲染覆写。

⚠️ **`addCard` 的默认实现返回 `false`**（`ClassicTranscript` 不覆写它），而 `ChatView.addCard`
无条件 `return true`（296-305）——即使 `appendRow` 是 `invokeLater` 异步执行的。
调用方 `handleConfirm` / `handleAsk` 用返回值决定「要不要退回对话框」，所以 `ChatView` 这边
的 `true` 是「我将要挂上去了」而非「已经挂上去了」。目前不会出错（`ChatView` 一定能挂），
但如果将来 `ChatView.addCard` 加上了「行数已满则拒绝」的逻辑，必须同步改成真实结果。

### 5.3 `MeasuredComponent`（60 行）：高度缓存协议

```java
// MeasuredComponent.java:11-59
11  public abstract class MeasuredComponent extends JComponent implements BlockListLayout.Measured {
        private int measuredWidth = -1;
        private int measuredHeight;
17      public final int heightFor(int width) {
            if (width != measuredWidth) { measuredWidth = width; measuredHeight = computeHeight(width); }
            return measuredHeight;
        }
38      protected final void invalidateHeight() { measuredWidth = -1; }
42      protected abstract int computeHeight(int width);
44      protected abstract void paintBlock(Graphics2D g2, int width, int height);
47      protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(KEY_FRACTIONALMETRICS, VALUE_FRACTIONALMETRICS_ON);
            try { paintBlock(g2, getWidth(), getHeight()); } finally { g2.dispose(); }
        }
```

这是整个渲染栈的**唯一度量入口**，三条不变量（违反任一条就会出现「文字被裁」「高度跳变」）：

| 不变量 | 含义 | 违反后的症状 |
| --- | --- | --- |
| **按宽度缓存** | `computeHeight` 只在宽度变化时被调 | 每帧重算 → 滚动卡顿 |
| **`computeHeight` 无副作用** | 除了缓存字段，不得修改组件状态 | 度量与绘制不一致 → 内容跳动 |
| **`invalidateHeight` 必须配对** | 内容/字体/主题变了就要调 | 高度不更新 → 文字被裁 |

📌 **为什么 `heightFor` 是 `final`**：缓存逻辑必须只有一份。子类只实现 `computeHeight`，
不能绕开缓存直接算。同理 `paintComponent` 也是 `final`（隐含在基类里不被覆写的约定），
子类只能实现 `paintBlock`，从而保证渲染提示与 `dispose` 不会漏。

### 5.4 `ChatRowView`（51 行）：行宽策略

```java
// ChatRowView.java:9-50
9   public abstract class ChatRowView extends MeasuredComponent {
14      protected ChatRowView(double ratio, boolean rightAligned)
22      protected final int maxWidth(int availableWidth) {
            return Math.max(UiMetrics.scale(140), Math.round(availableWidth * ratio));
        }
28      protected int contentWidth(int maxWidth) { return maxWidth; }      // 默认可变
33      public final int preferredWidth(int availableWidth) {
            int cap = maxWidth(availableWidth);
            return Math.min(cap, Math.max(UiMetrics.scale(80), contentWidth(cap)));
        }
40      public final boolean alignRight() { return rightAligned; }
46      public void contentChanged() { invalidateHeight(); }
```

这是一个**宽高三步曲**：

1. `cap = availableWidth × ratio`：行最多占可用宽度的 ratio（用户气泡 0.78 左右、助手卡片 1.0、
   提示行 1.0）。`max(140, …)` 保证窗口极窄时仍有一个可读下限。
2. `contentWidth(cap)`：子类在这里回答「内容**想要**多宽」。默认返回 `cap`（占满）；
   `UserMessageView` 返回文字实际宽度（自适应气泡）；`NoticeView` 返回
   `min(textWidth, layout.maxAdvance()) + indent + padX*2`。
3. `max(80, …)` 再 `min(cap, …)`：内容太窄时给 80px 下限，但不超 `cap`。

📌 **为什么 `contentWidth` 的参数是「已压缩的 cap」而不是原始可用宽**：子类折行时必须按
最终宽度算，否则会出现「按 900px 算 1 行，实际只给 600px」的裁切。

### 5.5 `BlockListLayout`（137 行）：竖向堆叠 + 宽度协商

```java
// BlockListLayout.java
14  public final class BlockListLayout implements LayoutManager2 {
18      public interface Measured { int preferredWidth(int availableWidth); boolean alignRight(); int heightFor(int width); }
32      public BlockListLayout(int gap)
55      public Dimension preferredLayoutSize(Container parent) { ... totalHeight(parent, availableWidth(parent)) ... }
64      public int totalHeight(Container parent, int width) {
            int h = insets.top + insets.bottom;
            for (Component c : visible) h += height(c, width) + (last ? 0 : gap);
            return h;
        }
102     public void layoutContainer(Container parent) {
            int avail = availableWidth(parent), y = insets.top;
            for (Component c : visible) {
                int w = c instanceof Measured m ? Math.min(m.preferredWidth(avail), avail) : avail;
                int x = (c instanceof Measured m && m.alignRight()) ? insets.left + avail - w : insets.left;
                c.setBounds(x, y, w, height(c, avail));
                y += height(c, avail) + gap;
            }
        }
129     private static int availableWidth(Container parent) {
            int w = parent.getWidth() - parent.getInsets().left - parent.getInsets().right;
            return w > 0 ? w : UiMetrics.scale(480);        // ★ 首帧兜底
        }
```

四条关键设计：

1. **`totalHeight` 与 `layoutContainer` 用同一套 `preferredWidth/heightFor`**。如果两者算法不同，
   就会出现「布局高度比实际内容少 8px」这类鬼影。抽成一个 `totalHeight` 公共方法就是为了防这个。
2. **`availableWidth <= 0 → 480`**（129-136）：首帧布局时容器还没有宽。若返回 0，
   每个子组件的 `max(24, w)` 下限会把文字折成每行一个字，高度爆炸。480 是一个「与默认面板宽
   接近」的保守值，下一页布局就会用真实宽度重算。
3. **`LayoutManager2` 而非 `LayoutManager`**：需要实现 `getLayoutAlignmentX/Y`（92-100），
   让父容器（`JScrollPane` 里的 holder）知道如何对齐。
4. **只排可见组件**：`trimOldRows` 会用 `content.remove(...)` 卸载旧行（§6.5），
   布局必须跳过 `!isVisible()` 的组件，否则高度会算多。

### 5.6 `BlockView`（119 行）：块视图的公共能力

```java
// BlockView.java
16  public abstract class BlockView extends MeasuredComponent {
        private InlineLayout caretTarget; private boolean caretVisible; private int caretX, caretY;
26      protected final void setCaretTarget(InlineLayout layout, int x, int y)
32      public void setCaretVisible(boolean visible)
40      protected final void paintCaret(Graphics2D g2) {
            if (caretVisible && caretTarget != null) caretTarget.paintCaret(g2, caretX, caretY, UiTheme.ACCENT);
        }
46      protected final void installLinks(InlineLayout layout, int insetX, int insetY) {
            // mouseMoved：查 linkAt → 变手型光标；mouseClicked：查 linkAt → BrowserUtil.browse(href)
        }
74      protected static String plain(List<InlineSpan> spans)       // 拼纯文本
86      public static String plain(MarkdownBlock block)             // 任意块 → 纯文本（复制用）
```

- **光标（caret）只在「流式的最后一块」上可见**：`BlockPanel.setCaretVisible`（76-84）只把
  可见标志传给 `tailView()`，其余块一直是 `false`。这保证了「光标只出现在正在写的地方」。
- **链接命中需要减去 inset**：`installLinks(layout, insetX, insetY)` 传入的 inset 是该块
  内容相对组件左上角的偏移（如 `QuoteView` 的 12px 缩进）。命中计算必须用
  `linkAt(x - insetX, y - insetY)`，否则链接区域会整体偏移。
- **`plain(MarkdownBlock)` 是复制功能的唯一出口**：`TextCopy.install` 传入的 supplier
  最终都落到这里（或 `BlockPanel.plainText()`），所以「复制」拿到的始终是无格式纯文本。

### 5.7 `BlockPanel`（121 行）：只重建尾部

```java
33  public void setBlocks(List<MarkdownBlock> blocks) {
        // ① 求公共前缀：record 的 equals 是逐字段比较，List.equals 逐元素比较
        int same = 0;
        while (same < mounted.size() && same < blocks.size() && mounted.get(same).equals(blocks.get(same))) same++;
        // ② 卸载 [same, mounted.size()) 的视图
        for (int i = mounted.size() - 1; i >= same; i--) { remove(i); mounted.remove(i); }
        // ③ 为 [same, blocks.size()) 创建视图
        for (int i = same; i < blocks.size(); i++) { BlockView v = create(blocks.get(i)); mounted.add(...); add(v); }
        // ④ 光标交给最后一块
        caretSource = tailView();
        invalidate...; revalidate(); repaint();
    }
```

📌 **为什么能「只重建尾部」**：`MarkdownBlock` 的 8 个变体全是 `record`（§8.1），
`record` 的 `equals` 是逐字段比较，而 `InlineSpan` 手写了 `equals`（§8.2）。流式追加时，
前面的块内容不变 → `equals` 为 true → 不重建。这是整套「40ms 重解析不卡」的最后一环：

```
新文本 → 解析出 N 个块 → 前 k 个与旧块 equals → 只重建 N-k 个视图
```

如果没有这一层，即使解析是增量的，每帧仍要丢掉重挂所有 `BlockView`，光标闪烁与滚动位置都会丢。

`create(MarkdownBlock)`（100-119）是一个 `switch` 模式匹配（Java 21）：

| 块类型 | 视图 |
| --- | --- |
| `Heading(level, spans)` | `HeadingView(level, spans)` |
| `Paragraph(spans)` | `ParagraphView(spans)` |
| `ListBlock(ordered, items)` | `ListView(block)` |
| `CodeBlock(language, code)` | `CodeBlockView(block)` |
| `Quote(spans)` | `QuoteView(spans)` |
| `Table(header, rows)` | `TableView(block)` |
| `Divider()` | `DividerView()` |

📌 因为 `MarkdownBlock` 是 `sealed interface`，这个 `switch` 不需要 `default`——编译器会检查
穷尽性。**新增一种块类型时，编译会失败并指出这里**，这是 sealed 类型最大的收益。

---

## §6 新对话区：`ChatView`（736 行）

这是默认渲染器。类的注释直接给出了三个性能承诺（20-29）：

```
1. token 按 40ms 合并，且只重解析尾块；
2. 每行按宽度缓存高度，重绘不再全量测量；
3. 过老的行按批卸载，不再长期留在组件树里。
```

下面按「结构 → 流式 → 行管理 → 滚动 → 内部组件」的顺序逐个落实。

### 6.1 结构：三层嵌套，每层只解决一件事

```
ChatView (JPanel, BorderLayout)
└─ Overlay (JPanel, null 布局)                 ← 让胶囊浮在滚动区之上
   ├─ JBScrollPane
   │   └─ ScrollHolder (JPanel + Scrollable)    ← 宽度永远跟视口一致
   │       └─ content (JPanel + BlockListLayout) ← 行栈
   │           ├─ [MoreView]   （仅在 hiddenFrom > 0 时）
   │           ├─ EmptyState   （仅在 rows 为空时，index 0）
   │           └─ ChatRowView…
   └─ BackToBottom (JComponent, 自绘胶囊)       ← 不在滚动区内
```

- **`ScrollHolder`（558-590）**：`getScrollableTracksViewportWidth() = true`（581-584），
  `getScrollableTracksViewportHeight() = false`（586-589）。前者保证**永不出现横向滚动条**
  （行宽永远等于视口宽，`BlockListLayout.availableWidth` 才有稳定参照）；后者保证内容比视口高时
  能纵向滚动。
- **`Overlay`（594-609）**：`setLayout(null)`，`doLayout` 里手动 `setBounds`：滚动区占满，
  胶囊水平居中、距底 `scale(14)`。用 `null` 布局是因为胶囊要**覆盖**在滚动区上而非占位。
- **`content` 的边界**（80-83）：`EmptyBorder(12, 10, 14, 10)`，即上 12 / 下 14 / 左右 10。
  上下不对称是因为底部多了胶囊的视觉重量。

### 6.2 构造（76-103）与两条定时器纪律

```java
76  public ChatView() {
80-83  content.setLayout(new BlockListLayout(scale(12))); content.setBorder(...)
85-89  scrollPane = new JBScrollPane(new ScrollHolder(content)); 去边框/透明；垂直 unit = scale(40)
90     scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> updatePill());
92-95  overlay + backToBottom + add(overlay)
99-100 flushTimer = new Timer(FLUSH_MS, e -> flushNow()); flushTimer.setRepeats(false);
102    mountEmptyState();
}
```

📌 **`setRepeats(false)`**：合流器是「一次性定时器」：每次收到 token 时如果没在跑就启动，
它跑完一次就停。这正是「40ms 内多个 token 只刷一次」的实现。若用 `setRepeats(true)`，
即使没新 token 也会每 40ms 重刷一次。

⚠️ **`updatePill()` 挂在滚动条上**：滚动条的每个 `AdjustmentEvent`（包括滚轮、拖拽、程序设值）
都会触发一次可见性判断。判断本身很便宜（3 个 int 比较），但调用量很大。

### 6.3 流式：从 token 到屏幕上的一行字

正文与思考走**同一套合批**，只是队列和缓冲区各一份：

```java
141  public void appendAssistantToken(String delta) {
         if (delta == null || delta.isEmpty()) return;
         …非 EDT：pendingTokens.add + drainScheduled + invokeLater（一次 drain 一个任务）
170  private void appendTokenOnEdt(String delta) {
171      ensureAssistantRow();                       // 没有当前卡片就建一个（并收起思考行）
172      buffer.append(delta);                       // ★ 只拼字符串，不动 UI
173      dirty = true;
174-176  if (!flushTimer.isRunning()) flushTimer.start();
}
185  public void appendReasoningToken(String delta) {
         …同样的 pendingReasoning + reasoningDrainScheduled 快速路径
}
212  private void appendReasoningOnEdt(String delta) {
213      ensureReasoningRow();                       // 先 breakSteps()，再挂一个 ReasoningView
214      reasoningBuffer.append(delta);
215      reasoningDirty = true;
216-218  if (!flushTimer.isRunning()) flushTimer.start();
}
```

**注意它什么都没画**。真正的绘制在 `flushNow`：

```java
362  private void flushNow() {
363      boolean pinned = isPinnedToBottom();            // ① 先记录用户是否在底部
364      boolean changed = false;
365-369  if (reasoningDirty && reasoning != null) { reasoningDirty = false; reasoning.setText(reasoningBuffer.toString()); changed = true; }
370-374  if (current != null && dirty) { dirty = false; current.setMarkdown(buffer.toString()); changed = true; }
375-377  if (!changed) return;
378-383  content.revalidate(); content.repaint();
         if (pinned) scrollToBottom(false);               // ② 只在原本就在底部时才跟随
         updatePill();
}
```

四个容易误读的点：

1. **为什么每次喂「整篇」而不是「增量」**：`IncrementalParser.feed(String)` 的契约就是
   「给我当前完整文本」，内部自己判断能不能复用前缀（§8.4）。这样调用方不必维护
   「上一次的文本到哪了」，也不会因为漏一个 delta 就永久错位。内存代价是 2 份字符串
   （`buffer` 与解析器内的 `text`），对十年级别的对话长度也无所谓。
2. **`pinned` 必须在 `setMarkdown` 之前取**：改内容后再判断 `isPinnedToBottom` 就已经是 false
   了，于是永远不跟随。
3. **用 `scrollToBottom(false)`（无动画）**：流式期间每 40ms 一次，带动画会变成持续的
   缓动追击，视觉上像果冻。只有用户点胶囊时才用动画（506-534）。
4. **两个脏标记必须同时处理**（3.3.0 修正）：旧版第一行是 `if (current == null || !dirty) return;`，
   而当一轮只有思考、正文还未开始时 `current` 恰好是 `null`，思考帧被整帧吞掉。现在
   `reasoningDirty` 与 `dirty` 各自落盘，任一有效就 `changed = true`。

**收尾 `finishAssistantInternal`（386-411）**：

```java
386  private void finishAssistantInternal() {
390      drainTokens();                              // 缓存 token 先落盘
393      finishReasoningInternal();                  // 思考行落盘 + 冻结计时 + 自动折叠
394      flushTimer.stop();
395-406  buffer 空 → removeRow(current)（★ 空行撤掉）；否则 setMarkdown + setStreaming(false)
408-410  current = null; buffer.setLength(0); dirty = false;
}
```

📌 **「空行撤掉」是为了处理「一轮只调工具、不写正文」的情况**。如果保留，用户会看到一个
只有头部 chip、没有正文的空卡片，后面紧跟着步骤块，视觉上像一片空白。

📌 **`ensureAssistantRow`（341-349）首行就是 `finishReasoningInternal()`**：正文一开始，思考
就停止增长并折叠成一行。这样「思考 → 正文」的时间顺序在界面上不会倒挂。

### 6.4 七种行的追加路径

| 方法 | 行 | 先做的事 | 追加的视图 |
| --- | --- | --- | --- |
| `appendReasoningToken` | 185-244 | `ensureReasoningRow`：先 `breakSteps`，再挂行；`finishReasoningInternal` 收尾 | `ReasoningView`（§10.9） |
| `addHint` | 114-129 | 若 `rows` 为空 → 只更新 `EmptyState` 提示语，**不建行** | `NoticeView(HINT)` |
| `addUserMessage` | 131-139 | `finishAssistantInternal` + `breakSteps` | `UserMessageView` |
| `addToolLine` | 247-266 | `finishAssistantInternal` + 复用/新建 `StepGroupView` | 步骤块 |
| `addNotice` | 269-276 | `finishAssistantInternal` + `breakSteps` | `NoticeView(INFO)` |
| `addErrorMessage` | 279-286 | 同上 | `ErrorView`（`NoticeView(ERROR)` 子类） |
| `addCard` | 296-305 | 同上 + `invokeLater(requestFocusInWindow)` | 传入的卡片 |

三处值得特别说明：

1. **`addHint` 的「空对话特殊路径」**（116-124）：没有消息时，提示语应该显示在空状态里面
   （“试试这样问”下面的一行小字），而不是变成一条孤立的灰条。
2. **`addToolLine` 的步骤聚合**（252-266）：
   ```java
   252  public void addToolLine(String text, boolean running, boolean failed) {
   258      if (steps == null || steps.getParent() != content) { steps = new StepGroupView(); appendRow(steps); }
   262      steps.add(text, failed);      // failed → StepGroupView.failedCount++
   263      steps.setRunning(running);
   }
   ```
   `steps.getParent() != content` 这个判断同时覆盖两种「失效」：从未创建、以及被 `clear()` /
   `revealOlder()` 从树上摘掉。`breakSteps()`（355-360）在任何非工具行到来时先 `setRunning(false)`
   再把 `steps` 置 null，于是下一批工具行会开一个新块。
   单参 `addToolLine(text)`（247-249）等价于 `(text, false, false)`，留给旧调用点。
3. **`addCard` 里的 `invokeLater(row::requestFocusInWindow)`**（302）：`appendRow` 已经 `revalidate`，
   但焦点请求必须在布局完成后才生效，所以再推迟一个事件循环。

### 6.5 行管理：`appendRow` / `trimOldRows` / `revealOlder`

```java
415  private void appendRow(ChatRowView row) {
416      rows.add(row);
417-420  if (emptyState != null) { content.remove(emptyState); emptyState = null; }
421      trimOldRows();                 // ★ 先修剪，再 add
422      content.add(row);
423-424  content.revalidate(); content.repaint();
}
```

```java
436  private void trimOldRows() {
437      while (rows.size() - hiddenFrom > KEEP_ROWS + REVEAL_BATCH) {   // 120 + 40 = 160
438          content.remove(rows.get(hiddenFrom));
439          hiddenFrom++;
440      }
441      syncMoreView();
}
```

`hiddenFrom` 的含义是「`rows` 里前多少个已从组件树卸载」。于是：

- `rows.size()` = 历史上产生过的所有行
- `rows.size() - hiddenFrom` = 当前挂在树上的行
- 常量：`KEEP_ROWS = 120`、`REVEAL_BATCH = 40`，门槛为二者之和 **160**

⚠️ **这里有一个与命名不符的细节**：`while (mounted > 160) remove(...)` 会把**挂载数稳定在 160**，
而不是「保有 120 + 留 40 缓冲」。因为循环每轮只删一行，正常状态下每次 `appendRow`
只会删掉最老的 1 行（161 → 160）。后果是：用户点「查看更早」把 `hiddenFrom` 减 40 之后，
挂载数变成 200，于是**下一条新消息到来时会一次性又删掉 41 行**，把刚展开的内容重新折叠。
这是「批量卸载」的意图（注释 434-435）与实际实现的偏离：要实现「一次卸 40 行、留 120」，
条件应写成「先算出本次要删的批次数，再统一 `remove` 并刷新 `moreView`」。→ §19.1

**`revealOlder`（462-477）**：点「查看更早」时 `hiddenFrom -= 40`，然后 `content.removeAll()`
把 `[hiddenFrom, rows.size())` 重新 `add` 回去，并在最前面补一个 `MoreView`。

⚠️ **这里有一处已确认的缺陷**：`trimOldRows` 只从 `content` 移除组件，**没有从 `rows` 移除**
（437-440）。后果：

1. `rows` 会持续持有所有历史行对象及其全部子视图（`InlineLayout` 里的 `TextLayout`、
   `BlockView` 列表、已度量缓存），即「视觉上卸载了，内存里没卸载」。长会话（几千行）下
   内存占用会持续增长。
2. `revealOlder` 依赖「`rows` 保留全部行」才能回滚显示，所以**不能简单地 `rows.remove`**。
   正确修法是引入一个「轻度行」表示（只留 `MarkdownBlock` 列表与行类型），或者给 `rows`
   换成一个只保存必要数据的环形缓冲。

📌 这是一个**知情取舍**而非疏忽：代码注释写明「按批卸载最老的行」（434-435），语义是
「从组件树上卸载」。真正的内存释放需要更大改造，当前版本先保 UI 流畅。→ §19.1

### 6.6 滚动与「回到底部」胶囊

```java
494  private void followTail() {
495-497  if (isPinnedToBottom()) scrollToBottom(false);
498      updatePill();
}
501  private boolean isPinnedToBottom() {
         JScrollBar bar = scrollPane.getVerticalScrollBar();
         return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - PIN_SLACK;   // 48
}
```

- **`PIN_SLACK = 48`**：允许用户稍微上滚一点点仍算「在底部」。没有这个容差，只要滚动条因为
  内容变高而差 1px，就停止跟随，用户会觉得「输出到一半不动了」。
- **`scrollToBottom(smooth)`（506-534）**：先 `invokeLater` 等布局完成，再 `bar.setValue(target)`；
  `smooth = true` 时用 `UiAnim.animate(120, …)` 把 `value` 线性插值到 `target`。
  ⚠️ **动画目标只算一次**（520）：如果 120ms 内内容又变高了，动画会停在「当时」的底部。
  由于只有用户点胶囊才会走这条路径，实际影响极小。
- **`BackToBottom`（611-669）**：自绘胶囊，`getPreferredSize` 按文字宽度 + `scale(26)`；
  hover 时边框与文字变 `ACCENT`。`updatePill`（536-543）只在可见性真的变化时才
  `setVisible + revalidate`，避免每个滚动事件都触发一次布局。

### 6.7 `isRunningTool` 已删除：状态改成参数（3.3.0）

3.0–3.2 里这里有一个私有方法（**已删除**）：

```java
private static boolean isRunningTool(String text) {
    return text.contains("执行中") || text.contains("开始：");
}
```

它判断「这条工具行是否还在跑」，依据是 **UI 自己上一手拼进去的中文字符串**：
`ChatSessionPanel` 拼 `"[工具] " + toolName + " 执行中..."`，在这里被 `contains("执行中")` 识别。
当年记录下的三层问题仍然值得记住：

1. **本地化即破坏**：把「执行中」改成英文，判定永远为 false，步骤块永远显示「完成」。
2. **子串误判**：模型返回的工具名或参数里若包含「执行中」，一条已完成的行会被当成运行中。
3. **正确做法**：`addToolLine(String text, boolean running)`，让状态成为**参数**而非文本的从属。

3.3.0 按第 3 条实施了，并且多带一个 `failed`：

```java
252  public void addToolLine(String text, boolean running, boolean failed) {
262      steps.add(text, failed);      // failed 累加到 failedCount
263      steps.setRunning(running);    // running 由 activeSteps 计数决定
}
```

- 生产者 `ChatSessionPanel.emitToolStart/emitToolEnd` 只负责拼**英文日志行**与计数：
  并行调用时 `activeSteps++/--`，只有归零才 `running=false`；`chat/finish`、`chat/cancel`
  也会清空在途表，避免步骤组永久卡在 running。
- 兼容入口 `addToolLine(text)` 转发 `(text, false, false)`（§5.2 的默认方法）。

📌 **留下的教调**：「一行字符串能表达一切」的代价是让文案与行为绑定。现在改文案不会改行为，
用户还能直接复制整组日志（`TextCopy`）——这是把状态做成参数的额外收益。

### 6.8 `clear` / `dispose`

```java
308  public void clear() {
309-331  runOnEdt: flushTimer.stop(); pendingTokens/pendingReasoning.clear();
         drainScheduled=scrollScheduled=reasoningDrainScheduled=false;
         current=steps=reasoning=null; buffer/reasoningBuffer 清空; dirty=reasoningDirty=false;
         rows.clear(); hiddenFrom=0; moreView=null; content.removeAll(); mountEmptyState(); …
}
335  public void dispose() { flushTimer.stop(); }
```

📌 `clear()` 必须把所有引用字段一起清。只 `content.removeAll()` 而不清 `rows` / `current` / `steps` /
`reasoning` 的话，下一次 `appendAssistantToken` 会往一个已经不在树上的 `current` 里写，
表现为「发消息没反应」；而漏掉 `reasoningBuffer` 会让下一轮的第一行思考带着上一轮的尾巴。

---

## §7 经典对话区：`ClassicTranscript`（514 行）

3.0 的实现，保留一个版本作为渲染回退。它的价值不在于「现在怎么用」，而在于
**「如果不做新渲染，会遇到什么」**——读它能把 §5–§6 里那些看起来多余的设计（40ms 合流、
高度缓存、行卸载、增量解析）全部变成有理由的。

### 7.1 结构：`BoxLayout` + `JEditorPane`

```
ClassicTranscript (BorderLayout)
└─ JBScrollPane
   └─ ScrollHolder (Scrollable, 宽度跟随)
       └─ list (JPanel, BoxLayout.Y_AXIS, EmptyBorder(10,8,12,8))
           ├─ MessageRow (气泡行：右对齐/左对齐 + 比例宽度)
           └─ StatusRow (灰色提示行)
```

`MessageRow`（349-451）与 `StatusRow`（475-513）都是**私有内部类**，不对外暴露。

### 7.2 常量：两套气泡颜色 + 两个宽度比例

```java
// ClassicTranscript.java:23-32
23  USER_BUBBLE  = JBColor(0x3574F0, 0x2F5FC4)       // 亮/暗两套蓝
24  USER_TEXT    = Color.WHITE
25  ERROR_BUBBLE = JBColor(0xFDECEC, 0x3A2626)       // 浅红底
26  ERROR_TEXT   = JBColor(0xC0392B, 0xF08A8A)
27  HINT_TEXT    = JBColor(0x808080, 0xA0A0A0)
30  USER_RATIO   = 0.72         // 用户气泡最多占 72%
31  AGENT_RATIO  = 0.88         // 助手最多占 88%
32  ROW_GAP      = 10
```

📌 **助手为什么要留 12% 空白**：完全占满时，长段落的右边缘会贴到滚动条，视觉上“炸开”。
新渲染里的 `ChatRowView.ratio = 1.0` + `BlockListLayout` 内边距达到了同样效果，
但机制不同：后者是靠容器 `EmptyBorder(…, 10, …)`。

### 7.3 刷新：每 70ms 全量重建 HTML

```java
115  public void appendAssistantToken(String delta) {
119-126  runOnEdt: ensureAssistantRow(); pending.append(delta); dirty = true; if (!flushTimer.isRunning()) flushTimer.start();
     }
196  private void flushNow() {
197-203  if (currentEditor == null || pending.length() == 0) { dirty = false; return; }
         if (!dirty) return;
204-210  dirty = false; boolean pinned = isPinnedToBottom();
         currentEditor.setText(MarkdownRenderer.toHtml(pending.toString()));   // ★ 全量
         currentRow.contentChanged(); currentRow.revalidate(); list.revalidate(); list.repaint();
211-213  if (pinned) scrollToBottom();
     }
```

与 §6.3 对照，差别一目了然：

| 维度 | `ChatView`（新） | `ClassicTranscript`（旧） |
| --- | --- | --- |
| 合流窗口 | 40ms | 70ms |
| 每次刷新做什么 | 增量解析 + 只重建尾部块 | **整篇 Markdown → HTML → 全量重解析 HTML** |
| 排版引擎 | Java2D `TextLayout` | Swing HTML 3.2（`JEditorPane`） |
| 滚动后位置 | 按行保留（不重建） | 光标/选区位可能丢失（`setText` 重置文档） |
| 长会话 | 行卸载 + 高度缓存 | 所有气泡常驻 |

⚠️ `JEditorPane.setText` 会**重新构建整个 Document**，因此：正在选中的文字会丢失、
`JEditorPane` 内部的滚动位置会被重置、每次都要重新跑一遍 HTML 解析。这正是新渲染器存在的理由。

### 7.4 行构建：三条路径

| 方法 | 行 | 构建物 |
| --- | --- | --- |
| `addHint` | 66-72 | `StatusRow(text, HINT_TEXT, indent=false)` |
| `addToolLine` | 129-136 | `StatusRow(text, HINT_TEXT, indent=true)` |
| `addNotice` | 138-145 | `StatusRow("[提示] " + text, HINT_TEXT, true)` |
| `addErrorMessage` | 147-156 | `MessageRow(RoundedBubble(ERROR_BUBBLE, …), false, 0.88)` |
| `addUserMessage` | 78-87 | `MessageRow(RoundedBubble(USER_BUBBLE, createUserBody(...)), true, 0.72)` |

**`createUserBody`（91-112）** 处理「正文 + 附件清单」：

```java
92-95   无附件 → createPlainText(text, USER_TEXT)
96-111  有附件 → BoxLayout 竖直堆叠：
            [正文 JTextArea]（有文本时）
            [附件 JTextArea]（“附件：a、b”，字号 -1）。
```

📌 注释 102-104 解释了为什么用 `JTextArea` 而不是 `JLabel`：`JLabel` 不自动换行，
附件一多就被气泡边界裁掉。

⚠️ **`addNotice` 里的 `"[提示] "` 是硬编码前缀**（142）。注意新渲染的 `NoticeView`
**不**加前缀（它用左侧 3px 状态条表达语气）。于是同一条 `chat/notice` 在两种渲染下
长得完全不同：经典是「[提示] xxx」的灰字，新渲染是一个带状态条的圆角块。
读代码时不要把这两个当成同一套设计。

### 7.5 `MessageRow`（349-451）：手工测量的范本

这是因为 `JEditorPane` 而不得不写的 100 行：

```java
382  public Dimension getPreferredSize() {
         int width = availableWidth();
         measure(width);
         return new Dimension(width, measuredHeight + ROW_GAP);
     }
389  public void doLayout() {
         int rowWidth = getWidth(); measure(rowWidth);
         int x = alignRight ? Math.max(0, rowWidth - measuredWidth) : 0;
         content.setBounds(x, 0, measuredWidth, measuredHeight);
     }
413  private void measure(int rowWidth) {
414-417  if (rowWidth == measuredRowWidth) return;                 // ★ 按宽度缓存
418-424  maxWidth = max(180, rowWidth * ratio)
         sizeContent(maxWidth);                                    // ① 先给宽
         pref = content.getPreferredSize();
         width = min(max(pref.width, 120), maxWidth);              // ② 内容窄则缩
         height = pref.height;
         if (width < maxWidth) { sizeContent(width); height = content.getPreferredSize().height; }  // ③ 改宽重测
426-428  measuredRowWidth = rowWidth; measuredWidth = width; measuredHeight = height;
     }
435  private void sizeContent(int width) { content.setSize(width, Short.MAX_VALUE); sizeChildren(content, width, 0); }
440  private static void sizeChildren(Component parent, int width, int depth) {
441-443  if (depth > 3 || !(parent instanceof Container)) return;
444-448  递归：child.setSize(inner, Short.MAX_VALUE)
     }
```

三个教训（新渲染器的 `MeasuredComponent` 直接继承了它们）：

1. **「先宽度后高度」**：`JEditorPane` / `JTextArea` 只有在知道宽度后才会算出正确的首选高度。
   `sizeChildren` 递归最多 3 层，正是因为气泡嵌套是 `Bubble → wrapper → textArea` 三层。
2. **「窄则缩」要重测**（421-425）：把宽度从 `maxWidth` 改小后，换行变了，高度也会变。
   不重测就会出现高度不足。
3. **按宽度缓存**（414-417）：否则每次 `getPreferredSize` 都递归设尺寸，`BoxLayout` 布局一遍
   就要跑几十次，滚动直接卡成幻灯片。

### 7.6 `StatusRow` 内部类（475-513）

与 `ui/StatusRow.java`（161 行，底部状态行）**同名但不同物**。

- 这里的 `StatusRow` 是「对话流里的一条灰字」（`JTextArea` + `lineWrap`）。
- `ui/StatusRow` 是「面板底部常驻状态行」（自绘 + 呼吸点 + 元信息）。

📌 同名不同物在两个包里容易让人读错。记住：**`ui.chat.ClassicTranscript.StatusRow` 是私有内部类，
外部不可引用；看到的 `new StatusRow()` 若带 `add(...)` 参数那一定不是它。

### 7.7 两处细节陷阱

```java
262  private boolean isPinnedToBottom() {
         return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 48;
     }
267  private void scrollToBottom() {
         SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));   // ★ 传入的是 max，不是 max-extent
     }
```

- `-48` 与 `PIN_SLACK` 数值一致，但这里是裸数字（新渲染器把它提成了常量）。
- `setValue(bar.getMaximum())` 依赖 `JScrollBar` 的钳位（超过 `max - extent` 会被截到）。
  能工作，但语义上是错的：若将来换成不自钳位的实现就会滚过头。

---

## §8 Markdown 层：`ui/markdown/*` + `MarkdownRenderer`

这一层只回答两个问题：**文本里有哪些块？每个块里有哪些样式片段？**
它不关心绘制，也不产生 HTML（除了 `MarkdownRenderer` 那条回退路径）。

```
Markdown 文本
   │ MarkdownParser.parseWithOffsets
   ▼
List<MarkdownBlock> + List<Integer> offsets
   │        （每个块 = 一条 record，内部已拆好 InlineSpan）
   ▼
BlockPanel.create → BlockView 子类 → InlineLayout.paint
```

### 8.1 `MarkdownBlock`（41 行）：sealed + 8 个 record

```java
// MarkdownBlock.java:10-40
10  public sealed interface MarkdownBlock {
14      record Heading(int level, List<InlineSpan> spans) implements MarkdownBlock {}
17      record Paragraph(List<InlineSpan> spans) implements MarkdownBlock {}
22      record ListItem(int level, String marker, List<InlineSpan> spans) {}
25      record ListBlock(boolean ordered, List<ListItem> items) implements MarkdownBlock {}
28      record CodeBlock(String language, String code) implements MarkdownBlock {}
31      record Quote(List<InlineSpan> spans) implements MarkdownBlock {}
36      record Table(List<List<String>> header, List<List<String>> rows) implements MarkdownBlock {}
39      record Divider() implements MarkdownBlock {}
    }
```

三个设计点：

1. **`sealed`**：编译器知道全部 8 个实现，所以 `BlockPanel.create` 与 `BlockView.plain` 的
   `switch` 不需要 `default`（§5.7）。新增块类型 = 编译错误 = 强制处理。
2. **`record` 提供 `equals`**：这是 `BlockPanel` 能「只重建尾部」的**唯一依据**（§5.7）。
   如果用普通类而不写 `equals`，每次 `setBlocks` 都会丢弃重挂全部视图。
3. **`Table.header` 是 `List<List<String>>` 而不是 `List<String>`**：为将来支持多行表头留位。
   当前 `TableView` 只取 `header().get(0)`（§9.7），`MarkdownParser.readTable` 也只写一行。
   ⚠️ 读代码时不要被类型误导成「已经支持多行表头」。

`ListItem` 不是 `MarkdownBlock` 的实现（它没有 `implements MarkdownBlock`），因为它只能存在于
`ListBlock` 内部——层级与符号都是列表的局部信息，不是独立的块。

### 8.2 `InlineSpan`（93 行）：不可变片段

```java
// InlineSpan.java:6-92
6   public final class InlineSpan {
        public final String text;
        public final boolean bold, italic, strike, code;
        public final String href;                 // 仅链接非 null
17      private InlineSpan(...)                   // 构造私有
27      public static InlineSpan plain(String text)
31      public static InlineSpan of(text, bold, italic, strike, code, href)
36      public static InlineSpan code(String text)
40      public static InlineSpan link(String text, String href)
44-54  withBold() / withItalic() / withStrike()   // 返回新实例
56      public boolean isLink() { return href != null; }
60      public boolean hasSameStyle(InlineSpan other)  // 比样式，不比 text
70      public boolean equals(Object) / 83 hashCode() / 88 toString()
    }
```

- **不可变**：`withBold()` 返回新对象而不是改自己。这使 `BlockPanel` 的前缀比较安全
  （旧块的 `spans` 不会被新一次解析默默地改掉）。
- **手写 `equals/hashCode`**（70-87）：`record` 不能用于这里（构造私有 + 静态工厂），
  而 `List<InlineSpan>.equals` 被 `Paragraph.equals` 依赖，所以必须正确实现。
- **`hasSameStyle` 与 `equals` 不同**：前者只比 5 个样式位（合并相邻同一样式片段用），
  后者连 `text` 一起比（块相等用）。两个方法在 `merge`（§8.6）与 `BlockPanel`（§5.7）
  里各自被调用，**不能互换**。

### 8.3 `MarkdownParser` 块级扫描（44-139）

单遍扫描，顺序**就是优先级**：

```java
44   parseWithOffsets(markdown):
45       归一化换行（\r\n / \r → \n）
46       split("\n", -1)            // -1 保留末尾空行
49       lineStarts(lines)           // 每行在归一化文本中的起始偏移
51-52    while (i < lines.length):

58-73    ① ``` 围栏代码：保留所有空白；未闭合的围栏也照收（流式中途）
75-78    ② 空行：跳过
80-84    ③ 分隔线：HR（--- / *** / ___）
86-93    ④ 标题：# 1-6 个 + 空格
95-108   ⑤ 引用：连续的 > 行合成一段（行间补一个空格）
110-114  ⑥ 表格：当前行是 |…| 且下一行是分隔行 → readTable
116-122  ⑦ 列表：UL_ITEM / OL_ITEM → readList
124-136  ⑧ 其余：普通段落，连续行合并（行间补一个空格）
```

几个必须记住的细节：

- **① 先于一切**：围栏内部的 `#` 不是标题、`|` 不是表格。若把标题判断放在前面，
  代码块里的注释会被当成标题。
- **① 的「未闭合也收」**（67-69）：`if (i < lines.length) i++;` 才跳闭合围栏。流式输出到
  半个代码块时，用户看到的是「已经是一个代码块」而不是「一堆散文本」。`MarkdownParserTest`
  的 `openFenceStillRendersWhileStreaming` 就是测这个。
- **⑤ 的行间空格**（100-103）：`quote.append(' ')` 只在「已有内容且新行非空」时加，
  避免多行引用的末尾多一个空格。
- **⑦ 用 `raw` 而不是 `trimmed` 匹配**（116-117）：列表缩进有意义，必须先看缩进。
  而 ①–⑥ 用 `trimmed`，因为这些块的缩进无意义。
- **⑧ 的终止条件**（128）：`!startsBlock(lines, i)`。`startsBlock`（159-170）把 ①–⑦ 的
  开头条件再判一遍——**这是本文件第二处「规则重复」**（第一处是与 `MarkdownRenderer` 的重复）。
  两处必须同步，否则会出现「段落提前结束」或「把标题吃进段落」。

`lineStarts`（147-155）是增量解析的基石：

```java
147  int offset = 0;
     for (i) { starts[i] = offset; offset += lines[i].length() + 1; }   // +1 = 那个被 split 掉的 '\n'
```

⚠️ 它假定归一化后每行只由 `\n` 分隔。`split("\n", -1)` 保留末尾空行时，最后一行的
`length` 为 0，算出的偏移正好等于文本长度——边界正确，但依赖 `+1` 这个假设。

### 8.4 `readList`（175-213）：四类行，三种处置

```java
180  while (i < lines.length) {
185-187  无序项 → ListItem(indentLevel(缩进), "•", inline(内容))
188-191  有序项 → ordered = true; ListItem(indentLevel(缩进), n + ".", inline(内容))
192-198  空行     → nextListItem(i+1)：后面还有列表项就跳过去继续，否则 break
199-205  缩进续行 → 并入上一条：last.spans + " " + inline(trimmed)
206-208  其余     → break（列表结束）
     }
211  整个列表作为一个 ListBlock 块加入
```

- **`indentLevel`（273-279）**：Tab 算 4 列，每 2 列一级，上限 3 级。
  即 `3 级 ← 6 空格`，`2 级 ← 4 空格`，`1 级 ← 2 空格`。`Math.min(3, …)` 是硬限制：
  再深就靠 `ListView` 的 `STEP = 16` 继续缩进，但层级不再增长（渲染上看着像平级）。
- **`marker` 在解析时确定**（186-187）：无序固定为 `•`（U+2022），有序为 `"n."`。
  📌 这意味着渲染阶段不需要知道「第几项」，`ListView` 只管画 `item.marker()`。
- **空行的 lookahead**（192-198）：列表中间的空行不结束列表（标准 Markdown 的行为）；
  `nextListItem`（215-225）跳过连续空行后看下一行是不是列表项。
- **续行合并的字符代价**（201-205）：每次合并都 `items.remove(last)` 再 `add` 新对象，
  `ArrayList` 的尾部增删是 O(1)，但生成了新的 `InlineSpan` 列表。对一个列表来说可忽略。

### 8.5 表格（110-114, 227-271）

三件套：

```java
241  isTableRow(line)      = startsWith("|") && endsWith("|") && length > 2
245  isTableSeparator(line) = 只含 - | : 空白，且去掉 | : 空格后长度 >= 3
258  splitRow(line)         = 去掉首尾 |，split("\\|", -1)，逐格 trim
```

- **`isTableSeparator` 为什么要 `>= 3`**（255）：`| - |` 只有一个连字符时，宁可不识别为表格
  （避免把普通的 `| x |` 文本误判）。`---` / `:-:` 这类才达标。
- **`readTable`（227-239）从 `start + 2` 开始**：跳过分隔行。表格只吃「连续以 `|` 开头和结尾」的
  行，遇到其它行就停。
- ⚠️ **单元内不支持转义 `\|`**：`split("\\|")` 会把 `a\|b` 切成两格。这是一个已知限制，
  模型几乎不会输出转义管道符，所以未处理。

### 8.6 行内解析（289-359）：一次扫描、八个分组

```java
26  INLINE = `([^`\n]+)`                                   // 组 1：行内代码
         |\[([^\]\n]+)]\(([^)\s]+)\)                   // 组 2：链接文字；组 3：href
         |\*\*(?=\S)(.+?)(?<=\S)\*\*                   // 组 4：**粗体**
         |(?<![\w])__(?=\S)(.+?)(?<=\S)__(?![\w])       // 组 5：__粗体__
         |(?<!\*)\*(?=\S)([^*\n]+?)(?<=\S)\*(?!\*)     // 组 6：*斜体*
         |(?<![\w])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w])   // 组 7：_斜体_
         |~~(?=\S)(.+?)(?<=\S)~~                       // 组 8：~~删除线~~
```

**为什么用单一交替式正则而不是 7 个正则依次 `replace`**：

1. **顺序与优先级**：`Matcher.find()` 从左到右扫，先匹配到的优先。行内代码排在第一位，
   所以 `` `**a**` `` 得到的是一个 code 片段而不是粗体。
2. **`(?=\S)` / `(?<=\S)` 成对出现**：标记内侧必须是非空白，所以 `a * b * c` 不会被当成斜体。
3. **`(?<!\w)__(?!\w)`**：`__init__` 这类 Python 标识符不会被当成粗体。
4. **`(?<!\*)\*(?!\*)`**：单星号不能是双星号的一部分，避免 `**bold**` 被斜体先吃掉。

`inlineInternal`（300-329）的循环：先生成「匹配前的纯文本」，再根据哪个 `group` 非空建片段，
最后 `merge`（343-359）把相邻同样式片段粘起来。

```java
289  public static List<InlineSpan> inline(String text) {
293-297  try { return inlineInternal(text); }
         catch (RuntimeException e) { return List.of(InlineSpan.plain(text)); }   // ★ 降级
     }
```

📌 **`catch RuntimeException` 的取舍**：正则回溯爆炸、`substring` 越界这类 bug 会让一整轮对话的
渲染变成异常堆栈。降级为纯文本后，用户至少能看到内容（只是没有粗体和链接）。
这是「渲染层不应因解析 bug 而白屏」的具化。

### 8.7 `IncrementalParser`（101 行）：只重解析尾部

```java
36  public List<MarkdownBlock> feed(String newText) {
37-40    if (next.equals(text)) return blocks;                        // ① 没变：同一实例
43-46    if (!next.startsWith(text) || text.isEmpty()) { adopt(全量解析); return blocks; }  // ② 非追加
48       int keep = blocks.size() - 1;                                 // ③ 保留除末尾块外的所有块
51-60    循环：from = keep<=0 ? 0 : offsets.get(keep);
             tail = parseWithOffsets(next.substring(from));
             若 tail 首块与 blocks[keep-1] "可合并" → keep-- 再试（最多 MAX_BACKOFF = 3 次）
62-69    合并：blocks[0..keep) + tail（offsets 加 from 重定位）
}
```

**① 「没变就返回同一实例」**（37-40）：不是微优化。`AgentMessageView.setMarkdown` 把结果交给
`BlockPanel.setBlocks`，若返回新列表，`List.equals` 虽然能比出相等，但**列表内容全等时
`mounted.get(i).equals(blocks.get(i))` 仍为 true**，所以不影响前缀比较。它省的是解析开销。

**② 非追加式变化**（43-46）：`!next.startsWith(text)` 包括「历史回放」「清空后重写」
「用户编辑了已上屏的内容」。此时增量窗口不可用，必须全量。

**③ 为什么要保留「除末尾块外」**（48）：流式追加时，最后一块可能正写到一半
（例如半个表格、半个列表）。它一定会变，所以不参与复用。

**「可合并」判定 `mergeable`（85-94）** 只有两种情况：

| 旧尾块 | 新首块 | 为什么要回退 |
| --- | --- | --- |
| `ListBlock` | `ListBlock` | 新的一行 `- ` 到来，应与上面的列表合成一个块 |
| `Table` | `Paragraph` / `Table` | 半行表格先是普通段落，竖线补齐后被表格吃掉 |

`MAX_BACKOFF = 3`（20）是「合并最多影响紧邻一块，留几步余量」：多回退几块不会错，
只是多解析几行；回退不够则会产出「两个半截块」，在屏幕上表现为列表中间断开。

`offsets` 的重定位（67-69）是 `from + offset`：因为 `parseWithOffsets` 拿到的是
**子串**，它算出的偏移是相对子串的，必须加回窗口起点。

### 8.8 `MarkdownRenderer`（389 行）：HTML 回退实现

只在 `ClassicTranscript` 里被调用（§7.3）。它的规则与 `MarkdownParser` **一一对应**
（注释 9-11 明说），但输出是 HTML 3.2 子集。

| 方面 | 实现要点 | 行 |
| --- | --- | --- |
| 整体包装 | `toHtml` 输出 `<style>` + `renderBody` | 30-41 |
| 调色板 | `Palette.current()` 每次渲染现读 `UiTheme`，所以切主题后重绘就变 | 342-379 |
| 标题 | `heading(level, text)` 生成 `<h1>`–`<h6>`，字号/上下边距内联 | 140-151 |
| 列表 | **渲染成两列表格**：左列符号（`&nbsp;` × `level*3`），右列文字 | 153-199 |
| 引用 | 左侧竖条用表格背景色 + `padding` 模拟 | 213-218 |
| 代码块 | `<table bgcolor>` + `<pre>`（Swing 不认 `border-radius`） | 220-228 |
| 表格 | `<table border=0>` + 表头底色 | 230-256 |
| 行内 | 先 `escape`，再用 `\u0001i\u0001` 占位保护行内代码，替换链接/强调后还原 `<code>` | 304-330 |

**为什么列表要用表格**（153-199）：HTML 3.2 没有 `display: flex`，也没有可靠的 `margin-left`
嵌套缩进。两列表格是唯一能同时做到「悬挂缩进」+「续行对齐」的办法。
`&nbsp;` 数目 = `level * 3`（290-296 的 `indentLevel`）。

**行内为什么要占位符**（304-330）：如果先把 `` `a*b*` `` 变成 `<code>a*b*</code>`，后面的斜体
替换会把它中间的 `*b*` 也换掉。先把行内代码抽成占位符，所有强调替换跑完后再还原，
才能保证「代码里的标记不算标记」。

⚠️ **两份规则必须同步**（🔁 §17.4）：

| 规则 | `MarkdownParser` | `MarkdownRenderer` |
| --- | --- | --- |
| 标题 | `HEADING`（19） | `HEADING`（20） |
| 无序项 | `UL_ITEM`（20） | `UL_ITEM`（21） |
| 有序项 | `OL_ITEM`（21） | `OL_ITEM`（22） |
| 分隔线 | `HR`（22） | `HR`（23） |
| 表格行 | `isTableRow`（241） | `isTableRow`（258） |
| 表格分隔 | `isTableSeparator`（245） | `isTableSeparator`（262） |
| 缩进级 | `indentLevel`（273） | `indentLevel`（290） |

两边的 `OL_ITEM` 已经不一致：解析器是 `^(\s*)(\d+)[.)]\s+(.*)$`（只认 `1.` / `1)`），
而渲染器是 `^(\s*)\d+[.)]\s+(.*)$`（不捕获数字，并在 `orderedPrefix`（298-302）里自己
重建序号）。目前行为一致，但这种「两份正则各自演化」的格局是长期风险。

---

## §9 块视图族：`ui/chat/*View` + `InlineLayout`

十个类，一个共同骨架：`BlockView extends MeasuredComponent`，只实现 `computeHeight` 与
`paintBlock`。整个家族的复杂度全在 `InlineLayout` 里——它把「一段带样式的文本」变成可换行、
可点击、可分行的 `TextLayout` 列表。

### 9.0 家族总览

| 块 | 视图 | 行数 | 关键常量 | 光标 |
| --- | --- | ---: | --- | --- |
| `Paragraph` | `ParagraphView` | 33 | — | ✔ 末尾 |
| `Heading` | `HeadingView` | 41 | h1/h2/h3 字号 + topGap 20/16/14 | ✔ |
| `ListBlock` | `ListView` | 84 | `STEP=16` `GUTTER=20` `ITEM_GAP=4` | ✔ 最后一项 |
| `Quote` | `QuoteView` | 39 | `bar=3` `indent=12` `padY=4` | ✔ |
| `Divider` | `DividerView` | 26 | 固定高 13 | ✘ |
| `Table` | `TableView` | 117 | 列宽最小 48、`cellPad=6` | ✘ |
| `CodeBlock` | `CodeBlockView` | 178 | `MAX_COLLAPSED_LINES=18` | ✘ |
| （非块） | `NoticeView` / `ErrorView` | 173 / 11 | 左侧条 3px | ✘ |
| （工具） | `InlineLayout` / `TextCopy` | 262 / 52 | — | — |

### 9.1 `InlineLayout`（261 行）：整个渲染栈的地基

类注释把理由说得很清楚（17-21）：`TextLayout` 提供中英混排换行、每段独立字体与颜色、
链接下划线、点击命中，且不经过 Swing 的 HTML 引擎。

**字段与缓存**：

```java
24  private static final FontRenderContext FRC = new FontRenderContext(null, true, true);  // AA + 分数度量
34-36  lines / lineStarts / lineTops          // 排版结果
37-39  measuredWidth = -1; height; lineGap = 2
41-43  builtFont / builtText / builtMuted     // 建 attributed 时的快照，用于判断“要不要重建”
```

**`measure(availableWidth)`（68-101）** 是唯一的排版入口：

```java
68  ensureAttributed();
70-73  if (width == measuredWidth) return height;         // ① 宽度未变：直接返回缓存
75-77  lines/lineStarts/lineTops 清空
79-80  LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(), FRC);
84-98  while (position < end) {
         TextLayout layout = measurer.nextLayout(width);
         if (layout == null) break;
         lines.add(layout); lineStarts.add(position); lineTops.add(y);
         y += ascent + descent + leading + lineGap;     // ② 行高 = 字体度量 + 2px 行距
         int next = measurer.getPosition();
         if (next <= position) break;                    // ③ 防死循环
         position = next;
     }
99   height = lines.isEmpty() ? 0 : max(1, round(y - lineGap));
```

- **② `lineGap = 2`**：中文行距偏紧时阅读拥挤，加 2px。它不是 `line-height`，而是每行之后
  额外增加的像素，所以最后要 `- lineGap`（最后一个行距不算进高度）。
- **③ `next <= position` 的防御**：`nextLayout` 在「宽度小于单个字符」时可能不前进。
  没有这个判断，`while` 会变成死循环，整个 EDT 卡死。

**样式装配 `ensureAttributed`（196-256）**：

```java
197-199  textColor = colorOverride != null ? colorOverride.get() : (muted ? UiTheme.muted() : UiTheme.text())
200      linkColor = UiTheme.ACCENT
201-205  if (attributed != null && builtFont == font && builtText.equals(textColor) && builtMuted.equals(linkColor)) return;
```

四个要点：

1. **按「字体实例 + 两个颜色」判断重建**。主题切换时 `UiTheme.font()` 会返回新实例，
   自然触发重建；同一主题下反复 `measure` 不会重复建 `AttributedString`。
2. **`colorOverride`（55-59）**：用户气泡在蓝色底上写白字，颜色不能用 `UiTheme.text()`。
   用 `Supplier<Color>` 而不是 `Color`，保证了「主题变了颜色也跟着变」（注释 44-46）。
3. **样式叠加顺序**（232-250）：`code` 优先（换等宽字体）→ `bold+italic` → `bold` → `italic`；
   删除线与链接是独立属性，可与字体共存。
4. **空文本也建一个 `" "`**（215-219）：否则 `getIterator()` 会抛异常。`spanEnds = new int[0]`
   让后续 `spanIndexAt` 安全返回 -1。

**链接命中（152-192）**：

```
linkAt(x, y)
  → characterIndexAt(x, y)：逐行比较 lineTops/lineHeight，命中行后 layout.hitTestChar(x, y)
     返回 lineStarts.get(i) + hit.getInsertionIndex()   // 转换为全文下标
  → spanIndexAt(characterIndex)：在 spanEnds 里找第一个 > index 的片段
  → 该片段是链接？返回 href : null
```

📌 **`lineStarts` 存的是「字符下标」而非像素**，因为 `TextLayout` 的 hit test 返回的是
**该行内的**插入位置，必须加上行首的全局下标才能映射回片段。

**`paintCaret`（136-148）**：只在最后一行末尾画 2px 竖条，高度 = ascent+descent。
坐标用 `last.getAdvance() + 1f`——即最后一个字形的右边。

**`paint`（124-131）** 返回 `y + max(height, 1)`，让调用方可以链式往下排（如 `ListView`）。

### 9.2 `ParagraphView`（33 行）

最短的块视图：

```java
15  ParagraphView(List<InlineSpan> spans) {
        layout = new InlineLayout(spans, UiTheme.font(), false);
        TextCopy.install(this, () -> plain(spans));
        setCaretTarget(layout, 0, 0);
    }
24  computeHeight(width) = layout.measure(width)
29  paintBlock → layout.paint(g2, 0, 0); paintCaret(g2)
```

📌 段落没有上下留白。块间距由 `BlockListLayout(gap = scale(12))` 统一提供——
这是「布局负责间距、视图负责内容」的分工。

### 9.3 `HeadingView`（41 行）

- 字体：`level == 1 → UiTheme.h1()`，`2 → h2()`，其余 → `h3()`。
- 上间距：h1 20 / h2 16 / h3 14（按级别递减），下间距为 0（同样交给块间距）。
- 颜色：`UiTheme.text()`，没有单独的标题色。

⚠️ `MarkdownParser` 接受 `#` 到 `######`（1-6 级），但 `HeadingView` 只区分前三级，
`####` 以上与 `###` 完全一样。这是一个**有意简化**（对话里的四级标题几乎没有），
但读代码时容易误以为丢了样式。

### 9.4 `ListView`（83 行）：悬挂缩进

```java
16-18  STEP = 16     // 每级缩进
       GUTTER = 20   // 符号列宽
       ITEM_GAP = 4  // 项间距
39-58  computeHeight(width):
         对每项：
           layout = new InlineLayout(item.spans(), UiTheme.font(), false)
           textWidth = width - item.level()*step() - gutter()
           layout.measure(max(24, textWidth))          // ★ 宽度下限 24
           tops.add(y); y += layout.height() + ITEM_GAP
         光标目标 = 最后一项的 (level*step + gutter, top)
         总高 = max(1, y - ITEM_GAP)
```

`paintBlock`（62-82）的关键几何：

```java
67  int left = item.level() * step();                 // 每级左缩进
73  int markerRight = left + gutter() - scale(6);     // 符号右边缘
77  g2.drawString(marker, markerRight - markerWidth, baseline);   // ★ 右对齐到 fixed 列
79  layout.paint(g2, left + gutter(), top);           // 正文左边缘
```

📌 **符号右对齐**是悬挂缩进的关键：`•` 与 `10.` 宽度不同，若左对齐，正文的起始位置会随序号
宽度跳动。右对齐到 `left + gutter - 6` 后，正文始终从 `left + gutter` 开始。

⚠️ `Math.max(24, textWidth)`（46）在窗口极窄、且嵌套很深时会导致文字溢出（因为 `layout`
按 24px 排，但 `paint` 时可用宽度只有十几 px）。窗口宽 < `level*16 + 20 + 24` 时可复现。

### 9.5 `QuoteView`（39 行）

```java
15-17  bar = scale(3); indent = scale(12); padY = scale(4)
28     computeHeight = layout.measure(width - indent) + padY*2
33     paintBlock: 左边缘 3px 竖条（UiTheme.BORDER）+ layout.paint(g2, indent, padY)
```

⚠️ 测量用 `width - indent`（28），而 `paint` 的正文从 `indent` 开始——两者一致，但**都没有
减去 ``bar`` 的 3px**。竖条画在 `[0, 3)`，正文从 `[12, …)` 开始，所以 3px 没有被利用但也不重叠。
这是有意的（留 9px 空白）。

### 9.6 `DividerView`（26 行）

固定 13px 高，在垂直中线画一条 `UiTheme.BORDER` 细线。不参与光标。
📌 固定高度而不是 `lineHeight`，因为它与字体无关。

### 9.7 `TableView`（116 行）：自绘网格

```java
40-44  columns = max(1, header.size, 每行 size)
       columnWidth = max(scale(48), width / columns)      // ★ 均分，但有 48px 下限
46-60  headerLayouts = layoutsFor(header, bold=true); headerHeight = 最高单元格 + cellPad*2
       逐行：rowLayouts/rowTops；y += 行高
64-75  layoutsFor(cells, bold): 每列一个 InlineLayout（单 span），measure(columnWidth - cellPad*2)
77-83  rowHeight = max(各单元格高度) + cellPad*2        // ★ 行高取最高格
```

`paintBlock`（86-115）画四样东西：

```java
88-90  表头：fillRoundRect(0,0,width,headerHeight) + fillRect(0, headerHeight/2, width, …)
       // 先把下半段用矩形补齐，再加圆角顶——一个廉价的“只上圆角”技巧
94-98  列分隔线：column * columnWidth 处一条竖线
99     表头下边线
100-103 每行上边线
105-107 表头文字：column*columnWidth + cellPad, cellPad
108-113 单元格文字：column*columnWidth + cellPad, rowTop + cellPad
```

⚠️ **三个已知限制**：

1. **`columnWidth` 均分导致总宽可能超出**：`max(48, width/columns)` 在列多时（`columns > width/48`）
   使总宽 > `width`，右侧列会被裁掉。
2. **表头圆角是近似的**（89-90）：先画一个圆角矩形，再用普通矩形覆盖下半段。在表头高度小于
   半径时会出现一条异色边。
3. **单元格不识别行内标记**（69）：`InlineSpan.plain(cell)`，所以表格里的 `**粗**` 是字面量。
   解析器 `splitRow` 也没保留标记样式（只保留文本）。

### 9.8 `CodeBlockView`（177 行）：裁切而不折行

类注释（13-17）给出了一个明确的产品判断：**折行的代码比截断的代码更难读，而复制按钮永远拿到完整原文**。

```java
20   MAX_COLLAPSED_LINES = 18
39   lines = code.split("\n", -1)（空代码给一个空行）
93-116 computeHeight:
        headerHeight = max(mono 行高, small 行高)
        copyArea = (width - pad - copyWidth, pad, copyWidth, headerHeight)
        visibleLines = expanded ? lines.length : min(lines.length, 18)
        toggleHeight = 可折叠 ? lineHeight : 0
        bodyTop = pad + headerHeight + scale(4)
        高度 = bodyTop + visibleLines*lineHeight + toggleHeight + pad
```

绘制（119-159）：圆角底（`SUBTLE`）→ 语言标签（空则显示 `code`）→ 复制/已复制（`ACCENT` / `OK`）→
`clipRect` 后逐行 `drawString` → 底部「展开（共 N 行）/ 收起」。

**`truncate`（161-176）用二分查找**：

```java
165-174  在 [0, line.length()] 里二分，找最长的“宽度 <= available - “…”宽度”的前缀
175      返回 前缀 + “…”
```

📌 逐字累加 `stringWidth` 是 O(n²)（每个前缀都重算整行宽度）。二分把它降到 O(n log n)。
对于超长单行（压缩后的 JS）差距明显。

**复制反馈**（75-86）：`copied = true` + 换文案 + `1200ms` 后恢复。
⚠️ 它创建**新的 `Timer` 对象**而不是复用一个字段（对比 `NoticeView` 的 `flash` 字段）。
连续快速点复制时，旧定时器仍会到点执行，把 `copied` 改回 false。影响极小（多一次 `repaint`），
但属于「定时器未取消」的同类问题。

### 9.9 `NoticeView`（172 行）与 `ErrorView`（11 行）

`NoticeView` 是唯一被**两种行**复用的视图：对话流里的提示（`Tone.INFO`）、
空状态下的 hint（`Tone.HINT`）、错误（`ErrorView` 继承并固定 `ERROR`）。

| 元素 | 值 |
| --- | --- |
| 左侧色条 | `bar = 3px`，`ERROR → ERROR` / `OK → OK` / `INFO`、`HINT → ACCENT@120` |
| 内缩 | `indent = 10`，`padX = 10`，`padY = 6` |
| 底色 | `ERROR → ERROR_SOFT`；其余 `SUBTLE@200` |
| 字体 | `UiTheme.small()`，`muted=true`（默认色走 `UiTheme.muted()`） |
| 复制动作 | hover 时右侧浮现「复制」，点击后 1.2s 内显示「已复制」 |

三个几何细节：

1. **左侧条的圆角**（108-114）：先 `clip(new RoundRectangle2D(...))` 再 `fill(Rectangle2D)`，
   使 3px 竖条上端跟随卡片圆角，而不是把圆角切成直角。
2. **`contentWidth`（84-88）**：`min(textWidth, layout.maxAdvance()) + indent + padX*2`。
   📌 它先 `layout.measure(textWidth)` 再取 `maxAdvance()`，所以「短提示不拉满整行」——
   这需要 `computeHeight` 用同一个 `textWidth` 算，两处都写了 `max(40, width - indent - padX*2)`。
3. **`copyArea` 在 `computeHeight` 里更新**（96-98）：因为它依赖 `height`，而 `height` 只在测量后
   才知道。这是「测量时顺带算命中区」的写法（`CodeBlockView`、`ToolCallCard` 同样）。

`ErrorView`（6-10）只有三行有效代码：`super(text, Tone.ERROR)`。📌 继承而不是参数化，是
因为调用方想要的是「这是一个错误」而不是「一个带 ERROR 语气的通知」。

### 9.10 `TextCopy`（51 行）：复制能力的最小实现

```java
18  public static TransferHandler handler(Supplier<String> text) {
        return new TransferHandler() {
21          getSourceActions → COPY
26          createTransferable → new StringSelection(text.get())
        };
    }
33  public static void install(JComponent c, Supplier<String> text) {
        c.setTransferHandler(handler(text)); c.setDragEnabled(true);
    }
39  public static void copyToClipboard(String text) {
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
    }
```

📌 **为什么用 `TransferHandler` 而不是 `KeyEvent` 监听**：`TransferHandler` 同时提供「Ctrl+C」
与「拖拽到外部应用」两种能力，且自带 `JComponent` 的焦点判断。用键盘监听会在输入框里抢走
Ctrl+C。

🔁 **每个自绘块都调了 `TextCopy.install`**：`ParagraphView`、`HeadingView`、`ListView`、
`TableView`、`CodeBlockView`、`NoticeView`、`StepGroupView`、`AgentMessageView`、
`EmptyState`。这就是为什么「选中不了文字但能复制」——所有块视图都是不可选中的自绘组件。

---

## §10 卡片与附属组件

八个组件，共同点：**它们是对话流里的“行”，而不是块**。它们直接继承 `ChatRowView`
（或 `MeasuredComponent`），自己算高度、自己画、自己处理鼠标。

### 10.1 `AgentMessageView`（274 行）：助手回答卡片

```
┌───────────────────────────────────────────────┐
│ [Agent]  5.2s · ↑1.2k ↓0.8k            复制   │  ← 头部（自绘，非组件）
│ ─────────────────────────────────────────── │  bodyTop
│  ┌─ BlockPanel ─────────────────────────┐   │
│  │ ParagraphView / ListView / …          │   │  ← 真正的 Markdown 内容
│  └───────────────────────────────────────┘   │
└───────────────────────────────────────────────┘
```

| 方法 | 行 | 要点 |
| --- | --- | --- |
| `setMarkdown` | 54-59 | `parser.feed(text)` → `body.setBlocks` → `contentChanged()` |
| `setMeta` | 61-67 | 内容变了才 `contentChanged`（避免每帧失效） |
| `setStreaming` | 79-101 | 启动/停止 500ms 闪烁器，控制光标可见性 |
| `computeHeight` | 135-142 | `max(bodyTop + bodyHeight + padY, bodyTop + 一行高 + padY)` |
| `layoutHeader` | 146-157 | 算 chip 与复制按钮的矩形（都是绘制，不是组件） |
| `doLayout` | 164-169 | 只给 `body` 设 bounds：`(padX, bodyTop, innerWidth, bodyHeight)` |

三个细节：

1. **`isEmpty()`（69-71）** 用 `body.plainText().isBlank()`。`ChatView.finishAssistantInternal`
   判定空行用的是 `buffer.length() == 0`——两者不完全等价（全是空白的正文会让 `isEmpty` 为 true
   但 buffer 不为空）。目前不影响，因为空白正文会渲染成一个空段落。
2. **`removeNotify`（104-112）停两个定时器**：`blink`（光标）与 `copyFlash`（复制反馈）。
   行被 `trimOldRows` 卸载时可能正在流式，不停表就是「界面已经没这个组件了，定时器还在 repaint」。
3. **`setStreaming(false)` 会把 `caretOn` 置 false**（98-99），所以收尾后光标一定消失。
   但 `body.setCaretVisible` 只对 `tailView()` 生效（§5.7）。

### 10.2 `UserMessageView`（119 行）：要“贴字”的气泡

```java
18  RATIO = 0.72
28  UserMessageView(text, attachmentNames):
32-38     按 "\n" 拆行；空行变成 " "（否则 InlineLayout 会得到空文本 → 高度 0）
36       每行 setColorOverride(UserMessageView::bubbleForeground)
42-48     有附件时追加一行斜体说明，颜色 = bubbleForeground@190
50       TextCopy.install(clipboard 内容 = 正文 + 附件清单)
```

**`darkSurface()`（67-72）** 是本文件最巧的 6 行：

```java
68  Color surface = UiTheme.surface();
69-71  luminance = 0.299R + 0.587G + 0.114B（ITU-R BT.601）
72     return luminance < 128;
```

- 深色主题：气泡用 `ACCENT@48`（半透明蓝），文字用 `UiTheme.text()`（亮色）。
- 浅色主题：气泡用实底 `ACCENT`，文字纯白。

📌 **为什么深色不用实底**：白字压在饱和蓝上在暗背景里“发光”，长时间阅读刺眼。
改用半透明底后对比度降低但更耐看。更关键的是 **文字色是 `Supplier`**（§9.1），
切主题时不需要重建任何对象。

**`contentWidth`（79-87）** 是「气泡贴字」的实现：

```java
80   textWidth = maxWidth - padX*2
82-85  逐行 measure(textWidth)，取最大的 maxAdvance()
86  return min(textWidth, widest) + padX*2
```

⚠️ 它用 `maxAdvance()`（最宽一行）而不是「所有行累加」，所以多行文本的气泡宽度 = 最宽那一行。
这是正确的（否则会拉满）。

### 10.3 `StepGroupView`（206 行）：把刷屏的工具行折成一行

```java
26-28  pad = 8, itemGap = 5, dotColumn = 18
51-61  add(line, failed)：items.add；failed 则 failedCount++；layouts.clear()
89-100 summary() = AgentBundle("ui.steps.count") + " · " + 状态（running / failed / done）
       状态取 ui.steps.running|failed|done，三者在中文/英文包里同为英文
94     failed 的判定：failedCount > 0（由调用方传入，不再看文本）
115-128 computeHeight：折叠时只算 header；展开时累加每个 InlineLayout
133-169 paintBlock：
         header 背景（展开或 hover 时 SUBTLE@200）
         左側 chevron（UiIcons.paintChevron）
         时间线：每项一个小圆点 + 竖线（最后一项无竖线）
         layout.paint(g2, dotColumn, y)
```

- **折叠/展开只由点击 header 触发**（176-182），hover 只是底色提示。
- **`count()` 而不叫 `size()`**（65-67）：`Component.size()` 已存在且返回 `Dimension`，
  同名会覆写成错的方法签名。注释里专门写了这一点。

📌 **3.3.0 修正：失败不再靠 `contains("失败")` 嗅探**。工具行改成英文日志之后，
`[工具] xxx 失败：…` 这种串已经不存在了；现在 `addToolLine(text, running, failed)` 把
`failed` 直接传给 `StepGroupView.add`，`failedCount` 仅增不减。历史教训（字符串里找词）
与 `isRunningTool` 一样留在 §6.7。

### 10.4 `ToolCallCard`（387 行）：MCP 调用的就地确认

为什么不用模态框，类注释（18-22）说得很清楚：模态框遮住了“为什么要调这个工具”的上下文，
而且只能用鼠标答。卡片留在流里，Enter/Esc 就能答。

```java
27   MAX_ARG_LINES = 4           // 参数只展示前 4 行
53-63 构造：server/tool/args（空则 "MCP" / "" / "{}"）、setFocusable、tooltip
77-86 decide(boolean)：只生效一次 → contentChanged() → decisionHandler
90-96 addNotify：invokeLater(requestFocusInWindow)   // 卡片一出现就能用键盘
99-106 removeNotify：未答复则 decide(false)          // ★ 不让 Core 干等
114-123 ensureLayout(width)：按宽度折行 desc 与 args（缓存 layoutWidth）
125-132 clipArgs：超过 4 行就截断，最后一行补 " …"
144-162 computeHeight：标题 + 描述 + “参数”标签 + 参数块 + 按钮行
307-324 installKeys：Enter → allow，Esc → deny（WHEN_FOCUSED）
```

**几何要点**：

```
height = padY + 标题高 + gap + desc高 + gap + label高 + args高 + gap + 按钮高 + padY
按钮行：右下角 [拒绝] [允许]，左侧同一行画键盘提示
argsArea = (padX, rowTop - gap - argsHeight, innerWidth, argsHeight)
```

**绘制先后**（195-237）：圆角底 + 描边 → 裁剪后画左侧状态条（等待：`WARN`；已答：`OK` / `ERROR`）
→ 标题 → 描述（`muted`）→ “参数”小字 → 参数块（`SUBTLE` 底 + 等宽字）→ 页脚。

📌 **答复后按钮换成结论文字**（260-267）：卡片会永久留在对话里，成为“当时做了什么决定”的记录。
这比“点完就消失”更符合审计需求。

⚠️ **双击风险**：`decide` 有 `decision != null` 守卫，键盘与鼠标同时触发也只会回调一次。
但 `removeNotify` 里的 `decide(false)` 会**在卡片因任何原因离开组件树时触发**——包括用户
切换到其他标签页（`CardLayout` 会把该会话的所有组件移出可见层？实际不会，但 `ClassicTranscript`
回退时就会）。所以“切标签 = 自动拒绝”是一个必须知道的行为。

### 10.5 `AskCard`（627 行）：提问卡

数据模型：

```java
42-71   private static final class Q（每题一个）：
          header/text/labels/descriptions/multi/allowCustom（不可变输入）
          picked（LinkedHashSet<Integer>，保持点击顺序）/ custom（bool）
          lines / optionLines / optionRects / customRect / fieldRect / field
          titleY / linesY          // ← 排版结果
75-79   public static final class Answer：picked（List<List<String>>）/ custom（List<String>）/ skipped
```

**三种结束方式**：

| 方法 | 行 | 语义 | 发送的内容 |
| --- | --- | --- | --- |
| `submit()` | 139-159 | 用户点“提交”/Enter | `picked` 转回标签文本，`custom` 取输入框 |
| `skip()` | 163-174 | 用户点“跳过”/Esc/**卡片被移除** | `skipped = true`，其余为空 |
| `withdraw()` | 178-185 | Core 取消（`ask/withdraw`） | 不回调，只标记已失效 |

📌 **`skip()` 与 `withdraw()` 的区别**：前者会回调 `answerHandler`（告诉 Core「按你的假设继续」）；
后者不回调（Core 已经不等了）。两者都把卡片置为 `answered`，阻止后续点击。

**排版 `ensureLayout`（213-263）**：

```
y = PAD_Y
y += 标题高 + GAP
对每题 q：
  q.lines = wrap(q.text, body, inner - INDENT)
  q.titleY = y                                         ← ★ 只赋值了 titleY
  y += q.lines.size()*行高 + GAP
  每个选项：optionLines / optionRects（高 = max(ROW_H, 行数*小字高 + 8)）
  「其它」：customRect（高 ROW_H）
  若已勾选「其它」：fieldRect（高 FIELD_H）+ 间距
  y += GAP
rowTop = y
submitArea = 右下角；skipArea = submitArea 左侧
```

**鼠标命中用一个整数编码**（477-500）：

```
-3 提交    -2 跳过    -4 无命中（也是“已答复”）
100*(qi+1) + oi   → 第 qi 题的第 oi 个选项
100*(qi+1) - 1    → 第 qi 题的「其它」
```

📌 这个编码同时用作 `hovered` 与 `hit()` 的返回值，省了一个「哪个控件”的枚举。
代价是可读性（`hovered == -3` 需要查表），但代码里就两处。

**「其它」用真实 `JTextField`**（596-626）：

```java
603  q.field = new JTextField();
604  q.field.putClientProperty("JTextField.placeholderText", …);
607  q.field.addActionListener(e -> submit());        // 输入框里回车 = 提交
608-615  Esc → skip
617  add(q.field);                                   // 作为子组件加入
```

📌 这是整个自绘体系里少数**直接嵌入 Swing 组件**的地方。理由：IME、粘贴、选中、
右键菜单这些行为自己实现不划算。`doLayout`（300-308）里专门给它设 bounds。

⚠️ **已确认的缺陷：`linesY` 从未赋值**（§19.1）：

```java
60   int linesY;                       // 声明
titleY = y;                           // 227 行，ensureLayout 只赋 titleY
int y = q.linesY;                     // 362 行，paintQuestion 读 linesY
```

因为 `int` 默认值 0，**问题正文总是画在 y = 0**，与标题 chip 重叠。修正方式：
在 227 行旁加 `q.linesY = y + 标题高 + GAP`（即正文应在的位置），或在 `paintQuestion` 里
直接用 `q.titleY + 标题高 + GAP`。这是一个肉眼可见的布局 bug，也是本卷里最“值得修”的一处。

⚠️ **另一处轻微不一致**：`hit()` 在已答复时返回 `-4`（479-481），而 `mouseExited` 判断
`hovered != -4` 才重置（535-540）。因为 `-4` 同时是“无命中”与“已答复”，逻辑能跑通，
但两个语义共用一个常量，读起来容易误解。

### 10.6 `EmptyState`（210 行）：空对话首屏

```java
24-29  SUGGESTIONS = 4 组（标题 key, 提示词 key）：代码 / 查 bug / 写测试 / 生成周报
31     MAX_CONTENT = 440
92-121 computeHeight:
93        contentWidth = min(440, max(240, width))      // ★ 居中列宽
96-99     iconTop=28 → titleTop → subtitleTop → y
101-113   2×2 卡片网格：cardW = (contentWidth - gap)/2，cardH = 58
115-119   可选 hint
126-141 paintBlock: 图标（UiIcons.scaled("agent", 40)）→ 标题 → 副标题 → 4 张卡 → hint
143-163 paintCard: 底色 SUBTLE/HOVER + 描边 + 标题 + 提示词（clipRect 防溢出）
167-200 mouse: hover 变色 + 手型；点击 → onPick.accept(提示词)
```

- **四张卡固定 2×2**（104-108）：`column = i % 2`、`row = i / 2`。加第五张会落到第三行。
- **点击直接投递提示词**（195）：`onPick.accept(AgentBundle.message(SUGGESTIONS[index][1]))`。
  这个回调最终连到 `ChatView.setSuggestionSink` → `ChatSessionPanel` 把文本填进输入框。
- **提示词不自动发送**。📌 这是有意的：自动发出去用户就没机会改参数了。

⚠️ `paintBlock` 里的 `left = (width - contentWidth) / 2` 在 width 小于 240 时为负，
绘制会向左溢出。面板窄于 240px 时可见（重度拖拽后）。

### 10.7 `TodoStrip`（279 行）：由 Core 驱动的清单

```java
39-41  COMPLETED / IN_PROGRESS / CANCELLED   // 与 agent/todos.py 同一套枚举
59-83  setItems:
         清空 → doneCount 重算 → 空则 setVisible(false)
         非空：setVisible(true) + collapsed = (doneCount >= items.size())   // ★ 全完成自动折叠
133-171 paintComponent: chevron + "2/5 已完成" 头部；折叠时到此为止
173-196 paintRow: 状态标记 + 文本（完成则变灰 + 删线）
201-222 paintMark: completed→勾（OK） cancelled→叉（muted） in_progress→3/4 弧（ACCENT） pending→圆环
243-274 mouse: 点头部 toggle；hover 变色
```

📌 **全部完成自动折叠**（77）把空间还给对话——这是产品上的正确定位（清单是过程信息，
不是结果）。

⚠️ **但它会在每次 `setItems` 时重新折叠**（77）：用户手动展开刚完成的清单后，
Core 再推一次 `chat/todos`（很常见）就会再次折叠。要修需要区分“用户显式展开”与“默认状态”，
例如加一个 `userOverrode` 标志。

⚠️ **头部文案的参数数目可疑**（155）：

```java
AgentBundle.message("todos.header", "", doneCount, items.size(), suffix)
```

第一个参数是空串。要么是模板有 4 个占位符而第一处不再使用，要么是遗留。
改文案时要确认 `todos.header` 的占位符个数（§17.2 的 i18n 清单里记为待核项）。

### 10.8 `StatusRow`（161 行）：底部状态行

```java
19-20  HEIGHT = 20, DOT = 12
56-75  setBusy：忙 → 启动 60ms 定时器（angle += 30°，setCoalesce(true)）；闲 → 停表 + angle = 0
88-138 paintComponent:
         忙：先画背景圆（ACCENT@70）+ 前景弧（ACCENT，从 angle 起 100°）
         右：meta chip（SUBTLE 圆角底 + muted 文字），宽 = 文字宽 + 2*s
         中：状态文本（muted），可用宽 = 总宽 - 左侧 - chip 宽 - 间距，超出则省略
155-160 removeNotify 停表
```

- 定时器 **60ms / 30°** = 每秒 5 圈。📌 视觉上刚好“转得动但不心慌”；改快了会显得焦躁。
- `setCoalesce(true)`：EDT 繁忙时合并多余事件，避免定时器追着补跑。
- **空闲时零定时器**（注释 54-55）。面板堆 8 个会话时，这一点很关键。

⚠️ `ellipsize`（140-150）是线性扫描 O(n)，且只返回 `""`（不返回省略号）当连一个字符都放不下时。
与 `TodoStrip.ellipsize`（224-239）是两个独立实现，行为不完全一致。

### 10.9 `ReasoningView`（210 行）：思考过程折成一行

它只做一件事：把模型的思考过程当成**可折叠的一段**，默认展开、正文开始时自动收起。

```java
header 文案：AgentBundle("ui.reasoning.title") + " · 12.4s · " + AgentBundle("ui.reasoning.chars")
            中文包：“思考过程 · 12.4s · 3204 字”；英文包：“Thinking · 12.4s · 3204 chars”
setText(text)：累计全文 → 重算段落 → invalidateHeight
finish()：停止计时；if (!touched) expanded = false    // ★ 用户点过标题就不抢他的选择
paintBlock：chevron + header；展开时每段一个 InlineLayout，small() + muted 着色
mouseClicked：命中 header → expanded 翻转，并置 touched = true
```

三条设计取舍：

1. **默认展开、正文到来时折叠**：思考先于答案到达，用户先看到全文；一旦 `appendAssistantToken`
   触发 `finishReasoningInternal()`，行自动收起，不把已读过的内容继续占屏。
2. **折叠只隐藏，不截断**：全文一直在模型里，点开就是完整思考（`TextCopy` 也能复制）；
   与旧版 Core 直接丢弃字段相比，这里不做任何长度上限。
3. **计时冻结在 `finish()`**：流式期间 header 每 40ms 刷一次，冻结后不再跳动；字数同理。

📌 **与步骤块的关系**：`ensureReasoningRow()` 先 `breakSteps()`，所以时间线永远是
「思考块 → 工具组 → 思考块 → …」的顺序；一个回合里可能有多个思考阶段（每次工具返回后再思考），
它们各自占一行，而不是拼在一起。

⚠️ 思考内容**不进会话历史**：Core 只把正文写入 store，`session/get` 回放时看不到思考行。
这是有意的（省 token、避免二次计费），但用户会把旧会话当成「当时没思考」。

## §11 `ui/design/`（5 文件 915 行）：设计系统

前 10 节的所有视图都在调用这五个类。它们不含业务逻辑，但**决定了全面板的观感与
DPI 行为**，也藏着两处「写了没接线」的死代码。

| 文件 | 行 | 职责 |
| --- | --- | --- |
| `UiMetrics` | 90 | 间距阶、圆角、行高、字体度量缓存 |
| `UiTheme` | 194 | 颜色令牌（JBColor）+ 字体派生与缓存 |
| `UiAnim` | 100 | 全局单定时器的补间动画 |
| `UiIcons` | 191 | SVG 图标注册表 + 6 个手绘字形 |
| `UiButton` | 345 | 四种形态的自绘按钮 |

依赖方向是单向的：`UiMetrics ← UiTheme ← {UiAnim, UiIcons, UiButton} ← 所有视图`。
四个类彼此之间只有 `UiButton → UiAnim/UiMetrics/UiTheme` 与 `UiIcons → 无` 这两条边。

### 11.1 `UiMetrics`（89 行）：唯一的尺寸来源

```java
// UiMetrics.java:71-88
71   private static final JLabel SCRATCH = new JLabel();          // ★ 度量借用一块 JLabel
73   public static int lineHeight(Font font) {
74       FontMetrics metrics = SCRATCH.getFontMetrics(font);
75       if (metrics == null) return JBUI.scale(20);
78       return Math.round(metrics.getHeight() * 1.15f);          // ★ 1.15 倍留白
     }
81   public static FontMetrics metrics(Font font)   // 直接透传
85   public static int textWidth(String text, Font font)
```

**间距阶**（24-46）：`xs=4 / s=6 / m=8 / l=12 / xl=16 / xxl=20`，全部经 `JBUI.scale(int)`。
调用方**永远拿到已经缩放过的像素**，所以代码里不会出现 `JBUI.scale(UiMetrics.m())` 这种
双重缩放——这是本卷多次强调的「单位约定」。

**圆角**（48-66）：`bubble=12 / card=10 / chip=8 / input=12 / button=6`。
五个值都很小，因为面板是狭窄的工具窗口：圆角越大，同样的内容看起来越「软」。

📌 **为什么 `lineHeight` 要乘 1.15**：Swing 的 `FontMetrics.getHeight()` 是
`ascent + descent + leading`，在 Windows 的默认 UI 字体上几乎没有 leading。
正文密集（Markdown 段落、工具卡片）时行距会贴得过紧，乘 1.15 是最省的调法。
注意它是**独立的一套行高**，与 `metrics.getHeight()` 不是同一个数：
凡是「行距」用 `lineHeight`，凡是「文本块高」用 `metrics.getHeight()`，两者混用会导致
最后一行下方多出或少掉几个像素（§16 会汇总这张对照表）。

⚠️ **`SCRATCH` 是单槽度量缓存**：`JComponent.getFontMetrics(Font)` 内部只保留
**一个** `FontMetrics`，且用 `fontMetrics.getFont() != font` 判断失效。而本面板会在
`font / small / bold / mono / h1 / h2 / h3` 七种字体之间来回取度量（`InlineLayout` 里的
一个段落就可能同时用到 font 与 mono），于是**每次字体切换都会重算一次** `FontMetrics`。
这是全卷最隐蔽的一处性能细节：单次开销极小（微秒级），但滚动时每帧上千次调用，
在 200% 缩放下会被放大。若要优化，应换成 `Map<Font, FontMetrics>` 而不是改调用点。

⚠️ `textWidth`（85-88）在 `metrics == null` 时返回 0，而 `lineHeight` 返回 `scale(20)`：
**同一个 null 分支，两个方法给出的兜底语义不同**（宽度 0 = 不可见，高度 20 = 占位）。
这里没有对错，但读代码时要意识到「null 不等于 0」。

### 11.2 `UiTheme`（193 行）：颜色与字体

#### 颜色令牌（23-38）

| 令牌 | 浅色 | 深色 | 来源 |
| --- | --- | --- | --- |
| `RAISED` | `#FFFFFF` | `#2B2D30` | 硬编码 |
| `SUBTLE` | `#F4F6F9` | `#313438` | 硬编码 |
| `BORDER` | `#DBDFE6` | `#3A3D41` | `Borders.color` |
| `ACCENT` | `#3574F0` | `#4B8BFF` | `Link.activeForeground` |
| `ACCENT_SOFT` | accent@30 | accent@48 | 硬编码 alpha |
| `HOVER` | `#EAF0FA` | `#34383D` | 硬编码 |
| `ERROR` | `#C0392B` | `#F08A8A` | `Notifications.errorForeground` |
| `ERROR_SOFT` | `#FDECEC` | `#3A2626` | 硬编码 |
| `OK` | `#2E7D32` | `#73B27A` | `Notifications.successForeground` |
| `WARN` | `#B26B00` | `#D9A343` | `Notifications.warningForeground` |

📌 **`JBColor` 的语义是「绘制时解析」**：常量本身不带颜色，`getRGB()` 被调用时
才去查当前主题。所以主题切换不需要重建组件树，只要触发一次 `repaint()`。
这就是全部视图都能「跟随主题」而代码里看不到任何监听器的原因。

📌 **`JBColor.namedColor(key, default)` 的三层回退**：先查平台 UI 主题里的 key
（例如用户装了自定义主题），查不到用 `default`，而 `default` 本身又是 JBColor，
于是再按明暗取两个硬编码值。本卷 4 个令牌用了这种写法（BORDER/ACCENT/ERROR/OK/WARN），
其余是纯 JBColor。

⚠️ `ACCENT_SOFT` 是**硬编码 alpha 的 JBColor**，而 `accentSoft(int)`（63-65）是
**运行时从 `ACCENT` 派生**。两者名字相近、来源完全不同：

```java
29   ACCENT_SOFT = new JBColor(new Color(53,116,240,30), new Color(75,139,255,48));
63   accentSoft(alpha) → withAlpha(ACCENT, alpha)
```

`ACCENT_SOFT` 用的 rgb 与 `ACCENT` 的浅色值（`0x3574F0` = 53,116,240）相同，
所以浅色主题下二者视觉一致；但**深色主题下 `ACCENT` 会取平台值**（若主题覆盖了
`Link.activeForeground`），`ACCENT_SOFT` 却仍是 `75,139,255` 的硬编码。
自定义主题下可能出现「chip 底色与强调色不同源」的细微色差。

#### 实时解析的背景与文字（42-59）

```java
42   surface()  → UIUtil.getPanelBackground() ?: new JLabel().getBackground()
47   text()     → UIUtil.getLabelForeground() ?: Color.BLACK
52   muted()    → UIUtil.getContextHelpForeground() ?: new Color(0x8C8C8C)
57   withAlpha(color, alpha) → new Color(r, g, b, alpha)
```

📌 这三个方法**每次调用都查一次 UIManager**，而不是缓存成常量。代价是一次哈希查找，
收益是「用户切换主题后已存在的组件也正确」——若缓存成 static 常量，切换后必须重启 IDE。

⚠️ `surface()` 的兜底 `new JLabel().getBackground()` 是**每次调用新建一个 JLabel**。
它在 `UserMessageView.darkSurface()` 里被 `paintBlock` 每帧调用（§10.2），
正常路径不会走到兜底，所以影响为零；但这是一个「只在极端情况下才分配的兜底」。

#### 字体派生与缓存（69-173）

```java
69-75  cachedBase / small / bold / mono / h1 / h2 / h3   // 7 个 volatile 缓存
79     source                                          // 缓存所依据的「当前 LAF 字体」
82-91  sync()：live = UIUtil.getLabelFont() ?: new JLabel().getFont()
               if (!live.equals(source)) { source = live; refresh(); }
```

| 方法 | 派生规则 |
| --- | --- |
| `font()` | 直接用 `source` |
| `small()` | `size - 1` |
| `bold()` | `Font.BOLD` |
| `mono()` | `Font.MONOSPACED`，`max(11, size - 1)` |
| `h1()` | `BOLD`，`size + 4` |
| `h2()` | `BOLD`，`size + 2` |
| `h3()` | `BOLD`（与 `bold()` 值相同） |

每个 getter 的结构都是「`sync()` → 取缓存 → 为空则派生并写回」。

📌 **`sync()` 放在每个 getter 的第一行，是这套缓存自愈的关键**：LAF 换字体后，
下一次任何人取字体都会发现 `live != source`，于是整体 `refresh()`。
因此**即使没有任何监听器，字体也会跟着主题走**——§11.2 末尾的死代码正是靠这个兜住的。

⚠️ **`h3()` 与 `bold()` 数值相同但实例不同**：两者都 `deriveFont(Font.BOLD)`，
各存一份缓存。`InlineLayout.ensureAttributed`（§9.2）判断「是否需要重建
`AttributedString`」时用的是**字体实例**比较，若同一段文本一次用 `bold()`、一次用 `h3()`，
就会被判定为「字体变了」而重建。实际调用点（`HeadingView` 用 h3，其余用 bold）
不会在同一行混用，所以只是理论风险。

⚠️ **`mono()` 从基础字体取字号，却换成 `Font.MONOSPACED` 逻辑字体**：
逻辑字体的 `deriveFont` 在中文环境下会做字符回退，代码块里的中文注释会以
另一种字形出现（宽度也不再等宽对齐）。这是平台行为，不是 bug，但排版上可见。

⚠️ **`install()`（178-192）从未被调用**：

```java
178  public static void install() {
179      if (listenerInstalled) return;
182      listenerInstalled = true;
183      UIManager.addPropertyChangeListener(event -> {
184          if (!"lookAndFeel".equals(event.getPropertyName())) return;
187          refresh();
188          for (Window window : Window.getWindows()) window.repaint();
         });
     }
```

全项目搜索 `UiTheme.install(` 只命中定义本身。所以这段「换 LAF 后重绘所有窗口」的逻辑**从未生效**。
实际起作用的补偿有两层：① 每个 getter 里的 `sync()` 保证字体正确；
② `JBColor` 在绘制时解析保证颜色正确。缺的只是**「没人触发重绘」**——
主题切换时 IntelliJ 自己会重绘全部窗口，所以用户看不到问题，这段代码属于保险而非必需。
🔁 但它仍是**死代码**，且 `listenerInstalled` 永远为 `false`。

### 11.3 `UiAnim`（99 行）：一个定时器管全部动画

```java
18   FRAME_MS = 15                       // ≈ 66 fps
20-30  Anim { startNanos, durationMs, step }
32   ACTIVE = new ArrayList<>()          // 只在 EDT 上读写
37-53  animate(durationMs, step)：
41        非 EDT → invokeLater(自身)
45        ACTIVE.add(new Anim(...))
46-49     懒创建 Timer(FRAME_MS) + setCoalesce(true)
50-52     未运行则 start
57-62  fade(from, target, durationMs, setter) → animate(progress → from + (target-from)*progress)
64-69  cancelAll()                         // ★ 从未被调用
71-93  tick()：遍历 ACTIVE，算 progress，easeOut，step.accept；异常 → done = true；空则停表
95-98  easeOut(t) = 1 - (1-t)³            // 三次缓出
```

- **单定时器的意义**：面板里最多同时有 8 个会话标签 + 若干按钮在做 hover 渐隐。
  若每个组件一个 `Timer`，EDT 上会堆几十个 120ms 定时器；这里只有一个 15ms 表，
  **空闲时自动 `stop()`**（90-92）。这是本卷线程模型的一条底层约束（§15）。
- **`easeOut` 是三次曲线**：起步快、收尾慢，120ms 的 hover 渐隐看起来「先亮后稳」。
  换成线性会有明显的机械感。
- **异常吞掉并移除**（79-85）：`step` 里通常只做「改字段 + `repaint()`」，
  若组件已 `dispose` 导致 NPE，把这一条动画移除即可，绝不能让整个循环崩掉。
  📌 这条注释写的正是「一个坏的组件不能拖垮全局面板」。
- **`fade` 的 `from` 是调用时快照**（61）：连续快速进出 hover 时，第二次 fade 从
  **当前值**开始（调用方把字段当前值传进来），所以不会跳变。

⚠️ **`cancelAll()`（64-69）从未被调用**。`ChatView.dispose` 与 `AgentChatPanel.dispose`
都没有清理动画。后果很轻：动画最多再跑 120ms，`step` 对一个已移出层次结构的组件调
`repaint()` 是空操作。但它意味着**关闭会话后仍有一个定时器在跑**（直到 ACTIVE 清空）。
若要彻底干净，应在 `ChatTranscript.dispose` 里调 `UiAnim.cancelAll()`——注意那是**全局**取消，
会连带停掉其它会话正在进行的动画，所以更好的做法是按组件登记句柄。

⚠️ **`ACTIVE` 的线程假设没有断言**：`animate` 会把非 EDT 调用弹回 EDT（41-44），
`tick` 由 Swing `Timer` 保证在 EDT 上执行，所以实际是安全的；但 `cancelAll()` 若被
后台线程调用会并发修改 `ArrayList`。目前无人调用，属于「一旦接线就会踩」的坑。

### 11.4 `UiIcons`（190 行）：资源 + 手绘

```java
25   CACHE = new ConcurrentHashMap<String, Icon>()
34-43  icon(name) = CACHE.computeIfAbsent(name, k -> {
           try  → IconLoader.getIcon("/icons/" + k + ".svg", UiIcons.class)
           catch → LOGGER.warn(...) + EmptyIcon.create(16)
       })
45-48  scaled(name, size) = 宽已等于 size ? 原图标 : IconUtil.scale(icon, null, size / (float) 宽)
```

📌 **`IconLoader.getIcon` 会自动选 `_dark` 变体**：插件资源里 `attach.svg` 与
`attach_dark.svg` 同名成对，深色主题下平台自己挑后者。这就是为什么目录里每个图标都有两份。

📌 **缺文件时降级为空图标但记日志**（38-41）：注释直说了历史事故——
「静默退化曾让『新建会话』按钮整个不可见」。现在的策略是「能画就画，画不出来也留一条
warn」，配合 `UiIconsResourceTest`（§18.2）在测试期就拦住。

⚠️ **`scaled()` 每次调用都可能新建一个 `IconUtil.scale` 的包装对象**（缓存键只有 name，
不含 size）。`EmptyState.paintBlock`（129）在**每帧绘制**里调
`UiIcons.scaled("agent", 40)`，于是每帧分配一个缩放图标。图标只有 40px，分配很小，
但这是「绘制路径里做分配」的典型反例——把 `Icon` 提成字段即可。

**六个手绘字形**（78-189），全部遵循同一个模板：`g.create()` → 抗锯齿 → 按 `box` 比例算点
→ `BasicStroke` → `draw/fill` → `dispose()`。

| 方法 | 用途 | 关键比例 |
| --- | --- | --- |
| `paintUpArrow` | 发送按钮 | 箭头半宽 `0.20w`，线宽 `max(1.4, 0.09w)` |
| `paintStop` | 停止按钮 | 方块边长 `0.42 * min(w,h)`，圆角 `0.3 * 边长` |
| `paintClose` | 关闭叉 / chip 删除 | 内缩 `max(2, 0.30w)` |
| `paintChevron` | 折叠箭头 | 半宽 `0.22w`，上下各 `0.5 * 半宽` |
| `paintCheck` | 待办完成 | 三点折线，线宽 `max(1.4, 0.12w)` |
| `paintPaperclip` | 附件 chip | 弧 `40°~320°` + 一条竖线 |

📌 **线宽都写成 `Math.max(1.x, 比例)`**：100% 缩放下比例可能算出 0.9px，
`BasicStroke` 会在取整时把细线抹掉。下限保证「永远看得见」。

📌 **`trash()` 映射到 `AllIcons.Actions.GC`、`retry()` 映射到 `AllIcons.Actions.Restart`**
（66-68）：平台没有独立的「删除/重试」标准图标，这是最接近的两个。语义上略偏，
但图标风格与 IDE 一致比自绘更重要。

### 11.5 `UiButton`（344 行）：四种形态

```java
23-32  enum Kind { GHOST, SOLID, ICON, OUTLINE }
34-45  字段：kind / listeners / text / icon / hovered / pressed / selected / circular / hover / padX / padY
51-61  构造：opaque=false、可聚焦、手型光标、字体 = UiTheme.font()
```

**四种形态的实际使用地**：

| Kind | 用处 | 视觉 |
| --- | --- | --- |
| `GHOST` | 停止、模型选择 | 无底，hover 淡底色 |
| `SOLID` | 发送 | 强调色实底 + 白字 |
| `ICON` | 附件、设置、标签条右上四个 | 方形，内边距 4 |
| `OUTLINE` | 「自动执行」开关 | 描边，选中时强调淡底 |

**尺寸**（154-201）：

```java
154-164  horizontalPadding: ICON 4 / SOLID 14 / OUTLINE 10 / GHOST 7
166-175  verticalPadding:   ICON 4 / SOLID 5 / 其他 3
177-184  contentSize = 文字宽 + 图标宽(+5 gap) × max(文字高, 图标高)
187-201  preferred = minimum = maximum = content + padding*2      // ★ 三者相同
```

⚠️ **`minimum == maximum == preferred`**：这是「按钮不参与拉伸」的声明。
放在 `BoxLayout`/`BorderLayout` 里都正确；但若哪天把它塞进 `GridBagLayout` 并期望
「填满列宽」，它会拒绝变大。修改布局时必须知道这一条。

**绘制**（206-278）：

```
212-215  alpha = enabled ? 1 : 0.4        // 置灰靠整体透明度，不改颜色
217      arc = circular ? min(w,h) : radiusButton(6)
218-224  底色：SOLID→ACCENT；OUTLINE→选中时 ACCENT_SOFT；其余→选中 HOVER / hover>0.01 时按 hover 插值
229-235  选中且非 SOLID → 强调色描边 @120
236-240  OUTLINE → BORDER 描边
241-244  pressed 且非 SOLID → text@18 覆盖层
246-250  前景：SOLID→白；选中→ACCENT；禁用→muted
253-258  有焦点 → accentSoft(90) 两像素焦点环
```

⚠️ **`OUTLINE` + `selected` 会连画两道描边**（229-240）：先画 `ACCENT@120`，紧接着
画 `BORDER`，而 `BORDER` 后画**完全覆盖**前者。所以「自动执行」开关打开时，
描边仍然是普通灰边，只有底色变了。若想让选中态更明显，应先判 `kind == OUTLINE` 再决定
描边颜色，而不是无脑叠两层。

📌 **置灰用 `AlphaComposite(0.4)` 而不是换颜色**（214-215）：这样图标（来自 `AllIcons` 的
彩色图标）也一起变淡，视觉统一。代价是焦点环要在 253-254 行**把 composite 重置回 1**，
否则禁用+聚焦时焦点环也只剩 40% 不透明度。

**交互**（282-339）：

- `mouseEntered/Exited` → `fadeHover(1f/0f)`；`mouseExited` 同时清 `pressed`（防止拖出后残留按下态）。
- `mouseReleased` 只有 `wasPressed && enabled && contains(point)` 才触发（310）——
  **按下后拖出再松开不触发**，这是标准按钮语义。
- `fadeHover`（317-327）**对 `SOLID` 直接跳过动画**：实底按钮换 hover 色会因为对比度不足
  而看不出变化，直接 `repaint()` 更省。
- 键盘：`WHEN_FOCUSED` 绑 `SPACE` 与 `ENTER`（331-332）→ 同一个 action → `doClick()`。
  📌 `doClick`（133-141）先判 `isEnabled()`，再 `List.copyOf(listeners)` 遍历——
  避免监听器在回调里移除自己导致 `ConcurrentModificationException`。
- `setEnabled`（143-150）**同时改光标**：禁用时不再是手型。

⚠️ **`hovered` 字段（39）只被 `isHoverActive()`（341-343）读取，而该方法全项目无调用**。
真正的 hover 态存在 `hover`（float）里。这是一对「写了但没人用」的字段+方法，
删掉不影响任何行为。

## §12 `PickerPopup`（573 行）：历史与记忆共用的列表弹窗

`AgentChatPanel` 的两处弹层用的是同一个类：

```java
// AgentChatPanel.java:398（历史，宽 400）
// AgentChatPanel.java:567（记忆，宽 420）
```

这不是「顺手复用」，而是刻意的：搜索框、hover 动作、键盘导航、空态、页脚提示五件事
在两个场景里逐字相同，差异只在「行里显示什么」与「动作做什么」。

### 12.1 数据模型（36-69）

```java
36-55  Row { title, subtitle, chip, warning, tooltip, payload, actions }
45-54      构造里把 null 统一成 ""，actions 为 null 时用 List.of()，否则 List.copyOf
59-69  RowAction { label, danger, handler }
```

- 四个字符串字段**全部在构造里归一化**，所以 `paintRow` 里可以直接 `row.chip.isEmpty()`，
  不需要 null 判断。📌 这与 `TodoStrip`、`ToolCallCard` 的处理方式一致：
  **在边界归一化，内部不再防御**。
- `payload` 是 `Object`，由调用方塞 `HistoryEntry`/`PendingMemory`，`pickHandler` 里
  `instanceof` 取回（AgentChatPanel:405、:433）。📌 这是 Swing 里「弱类型载荷」的常见折中：
  省掉一个泛型参数，代价是每次都要 `instanceof`。
- `danger` 只影响 hover 时的底色与文字色（469-474），不影响行为——危险动作的
  二次确认由 `handler` 自己负责。

### 12.2 结构（85-163）

```
JPanel(BorderLayout(0, xs))
├─ NORTH  head(JPanel BorderLayout(0,xs))
│         ├─ NORTH searchRow: [SearchGlyph | JBTextField]
│         └─ SOUTH header(JLabel, small, muted)
├─ CENTER center(JPanel BorderLayout)
│         ├─ NORTH emptyLabel（与列表互斥显示）
│         └─ CENTER JBScrollPane(rows)
└─ SOUTH  footer(JLabel, small, muted)
```

- 宽由构造参数固定（87 `JBUI.scale(width)`），高由 `getPreferredSize`（214-219）
  在 `140..460` 之间夹取。📌 固定宽 + 自适应高，是弹层最稳的组合：
  宽度变化会让自绘行重排，弹层跳宽看起来像 bug。
- `scroll.setUnitIncrement(JBUI.scale(44))`（145）**正好等于一行的高度**：
  滚一格走一行，鼠标滚轮与键盘 ↑↓ 的步长一致。
- `emptyLabel` 与 `scroll` 共用中间位置（147-154），`setRows` 里二选一显示（181-192）。

### 12.3 搜索防抖（228-239）

```java
228  schedule():
229      String query = query();                 // trim 后
230-232  searchHandler == null → 直接返回
233-235  旧 debounce != null → stop()
236     debounce = new Timer(300, e -> searchHandler.accept(query));
237     debounce.setRepeats(false);
238     debounce.start();
```

⚠️ **每次敲键都新建一个 `Timer`**，只 `stop()` 旧的、不复用同一个实例。对一个 300ms
单发定时器来说开销可忽略，但它是「每次输入分配一个对象」的写法；用同一个 Timer + 
`restart()` 更干净。

⚠️ **弹窗关闭时没有取消 debounce**。`createPopup`/`cancel` 路径里都没有 `debounce.stop()`，
所以「敲字后 300ms 内按 Esc」会触发一次 `searchHandler`。`AgentChatPanel.runHistorySearch`
用三道闸挡住了这个问题（483-486）：

```java
483  if (historyPicker != picker || historyPopup == null || !historyPopup.isVisible()
484          || !query.equals(picker.query())) return;
```

📌 这是**跨层防御**的范例：UI 组件不保证生命周期，调用方用「身份 + 可见性 + 查询串」
三重校验把过期结果丢掉。写自己的 picker 时这三条值得照抄。

### 12.4 键盘模型（243-279）

```java
244  InputMap = WHEN_ANCESTOR_OF_FOCUSED_COMPONENT      // ★ 关键
246-249  ↓ ↑ ENTER ESC → picker-down / up / pick / close
250-261  ↓↑ 调 rows.move(±1)
262-270  ENTER：selectedRow() 非空 → pickHandler.accept(row)
271-278  ESC：popup != null → popup.cancel()
```

📌 **为什么不是 `WHEN_IN_FOCUSED_WINDOW`**：焦点在 `JBTextField` 里，用
`WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` 才能让「在输入框里按 ↑↓ 也能选行」生效。
优先级顺序是：焦点组件自己的 `WHEN_FOCUSED` → 祖先的 `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT`
→ 窗口级。`JBTextField` 没有绑 ↑↓/Enter/Esc，所以全部落到这里。

📌 **切换行会滚动**（334 `scrollRectToVisible`）：键盘选中项移出视口时自动滚入，
这是「键盘操作要能看见结果」的最低要求。

### 12.5 行的布局与绘制（344-478）

```java
344-346  rowHeight = warning 为空 ? 44 : 60
348-368  layoutRows():
349-351     清空 bounds / actionBounds，记 layoutWidth = getWidth()
353-367     每行：bounds(0, y, width, h)
              action 从右往左排：宽 = 文字宽 + l，高 20，y = 行底 - 26，间距 xs
370-374  ensureLayout(): getWidth() != layoutWidth → layoutRows()
377-383  preferredSize = (弹窗宽 - 2m, 行高之和)
```

📌 **同一批 `Rectangle` 同时驱动绘制与命中测试**（注释 298-300）。
`indexAt`（545-552）与 `actionAt`（554-565）只是线性扫这批矩形，
所以**不可能出现「看到的和点到的不一致」**。

📌 **`44px` 行高是设计常数**：一行标题 + 一行副标题刚好放得下，
`warning` 多一行时用 `60px`。两个值都经 `JBUI.scale`，200% 下依然成立。

**`paintRow`（405-457）的绘制顺序**：

```
409-413  选中或 hover → 圆角底色（hover 用 HOVER，选中用 SUBTLE）
415-423  标题（bold）
428-432  副标题（small, muted）
433-438  warning（small, WARN）
440-452  chip（右上角，ACCENT_SOFT 底 + ACCENT 字）
454-456  仅 hover 时画动作按钮
```

⚠️ **`textWidth` 没有扣掉 chip 的宽度**（421）：

```java
421  int textWidth = rect.width - (left - rect.x) - UiMetrics.m();
```

chip 画在右上（444 `chipX = rect.x + rect.width - m - chipWidth`），
而标题的可用宽度算到了右边距 `m` 为止。所以**长标题会画到 chip 底下**，
两者重叠。历史行的 chip 目前恒为 `""`（AgentChatPanel:429 传空串），
只有记忆弹窗会传类别 chip，因此重叠只在记忆列表出现。修法是
`textWidth -= (chipWidth + 2*s)`。

⚠️ **warning 行会顶出 60px**：三行文本（标题 bold 高 + 两行 small 高）加上
`top = rect.y + s` 的偏移，累计可能超过 `60 - s`。代码没有 clip，最后一行会画到
下一行的底色上。当前 `warning` 只在「记忆候选与已有记忆冲突」时出现，内容短，
所以肉眼未见——属于「内容一变就露」的边界。

📌 **动作按钮只在 hover 时画**（454）：未 hover 的行不显示「删除」等按钮，
既省绘制又避免误点。命中测试 `actionAt` 不判 hover，但鼠标点击必然先经过
`mouseMoved` 把 `hoverActions` 设好，所以实际一致。

### 12.6 交互（499-565）

```java
502-515  mouseMoved: index/action 变化才 repaint；setToolTipText(行的 tooltip)；手型光标
517-522  mouseExited: 清 hover
524-539  mouseClicked:
527-529     非左键或无命中 → 返回
531-535     命中 action → 执行 handler 并 return    // ★ 不触发 pick
536-538     否则 pickHandler.accept(row)
```

- 📌 **`setToolTipText` 在 `mouseMoved` 里逐行切换**（508）：`ToolTipManager` 已注册
  整个 `RowsPanel`（314），所以悬停 750ms 后显示当前行的 tooltip。
  这也是为什么 `Row.tooltip` 允许为 null——null 表示「这一行没有提示」。
- ⚠️ **鼠标点击不更新 `selected`**：`selected` 只在 `setRows`（321）与 `move`（333）里改。
  点选一行后按 ↑↓，跳转起点是「上次键盘选中的行」而不是刚点的行。视觉上因为
  hover 底色也在，用户不易察觉；但这是真实的状态分裂。
- ⚠️ **`layoutRows` 在 `getWidth() == 0` 时会算出 `width = 1`、
  `action.x` 为负**（355-357 的 `Math.max(1, getWidth())`）。`ensureLayout` 在
  首次绘制时按真实宽度重算，所以只是首帧前的一次无效布局；
  但 `scrollRectToVisible(bounds.get(selected))`（334）若发生在首帧前，滚到的是一个
  1px 宽的矩形。

### 12.7 页脚与 i18n

```java
570-572  defaultFooter() → AgentBundle.message("ui.picker.footer")
```

两个调用点都用它（AgentChatPanel:401、:570 附近），保证「↑↓ 选择 · Enter 打开 · Esc 关闭」
这行提示只有一份文案。

> 🔁 **一处口径**：`PickerPopup` 是全卷第三个「自己实现 `ellipsize`」的地方
> （480-495，另两处是 `TodoStrip` 224-239 与 `StatusRow` 140-150）。三份实现行为不完全一致：
> 这里放不下时返回省略号本身，`StatusRow` 返回空串。§19 会把它们列在一起。

## §13 附属件（4 文件 349 行）：附件、两个模态框、文案包

这四类东西都不参与「对话渲染」，但都在同一次请求的链路上：
`ChatAttachment` 组装提示词 → `DiffConfirmCard`/`CommandConfirmCard` 在工具执行前拦截
→ `AgentBundle` 提供全部文案。

```
ChatSessionPanel.sendMessage
   └─ ChatAttachment.buildPrompt(text, attachments, root)   ← 拼成 Core 的 message
        └─ Core → 工具调用 → EditTools/CommandTools
              └─ DiffConfirmCard.confirm / CommandConfirmCard.confirm   ← 模态拦截
AgentBundle.message(...)                                     ← 上述所有文案
```

### 13.1 `ChatAttachment`（155 行）：把文件变成提示词

```java
18   MAX_FILES = 8                    // 由 ChatComposer 在添加时限制（:256、:311）
20   MAX_FILE_CHARS  = 40_000         // 单个文件内联的最大字符数
21   MAX_TOTAL_CHARS = 120_000        // 整条消息所有附件的总字符预算
22   MAX_INLINE_BYTES = 512 * 1024    // 超过此字节数不内联
23   BINARY_PROBE = 4096              // 二进制探测的前缀长度
```

**`buildPrompt`（30-70）的完整流程**：

```
31      prompt = 用户输入
32-34   无附件 → 原样返回（不加任何包装）
35      "\n\n---\n以下是我附加的文件内容，请把它们作为上下文：\n"
36      budget = 120_000
37-68   for (index, file):
39-40       "【附件 i/N】" + displayName(file, root)
41-44       budget <= 0 → "（已达单条消息的上下文上限，未内联）" + continue
45-49       目录 → "（这是一个目录，未内联；需要时用 list_dir …：<绝对路径>）" + continue
50-55       readInline 失败（二进制 / >512KB / IO 异常）
              → "（二进制或超过 512 KB，未内联，需要时用 read_file 读取：<绝对路径>）"
56-58       limit = min(长度, min(40_000, budget)); budget -= limit
59-63       ```lang + 内容 + ```（末尾补一个换行）
64-67       截断时追加「（以上为前 N 个字符，原文件共 M 个字符）」
```

几个必须在写调用方时知道的点：

- 📌 **附件是「消息文本」而不是结构化字段**。`ChatSessionPanel` 把 `buildPrompt` 的结果直接
  放进 `chat/start` 的 `message` 参数（:347），Core 看到的是一条**又长又带围栏的普通消息**。
  好处是 Core 不需要懂附件协议；代价是**模型会把附件当成「用户贴进来的文本」**，
  而不是「已授权读取的文件」。
- 📌 **超预算时只丢内容、不丢路径**——但只在 `budget > 0` 时成立。见下方 ⚠️。
- 📌 **目录与二进制都保留绝对路径**，并在提示词里点名「用 `list_dir` / `read_file`」：
  这是「把选择权交给 Agent 工具」的设计，避免 UI 层猜用户想要什么。

⚠️ **`budget` 耗尽后，连路径都不再给**（41-44 在 45-55 之前）：

```java
41   if (budget <= 0) { prompt.append("（已达单条消息的上下文上限，未内联）\n"); continue; }
45   if (Files.isDirectory(file)) { ... 只给路径 ... continue; }     // 不消耗预算
51   if (body == null)          { ... 只给路径 ... continue; }     // 不消耗预算
```

目录与二进制**本来就不占预算**，却在预算为 0 时被提前拦掉。用户拖入 8 个大文件后，
最后几个若正好是目录，就连路径提示都没有了。修法是把 41-44 的判断下沉到「真正要内联」的
那一支（56 行之前）。

⚠️ **`isBinary` 只看前 4096 字节里有无 `0x00`**（114-122）：
UTF-16 LE/BE 的文本文件前两字节是 `FF FE`/`FE FF`，紧接着就是 `0x00`，
因此**UTF-16 编码的源文件会被当成二进制**而只给路径。GBK 文件不含 `0x00`，反而会被按
UTF-8 解码成乱码内联进去。两者都是「按字节猜编码」的固有缺陷，
正确做法是用 `CharsetDetector` 或至少先看 BOM。

⚠️ **`fenceLanguage` 的 `toLowerCase()` 没带 `Locale`**（129）：
在土耳其语环境下 `"II".toLowerCase()` 会得到 `ıı`（无点 i），扩展名 `II` 之类的判断会失效。
这是 JDK 的经典坑（`String.toLowerCase()` 用默认 Locale）。对本项目（`.java/.py/.ts`）
影响为零，但值得改成 `toLowerCase(Locale.ROOT)`。

📌 **`relativePath` 的 `startsWith` 是按路径分量比较的**（86）：`Path.startsWith` 不会把
`/repo2` 误判成 `/repo` 的子路径，所以不需要手写字符串前缀判断。跨盘符时
`relativize` 会抛 `IllegalArgumentException`，被 89 行的 `catch (RuntimeException)` 接住
并退回绝对路径——这是 Windows 上必须有的分支。

📌 **`MAX_FILE_CHARS` 是字符数、`MAX_INLINE_BYTES` 是字节数**（20、22）：
一个 512KB 的纯中文 UTF-8 文件大约是 17 万字符，先被字节阀放进来，再被 4 万字符截断；
一个 512KB 的 ASCII 日志也是 51 万字符，同样截到 4 万。
所以**两个阀在不同语言下的实际约束力不同**，字节阀只负责拦住「读进来就爆内存」的巨文件。

📌 提示词里的中文包装（35、39、42、46、52、65）是**硬编码**，没有走 `AgentBundle`。
这是有意的：它属于「发给模型的指令文本」，与 Core 的 prompt 同语言（中文），
不应随 IDE 语言切换而变。

### 13.2 `DiffConfirmCard`（108 行）：写文件前的模态 diff

```java
19   MAX_DIFF_LINES = 800
26-51 confirm(project, path, before, after):
27       boolean[] accepted = {false};                      // ★ 数组做 out 参数
28       invokeAndWait(() -> { ... dialog.showAndGet() ... })
50       return accepted[0];
```

📌 **为什么用 `boolean[0]` 而不是 `AtomicBoolean`**：`invokeAndWait` 保证调用线程会阻塞到
EDT 执行完毕，之间不需要任何内存屏障之外的东西；一个一元素数组足够了。
使用 `AtomicBoolean` 也对，但语义上是「不需要原子性」。

📌 **`DialogWrapper(project, false)` 的第二个参数是 `canBeParent`**：
这里传 `false`，因为它是从工具窗口弹出的**兄弟对话框**而非父对话框。
传 `true` 会让它出现在窗口层级里，导致后续弹窗级联关闭。

**匿名子类的初始化顺序**（30-47）：

```java
31   private final JBTextArea area = new JBTextArea(renderDiff(before, after));
33-36 { init(); setTitle(title); }          // ★ 实例初始化块
38-46 @Override createCenterPanel() { ... }
```

Java 的规则是：**字段初始化 → 实例初始化块 → 构造器体**。
`DialogWrapper` 的构造器已经在 30 行执行完（父类先），随后跑子类的字段与块，
块里调 `init()` 时 `area` 已经就绪，所以 `createCenterPanel` 能拿到内容。
🔁 这是 `DialogWrapper` 的标准写法，但在读代码时容易误以为 `init()` 先于字段。

**`renderDiff`（55-107）：紧凑版 unified diff**

```
56-58    完全相同 → "（内容无变化）"
59-71    统计公共前缀 prefix 与公共后缀 suffix（后缀不跨过 prefix）
74-78    前 3 行上下文
79-81    所有被删行  "- "
82-84    所有新增行  "+ "
85-88    后 3 行上下文
91-95    空文件特判："+（新文件）" / "-（删除文件）"
97-105  > 800 行 → 截断并注明总行数
```

- 📌 **不是标准 diff**：没有 `@@ -a,b +c,d @@` 区段头，也**不是按行交错**的
  （先列全部删除、再列全部新增）。对「改一处」的场景完全够用；
  对「大段重排」会出现「先一片红再一片绿」，可读性下降。用真 diff 库会引入依赖，
  这是权衡后的取舍。
- ⚠️ **`after` 为空 = 删除文件**（93-95）：但此时 82-84 行没有新增行，
  81 行却会输出所有旧行，所以文本是「-（删除文件）」+ 全文。对 800 行以内的文件，
  用户看到的是整个文件内容——信息量过大的确认框。
- ⚠️ **固定像素尺寸 `900×520`**（44）：没有经 `JBUI.scale`，200% 缩放下对话框
  相对变小（其余 UI 都放大了）。`CommandConfirmCard` 的 `720×160` 同理。
- 📌 `renderDiff` 是**包级可见的静态方法**（没有 `private`），本意是给单测留口子，
  但 `src/test` 里没有它的测试（§18）。

### 13.3 `CommandConfirmCard`（62 行）：跑命令前的确认

```java
28-61 confirm(project, command, cwd):
30     invokeAndWait(() -> { ... })
31-33  hint = "Agent 请求执行以下命令；允许后才会运行。"（灰色、斜体 11f）
34-57  DialogWrapper：标题「确认执行命令」，OK=「执行」，Cancel=「跳过」
45-56  center = cwd 标签(NORTH) + 命令文本区(CENTER, 720×160) + hint(SOUTH)
```

- 📌 **OK/Cancel 的文案是「执行 / 跳过」而不是「确定 / 取消」**：
  「跳过」能准确表达「这次不执行，但任务继续」——这正是工具协议里 `approved=false` 的语义。
- 📌 **`setLineWrap(true)`**（47）：命令可能很长，折行比横向滚动更易读。
  而 `DiffConfirmCard` 的 diff 必须关掉折行（`setLineWrap(false)`），否则行号与缩进就乱了。
  **同一个面板里两种文本区，两种折行策略。**
- ⚠️ **`JBLabel` 的 `setForeground(Color.GRAY)`**（32）：固定灰色在深色主题下对比度偏低，
  应该用 `UiTheme.muted()`。同理 31 行的提示文字与 52 行的「工作目录: 」都是硬编码中文 + 硬编码颜色。

⚠️ **两个 `confirm` 都是「阻塞后台线程」而不是「回调」**：
`EditTools` / `CommandTools` 在池化线程上调用它们（§06），`invokeAndWait` 把动作挪到 EDT。
如果哪天有人在 EDT 上调用，`invokeAndWait` 会直接抛 `IllegalStateException`（"Cannot call
from EDT"）。注释里写了「有意阻塞」，但**类型系统没有拦住误用**。

### 13.4 `AgentBundle`（24 行）：265 个键的两份文案

```java
11   extends DynamicBundle
13   BUNDLE = "messages.AgentBundle"
14   INSTANCE = new AgentBundle()
20-23 message(key, params...) → INSTANCE.getMessage(key, params)
```

- 资源：`AgentBundle.properties`（中文基线）与 `AgentBundle_en.properties`，
  **两份各 265 个键**（已核对数量一致）。
- 📌 **`DynamicBundle` 而不是 `ResourceBundle`**：`DynamicBundle` 在运行时响应
  IDE 语言切换（无需重启），且会把 `messages.AgentBundle` 按当前 Locale 选后缀。
- 📌 类注释（7-10）明确了翻译边界：**Shell 界面文案走 bundle，Core 的提示词与工具描述不翻译**
  （「翻译它们会把 prompt 搅乱」）。这条边界在 §17.2 会展开。
- ⚠️ **缺键时 `message()` 返回 `!key!` 字面量**（`ResourceBundle` 行为），不会抛异常。
  因此漏翻一个键的后果是界面上出现 `!composer.send!`，编译器不会提醒。

## §14 `inline/`（2 文件 134 行）：编辑器 ghost text

这一对文件完全不在工具窗口内，但它是**同一个 Core 的第二个入口**：编辑器里打字时，
把光标前后文发给 Core 做 FIM 补全。

```
plugin.xml:113-116   <inline.completion.provider id="com.workreport.agent.inline" order="first"/>
        │
AgentInlineCompletionProvider.kt（Kotlin，81 行）
        │  withContext(Dispatchers.IO) + runCatching
InlineCompletionClient.java（55 行）
        │  conn.request("inline/complete", params).get(1500ms)
AgentCoreService.connection() → Core
```

### 14.1 `InlineCompletionClient`（54 行）

```java
18   TIMEOUT_MS = 1500
26-53 complete(project, path, language, prefix, suffix):
29-34    AgentCoreService.getInstance(project).connection()
           异常（Core 未启动）→ LOG.debug + return ""
35-39   params = { prefix, suffix, language, path }        // null → ""
41-42   conn.request("inline/complete", params).get(1500, MILLISECONDS)
43-46   !ok | 无 result | result 不是对象 → ""
47-48   result.text 或 ""
49-52   任何异常 → LOG.debug + ""
```

- 📌 **「失败一律返回空串」是有意的**：编辑器补全是高频、无声的后台行为，
  弹出「Core 未启动」的通知会打断打字。日志级别用 `debug` 而不是 `warn`，
  避免一次 Core 重启就把日志刷满。
- 📌 **1.5 秒超时**比 debounce（300ms）长得多：留出 RPC 往返 + 模型首 token 的时间；
  但如果模型慢，用户已经在继续打字了（见 14.2 的取消问题）。
- ⚠️ **`params.put("prefix", prefix)` 没有 null 保护**（36-37）：只有 `language`/`path`
  做了 null 转空串。若上游传 null，`Gson` 会写出 `"prefix": null`，Core 侧解析可能失败。
  当前唯一调用方（Kotlin）保证非 null，所以只是契约依赖。
- ⚠️ **`path` 是绝对路径**（Kotlin 侧传 `file.path`）：FIM 请求会把项目内文件路径发给 Core，
  而 Core 会把它拼进 prompt。这是隐私边界上一次必要的泄露（要足够上下文才能补全），
  但与 §07 的记忆/索引一样，需要用户在设置里知情。

### 14.2 `AgentInlineCompletionProvider.kt`（80 行）

```kotlin
26    id = InlineCompletionProviderID(类名)
28-35 isEnabled:
29        !ReportSettingsState.inlineCompletionEnabled → false
32-34     request 为空 / 无 virtualFile / 非本地文件 / 是目录 → false
37    getDebounceDelay = 300.ms
39-72 getSuggestionDebounced:
40-44     project / file 为空 → Empty；file.length > 512KB → Empty
46-48     offset = endOffset.coerceIn(0, textLength)
              prefix = 前 4000 字符（不足则从头）
49-51     prefix 全空白 → Empty
52-55     suffix = 后 1200 字符（不足则到文末）
57-63     withContext(Dispatchers.IO) { runCatching { client.complete(...) }.getOrNull() }
64-66     结果为 null 或空白 → Empty
68-71     attributes = InlineCompletionFontUtils.attributes(editor)
              InlineCompletionSingleSuggestion.build(flowOf(TextElement(text, attributes)))
74-79     DEBOUNCE 300ms / MAX_PREFIX 4000 / MAX_SUFFIX 1200 / MAX_FILE 512KB
```

- 📌 **继承 `DebouncedInlineCompletionProvider` 而不是自己开定时器**：防抖、协程作用域、
  「上一次请求未完时取消」都由平台处理。这是**不重复造轮子**的正确示范。
- 📌 **`getSuggestionDebounced` 是 `suspend` 函数**，所以阻塞的 Java 客户端必须包在
  `withContext(Dispatchers.IO)` 里（57）：直接在协程里调 `.get(1500ms)` 会阻塞
  平台用于补全的调度线程。
- 📌 **`runCatching { }.getOrNull()`**（58-62）：与其判断异常类型，不如「失败即无补全」。
  与 Java 侧的「异常返回空串」形成双重保险。
- 📌 **`InlineCompletionFontUtils.attributes(editor)`**（68）：用编辑器当前字体属性，
  ghost text 的字体、字号、连字与代码完全一致。自己 `new SimpleTextAttributes(...)`
  会在等宽/连字字体下错位。

⚠️ **取消无法中断阻塞的 RPC**：用户在第 100ms 继续打字时，平台会取消上一次
`getSuggestionDebounced`，但 `withContext(Dispatchers.IO) { client.complete(...) }` 
内部的 `.get(1500ms)` **不响应协程取消**，线程仍会占用到 RPC 返回或超时。
连续快速输入时最多可并行几个 IO 线程（受 `Dispatchers.IO` 上限 64 保护，实际不会爆），
但「取消」在这里是**逻辑上的**而不是**物理上的**。

⚠️ **`prefix`/`suffix` 按字符切，不看行边界**（48、54）：
FIM 会因为截断在标识符中间而降低质量。理想做法是把 `prefix` 起点回退到最近的行首
（`document.getLineStartOffset(...)`）。当前实现为了少一次索引查找而放弃了这点。

⚠️ **`file.length` 是字节、`MAX_FILE_BYTES` 也是字节**（42、78）：
与 `ChatAttachment` 的 512KB 阀同值，但这里是**拒绝补全**而不是降级。
所以在一个 600KB 的 `.java` 里打字**完全不会有 ghost text**，且没有任何提示——
用户只会以为「这个功能坏了」。这是无提示降级的典型代价。

## §15 线程模型：谁在哪条线上

前 14 节的所有调用都没标注线程。这一节把它们一次讲清：
**面板里有 5 类线程、10 个定时器、3 处阻塞桥**，任何一处搞错都表现为「界面偶尔不刷新」。

### 15.1 五类线程

| # | 线程 | 进入方式 | 干什么 |
| --- | --- | --- | --- |
| 1 | **EDT** | Swing 自身 | 全部绘制、布局、事件分发 |
| 2 | **RPC reader** | `JsonRpcConnection` 读 stdout 的循环 | 解帧 → `dispatch` → `AgentChatPanel.onCoreMessage` |
| 3 | **App 线程池** | `executeOnPooledThread` | 发 RPC 请求、列历史、搜历史、拉记忆、推设置、**执行工具** |
| 4 | **Core 子进程** | 进程 | Python 侧的 Agent 循环 |
| 5 | **协程** | `Dispatchers.IO` | 只用于行内补全 |

📌 **第 2 条是最容易被忽视的**。读 stdout 的是
`JsonRpcConnection.startReader` 里的一个普通线程，它解析出 `method` 后**直接在读线程上**调用
所有 `notificationHandlers`（`JsonRpcConnection:81-90`），
`AgentCoreService.dispatch`（162-172）也只做 try/catch，**不做 EDT 切换**。
于是：

```
Core stdout
  → reader 线程：JsonRpcConnection.dispatch        (81)
  → reader 线程：AgentCoreService.dispatch          (162)
  → reader 线程：AgentChatPanel.onCoreMessage       (871)
  → reader 线程：ChatSessionPanel.onCoreMessage     (613)   ★ 这里已经在读线程上了
```

### 15.2 14 个分支的线程归属（关键表）

`ChatSessionPanel.onCoreMessage`（613-758）入口第一段（619-630）就在读线程上分流：

```java
if (!SwingUtilities.isEventDispatchThread()) {
    if ("chat/token".equals(method))     { transcript.appendAssistantToken(delta); return; }
    if ("chat/reasoning".equals(method)) { transcript.appendReasoningToken(delta); return; }
    SwingUtilities.invokeLater(() -> onCoreMessage(method, params));
    return;
}
```

| case | 调用的东西 | 线程归属 |
| --- | --- | --- |
| `chat/token` | `ChatView.appendAssistantToken` | 读线程入队（`pendingTokens`）→ EDT 合批落盘 |
| `chat/reasoning` | `ChatView.appendReasoningToken` | 同上（`pendingReasoning`） |
| 其余 12 个分支 | `todoStrip` / `handleConfirm` / `handleAsk` / `finishTask` / `addErrorMessage` … | ✅ 已在入口 `invokeLater` 到 EDT |

📌 **3.3.0 前是「每个分支自己保证」**：`chat/todos`、`ask/withdraw`、`chat/finish` 三处漏切，
表现为清单偶尔不刷新、标签闪一下。现在只在入口做一次守卫，且流式两类保留快速路径
（每帧一个 Runnable 会和 40ms 合流打架，§6.2）。

⚠️ **新增分支时不要再自行 `invokeLater`**：入口已保证在 EDT，重复投递只会让顺序更难推理。
唯一的例外是继续留在快速路径的流式通道。

⚠️ **`finishTask` 仍是影响面最大的调用**：它改状态行 + 输入区 + `owner.onTaskCleared` →
`AgentChatPanel.rebuildTabs`（清空并重建整条标签栏）。现在它在 EDT 上执行，所以只会“闪”，
不会出现跨线程重建。

### 15.3 十个定时器

全 `ui/` 只有 10 处 `new Timer(`（已穷举）：

| 文件:行 | 周期 | 作用 | 空闲时是否停 |
| --- | --- | --- | --- |
| `UiAnim:47` | 15ms | 全局补间 | ✅ `ACTIVE` 空则 `stop`（90-92） |
| `ChatView:99` | 40ms | 流式正文/思考合流 | ❌ 只在 `finishAssistantInternal`/`clear`/`dispose` 停 |
| `ClassicTranscript:60` | 70ms | 经典模式全量重渲 | ❌ 同上 |
| `SessionTabsBar:254` | 40ms | 运行中标签的呼吸点 | ✅ 无运行任务时 `stop`（`syncPulse`） |
| `StatusRow:63` | 60ms | 左侧旋转弧 | ✅ `setBusy(false)` 停（70-73） |
| `AgentMessageView:88` | 500ms | 流式光标闪烁 | ✅ `setStreaming(false)` 停（`removeNotify` 也停） |
| `AgentMessageView:267` | 1200ms | “已复制”提示 | ✅ 单发 |
| `CodeBlockView:79` | 1200ms | 代码块复制提示 | ✅ 单发（但每次新建 Timer） |
| `NoticeView:154` | 1200ms | 提示可复制高亮 | ✅ 单发 |
| `PickerPopup:236` | 300ms | 搜索防抖 | ✅ 单发（旧的不复用） |

📌 **“空闲时零定时器”是本面板的硬指标**：工具窗口经常死在后台，
8 个会话 × 若干定时器会让 EDT 每秒空转几十次。上表中 ✅ 的六处都做了显式停止，
剩下两处（40/70ms 合流）只在「正在流式输出」时运转，也能接受。

📌 **Swing `Timer` 的回调一定在 EDT 上**，所以定时器里的代码**不需要再 `invokeLater`**。
这也是 `ChatView.flushNow`、`UiAnim.tick` 都不做线程判断的原因。

### 15.4 三处阻塞桥

| 桥 | 方向 | 位置 | 风险 |
| --- | --- | --- | --- |
| `conn.request(...).get(timeout)` | 池化 → Core | `InlineCompletionClient:41` | 1.5s 超时；不可取消 |
| `invokeAndWait(dialog)` | 池化 → EDT | `DiffConfirmCard:28`、`CommandConfirmCard:30` | 若误在 EDT 调用直接抛异常 |
| `writeGate.acquire/release` | 池化 ↔ 池化 | `ToolDispatcher:73/86` | 公平锁；持锁期间弹模态框 |

⚠️ **`writeGate` 的持锁时间包含用户思考时间**：`EditTools` 在拿锁后回调
`DiffConfirmCard.confirm`（模态框）。一个会话弹着确认框时，其它会话的写工具全部
`lock()` 阻塞在池化线程上。池化线程是 IntelliJ 共享的（默认上限几十），
所以在极端情况下——多个会话同时写同一项目 + 用户离开电脑——**会把公共线程池占满**。
这不是 UI 代码的问题，但 UI 的模态框正是那个“无限期持锁”的源头（§06 已提过工具侧，
这里补上线程后果）。

📌 **为什么工具执行必须离开 EDT**：注释写得很直白（`ToolDispatcher:60-61`）。
一个 `run_command` 可能跑 30 秒；若在 EDT 上执行，整个 IDE 会冻住。
同理，`DiffConfirmCard` 用 `invokeAndWait` 而不是 `invokeLater`，是为了让**工具线程真正等到答案**——
代价就是上面说的持锁。

### 15.5 一张图

```
        ┌─────────────── Core 子进程 ───────────────┐
        │  Agent 循环 / LLM / 工具派发              │
        └───┬───────────────────────────────▲──────┘
   stdout   │                               │ stdin
            ▼                               │
   ┌─────────────────┐              ┌───────┴────────┐
   │ RPC reader 线程 │              │ App 线程池     │
   │ 解帧 + dispatch │              │ 请求/工具/弹框 │
   └───┬─────────────┘              └───┬────────────┘
       │ onCoreMessage (读线程!)         │ invokeAndWait
       │  ├─ token/reasoning → 入队合批      ▼
       │  └─ 其余 → invokeLater ✔     ┌──────────┐
       └─────────────────────────▶ │   EDT    │ ◀── 10 个 Timer
                                    └──────────┘
```

## §16 度量不变量：为什么高度总是“先算再画”

自绘组件的 bug 有 80% 出在度量上。这一节把全卷散落的规则聚成一张可检查的清单。

### 16.1 三步曲

每一个行级视图（`ChatRowView` 及其子类）都必须按同一顺序工作：

```
① contentWidth(cap)  →  内容需要多宽（可能小于 cap）
② preferredWidth     =  min(cap, max(80, contentWidth(cap)))
③ computeHeight(实际宽) → 在这个宽度下有多高
```

对应代码在 `ChatRowView`（§5.4）：

```java
cap = max(140, avail * ratio)
preferredWidth = min(cap, max(80, contentWidth(cap)))
```

📌 **为什么不能反过来**：高度依赖宽度（折行），宽度不依赖高度。
先算高度就只能用“猜的宽度”，结果是“先画后再改”的闪动。
`MessageRow.measure`（§7.5）把这条写成了三步：先按宽测、窄了重测、按宽度缓存。

### 16.2 七条不变量

| # | 不变量 | 违反后的症状 | 代码位置 |
| --- | --- | --- | --- |
| 1 | **`paintBlock` 之前必须已 `measure`** | 空数组 / 零高 / 下标越界 | 所有 `*View` |
| 2 | **高度缓存必须以宽度为 key** | 拖宽后面板不重新折行 | `MeasuredComponent:17-25`、`InlineLayout:68` |
| 3 | **`totalHeight` 与 `layoutContainer` 用同一套度量** | 滚动条长度与实际内容不符 | `BlockListLayout:129-136` |
| 4 | **`maxAdvance` 是内容宽，`getWidth` 是分配宽** | 气泡右侧留白或文字被裁 | `UserMessageView:79-87` |
| 5 | **行距用 `lineHeight`，文本块高用 `metrics.getHeight()`** | 最后一行下方多/少几像素 | §11.1 |
| 6 | **`JBUI.scale` 只乘一次** | 200% 缩放下间距翻四倍 | `UiMetrics` 全部 |
| 7 | **宽≤ 0 时要有兵底** | 首帧布局算出 1px / 负坐标 | `BlockListLayout:129`、`PickerPopup:355` |

逐条展开它们具体怎么坏：

**#1**：`ParagraphView.paintBlock` 直接用 `layout.lines()`。若 `computeHeight` 未先被调用，
`InlineLayout` 里的行数组是空的——**画面空白但高度正常**（因为 `computeHeight` 里算了），
这是最难查的一类：布局对、内容空。所有视图依靠 Swing 的
`getPreferredSize → setBounds → paint` 顺序保证这点，所以**不能在构造器里画**。

**#2**：`MeasuredComponent.heightFor(width)` 用 `lastWidth/lastHeight` 单槽缓存（§5.3）。
槽位只存“上一次的宽度”，拖拽时会逐帧重算，正确；但**字体变了、宽度没变**时不会重算。

⚠️ **主题换字体后高度不会自动失效**：`UiTheme.refresh()` 只清字体缓存，
不通知任何组件“你的度量过期了”。若新字体行高更大，已上屏的消息会在下一次宽度变化前
**保持旧高**，文字看起来贴边。可复现路径：IDE 设置里换字号 → 面板不重开 → 滚动区高度略微不准。
修法是在 `UiTheme.install()`（当前没被调）里顺便 `Window.repaint()` + 对已知面板
`revalidate()`，但根本方案是让 `MeasuredComponent` 把字体也纳入缓存 key。

**#3**：`BlockListLayout` 同时实现 `totalHeight` 与 `layoutContainer`，两者都调
`block.preferredHeight(width)`。只要有一边用了不同的宽度（例如一边用 `getWidth()`、
一边用 `parent.getWidth()`），就会出现**“能滚到底但底部是空白”**。
这也是为什么 `availableWidth <= 0 → 480` 这个兵底必须写在**一个**地方（129-136）。

**#4**：`UserMessageView.contentWidth`（79-87）返回 `min(textWidth, widest) + padX*2`，
而 `paintBlock` 用 `width - padX*2` 重算文本宽。两者必须一致；
若气泡被拉宽（`super(RATIO, true)` 靠右对齐、`preferredWidth` 小于分配宽），
`paintBlock` 用的是**实际分配宽**，文本仍然贴左——这就是“气泡宽了但字不居中”的来源。

**#5**：两者混用最典型的地方是 `StatusRow`：它的 chip 高用 `metrics.getHeight() + 2`，
而整行高固定 `20`；一旦字体行高超过 18，chip 会被 `min(height-2, …)`（116）夹扁。

**#6**：`UiMetrics` 的方法**已经返回缩放后的值**。误写成
`JBUI.scale(UiMetrics.m())` 不会报错，只会变成 16px——看起来“只是间距大了点”。

**#7**：`BlockListLayout` 的 480 是一个**假宽度**：它只用于“还没分配到宽度时给个合理值”，
不能用于绘制。`PickerPopup` 的 `Math.max(1, getWidth())` 同理。

### 16.3 三条自测手法

写完一个自绘组件后，这三步能捕获绝大多数度量 bug：

1. **拖窗口宽度到 200px 再拉到最大**：看是否出现“文字重叠 / 竖向空白 / 横向滚动条”。
2. **在 200% 缩放下重做一次**：看是否有硬编码像素（本卷的 `DiffConfirmCard`、
   `CommandConfirmCard`、`ChatComposer.chromeHeight` 的 18、`StatusRow.HEIGHT=20` 都会露）。
3. **切一次深浅主题再换字号**：看已上屏的消息是否重排（§16.2 #2 会露）。

## §17 跨层契约：UI 与 Core 的握手清单

UI 不直接调用 Python，全部通过 JSON-RPC。这一节把双向的方法名、字段与**三处已对不上的地方**列清。

### 17.1 Core → Shell：19 种通知，17 个已上屏

先按 `taskId` 分两堆（`AgentChatPanel.onCoreMessage:871-916`）：

**无 `taskId` 的全局帧（3 个）**

| method | params | 处理 |
| --- | --- | --- |
| `core/hello` | — | 状态行置「就绪」（887） |
| `memory/pendingChanged` | `count` | 记忆按钮角标 + 状态行提示（888-896） |
| `session/indexProgress` | `finished` | 未完成→「正在建立会话索引」；完成→「就绪」（897-905） |

📌 其余无 `taskId` 的帧**静默丢弃**（906 `return`）。这是有意的：Shell 只声明自己认识的
全局事件，新版本 Core 多发的东西不会在 UI 上乱跳。协议共 **19 种**通知，这里只列了 17 个
被消费的；剩下两个全局帧 `agent/ping`（3 s 心跳）与 `index/progress`（`index/build` 无人调用）
在 Java 侧没有分支，到达即丢弃（03 卷 §9、07 卷 §17.3）。

**有 `taskId` 的会话帧（14 个）**

| method | 关键 params | 上屏效果 |
| --- | --- | --- |
| `chat/token` | `delta` | 追加到当前正文（40ms 合流） |
| `chat/reasoning` | `delta` | 思考折叠行（§10.9）；不进会话历史 |
| `tool/invoke` | `toolCallId, toolName, permission, params` | **不上屏**，转 `ToolDispatcher` |
| `chat/toolStart` | `toolName, toolCallId, args` | `read_file · src/main/App.java` |
| `chat/toolEnd` | `toolCallId, ok, summary` | `read_file · done · 0.4s · summary（截 120）` |
| `chat/subagent` | `kind, subagentId, actor, capability, model, goal, ok, durationMs, summary` | `agent id · model [cap] · start / done · 12.4s` |
| `chat/artifact` | `kind, title, authorRole` | 一行投稿提示 + 点亮输入区的「交付物」 |
| `chat/notice` | `text` | `NoticeView(INFO)` |
| `chat/todos` | `todos[{content,status}]` | 重算待办条 |
| `agent/confirm` | `confirmId, server, tool, args` | 对话区确认卡 / 兵底对话框 |
| `ask/user` | `askId, questions[]` | 对话区提问卡 / 兵底对话框 |
| `ask/withdraw` | `askId` | 提问卡变灰禁用 |
| `chat/finish` | `usageTotal{in,out}` | 收尾 + 用量提示 + 解绑 taskId |
| `chat/error` | `error{code,message}` | `[错误:code] message` |

⚠️ **路由的时序依赖**（908-914）：没找到 `sessionId ↔ taskId` 映射的帧会 `LOG.debug` 丢掉。
理论上 `chat/token` 可能跑在 `chat/start` 返回之前。但实际的线程顺序把它们串行了：

```
reader 线程：读到 chat/start 的响应帧 → pending.remove(id) → future.complete(msg)
            └─ thenAccept（注册于池化线程）在“完成线程”上执行 = reader 线程
               └─ currentTaskId = taskId; owner.onTaskBound(...)      // 552
reader 线程：继续读下一行（此时 chat/token 才可能到达）
```

📌 `CompletableFuture.thenAccept` 在**完成 future 的那条线程**上同步执行，所以
「登记 taskId」与「读取下一帧」天然串行。910-911 行的注释说「只可能是 start 返回之前到达的帧」，
在单 reader 线程模型下几乎不可能——除非 Core 在另一个线程抢先写出 `chat/token` 而 reader 已经把
两者都读进来了（仍是先后顺序）。这段分支是**防御式的**，不是实际路径。

### 17.2 Shell → Core：14 个请求 + 3 个通知

| 方法 | 类型 | 调用点 | params |
| --- | --- | --- | --- |
| `ide/hello` | notify | `AgentCoreService:187` | `protocolVersion, ide, capabilities["ask-user","team"]` |
| `settings/update` | notify | `AgentCoreService:162` | 模型/端点/密钥/权限/命令白名单/MCP/团队 |
| `chat/start` | request | `ChatSessionPanel:545` | `sessionId, message, contextSnapshot` |
| `chat/cancel` | notify | `ChatSessionPanel:583` | `taskId` |
| `tool/result` | request | `ToolDispatcher:96` | `toolCallId, ok, result, error` |
| `agent/confirmResult` | request | `ChatSessionPanel:908` | `confirmId, approved` |
| `ask/userAnswer` | request | `ChatSessionPanel:1165` | `askId, skipped, answers[{index,picked,custom}]` |
| `session/list` | request | `AgentChatPanel:374` | — |
| `session/get` | request | `AgentChatPanel:735` | `sessionId`（20s 超时） |
| `session/search` | request | `AgentChatPanel:469` | `query, limit` |
| `session/delete` | request | `AgentChatPanel:781` | `sessionId`（返回 `freedBytes`） |
| `memory/pending` | request | `AgentChatPanel:540` | — |
| `memory/promote` | request | `AgentChatPanel:677` | `id` + 可选 `content`（返回值带 `duplicate`） |
| `memory/discard` | request | `AgentChatPanel:687` | `id` |
| `team/list` | request | `ChatSessionPanel:318` / `:335` | —（名册；输入区的团队下拉用它） |
| `team/artifacts` | request | `ChatSessionPanel:406` | `sessionId`（团队黑板的交付物） |
| `inline/complete` | request | `InlineCompletionClient:41` | `prefix, suffix, language, path`（1.5s 超时） |

📌 **`capabilities` 是一次「能力声明」**（`AgentCoreService:187`）：只有列出 `ask-user`，
Core 才会发 `ask/user`；只有列出 `team`，预审卡才走 Shell（否则每次写入单独确认）。
这样旧版 Shell 不会干等一个没有 UI 的提问。
同样的思路适用于以后新增的可选通知——**先声明，再发送**。

📌 **`settings/update` 在每条消息前都推一次**（`ChatSessionPanel:540`），
所以设置项的改动不需要重启 Core；只有三个 Core 路径变了才 `restart()`（§1.9）。

### 17.3 三处已对不上的地方

#### ① `open_files` 其实是附件列表

```java
// ChatSessionPanel.buildContextSnapshot:599-605
if (!attachments.isEmpty()) {
    List<String> paths = ...;      // attachments 的绝对路径
    snapshot.put("open_files", paths);
}
```

字段名叫 `open_files`（打开的文件），实际塞的是**用户手动添加的附件**，而且**只在有附件时才出现**。
真正“当前编辑器文件”在 `current_file`，打开的全部标签页根本没有传。
⚠️ Core 侧若按名字理解为「IDE 里打开的所有文件」，就会得到一个残缺名单。
要么改名为 `attachments`，要么真的填 `FileEditorManager.getOpenFiles()`。

#### ② `sourceLabel()` 比错字段

```java
// AgentChatPanel.PendingMemory:527-529
String sourceLabel() {
    return "correction".equals(source) ? "纠正" : "偏好";
}
```

而 Core 输出的字段是（`core/src/agent_core/memory/pending.py:52-53, 131-137, 198-208`）：

```
source    ∈ { "user_instruction", "user_correction" }
category  ∈ { "user_preference", "correction" }
```

即：`"correction"` 这个值属于 **`category`**，`source` 里叫 `"user_correction"`。
所以 `sourceLabel()` **永远返回「偏好」**，即使这条候选是用户纠正。
修法：`return "correction".equals(category) ? "纠正" : "偏好";`
(这是全卷第二个真正的功能 bug，第一个是 `AskCard.linesY`。)

#### ③ 显示的内容 ≠ 发送的内容

用户气泡只显示打字的文本（+ 附件名一行），而 Core 收到的是文本 + 每个附件的内容块：

```java
515  transcript.addUserMessage(text.isEmpty() ? "（仅附件）" : text, names);
543  params.put("message", ChatAttachment.buildPrompt(userText, attachments, projectRoot));
```

📌 这是**正确的产品行为**（用户不应看到自己文件的全文），但它带来一个后果：
从历史里重新打开会话时，气泡回放的是 `session/get` 返回的 `content`——
也就是**带附件全文的那一大团**。同一个问题，新建时短、回放时长。
要彻底一致，需要 Core 在存储时把附件块单独成字段（当前协议没有）。

### 17.4 i18n 清单

- 两份各 **265 个键**（中文基线 + `_en`），无缺键（已用数量核对）。
- 前缀分布：`panel./busy./status./session./tab./history./memory./composer./todos./ui.*/common./core./action./`，
  其中 `ui.*` 含 `ui.steps.*`（步骤组）、`ui.reasoning.*`（思考行）、`ui.team.*`（团队黑板）。
- 取文案统一走 `AgentBundle.message(key, params...)`。
- ⚠️ **工具行与思考正文不走 bundle**：`read_file · path · done` 这类开发者日志刻意保持英文，
  中英文包都不管；`ui.steps.*` 六个键在中文包里也写成英文（`3 steps` / `running`），
  这样「步骤组本身是英文日志」不会因语言切换而变形。

**一个已解决的问题**（删掉 §10.7 的悬念）：

```properties
todos.header={0}任务清单 {1}/{2}{3}
```

四个占位符，而 `TodoStrip` 传的第一个参数是 `""`。所以渲染结果是
「任务清单 2/5（已完成）」——**{0} 是一个保留槽位**（原本可能放会话名），
不是错位。改文案时保留四个占位符即可。

⚠️ **`{0}` 这种“保留占位符”很容易被当成多余而删掉**，删了之后
`MessageFormat` 会把后面的参数依次前移，结果变成「2任务清单 5/…」。
🔁 凡是 `{n}` 参数由代码传入，就不能只看文案判断“多余”。

### 17.5 曾经「中文串就是语义」，3.3.0 已修复

3.0–3.2 有三处逻辑**不靠字段、只靠字符串内容**判断状态：

| 生产者 | 字符串 | 消费者 | 判断 |
| --- | --- | --- | --- |
| `ChatSessionPanel` | `[工具] X 执行中...` | `ChatView.isRunningTool` | `contains("执行中")` |
| `ChatSessionPanel` | `[子代理] id 开始：goal` | 同上 | `contains("开始：")` |
| `ChatSessionPanel` | `[工具] 失败` / `[子代理] … 失败` | `StepGroupView` | `items.anyMatch(contains("失败"))` |

当年的后果（保留作教训）：

- ⚠️ 把「执行中」改成「正在执行」，步骤组就**不再显示旋转弧**（整组当成已完成）。
  编译器不会提醒，单测也没有覆盖。
- ⚠️ `ToolDispatcher` 的排队提示既不含「执行中」也不含「开始：」，**会被当成一个已完成的步骤**。
  实际看起来是：先出现一行灰色的“排队”，再出现一行动态的“执行中”——可接受，但语义上是错的。

3.3.0 的修复：

1. **状态是参数**：`TranscriptView.addToolLine(text, running, failed)`（§5.2），`ChatView` 不再有
   `isRunningTool`；`StepGroupView.add(line, failed)` 直接累加 `failedCount`。
2. **文案不再决定行为**：工具行改成 `read_file · path · done · 0.4s` 这种英文日志，步骤组
   `summary()` 取 `ui.steps.*`（两包同值英文）。排队提示也改成了
   `queued · waiting for session 2 · edit_file`，并与普通步骤行一样传 `running=false`。
3. **并行安全**：running 由 `activeSteps` 计数决定，不再依赖「最后一行长得像结束」——
   三个工具并发时，前两个结束不会提前熄灭旋转弧。

📌 **留下的契约**：新增任何「隐式状态」前先问一句「这个状态能不能做成参数」；
字符串只用于展示，不参与判断（§19 #6）。

### 17.6 两份 Markdown 规则

自绘模式用 `MarkdownParser`，经典模式用 `MarkdownRenderer`，两者各自实现了一套块级/行内规则：

| 规则 | `MarkdownParser` | `MarkdownRenderer` | 一致？ |
| --- | --- | --- | --- |
| 围栏代码 | ` ``` ` 开头，未闭合也收 | 同样 | ✅ |
| 标题 | `#{1,6}` + 空格 | 同样 | ✅ |
| 引用 | 连续 `>` 合并 | 同样 | ✅ |
| 无序列表 | `- / * / +` | 同样 | ✅ |
| 有序列表 | `\d+[.)]` | 仅 `\d+\.` | ❌ **已不一致** |
| 表格 | 需分隔行且 ≥3 个 `-` | 同样 | ✅ |
| 行内代码 | 单反引号 | 同样（用 `\u0001i\u0001` 占位保护） | ✅ |

📌 **不要求两份渲染结果像素一致**（一份是 Swing 组件、一份是 HTML），
但**块级分词必须一致**，否则同一个回复在两模式下会“结构不同”（例如 `1)` 在自绘里是列表、
在经典里是段落）。改任何一边都要同步另一边——这是本卷最大的隐式耦合。

## §18 测试映射：184 行测试守住了哪三分之一

`src/test` 下只有 2 个文件、**15 个用例、184 行**，与 12,400 行 UI 代码相比约 **1.5%**。
但这两份测试的选点很聪明：它们只测了“纯函数 + 等价性”，
而正是这两类地方最容易在重构中静默出错。

### 18.1 `MarkdownParserTest`（149 行，14 个用例）

| # | 用例 | 行 | 守的是什么 | 对应本卷 |
| --- | --- | --- | --- | --- |
| 1 | `headingKeepsLevelAndText` | 19 | 标题级别不被吃掉 | §8.3 |
| 2 | `consecutiveLinesJoinIntoOneParagraph` | 27 | 段落合并（行间空格） | §8.3 ⑧ |
| 3 | `paragraphStopsAtNextBlock` | 35 | `startsBlock` 终止判定 | §8.3 ⚠️ 规则重复 |
| 4 | `unorderedListKeepsNesting` | 42 | `indentLevel` 两级缩进 | §8.4 |
| 5 | `orderedListKeepsNumbers` | 53 | `marker = "n."` | §8.4 |
| 6 | `fencedCodeKeepsWhitespaceAndLanguage` | 62 | 围栏内不解析 | §8.3 ① |
| 7 | `openFenceStillRendersWhileStreaming` | 70 | **未闭合围栏也成块** | §8.3 ① |
| 8 | `tableParsesHeaderAndRows` | 77 | 表头/分隔行/行 | §8.5 |
| 9 | `quoteAndDivider` | 86 | 引用合并 + HR | §8.3 ③⑤ |
| 10 | `inlineStylesAreRecognised` | 93 | 粗/斜/删/码 | §8.6 |
| 11 | `inlineMarkersNeverLeakIntoPlainText` | 105 | 标记符不泄到正文 | §8.6 |
| 12 | `incrementalMatchesFullParseForEveryPrefix` | 111 | **逐前缀等价** | §8.7 |
| 13 | `incrementalReusesLeadingBlocks` | 123 | `assertSame` 对象复用 | §5.7 |
| 14 | `clearThenReplayResetsCleanly` | 133 | `reset()` 后重建 | §8.7 |

📌 **#12 是全仓性价比最高的一行测试**：

```java
for (int end = 0; end <= document.length(); end++) {
    String prefix = document.substring(0, end);
    assertEquals(MarkdownParser.parse(prefix), parser.feed(prefix), "prefix mismatch at " + end);
}
```

它对一段同时含 8 种块的文本（298 个字符）逐前缀比对「增量结果 == 全量结果」。
这相当于把 `keep/backoff/mergeable` 三个机制的所有组合全跑一遍——
任何一次“回退不够”或“偏移算错”都会在某个前缀上暴露。
**手写样例永远盖不到这种维度的边界。**

📌 **#13 用 `assertSame` 而不是 `assertEquals`**：前者验“同一个对象”（复用成功），
后者就算用了 `equals` 也能过（重建但相等）。这是 `BlockPanel` 前缀比较能成立的前提。

### 18.2 `UiIconsResourceTest`（35 行，1 个用例）

```java
19-20  REFERENCED = ["agent","attach","history","memory","add","settings"]
23-30  每个名字：/icons/<n>.svg 与 /icons/<n>_dark.svg 都必须能 getResourceAsStream
```

它防的是
`UiIcons.icon()` 的“静默降级为空图标”（§11.4）。
📌 已核对：插件里 12 个 svg（6×2）与清单完全一致，
调用点的 6 个名字（`attach`/`memory`/`history`/`settings`/`add`/`agent`）也全在清单里。

⚠️ **清单靠人工同步**（注释写着 “Keep in sync”）：新增一个
`UiIcons.icon("foo")` 时若忘了往 `REFERENCED` 里加，测试依旧全绿，
缺文件就会到运行期才暴露。更稳的做法是在 `UiIcons` 里维护一个
`Set<String> REQUIRED`（生产代码和测试共用）。

### 18.3 没被覆盖到的部分（按“值得补”排序）

| 目标 | 为什么值得测 | 难点 |
| --- | --- | --- |
| `ChatAttachment.buildPrompt` | 纯静态、预算/截断/二进制分支多 | 需临时目录 |
| `DiffConfirmCard.renderDiff` | 已是包级静态（专为测试留口） | 无 |
| `ChatSessionPanel.parseTodos` | 已是 `public static`，只做字段提取 | 无 |
| `PendingMemory.sourceLabel` | 能直接碎 §17.3 的 bug | 内部类，需放宽可见性 |
| `AskCard` 命中编码 | `-3/-2/-4/100*(qi+1)±oi` | 要构造组件（轻） |
| 所有 `paint*` | —— | 需 golden image，成本高，不建议 |

📌 **一条经验**：UI 代码要测，就测「从控件里抽出来的纯计算」。
本卷里现成的纯函数就有 4 个（上表前四行），全部零 Swing 依赖，
补它们不需要任何测试框架魔法。

## §19 易错点汇总（修 bug / 重构前必读）

按“是否影响功能”排序。每条给位置、现象、修法。已修复的两条仍列出，因为它们是后续重构的参照。

| # | 位置 | 类型 | 现象 | 修法 |
| --- | --- | --- | --- | --- |
| 1 | `AskCard.linesY`（60/227/362） | **功能 bug** | 提问正文恒画在 y=0，与标题重叠 | 227 行旁补 `q.linesY = ...` |
| 2 | `PendingMemory.sourceLabel`（527） | **功能 bug** | 纠正类候选永远显示「偏好」 | 改比 `category` |
| 3 | `ChatSessionPanel.onCoreMessage` 入口 | **已修（3.3.0）** | 3.0–3.2 读线程直改 Swing（todos/withdraw/finish） | 入口统一切 EDT |
| 4 | `ChatView.trimOldRows`（436-442） | 逻辑瑕疵 | 揭示旧消息后下一条就回删 41 行 | 恢复时同时抬高上限 |
| 5 | `SessionTabsBar.updateHover`（545-600） | 逻辑瑕疵 | slot/index 混用，hover 渐变串到邻标签 | 统一用全局 index |
| 6 | 中文串语义（§17.5） | **已修（3.3.0）** | 3.0–3.2 改文案就改行为 | 状态参数化 `addToolLine(text, running, failed)` |
| 7 | `open_files`（ChatSessionPanel:604） | 命名错位 | 实际是附件列表 | 改名或真填打开文件 |
| 8 | `TodoStrip:77` | 体验 | 每次推 todos 都重新折叠 | 加 `userOverrode` |
| 9 | `PickerPopup:421` | 绘制 | 长标题压到 chip 下面 | `textWidth` 扣除 chip 宽 |
| 10 | `UiTheme.install` / `UiAnim.cancelAll` / `UiButton.isHoverActive` | 死代码 | 永不执行 | 删除或接线 |
| 11 | `UiIcons.scaled`（EmptyState:129） | 性能 | 每帧分配缩放图标 | 提成字段 |
| 12 | `UiMetrics.SCRATCH`（71） | 性能 | 单槽度量缓存被 7 种字体刷爆 | 改 `Map<Font,…>` |
| 13 | 硬编码像素（900×520 / 720×160 / 18 / 20） | DPI | 200% 下比例不对 | 走 `JBUI.scale` |
| 14 | 三份 `ellipsize` | 一致 | 放不下时返回值不同 | 合并到 `UiMetrics` |
| 15 | `ChatComposer` 附件去重 | Windows | `Path.equals` 分大小写 | `equalsIgnoreCase`/toRealPath |
| 16 | `MarkdownRenderer` 的 `OL_ITEM` | 一致 | `1)` 两模式结果不同 | 两边同步正则 |
| 17 | `InlineCompletionProvider` 512KB | 体验 | 大文件无 ghost text 也无提示 | 状态栏提示 |

四条的展开说明：

**#1 `linesY`**（§10.5）：声明了 `int linesY`，`ensureLayout` 只赋 `titleY`，
`paintQuestion` 读 `linesY` 得到 0。
影响：只要用过 `ask_user`，问题正文就叠在标题上。
这是全卷**唯一一眼能看出来的布局 bug**，也是最容易修的（一行）。

**#2 `sourceLabel`**（§17.3）：字段名对不上，结果永远是「偏好」。
影响：用户看不到“这条候选来自对之前说法的纠正”，而这恰恰是最需要确认的一类记忆。
修法同样是一行，但需要先知道 Core 的字段定义（这正是跨层契约要写进文档的原因）。

**#3 读线程改 Swing**（§15.2）：3.3.0 前有三处漏切线程，表现为清单偶尔不刷新、
关闭/完成时标签条件性闪烁。现在入口统一切 EDT，流式两类走快速路径。
新增分支不要再自行 `invokeLater`。

**#4 `trimOldRows`**（§6.5）：`while (rows.size() - hiddenFrom > 160)` 会把已挂载行
稳定在 160；“查看更早”展开 40 行后，下一条消息就再删 41 行。
修复思路：把 160 改成字段 `mountLimit`，`revealOlder` 时 `mountLimit += REVEAL_BATCH`。

## §20 自测清单

### A. 改代码之前先确认

- [ ] 改的是 `paint*` 吗？→ 回看 §16.2 的 7 条不变量。
- [ ] 改的是工具/思考行的展示吗？→ 状态走 `addToolLine(text, running, failed)` 参数，
      不要从文本里嗅探（§6.7）；新文案键同步两份 bundle。
- [ ] 新增了 Core 事件？→ 三处同时改：`ChatSessionPanel.onCoreMessage`、§17.1 表、
      `plugin.xml` 的 `capabilities`（若是可选通知）。
- [ ] 新增了文案键？→ 两份 properties 都加（265 对齐），且占位符个数与调用一致。
- [ ] 新增了图标？→ 加 `_dark` 变体 + 更新 `UiIconsResourceTest.REFERENCED`。
- [ ] 改了 Markdown 规则？→ `MarkdownParser` 与 `MarkdownRenderer` 两边同步（§17.6）。
- [ ] 新组件在构造器里碰了 Swing？→ 确认所在线程（§15）。

### B. 手动冒烟（每项 < 1 分钟）

1. **流式长回复**：发一个会输出代码块 + 表格 + 列表的请求，
   观察 40ms 合流是否平滑、光标是否流式闪烁、是否自动跟底。
2. **向上滚动到一半**：确认不再自动跳底，右下“回到底部”胶囊出现，点击能平滑回底。
3. **连发 200 条**（或临时把 `KEEP_ROWS` 改小）：确认“查看更早 N 条”出现且点击能展开
   （展开后回删 41 行是已知瑕疵，§19 #4）。
4. **并行 3 个会话**：同时发消息，确认 token 不串台，标签上的呼吸点只在运行中的标签上。
5. **拖窄到 200px**：看气泡/表格/代码块是否重叠或溢出（表格列宽 `max(48, …)` 会先碎）。
6. **缩放到 200%**：对比两个模态框（`DiffConfirmCard`/`CommandConfirmCard`）与主面板的比例。
7. **切主题 + 改字号**：已上屏消息是否重排（§16.2 ⚠️ 会露）。
8. **工具全链路**：`read_file` → `write_file`（弹 diff）→ 拒绝一次、允许一次；
   `run_command`（非白名单弹确认）；`ask_user`（回答一次、Esc 跳过一次）。
9. **内存/历史弹层**：输入搜字（300ms 防抖）、↑↓ 选行、Enter 打开、Esc 关闭、
   删除一行后确认弹层不残留、待确认角标数字跟着变。
10. **行内补全**：在一个 < 512KB 的文件里停 300ms，看 ghost text；在大文件里确认“没有”
    且不报错。

### C. 提交前 grep 清单

```
contains("执行中") | contains("开始：")   → 不应再出现（状态已参数化，§6.7）
\[工具\] | \[子代理\]              → 旧中文前缀是否已彻底清掉
appendReasoningToken(          → 新渲染是否实现，旧渲染是否保留空实现
open_files              → 是否已改名/已真填
sourceLabel             → 是否已改比 category
UiTheme.install(        → 仍是零调用则考虑删除
UiAnim.cancelAll(       → 同上
new Timer(              → 新增定时器是否会在空闲时停止
```

## §21 本卷收尾：一页纸回顾

### 21.1 这一卷讲了什么

```
§0  导览：51 文件 / 12,400 行；三条渲染线；记号约定
§1  装配与容器：AgentToolWindowFactory → AgentChatPanel（8 会话、5 集合、14 路路由）
§2  会话面板：一次发送 → chat/start → 14 个事件 → 收尾
§3  输入区：ChatComposer（键位、附件三入口、三级降级 placeholder）
§4  标签栏：SessionTabsBar（自绘、收缩算法、呼吸点）
§5  门面与基座：ChatTranscript / MeasuredComponent / ChatRowView / BlockPanel
§6  自绘对话区：ChatView（40ms 合流、七种行、双脏标记、卸载与揭示）
§7  经典模式：ClassicTranscript（70ms 全量 HTML）
§8  Markdown 解析：8 种块 + 8 个行内组 + 增量尾部
§9  块视图族：InlineLayout + 8 个 View + TextCopy
§10 卡片族：Agent/User/Step/ToolCall/Ask/Empty/Todo/Status/Reasoning
§11 设计系统：UiMetrics / UiTheme / UiAnim / UiIcons / UiButton
§12 列表弹层：PickerPopup（历史与记忆共用）
§13 附属件：ChatAttachment / 两个模态框 / AgentBundle
§14 行内补全：Kotlin provider + Java client
§15 线程模型：5 类线程 / 10 个定时器 / 3 处阻塞桥
§16 度量不变量：7 条 + 3 个自测手法
§17 跨层契约：19 种通知 / 14 个请求 / 3 处错位 / i18n / 中文串 / 双份规则
§18 测试映射：184 行 / 15 个用例 / 4 个值得补的纯函数
§19 易错点：17 条（2 个真 bug、1 个线程 bug、14 处瑕疵与隐患）
§20 自测清单：A 改前 / B 冒烟 10 项 / C 提交前 grep
```

### 21.2 如果只记三件事

1. **一个视图就是一个 `(宽度 → 高度)` 纯函数**，所有绘制都基于预先算好的度量；
   违反它就是“重叠 / 空白 / 闪烁”。
2. **状态不要藏在文字里**：3.0–3.2 靠 `执行中`/`开始：`/`失败` 嗅探，改文案就改行为；
   3.3.0 已改为 `addToolLine(text, running, failed)`。新功能同样如此：能做成参数就别做字符串。
3. **读线程 ≠ EDT**：入口已统一守卫（§15.2），流式两类走快速路径；
   写新的 `onCoreMessage` 分支时直接改 Swing 即可，但**不要重复 `invokeLater`**。

### 21.3 留待修复的清单（不改代码，只记录）

| 优先级 | 项 | 工作量 |
| --- | --- | --- |
| P0 | `AskCard.linesY` 未赋值 | 1 行 |
| P0 | `PendingMemory.sourceLabel` 比错字段 | 1 行 |
| P1 | 三处读线程改 Swing | 入口加 3 行守卫 |
| P1 | `open_files` 命名/内容不符 | 改名或补数据 |
| P2 | `trimOldRows` 揭示后回删 | 引入 `mountLimit` |
| P2 | `SessionTabsBar` slot/index 混用 | 统一 index |
| P3 | 两处死代码 + 三份 ellipsize + 硬编码像素 | 清理 |
| P3 | 4 个纯函数补单测 | 半天 |

### 21.4 下一卷：第 09 卷《另一个 Shell、脚本与冒烟》

Java 侧到 §21 就完整了。剩下三块不在本卷范围：

| 块 | 规模 | 为什么单独成卷 |
| --- | --- | --- |
| `vscode/` | 17 文件约 3,060 行（TS 源码 10 个 2,511 行） | **第二个 Shell**：同一套 RPC 协议的另一个实现 |
| `scripts/` + `core/scripts/` | 6 个 ps1 + 10 个 smoke 脚本（+ 2 个 Node 检查脚本） | 构建、验证与端到端冒烟的唯一入口 |
| `src/test` | 2 文件 184 行（本卷 §18 已覆盖） | 已讲完，不再重复 |

第 09 卷的重点将是**对照阅读**：

- `vscode/src/panel.ts`（614）与 `ChatSessionPanel`（1330）逐函数对比：
  同一个 `chat/start` + 14 个事件，Webview 怎么做渲染？
- `vscode/src/tools.ts`（521）与 `ToolDispatcher`（127）：
  同一个 `tool/invoke`，Node 侧为什么需要 4 倍代码？
- `vscode/src/rpc.ts`（148）与 `JsonRpcConnection`（138）：两份逐行几乎同构的实现。
- `vscode/src/secrets.ts`（47）：API Key 存哪里，与 Java 侧的 `ModelProfiles` 如何对齐。
- `scripts/build_plugin.ps1` / `build_core.ps1` / `build_native.ps1` / `verify_all.ps1`：
  构建链与第 02 卷（pom/assembly）的对应关系。
- `core/scripts/smoke_*.py` 9 个脚本：每个协议能力对应的端到端验证。

> 📌 **阅读建议**：先把本卷 §17 的协议表当作“字典”，再去读 VS Code 的
> `rpc.ts` —— 你会发现两边处理的是同一批方法名，只是宿主语言与渲染技术不同。
> 顺序反过来（先读 TS）也可以，但会多花一倍的力气。

