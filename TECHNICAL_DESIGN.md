# Work Report AI Assistant - 技术实现文档

## 项目概述

Work Report AI Assistant 是一款基于 IntelliJ Platform SDK 开发的 IDEA 插件。其核心思路是：通过调用 git4idea 插件提供的 Git API 采集项目提交历史，将其结构化后生成报告草稿，再通过 OkHttp 调用本地或云端 LLM API 进行 AI 润色，最终以流式打字机效果实时呈现结果。

---

## 技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 开发语言 | Java | 21 | 使用 Record、Text Blocks、Switch Expression 等现代语法 |
| 编译构建 | Maven | 3.x | maven-compiler-plugin 3.13.0 |
| 插件打包 | maven-assembly-plugin | 3.7.1 | 生成 ZIP 格式分发包 |
| 平台 SDK | IntelliJ Platform | 2025.3.x (253.*) | system scope 引用本地 IDEA 安装目录 JAR |
| Git 集成 | git4idea | 随 IDEA 内置 | GitLineHandler、GitRepositoryManager、GitCommand |
| HTTP 客户端 | OkHttp | 4.12.0 | 调用 LLM API，支持流式 SSE 响应 |
| JSON 解析 | Gson | 随 IDEA 内置 | 解析 LLM API 的 JSON 响应 |
| 安全存储 | IntelliJ PasswordSafe | 随平台提供 | API Key 加密存储至系统密钥链 |
| UI 框架 | Swing + IntelliJ UI DSL | 随平台提供 | JBTextArea、JBLabel、JSplitPane、FormBuilder 等 |

---

## 项目目录结构

```
workreport-intellij-plugin/
├── assembly/
│   └── plugin-assembly.xml          # ZIP 打包描述文件
├── src/main/
│   ├── java/com/workreport/plugin/
│   │   ├── model/
│   │   │   └── GitCommitEntry.java  # Git 提交记录数据模型（Java Record）
│   │   ├── privacy/
│   │   │   └── PrivacyNoticeUtil.java  # 隐私确认弹窗工具类
│   │   ├── service/
│   │   │   ├── GitLogCollectorService.java   # Git 日志采集与格式化服务
│   │   │   └── ReportPolishService.java      # AI 润色服务（支持流式输出）
│   │   ├── settings/
│   │   │   ├── ReportSettingsConfigurable.java  # 设置页面 UI 与逻辑
│   │   │   └── ReportSettingsState.java         # 持久化配置状态
│   │   └── ui/
│   │       ├── ReportToolWindowFactory.java  # 工具窗口工厂（IDEA 扩展点入口）
│   │       └── ReportToolWindowPanel.java    # 主 UI 面板（所有交互逻辑）
│   └── resources/META-INF/
│       └── plugin.xml               # 插件描述与扩展点注册
└── pom.xml                          # Maven 构建配置
```

---

## 模块职责详解

### 1. GitCommitEntry（数据模型）

```java
public record GitCommitEntry(
    String hash,       // 提交哈希值（短格式，如 a1b2c3d）
    String author,     // 提交作者名
    LocalDate date,    // 提交日期
    String subject     // 提交说明首行
)
```

使用 Java 21 Record 语法定义不可变数据对象，自动生成构造函数、getter、equals、hashCode、toString。是整个数据流转的核心数据载体。

---

### 2. GitLogCollectorService（Git 日志采集服务）

**职责**：与 IDEA git4idea 插件交互，采集指定日期范围内的 Git 提交记录，并格式化为可读文本或报告草稿。

**核心方法**：

| 方法 | 说明 |
|------|------|
| `collect()` | 遍历当前项目所有 Git 仓库，执行 `git log` 命令，返回 `List<GitCommitEntry>` |
| `formatEntries()` | 将提交记录按日期分组，格式化为带中文星期的 Markdown 格式文本 |
| `generateDraftReport()` | 根据报告类型（日报/周报/季报/年报）生成对应结构的草稿模板 |

**git log 命令参数构造**：

```
git log
  --pretty=format:%h|%an|%ad|%s   # 字段分隔格式：hash|作者|日期|标题
  --date=short                      # 日期格式：yyyy-MM-dd
  --after=<startDate-1>             # 时间范围起点（含当天）
  --before=<endDate+1>              # 时间范围终点（含当天）
  --no-merges                       # 过滤合并提交
  [--author=<filter>]               # 可选：按作者名过滤
```

**多仓库支持**：通过 `GitRepositoryManager.getInstance(project).getRepositories()` 获取当前项目下所有 Git 仓库，逐一采集并合并结果，最终按日期降序排列。

**容错机制**：单个仓库采集失败时不中断整体流程，而是将错误信息作为一条特殊记录（author=system, hash=ERROR）追加到结果列表，确保其他仓库的结果仍然可用。

---

### 3. ReportPolishService（AI 润色服务）

**职责**：将报告草稿通过 HTTP 发送至 LLM API，支持普通请求、流式 SSE 请求和带对话历史的多轮请求三种模式。

#### 3.1 三模式支持

**非流式模式（Fallback）**

```
请求 → 等待 → 一次性返回完整响应 → 更新 UI
```

**流式模式（主路径）**

```
请求（stream: true）→ 逐行读取 SSE → 每个 token 立即回调 → 实时追加到 UI
```

**对话历史模式（新增）**

```
携带历史对话记录 → AI 理解上下文 → 基于前序结果继续优化 → 流式/非流式输出
```

#### 3.2 ConversationEntry 数据结构

```java
public static class ConversationEntry {
    public String role;    // "system", "user", 或 "assistant"
    public String content; // 对话内容
}
```

用于存储单轮对话记录，系统提示、用户输入、AI 回复都以此格式保存。

#### 3.3 OkHttpClient 配置
    .connectTimeout(Duration.ofSeconds(10))   // 连接超时
    .readTimeout(Duration.ofSeconds(60))      // 读取超时（流式需要较长时间）
    .writeTimeout(Duration.ofSeconds(10))     // 写入超时
    .build();
```

静态单例，整个服务生命周期内复用，避免重复创建连接池。

#### 3.5 对话历史拼接逻辑（新增）

**首次润色（无历史）**

```json
{
  "messages": [
    {"role": "system", "content": "你是专业的日报润色助手..."},
    {"role": "user", "content": "今日完成工作..."}
  ]
}
```

**第二轮优化（携带 1 轮历史）**

```json
{
  "messages": [
    {"role": "system", "content": "你是专业的日报润色助手..."},
    {"role": "user", "content": "今日完成工作..."},           // 第一轮用户输入
    {"role": "assistant", "content": "润色后的文本..."},     // 第一轮 AI 回复
    {"role": "user", "content": "再精简一些"}                 // 第二轮用户指令
  ]
}
```

**动态构建消息列表**

```java
StringBuilder messagesBuilder = new StringBuilder();

// 1. 添加 System Prompt
messagesBuilder.append("{\"role\": \"system\", \"content\": \"...");

// 2. 遍历历史对话
if (history != null && !history.isEmpty()) {
    for (ConversationEntry entry : history) {
        messagesBuilder.append(", {\"role\": \"").append(entry.role)
            .append("\", \"content\": \"").append(entry.content).append("\"}");
    }
}

// 3. 添加当前用户输入
messagesBuilder.append(", {\"role\": \"user\", \"content\": \"...");
```

优势：AI 能理解完整的对话上下文，无需用户重复说明背景信息。

---

### 4. ReportSettingsState（持久化配置）

**职责**：存储插件所有配置项，通过 IntelliJ PersistentStateComponent 机制序列化为 XML 文件持久化。

**配置项**：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `reportType` | String | `"weekly"` | 当前报告类型 |
| `apiEndpoint` | String | `"http://localhost:11434/api/chat"` | LLM API 端点 |
| `modelName` | String | `"qwen2.5:7b"` | 模型名称 |
| `useOllamaMode` | boolean | `true` | 是否为 Ollama 本地模式 |
| `privacyAccepted` | boolean | `false` | 用户是否已确认隐私提示 |
| `authorFilter` | String | `""` | Git 作者过滤条件 |

**存储路径**：`<IDE config dir>/options/workReportSettings.xml`

**注意**：API Key 不在此存储，通过 PasswordSafe 单独加密存储。

---

### 5. ReportSettingsConfigurable（设置页面）

**职责**：在 IDEA Settings → Tools 下注册「Work Report AI Assistant」设置页面，提供配置 UI 并与 `ReportSettingsState` 双向绑定。

**关键实现**：

- **模式切换联动**：切换 Ollama/云端模式时，自动更新默认端点，并动态启用/禁用 API Key 输入框
- **API Key 加密存储**：使用 `PasswordSafe.getInstance().set()` 将 API Key 写入系统密钥链（Windows Credential Manager / macOS Keychain），读取时使用 `PasswordSafe.getInstance().get()`，全程不以明文存储
- **隐私重置**：当端点发生变更时，调用 `resetPrivacyIfEndpointChanged()` 重置 `privacyAccepted = false`，强制用户重新确认隐私协议

---

### 6. PrivacyNoticeUtil（隐私确认工具）

**职责**：在首次（或端点变更后首次）调用 AI 润色时，弹出 HTML 格式的隐私警告弹窗。

**弹窗内容**：
- 红色加粗警告标题
- 显示当前配置的 API 端点地址
- 区分说明 Ollama 本地模式和云端 API 模式的数据去向
- 默认选中「取消」按钮，防止误操作确认

**确认状态持久化**：用户点击「我已了解并同意」后，将 `privacyAccepted = true` 写入 `ReportSettingsState`，后续调用直接跳过弹窗，直到端点变更后重置。

---

### 7. ReportToolWindowPanel（主 UI 面板）

**职责**：整合所有功能模块，构建插件主交互界面，处理所有用户操作事件。

**布局结构**：

```
SimpleToolWindowPanel
└── BorderLayout 主面板
    ├── NORTH - 工具栏面板（BorderLayout）
    │   ├── LEFT  - 报告类型下拉框 + 起始/结束日期输入框
    │   └── RIGHT - 采集 Git 日志按钮
    ├── CENTER - JSplitPane 左右分栏（比例 4:6）
    │   ├── LEFT  - Git 提交记录面板（JBTextArea, 只读）
    │   └── RIGHT - 报告编辑器面板（JBTextArea, 可编辑）
    └── SOUTH - 底部操作面板（BorderLayout）
        ├── CENTER - 按钮组（AI 润色 / 复制报告 / 导出为文件）
        └── SOUTH  - 状态标签（动态颜色反馈）
```

**日期自动计算逻辑**：

| 报告类型 | 起始日期 | 结束日期 |
|---------|---------|---------|
| 日报 | 今天 | 今天 |
| 周报 | 本周一（`previousOrSame(MONDAY)`） | 今天 |
| 季报 | 本季度第一天（`((month-1)/3)*3+1` 月的 1 日） | 本季度最后一天 |
| 年报 | 当年 1月1日 | 当年 12月31日 |

**润色按钮状态管理**：每次完成 Git 日志采集后调用 `refreshPolishButtonState()`，根据 `ReportSettingsState.isApiConfigured()` 判断 API 端点是否已配置，动态启用或禁用 AI 润色按钮。

---

## 完整数据流转

### 流程一：Git 日志采集

```
用户点击「采集 Git 日志」
        │
        ▼
解析输入日期范围（LocalDate）
        │
        ▼
ApplicationManager.executeOnPooledThread()  [后台线程]
        │
        ▼
GitRepositoryManager.getRepositories()
  获取所有 Git 仓库列表
        │
        ├── 仓库 1：GitLineHandler 执行 git log
        │           Git.getInstance().runCommand(handler)
        │           解析输出行 → List<GitCommitEntry>
        │
        ├── 仓库 2：同上...
        │
        └── 合并所有结果，按日期降序排列
                │
                ▼
        formatEntries()   生成 Git 日志展示文本（按日期分组 + 中文星期）
        generateDraftReport()  生成报告草稿（按报告类型模板）
                │
                ▼
        ApplicationManager.invokeLater()  [回到 EDT]
                │
                ▼
        gitLogArea.setText(formatted)    更新左侧 Git 日志面板
        reportArea.setText(draft)        更新右侧报告编辑区
        statusLabel.setText(...)         更新状态栏
```

### 流程二：AI 润色（流式 + 对话模式）

```
用户点击「AI 润色」
        │
        ▼
PrivacyNoticeUtil.ensurePrivacyAccepted()
  privacyAccepted = true？
    ├── Yes → 跳过弹窗
    └── No  → 弹出隐私确认弹窗
                ├── 用户取消 → 终止流程
                └── 用户确认 → privacyAccepted = true，继续
        │
        ▼
检查 isConversationStarted 状态
        │
        ├── false（首次润色）
        │   ├─ originalDraftText = reportText
        │   ├─ conversationHistory.clear()
        │   ├─ 添加 system prompt 到 history
        │   ├─ isConversationStarted = true
        │   └─ refreshUIForConversation() 显示对话输入区
        │
        └── true（继续对话）
            └─ 直接使用现有 history
        │
        ▼
reportArea.setText("")   清空编辑区，准备流式追加
        │
        ▼
polishAsyncStreamingWithHistory(history, onToken, onSuccess, onError)
        │
        ▼
Task.Backgroundable  [后台线程，IDEA 进度条]
        │
        ▼
polishSyncStreamingWithHistory()
  构建 JSON 请求体（含 System Prompt + 历史对话 + 当前输入，stream: true）
  OkHttp POST 请求 → LLM API 端点
        │
        ▼
  逐行读取响应体（BufferedReader）
        │
        ├── 行格式：data: {...}
        │       │
        │       ▼
        │   extractTokenFromStream()
        │       Ollama: message.content
        │       OpenAI: choices[0].delta.content
        │       │
        │       ▼
        │   onToken.accept(token)
        │       │
        │       ▼
        │   ApplicationManager.invokeLater() [EDT]
        │       │
        │       ▼
        │   reportArea.append(token)   实时追加文字（打字机效果）
        │
        ├── 行格式：data: [DONE] → 结束读取
        │
        └── 所有行读取完毕
                │
                ▼
        onSuccess.accept(fullResult)  [EDT]
                │
                ├─ conversationHistory.add(new ConversationEntry("user", currentText))
                ├─ conversationHistory.add(new ConversationEntry("assistant", fullResult))
                ├─ polishButton.setEnabled(true)
                ├─ sendButton.setEnabled(true)
                ├─ userInputField.setEnabled(true)
                └─ statusLabel.setText("润色完成！您可以继续提出优化要求")

───────────────────────────────────────

用户输入优化指令 → 点击「发送」
        │
        ▼
onSendUserInstruction()
        │
        ▼
禁用 userInputField 和 sendButton（防止重复提交）
        │
        ▼
reportArea.setText("")   清空编辑区
        │
        ▼
polishAsyncStreamingWithHistory(
    currentReportText + "\n\n用户优化要求：" + instruction,
    conversationHistory,  // 携带完整历史
    ...
)
        │
        ▼
同上流式流程 → AI 基于上下文理解后进行优化
        │
        ▼
收到完整响应后
        │
        ├─ conversationHistory.add(user_entry_with_instruction)
        ├─ conversationHistory.add(assistant_entry)
        ├─ 启用 userInputField 和 sendButton
        ├─ 清空输入框
        └─ statusLabel.setText("优化完成！继续提出更多要求...")
```

---

## 插件注册与扩展点（plugin.xml）

```xml
<idea-plugin>
    <!-- 平台核心依赖 -->
    <depends>com.intellij.modules.platform</depends>
    <!-- Git 操作依赖 git4idea 内置插件 -->
    <depends>Git4Idea</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- 注册底部工具窗口 -->
        <toolWindow id="WorkReport"
                    anchor="bottom"
                    factoryClass="ReportToolWindowFactory"/>

        <!-- 注册设置页面（Tools 分组下） -->
        <applicationConfigurable
                instance="ReportSettingsConfigurable"
                parentId="tools"/>

        <!-- 注册持久化配置服务（应用级别单例） -->
        <applicationService
                serviceImplementation="ReportSettingsState"/>
    </extensions>
</idea-plugin>
```

---

## 打包与分发

### 打包命令

```bash
mvn clean package
```

### 产物

```
target/workreport-ai-assistant-1.0.0.zip
└── workreport-ai-assistant/
    └── lib/
        ├── workreport-intellij-plugin-1.0.0.jar   # 插件主 JAR（含 plugin.xml）
        ├── okhttp-4.12.0.jar                       # OkHttp HTTP 客户端
        ├── okio-*.jar                              # OkHttp 依赖
        └── kotlin-stdlib-*.jar                    # OkHttp 依赖
```

**关键设计**：IntelliJ 平台 JAR 以 `system` scope 引用（编译期可见，运行时由 IDE 提供），打包时通过 `<exclude>com.jetbrains.intellij:*</exclude>` 排除，避免 ZIP 包体积膨胀和类冲突。

### 安装

IDEA Settings → Plugins → Install Plugin from Disk → 选择 ZIP → Restart IDE

---

## 关键设计决策

### 1. 为什么使用 GitLineHandler 而非直接调用 git 命令行？

GitLineHandler 是 git4idea 提供的高层封装，自动处理：
- Git 可执行文件路径的跨平台定位
- 工作目录（仓库根目录）的正确设置
- 命令执行结果的结构化返回（成功/失败/输出/错误输出分离）

直接调用 `Runtime.exec("git log ...")` 在 Windows 路径、环境变量等方面容易出错。

### 2. 为什么流式和非流式都保留？

流式依赖 SSE 支持，部分 API 代理或企业网关可能不支持流式响应。保留非流式作为 `polishAsync()` fallback，调用方可按需选择，保障向后兼容性。

### 3. 为什么 API Key 不存在 XML 配置文件中？

`PersistentStateComponent` 的 XML 配置文件存储在 IDEA 配置目录下，有被版本控制或备份工具同步到云端的风险。使用 PasswordSafe + 系统密钥链（Windows Credential Manager / macOS Keychain）存储，密钥从不以明文落盘。

### 4. 为什么每次切换 API 端点都要重置隐私确认？

用户确认的是「向 X 地址发送数据」，一旦端点变更，原来的确认对新地址不再有效，必须重新告知用户数据将发往的新目标，防止静默将数据发送至用户未知的服务。

### 5. UI 字体选择为何使用 UIManager.getFont("TextArea.font")？

IntelliJ IDEA 启动时会根据系统语言和字体配置初始化 `UIManager`，其中 `TextArea.font` 已配置了中文回退字体（Windows 下为 Microsoft YaHei 等）。直接指定 `Consolas` 或 `JetBrains Mono` 等西文等宽字体会因缺少中文字形而导致中文显示为方块乱码。
