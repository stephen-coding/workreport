# IntelliJ IDEA 版本兼容性指南

## 📊 当前兼容性配置

### 支持的版本范围

```xml
<idea-version since-build="252" until-build="262.*"/>
```

**兼容版本**:
- ✅ **IntelliJ IDEA 2025.2.x** (build 252.*) — 最低支持版本
- ✅ **IntelliJ IDEA 2025.3.x** (build 253.*)
- ✅ **IntelliJ IDEA 2026.1.x** (build 261.*)
- ✅ **IntelliJ IDEA 2026.2.x** (build 262.*)

> **为什么从 252 开始**：编辑器 ghost text 依赖平台的 inline completion API
> （`com.intellij.inline.completion.provider` 扩展点 + `ide-codeinsight-inline` 产物），
> 该 API 的稳定形态从 2025.2（252）开始提供；更早的 233–251 无法编译通过。
> 其余功能本身并不需要 252，仅 ghost text 扩展点拉高了门槛。
> Kotlin `AgentInlineCompletionProvider` 由 Maven `kotlin-maven-plugin` 在 `process-sources` 阶段编译，
> `kotlin-stdlib` / `kotlinx-coroutines` 为 `provided`（IDE 运行时自带）。

---

## 🔍 IntelliJ IDEA 版本号规则

### Build Number 格式

IntelliJ IDEA 使用以下版本号格式：`主版本。次版本。修订号`

例如：`IC-2024.1.5` = `build 241.17890.5`

| 字段 | 说明 | 示例 |
|------|------|------|
| **主版本** | 年份后两位 + 发布序号 | `241` = 2024 年第 1 个主要版本 |
| **次版本** | 小版本号 | `17890` |
| **修订号** | Bug 修复版本 | `5` |

### 常见版本的 Build Number

| IDEA 版本 | Build Number | 发布时间 |
|----------|-------------|---------|
| 2023.3.x | 233.* | 2023 Q4 |
| 2024.1.x | 241.* | 2024 Q2 |
| 2024.2.x | 242.* | 2024 Q3 |
| 2024.3.x | 243.* | 2024 Q4 |
| 2025.1.x | 251.* | 2025 Q2 |
| 2025.2.x | 252.* | 2025 Q3 |
| 2025.3.x | 253.* | 2025 Q4 |
| 2026.1.x | 261.* | 2026 Q1 |
| 2026.2.x | 262.* | 2026 Q2 |

---

## ⚙️ 如何修改兼容版本

### 场景 1: 向下降低最低版本

编辑 `src/main/resources/META-INF/plugin.xml`:

```xml
<!-- 例如回退到 2025.1.x（必须先移除 ghost text 扩展点） -->
<idea-version since-build="251" until-build="262.*"/>
```

**注意**: 需要确保代码没有使用新版本 API，否则在老版本 IDE 上会报错；
且必须同时删掉 `plugin.xml` 里的 `<inline.completion.provider>` 与 Kotlin provider、
以及 `pom.xml` 的 `ide-codeinsight-inline` 依赖，否则无法编译。

### 场景 2: 只支持最新版本

```xml
<!-- 仅支持最新稳定版 -->
<idea-version since-build="262" until-build="*"/>
```

### 场景 3: 移除版本限制（不推荐）

```xml
<!-- 不指定版本范围（可能在不兼容的版本上运行） -->
<!-- <idea-version/> -->
```

---

## 🧪 测试多版本兼容性

### 方法 1: 安装多个版本的 IDEA

在同一台机器上安装多个版本的 IDEA 进行测试：

```
D:/IDEA/
├── IntelliJ IDEA 2023.3/
├── IntelliJ IDEA 2024.1/
└── IntelliJ IDEA 2025.3/
```

### 方法 2: 使用 Maven 多环境构建

为不同版本分别编译测试：

```xml
<!-- pom.xml 中添加 profiles -->
<profiles>
    <profile>
        <id>idea-2023.3</id>
        <properties>
            <idea.home>D:/IDEA/IntelliJ IDEA 2023.3</idea.home>
        </properties>
    </profile>
    <profile>
        <id>idea-2024.1</id>
        <properties>
            <idea.home>D:/IDEA/IntelliJ IDEA 2024.1</idea.home>
        </properties>
    </profile>
</profiles>
```

使用方式：

```bash
# 为特定版本编译
mvn clean package -P idea-2023.3
```

---

## ⚠️ 跨版本兼容性注意事项

### 可能导致不兼容的变更

#### 1. **API 废弃或移除**

IntelliJ 平台会在某些版本中废弃旧 API：

```java
// ❌ 已废弃的 API（2024.2 移除）
ApplicationManager.getApplication().runWriteAction();

// ✅ 新的 API
WriteAction.run(() -> { /* ... */ });
```

#### 2. **UI 组件变化**

```java
// ❌ 旧版本 UI 组件
new com.intellij.ui.components.JBLabel("text");

// ✅ 推荐使用新组件
JBLabel label = new JBLabel("text");
label.setFont(JBFont.h1());
```

#### 3. **配置文件格式变化**

```xml
<!-- 2024.1 之前的 plugin.xml 格式 -->
<actions>
    <action class="com.example.MyAction"/>
</actions>

<!-- 2024.1+ 的新格式 -->
<actions>
    <action id="MyActionId">
        <class name="com.example.MyAction"/>
    </action>
</actions>
```

---

## 🛡️ 确保向后兼容的最佳实践

### 1. 使用稳定的 API

优先使用 `com.intellij.openapi` 包下的类，这些 API 通常更稳定：

```java
// ✅ 稳定的核心 API
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.application.ApplicationManager;

// ⚠️ 谨慎使用内部 API
import com.intellij.idea.IdeaLogger; // 可能变化
```

### 2. 避免使用 @ApiStatus.Internal 注解的类

```java
// 检查 API 状态
@ApiStatus.Internal  // ❌ 不要使用
@ApiStatus.Experimental // ⚠️ 谨慎使用
@ApiStatus.ScheduledForRemoval // ❌ 即将移除
```

### 3. 运行时版本检查

如果必须使用新版本 API，可以添加运行时检查：

```java
import com.intellij.openapi.application.ApplicationInfo;

public boolean isMinimumVersion(int buildNumber) {
    ApplicationInfo info = ApplicationInfo.getInstance();
    int currentBuild = info.getBuild().getBaselineVersion();
    return currentBuild >= buildNumber;
}

// 使用示例
if (isMinimumVersion(241)) {
    // 使用 2024.1+ 的新 API
    newFeature();
} else {
    // 降级处理
    legacyFeature();
}
```

### 4. 使用反射调用新 API（高级技巧）

```java
try {
    // 尝试调用新版本的 API
    Class<?> clazz = Class.forName("com.intellij.newapi.NewFeature");
    Object instance = clazz.getDeclaredConstructor().newInstance();
    clazz.getMethod("doSomething").invoke(instance);
} catch (ClassNotFoundException e) {
    // 新版本 API 不存在，使用旧方案
    useLegacyApproach();
}
```

---

## 📝 实际案例：适配不同版本

### 案例 1: Git 日志采集的兼容性

```java
// ✅ 兼容所有版本的写法
import git4idea.repo.GitRepository;
import git4idea.history.GitHistoryUtils;

public List<GitCommitEntry> loadCommits(Project project, Date since) {
    // 这个方法在所有版本中都可用
    return GitHistoryUtils.loadDetails(project, since, null);
}
```

### 案例 2: 工具窗口的兼容性

```java
// ✅ 使用标准 API 创建工具窗口
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

public void createToolWindow(Project project) {
    ToolWindowManager manager = ToolWindowManager.getInstance(project);
    ToolWindow toolWindow = manager.getToolWindow("WorkReport");
    
    // 这个 API 在所有版本中都稳定
    toolWindow.getContentManager().addContent(content);
}
```

---

## 🔧 动态调整兼容版本

### 根据用户反馈调整

如果收到用户报告说在某些版本上无法安装：

1. **收集信息**:
   - 用户的 IDEA 版本号
   - 错误提示信息
   - 操作系统信息

2. **分析原因**:
   - 是否使用了该版本不支持的 API
   - 是否有依赖冲突

3. **解决方案**:
   - 如果可以快速修复：降低 `since-build` 并修复兼容性问题
   - 如果需要大改动：保持当前配置，发布专门的兼容版本

### 示例：分版本发布

```xml
<!-- 老版本 LTS（移除 ghost text 扩展点与 Kotlin provider） -->
<idea-version since-build="251" until-build="251.*"/>
<!-- 插件 ID: workreport-ai-assistant-lts -->

<!-- 标准版本（含编辑器 ghost text 与语义索引） -->
<idea-version since-build="252" until-build="262.*"/>
<!-- 插件 ID: workreport-ai-assistant -->
```

---

## 📊 版本分布统计

2.2.0 起不再按版本占比做“向下兼容”：ghost text 依赖 252+ 才有的 inline completion API，
因此直接以官方当前支持的 IDE 版本为基线（2025.2 及以后）。
如果日后需要覆盖更老版本，建议采用上面的“LTS + 标准版”双包策略，而不是降低主包 `since-build`。

---

## 🎯 当前项目的兼容性策略

### 已实施

✅ **收紧后的兼容策略**: 支持 2025.2.x - 2026.2.x（`since-build=252`）

### 待验证

⏳ **需要在多个版本上测试**:
- 在 2025.2.x 上验证 ghost text（inline completion API 最早可用版本）
- 在 2026.1.x 上验证聊天、工具、语义索引全链路
- 在 2026.2.x 上验证 diff 确认 / 快照回退等编辑功能

### 下一步计划

1. **下载多个版本的 IDEA**进行安装测试
2. **收集早期采用者反馈**
3. **根据反馈调整兼容版本范围**

---

## 📋 总结

### 关键要点

1. ✅ **当前配置覆盖 2025.2 及以后的所有稳定版**
2. ⚠️ **需要在实际环境中测试各版本兼容性**
3. 🔄 **根据用户反馈动态调整版本策略**
4. 📊 **优先保证核心功能的稳定性**

### 建议行动

1. **短期**: 在当前配置下发布，观察用户反馈
2. **中期**: 在 2-3 个主流版本上充分测试
3. **长期**: 建立自动化测试矩阵，覆盖所有支持的版本

---

## 🔗 相关资源

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [IntelliJ IDEA Release History](https://www.jetbrains.com/idea/download/other.html)
- [API Compatibility Guide](https://plugins.jetbrains.com/docs/intellij/api-compatibility.html)
