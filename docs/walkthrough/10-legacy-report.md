# 第 10 卷：旧功能本体——日报/周报生成与设置页

> 前九卷讲的都是 **Agent 子系统**（`com.workreport.agent` + `core` + `vscode`）。
> 这一卷回到仓库名字本身：`workreport` 原本是一个「从 git log 生成工作汇报」的插件。
> 它的数据层还活着、设置层还活着，而界面层已经死了——**为什么死、死在哪，是这一卷的主线。**

---

## 0. 卷首：先判断"哪些代码还活着"

### 0.1 覆盖清单

```
src/main/java/com/workreport/plugin/          11 个文件 / 约 2,340 行
├── model/GitCommitEntry.java                  18   单条提交的不可变载体
├── privacy/PrivacyNoticeUtil.java             80   首次润色的隐私确认弹窗
├── service/GitLogCollectorService.java       282   git log → 提交列表 → 草稿
├── service/ReportPolishService.java          451   直连 LLM 的润色（不经过 Core）
├── settings/ModelEndpoints.java               53   端点 → 协议类型的推断
├── settings/ModelProfile.java                 47   一条可切换的模型配置
├── settings/ModelProfiles.java               117   配置列表 + 每模型 API Key
├── settings/ReportSettingsConfigurable.java  381   设置页（整棵设置树的唯一入口）
├── settings/ReportSettingsState.java         133   全局持久化状态
├── ui/ReportToolWindowFactory.java            23   （已从 plugin.xml 摘除）
└── ui/ReportToolWindowPanel.java             754   （已从 plugin.xml 摘除）
```

### 0.2 判定方法：以 `plugin.xml` 为唯一注册表

IntelliJ 插件里，一个类要被执行，必须出现在 `plugin.xml` 的扩展点、服务或 Action 里
（或者被这些类 `new` 出来）。所以判断「死代码」不需要读全仓库，只需要三步：

```
① 在 plugin.xml 里搜类名            → 没有 = 不是入口
② 全仓库 grep 这个类名              → 只有它自己 = 没人 new 它
③ 顺着引用链往上走                  → 链条尽头没挂在 plugin.xml 上 = 死代码
```

对整个仓库只有**一个** `plugin.xml`（`src/main/resources/META-INF/plugin.xml`，143 行），所以这个判定是完备的。

按这个方法跑一遍，结论分成三类：

| 类别 | 文件 | 依据 |
|---|---|---|
| **活跃 · 被 Agent 复用** | `GitLogCollectorService`、`GitCommitEntry` | `GitTools.java:37` 直接 `new` 它来服务 `git_log` 工具 |
| **活跃 · 设置与状态** | `ReportSettingsState`、`ReportSettingsConfigurable`、`ModelProfile(s)`、`ModelEndpoints` | `plugin.xml:106-109` 注册设置页；`AgentCoreService` 读同一份 State |
| **孤儿 · 无入口** | `ReportToolWindowFactory`、`ReportToolWindowPanel`、`ReportPolishService`、`PrivacyNoticeUtil` | 工厂未注册，面板只被工厂 `new`，润色服务与隐私工具只被面板用 |

一句话概括这次演进的形态：

```
数据层被吸收   （git 采集 → 成为 Agent 的一个工具）
设置层被共用   （同一份 XML、同一个 Service 实例）
界面层被抛弃   （独立面板 → 换成 Agent 聊天面板）
```

`plugin.xml` 的 `<description>` 把这件事写得很直白：

```xml
 9          The classic work-report generator is being migrated into a built-in skill.
11          AI 编码助手：……经典的工作报告生成器将迁移为内置 Skill。
```

「迁移」不是删除。旧代码留在树里，好处是数据层不用重写；代价是**读者会以为它还活着**。
这就是为什么这一卷先讲判定方法，再讲代码。

### 0.3 一个能立刻验证的入口

`Tools` 菜单里那个「生成报告」还在，但它已经**不是旧面板的入口**了：

```java
// src/main/java/com/workreport/agent/actions/GenerateReportAction.java
17      private static final String TOOL_WINDOW_ID = "AgentCoder";
18      private static final String PRESET = "生成今天的日报";
...
26          ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
27          if (window != null) {
28              window.show();
29          }
30          AgentChatPanel panel = AgentChatPanel.find(project);
...
38          if (panel != null) {
39              panel.sendPresetMessage(PRESET);
40          }
```

注意它 import 的是 `com.workreport.agent.ui.AgentChatPanel`（包名 `agent`，不是 `plugin`）。
也就是说：**同一个菜单项，从「打开旧面板」改成了「往 Agent 面板里塞一句话」**，由内置
`daily-report` Skill 去驱动 git 采集。

这是一个典型的重构手法——**保住用户熟悉的入口，换掉入口后面的所有东西**。
用户看到的还是「生成报告」，但他实际得到的是一个能追问、能改、能接着做的对话。

---

## 1. 装配：`plugin.xml` 的五处注册

### 1.1 注册清单

`plugin.xml` 的 `<extensions>` 段只有四项，加上 `<actions>` 里的两条，总共六处：

| # | 扩展点 / 组 | id | 实现类 | 作用 |
|---|---|---|---|---|
| 1 | `toolWindow` | `AgentCoder` | `agent.ui.AgentToolWindowFactory` | 底部聊天面板 |
| 2 | `applicationConfigurable` | `com.workreport.settings` | `plugin.settings.ReportSettingsConfigurable` | 设置页 |
| 3 | `inline.completion.provider` | `com.workreport.agent.inline` | `agent.inline.AgentInlineCompletionProvider` | 行内补全 |
| 4 | `applicationService` | —— | `plugin.settings.ReportSettingsState` | 持久化状态 |
| 5 | `action` | `AgentCoder.OpenSettings` | `agent.actions.OpenSettingsAction` | Tools 菜单 → 设置页 |
| 6 | `action` | `AgentCoder.GenerateReport` | `agent.actions.GenerateReportAction` | Tools 菜单 → 生成日报 |

```xml
 98          <toolWindow id="AgentCoder"
 99                      anchor="bottom"
100                      icon="/icons/agent.svg"
101                      factoryClass="com.workreport.agent.ui.AgentToolWindowFactory"/>
...
106          <applicationConfigurable
107                  instance="com.workreport.plugin.settings.ReportSettingsConfigurable"
108                  id="com.workreport.settings"
109                  parentId="tools"/>
...
113          <inline.completion.provider
114                  id="com.workreport.agent.inline"
115                  implementation="com.workreport.agent.inline.AgentInlineCompletionProvider"
116                  order="first"/>
...
120          <applicationService
121                  serviceImplementation="com.workreport.plugin.settings.ReportSettingsState"/>
```

两处细节值得记住：

**（1）`instance=` 不是 `implementation=`。**
`applicationConfigurable` 同时支持两种写法：`instance` 要求类有无参构造、不缓存实例；
`implementation` 走 `Configurable` 的 provider 机制。这里用 `instance`，意味着每次打开设置都可能新建一个
`ReportSettingsConfigurable` 对象——所以它的所有字段都是「一次会话」的临时状态，真正的数据只在
`ReportSettingsState` 里。

**（2）`parentId="tools"`。**
设置页挂在 `Settings → Tools` 下面。`OpenSettingsAction` 用同一个 id 直接跳过去：

```java
14      private static final String SETTINGS_ID = "com.workreport.settings";
...
19          ShowSettingsUtil.getInstance().showSettingsDialog(event.getProject(), SETTINGS_ID);
```

注意注释里那句话——**它是 application 级配置，所以没有打开项目也能用**：

```java
18          // The configurable is application-level, so it also works without an open project.
```

这一点很关键：`ShowSettingsUtil.showSettingsDialog(null, id)` 在无项目场景下是合法的，
而 toolWindow 不是。两个入口的生命周期差异就从这里来。

### 1.2 `ReportToolWindowFactory` 为什么还在树里

```java
// src/main/java/com/workreport/plugin/ui/ReportToolWindowFactory.java
12  public final class ReportToolWindowFactory implements ToolWindowFactory {
...
18          ReportToolWindowPanel panel = new ReportToolWindowPanel(project);
```

它只有 23 行，逻辑就是「创建一个面板」。它没被注册，所以永远不会被平台调用；
面板也只被它 `new`。两者的引用链到此为止。

> **为什么不删？**
> 因为 `GitLogCollectorService` 和 `ReportPolishService` 被面板持有，面板被工厂持有，
> 而这两个 Service 里有不少可以复用的逻辑（尤其 git 那段）。删面板等于先要把可复用部分抽出来，
> 属于「需要一次专门重构」的工作，不属于随手清理。**遗留代码最常见的死法就是这种「删不干净」**。

---

## 2. 设置层：一个 `@State` 服务，两套功能共用

这是本卷最重要的一节。**`ReportSettingsState` 是唯一配置源**，Agent 子系统和旧报告功能读的是同一个实例。

### 2.1 声明与持久化位置

```java
// src/main/java/com/workreport/plugin/settings/ReportSettingsState.java
12  @State(name = "WorkReportSettings", storages = @Storage("workReportSettings.xml"))
13  @Service(Service.Level.APP)
14  public final class ReportSettingsState implements PersistentStateComponent<ReportSettingsState> {
```

| 注解 / 参数 | 含义 | 落点 |
|---|---|---|
| `@State(name = "WorkReportSettings")` | XML 根节点的名字 | `<component name="WorkReportSettings">` |
| `@Storage("workReportSettings.xml")` | 单个配置文件 | `<IDE 配置目录>/options/workReportSettings.xml` |
| `@Service(Service.Level.APP)` | 整个 IDE 一份 | 与「每个项目一份」相对 |

`Level.APP` 是一个有意为之的选择：模型配置、API Key、并发参数都是**机器级偏好**，不该跟项目走。
代价是：**同一个 IDE 打开两个项目，它们共享模型列表**；而会话数据是 Core 按 `projectId` 分开的，
所以「配置共享、数据隔离」是这套系统的默认形态。

另外 `plugin.xml:120-121` 又把它登记成 `applicationService`。两处声明的效果一致
（`@Service` 注解在 2019.3+ 就能自动注册），保留 XML 声明是为了显式可见 + 兼容旧阅读习惯。

### 2.2 二十三个字段，逐个说清归属

字段是这一卷的「协议」：谁写它、谁读它，决定了改动的影响面。

| 字段 | 默认值 | 写入方 | 读取方 |
|---|---|---|---|
| `reportType` | `"weekly"` | 旧面板（切类型，`onReportTypeChanged`） | 旧面板（初始化时 L140）——**无活消费方** |
| `apiEndpoint` | `http://localhost:11434/api/chat` | 设置页 `apply()`（从 active profile 镜像） | `ReportPolishService`、`PrivacyNoticeUtil`（均无活调用方） |
| `modelName` | `qwen2.5:7b` | 同上 | 仅 `ReportPolishService` |
| `useOllamaMode` | `true` | 同上（`ModelEndpoints.isOllama`） | `ModelProfiles.resolve`（升级兜底）+ `ReportPolishService` |
| `privacyAccepted` | `false` | `PrivacyNoticeUtil` / `apply()` 重置 | `PrivacyNoticeUtil` |
| `authorFilter` | `""` | 设置页 | `GitLogCollectorService` |
| `mcpServersJson` | `""` | 设置页 | `AgentCoreService` → Core |
| `coreExePath` | `""` | 设置页 | `PythonLocator` / `CoreProcess` |
| `coreHomePath` | `""` | 设置页 | 同上 |
| `pythonPath` | `""` | 设置页 | 同上 |
| `modelProfilesJson` | `""` | 设置页 `apply()` | `ModelProfiles.parse` |
| `activeProfile` | `""` | 设置页 `apply()` | `ModelProfiles.active` |
| `autoAcceptEdits` | `false` | 设置页 *（无对应控件）* | `ToolDispatcher.java:67` |
| `inlineCompletionEnabled` | `true` | 设置页 | `AgentInlineCompletionProvider.isEnabled` |
| `embeddingModel` | `nomic-embed-text` | 设置页 | `AgentCoreService` → Core |
| `maxIterations` | `40` | 设置页 | `AgentCoreService` → Core |
| `contextWindow` | `32000` | 设置页 | 同上 |
| `toolConcurrency` | `4` | 设置页 | 同上 |
| `subagentConcurrency` | `3` | 设置页 | 同上 |
| `temperature` | `0.2` | 设置页 | 同上 |
| `maxTokens` | `4096` | 设置页 | 同上 |
| `commandAllowlist` | `""` | 设置页 | 同上 |

读完这张表会注意到三件事：

**（1）五个字段的消费方全在死代码里**：`reportType`、`privacyAccepted`、`apiEndpoint`、`modelName`，
以及 `useOllamaMode`（唯一例外：`ModelProfiles.resolve` 在做旧 XML 升级时还会读它）。其余全是 Agent 的。

`authorFilter` 看着像旧功能，其实**还活着**：`GitLogCollectorService.collect()` 每次调用都读它，
而 `GitTools.log`（Agent 的 `git_log` 工具）复用的正是同一个方法——
一个废弃设置项仍在悄悄过滤 Agent 的工具结果（详见 §7.5）。

**（2）`autoAcceptEdits` 在设置页里没有控件。**
它被 `ToolDispatcher` 读、被 VS Code 扩展写（`vscode/src/settings.ts`），但 Java 设置页的 15 个控件里
没有它。也就是说：**IntelliJ 用户无法从界面打开「自动接受修改」**，只能手改 XML。
这是一个明确的接口缺口（第 9 卷 §9.3 已从另一侧提到过同一个开关）。

**（3）`apiEndpoint` / `modelName` / `useOllamaMode` 是「镜像字段」。**
它们不是独立配置，而是 `apply()` 时从当前选中的 profile 抄过来的（`ReportSettingsConfigurable.java:237-242`）。
保留它们是为了两件事：
- 旧 `ReportPolishService` 还在直接读这三个字段；
- 旧 XML 升级上来时，用户原来的单模型配置能直接变成一个 profile（`ModelProfiles.resolve` 的兜底）。

### 2.3 `loadState`：十行 `!= null ? : default` 的理由

```java
 92      @Override
 93      public void loadState(@NotNull ReportSettingsState state) {
 94          this.reportType = state.reportType != null ? state.reportType : "weekly";
 95          this.apiEndpoint = state.apiEndpoint;
 96          this.modelName = state.modelName;
...
109          // Older XML files have no value here (deserializes as 0) — keep the default.
111          this.maxIterations = state.maxIterations > 0 ? state.maxIterations : 40;
112          this.contextWindow = state.contextWindow > 0 ? state.contextWindow : 32000;
113          this.toolConcurrency = state.toolConcurrency > 0 ? state.toolConcurrency : 4;
114          this.subagentConcurrency = state.subagentConcurrency > 0 ? state.subagentConcurrency : 3;
115          this.temperature = state.temperature > 0 ? state.temperature : 0.2;
116          this.maxTokens = state.maxTokens > 0 ? state.maxTokens : 4096;
```

这段代码在做两件不一样的事，混在一起容易看错：

| 类型 | 判断 | 原因 |
|---|---|---|
| `String` 字段 | `!= null ? : ""` | 老 XML 没有这个节点 → 反序列化成 `null`，后续 NPE |
| `int` / `double` 字段 | `> 0 ? : 默认值` | 老 XML 没有这个节点 → 反序列化成 **`0`**（基本类型不能是 null） |

第二种是 IntelliJ 持久化的经典坑：**新增一个 `int` 配置项，老用户读出来是 0**。
如果直接信任它，`maxIterations = 0` 会让 Agent 一轮就停，`maxTokens = 0` 会让请求直接失败。
用「0 视为未配置」来兜是最省事的写法，代价是 **`0` 不能作为合法业务值**——
这也解释了 `ReportSettingsConfigurable.parseIntField(..., min = 5/4000/1/256, ...)` 为什么所有下限都大于 0。

> 顺带一个反面例子：`temperature` 用 `> 0` 判断，而它的合法范围是 `0.1 ~ 1.5`。
> 所以 `temperature = 0`（确定性的贪婪解码）在语义上是合法的，但会被这里改写回 `0.2`。
> 对这份代码来说无所谓（设置页最小 0.1），但对「想用温度 0 做可复现输出」的人是踩坑的。

### 2.4 `resetPrivacyIfEndpointChanged`：一条安全设计

```java
120      // Reset privacy confirmation when API endpoint changes (require re-confirmation)
122      public void resetPrivacyIfEndpointChanged(String newEndpoint) {
123          if (!apiEndpoint.equals(newEndpoint)) {
124              privacyAccepted = false;
125          }
126      }
```

调用点只有一处，在设置页 `apply()` 里：

```java
237          // Mirror the active profile into the legacy single-model fields.
238          ModelProfile current = ModelProfiles.active(state);
239          state.apiEndpoint = current.baseUrl;
240          state.modelName = current.model;
241          state.useOllamaMode = ModelEndpoints.isOllama(current.provider, current.baseUrl);
242          state.resetPrivacyIfEndpointChanged(current.baseUrl);
```

**顺序是关键**：先赋值 `state.apiEndpoint = current.baseUrl`，然后才把 `current.baseUrl` 传进去比较。
此时 `apiEndpoint` 已经等于 `newEndpoint`，`equals` 恒为 `true`，**`privacyAccepted` 永远不会被重置**。

这是一个真实存在的逻辑顺序 bug。正确的写法是在赋值之前比较：

```java
boolean endpointChanged = !current.baseUrl.equals(state.apiEndpoint);
state.apiEndpoint = current.baseUrl;
if (endpointChanged) {
    state.privacyAccepted = false;
}
```

它带来的后果是：用户同意过一次隐私提示后，**改成任意云端端点都不会再被提醒**——
而这条提示的存在意义恰恰是「换端点后要重新告知数据会发到哪里」。

> 这一类 bug 在测试里很难被发现，因为单看 `resetPrivacyIfEndpointChanged` 方法本身，它是完全正确的；
> 错在调用点的语句顺序。写这类「比较 + 赋值」的方法时，**方法应当自己完成比较，而不是要求调用方保持顺序**——
> 把 `String newEndpoint` 改成 `void applyEndpoint(String newEndpoint)` 就不会有这个问题。

### 2.5 `isApiConfigured`：一个判定撑起一个按钮

```java
128      // Check if API is configured (non-empty endpoint)
130      public boolean isApiConfigured() {
131          return apiEndpoint != null && !apiEndpoint.isBlank();
132      }
```

只用「端点非空」判断「能不能润色」。这意味着：端点填成 `http://x` 也算已配置，按钮会亮，
点了才失败。`GitLogCollectorService` 那边同样是「填错就等到运行时报错」。
旧功能整体的风格是**懒校验**：设置页不校验，运行时报错。

对照 Agent 那套：`AgentCoreService` 在启动 Core 前会做 `PythonLocator` 探测、端口/路径校验，
失败会直接显示在状态行。新系统明显更愿意「早点告诉你」。

---

### 2.6 `ModelProfile`：47 行里藏着一个契约

```java
// src/main/java/com/workreport/plugin/settings/ModelProfile.java
 5  public final class ModelProfile {
 7      public String name = "";
 8      public String provider = "ollama";   // "ollama" | "openai_compat"
 9      public String baseUrl = "";
10      public String model = "";
...
22      public boolean isValid() {
23          return name != null && !name.isBlank()
24                  && provider != null && !provider.isBlank()
25                  && model != null && !model.isBlank();
26      }
```

三件事值得注意：

**（1）`baseUrl` 不参与 `isValid()`。**
空端点是合法的。这跟 `isApiConfigured()` 的口径不同——一个说「端点可以空」，一个说「端点空就不算配置」。
两者用的是不同层级的判断，不算矛盾，但读的时候要分清。

**（2）`name` 是主键，不是显示名。**
类注释第一行就写了：

```java
 3  // One switchable model profile: name is the stable id used for the API key lookup.
 4  // 一条可切换的模型配置：name 作为 API Key 的稳定索引。
```

所以 `name` **一改，API Key 就丢**（key 存在 `apiKey:<name>` 这个槽位里）。
设置页把整张表当 `DefaultTableModel`，第 0 列可编辑——用户改个名字，旧 key 就再也读不到了，
而且不会有任何提示。这是「把主键做成可编辑单元格」的代价。

**（3）手写 `equals` / `hashCode`，而不是 record。**
项目已经用 Java 21（`pom.xml` 的 release 21），完全可以写成 `record ModelProfile(...)`。
但它是 Gson 反序列化的目标类型，且字段是 `public` 非 `final` 的可变风格。
`equals` 被 `ReportSettingsConfigurable.isModified()` 用来做「表格是否被改动」的判断：

```java
190          return !collectProfiles().equals(ModelProfiles.parse(state.modelProfilesJson))
```

所以**顺序**也参与相等判定（`List.equals` 是逐位比较）。用户拖动排序（虽然当前没有实现）也算修改。

### 2.7 `ModelProfiles`：解析、兜底、和两代 API Key

四个静态方法的职责分得很清楚：

```
parse(json)         JSON → List<ModelProfile>，跳过非法项，坏 JSON 不抛
toJson(list)        List<ModelProfile> → JSON
resolve(state)      空列表时用旧的单模型字段造一条「默认」profile
active(state)       按 activeProfile 找；找不到就用第一条；一条都没有就用硬编码默认
```

**（1）`parse` 的容错**

```java
34          try {
35              ModelProfile[] parsed = GSON.fromJson(json, ModelProfile[].class);
36              if (parsed != null) {
37                  for (ModelProfile profile : parsed) {
38                      if (profile != null && profile.isValid()) {
39                          result.add(profile);
40                      }
41                  }
42              }
43          } catch (JsonSyntaxException e) {
44              LOG.warn("invalid model profiles JSON, ignored", e);
45              return result;
46          }
```

坏 JSON 只是 `LOG.warn` 然后返回空列表，**不抛异常**。调用链上游是 `resolve` → `active` →
`AgentCoreService` 读配置。也就是说：配置写坏了，插件不会崩，而是**静默回退到默认 Ollama**。
用户看到的现象是「我的云端模型不生效了」，日志里只有一条 warn。这是容错的典型代价。

注意 `GSON.fromJson` 抛的是 `JsonSyntaxException`，但它也可能抛 `IllegalStateException`
（JSON 是数组元素而不是对象时）。后者没被捕获——一个 `[1,2,3]` 的配置会让这里抛到 `active()` 的调用方。

**（2）`resolve` 的兜底就是「升级路径」**

```java
55      public static List<ModelProfile> resolve(ReportSettingsState state) {
56          List<ModelProfile> profiles = parse(state.modelProfilesJson);
57          if (!profiles.isEmpty()) {
58              return profiles;
59          }
60          ModelProfile fallback = new ModelProfile(
61                  DEFAULT_NAME,
62                  state.useOllamaMode ? "ollama" : "openai_compat",
63                  state.apiEndpoint,
64                  state.modelName);
65          if (fallback.isValid()) {
66              profiles.add(fallback);
67          }
```

对于从 2.x 升级上来的用户，`modelProfilesJson` 是空的，但 `apiEndpoint` / `modelName` 有值。
这里把它们包装成一条名叫 `默认`（`DEFAULT_NAME`）的 profile。**旧配置无需迁移脚本就能继续用**。

**（3）两代 API Key：`apiKey` 的兼容读取**

```java
20      private static final String CREDENTIAL_SERVICE_NAME = "WorkReportPlugin";
21      private static final String LEGACY_API_KEY = "apiKey";
22      private static final String PROFILE_PREFIX = "apiKey:";
...
90      public static String apiKey(String profileName) {
91          if (profileName == null || profileName.isBlank() || DEFAULT_NAME.equals(profileName)) {
92              String legacy = apiKeyValue(LEGACY_API_KEY);
93              if (legacy != null && !legacy.isEmpty()) {
94                  return legacy;
95              }
96          }
97          return apiKeyValue(PROFILE_PREFIX + profileName);
98      }
```

读顺序是 **先旧后新**，但**只对「默认」这个 profile 才看旧槽位**：

| 场景 | 读哪个槽 | 结果 |
|---|---|---|
| profile 名 = `默认`，旧槽有值 | `apiKey` | 返回旧 key（升级用户的 key 不丢） |
| profile 名 = `默认`，旧槽为空 | `apiKey:默认` | 正常新路径 |
| profile 名 = `云端` | `apiKey:云端` | 与旧槽无关 |

写的时候就**不分新旧**了：

```java
100      public static void storeApiKey(String profileName, String value) {
104          String key = DEFAULT_NAME.equals(profileName) ? LEGACY_API_KEY : PROFILE_PREFIX + profileName;
105          PasswordSafe.getInstance().set(attributes(key), new Credentials(key, value));
```

`默认` 这个 profile 的 key 又写回旧槽位 `apiKey`。这是一个**刻意的双向兼容**：
让「默认」永远落在旧槽上，老版本回退时还能读到。

**（4）`PasswordSafe` 的 key 是怎么拼的**

```java
113      private static CredentialAttributes attributes(String key) {
114          return new CredentialAttributes(
115                  CredentialAttributesKt.generateServiceName(CREDENTIAL_SERVICE_NAME, key));
116      }
```

最终落成 `serviceName = "WorkReportPlugin"` + `userName = key` 的凭据项。
这里有一个小冗余：`new Credentials(key, value)` 把 `key` 也当成了用户名，而 `CredentialAttributes`
的第二个参数已经是同一个 `key`。它们应当一致，只是重复表达了。

`ReportSettingsConfigurable.retrieveApiKey()` 是给旧调用方留的入口：

```java
376      // Backward-compatible accessor used by the chat panel for the legacy single-model path.
378      public static String retrieveApiKey() {
379          return ModelProfiles.apiKey(ModelProfiles.activeName(ReportSettingsState.getInstance()));
380      }
```

**两条链路都通到同一个存储**：旧 `ReportPolishService` 用它拿 key，新 `AgentCoreService` 用
`ModelProfiles.apiKey(profile.name)` 拿 key，最后都进 `PasswordSafe`。这保证了「设置里填一次，两处都能用」。

### 2.8 `ModelEndpoints`：端点形状优先于用户填的类型

```java
// src/main/java/com/workreport/plugin/settings/ModelEndpoints.java
 3  // Endpoint shape decides the wire protocol, mirroring agent_core.llm.detect_provider().
 4  // 端点形状决定协议类型，规则与 Core 的 detect_provider() 保持一致：
 5  // 填错「类型」时以端点为准，避免把 OpenAI 端点拼成 .../chat/completions/api/chat。
```

`detect(configured, baseUrl)` 的决策树：

```java
18      public static String detect(String configured, String baseUrl) {
19          String provider = configured == null ? "" : configured.trim().toLowerCase();
20          String base = trimTrailingSlash(baseUrl);
21          if (base.isEmpty()) {
22              return provider.isEmpty() ? OLLAMA : provider;
23          }
24          if (endsWithAny(base, OLLAMA_SUFFIXES)) {
25              return OLLAMA;
26          }
27          if (base.contains("/chat/completions") || base.endsWith("/v1")) {
28              return OPENAI;
29          }
30          return OPENAI.equals(provider) ? OPENAI : OLLAMA;
31      }
```

```
端点为空 ──────────────→ 用用户填的类型（空则 ollama）
  │
  ├─ 以 /api/chat | /api/generate | /api/embed(s) | /api/tags 结尾 ─→ ollama
  │
  ├─ 含 /chat/completions 或 以 /v1 结尾 ───────────────────────→ openai_compat
  │
  └─ 其它 ──→ 用户填了 openai_compat 就是它，否则 ollama
```

三点观察：

**（1）`/v1` 也算 OpenAI。** 因为很多人只填 `https://api.xxx.com/v1`，
真正的路径由客户端补。但 `ReportPolishService` **不补**路径，它直接把 `apiEndpoint` 当完整 URL 用
（见 §4.4）。所以填 `/v1` 的人在用旧润色时一定会 404——这条规则是给「端点探测」用的，
不是给旧润色用的。

**（2）`trimTrailingSlash` 做了循环去斜杠**，`http://x///` → `http://x`。
但紧接着的 `endsWith("/api/chat")` 就依赖这个清理，否则 `http://x/api/chat/` 匹配不上。

**（3）与 Core 的 `detect_provider()` 保持同构**。这是两层代码必须对齐的少数几处之一：
如果 Java 认为是 Ollama、Core 认为是 OpenAI，同一个端点会得到两种请求格式，而两边都不会报错——
一边成功一边 400。第 5 卷 §provider 里讲过 Core 侧的同一套规则。

### 2.9 `ReportSettingsConfigurable`：381 行的表单

这是旧功能里**唯一还挂在界面上的部分**，也是唯一「用户真的在用」的旧代码。

#### 2.9.1 结构

```
createComponent()      建表格 + 15 个控件 → FormBuilder 拼成一条长表单 → reset()
isModified()           逐字段对比 state；表格用 equals 比 profile 列表
apply()                校验 → 写 key → 写 profile JSON → 镜像字段 → 写其余 13 项
reset()                state → 控件
parseMaxIterations()   5~300，越界抛 ConfigurationException
parseIntField()        通用整数区间校验
parseTemperature()     0.1~1.5，四舍五入到两位
collectProfiles()      表格 → List<ModelProfile>（丢掉非法行）
hasNewApiKey()         是否有非 "******" 的 key 输入
normalizeProvider()    "openai*" → openai_compat，其它 → ollama
```

#### 2.9.2 表格就是数据模型

第 0 列 name、1 列 type、2 列 endpoint、3 列 model、4 列 key。`isCellEditable` 恒 `true`：

```java
61              @Override
62              public boolean isCellEditable(int row, int column) {
63                  return true;
64              }
```

key 列用 `******` 占位，只有用户**真的输入了新值**才写回密码库：

```java
216          for (int row = 0; row < profileModel.getRowCount(); row++) {
217              String key = cell(row, 4);
218              if (!key.isEmpty() && !KEY_MASK.equals(key)) {
219                  ModelProfiles.storeApiKey(cell(row, 0), key);
220              }
221          }
```

这个「掩码 + 哨兵串」的模式有三处隐含风险：

1. **`******` 是字面量**（`KEY_MASK = "******"`）。如果某个用户的 API Key 恰好就是 `******`
   （6 个星号），它会被当成掩码而不保存。
2. **`isModified()` 用 `hasNewApiKey()` 而不是比较**：只要填了新 key 就算「已修改」，
   即使其它字段没动。这是对的——否则用户只改 key 时「Apply」按钮是灰的。
3. **删行不会删 key**。`removeButton` 只 `profileModel.removeRow(row)`，密码库里那一项还在。
   下次建一个同名的 profile，旧 key 会被静默复用。这是一个**安全残留**。

#### 2.9.3 `apply()` 的四段

```
① 校验          profiles 为空 → 抛 ConfigurationException（设置页会显示红字，不保存）
② 写 key        逐行 storeApiKey（跳过掩码与空）
③ 写 profile    存 JSON；activePointer 失效时回落到第一条；镜像 apiEndpoint/modelName/useOllamaMode
④ 写其余 13 项  每个字段各自校验/规整
```

第 ③ 段的「回落」逻辑：

```java
225          String active = state.activeProfile;
226          boolean stillThere = false;
227          for (ModelProfile profile : profiles) {
228              if (profile.name.equals(active)) {
229                  stillThere = true;
230                  break;
231              }
232          }
233          if (!stillThere) {
234              state.activeProfile = profiles.get(0).name;
235          }
```

它的作用是：用户删掉当前选中的模型后，`activeProfile` 指向一个不存在的名字，这里把它拉回第一条。
**但没有反向检查**：`state.activeProfile` 在 `reset()` 时不会显示给用户，也没有下拉框能改它——
所以「切换当前模型」这个功能在设置页里**没有 UI**，只能靠改 `modelProfilesJson` 的顺序来间接影响
（`ModelProfiles.active` 找不到名字时会取 `profiles.get(0)`）。这是旧面板被删后留下的断层：
原来切换靠旧面板的工具条，现在面板没了，切换入口也没了。

#### 2.9.4 校验风格：越界就报错，不静默纠正

```java
290      // A typo here silently truncates every task, so out-of-range values fail loudly.
292      private int parseMaxIterations() throws ConfigurationException {
303          if (value < 5 || value > 300) {
304              throw new ConfigurationException("最大迭代轮次需在 5~300 之间，当前为 " + value);
305          }
```

这条注释写出了一个重要的判断：**「静默纠正」比「报错」更危险**。
如果用户输入 `400`，静默改成 `300`，他会以为自己配置成功，然后在长任务里被莫名截断。
抛 `ConfigurationException` 会让设置页底部出现红字、`Apply` 不生效，用户必须当场改对。

`parseTemperature` 里还有一个小动作：

```java
341          return Math.round(value * 100) / 100.0;
```

把 `0.234` 规整成 `0.23`，避免 XML 里出现 `0.23400000000000001` 这种浮点噪声。

---

## 3. `GitLogCollectorService`：唯一被新系统继承的旧代码

282 行，五个方法，一条完整的「采集 → 解析 → 格式化 → 出草稿」管道：

```
collectFormatted()         一行包装：collect + formatEntries
collect()                  逐仓库跑 git log，合并、报错、排序
parseGitOutput()           "hash|author|date|subject" → GitCommitEntry
formatEntries()            按日期分组 → 中文标题 + 条目列表
generateDraftReport()      按报告类型出四套草稿模板
```

### 3.1 `collectFormatted` 与 `toDisplayLine` 是两处死代码

```java
 34      public String collectFormatted(Project project, LocalDate startDate, LocalDate endDate, String reportType) {
 35          List<GitCommitEntry> entries = collect(project, startDate, endDate, reportType);
 36          return formatEntries(entries);
 37      }
```

全仓库没有任何调用点（旧面板用的是 `collect` + `formatEntries` 两步直调，Agent 的 `GitTools` 也是）。
同样地，`GitCommitEntry.toDisplayLine()` 也没人调用：

```java
 15      public String toDisplayLine() {
 16          return "- %s %s (%s)".formatted(date, subject, author);
 17      }
```

而 `formatEntries` 自己拼的是 `- subject (author)`——**两者格式不同**（一个有日期，一个没有）。
所以 `toDisplayLine` 不是「暂时没用」，而是「写完之后被另一个实现取代了」。

> 这两个方法是同一个现象：**先写了工具方法，后来在调用处另写了一遍**。
> 它们不会报错，只会让下一个读者犹豫「该用哪个」。

### 3.2 `collect`：多仓库合并 + 两处边界处理

```java
 41      public List<GitCommitEntry> collect(Project project, LocalDate startDate, LocalDate endDate, String reportType) {
 42          List<GitCommitEntry> allEntries = new ArrayList<>();
 43          GitRepositoryManager manager = GitRepositoryManager.getInstance(project);
 44          List<GitRepository> repos = manager.getRepositories();
 46          ReportSettingsState settings = ReportSettingsState.getInstance();
 47          String authorFilter = settings.authorFilter;
 49          for (GitRepository repo : repos) {
```

**（1）遍历所有仓库，而不是只取第一个。**
这与 `GitTools.firstRepository()`（只取 `get(0)`）形成对比：旧报告要汇总整个项目的所有仓库，
新工具只需要「一个仓库够用」。两种口径各自合理，但**同一个项目下两个功能可能给出不同的 commit 集合**。

**（2）日期区间的「左减一天」**

```java
 57                  boolean isDaily = "daily".equals(reportType);
 59                  LocalDate queryStart;
 60                  LocalDate queryEnd;
 61                  if (isDaily) {
 63                      queryStart = startDate;
 64                      queryEnd = endDate.plusDays(1); // 不包含 endDate+1 当天
 65                  } else {
 67                      queryStart = startDate.minusDays(1);
 68                      queryEnd = endDate.plusDays(1);
 69                  }
 71                  handler.addParameters(
 72                          "--pretty=format:" + LOG_FORMAT,
 73                          "--date=short",
 74                          "--after=" + queryStart + " 00:00:00",
 75                          "--before=" + queryEnd + " 00:00:00",
 76                          "--no-merges"
 77                  );
```

git 的 `--after` / `--before` 都是**严格不等**：

```
--after=<t>    committer date > t
--before=<t>   committer date < t
```

于是：

| 分支 | 实际区间 | 效果 |
|---|---|---|
| `daily` | `(start 00:00, end+1 00:00)` | 恰好覆盖 `start..end` 整天 ✓ |
| 非 `daily` | `(start-1 00:00, end+1 00:00)` | **多了一整天**（`start-1`） |

注释说「周报/季报/年报：扩展 1 天包含边界」，但 `start` 那一侧本来就是闭的（`00:00:00` 已包含），
不需要再往前一天。后果很具体：默认周报区间是「本周一 → 今天」，而 `startDate.minusDays(1)` 是**上周日**，
所以周一生成的周报会把**上周日**的提交算进「本周工作内容」。

> 这条 bug 目前**影响不到任何人**：旧面板已死，而 Agent 的 `GitTools.log` 写死了传 `"daily"`：
>
> ```java
> 38              List<GitCommitEntry> entries = service.collect(project, start, end, "daily");
> ```
>
> 所以它是一处「埋在死代码里的活 bug」。哪天要把周报做成 Skill，第一件要改的就是这里。

**（3）`--no-merges`**
合并提交被排除。这是给「工作内容」看的日志，合并提交的 subject 通常是 `Merge branch 'xxx'`，
对报告没有信息量。但它也意味着：「我只做了合并」这类工作不会出现在报告里。

**（4）`--author=` 是正则，不是子串**

```java
 78                  if (authorFilter != null && !authorFilter.isBlank()) {
 79                      handler.addParameters("--author=" + authorFilter);
 80                  }
```

git 的 `--author` 按 **regex** 匹配作者字段。用户在设置页填 `张.`，会同时匹配「张三」「张四」。
填 `(` 则直接让 git 报错（正则语法错），于是整仓采集变成一条 ERROR 条目（见下）。
设置页对 `authorFilter` **没有任何校验**，提示文字只说「留空表示包含所有作者」。

### 3.3 错误也变成了「提交记录」

两处 `catch` 都往同一个列表里塞一个 `GitCommitEntry`：

```java
 85                  if (result.success()) {
 86                      parseGitOutput(result.getOutputAsJoinedString(), allEntries);
 87                  } else {
 88                      allEntries.add(new GitCommitEntry(
 89                              "ERROR", "system", LocalDate.now(),
 90                              "git log 命令失败: " + result.getErrorOutputAsJoinedString()
 91                      ));
 92                  }
 93              } catch (Exception e) {
 96                  allEntries.add(new GitCommitEntry(
 97                          "ERROR", "system", LocalDate.now(),
 98                          "采集仓库 [" + repo.getRoot().getName() + "] 失败: " + e.getMessage()
 99                  ));
100              }
```

这个设计意图是「不要让一个仓库失败导致整个报告失败」，但实现方式很危险：

```
allEntries ──► sort by date desc ──► formatEntries ──► 报告文本
                                    └► generateDraftReport ──► 草稿 ──► 送给 LLM 润色
```

因为 `date = LocalDate.now()`，错误条目**恒定排在第一位**，然后：

- `formatEntries` 会把它渲染成今天的 `- git log 命令失败: ... (system)`；
- `generateDraftReport` 会把它当成一条「今日完成工作」；
- 到了润色环节，**这段 git 错误会被当成工作内容发给 LLM**。

正确做法是让 `collect` 返回一个「结果对象」（成功列表 + 错误列表），而不是把两种语义撞进同一个类型。
这也是为什么 `ERROR` 这个魔法字符串写在了 `hash` 字段里——旁路信息无处安放。

### 3.4 `parseGitOutput`：`split(..., 4)` 是必要的

```java
109      private void parseGitOutput(String output, List<GitCommitEntry> entries) {
110          if (output == null || output.isBlank()) return;
111          for (String line : output.split("\n")) {
112              String trimmed = line.trim();
113              if (trimmed.isEmpty()) continue;
114              String[] parts = trimmed.split(FIELD_SEPARATOR, 4);
115              if (parts.length >= 4) {
116                  try {
117                      LocalDate date = LocalDate.parse(parts[2].trim(), DATE_FMT);
```

`FIELD_SEPARATOR` 是 `"\\|"`（正则里的转义管道），limit = 4 意味着**前三个管道切分，剩下全部归给 subject**。
这是必需的：提交说明里写 `fix: A | B 任选其一` 很常见，不限 limit 会把 subject 切碎。

但作者、hash 里出现 `|` 仍会错位（作者名带 `|` 虽罕见，但机器帐号如 `bot|ci` 是可能的）。
更稳的做法是选一个不会出现在内容里的分隔符（`\x01`），或者用 `-z` + 自定义格式。

另一个失败模式是**时间格式**：

```java
 73                          "--date=short",
```

`--date=short` 输出 `YYYY-MM-DD`，正好对得上 `DATE_FMT`。但如果哪一天有人把它改成 `--date=iso`，
这里会静默跳过所有行（`catch (Exception ignored)`），**报告变成「没有找到提交记录」而不是报错**。

### 3.5 `formatEntries`：中文星期 + 一个冗余包装

```java
134      public String formatEntries(List<GitCommitEntry> entries) {
135          if (entries.isEmpty()) {
136              return "该日期范围内没有找到提交记录。";
137          }
139          Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
140                  .collect(Collectors.groupingBy(
141                          GitCommitEntry::date,
142                          TreeMap::new,
143                          Collectors.toList()
144                  ));
146          var sb = new StringBuilder();
147          for (var entry : new TreeMap<>(grouped).entrySet()) {
```

`groupingBy` 已经指定了 `TreeMap::new`，第 147 行又 `new TreeMap<>(grouped)` 复制了一次。
**无害、但说明作者当时不确定前一个参数是不是排了序**。这种「防御性再包一层」在多线程环境下有意义，
在这里纯属冗余。

星期的映射靠一个数组：

```java
 23      private static final String[] DAY_NAMES_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
...
149              int dayIdx = date.getDayOfWeek().getValue() - 1;
150              String dayName = dayIdx >= 0 && dayIdx < DAY_NAMES_CN.length ? DAY_NAMES_CN[dayIdx] : "";
```

`DayOfWeek.getValue()` 是 1（周一）~ 7（周日），减 1 后正好对上数组下标。
这里本可以用 `date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA)`，
但手写数组的好处是**不受 IDE 语言与 JVM locale 影响**——在英文 IDE 上也永远出「周一」。

### 3.6 `generateDraftReport`：四个方法，三处重复

```java
171      public String generateDraftReport(List<GitCommitEntry> entries, String reportType) {
172          return switch (reportType) {
173              case "daily" -> generateDailyReport(entries);
174              case "quarterly" -> generateQuarterlyReport(entries);
175              case "yearly" -> generateYearlyReport(entries);
176              default -> generateWeeklyReport(entries);
177          };
178      }
```

四个方法结构完全一致，只有散文部分不同：

```
# <标题>
## <板块 1>
- <每条 subject>          ← 四个方法逐字相同（周/季/年还多一层按日期分组，但分组结果没用）
## <板块 2>…
- （请填写…）             ← 占位符
```

周/季/年三个方法里这段尤其可疑：

```java
210          Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
211                  .collect(Collectors.groupingBy(GitCommitEntry::date, TreeMap::new, Collectors.toList()));
213          for (var entry : new TreeMap<>(grouped).entrySet()) {
214              for (GitCommitEntry commit : entry.getValue()) {
215                  sb.append("- ").append(commit.subject()).append("\n");
216              }
217          }
```

**分组了，但分组键（日期）从未被使用**——内层只是把同一天的 commit 依次输出。
效果等价于 `for (commit : entries) sb.append("- " + subject + "\n")`，
但多建了两个 `TreeMap` 和一层嵌套循环。而且因为 `TreeMap` 按日期**升序**，
输出顺序变成了「最早的在前」，而列表本身是**按日期倒序**排序过的（`collect` 末尾 `reversed()`）——
分组把倒序逆回去了。

真正有效的只有 `generateDailyReport` 里那行 `- subject` 和末尾几个占位符。所以这套模板的实际价值是：

> **给用户一个「看起来像报告」的骨架，让 LLM 有东西可润色，也让用户知道该补什么。**

从产品角度看这个选择是对的（空文本框会让人卡住），从代码角度看四份重复应该合并成
「标题 + 板块名 + 是否分日」三张配置表。

---

## 4. `ReportPolishService`：一套独立的 LLM 调用栈

451 行，**不经过 Core**，直接用 OkHttp 发 HTTP。这是它最值得读的地方：
同一份「调模型」的需求，在两代代码里被实现成了两套完全不同的东西。

### 4.1 三个入口，一条转发链

```
polishAsync(project, text, type, onSuccess, onError)
    └─► polishAsyncStreaming(..., onToken = null, ...)
        └─► polishAsyncStreamingWithHistory(..., history = null, ...)
            └─► Task.Backgroundable.run()
                ├─ onToken != null → polishSyncStreaming() ─► SSE 逐行读
                └─ onToken == null → polishSync() ─► polishSyncWithHistory() ─► 一次性 JSON
```

三个方法只是参数逐层补齐：先不给 `onToken`（非流式），再不给 `history`（首轮）。
这种「同名 + 减少一个参数」的重载链在 UI 代码里很常见，好处是调用方只关心自己需要的参数，
坏处是**读代码时必须一路追到最里层才知道到底调的是什么**。

### 4.2 后台线程 + 回 EDT：IntelliJ 的标准写法

```java
151          ProgressManager.getInstance().run(new Task.Backgroundable(project, taskTitle, true) {
153              public void run(@NotNull ProgressIndicator indicator) {
154                  indicator.setIndeterminate(true);
155                  indicator.setText("正在连接 AI 服务...");
156                  try {
157                      if (onToken != null) {
159                          StringBuilder fullResult = new StringBuilder();
160                          polishSyncStreaming(text, reportType, history, token -> {
161                              fullResult.append(token);
162                              // 关键逻辑：回到 EDT 更新 UI，实现打字机效果
163                              ApplicationManager.getApplication().invokeLater(() -> onToken.accept(token));
164                          });
165                          ApplicationManager.getApplication().invokeLater(() -> onSuccess.accept(fullResult.toString()));
166                      } else {
168                          String result = polishSync(text, reportType);
169                          ApplicationManager.getApplication().invokeLater(() -> onSuccess.accept(result));
170                      }
171                  } catch (Exception e) {
172                      ApplicationManager.getApplication().invokeLater(() -> onError.accept(e));
173                  }
174              }
175          });
```

四个要点：

| 做法 | 原因 |
|---|---|
| `Task.Backgroundable` | 自动给进度条、自动在后台线程跑、自动处理项目关闭 |
| `invokeLater` 包每个回调 | Swing 只能在 EDT 上改；OkHttp 回调发生在**读取线程** |
| `StringBuilder` 在后台线程累加 | 回调方想在结束时拿完整文本，但不该再拼一次 |
| `catch (Exception)` → `onError` | 保证背景任务的异常不会变成「静默卡在进度条」 |

**一个必须知道的缺陷：取消按钮无效。**
`Task.Backgroundable(project, taskTitle, **true**)` 的第三个参数 `canBeCancelled = true`，进度条会出现 X 按钮。
但 `run()` 里从未检查 `indicator.isCanceled()`，而 `polishSyncStreaming` 的读循环也没有退出条件：

```java
378                  String line;
379                  while ((line = reader.readLine()) != null) {
```

于是用户点了取消，界面上的进度条消失，**后台线程仍然会把整条 SSE 流读完**（最多 60 秒 read timeout），
并且读完后照旧 `invokeLater(onSuccess)`——把结果显示到一个用户已经放弃的界面上。

### 4.3 四套 system prompt，一条共同的约束线

```java
 51      private String getSystemPrompt(String reportType) {
 52          return switch (reportType) {
 53              case "daily" -> getDailyPrompt();
 54              case "quarterly" -> getQuarterlyPrompt();
 55              case "yearly" -> getYearlyPrompt();
 56              default -> getWeeklyPrompt();
 57          };
 58      }
```

四段 prompt 的前两条要求逐字相同：

```
1. 保持所有事实信息准确，不要捏造内容
2. 使用专业的工作汇报语言
5. 保持原文的技术术语不变
```

第 1 条是这份功能的核心约束——**它防的是 LLM 把「修了个 null 指针」吹成「重构了核心模块」**。
报告是要交给上级看的，捏造事实比语言不通严重得多。

第 5 条同样具体：技术术语（类名、接口名、框架名）一旦被「翻译」成中文，
同事就看不懂了。这两条加在一起，定义了「润色」的边界：**只改表达，不改内容**。

| 报告类型 | 板块结构 |
|---|---|
| 日报 | 今日完成工作 / 明日计划 / 遇到的问题 |
| 周报 | 本周工作内容 / 下周计划 / 需要协调的问题 |
| 季报 | 本季度主要成果 / 项目进展总结 / 下季度规划 / 风险与挑战 |
| 年报 | 年度工作回顾 / 年度成果总结 / 来年目标 / 个人成长与反思 |

四个句型也递进得很清楚：日报「突出成果和价值」→ 季报「归纳提炼、突出里程碑」→
年报「高度概括、突出年度亮点」。这是 prompt 工程里最实际的一层——**用词汇而不是用规则来控制粒度**。

### 4.4 手拼 JSON：能跑，但每一步都在刀尖上

先看请求体是怎么长出来的：

```java
207          StringBuilder messagesBuilder = new StringBuilder();
210          messagesBuilder.append("{\"role\": \"system\", \"content\": \"").append(escapedSystemPrompt).append("\"}");
213          if (history != null && !history.isEmpty()) {
214              for (ConversationEntry entry : history) {
215                  messagesBuilder.append(", {\"role\": \"").append(entry.role)
216                      .append("\", \"content\": \"").append(escapeJson(entry.content)).append("\"}");
217              }
218          }
221          messagesBuilder.append(", {\"role\": \"user\", \"content\": \"").append(escapedContent).append("\"}");
```

然后整段套进 Text Block：

```java
229              jsonBody = """
230                      {
231                        "model": "%s",
232                        "messages": [%s],
233                        "stream": false
234                      }
235                      """.formatted(escapeJson(model), messagesBuilder.toString());
```

**为什么不直接 `new Gson().toJson(map)`？**
因为这是一份「先写于 2.x、当时可能不想多引依赖」的代码。项目里已经有 Gson（`ModelProfiles` 在用），
所以现在完全可以换掉。手拼 JSON 的代价是必需自己手写转义：

```java
426      private static String escapeJson(String text) {
427          if (text == null) return "";
428          return text.replace("\\", "\\\\")
429                     .replace("\"", "\\\"")
430                     .replace("\n", "\\n")
431                     .replace("\r", "\\r")
432                     .replace("\t", "\\t");
433      }
```

它处理的五类字符是对的，但**漏了两类**：

| 漏掉的东西 | 后果 |
|---|---|
| U+0000–U+001F 里的其它控制字符（`\b` `\f` `\u0001`…） | 产生非法 JSON，服务端 400 |
| 反斜杠之外的单引号/斜杠 | 不致命（JSON 允许） |

实际触发条件很具体：**用户直接把一份带 `\f`（换页符）的 PDF/Word 文本粘进报告框**。
这个字符在复制粘贴里不罕见，而粘贴框是一个普通 `JBTextArea`，不会清理。

还有一处更隐蔽的风险在 214–217 行：`entry.role` 是**直接拼进去的**，没有白名单。
`history` 里的 role 全部由 `polishAsyncStreamingWithHistory` 的调用方决定，当前只有面板在用，
它写的是 `"system"` / `"user"` / `"assistant"`。但如果将来有人把用户输入当 role 传进来，
就能构造出任意 JSON。**手拼 JSON 的实质问题是：数据与格式没有分离，安全边界只能靠调用方自觉。**

### 4.5 两种协议，四个分支

| | Ollama | OpenAI 兼容 |
|---|---|---|
| 端点（默认） | `http://localhost:11434/api/chat` | 用户自填完整 URL |
| 非流式请求 | `{model, messages, stream:false}` | `{model, messages, temperature:0.7}` |
| 流式请求 | `{model, messages, stream:true}` | `{model, messages, temperature:0.7, stream:true}` |
| 认证 | 无 | `Authorization: Bearer <key>` |
| 非流式响应 | `message.content` | `choices[0].message.content` |
| 流式响应 | `message.content` | `choices[0].delta.content` |

两个值得注意的不对称：

**（1）非流式的 OpenAI 请求里没有 `stream` 字段。**
依赖服务端默认为 `false`。绝大多数实现如此，但不是协议规定的。

**（2）端点不做路径拼接。**
`ReportPolishService` 把 `settings.apiEndpoint` 直接当完整 URL 用：

```java
249          Request.Builder requestBuilder = new Request.Builder()
250                  .url(endpoint)
251                  .post(body);
```

所以用户必须填到 `/api/chat` 或 `/chat/completions`，少一段就是 404。
对照 Core 侧的 `resolve_generate_url()`（第 5 卷）：它会判断端点是否已含路径，缺了会补。
**这是两代代码在同一个问题上的两种做法：一个宽容，一个严格。**

### 4.6 写死的温度

```java
243                          "temperature": 0.7
```

0.7 是写死的，**不读 `settings.temperature`**（那里默认 0.2）。
润色是「改写语言」任务，高温确实有好处（表达多样），但用户在设置页调了温度后会发现对润色无效。
设置页的提示文字写的是「采样温度，范围 0.1~1.5；越低越稳定」——这是给 Agent 的。
两套功能共用一个配置项、却只有一个读它，是「共用 State」带来的新问题。

### 4.7 `parseResponse`：把异常包成 RuntimeException

```java
277      private String parseResponse(String responseBody, boolean isOllama) {
296          } catch (Exception e) {
297              throw new RuntimeException("解析 AI 响应失败：" + e.getMessage()
298                      + "\n原始响应：" + responseBody, e);
299          }
```

把**整个响应体拼进异常消息**。好处是报错时能直接看到服务端到底返回了什么（最常见的错误是
「模型名不存在」「无权限」，都在响应体里）；坏处是这个字符串可能非常长，会直接进入 statusLabel 与弹窗。

两种格式的取值路径都是「逐层 `getAsJsonObject`」，任何一层缺失就 `NullPointerException`
（Gson 的 `getAsJsonObject` 返回 null），然后被包成 RuntimeException。所以：

- Ollama 返回 `{"error":"model not found"}` → 报「解析失败」而不是「模型不存在」；
- OpenAI 返回 `{"choices":[]}` → `get(0)` 抛 `IndexOutOfBoundsException` → 同上。

**错误分类在这里丢失了**：网络错、鉴权错、模型名错、格式错最终都变成一样的「解析 AI 响应失败」。

### 4.8 SSE 解析：一个健壮的小循环

```java
376              try (BufferedReader reader = new BufferedReader(
377                      response.body() != null ? response.body().charStream() : null)) {
378                  String line;
379                  while ((line = reader.readLine()) != null) {
381                      if (line.startsWith("data: ")) {
382                          String data = line.substring(6);
384                          if ("[DONE]".equals(data)) {
385                              break;
386                          }
388                          String token = extractTokenFromStream(data, isOllama);
389                          if (token != null && !token.isEmpty()) {
390                              onToken.accept(token);
391                          }
392                      }
393                  }
394              }
```

和 Core 的 `streaming.py`（第 5 卷）相比，这里没有：

| 缺少的东西 | 后果 |
|---|---|
| `\r\n` 处理 | `readLine()` 已经吃掉行尾，实际无影响 |
| 多行 `data:` 拼接 | 当前两个提供方都不发行内换行 |
| `event:` 字段处理 | 未使用 |
| `[DONE]` 之外的终止条件 | 依靠 EOF |
| 重试 / 退避 | 一旦断流就整段失败 |
| 取消检查 | 见 4.2 |

比对「一个成熟的 SSE 客户端」与「一个够用的 SSE 客户端」的差别，这份代码是个很好的标本：
**它把 90% 的场景跑通了，剩下 10% 靠 `catch (Exception)` 包成「解析失败」。**

### 4.9 `extractTokenFromStream`：吞掉错误，返回空串

```java
401      private String extractTokenFromStream(String data, boolean isOllama) {
402          try {
403              JsonObject root = JsonParser.parseString(data).getAsJsonObject();
404              if (isOllama) {
406                  return root.getAsJsonObject("message").get("content").getAsString();
407              } else {
409                  var choices = root.getAsJsonArray("choices");
410                  if (choices != null && choices.size() > 0) {
411                      var delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
412                      if (delta != null && delta.has("content")) {
413                          var content = delta.get("content");
414                          return content.isJsonNull() ? "" : content.getAsString();
415                      }
416                  }
417              }
418          } catch (Exception e) {
419              // 解析失败时返回空，继续处理下一行
420              }
421          return "";
```

对比两个分支的健壮度：

- OpenAI 分支：`choices` 判 null、判 size、`delta` 判 null、`has("content")`、`isJsonNull()`——**五重防护**；
- Ollama 分支：`root.getAsJsonObject("message").get("content").getAsString()`——**零防护**。

Ollama 流式响应里，最后一个 chunk 是 `{"message":{"content":""},"done":true}`，这条能过；
但如果遇到 `{"done":true}`（无 `message` 字段），就会 NPE → 被 catch 吞掉 → 返回空 → 继续下一行。
效果上「无害」，但代价是**真正的错误（比如中途返回 `{"error":...}`）也被静默吞掉**，
用户看到的现象是「流突然结束，文本不完整，没有任何报错」。

### 4.10 `notifyError`：一个注定走兜底的通知

```java
439      public static void notifyError(Project project, String message) {
440          try {
441              NotificationGroupManager.getInstance()
442                      .getNotificationGroup("WorkReport Notifications")
443                      .createNotification("报告助手错误", message, NotificationType.ERROR)
444                      .notify(project);
445          } catch (Exception e) {
447              // Fallback when notification group is not registered
448              com.intellij.openapi.ui.Messages.showErrorDialog(project, message, "报告助手错误");
449          }
450      }
```

`plugin.xml` 里**没有任何 `<notificationGroup>` 声明**，而 `getNotificationGroup(id)` 在 id 未注册时
会抛 `IllegalArgumentException`。所以：

```
notifyError(...)
   → getNotificationGroup("WorkReport Notifications")
   → IllegalArgumentException
   → catch
   → Messages.showErrorDialog(...)   ← 每次都走这里
```

也就是说这个「非阻塞通知」**从未生效过**，每次都是模态弹窗。而且 `showErrorDialog` 必须在 EDT 上调用，
但调用方 `ReportToolWindowPanel` 是在 `onError` 回调里调它——那个回调已经被
`invokeLater` 包过（§4.2），所以恰好是合规的。**两个错误叠在一起刚好抵消**，只能说是运气。

修法二选一：注册通知组（
`<notificationGroup id="WorkReport Notifications" displayType="BALLOON"/>`），
或者删掉这个类。对一个即将退役的模块，后者更合理。

---

## 5. `ReportToolWindowPanel`：754 行的废弃面板

虽然它已经不挂载，但它是**这套功能完整形态的存档**——看清它写了什么，就知道「迁移成 Skill」丢掉了哪些东西、又必须保留哪些。

### 5.1 结构：一屏四区

```
SimpleToolWindowPanel
└─ mainPanel (BorderLayout, 12px 内边距)
   ├─ NORTH  toolbarPanel       报告类型下拉 + 起止日期 + [采集 Git 日志]
   ├─ CENTER centerSplitPane    左：Git 日志（只读，40%）| 右：报告编辑器（可写，60%）
   └─ SOUTH  bottomAreaPanel
             ├─ [0] 按钮行      [润色] [复制] [导出]
             └─ [1] 对话区      （首轮润色成功后动态插入）输入框 + [发送] + [清空]
```

```java
 88      public ReportToolWindowPanel(Project project) {
 89          super(true, true);
 90          this.project = project;
 91          this.gitLogService = new GitLogCollectorService();
 92          this.polishService = new ReportPolishService();
 94          initUI();
 95          refreshPolishButtonState();
 96      }
```

两个 Service 都是**直接 `new`**，而不是从 `@Service` 取。这在一个只有一份的面板里没问题，
但意味着它们不是单例，而 `ReportPolishService` 里那些 `static` 成员（`HTTP_CLIENT`、`MEDIA_TYPE`）
才是真正共享的部分。

### 5.2 `calculateDateRange`：四种类型的默认区间

```java
355      private LocalDate[] calculateDateRange(String reportType) {
356          LocalDate today = LocalDate.now();
359          switch (reportType) {
360              case "daily":
362                  start = today;  end = today;
365              case "quarterly":
367                  int month = today.getMonthValue();
368                  int quarterStartMonth = ((month - 1) / 3) * 3 + 1;
369                  start = LocalDate.of(today.getYear(), quarterStartMonth, 1);
370                  end = start.plusMonths(3).minusDays(1);
372              case "yearly":
374                  start = LocalDate.of(today.getYear(), 1, 1);
375                  end = LocalDate.of(today.getYear(), 12, 31);
377              case "weekly":
380                  start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
381                  end = today;
```

| 类型 | 区间 | 备注 |
|---|---|---|
| 日报 | 今天 ~ 今天 | —— |
| 周报 | 本周一 ~ 今天 | **不是一整周**，周三只能拿到半周 |
| 季报 | 本季度首日 ~ 本季度末日 | 季末是未来日期 |
| 年报 | 1/1 ~ 12/31 | 同上 |

周报「周一到今天」是合理的产品选择（周五生成周报时不该包含未来），但它与「周报」的直觉有冲突。
季度/年度把 `end` 设成未来的日期，于是 `--before` 的上界实际不起作用。

类型切换会立刻重算区间并回写设置：

```java
388      private void onReportTypeChanged() {
391          ReportSettingsState.getInstance().reportType = reportType;
394          LocalDate[] dateRange = calculateDateRange(reportType);
395          startDateField.setText(dateRange[0].format(DATE_FMT));
396          endDateField.setText(dateRange[1].format(DATE_FMT));
```

注意它**只改了内存字段**，没走设置页的 `apply()`。`PersistentStateComponent` 会在下一次
保存周期（或 IDE 退出）把整个对象序列化，所以最终会落盘——但落盘时机不受控。
直接改 `@State` 字段在 IntelliJ 里是合法但不够严谨的写法（严格做法是调 `Settings.change()` 或发 `MODIFICATION` 主题）。

### 5.3 `onCollectGitLog`：一次教科书式的「后台 → EDT」

```java
420      private void onCollectGitLog() {
423          try {
424              startDate = LocalDate.parse(startDateField.getText().trim(), DATE_FMT);
425              endDate = LocalDate.parse(endDateField.getText().trim(), DATE_FMT);
426          } catch (Exception ex) {
427              statusLabel.setText("日期格式错误，请使用 yyyy-MM-dd 格式");
428              statusLabel.setForeground(JBColor.RED);
429              return;
430          }
433          collectButton.setEnabled(false);
439          ApplicationManager.getApplication().executeOnPooledThread(() -> {
440              List<GitCommitEntry> entries = gitLogService.collect(project, startDate, endDate, reportType);
441              String formatted = gitLogService.formatEntries(entries);
442              String draft = gitLogService.generateDraftReport(entries, reportType);
446              ApplicationManager.getApplication().invokeLater(() -> {
447                  lastCollectedEntries = entries;
448                  gitLogArea.setText(formatted);
449                  reportArea.setText(draft);
452                  refreshPolishButtonState();
453              });
454          });
```

五个正确的决策：

| 决策 | 为什么对 |
|---|---|
| 先 `LocalDate.parse` 再进后台 | 日期错是**用户输入错**，该在 EDT 上立即反馈 |
| `setEnabled(false)` | 防双击开五个线程 |
| `executeOnPooledThread` | `git log` 会阻塞，可能几百 ms |
| 所有变量在 `invokeLater` 前算好 | 避免在 EDT 上做 IO |
| 回 EDT 后才 `setText` | Swing 线程安全 |

同一文件里 `onReportTypeChanged` 与 `onCopy` 是纯 EDT 操作，无需包后台；
而 `onExport` 里的 `Files.writeString`（L589）**在 EDT 上写文件**——对一份几 KB 的报告无感，
但对一份几 MB 的报告会卡 UI。这是这个面板里唯一一处「IO 在 EDT」的地方。

### 5.4 `onPolish`：隐私 → 清空 → 流式回填

```java
460      private void onPolish() {
461          String reportText = reportArea.getText();
462          if (reportText == null || reportText.isBlank()) {
463              statusLabel.setText("报告内容为空，无法润色"); return;
466          }
471          if (!PrivacyNoticeUtil.ensurePrivacyAccepted()) {
472              statusLabel.setText("用户取消了润色操作"); return;
475          }
477          polishButton.setEnabled(false);
478          collectButton.setEnabled(false);
483          reportArea.setText("");
486          if (!isConversationStarted) {
487              originalDraftText = reportText;
488              conversationHistory.clear();
489              isConversationStarted = true;
492              conversationHistory.add(new ConversationEntry("system", getSystemPromptForHistory(reportType)));
493          }
497          polishService.polishAsyncStreamingWithHistory(
498                  project, reportText, reportType, conversationHistory,
503                  token -> reportArea.append(token),
505                  polishedText -> { … },
537                  error -> { … }
557          );
```

流程本身是对的：**先保存原文 → 清空 → 流式回填**。失败时也做了恢复：

```java
536                  // Error callback / 失败回调
537                  error -> {
541                      // 润色失败时恢复原始报告内容
542                      reportArea.setText(reportText);
555                      ReportPolishService.notifyError(project, "润色失败：" + error.getMessage());
556                  }
```

但同一个模式在 `onSendUserInstruction` 里**没有做**：

```java
628          // 清空编辑区，准备流式输出
629          reportArea.setText("");
...
661                  // Error callback
662                  error -> {
671                      statusLabel.setText("优化失败：" + error.getMessage());
672                      statusLabel.setForeground(JBColor.RED);
673                      ReportPolishService.notifyError(project, "优化失败：" + error.getMessage());
674                  }
```

错误回调里只恢复了按钮和状态行，**没有把 `currentReportText` 写回去**。
于是「第二轮优化失败」的直接后果是：**编辑区空白，上一轮的成果丢失**（只能重新采集一遍）。
这是一个真实的、可复现的数据丢失缺陷。

### 5.5 双 system 提示：一条消息被发了两遍（内容还不一样）

`onPolish` 首轮往历史里塞了一条 `system`：

```java
492              conversationHistory.add(new ConversationEntry("system", getSystemPromptForHistory(reportType)));
```

而 `polishSyncStreaming` 开头又自己拼了一条：

```java
315          String escapedSystemPrompt = escapeJson(getSystemPrompt(reportType));
322          messagesBuilder.append("{\"role\": \"system\", \"content\": \"").append(escapedSystemPrompt).append("\"}");
```

两条 system 的内容还**不一样**：`getSystemPrompt()` 是带 5 条要求的完整版（几十行），
`getSystemPromptForHistory()` 只有一句话：

```java
744      private String getSystemPromptForHistory(String reportType) {
745          ReportPolishService service = new ReportPolishService();
746          // 使用反射或其他方式获取私有方法，这里简化处理
747          return switch (reportType) {
748              case "daily" -> "你是一个专业的日报润色助手。请将用户提供的工作内容改写为一份结构清晰、语言专业的中文日报。";
```

这段代码能读岀三种痕迹：

1. **`ReportPolishService service = new ReportPolishService();` 是个完全未使用的局部变量**（下一行就没再碰它）；
2. 注释写着「使用反射或其他方式获取私有方法」，说明作者当时想直接复用 `getSystemPrompt`，但因为它是 `private` 而放弃了；
3. 于是复制了一份**截短版**——丢掉了全部 5 条约束（尤其「不要捏造内容」）。

最终发给模型的 messages 是：

```
[ system: 简短版 ]  ← 来自历史
[ user: 报告原文 ]  ← 第一轮的 user
[ system: 完整版 ]  ← 本次请求
[ user: 报告原文 ]  ← 本次请求
```

也就是说：**同一个 system 提示词的两份变体同时在场，同一个 user 内容也重复两遍**。
大多数服务端能容忍（只用第一条 system），但 token 白花，且行为变得依赖服务端实现。

### 5.6 遗留的 `System.out.println`

```java
506                      System.out.println("[DEBUG] Polish success callback called");
507                      System.out.println("[DEBUG] conversationPanelShown=" + conversationPanelShown);
508                      System.out.println("[DEBUG] isConversationStarted=" + isConversationStarted);
520                          System.out.println("[DEBUG] Calling refreshUIForConversation()");
523                          System.out.println("[DEBUG] Set conversationPanelShown=true");
```

五条调试输出。在 IntelliJ 插件里 `System.out` 会被拐到 `idea.log`，所以它们**会真的进日志文件**。
这三行监控的全是同一个问题：「面板为什么没显示出来」——因为 `refreshUIForConversation` 依赖
硬结构定位（见下），当年确实在这里掉过坑。

### 5.7 `refreshUIForConversation`：靠坐标找组件

```java
713      private void refreshUIForConversation() {
716              Component content = getContent();
717              if (content instanceof JPanel mainPanel) {
719                  Component bottomArea = mainPanel.getComponent(mainPanel.getComponentCount() - 1);
720                  if (bottomArea instanceof JPanel areaPanel) {
722                      if (areaPanel.getComponentCount() > 1) {
723                          areaPanel.remove(1); // 移除索引 1 的组件（对话面板）
724                      }
727                      if (isConversationStarted) {
728                          JPanel conversationPanel = createConversationPanel();
729                          areaPanel.add(conversationPanel, BorderLayout.SOUTH);
730                      }
```

它做了两个很强的假设：

1. `mainPanel` 的**最后一个**子组件是底部区域；
2. 底部区域的**索引 1** 是对话面板。

任何人在 `initUI()` 末尾多加一个 `mainPanel.add(...)`，或者把对话面板插到索引 0，这里就会静默失效
（移除错的组件或抛 `IndexOutOfBoundsException`）。这类代码的替代写法是**把对话面板持有成字段**：

```java
private JPanel conversationPanel;   // 直接用字段增删，不靠坐标
```

一个更有意思的细节：`onClearConversation` 会把引用置 null：

```java
696              // 重置按钮引用
697              sendButton = null;
698              userInputField = null;
699              clearHistoryButton = null;
```

然后在所有回调里逐个 `if (sendButton != null)`。但既然组件已被移除且引用置空，这些防御
完全没用——真正需要防的是「回调到达时组件已 dispose」。

### 5.8 一个正在淡出的设计：通过「换控件」控制可见性

整个对话区的显隐靠「重建组件」而不是 `setVisible`：光看这段代码会觉得绕，但它有一个理由——
对话区是**首轮润色后才出现**的，而 Swing 的布局在 `remove` + `add` 后需要 `revalidate()`。
用重建的方式可以保证每次都是干净的初始状态（输入框为空、按钮可用）。

代价就是上面那些脆弱假设 + 五个 DEBUG 输出。这段代码是「在测试不到 UI 的情况下兵来将挡」的产物。

---

## 6. 两个小文件：隐私弹窗与提交记录

### 6.1 `PrivacyNoticeUtil`：81 行，三件做对的事

这个类只有三个方法，但它把一个「同意类对话框」该做对的三件事都做对了：**说清目的地、默认不危险、记住选择**。

#### （1）正文：把「数据去哪」放在最显眼的位置

```java
// src/main/java/com/workreport/plugin/privacy/PrivacyNoticeUtil.java
26          String htmlMessage = """
27                  <html><body style="width:420px;">
28                  <p style="color:red;font-size:14px;font-weight:bold;">
29                  &#9888;&#65039; 警告：润色功能将发送您的报告内容至外部服务！
30                  </p>
31                  <hr/>
32                  <p>您的报告文本将被发送至以下地址进行 AI 润色：</p>
33                  <p style="font-weight:bold;color:#336699;">%s</p>
34                  <ul>
35                  <li>如果您使用的是 <b>Ollama（本地模式）</b>，数据不会离开您的计算机。</li>
36                  <li>如果您使用的是 <b>云端 API</b>，数据将通过网络发送至第三方服务。</li>
37                  </ul>
38                  <p style="color:red;">请确认您了解并接受上述数据传输行为。</p>
39                  </body></html>
40                  """.formatted(escapeHtml(endpoint));
```

| 元素 | 用意 |
|---|---|
| 红色粗体首行 + ⚠️ | 视觉上一眼可见。`&#9888;&#65039;` 是 U+26A0 与 U+FE0F 的十进制实体 |
| `<hr/>` | 把「警告」和「细则」分开，避免用户只读第一行 |
| 端点单独一行、蓝色加粗 | **同意的对象是「发到哪」，不是「是否用 AI」** |
| 两条 bullet 区分本地/云端 | 让用户在同一屏内判断自己属于哪种模式 |
| 结尾一行红字 | 明确「需要确认」而不是「通知你一下」 |

`style="width:420px"` 在 Swing 的 HTML 3.2 渲染器里支持有限（`javax.swing.text.html` 只实现了一小部分 CSS），
实际宽度多半由内容决定。无害，但别指望像素级控制。

> **对照 Agent 子系统**：`AgentChatPanel` 会把代码片段、文件内容、会话历史发给同一个模型，
> 而那里**没有任何隐私提示**。这套写得最用心的合规代码，恰好只服务那个已经不再挂载的面板。
> 「最强的一次数据外发（读整个仓库）没有任何提示，最弱的一次（一份日报）反而有警告」——
> 这是迁移过程中最容易漏掉的一类东西，§7.6 会把它放进对照表。

#### （2）`defaultIndex = 1`：把「取消」设成默认按钮

```java
42          int result = Messages.showDialog(
43                  htmlMessage,
44                  "隐私与数据传输提示",
45                  new String[]{"我已了解并同意", "取消"},
46                  1, // Default selection: "Cancel" / 默认选中"取消"
47                  Messages.getWarningIcon()
48          );
```

三个返回值语义：

| 用户操作 | 返回值 | 这里的结果 |
|---|---|---|
| 点「我已了解并同意」 | `0` | 通过，写 `privacyAccepted = true` |
| 点「取消」 | `1` | 拒绝 |
| ESC / 右上角关闭 | `-1` | 拒绝 |

判断只用 `result == 0`，把 `-1` 也算进拒绝，是 **fail-safe** 的写法。
同意类对话框绝不该写成 `result != 1` ——那样「关闭」会被当成「同意」。

代价：默认按钮是「取消」违反 IntelliJ 的一般习惯（默认按钮通常是主操作），键盘用户要按两次回车才能同意。
对一个「一次性、影响外部」的对话框，这个代价可以接受。

#### （3）`escapeHtml`：四行替换，顺序不能反

```java
73      private static String escapeHtml(String text) {
74          if (text == null) return "";
75          return text.replace("&", "&amp;")
76                     .replace("<", "&lt;")
77                     .replace(">", "&gt;")
78                     .replace("\"", "&quot;");
79      }
```

`&` 必须**第一个**替换，否则 `&lt;` 会被二次转义成 `&amp;lt;`。这是所有 HTML 转义函数的固定顺序。

没有转 `'`（`&#39;`）。在这个位置无害——端点只出现在文本节点里，不在属性里。
Swing 的 HTML 解析器也不执行脚本，所以这里不是 XSS 防线，而是 **布局防线**：
端点里出现一个 `<` 就会让后面的文字被当成标签吃掉。

#### （4）`ensurePrivacyAccepted`：用一个字段代替「不再提示」复选框

```java
65      public static boolean ensurePrivacyAccepted() {
66          ReportSettingsState settings = ReportSettingsState.getInstance();
67          if (settings.privacyAccepted) {
68              return true;
69          }
70          return showPrivacyWarning(settings.apiEndpoint);
71      }
```

IntelliJ 提供了 `Messages.showCheckboxMessageDialog`（带「不再提示」），这里没用，而是把选择持久化进 `@State`。

| 做法 | 优点 | 缺点 |
|---|---|---|
| 弹窗内复选框 | 与弹窗原子，用户看得见 | 每次都要弹窗才能改 |
| 持久化字段（这里） | 一次同意永久生效，零 UI 成本 | 用户无法在设置页撤回；「永久」的粒度不受控 |

而且 `privacyAccepted` 是**全局一个布尔**：Ollama 本地模式同意过，之后切到云端也不会再问
（除了 §2.4 那个本来该重置却永远不重置的 bug）。
真正合理的粒度是「每个端点一次确认」，字段应该是 `Set<String> acceptedEndpoints`。

#### （5）它现在唯一的调用点在死代码里

`ensurePrivacyAccepted()` 的调用者只有 `ReportToolWindowPanel.onPolish()`（L471）。
所以这个类的命运是：**写得最严谨的部分，用户已经用不到了**。§8 会把它记成「迁移遗漏」。

### 6.2 `GitCommitEntry`：19 行 record，一个没人调用的方法

```java
// src/main/java/com/workreport/plugin/model/GitCommitEntry.java
 7  public record GitCommitEntry(
 8          String hash,     // 提交哈希值（短格式）
 9          String author,   // 提交作者
10          LocalDate date,  // 提交日期
11          String subject   // 提交说明（首行）
12  ) {
15      public String toDisplayLine() {
16          return "- %s %s (%s)".formatted(date, subject, author);
17      }
18  }
```

**（1）record 替我们生成了什么**

- 全参构造器、四个访问器（`hash()` 而不是 `getHash()`）；
- `equals` / `hashCode`（逐字段，`LocalDate` 自带正确的值语义）；
- `toString`（`GitCommitEntry[hash=..., author=...]`，只适合调试）。

**（2）为什么 record 是对的选择**

`List<GitCommitEntry>` 在后台线程（`executeOnPooledThread` / `Task.Backgroundable`）里构造，
在 EDT 上被 `formatEntries` / `generateDraftReport` 读取（§5.3）。不可变对象天然线程安全，不需要任何同步手段。
四个字段也都是值语义（短哈希、作者名、日期、标题），没有「同一个提交的两个可变视图」的需求。

**（3）`toDisplayLine()` 零调用点**

`formatEntries` 自己拼了另一种格式：

```java
// GitLogCollectorService.java:152-158
152              sb.append("## ").append(date.format(DATE_FMT))
153                .append("（").append(dayName).append("）\n");
155              for (GitCommitEntry commit : entry.getValue()) {
156                  sb.append("- ").append(commit.subject())
157                    .append(" (").append(commit.author()).append(")\n");
```

两者都产出 `- 主题 (作者)`，但 `formatEntries` 多一层「按日期分组 + 中文星期」的表头，
`toDisplayLine()` 则把日期塞进每一行。说明这个方法曾经被用过，后来格式化逻辑挪进了 `formatEntries` 并内联了一份。

**留下一个「看起来该用但没人用」的方法是遗留代码的典型气味**：读者会以为格式化逻辑在 record 里，
改 `formatEntries` 时漏掉它，两边就永久分叉了。

**（4）缺一个 `repo` 维度**

四个字段里没有「这个提交属于哪个仓库」。而 `GitTools.firstRepository()` 只取第 0 个仓库
（`repositories.isEmpty() ? null : repositories.get(0)`），`GitLogCollectorService` 也只按 `project` 找仓库。
多仓库/多模块项目里，另一条仓库的提交**直接消失，且没有任何提示**——这跟 §3.3 那个「错误变成提交记录」
是同一类问题的两面：**采集口径的失真不会被下游发现**。

**（5）`%h` 是短哈希**

`git log --format=%h` 给的是按仓库规模自动加长的短哈希（通常 7–8 位）。它在**当前仓库**里足够唯一定位，
但不能跨仓库引用，也不适合当长期标识。要长期引用应该存 `%H`，展示时再截断。

---

## 7. 两套系统的交叉点

前六节分开讲了两套系统。这一节只讲它们**碰在一起的地方**——因为「迁移」最容易出问题的地方
从来不是新代码，而是新旧共享的那几个字段、文件和服务。

### 7.1 四条耦合线

```
                    workReportSettings.xml  （一份文件）
                              │
                  ReportSettingsState （APP 单例）
        ┌─────────────────────┼──────────────────────┐
   镜像字段                ModelProfile(s)          Agent 参数
 apiEndpoint/modelName   → PasswordSafe        maxIterations/contextWindow/...
 useOllamaMode              │                         │
        │                   │                         │
  ReportPolishService   retrieveApiKey()      AgentCoreService.pushSettings()
   OkHttp 直连            （旧）                  JSON-RPC settings/update
        │                   │                         │
        └───────── 旧面板（死）             Core（活）

              GitLogCollectorService  ← 被两方共用
               ├─ 旧面板：collect + formatEntries + generateDraftReport
               └─ GitTools：collect(…, "daily") + formatEntries
```

| 耦合线 | 共享的东西 | 旧侧用法 | 新侧用法 | 风险 |
|---|---|---|---|---|
| 配置 | `ReportSettingsState` | 直接读字段 | 直接读字段 + `settings/update` 下发 | 单例被 EDT 与后台线程同时写 |
| 密钥 | `PasswordSafe("WorkReportPlugin")` | `retrieveApiKey()` | `ModelProfiles.apiKey(profile.name)` | 同槽位、不同解析路径 |
| git 采集 | `GitLogCollectorService` | 面板全流程 | `GitTools.log` 只取 `formatEntries` | 旧设置 `authorFilter` 静默影响工具结果 |
| 模型配置 | `ModelProfile(s)` | 镜像字段 | `profile.provider/baseUrl/model` | 镜像不同步 → 端点与 key 错配 |

### 7.2 密钥：两条链路，一个槽位

```java
// 旧：ReportPolishService.java:256 与 362
String apiKey = ReportSettingsConfigurable.retrieveApiKey();

// 桥接：ReportSettingsConfigurable.java:378-380
public static String retrieveApiKey() {
    return ModelProfiles.apiKey(ModelProfiles.activeName(ReportSettingsState.getInstance()));
}

// 新：AgentCoreService.java:128, 151-154
ModelProfile profile = ModelProfiles.active(state);
String apiKey = ModelProfiles.apiKey(profile.name);
if (apiKey != null && !apiKey.isEmpty()) {
    settings.put("api_key", apiKey);
}
```

两者最终都落到 `apiKey:<name>` 这个槽位（§2.7），但**取值依据不同**：

- 旧：`activeName(state)` 返回一个**字符串**；
- 新：`active(state)` 返回一个**对象**，再取 `.name`。

正常情况下两者相等。真正会出问题的是**端点与 key 来自不同来源**：旧服务用镜像字段
`settings.apiEndpoint` / `settings.modelName`，却用 active profile 的 key。
一旦镜像字段与 active profile 不同步，就会出现「**用 A 的端点 + B 的 token**」。

这个不同步是**结构性的**，不是偶发的：镜像字段只在 `apply()` 里同步（§2.4），
而 `@State` 的字段全是 `public` 且可写——`onReportTypeChanged` 就直接写了（§5.2），
VS Code 扩展也直接写（`vscode/src/settings.ts`）。

### 7.3 认证头：一个看「类型」，一个看「端点形状」

```java
// 旧：ReportPolishService.java:255-260
if (!isOllama) {                        // 布尔来自 settings.useOllamaMode
    String apiKey = ReportSettingsConfigurable.retrieveApiKey();
    if (apiKey != null && !apiKey.isEmpty()) {
        requestBuilder.header("Authorization", "Bearer " + apiKey);
    }
}

// 新：AgentCoreService.java:130-133
// Endpoint shape wins over the typed provider: a gateway on an OpenAI endpoint
// must still receive the bearer token even when "ollama" was selected.
settings.put("provider", ModelEndpoints.detect(profile.provider, profile.baseUrl));
```

`ModelEndpoints.detect` 的存在理由（§2.8）就是「网关挂在 OpenAI 形状的路径上，即使类型选了 ollama，也必须带 Key」。
旧服务没有这层判断。于是一个完全合法的配置会一分为二：

| 配置 | 旧服务 | 新 Core |
|---|---|---|
| `baseUrl = https://gw.corp/v1` + `provider = ollama` | 不带 `Authorization` → **401** | `detect` → `openai_compat` → 带 Bearer → 正常 |

**同一个设置页、同一条 profile，两个消费方理解不同。** 这类不一致只能靠「只有一条下发路径」根治，
也就是删掉旧服务——而不是在各处补 `detect` 调用。

### 7.4 流式解析：SSE 与 NDJSON 的混淆

旧服务的流式循环只认识一种行格式：

```java
// ReportPolishService.java:379-382
379                 while ((line = reader.readLine()) != null) {
381                     if (line.startsWith("data: ")) {
382                         String data = line.substring(6);
```

而默认端点是 `http://localhost:11434/api/chat`（`ReportSettingsState.apiEndpoint` 的默认值），
`useOllamaMode` 默认 `true`（§2.2）。把这两个事实放在一起，结论就出来了：

| 提供方 | 流式响应格式 | 旧服务能否解析 |
|---|---|---|
| OpenAI 兼容 `/v1/chat/completions` | `data: {...}` 逐行 + `data: [DONE]` | ✅ |
| Ollama 原生 `/api/chat` | **每行一个完整 JSON，没有 `data:` 前缀** | ❌ 全部行被跳过 |

也就是说：**默认配置下（Ollama 原生端点），流式润色一个 token 都取不到。**
而成功回调并不兜底写回结果：

```java
// ReportToolWindowPanel.java:505-533
505                 polishedText -> {
511                     conversationHistory.add(new ConversationEntry("user", reportText));
512                     conversationHistory.add(new ConversationEntry("assistant", polishedText));
...
533                     statusLabel.setText("润色完成！您可以继续提出优化要求");
```

它没有 `reportArea.setText(polishedText)`——因为设计假设是「token 已经把内容追加进去了」。
于是当 token 流为空时，用户看到的是：**编辑区空白 + 状态栏「润色完成！」**，
历史里还多了一条 `assistant: ""`。

三个可以学的点：

1. **「成功」的判据不能只看回调被调用。** `onSuccess` 只代表「HTTP 200 + 流正常结束」，不代表有内容。
   UI 层的成功条件应该是 `!polishedText.isBlank()`，或者至少 `setText` 兜一次。
2. **协议识别要基于响应，而不是基于配置。** 同一个本地服务同时提供 NDJSON（`/api/chat`）
   和 SSE（`/v1/chat/completions`）两种接口，用「用户在设置页选了什么」来决定解析器，天然会错配。
   稳妥做法是看响应头 `Content-Type`（`text/event-stream` vs `application/x-ndjson`），或者干脆只支持一种。
3. **Core 里这件事是分开的**：`llm/providers/ollama.py` 与 `openai_compat.py` 各自实现自己的流解析（第 5 卷）。
   「协议归 provider」是这个项目从旧到新最重要的一次抽象。

> 这段代码现在无人调用（面板未注册），所以这是一个「已死但仍值得记录」的缺陷。
> §8 会给每一条标注「当前影响」。

### 7.5 同一个 git 采集器，两种调用约定

| 调用方 | 参数 | 用到的产物 |
|---|---|---|
| `ReportToolWindowPanel.onCollectGitLog` | `collect(project, start, end, reportType)`（四种类型） | `formatEntries` + `generateDraftReport` |
| `GitTools.log`（Agent 工具） | `collect(project, start, end, "daily")`（写死） | 只有 `formatEntries` |

三处差异带来三个后果：

**（1）分支覆盖差。** `GitLogCollectorService` 里 `"daily"` 与「非 daily」走不同的日期边界（§3.2）。
Agent 永远传 `"daily"`，所以那条「多取一天」的路径**由旧面板独占**——而旧面板不挂载了。
结果是一个真实存在的 bug 只活在死代码路径上。

**（2）旧设置会改变新工具的行为。** `collect()` 内部读 `ReportSettingsState`（L46 起），
其中包含 `authorFilter`。也就是说：如果用户多年前在设置页填了作者过滤，
**Agent 的 `git_log` 工具只会返回那个作者的提交**，而模型完全不知道为什么提交变少了。
工具的参数里没有 author，调用方也看不出这层过滤。

这是「共享服务」的典型代价：**一个服务只要读全局状态，它就不是纯函数，复用它的一方会继承它所有的隐含前提。**

**（3）`generateDraftReport` 成为纯死代码。** 它只被旧面板调用，四个草稿方法（§3.6）现在一个都跑不到。

### 7.6 两套 LLM 调用栈的完整对照

| 维度 | 旧：Java 直连 | 新：Core |
|---|---|---|
| 调用链 | 面板 → `ReportPolishService` → OkHttp | 面板 → JSON-RPC → `agent_core` → provider |
| HTTP 客户端 | OkHttp（connect 10s / read 60s / write 10s） | httpx（read 8s，见第 9 卷 §13.1-F） |
| 请求体 | 手拼 JSON 字符串 + text block | `json.dumps` / SDK |
| 消息组装 | 1 条 system + 历史 + 1 条 user | system + 上下文压缩 + 工具消息 |
| 流式 | 手写 `data: ` 行解析 | provider 级 SSE / NDJSON 适配 |
| 工具调用 | 无 | 全套（read/write/git/shell/MCP/subagent） |
| 重试 | 无 | provider 层退避重试（第 5 卷） |
| 取消 | `Task.Backgroundable(..., true)` 但从不检查 `indicator`（§4.2） | 任务级取消传播 |
| 错误呈现 | `notifyError` → 恒走模态框（§4.10） | 事件流 `error` → 状态行 |
| 用量统计 | 无 | usage 目录 + `session/usage`（第 7 卷） |
| 隐私提示 | 有（一次性，且与端点绑定失效） | **无** |
| 可测试性 | 需要真实 HTTP 服务 | `smoke_*.py` 用假 provider 全链路验证（第 9 卷） |

一句话总结这张表：**旧栈是「一个人写一个功能」，新栈是「一个平台服务多个功能」。**
旧栈缺的每一项都能在新栈里找到对应的补齐——**除了隐私提示，这一项是新栈缺、旧栈有**。

### 7.7 设置生效的两条路径

旧路径（同步即生效）：

```
用户在设置页点 OK
  → ReportSettingsConfigurable.apply()
     → 写 ReportSettingsState 字段 / PasswordSafe     ← 到此结束
  → 下一次润色直接读字段                            ← 所以「立即生效」
```

新路径（异步下发）：

```
用户在设置页点 OK
  → apply() 只写 State（不通知 Core）
  → 下一次发消息前 ChatSessionPanel 调 coreService.pushSettings()
     → JSON-RPC settings/update → Core 应用
```

三条真实的下发触发点：

| 触发点 | 代码 | 覆盖场景 |
|---|---|---|
| 建立连接时 | `AgentCoreService.connection()` L100 | 冷启动 |
| 每条消息前 | `ChatSessionPanel` L279 | 常规路径 |
| 设置面板关闭后 | `AgentChatPanel.openSettings()` L950-955 | 立刻得到「已下发」反馈 |

以及一条例外——**只有三个 Core 位置路径变了才重启进程**：

```java
// AgentChatPanel.java:934-948
934         boolean corePathsChanged = !Objects.equals(exePath, state.coreExePath)
935                 || !Objects.equals(homePath, state.coreHomePath)
936                 || !Objects.equals(pythonPath, state.pythonPath);
937         if (corePathsChanged) {
...
945                 coreService.restart();
```

因为**重启会清空所有会话上下文**（注释原文：`rebuilding wipes every session's conversation history`）。
这是一个很成熟的取舍：模型/端点/MCP 可以热更新，进程位置只能冷重启。

再补一个边界情况：`InlineCompletionClient.complete()` 只调 `connection()`。
连接已存在时 `connection()` 直接返回（L76-78），**不会重新下发**。
所以「只改模型、不打开聊天面板、继续写代码」时，行内补全在本次连接内仍用旧模型。
这不是 bug（契约是「每条消息前推一次」），但它说明一个结构性事实：
**「推配置」是聊天路径的职责，不是配置层的职责。** 一旦有第三个消费方，这套契约就得改成
`Settings.change()` + 监听器——`apply()` 自己负责通知所有消费者。

---

## 8. 缺陷清单：按「现在还能不能触发」分层

一份遗留代码的缺陷清单，价值不在「列了多少个」，而在「标清楚哪些还能触发」。
下面按影响分四组，每条给：位置 / 症状 / 触发条件 / 修法。

### 8.1 A 组：活代码里的缺陷（今天就能踩到）

#### A1 · 隐私确认永不重置

| 项 | 内容 |
|---|---|
| 位置 | `ReportSettingsConfigurable.apply()` L239 → L242 |
| 症状 | 改端点后不再要求重新同意隐私提示 |
| 触发 | 设置页换模型/端点 → OK |
| 修法 | 先比较后赋值（§2.4） |

这是本卷唯一一个「读一眼就能确认、改一行就能修」的逻辑 bug。

#### A2 · 废弃设置项在静默过滤 Agent 的工具结果

| 项 | 内容 |
|---|---|
| 位置 | `GitLogCollectorService.collect()` L46 起读 `authorFilter`；调用方 `GitTools.log` L37-38 |
| 症状 | Agent 的 `git_log` 只返回某作者的提交，模型不知道有过滤 |
| 触发 | 设置页填过作者过滤（regex） |
| 修法 | `collect()` 增加 `authorFilter` 参数；旧面板传设置值，`GitTools` 传 `null` |

#### A3 · 采集错误变成「提交记录」，还会被喂给模型

| 项 | 内容 |
|---|---|
| 位置 | `GitLogCollectorService` 的 catch 分支（§3.3） |
| 症状 | git 失败时返回一条 `ERROR` 条目，`formatEntries` 渲染成 `- ERROR …` |
| 触发 | 非 git 项目 / git 不在 PATH / 仓库损坏 |
| 修法 | 返回 `Result<List<GitCommitEntry>>`，或抛受检异常，让上层决定怎么展示 |

这一条值得单独强调：§3.3 里它是「死代码里的 bug」，但 `GitTools` 复用 `collect()` 之后，
它成了**活路径上的 bug**——`git_log` 工具会把 `ERROR: …` 当成一条工作记录返回给模型，
模型会煞有介事地把它写进日报。

#### A4 · 多仓库只取第 0 个

| 项 | 内容 |
|---|---|
| 位置 | `GitTools.firstRepository()`（`repositories.get(0)`）；`GitLogCollectorService` 同样按单一仓库构造命令 |
| 症状 | 多模块项目的其它仓库提交直接消失 |
| 修法 | 遍历 `getRepositories()` 合并；`GitCommitEntry` 加 `repo` 字段（§6.2） |

#### A5 · 删掉 profile 不删密钥

| 项 | 内容 |
|---|---|
| 位置 | `apply()` 只对「存在的行」调 `storeApiKey`，没有删除分支 |
| 症状 | `PasswordSafe` 里残留 `apiKey:<name>`；同名重建时读到旧 key |
| 修法 | 对比旧列表与新列表，差集调 `PasswordSafe.set(null)`（§2.7） |

从功能上看“不删也没事”，从安全上看是**密钥泄漏而不知**：用户以为删了配置，
凭证却还在 IDE 密码库里。

#### A6 · profile 改名即丢 key

| 项 | 内容 |
|---|---|
| 位置 | `ModelProfile.name` 是主键，而表格第 0 列可编辑（§2.6） |
| 症状 | 改名后读不到 key → 云端 401，或 Core 拿不到 `api_key` 而降级 |
| 修法 | 表格加隐藏 id 列；或在改名时迁移密码库槽位 |

#### A7 · 设置页缺 `autoAcceptEdits`

| 项 | 内容 |
|---|---|
| 位置 | 15 个控件里没有它；`ToolDispatcher` L67 读它 |
| 症状 | IntelliJ 用户无法从界面打开「自动接受修改」，只能手改 XML |
| 修法 | 加一个 checkbox（与 VS Code 侧对齐） |

#### A8 · 镜像字段与 active profile 可能脱节

| 项 | 内容 |
|---|---|
| 位置 | §7.2（旧侧用镜像字段，key 用 active profile） |
| 症状 | 端点与 token 错配 |
| 修法 | 随旧服务一起删掉镜像字段 |

#### A9 · 行内补全不刷新设置

| 项 | 内容 |
|---|---|
| 位置 | `InlineCompletionClient.complete()` 只调 `connection()` |
| 症状 | 改了模型但没走聊天路径 → 本次连接内仍用旧模型 |
| 修法 | `Settings.change()` 监听 → `pushSettings()` |

#### A10 · `temperature = 0` 被静默改写

| 项 | 内容 |
|---|---|
| 位置 | `ReportSettingsState.loadState` L115 |
| 症状 | 想要确定性输出的人拿到 0.2 |
| 修法 | 用 `Double` + `null` 判未配置，或哨兵值 `-1` |

### 8.2 B 组：只在旧面板里的缺陷（复活才会触发）

| # | 缺陷 | 位置 | 复活后的后果 |
|---|---|---|---|
| B1 | 第二轮优化失败丢草稿 | §5.4 | 用户上一轮成果直接消失 |
| B2 | Ollama 原生流式解析为空 + 成功回调不写回 | §7.4 | 编辑区空白 + 状态栏「润色完成！」 |
| B3 | 取消无效 | §4.2 | 点取消没用，请求照跑 |
| B4 | `escapeJson` 漏控制字符 / `role` 无白名单 | §4.4 | 历史里含 `\n` 以外的控制符时请求体非法 |
| B5 | 双 system + user 重复两遍 | §5.5 | token 白花，行为依赖服务端实现 |
| B6 | 温度写死 0.7 / 端点不补路径 | §4.5 / §4.6 | 设 0.2 不生效；填 `/v1` 必 404 |
| B7 | 非 daily 多取一天 | §3.2 | 周报会包含上周日 |
| B8 | `notifyError` 恒走模态框 | §4.10 | 非阻塞通知从未生效 |
| B9 | `onExport` 在 EDT 写文件 | §5.3 | 大报告卡 UI |
| B10 | 靠坐标找组件 / 未使用局部变量 / 5 条 println | §5.5–§5.7 | 布局一改就静默失效 |

B2 是这十条里最重的一条：它不会报错，只会「成功返回空」。

### 8.3 C 组：迁移遗漏（新系统缺的东西）

#### C1 · Agent 侧没有隐私提示

旧面板在每次（首次）润色前都会弹一个「你的报告会发到 <endpoint>」的确认（§6.1）；
Agent 面板把**整个仓库的代码片段**发给同一个模型，却没有任何提示。

对比一下两边发送的数据量：

| 场景 | 外发内容 | 提示 |
|---|---|---|
| 旧：润色一份日报 | 几百字报告文本 | ✅ 弹窗 + 端点展示 |
| 新：让 Agent 修一个 bug | 文件内容 + 目录结构 + 对话历史 + 可能的 `git diff` | ❌ 无 |

这不只是一个 UI 缺失，而是**迁移时没有把「合规属性」当作功能一起搬走**。
最省事的补法：在 `AgentChatPanel` 首次发送前置一个同款确认，并把已确认的端点记到 `Set<String>`。

#### C2 · `reportType` 成了僵尸配置

`ReportSettingsState.reportType` 仍存在于 XML、仍被 `loadState` 处理，但**没有任何活消费方**
（唯一读者是未注册面板的 L140）。它会永久停留在 `workReportSettings.xml` 里，让后来的人误以为它有用。

#### C3 · 四个草稿模板的等价性需要校验

`generateDraftReport` 的四套结构（「今日完成工作 / 明日计划 / 遇到的问题」等）在迁移后由
内置 Skill 的 prompt 承担。**是否逐条等价，需要拿 Skill 正文对照**——这是本卷留下的校验项，不做断言。

#### C4 · 「复制 / 导出」的交互取舍

旧面板有「复制到剪贴板」「导出 .md」（`onCopy` / `onExport`）；Agent UI 里没有这两个按钮。
往好处看：Agent 能用 `write` 工具直接落盘，比导出对话框更灵活；
往坏处看：**「一键复制」这个最常用的动作消失了**。这是产品取舍，不是缺陷，但得被记录。

### 8.4 D 组：气味与债务（无功能影响，但会坑下一个改它的人）

| # | 气味 | 位置 | 为什么会坑人 |
|---|---|---|---|
| D1 | 四个草稿方法结构重复 | §3.6 | 改一个忘三个 |
| D2 | `new TreeMap<>(grouped)` 冗余 | `formatEntries` L147 | `groupingBy(..., TreeMap::new, ...)` 已经是 `TreeMap` |
| D3 | `polishAsync` / `polishAsyncStreaming` 无外部调用 | §4.1 | 两个公开入口全是死路 |
| D4 | 整条非流式分支不可达 | `polishSyncWithHistory` + `parseResponse` | 两个在树内调用方都传 `onToken`，所以 `onToken == null` 永不成立 |
| D5 | `toDisplayLine()` 零调用 | §6.2 | 格式化逻辑两处 |
| D6 | 注释写「用反射或其他方式获取私有方法」 | §5.5 | 遗留的思考痕迹，实际是复制粘贴 |
| D7 | 754 行面板留在树里且无 `@Deprecated` | §0 | 读者默认它还活着 |

D4 值得停一下：`parseResponse` 里那个「把所有错误包成 RuntimeException」的问题（§4.7），
实际上**永远不会运行**——因为唯一的调用者 `polishSyncWithHistory` 要求 `onToken == null`，
而两个调用点都传了 lambda。**这也是为什么静态阅读比运行更重要：不是每个 bug 都有机会发生。**

### 8.5 如果只修三条

| 优先级 | 条目 | 理由 |
|---|---|---|
| 1 | A2（`git_log` 被静默过滤） | 影响 Agent 输出正确性，且用户完全不可见 |
| 2 | A3（`ERROR` 变提交记录） | 同上，且会把错误写成「工作成果」 |
| 3 | C1（Agent 无隐私提示） | 合规风险，与外发数据量不匹配 |

A1（隐私确认永不重置）虽然是最「经典」的 bug，但它的实际影响被两层抵消了：
提示本身只在死面板里弹，而且 `privacyAccepted` 永远是初值 `false` 时也不会引起任何异常。
**排优先级要看「真实触发路径」，不是看「代码错得多明显」。**

---

## 9. 十卷收尾

### 9.1 全书目录

| 卷 | 主题 | 主要覆盖对象 | 行数 |
|---|---|---|---|
| [01](01-request-lifecycle.md) | 一次请求的完整生命周期 | Java 面板 → RPC → Core → LLM → 工具 → 渲染 | 701 |
| [02](02-build-and-assembly.md) | 构建与装配 | `pom.xml` / `plugin.xml` / `assembly` / 构建脚本 | 462 |
| [03](03-core-entry-and-rpc-server.md) | Core 入口与 RPC 服务端 | `server.py` 逐函数、握手、能力协商、心跳 | 1,299 |
| [04](04-agent-loop-and-subagents.md) | Agent 循环与子代理 | 循环体、调度、并发、子代理隔离 | 1,539 |
| [05](05-llm-layer.md) | LLM 层 | providers / transport / streaming / context / tool_text | 1,656 |
| [06](06-tool-system.md) | 工具系统 | core 注册与远程、Java 工具实现、权限校验 | 1,537 |
| [07](07-state-memory-index.md) | 状态与持久化 | 会话存储 / 记忆 / 索引 / MCP / Skills / 用量 | 3,079 |
| [08](08-java-ui.md) | Java UI | 渲染层、卡片、面板、设计系统 | 4,164 |
| [09](09-shells-and-verification.md) | 其它 Shell 与验证体系 | VS Code 扩展、单测、冒烟脚本、跨层不变量 | 5,615 |
| 10 | 旧功能本体（本篇） | `com.workreport.plugin.**`（11 个文件 / 2,339 行源码） | 2,257 |
| —— | **合计** | 10 卷正文（不含主索引 377 行） | **22,309** |

### 9.2 三条阅读主线

这套文档不是线性读的。根据你想解决的问题，走三条不同的路：

**主线一：一次请求怎么走完（最推荐先读）**

```
01（全景）→ 03（RPC 逐函数）→ 04（Agent 循环）→ 05（模型调用）→ 06（工具执行）→ 08（UI 渲染）
```

适合：想改功能、想排查「消息发出去了为什么没反应」。

**主线二：状态存在哪里**

```
07（存储层）→ 03（会话握手与 projectId 绑定）→ 09（测试与不变量如何锁住它）
```

适合：想加持久化字段、想理解「为什么换项目就丢历史」。

**主线三：两套系统的交接（本篇）**

```
10（旧代码与判定法）→ 01（新链路）→ 06（git 工具复用）
```

适合：想删旧模块、想弄清「哪些代码真的在跑」。

### 9.3 最值得带走的八条经验

1. **壳不持有策略。** `pushSettings` 把用户旋钮完整下发，Shell 只负责渲染与转发（§7.7）。
2. **能力要协商。** `ide/hello.capabilities` 里没有 `ask-user`，Core 就不发提问（第 3 / 9 卷）。
3. **截断必须成对。** 工具结果被截断时，必须同时告诉模型「被截断了」（第 6 卷）。
4. **超时是漏斗。** 1.5s（壳）< 8s（httpx read）< 12s（asyncio），每层都要比下一层宽（第 9 卷 §13）。
5. **冒烟要能断言「没发生」。** 队列消费式做不到，帧缓冲式才行（第 9 卷 §12）。
6. **迁移路径：数据层吸收、设置层共用、界面层抛弃。** 可行，但**合规属性容易漏**（§0.2 / C1）。
7. **主键不要做成可编辑单元格**（A6）。
8. **方法自己完成比较，不要要求调用方保持语句顺序**（A1）。

后两条都来自本卷：**它们之所以能藏这么久，是因为旧面板已经不跑了。**

### 9.4 如果要彻底删掉旧模块：八步操作清单

按这个顺序做，每一步都能单独提交、单独回滚：

1. 在 `GitLogCollectorService` 上把「活的部分」抽清：`collect(..., authorFilter)` + `formatEntries`（`GitTools` 只需要这两个）；
2. 把 `generateDraftReport` 的四套结构搬进 Skill prompt，并对照校验（C3）；
3. 在 `AgentChatPanel` 补隐私确认，按端点集合存储（C1）；
4. 删 `ui/ReportToolWindowFactory`、`ui/ReportToolWindowPanel`、`service/ReportPolishService`、`privacy/PrivacyNoticeUtil`、`model/GitCommitEntry.toDisplayLine`；
5. 删 `@State` 里的 `reportType`、`apiEndpoint`、`modelName`、`useOllamaMode`、`privacyAccepted`（`useOllamaMode` 要先从 `ModelProfiles.resolve` 的升级兜底里拿掉）；
6. 给 `autoAcceptEdits` 补设置页控件（A7）；
7. 给被删的 profile 清理密钥槽（A5）；
8. 最后删 `ReportSettingsConfigurable.retrieveApiKey()`——它是旧链路留在新代码里的最后一座桥。

> 第 5 步最容易被低估：**删配置字段会丢掉用户数据**。正确做法是先迁移（写新字段）→ 读两边 → 再删旧字段，跨两个版本。

### 9.5 十卷没覆盖的东西

诚实标一下边界：

| 未逐行覆盖 | 原因 |
|---|---|
| 第三方 SDK 内部行为（OpenAI/httpx 等） | 只讲「我们怎么用」，不讲「它们怎么实现」 |
| `vscode` 的 webview 前端逐函数 | 第 9 卷只讲到结构、消息与时序 |
| `core/tests` 的 4,112 行全部用例 | 第 9 卷只讲体系与代表用例 |
| CI / 发布流水线 | 仓库里没有对应目录 |
| 性能实测数据 | 只引用了 `bench_core` 的阈值，未实跑 |

### 9.6 仓库卫生：一个顺手发现

仓库根目录下有四个与项目无关的文件：

```
lottery.py              1,555 B
test_core_231.log       3,334 B
lottery_gui.py         26,992 B
ssq_gui.py             28,079 B    双色球摇号动画（tkinter + Pillow）
```

三个彩票脚本加起来 56 KB，直接躺在 Java 插件仓库的根目录下。它们不影响构建（不在 `src` 里），
但会让新读者花三十秒困惑「这跟工作汇报有什么关系」。

这是遗留仓库很典型的一类噪声：**能跑、不报错、就是不属于这里**。
清理它不需要任何设计决策，只需要一次 `git rm`——所以它也是最容易被无限拖延的那类事。

### 9.7 一句话收束

这个仓库的本质是「一次完整架构迁移留下的地层」：

```
最底层  workreport      git log → 草稿 → LLM 润色       （数据层还活着，界面层已死）
中间层  agent_core      Python Agent + JSON-RPC        （全部活着）
最上层  intellij / vsc     两个只负责渲染与转发的壳          （全部活着）
```

读它的正确方式不是从 `main` 往下读，而是：

1. 先画一张「**谁还被注册**」的表（§0.2 的三步法）；
2. 只读活的那部分；
3. 把死的部分当历史档案——**但别假设它无害**，A2 / A3 就是从死代码里伸出来的两只手。

十卷到此结束。

---

<!-- ANCHOR -->
