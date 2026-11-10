# Work Report AI Assistant - 技术架构文档

> **历史文档**：本文件描述的是 v2.0 重构前「日报生成器」的分层实现（UI / Git 采集 / AI 润色服务），
> 保留用于对照。当前架构（Core / Shell 分离、工具循环、记忆 / Skill / MCP / 子代理）见
> [README](README.md) 与 [docs/AGENT_REFACTOR_DETAILED_DESIGN.md](docs/AGENT_REFACTOR_DETAILED_DESIGN.md)。

## 📚 目录

1. [项目概述](#项目概述)
2. [整体架构](#整体架构)
3. [UI 界面设计](#ui-界面设计)
4. [Git 日志采集服务](#git-日志采集服务)
5. [AI 润色服务](#ai-润色服务)
6. [设置与持久化](#设置与持久化)
7. [隐私保护设计](#隐私保护设计)
8. [设计模式应用](#设计模式应用)
9. [关键技术点详解](#关键技术点详解)

---

## 项目概述

### 项目信息
- **名称**: Work Report AI Assistant
- **类型**: IntelliJ IDEA 插件
- **功能**: 基于 Git 提交历史自动生成 AI 润色的工作报告
- **支持报告类型**: 日报、周报、季报、年报
- **技术栈**: Java 17+, Swing, IntelliJ Platform API, OkHttp3

### 核心功能
1. **Git 日志自动采集** - 按日期范围抓取提交记录
2. **AI 智能润色** - 调用本地 (Ollama) 或云端 LLM 优化报告
3. **多轮对话优化** - 支持持续迭代修改报告
4. **报告导出** - 支持复制/导出 Markdown 文件

---

## 整体架构

### 分层架构图

```
┌─────────────────────────────────────────┐
│         Presentation Layer              │
│      (UI Components & Events)           │
│  ReportToolWindowPanel                  │
│  - ToolWindow Factory                   │
│  - Swing UI Components                  │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│         Business Logic Layer            │
│         (Service Layer)                 │
│  GitLogCollectorService                 │
│  ReportPolishService                    │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│         Data Model Layer                │
│  GitCommitEntry                         │
│  ConversationEntry                      │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│         Infrastructure Layer            │
│  Settings Persistence                   │
│  Privacy Management                     │
│  HTTP Client (OkHttp3)                  │
└─────────────────────────────────────────┘
```

### 模块依赖关系

```
UI Panel (ReportToolWindowPanel)
    ├── uses → GitLogCollectorService
    ├── uses → ReportPolishService
    ├── uses → ReportSettingsState
    └── uses → PrivacyNoticeUtil

GitLogCollectorService
    ├── uses → Git4Idea API
    └── uses → GitCommitEntry (Model)

ReportPolishService
    ├── uses → OkHttp3 (HTTP Client)
    ├── uses → ConversationEntry (Model)
    └── uses → ReportSettingsState

ReportSettingsState
    └── implements → PersistentStateComponent
```

---

## UI 界面设计

### 文件位置
```
src/main/java/com/workreport/plugin/ui/
├── ReportToolWindowFactory.java    # ToolWindow 入口
└── ReportToolWindowPanel.java      # 主面板实现
```

### 核心技术详解

#### 1. ToolWindow 工厂模式

**作用**: IntelliJ 平台的标准 UI 入口机制

**实现代码**:
```java
public class ReportToolWindowFactory implements ToolWindowFactory {
    
    @Override
    public void createToolWindowContent(
        @NotNull Project project, 
        @NotNull ToolWindow toolWindow
    ) {
        // 创建内容面板
        ReportToolWindowPanel panel = new ReportToolWindowPanel(project);
        
        // 添加到 ToolWindow 组件
        toolWindow.getComponent().add(panel);
        
        // 初始化内容（可选）
        panel.init();
    }
}
```

**工作机制**:
1. IDE 启动时扫描 `plugin.xml` 中声明的 `toolWindow` 扩展点
2. 当用户点击工具窗口标签时，调用 `createToolWindowContent()`
3. 工厂类负责创建并返回内容组件
4. 内容组件被缓存，下次显示时直接复用

**设计模式**: **工厂方法模式**
- 解耦了 ToolWindow 管理和具体内容创建
- 符合开闭原则，易于扩展新窗口

---

#### 2. Swing 布局管理器

##### BorderLayout（主布局）

**用途**: 主面板布局，分为 NORTH/CENTER/SOUTH 三个区域

```java
// 创建主面板，使用 BorderLayout
JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));

// 添加顶部工具栏
JPanel toolbarPanel = createToolbarPanel();
mainPanel.add(toolbarPanel, BorderLayout.NORTH);

// 添加中央内容区（分栏）
JSplitPane centerSplitPane = createCenterSplitPane();
mainPanel.add(centerSplitPane, BorderLayout.CENTER);

// 添加底部操作区
JPanel bottomAreaPanel = createBottomAreaPanel();
mainPanel.add(bottomAreaPanel, BorderLayout.SOUTH);
```

**布局特点**:
- NORTH: 固定高度，放置工具栏
- CENTER: 占据剩余空间，放置主要内容
- SOUTH: 固定高度或自适应，放置操作按钮

##### FlowLayout（按钮布局）

**用途**: 按钮面板、行内组件排列

```java
// 左对齐按钮面板
JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
buttonPanel.setOpaque(false); // 透明背景

// 添加按钮
buttonPanel.add(polishButton);
buttonPanel.add(Box.createHorizontalStrut(10)); // 固定间距
buttonPanel.add(copyButton);
buttonPanel.add(exportButton);
```

**参数说明**:
- `FlowLayout.LEFT`: 左对齐
- `10`: 组件间水平间距 (hgap)
- `0`: 组件间垂直间距 (vgap)

##### GridBagLayout（复杂表单）

**用途**: 设置页面的表单布局

```java
JPanel panel = new JPanel(new GridBagLayout());
GridBagConstraints constraints = new GridBagConstraints();

// 第一行：API Endpoint 标签
constraints.gridx = 0;
constraints.gridy = 0;
constraints.anchor = GridBagConstraints.WEST;
panel.add(new JLabel("API Endpoint:"), constraints);

// 第一行：API Endpoint 输入框
constraints.gridx = 1;
constraints.fill = GridBagConstraints.HORIZONTAL;
constraints.weightx = 1.0;
panel.add(apiEndpointField, constraints);

// 第二行：模型选择标签
constraints.gridx = 0;
constraints.gridy = 1;
constraints.weightx = 0;
panel.add(new JLabel("AI Model:"), constraints);

// 第二行：模型下拉框
constraints.gridx = 1;
constraints.weightx = 1.0;
panel.add(modelCombo, constraints);
```

**关键参数**:
- `gridx/gridy`: 网格坐标
- `weightx/weighty`: 空间分配权重
- `fill`: 填充方式 (HORIZONTAL/VERTICAL/BOTH)
- `anchor`: 组件对齐方式

---

#### 3. IntelliJ 平台专用组件

##### JB 系列组件（主题适配）

**优势**: 自动适配 IDEA 主题（Light/Dark Mode）

```java
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBColor;

// 标签
JBLabel label = new JBLabel("Git 提交记录 (只读)");

// 文本框（带默认值）
JBTextField textField = new JBTextField("2026-03-31", 9);

// 多行文本区
JBTextArea textArea = new JBTextArea(10, 40);
textArea.setEditable(false);
textArea.setLineWrap(true);

// 滚动面板
JBScrollPane scrollPane = new JBScrollPane(textArea);
scrollPane.setBorder(LineBorder.createGrayLineBorder());

// 颜色（自动适配主题）
JBColor successColor = new JBColor(
    new Color(0, 120, 0),      // Light 模式
    new Color(80, 200, 80)     // Dark 模式
);
statusLabel.setForeground(successColor);
```

**常用 API**:
- `JBColor.isDark()`: 判断当前主题
- `UIUtil.getPanelBackground()`: 获取面板背景色
- `JBUI.scale()`: DPI 适配

---

#### 4. EDT 线程模型

**核心规则**: **所有 UI 操作必须在 Event Dispatch Thread (EDT) 执行**

##### 场景 1: UI 更新

```java
// ✅ 正确：在 EDT 执行
ApplicationManager.getApplication().invokeLater(() -> {
    textArea.setText("更新内容");
    statusLabel.setText("采集完成");
});

// ❌ 错误：在后台线程直接更新 UI
// textArea.setText("更新内容"); // 可能导致界面卡死或异常
```

##### 场景 2: 异步任务 + UI 回调

```java
private void onCollectGitLog() {
    // 1. 解析日期（UI 线程）
    LocalDate startDate = parseDate(startDateField.getText());
    
    // 2. 后台执行耗时操作（非 UI 线程）
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
        // 耗时操作：Git 日志采集
        List<GitCommitEntry> entries = gitService.collect(project, startDate, endDate);
        String formatted = gitService.format(entries);
        
        // 3. 回到 EDT 更新 UI
        ApplicationManager.getApplication().invokeLater(() -> {
            gitLogArea.setText(formatted);
            statusLabel.setText("采集完成，共 " + entries.size() + " 条记录");
        });
    });
}
```

**线程切换流程**:
```
用户点击按钮 (EDT)
    ↓
executeOnPooledThread (后台线程池)
    ↓
执行 Git 采集 (后台线程)
    ↓
invokeLater (调度回 EDT)
    ↓
更新 UI 组件 (EDT)
```

**为什么需要 EDT?**
- Swing 不是线程安全的
- 避免多线程同时修改 UI 导致状态不一致
- 保证事件处理的顺序性

---

#### 5. 复合组件创建

##### 工具栏面板创建

```java
private JPanel createToolbarPanel() {
    // 外层：BorderLayout
    JPanel panel = new JPanel(new BorderLayout(8, 0));
    panel.setBackground(UIUtil.getPanelBackground());
    panel.setBorder(new EmptyBorder(8, 0, 8, 0));
    
    // 左侧：报告类型 + 日期选择
    JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    leftPanel.setOpaque(false);
    
    // 报告类型下拉框
    reportTypeCombo = new JComboBox<>(new String[]{"日报", "周报", "季报", "年报"});
    reportTypeCombo.addActionListener(e -> onReportTypeChanged());
    
    // 日期输入框
    startDateField = new JBTextField("2026-03-31", 9);
    endDateField = new JBTextField("2026-03-31", 9);
    
    // 组装左侧
    leftPanel.add(new JBLabel("报告类型:"));
    leftPanel.add(reportTypeCombo);
    leftPanel.add(Box.createHorizontalStrut(10)); // 间隔
    leftPanel.add(new JBLabel("起始日期:"));
    leftPanel.add(startDateField);
    leftPanel.add(new JBLabel("结束日期:"));
    leftPanel.add(endDateField);
    
    // 右侧：采集按钮
    JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    rightPanel.setOpaque(false);
    collectButton = new JButton("采集 Git 日志");
    rightPanel.add(collectButton);
    
    // 组装
    panel.add(leftPanel, BorderLayout.CENTER);
    panel.add(rightPanel, BorderLayout.EAST);
    
    return panel;
}
```

**嵌套结构**:
```
toolbarPanel (BorderLayout)
    ├── CENTER: leftPanel (FlowLayout)
    │   ├── "报告类型:" Label
    │   ├── reportTypeCombo
    │   ├── HorizontalStrut (间隔)
    │   ├── "起始日期:" Label
    │   ├── startDateField
    │   ├── "结束日期:" Label
    │   └── endDateField
    └── EAST: rightPanel (FlowLayout)
        └── collectButton
```

---

### UI 事件处理机制

#### ActionListener（按钮点击）

```java
// 方式 1: Lambda 表达式（推荐）
polishButton.addActionListener(e -> onPolish());
copyButton.addActionListener(e -> onCopy());

// 方式 2: 匿名内部类
clearHistoryButton.addActionListener(new ActionListener() {
    @Override
    public void actionPerformed(ActionEvent e) {
        onClearConversation();
    }
});
```

#### FocusListener（焦点事件）

```java
userInputField.addFocusListener(new FocusAdapter() {
    @Override
    public void focusGained(FocusEvent e) {
        // 获得焦点时清空提示文本
        if (userInputField.getText().contains("请输入优化指令")) {
            userInputField.setText("");
            userInputField.setForeground(JBColor.BLACK);
        }
    }
    
    @Override
    public void focusLost(FocusEvent e) {
        // 失去焦点时恢复提示文本
        if (userInputField.getText().isEmpty()) {
            userInputField.setText("请输入优化指令...");
            userInputField.setForeground(JBColor.GRAY);
        }
    }
});
```

#### ItemListener（下拉框选择）

```java
reportTypeCombo.addItemListener(e -> {
    if (e.getStateChange() == ItemEvent.SELECTED) {
        onReportTypeChanged();
    }
});
```

---

## Git 日志采集服务

### 文件位置
```
src/main/java/com/workreport/plugin/service/GitLogCollectorService.java
```

### 核心职责
1. 执行 Git Log 命令
2. 解析输出结果
3. 格式化展示
4. 生成报告草稿

---

### 技术详解

#### 1. Git4Idea API 使用

##### 获取仓库管理器

```java
// 单例模式获取管理器
GitRepositoryManager manager = GitRepositoryManager.getInstance(project);

// 获取所有 Git 仓库
List<GitRepository> repos = manager.getRepositories();

// 遍历多个仓库（支持 Multi-root 项目）
for (GitRepository repo : repos) {
    VirtualFile root = repo.getRoot(); // 仓库根目录
    String repoName = root.getName();  // 仓库名称
}
```

**为什么支持多仓库？**
- IntelliJ 支持 Multi-root 项目（一个 Project 包含多个独立 Git 仓库）
- 例如：微服务架构下每个服务是一个独立仓库

---

##### 构建 Git 命令

```java
// 创建命令处理器
GitLineHandler handler = new GitLineHandler(
    project,           // 项目上下文
    repo.getRoot(),    // Git 仓库根目录
    GitCommand.LOG     // 执行 git log 命令
);

// 添加参数
handler.addParameters(
    "--pretty=format:%h|%an|%ad|%s",  // 输出格式：hash|作者 | 日期 | 标题
    "--date=short",                   // 日期格式：YYYY-MM-DD
    "--after=2026-03-30 00:00:00",    // 开始日期（包含）
    "--before=2026-04-01 00:00:00",   // 结束日期（不包含）
    "--no-merges"                     // 排除合并提交
);

// 可选：作者过滤
if (authorFilter != null && !authorFilter.isBlank()) {
    handler.addParameters("--author=" + authorFilter);
}
```

**参数详解**:
- `--pretty=format`: 自定义输出格式，用 `|` 分隔字段
- `--date=short`: 短日期格式（便于解析）
- `--after/--before`: 时间范围（左闭右开区间）
- `--no-merges`: 排除 merge commit，只看实际代码提交

---

##### 执行命令并处理结果

```java
// 获取 Git 实例并执行命令
GitCommandResult result = Git.getInstance().runCommand(handler);

// 检查执行结果
if (result.success()) {
    // 成功：解析输出
    String output = result.getOutputAsJoinedString();
    parseGitOutput(output, allEntries);
} else {
    // 失败：记录错误
    allEntries.add(new GitCommitEntry(
        "ERROR", 
        "system", 
        LocalDate.now(),
        "git log 命令失败：" + result.getErrorOutputAsJoinedString()
    ));
}
```

**GitCommandResult 结构**:
```java
class GitCommandResult {
    boolean success();                          // 是否成功
    int getExitCode();                          // 退出码
    String getOutputAsJoinedString();           // 标准输出
    String getErrorOutputAsJoinedString();      // 错误输出
}
```

---

#### 2. Git 输出解析

##### 解析逻辑

```java
private void parseGitOutput(String output, List<GitCommitEntry> entries) {
    if (output == null || output.isBlank()) return;
    
    // 按行分割
    for (String line : output.split("\n")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) continue;
        
        // 按分隔符拆分字段
        String[] parts = trimmed.split("\\|", 4); // 最多 split 4 段
        
        if (parts.length >= 4) {
            try {
                // 解析各字段
                String hash = parts[0].trim();      // 短哈希
                String author = parts[1].trim();    // 作者
                LocalDate date = LocalDate.parse(   // 日期
                    parts[2].trim(), 
                    DateTimeFormatter.ofPattern("yyyy-MM-dd")
                );
                String subject = parts[3].trim();   // 提交标题
                
                // 创建条目对象
                entries.add(new GitCommitEntry(
                    hash, author, date, subject
                ));
            } catch (Exception ignored) {
                // 跳过格式异常的行
            }
        }
    }
}
```

**示例输入输出**:

**输入**:
```
abc123|张三 |2026-03-31|fix: 修复登录 bug
def456|李四 |2026-03-30|feat: 新增用户管理功能
```

**输出**:
```java
[
  GitCommitEntry(hash="abc123", author="张三", date=2026-03-31, subject="fix: 修复登录 bug"),
  GitCommitEntry(hash="def456", author="李四", date=2026-03-30, subject="feat: 新增用户管理功能")
]
```

---

#### 3. 日期范围计算

##### 业务规则

| 报告类型 | 日期范围 | 计算逻辑 |
|---------|---------|---------|
| **日报** | 当天 | `today` to `today` |
| **周报** | 本周一 ~ 今天 | `Monday` to `today` |
| **季报** | 本季度 | `Quarter Start` to `Quarter End` |
| **年报** | 本年 | `Jan 1` to `Dec 31` |

##### 实现代码

```java
private LocalDate[] calculateDateRange(String reportType) {
    LocalDate today = LocalDate.now();
    LocalDate start, end;
    
    switch (reportType) {
        case "daily":
            // 日报：仅今天
            start = today;
            end = today;
            break;
            
        case "weekly":
            // 周报：本周一到今天
            start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            end = today;
            break;
            
        case "quarterly":
            // 季报：本季度
            int month = today.getMonthValue();
            int quarterStartMonth = ((month - 1) / 3) * 3 + 1; // 1,4,7,10
            start = LocalDate.of(today.getYear(), quarterStartMonth, 1);
            end = start.plusMonths(3).minusDays(1);
            break;
            
        case "yearly":
            // 年报：本年
            start = LocalDate.of(today.getYear(), 1, 1);
            end = LocalDate.of(today.getYear(), 12, 31);
            break;
            
        default:
            // 默认周报
            start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            end = today;
    }
    
    return new LocalDate[]{start, end};
}
```

**关键 API**:
- `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)`: 找到最近一个周一（包含今天）
- `((month - 1) / 3) * 3 + 1`: 计算季度起始月（整数除法向下取整）

---

#### 4. 数据格式化

##### 按日期分组

```java
// 使用 Stream API 分组
Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
    .collect(Collectors.groupingBy(
        GitCommitEntry::date,          // 按日期分组
        TreeMap::new,                  // 使用 TreeMap 自动排序
        Collectors.toList()            // 每组收集为 List
    ));
```

**数据结构**:
```java
{
  2026-03-30: [
    GitCommitEntry(..., subject="fix: bug1"),
    GitCommitEntry(..., subject="feat: feature1")
  ],
  2026-03-31: [
    GitCommitEntry(..., subject="fix: bug2")
  ]
}
```

---

##### 生成 Markdown 格式

```java
var sb = new StringBuilder();

// 遍历分组（TreeMap 已按日期排序）
for (var entry : grouped.entrySet()) {
    LocalDate date = entry.getKey();
    List<GitCommitEntry> commits = entry.getValue();
    
    // 计算星期几
    int dayIdx = date.getDayOfWeek().getValue() - 1; // 1=周一，7=周日
    String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    String dayName = dayNames[dayIdx];
    
    // 添加日期标题
    sb.append("## ").append(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
      .append("（").append(dayName).append("）\n");
    
    // 添加提交列表
    for (GitCommitEntry commit : commits) {
        sb.append("- ").append(commit.subject())
          .append(" (").append(commit.author()).append(")\n");
    }
    sb.append("\n");
}

return sb.toString().trim();
```

**输出示例**:
```markdown
## 2026-03-30（周一）
- fix: 修复登录 bug (张三)
- feat: 新增用户管理 (李四)

## 2026-03-31（周二）
- fix: 优化 UI 布局 (王五)
```

---

#### 5. 报告草稿生成

##### 策略模式实现

```java
public String generateDraftReport(List<GitCommitEntry> entries, String reportType) {
    return switch (reportType) {
        case "daily" -> generateDailyReport(entries);
        case "quarterly" -> generateQuarterlyReport(entries);
        case "yearly" -> generateYearlyReport(entries);
        default -> generateWeeklyReport(entries); // 默认周报
    };
}
```

##### 日报模板

```java
private String generateDailyReport(List<GitCommitEntry> entries) {
    if (entries.isEmpty()) {
        return "今日工作内容：\n\n（暂无 Git 提交记录，请手动填写）";
    }
    
    var sb = new StringBuilder();
    sb.append("# 日报\n\n");
    sb.append("## 今日完成工作\n\n");
    
    // 列出所有提交
    for (GitCommitEntry commit : entries) {
        sb.append("- ").append(commit.subject()).append("\n");
    }
    
    // 固定模板部分
    sb.append("\n## 明日计划\n\n");
    sb.append("- （请填写明日计划）\n");
    sb.append("\n## 遇到的问题\n\n");
    sb.append("- 无\n");
    
    return sb.toString();
}
```

**输出示例**:
```markdown
# 日报

## 今日完成工作

- fix: 修复登录 bug
- feat: 新增用户管理功能

## 明日计划

- （请填写明日计划）

## 遇到的问题

- 无
```

---

## AI 润色服务

### 文件位置
```
src/main/java/com/workreport/plugin/service/ReportPolishService.java
```

### 核心职责
1. 调用 LLM API（本地 Ollama 或云端）
2. 流式响应处理（打字机效果）
3. 对话历史管理
4. 错误处理与通知

---

### 技术详解

#### 1. HTTP 客户端配置（OkHttp3）

##### 创建 OkHttpClient

```java
// 创建 HTTP 客户端（可复用单例）
OkHttpClient httpClient = new OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时
    .readTimeout(60, TimeUnit.SECONDS)     // 读取超时
    .writeTimeout(30, TimeUnit.SECONDS)    // 写入超时
    .build();
```

**为什么需要超时设置？**
- 防止 AI 接口无响应导致插件卡死
- 释放网络连接资源
- 提升用户体验（及时报错）

---

##### 构建 JSON 请求体

```java
// 使用 Gson 构建 JSON
Gson gson = new Gson();

// 消息结构
class Message {
    String role;    // "system" | "user" | "assistant"
    String content; // 消息内容
}

// 请求体
class ChatRequest {
    String model;                    // 模型名称
    List<Message> messages;          // 对话历史
    boolean stream;                  // 是否流式响应
}

// 构建请求
List<Message> messages = new ArrayList<>();
messages.add(new Message("system", "你是一个专业的润色助手..."));
messages.add(new Message("user", "请润色这份报告..."));

ChatRequest request = new ChatRequest();
request.model = "qwen2.5:7b";
request.messages = messages;
request.stream = true;

String jsonBody = gson.toJson(request);
```

**生成的 JSON**:
```json
{
  "model": "qwen2.5:7b",
  "messages": [
    {
      "role": "system",
      "content": "你是一个专业的润色助手..."
    },
    {
      "role": "user",
      "content": "请润色这份报告..."
    }
  ],
  "stream": true
}
```

---

##### 同步调用示例

```java
// 构建 HTTP 请求
Request httpRequest = new Request.Builder()
    .url("http://localhost:11434/api/chat")
    .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
    .build();

// 执行请求（阻塞等待响应）
try (Response response = httpClient.newCall(httpRequest).execute()) {
    if (!response.isSuccessful()) {
        throw new IOException("Unexpected code " + response);
    }
    
    // 获取响应体
    String responseBody = response.body().string();
    return responseBody;
}
```

**缺点**: 必须等待完整响应返回才能处理
**改进**: 使用流式响应（见下一节）

---

#### 2. 流式响应处理（SSE）

##### Server-Sent Events 原理

**传统 HTTP**:
```
Client Request → Wait... → Server Response (完整 JSON)
```

**SSE 流式**:
```
Client Request → Server Stream:
  data: {"token": "你"}
  data: {"token": "好"}
  data: {"token": "，"}
  data: {"token": "世"}
  data: {"token": "界"}
  ...
```

**优势**:
- 实时显示 AI 回复（打字机效果）
- 降低用户感知延迟
- 支持长文本流式输出

---

##### EventSource 实现

```java
// 创建 EventSource 工厂
EventSource.Factory factory = EventSources.createFactory(httpClient);

// 定义回调
EventSource.Listener callback = new EventSource.Listener() {
    @Override
    public void onOpen(EventSource eventSource, Response response) {
        System.out.println("SSE 连接已建立");
    }
    
    @Override
    public void onEvent(EventSource eventSource, String id, String type, String data) {
        // 逐字接收并追加到 UI
        String token = data; // 例如："你"
        
        // 在 EDT 更新 UI
        ApplicationManager.getApplication().invokeLater(() -> {
            reportArea.append(token);
        });
    }
    
    @Override
    public void onClosed(EventSource eventSource) {
        System.out.println("SSE 连接已关闭");
    }
    
    @Override
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
        System.err.println("SSE 错误：" + t.getMessage());
    }
};

// 发起流式请求
EventSource eventSource = factory.newEventSource(httpRequest, callback);

// 保持连接（直到 AI 回复完成）
// EventSource 会自动管理连接生命周期
```

**工作流程**:
```
1. 创建 EventSource → 建立 SSE 连接
2. 服务器推送第一个 token → onEvent("你")
3. UI 追加 "你"
4. 服务器推送第二个 token → onEvent("好")
5. UI 追加 "好"
...
N. 服务器推送完成 → onClosed()
```

---

#### 3. 对话历史管理

##### 数据结构

```java
// 对话条目
public record ConversationEntry(
    String role,      // "system" | "user" | "assistant"
    String content    // 消息内容
) {}

// 对话历史（列表）
List<ConversationEntry> conversationHistory = new ArrayList<>();
```

---

##### 添加系统提示

```java
// 第一次润色时添加系统提示
if (!isConversationStarted) {
    String systemPrompt = switch (reportType) {
        case "daily" -> "你是一个专业的日报润色助手...";
        case "weekly" -> "你是一个专业的周报润色助手...";
        case "quarterly" -> "你是一个专业的季报润色助手...";
        default -> "你是一个专业的年报润色助手...";
    };
    
    conversationHistory.add(new ConversationEntry("system", systemPrompt));
    isConversationStarted = true;
}
```

**作用**:
- 设定 AI 角色和任务
- 提供润色指南（语气、格式要求）
- 仅添加一次，避免重复

---

##### 多轮对话上下文

```java
// 第一轮：用户输入
conversationHistory.add(new ConversationEntry("user", originalReport));

// 第一轮：AI 回复
conversationHistory.add(new ConversationEntry("assistant", polishedText));

// 第二轮：用户提出优化要求
String userInstruction = "请精简一点，突出技术难点";
conversationHistory.add(new ConversationEntry("user", 
    originalReport + "\n\n优化要求：" + userInstruction));

// 第二轮：AI 基于上下文继续优化
polishService.polishAsyncStreamingWithHistory(
    project,
    currentText,
    reportType,
    conversationHistory,  // 包含前两轮对话
    tokenCallback,
    successCallback,
    errorCallback
);
```

**完整历史示例**:
```java
[
  {
    "role": "system",
    "content": "你是一个专业的润色助手..."
  },
  {
    "role": "user",
    "content": "# 日报\n今日完成工作:\n- fix bug"
  },
  {
    "role": "assistant",
    "content": "# 日报\n## 今日完成工作\n- 修复了关键 bug..."
  },
  {
    "role": "user",
    "content": "请精简一点"
  },
  {
    "role": "assistant",
    "content": "# 日报\n## 完成工作\n- 修复 bug"
  }
]
```

**为什么需要历史？**
- AI 理解上下文（指代关系）
- 避免重复说明背景
- 支持渐进式优化

---

#### 4. 异步润色方法

##### 方法签名

```java
public void polishAsyncStreamingWithHistory(
    Project project,
    String reportText,
    String reportType,
    List<ConversationEntry> history,
    Consumer<String> tokenCallback,      // 每收到一个 token 的回调
    Consumer<String> successCallback,    // 成功完成的回调
    Consumer<Throwable> errorCallback    // 失败的回调
)
```

**参数说明**:
- `project`: IDEA 项目上下文
- `reportText`: 待润色的报告文本
- `reportType`: 报告类型（daily/weekly等）
- `history`: 对话历史
- `tokenCallback`: 流式输出回调（用于 UI 实时更新）
- `successCallback`: 完成回调（用于清理 UI 状态）
- `errorCallback`: 错误回调（用于错误提示）

---

##### 实现流程

```java
public void polishAsyncStreamingWithHistory(...) {
    // 1. 在后台线程执行
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
        try {
            // 2. 构建请求体
            List<Message> messages = convertToMessages(history);
            messages.add(new Message("user", reportText));
            
            ChatRequest request = new ChatRequest();
            request.model = settings.modelName;
            request.messages = messages;
            request.stream = true;
            
            String jsonBody = new Gson().toJson(request);
            
            // 3. 创建 HTTP 请求
            Request httpRequest = new Request.Builder()
                .url(settings.apiEndpoint)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();
            
            // 4. 创建 EventSource
            EventSource.Factory factory = EventSources.createFactory(httpClient);
            
            AtomicReference<StringBuilder> fullResponse = new AtomicReference<>(new StringBuilder());
            
            EventSource.Listener listener = new EventSource.Listener() {
                @Override
                public void onEvent(EventSource es, String id, String type, String data) {
                    // 5. 逐字回调
                    tokenCallback.accept(data);
                    fullResponse.get().append(data);
                }
                
                @Override
                public void onClosed(EventSource es) {
                    // 6. 成功完成回调
                    successCallback.accept(fullResponse.get().toString());
                }
                
                @Override
                public void onFailure(EventSource es, Throwable t, Response response) {
                    // 7. 错误回调
                    errorCallback.accept(t);
                }
            };
            
            // 8. 发起请求
            factory.newEventSource(httpRequest, listener);
            
        } catch (Exception e) {
            errorCallback.accept(e);
        }
    });
}
```

**线程安全考虑**:
- 所有 UI 更新通过 `invokeLater` 调度到 EDT
- 使用 `AtomicReference` 保证跨线程变量可见性
- EventSource 在后台线程回调，不阻塞主线程

---

#### 5. 错误处理与通知

##### 统一错误处理

```java
// 错误回调
error -> {
    // 1. 恢复 UI 状态
    polishButton.setEnabled(true);
    collectButton.setEnabled(true);
    
    // 2. 显示错误信息
    statusLabel.setText("润色失败：" + error.getMessage());
    statusLabel.setForeground(JBColor.RED);
    
    // 3. 系统通知弹窗
    ReportPolishService.notifyError(project, "润色失败：" + error.getMessage());
    
    // 4. 恢复原始内容（如果已清空）
    reportArea.setText(originalDraft);
}
```

---

##### 系统通知实现

```java
public static void notifyError(Project project, String message) {
    ApplicationManager.getApplication().invokeLater(() -> {
        Notification notification = new Notification(
            "Work Report Notifications",  // 通知组 ID
            "润色失败",                    // 标题
            message,                      // 内容
            NotificationType.ERROR        // 类型：ERROR/WARNING/INFORMATION
        );
        
        // 显示通知（右下角弹窗）
        Notifications.Bus.notify(notification, project);
    });
}
```

**通知效果**:
```
┌─────────────────────────────────┐
│ ⚠️ 润色失败                     │
│ 连接超时，请检查网络或 API 端点   │
│                      [查看帮助]  │
└─────────────────────────────────┘
```

---

## 设置与持久化

### 文件位置
```
src/main/java/com/workreport/plugin/settings/
├── ReportSettingsState.java         # 状态管理类
└── ReportSettingsConfigurable.java  # 设置页面 UI
```

---

### 技术详解

#### 1. PersistentStateComponent

##### 接口定义

```java
public interface PersistentStateComponent<T> {
    T getState();                      // 获取当前状态
    void loadState(@NotNull T state);  // 从持久化加载状态
}
```

**作用**: IntelliJ 平台提供的持久化框架
- 自动序列化/反序列化
- IDE 启动时自动加载
- IDE 关闭时自动保存
- 存储位置：`.idea/workReportSettings.xml`

---

##### 实现类

```java
@State(
    name = "WorkReportSettings",           // 状态名称
    storages = @Storage("workReportSettings.xml")  // 存储文件名
)
@Service(Service.Level.APP)  // APP 级别单例（整个 IDE 一个实例）
public final class ReportSettingsState implements PersistentStateComponent<ReportSettingsState> {
    
    // ========== 持久化字段 ==========
    
    // 报告类型：daily, weekly, quarterly, yearly
    public String reportType = "weekly";
    
    // API 端点
    public String apiEndpoint = "http://localhost:11434/api/chat";
    
    // 模型名称
    public String modelName = "qwen2.5:7b";
    
    // 是否使用 Ollama 本地模式
    public boolean useOllamaMode = true;
    
    // 隐私协议确认状态
    public boolean privacyAccepted = false;
    
    // Git 作者过滤（留空表示所有作者）
    public String authorFilter = "";
    
    // ========== 单例访问 ==========
    
    public static ReportSettingsState getInstance() {
        return ApplicationManager.getApplication()
            .getService(ReportSettingsState.class);
    }
    
    // ========== 接口实现 ==========
    
    @Override
    public ReportSettingsState getState() {
        return this;  // 返回当前状态对象
    }
    
    @Override
    public void loadState(@NotNull ReportSettingsState state) {
        // 从 XML 加载时调用
        this.reportType = state.reportType != null ? state.reportType : "weekly";
        this.apiEndpoint = state.apiEndpoint;
        this.modelName = state.modelName;
        this.useOllamaMode = state.useOllamaMode;
        this.privacyAccepted = state.privacyAccepted;
        this.authorFilter = state.authorFilter;
    }
}
```

---

##### 序列化过程

**保存时** (IDE 关闭或配置变更):
```java
// 1. 调用 getState()
ReportSettingsState state = settingsInstance.getState();

// 2. 序列化为 XML
/*
<WorkReportSettings>
  <reportType>weekly</reportType>
  <apiEndpoint>http://localhost:11434/api/chat</apiEndpoint>
  <modelName>qwen2.5:7b</modelName>
  <useOllamaMode>true</useOllamaMode>
  <privacyAccepted>true</privacyAccepted>
  <authorFilter></authorFilter>
</WorkReportSettings>
*/

// 3. 写入文件
// .idea/workReportSettings.xml
```

**加载时** (IDE 启动):
```java
// 1. 读取 XML 文件
// .idea/workReportSettings.xml

// 2. 反序列化为对象
ReportSettingsState state = new ReportSettingsState();
state.reportType = "weekly";
state.apiEndpoint = "http://localhost:11434/api/chat";
...

// 3. 调用 loadState()
settingsInstance.loadState(state);
```

---

#### 2. Configurable 设置页面

##### 接口定义

```java
public interface Configurable {
    @Nullable
    JComponent createComponent();  // 创建 UI 组件
    
    boolean isModified();          // 是否有修改
    
    void apply() throws ConfigurationException;  // 应用修改
    
    @NotNull
    String getDisplayName();       // 显示名称
    
    @Nullable
    Runnable enableSearch(String option);  // 搜索支持（可选）
}
```

---

##### 实现示例

```java
public class ReportSettingsConfigurable implements Configurable {
    
    // UI 组件
    private JTextField apiEndpointField;
    private JComboBox<String> modelCombo;
    private JBCheckBox useOllamaCheckBox;
    private JTextField authorFilterField;
    
    @Override
    public JComponent createComponent() {
        // 获取当前设置
        ReportSettingsState settings = ReportSettingsState.getInstance();
        
        // 创建面板（GridBagLayout 布局）
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        
        // === 第一行：API Endpoint ===
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("API Endpoint:"), constraints);
        
        apiEndpointField = new JTextField(settings.apiEndpoint, 40);
        constraints.gridx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        panel.add(apiEndpointField, constraints);
        
        // === 第二行：AI 模型 ===
        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.weightx = 0;
        panel.add(new JLabel("AI Model:"), constraints);
        
        modelCombo = new JComboBox<>(new String[]{
            "qwen2.5:7b",
            "qwen2.5:14b",
            "deepseek-coder:6.7b",
            "llama3:8b"
        });
        modelCombo.setSelectedItem(settings.modelName);
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        panel.add(modelCombo, constraints);
        
        // === 第三行：Ollama 模式 ===
        constraints.gridx = 0;
        constraints.gridy = 2;
        constraints.weightx = 0;
        panel.add(new JLabel("使用 Ollama 本地模式:"), constraints);
        
        useOllamaCheckBox = new JBCheckBox();
        useOllamaCheckBox.setSelected(settings.useOllamaMode);
        constraints.gridx = 1;
        constraints.weightx = 0;
        panel.add(useOllamaCheckBox, constraints);
        
        // === 第四行：作者过滤 ===
        constraints.gridx = 0;
        constraints.gridy = 3;
        constraints.weightx = 0;
        panel.add(new JLabel("Git 作者过滤:"), constraints);
        
        authorFilterField = new JTextField(settings.authorFilter, 40);
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        panel.add(authorFilterField, constraints);
        
        // 添加说明标签
        JLabel hintLabel = new JLabel(
            "<html><i>留空表示所有作者，支持通配符 *</i></html>"
        );
        constraints.gridx = 1;
        constraints.gridy = 4;
        panel.add(hintLabel, constraints);
        
        return panel;
    }
    
    @Override
    public boolean isModified() {
        ReportSettingsState settings = ReportSettingsState.getInstance();
        
        // 检测是否有修改
        boolean modified = !apiEndpointField.getText().equals(settings.apiEndpoint)
            || !modelCombo.getSelectedItem().equals(settings.modelName)
            || useOllamaCheckBox.isSelected() != settings.useOllamaMode
            || !authorFilterField.getText().equals(settings.authorFilter);
        
        return modified;
    }
    
    @Override
    public void apply() throws ConfigurationException {
        ReportSettingsState settings = ReportSettingsState.getInstance();
        
        // 验证输入
        if (apiEndpointField.getText().isBlank()) {
            throw new ConfigurationException("API Endpoint 不能为空");
        }
        
        // 保存设置
        settings.apiEndpoint = apiEndpointField.getText();
        settings.modelName = (String) modelCombo.getSelectedItem();
        settings.useOllamaMode = useOllamaCheckBox.isSelected();
        settings.authorFilter = authorFilterField.getText();
        
        // 如果 API 端点变更，重置隐私确认（要求重新同意）
        settings.resetPrivacyIfEndpointChanged(apiEndpointField.getText());
    }
    
    @Override
    public String getDisplayName() {
        return "Work Report AI Assistant";
    }
}
```

---

##### 注册到 Settings

**plugin.xml**:
```xml
<extensions defaultExtensionNs="com.intellij">
    <applicationConfigurable
        instance="com.workreport.plugin.settings.ReportSettingsConfigurable"
        id="com.workreport.settings"
        displayName="Work Report AI Assistant"
        parentId="tools"/>  <!-- 位于 Tools 分类下 -->
</extensions>
```

**访问路径**:
```
File → Settings → Tools → Work Report AI Assistant
(macOS: IntelliJ IDEA → Preferences → Tools → ...)
```

---

## 隐私保护设计

### 文件位置
```
src/main/java/com/workreport/plugin/privacy/PrivacyNoticeUtil.java
```

---

### 技术详解

#### 1. 首次使用确认机制

##### 业务流程

```
用户首次点击"AI 润色"
    ↓
检查 privacyAccepted 标志
    ↓
如果为 false → 弹出隐私协议对话框
    ↓
用户阅读并同意 → 设置 privacyAccepted = true
    ↓
后续使用不再弹出
```

---

##### 实现代码

```java
public class PrivacyNoticeUtil {
    
    /**
     * 确保用户已接受隐私协议
     * @return true 如果用户已同意，false 如果用户取消
     */
    public static boolean ensurePrivacyAccepted() {
        ReportSettingsState settings = ReportSettingsState.getInstance();
        
        // 如果已同意，直接通过
        if (settings.privacyAccepted) {
            return true;
        }
        
        // 弹出隐私协议对话框
        PrivacyNoticeDialog dialog = new PrivacyNoticeDialog();
        
        // 显示模态对话框并等待用户操作
        boolean accepted = dialog.showAndGet();
        
        if (accepted) {
            // 用户同意，保存状态
            settings.privacyAccepted = true;
            return true;
        } else {
            // 用户取消
            return false;
        }
    }
}
```

---

#### 2. 隐私协议对话框

##### Dialog 实现

```java
public class PrivacyNoticeDialog extends DialogWrapper {
    
    private JEditorPane contentPane;
    
    public PrivacyNoticeDialog() {
        super(true); // use current window as parent
        setTitle("隐私协议");
        init();
    }
    
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(600, 400));
        
        // 协议内容（HTML 格式）
        String privacyText = """
            <html>
            <h2>隐私协议</h2>
            <p>使用本插件的 AI 润色功能时，您的工作报告内容将被发送到：</p>
            <ul>
              <li><b>本地模式 (Ollama)</b>: 数据仅在本地传输，不会离开您的计算机</li>
              <li><b>云端模式</b>: 数据将发送到第三方 API 服务商（如 OpenAI、Anthropic 等）</li>
            </ul>
            <p><b>重要提示：</b></p>
            <ul>
              <li>请勿发送敏感或机密信息</li>
              <li>您应对发送的内容承担全部责任</li>
              <li>我们建议优先使用本地 Ollama 模式</li>
            </ul>
            <p>点击"同意"即表示您理解并接受上述条款。</p>
            </html>
            """;
        
        contentPane = new JEditorPane("text/html", privacyText);
        contentPane.setEditable(false);
        contentPane.setCaretPosition(0);
        
        panel.add(new JBScrollPane(contentPane), BorderLayout.CENTER);
        return panel;
    }
    
    @Override
    protected Action[] createActions() {
        return new Action[]{
            getOKAction(),   // "同意"按钮
            getCancelAction() // "取消"按钮
        };
    }
}
```

---

#### 3. 敏感信息加密存储

##### PasswordSafe 使用

```java
// 存储 API Key（加密）
PasswordSafe passwordSafe = PasswordSafe.getInstance();

passwordSafe.set(
    PasswordSafe.Feature.ALLOW_MEMORY_STORAGE,  // 允许内存缓存
    null,                                        // Project（null 表示 APP 级别）
    "WorkReportApiKey",                          // 密钥标识
    apiKey                                       // API Key（char[]）
);

// 读取 API Key（解密）
char[] apiKey = passwordSafe.getPassword(
    null,             // Project
    "WorkReportApiKey" // 密钥标识
);

// 使用完后立即清空
Arrays.fill(apiKey, ' '); // 安全清除敏感数据
```

**存储位置**:
- Windows: Credential Manager
- macOS: Keychain
- Linux: libsecret / KWallet

**安全性**:
- 操作系统级加密
- 与其他密码同等安全
- 不会被版本控制系统捕获

---

## 设计模式应用

### 1. 工厂方法模式

**位置**: `ReportToolWindowFactory`

```java
public class ReportToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, 
                                        @NotNull ToolWindow toolWindow) {
        // 工厂负责创建内容
        ReportToolWindowPanel panel = new ReportToolWindowPanel(project);
        toolWindow.getComponent().add(panel);
    }
}
```

**优点**:
- 解耦 ToolWindow 管理和内容创建
- 易于扩展新窗口（新建工厂类即可）

---

### 2. 单例模式

**位置**: `ReportSettingsState`

```java
@Service(Service.Level.APP)  // IDEA 平台注入单例
public class ReportSettingsState {
    
    public static ReportSettingsState getInstance() {
        return ApplicationManager.getApplication()
            .getService(ReportSettingsState.class);
    }
}
```

**为什么用单例？**
- 全局唯一配置状态
- 避免多次加载/保存
- 方便任何地方访问

---

### 3. 观察者模式

**位置**: 各种 Listener

```java
// ActionListener 观察按钮点击
polishButton.addActionListener(e -> onPolish());

// FocusListener 观察焦点变化
textField.addFocusListener(new FocusAdapter() {
    @Override
    public void focusGained(FocusEvent e) { ... }
});

// ItemListener 观察下拉框选择
reportTypeCombo.addItemListener(e -> {
    if (e.getStateChange() == ItemEvent.SELECTED) {
        onReportTypeChanged();
    }
});
```

**本质**: 事件源（按钮/文本框）→ 通知观察者（Listener）→ 执行回调

---

### 4. 策略模式

**位置**: 报告生成

```java
public String generateDraftReport(List<GitCommitEntry> entries, String reportType) {
    return switch (reportType) {
        case "daily" -> generateDailyReport(entries);      // 策略 A
        case "weekly" -> generateWeeklyReport(entries);    // 策略 B
        case "quarterly" -> generateQuarterlyReport(entries); // 策略 C
        default -> generateYearlyReport(entries);          // 策略 D
    };
}
```

**优点**:
- 每种报告类型独立实现
- 易于新增报告类型（加个 case 即可）
- 符合开闭原则

---

### 5. 模板方法模式

**位置**: Git 日志采集流程

```java
public void collectGitLog(Project project, LocalDate start, LocalDate end) {
    // 固定流程（模板）
    List<GitRepository> repos = getRepositories(project);  // 步骤 1
    
    for (GitRepository repo : repos) {
        GitLineHandler handler = buildCommand(repo, start, end); // 步骤 2
        GitCommandResult result = executeCommand(handler);       // 步骤 3
        parseOutput(result.getOutput());                         // 步骤 4
    }
    
    sortAndFormat();  // 步骤 5
}
```

**特点**: 流程固定，具体步骤可子类化（虽然这里没用到）

---

### 6. 构建器模式

**位置**: HTTP 请求构建

```java
Request request = new Request.Builder()
    .url(apiEndpoint)
    .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
    .addHeader("Content-Type", "application/json")
    .build();
```

**优点**:
- 链式调用，代码简洁
- 参数可选，灵活组合
- 不可变对象，线程安全

---

## 关键技术点详解

### 1. IntelliJ 平台 Service 机制

#### Service 级别

```java
// APP 级别：整个 IDE 一个实例
@Service(Service.Level.APP)
public class ReportSettingsState { ... }

// PROJECT 级别：每个 Project 一个实例
@Service(Service.Level.PROJECT)
public class ProjectSpecificService { ... }
```

**区别**:
- APP: 跟随 IDE 生命周期
- PROJECT: 跟随项目打开/关闭

---

#### 依赖注入

```java
// 方式 1: 直接获取实例
ReportSettingsState settings = ReportSettingsState.getInstance();

// 方式 2: 构造函数注入（推荐）
public class MyService {
    private final ReportSettingsState settings;
    
    public MyService(ReportSettingsState settings) {
        this.settings = settings;
    }
}

// IDEA 平台自动注入
@Service
public class MyService {
    @NotNull
    private final ReportSettingsState settings;
    
    public MyService() {
        this.settings = ReportSettingsState.getInstance();
    }
}
```

---

### 2. 虚拟文件系统（VirtualFile）

#### 获取 Git 仓库根目录

```java
GitRepository repo = ...;
VirtualFile root = repo.getRoot();

// 转换为 Java File
File ioFile = VfsUtilCore.virtualToIoFile(root);

// 获取路径字符串
String path = root.getPath();         // 绝对路径
String name = root.getName();         // 目录名
String nameWithoutExtension = root.getNameWithoutExtension();
```

**VirtualFile 优势**:
- 统一抽象（本地文件/归档文件/网络文件）
- 支持 VFS 缓存
- 跨平台兼容

---

### 3. Gson 序列化

#### 复杂对象转换

```java
// 定义类
class ChatRequest {
    String model;
    List<Message> messages;
    boolean stream;
}

class Message {
    String role;
    String content;
}

// 序列化
Gson gson = new Gson();
ChatRequest request = new ChatRequest();
request.model = "qwen2.5:7b";
request.messages = Arrays.asList(
    new Message("system", "You are a helper"),
    new Message("user", "Hello")
);
request.stream = true;

String json = gson.toJson(request);
/*
{
  "model": "qwen2.5:7b",
  "messages": [
    {"role": "system", "content": "You are a helper"},
    {"role": "user", "content": "Hello"}
  ],
  "stream": true
}
*/

// 反序列化
ChatRequest parsed = gson.fromJson(json, ChatRequest.class);
```

---

### 4. Java 时间 API

#### LocalDate 运算

```java
LocalDate today = LocalDate.now();

// 加减天数
LocalDate tomorrow = today.plusDays(1);
LocalDate yesterday = today.minusDays(1);

// 调整到特定星期
LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
LocalDate nextFriday = today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY));

// 季度计算
int month = today.getMonthValue(); // 3
int quarter = (month - 1) / 3 + 1; // 1
LocalDate quarterStart = LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1);
LocalDate quarterEnd = quarterStart.plusMonths(3).minusDays(1);

// 格式化
DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
String formatted = today.format(formatter); // "2026-03-31"

// 解析
LocalDate date = LocalDate.parse("2026-03-31", formatter);
```

---

### 5. Stream API 高级用法

#### 分组 + 排序 + 收集

```java
List<GitCommitEntry> entries = ...;

// 按日期分组 → 按日期排序 → 每组转为 List
Map<LocalDate, List<GitCommitEntry>> grouped = entries.stream()
    .collect(Collectors.groupingBy(
        GitCommitEntry::date,          // 分组键
        TreeMap::new,                  // 供应者（自动排序）
        Collectors.toList()            // 下游收集器
    ));

// 扁平化处理
List<String> subjects = entries.stream()
    .map(GitCommitEntry::subject)      // 提取 subject
    .filter(s -> s.contains("fix"))    // 过滤
    .distinct()                        // 去重
    .sorted()                          // 排序
    .collect(Collectors.toList());
```

---

## 总结

### 技术栈总览

| 类别 | 技术 | 用途 |
|------|------|------|
| **UI 框架** | Swing + IntelliJ UI | 工具窗口、对话框、表单 |
| **平台 API** | IntelliJ Platform | Service、VirtualFile、Notification |
| **版本控制** | Git4Idea | Git 命令执行、仓库管理 |
| **HTTP 通信** | OkHttp3 | API 调用、SSE 流式处理 |
| **JSON** | Gson | 序列化/反序列化 |
| **并发** | Java Concurrency | EDT 线程模型、线程池 |
| **时间处理** | java.time | 日期计算、格式化 |
| **函数式** | Stream API | 数据聚合、转换 |
| **持久化** | PersistentStateComponent | 配置存储 |
| **安全** | PasswordSafe | 敏感信息加密 |

---

### 架构优势

1. **分层清晰**: UI → Service → Model → Infrastructure
2. **职责单一**: 每个类只做一件事
3. **易于测试**: 业务逻辑与 UI 分离
4. **可扩展**: 新增报告类型只需添加策略
5. **主题适配**: 使用 JB 组件自动适配暗黑模式
6. **线程安全**: 严格遵循 EDT 规则

---

### 学习路线建议

**第 1 周**: Swing 基础 + IntelliJ 平台入门
- 布局管理器
- 事件监听
- ToolWindow 框架

**第 2 周**: Git4Idea API + OkHttp3
- Git 命令执行
- HTTP 请求
- JSON 处理

**第 3 周**: 高级特性
- SSE 流式响应
- 对话历史管理
- 持久化机制

**第 4 周**: 架构优化
- 设计模式应用
- 代码重构
- 性能优化

---

**文档版本**: v1.0  
**最后更新**: 2026-03-31  
**维护者**: Work Report Team
