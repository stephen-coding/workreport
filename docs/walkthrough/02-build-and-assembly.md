# 第 02 卷 · 构建与装配

> 本卷回答：**这 3 万行代码是怎么变成一个能装进 IDE 的 zip 的？装进去之后，IDE 又是按哪几行 XML 把功能接起来的？**
>
> 覆盖：`pom.xml`、`src/main/resources/META-INF/plugin.xml`、`assembly/plugin-assembly.xml`、`core/pyproject.toml`、`core/packaging/agent-core.spec`、`scripts/{build_core,build_native,build_plugin,verify_all}.ps1`、`vscode/package.json`、`vscode/tsconfig.json`。

---

## 0. 一次构建之后，磁盘上多了什么

```powershell
powershell -File scripts/build_plugin.ps1
```

按顺序产生四类产物：

| 阶段 | 产物 | 谁产生 |
|---|---|---|
| Rust 加速（可选） | `core/src/agent_core_native.pyd`（Windows）/ `.so` | `build_native.ps1`（cargo） |
| Core 可执行文件 | `core/dist/agent-core.exe` | `build_core.ps1`（PyInstaller） |
| 插件 JAR | `target/workreport-intellij-plugin-3.1.2.jar` | `mvn package`（含 Kotlin 编译） |
| 可安装 zip | `target/workreport-ai-assistant-3.1.2.zip` | `maven-assembly-plugin` |

zip 解压后的结构（`includeBaseDirectory=false`，所以根目录就是插件目录）：

```
workreport-ai-assistant-3.1.2.zip
└── com.workreport.ai-assistant/          ← 目录名必须等于 plugin.xml 的 <id>
    ├── lib/
    │   ├── workreport-intellij-plugin-3.1.2.jar   ← 类 + META-INF/plugin.xml + icons/ + messages/
    │   └── okhttp-4.12.0.jar / okio-*.jar / kotlin-stdlib-*.jar
    └── agent-core/
        └── agent-core.exe              ← 内置 Core，用户无需装 Python
```

安装到 IDE 后，这个目录位于 `<IDE 配置目录>/plugins/workreport-ai-assistant/lib/...`。`PythonLocator.bundledCoreExe()` 用插件描述符的 `getPluginPath()` 定位到它，再拼 `agent-core/agent-core[.exe]` —— **这条路径就是 4.2 节 fileSet 的 outputDirectory**，两处必须一致。

---

## 1. 版本号：一条主链 + 两个手工同步点

版本号有 5 个地方出现，但只有 **1 个源头**：

```
pom.xml <version>3.1.2</version>
   ├─(手工同步)→ src/main/resources/META-INF/plugin.xml  <version>3.1.2</version>
   ├─(手工同步)→ pom.xml 里的 assembly finalName / JAR 名（自动取 ${project.version}）
   └─(build_core.ps1 读取)→ 环境变量 AGENT_CORE_VERSION
                              → agent-core.spec 写入 core/build/core-build-info.json
                              → 打进 exe 根目录
                              → agent_core/__init__._packaged_version() 读取 sys._MEIPASS/core-build-info.json
                              → __version__ → protocol.CORE_VERSION → core/hello / CLI --version / MCP clientInfo
```

`core/src/agent_core/__init__.py:25-47` 的 `_resolve_version()` 是唯一解析点，优先级：

1. `AGENT_CORE_VERSION` 环境变量（发布构建注入）；
2. 打包进 exe 的 `core-build-info.json`；
3. `importlib.metadata.version("agent-core")`（`pip install -e` 场景）；
4. `"0.0.0-dev"`（源码直跑）。

⚠️ **两个不同步的版本号（不是 bug，但要知道）**：

| 文件 | 版本 | 为什么不同源 |
|---|---|---|
| `core/pyproject.toml` | `0.1.0` | 只是 Python 包元数据；真正对外报的版本走 `_resolve_version()` |
| `vscode/package.json` | `0.1.0` | VS Code 扩展市场独立编号，与插件版本无强绑定 |

📌 为什么不给 PyPI 包也同步版本？——Core 不通过 PyPI 分发，`pyproject.toml` 只在 `pip install -e core` 做本地开发时用；让它跟着插件版本跑会引入「每次发版都要改 5 个文件」的负担。

---

## 2. `pom.xml` 逐段

### 2.1 坐标与打包方式（7-14）

```xml
<groupId>com.workreport</groupId>
<artifactId>workreport-intellij-plugin</artifactId>
<version>3.1.2</version>
<packaging>jar</packaging>
```

`packaging=jar`：插件主产物就是一个普通 JAR，**不是** `intellij-plugin` 打包器产物——因为分发走的是自己的 `assembly`（要额外塞 Core 可执行文件），而不是 Gradle IntelliJ Plugin 那套。

### 2.2 properties（16-36）

| 属性 | 值 | 作用 |
|---|---|---|
| `maven.compiler.source/target` | `21` | **字节码 65**。平台 2026.3 要求运行时至少 JVM 21，产物仍能在 2025.2 的 JBR 21 上加载 |
| `project.build.sourceEncoding` | `UTF-8` | 源码与资源编码 |
| `intellij.version` | `263.7307.259` | 编译期平台基线与依赖版本 |
| `okhttp.version` | `4.12.0` | 旧日报功能的 HTTP 客户端 |
| `kotlin.version` | `2.4.21-RC` | **必须与目标 IDE 内置 Kotlin 一致**，否则运行时 `NoSuchMethodError` |

⚠️ 最关键的一行是注释里的因果：**编译要用 JDK 25，产出 Java 21 字节码**。原因：
- 平台 2026.3 的 class 文件是 **69.0（Java 25）**，用 JDK 21 的 `javac` 读都读不了，直接报 `wrong class file version 69.0`；
- 但插件自己的字节码要兼容 2025.2（JBR 21），所以 `target=21`。

这就是 `build_plugin.ps1` 和 `verify_all.ps1` 都要先跑一次 `javac -version` 预检的原因。

### 2.3 repositories（40-63）

两个 JetBrains 仓库：

- `intellij-releases`：`https://www.jetbrains.com/intellij-repository/releases` —— 平台本体（core / core-ui / ide-impl / vcs / git...）。
- `intellij-dependencies`：`https://cache-redirector.jetbrains.com/intellij-dependencies` —— 平台依赖的第三方库（annotations、gson、coroutines 等）。

都关闭了 snapshots：避免某次构建突然拉到 EAP 快照导致行为漂移。

### 2.4 dependencies（65-217）

**全部 `provided` 是刻意的**：这些类由 IDE 运行时提供，打进插件会导致 `LinkageError`（同一个类被两个 classloader 加载）。真正随包分发的只有 `okhttp`（`compile`）。

| 依赖 | scope | 用来干什么 | 备注 |
|---|---|---|---|
| `com.jetbrains.intellij.platform:core` | provided | `Project`、`Service`、`ApplicationManager`、`VirtualFile` | |
| `core-ui` | provided | 工具窗口、设置页、通知、文件选择器 | |
| `ide-impl` | provided | `Configurable`、`PasswordSafe`、`Messages` | |
| `vcs-core` / `vcs` / `vcs-dvcs` / `vcs-dvcs-impl` | provided | Git 相关接口与 `AbstractRepositoryManager` | 旧日报的 `GitLogCollectorService` 用 |
| `com.jetbrains.intellij.vcs:git` | provided | `GitLineHandler`、`GitRepositoryManager` | |
| `git-backend` | provided | **263 起 Git 插件拆分后的实现类** | 不加会 `NoClassDefFoundError` |
| `git-shared` | provided | 前端/后端共享模型 | 同上 |
| `ide-codeinsight-inline` | provided | `DebouncedInlineCompletionProvider` 等 | ghost text 专用 |
| `org.jetbrains:annotations` | provided | `@NotNull` / `@Nullable` | 版本 26.1.0 |
| `com.google.code.gson:gson` | provided | 全项目的 JSON | IDE 自带 2.10.1 |
| `kotlin-stdlib` | provided | Kotlin 类（`AgentInlineCompletionProvider`） | 版本随平台 |
| `kotlinx-coroutines-core-jvm` | provided | `withContext` / `flowOf` | `1.11.0-intellij-1`，是 **IntelliJ 定制版**，不能换成 Maven Central 的 1.8.x |
| `okhttp` | compile | `ReportPolishService` 调 LLM HTTP | 唯一打包进来的第三方库 |
| `junit-jupiter` | test | Markdown 解析器 + 图标资源测试 | 纯 JVM 测试，不起 IDE |

📌 为什么 Git 要 3 个坐标？——2026.3 把 Git 插件拆成 frontend / shared / backend 三个模块，`GitLineHandler` 在 backend、模型类在 shared、扩展点在 frontend。只写 `<depends>Git4Idea</depends>` 只能保证**运行时**有插件，**编译期**仍需显式三个 artifact。

### 2.5 build/plugins（219-309）

| 插件 | 版本 | 关键配置 | 说明 |
|---|---|---|---|
| `maven-compiler-plugin` | 3.13.0 | `source/target=21`、`encoding=UTF-8` | 编译 Java |
| `kotlin-maven-plugin` | `${kotlin.version}` | 执行阶段 `process-sources`；`jvmTarget=21`；`sourceDirs` 同时包含 `src/main/kotlin` 与 `src/main/java` | **Kotlin 必须先于 javac**，否则 Java 引用 Kotlin 类找不到 |
| `maven-jar-plugin` | 3.4.2 | 默认 | 产出插件 JAR |
| `maven-surefire-plugin` | 3.2.5 | `useModulePath=false` | 关掉 JPMS，让 JUnit 5 直接跑（否则 `module-info` 缺失时报错） |
| `maven-assembly-plugin` | 3.7.1 | 描述符 `assembly/plugin-assembly.xml`、`appendAssemblyId=false`、`finalName=workreport-ai-assistant-${project.version}` | `appendAssemblyId=false` 保证 zip 名**没有** `-plugin` 后缀 |

⚠️ `kotlin-maven-plugin` 的 `sourceDirs` 里放 `src/main/java` 很反直觉：这是为了让 Kotlin 编译器能把 Java 源码作为「可见依赖」参与解析（Kotlin 类 `AgentInlineCompletionProvider` 引用 Java 的 `InlineCompletionClient` 与 `ReportSettingsState`）。javac 随后仍会再编译一遍 Java（Kotlin 只做分析，不产出 Java 的 class）。

### 2.6 resources（304-308）

```xml
<resource><directory>src/main/resources</directory></resource>
```

`META-INF/plugin.xml`、`icons/*.svg`、`messages/AgentBundle*.properties` 全靠这一行进 JAR。**删掉它，插件装上去会提示「没有 plugin.xml」**。

---

## 3. `plugin.xml` 逐节点

### 3.1 头部（1-18）

| 节点 | 值 | 注意 |
|---|---|---|
| `<id>` | `com.workreport.ai-assistant` | 🔁 与 `PythonLocator.PLUGIN_ID` 常量完全一致；与 assembly 的 `outputDirectory` 一致 |
| `<name>` | `Agent Coding Assistant` | 市场展示名 |
| `<version>` | `3.1.2` | 手工同步 pom |
| `<vendor>` | `WeeklyReport` | |
| `<description>` | CDATA，中英双语 | CDATA 里可以写 `<br/>` |
| `<idea-version since-build="252" until-build="263.*"/>` | 252 = 2025.2 起 | ⚠️ **252 是下限的依据**：`inline.completion.provider` 扩展点从 2025.2 才有；`until-build="263.*"` 是上限，IDE 升到 264 会直接拒绝加载，直到你改这一行 |

### 3.2 依赖与资源包（20-31）

```xml
<depends>com.intellij.modules.platform</depends>
<depends>Git4Idea</depends>
<resource-bundle>messages.AgentBundle</resource-bundle>
```

- `Git4Idea` 是**插件依赖**：用户装了 IDEA Community/Ultimate 都自带；但如果是 Rider/WebStorm 之类，需要有 Git 插件才能加载本插件。
- 🔁 `resource-bundle` 的值必须等于 `AgentBundle.java` 里 `DynamicBundle` 的 bundle 名（`messages.AgentBundle`），也等于 `src/main/resources/messages/AgentBundle.properties` 的路径（去掉 `.properties`）。

### 3.3 extensions（95-122）

| 扩展点 | 关键属性 | 运行时效果 |
|---|---|---|
| `toolWindow` | `id="AgentCoder"`、`anchor="bottom"`、`icon="/icons/agent.svg"`、`factoryClass=AgentToolWindowFactory` | 底部工具窗口。🔁 这个 `id` 与 `AgentChatPanel.find()` 里 `getToolWindow("AgentCoder")` 的字符串必须一致 |
| `applicationConfigurable` | `instance=ReportSettingsConfigurable`、`id="com.workreport.settings"`、`parentId="tools"` | Settings → Tools 下的设置页。🔁 `id` 与 `AgentChatPanel.openSettings()` 的 `showSettingsDialog(project, "com.workreport.settings")` 一致 |
| `inline.completion.provider` | `id="com.workreport.agent.inline"`、`implementation=...AgentInlineCompletionProvider`、`order="first"` | ghost text。实现类是 **Kotlin**：`src/main/kotlin/.../AgentInlineCompletionProvider.kt` |
| `applicationService` | `serviceImplementation=ReportSettingsState` | 全局配置单例（`ReportSettingsState.getInstance()` 靠它） |
| （无 XML） | `@Service(Service.Level.PROJECT) AgentCoreService` | **项目级**服务不需要在 plugin.xml 注册，注解 + `project.getService()` 即可 |

📌 `instance=` vs `implementation=`：设置页用 `instance`（Platform 自己 new 并注入 `Configurable`），其它扩展点用 `implementation`（IDE 反射构造）。写错属性的表现是「设置页打不开 / 扩展未注册」，但构建不报错。

### 3.4 actions（126-142）

| action id | 类 | 文案 | 位置 |
|---|---|---|---|
| `AgentCoder.OpenSettings` | `OpenSettingsAction` | `%action.settings.text` | `ToolsMenu` 最前 |
| `AgentCoder.GenerateReport` | `GenerateReportAction` | `%action.report.text` | `ToolsMenu` 中 `OpenSettings` 之后 |

`%key` 由 `AgentBundle` 解析（中英两份 properties）。`GenerateReportAction` 是**旧功能入口**：它不自己跑 Git，而是把「生成今天的日报」塞进 Agent 面板发送（第 10 卷）。

### 3.5 change-notes（33-93）

HTML 片段，IDE 的插件详情页直接渲染。中英各一段，历史版本从 3.0.0 到 3.1.2。📌 这一节是**唯一给用户看的发布说明**，`CHANGELOG.md` 是给仓库看的，两者不自动同步。

---

## 4. `assembly/plugin-assembly.xml` 逐段

### 4.1 骨架（1-9）

```xml
<format>zip</format>
<includeBaseDirectory>false</includeBaseDirectory>
```

`false` 意味着 zip **没有**外层包装目录，解压即得 `com.workreport.ai-assistant/`。如果改成 `true`，IDE 会装不上（找不到 plugin.xml）。

### 4.2 fileSets（13-32）

| fileSet | 源 | 目标 | 筛选 |
|---|---|---|---|
| 主 JAR | `${project.build.directory}`（`target/`） | `com.workreport.ai-assistant/lib` | 只含 `${artifactId}-${version}.jar` |
| Core 可执行文件 | `${project.basedir}/core/dist` | `com.workreport.ai-assistant/agent-core` | 只含 `agent-core` 与 `agent-core.exe` |

⚠️ **`core/dist` 不存在时 assembly 不报错**，只会产出一个「没有 Core」的 zip，装到用户机器上表现为「找不到 Agent Core，请填写可执行文件路径」。所以 `build_plugin.ps1` 在 `mvn package` 之前显式检查（见 5.3）。

### 4.3 dependencySets（36-49）

```xml
<scope>runtime</scope>
<useProjectArtifact>false</useProjectArtifact>
<excludes>
  com.jetbrains.intellij.platform:*
  com.jetbrains.intellij.vcs:*
  org.jetbrains:annotations
</excludes>
```

- `runtime` 只捞非 provided 的依赖 → 实际只有 okhttp 及其传递依赖（okio、kotlin-stdlib-jdk8 等）。
- `useProjectArtifact=false`：主 JAR 已由第一个 fileSet 处理，这里不要重复。
- 三个 exclude 是**保险丝**：万一将来有人把某个平台依赖写成 `compile`，也不会把平台 JAR 打进插件。

---

## 5. 四个脚本

### 5.1 `build_core.ps1`（37 行）

| 行 | 动作 | 为什么 |
|---|---|---|
| 17-20 | `[System.IO.File]::ReadAllText($pomPath, UTF8)` 解析 pom | **PowerShell 5.1 默认按 ANSI 读无 BOM 文件**，中文注释会把版本号解析成乱码；显式 UTF-8 才安全 |
| 19 | `$env:AGENT_CORE_VERSION = $pom.project.version` | 把版本注入子进程，供 spec 写 build-info |
| 24 | `pip install --upgrade "pyinstaller>=6.3"` | 6.3 起才稳定支持 `--noconfirm` 与 `_MEIPASS` 行为 |
| 27 | `PyInstaller --clean --noconfirm packaging/agent-core.spec` | `--clean` 清缓存，避免旧 PYZ 残留 |
| 30-32 | 拼 `core/dist/agent-core[.exe]` 并打印 | 产物路径与 assembly 的 fileSet 对应 |

### 5.2 `agent-core.spec`（76 行）

PyInstaller 的「构建脚本」，逐段看：

| 段 | 行 | 内容 | 说明 |
|---|---|---|---|
| 路径 | 15-16 | `PROJECT_ROOT = SPECPATH/..`（即 `core/`）、`SRC = core/src` | 用 `SPECPATH` 而不是 cwd，保证从任何目录调用都对 |
| 版本戳 | 21-24 | 写 `core/build/core-build-info.json` | 值为 `AGENT_CORE_VERSION`；空字符串也照样写 |
| datas | 27-35 | `skills/builtin`、`agent/subagents_builtin`、`agent/teams_builtin`、build-info | **Markdown 是运行时从磁盘读的**，必须作为 data 打进去；团队名册的 `TEAM.md` 同理 |
| hiddenimports | 37-41 | `collect_submodules("agent_core")` + pydantic 全部子模块 + `httpx/h11/httpcore/sqlite3` | PyInstaller 静态分析看不到动态 import（providers 按名字加载），所以全量收集 |
| binaries | 43-48 | 存在则打 `agent_core_native.pyd` / `.so` | 原生扩展可选 |
| Analysis | 50-60 | 入口 `agent_core/__main__.py`；`excludes=["tkinter","pytest","setuptools"]` | 排除 tkinter 省 ~10 MB |
| EXE | 64-76 | `name="agent-core"`、`console=True`、`upx=False` | `console=True` **必须**：它继承 stdin/stdout 做 RPC；`upx=False` 因为某些杀软会误报 UPX 壳 |

⚠️ 单文件 exe 的启动成本：每次启动都要把内部文件解压到临时目录（`sys._MEIPASS`），首次约 0.5~2 秒。这也是 `AgentCoreService` 懒启动的原因之一。

### 5.3 `build_plugin.ps1`（82 行）

| 行 | 逻辑 | 边界 |
|---|---|---|
| 8-12 | 参数 `-SkipNative` / `-SkipCore` / `-Python` | 允许只重打 zip |
| 18-22 | 找 cargo：PATH → `%USERPROFILE%\.cargo\bin\cargo.exe` | 找不到**不失败**，只打印「跳过 Rust，纯 Python 回退仍可用」 |
| 24-33 | 跑 `build_native.ps1` | 失败即 `throw` |
| 35-39 | 跑 `build_core.ps1 -Python $Python` | 失败即 `throw` |
| 41-46 | 检查 `core/dist/agent-core*` | **显式拦截「zip 里没有 Core」** |
| 49-65 | JDK 预检：`JAVA_HOME/bin/javac.exe` 优先，否则 PATH 的 `javac`；解析 `javac (\d+)` 并要求 ≥ 25 | 用 `$Matches[1]`，注意 `-version` 输出到 stderr，所以 `2>&1` |
| 66-67 | `mvn -q -DskipTests package` | 打包不跑测试（测试由 `verify_all` 负责） |
| 69-78 | 取 `target/*.zip` 中最新一个并打印大小/安装路径 | `Sort-Object LastWriteTime -Descending` |

📌 为什么 `-SkipTests`？——`mvn package` 里的 surefire 会跑 JUnit，但 `verify_all.ps1` 已经单独跑过一遍；打包时跳过能把反馈时间从 ~40 秒压到 ~15 秒。

### 5.4 `build_native.ps1`（137 行）——本项目最容易踩坑的脚本

| 段 | 行 | 逻辑 |
|---|---|---|
| `Resolve-Cargo` | 25-31 | PATH → `~/.cargo/bin/cargo.exe` → 抛错 |
| `Resolve-Rustup` | 33-39 | 同上，找不到返回 `$null`（允许没有 rustup 但已有工具链） |
| `Resolve-MinGW` | 41-51 | PATH 的 `gcc` → 在 WinGet 包目录里递归找 `gcc.exe` → 返回 `$null` |
| Windows 分支 | 61-70 | 必须有 gcc；把 gcc 与 cargo 目录**前置**到 PATH；默认工具链 `stable-x86_64-pc-windows-gnu`；`--no-default-features` |
| 非 Windows 分支 | 71-74 | 默认工具链 `stable`；features 前置 `abi3` |
| 工具链安装 | 76-87 | `rustup toolchain list` 里没有就 `rustup toolchain install` |
| 临时环境变量 | 93-107 | 记下旧值 → 设置 → `try/finally` 恢复，避免污染当前 shell |
| cargo 构建 | 99-101 | `cargo +<toolchain> build --release --features tokenizer,codesoup` |
| 产物定位 | 109-113 | 在 `release/` 下找 `agent_core_native.dll` / `libagent_core_native.so` / `.dylib`，取最新 |
| 改名暂存 | 115-128 | `.dll → .pyd`，其余 `.so`；目标 `core/src/agent_core_native.*`；目标比源新则跳过（`-Force` 强制） |
| 自检 | 130-131 | 用目标 Python 导入 `native_info()`，`backend == "rust"` 才算成功 |

⚠️ **为什么 Windows 不走 abi3？** 脚本头注释写得很清楚：PyO3 的 abi3 在 `windows-gnu` 上只链接 `python3.dll`，而该转发层**不导出 `PyCMethod_New`**，链接期直接失败。所以 Windows 按当前解释器版本链接，扩展必须与构建它时的 Python 版本一致；Linux/macOS 才启用 abi3（跨 Python 版本可用）。

⚠️ 第二条：`native_info()` 自检用的是 `$Python` 参数指定的解释器。**构建用的解释器和自检用的解释器必须是同一个版本**，否则复制过去的 `.pyd` 导入失败，脚本会报「extension built but not loaded: check that the Python version matches the toolchain」。

### 5.5 `verify_all.ps1`（101 行）

| 段 | 行 | 逻辑 |
|---|---|---|
| `Invoke-Step` | 16-28 | 用 `$LASTEXITCODE` 判断：非 0 记进 `$failures` 并打印红色 FAILED，**继续跑后续步骤** |
| Core 段 | 30-49 | `-Native` 时先构建原生扩展并设 `AGENT_CORE_EXPECT_RUST=1`（让 `test_native.py` 断言必须走 Rust，不许静默回退）；然后 1 个单测 + 9 个冒烟 |
| VS Code 段 | 52-64 | 没有 `node_modules` 才 `npm install`；`npm run compile`；`npm run smoke` |
| IntelliJ 段 | 66-92 | JDK < 25 时打印黄色 SKIPPED **并记入 failures**（不是静默跳过） |
| 汇总 | 94-101 | 有失败 → exit 1；否则 `VERIFY ALL PASS` |

9 个冒烟的顺序：`tools → memory → mcp → subagent → command → todos → v3 → ask → team`。它们都**不需要真实 LLM**（用 fake provider / fake MCP server），所以能在 CI 上稳定跑。

📌 `$ErrorActionPreference = "Continue"`（12 行）是刻意的：脚本要跑完全部步骤再汇总，而不是第一个失败就中断。

---

## 6. `core/pyproject.toml`（30 行）

| 段 | 内容 | 说明 |
|---|---|---|
| `[project]` | `name="agent-core"`、`version="0.1.0"`、`requires-python=">=3.9"` | **3.9 是硬下限**：代码里刻意不用 `X | Y` 这种 3.10 语法（`protocol.py` 顶部注释也强调了） |
| dependencies | `pydantic>=2.5`、`httpx>=0.27` | 全部逻辑只用这两个第三方库 |
| optional.dev | `pytest>=7`、`pytest-asyncio>=0.23` | 但实际单测用的是 **`unittest`**，pytest 只是备用 |
| build-system | setuptools>=68 | `src` 布局 |
| package-data | `py.typed`、`skills/builtin/*/SKILL.md`、`agent/subagents_builtin/*/SUBAGENT.md` | 与 spec 的 datas 呼应：**源码安装与 exe 打包都要带上这些 Markdown** |

---

## 7. `vscode/package.json`（166 行）

### 7.1 激活方式（14-17）

```json
"activationEvents": ["onView:agentCoder.chatView"],
"main": "./out/extension.js"
```

**只有打开视图才激活**，不占用 VS Code 启动时间。`main` 指向 `tsc` 的输出目录（`tsconfig.json` 的 `outDir: out`）。

### 7.2 容器与视图（18-36）

`viewsContainers.activitybar` → id `agentCoder`（左侧活动栏图标 `media/icon.svg`）→ `views.agentCoder` 里的 `agentCoder.chatView`，`type: "webview"`。

### 7.3 命令（37-50）

| 命令 | 作用 |
|---|---|
| `agentCoder.openChat` | 打开对话面板 |
| `agentCoder.generateDailyReport` | 旧日报入口（与 IntelliJ 的 `AgentCoder.GenerateReport` 对称） |
| `agentCoder.restartCore` | 重启 Core 进程（相当于 `AgentCoreService.restart()`） |

### 7.4 配置项（51-151）——与 IntelliJ 设置页的对齐表

| VS Code 键 | IntelliJ 侧来源 | 默认 | 是否下发 Core |
|---|---|---|---|
| `pythonPath` | `ReportSettingsState.pythonPath` | `""` | 否（Shell 自己用） |
| `coreHome` | `coreHomePath` | `""` | 否 |
| `provider` | 当前 `ModelProfile.provider`，再经 `ModelEndpoints.detect()` 改写 | `ollama` | 是，键 `provider` |
| `baseUrl` | `ModelProfile.baseUrl` | `http://localhost:11434` | 是，键 `base_url` |
| `model` | `ModelProfile.model` | `qwen2.5:7b` | 是，键 `model` |
| `apiKey` | PasswordSafe（`ModelProfiles.apiKey(profile.name)`） | `""` | 非空才下发，键 `api_key` |
| `maxIterations` | `maxIterations` | 40 | 是，键 `max_iterations` |
| `toolConcurrency` | `toolConcurrency` | 4 | 是，键 `tool_concurrency` |
| `subagentConcurrency` | `subagentConcurrency` | 3 | 是，键 `subagent_concurrency` |
| `contextWindow` | `contextWindow` | 32000 | 是，键 `context_window` |
| `temperature` | `temperature` | 0.2 | 是，键 `temperature` |
| `maxTokens` | `maxTokens` | 4096 | 是，键 `max_tokens` |
| `embeddingModel` | `embeddingModel` | VS Code `""` / IntelliJ `nomic-embed-text` | 是，键 `embedding_model` |
| `autoAcceptEdits` | `autoAcceptEdits` | false | **否**：Shell 端策略，只在 `ToolDispatcher` 判 `needsApproval` 时用 |
| `activeTeam` | `ReportSettingsState.activeTeam` / 输入区「团队」下拉 | `""` | 是，键 `active_team`；空串 = 不启用团队（3.3.0 默认，行为与单代理完全一致） |
| `commandAllowlist` | `commandAllowlist`（IntelliJ 是换行分隔的 `String`，下发前 `splitAllowlist()`） | `[]` / `""` | 是，键 `command_allowlist`，空数组 = Core 内置白名单 |
| `inlineCompletion.enabled` | `inlineCompletionEnabled` | true | **否**：决定 Kotlin Provider 的 `isEnabled()` |
| `mcpServers` | `mcpServersJson`（文本） | `[]` / `""` | 解析成功才下发，键 `mcp_servers`；项目内 `.agent/mcp.json` 优先 |

两点容易看漏：

- **`provider` 不是原样下发**：`ModelEndpoints.detect(profile.provider, profile.baseUrl)` 让「端点形状」优先。用户把类型选成 `ollama` 但 url 填了 `/v1` 网关时，Core 会按 OpenAI 兼容处理（否则不会带 Bearer token）。
- **有两项刻意不下发**：`autoAcceptEdits` 与 `inlineCompletionEnabled` 都是 Shell 侧行为，Core 不知道也不需要知道。


🔁 **这 18 个键是三端共用的契约**：VS Code 的 `settings.ts`、IntelliJ 的 `ReportSettingsState` + `pushSettings()`、Core 的 `ChatConfig`。改任意一端的键名，另外两端会静默失效（JSON 里多余的键会被 `_apply_settings` 过滤掉，不报错）。

### 7.5 scripts（154-160）

| 脚本 | 命令 | 用途 |
|---|---|---|
| `compile` | `tsc -p ./` | 编译到 `out/` |
| `watch` | `tsc -watch -p ./` | 开发 |
| `smoke` | `node scripts/smoke-rpc.js` | 协议冒烟（`verify_all` 调用） |
| `check:settings` | `node scripts/check-settings.js` | 校验配置键与 Core 的 `ChatConfig` 对齐 |

---

## 8. 从零到可安装：完整命令序列

```powershell
# 0) 前置：JDK 25（JAVA_HOME 指向它）、Python 3.9+、可选 Rust + MinGW-w64
javac -version          # 必须是 25
python --version        # 3.9+

# 1) 一条命令产出 zip（会自动跑 native → core → mvn package）
powershell -File scripts/build_plugin.ps1

# 2) 只要 zip、不重建 Core
powershell -File scripts/build_plugin.ps1 -SkipCore -SkipNative

# 3) 全量回归（单测 + 8 冒烟 + VS Code + IntelliJ 编译）
powershell -File scripts/verify_all.ps1

# 4) 校验产物
Add-Type -A System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::OpenRead("target/workreport-ai-assistant-3.1.2.zip").Entries |
  Select-Object FullName
```

第 4 步应当能看到至少这几条：

```
com.workreport.ai-assistant/lib/workreport-intellij-plugin-3.1.2.jar
com.workreport.ai-assistant/lib/okhttp-4.12.0.jar
com.workreport.ai-assistant/agent-core/agent-core.exe
```

---

## 9. 常见失败模式

| 现象 | 根因 | 修法 |
|---|---|---|
| `wrong class file version 69.0` | 用 JDK 21 跑 `mvn` | `JAVA_HOME` 指向 JDK 25 |
| `NoClassDefFoundError: GitLineHandler` | 只加了 `:git`，没加 `git-backend` | 三个 git 坐标都要有 |
| 插件装上但工具窗口空白 | `toolWindow id` 与 `AgentChatPanel.find()` 的 `"AgentCoder"` 不一致 | 两处同步 |
| 设置页打不开 | `applicationConfigurable` 用了 `implementation=` 而不是 `instance=` | 改回 `instance` |
| zip 里没有 `agent-core/` | 没跑 `build_core.ps1`，或 `core/dist` 空 | 不带 `-SkipCore` 重跑；`build_plugin.ps1` 已内置检查 |
| 内置 Core 启动即退出 | 单文件 exe 缺 data（SKILL.md / SUBAGENT.md） | spec 的 `datas` 不能删 |
| 原生扩展导入失败 | 构建与运行的解释器版本不一致 | 用同一个 `python` 既构建又自检（脚本 130 行） |
| Windows 链接 `PyCMethod_New` 未定义 | 误开了 abi3 | Windows 分支保持 `--no-default-features` |
| 新版 IDE 拒绝加载 | `until-build="263.*"` | 确认兼容后放宽该值 |
| 界面全是英文 | IDE 语言为英文且 `AgentBundle_en.properties` 缺键 | 补齐键；缺键会回退到默认 bundle |

---

## 10. 本卷自测清单

- 在 `plugin.xml` 里找出所有「字符串常量必须与 Java 代码一致」的地方（提示：至少 3 处：插件 id、toolWindow id、settings id）。
- 解释：为什么 `kotlin-maven-plugin` 的 `sourceDirs` 要包含 `src/main/java`，而 `maven-compiler-plugin` 仍然要存在？
- 解释：`agent-core.spec` 里 `datas` 与 `hiddenimports` 分别解决什么问题？互换会怎样？
- 跑 `build_plugin.ps1 -SkipCore`，观察它为什么在「core/dist 为空」时仍可能成功——这与 4.2 的「assembly 不报错」是同一个坑。
- 对照 `vscode/package.json` 的 17 个配置键，在 `ChatConfig` 里逐个找到对应字段（找不到的就是未下发的键）。

---

## 11. 下一卷

第 03 卷进入 Python Core 的骨架：`__main__.py` 的每个分支、`rpc/server.py` 的 890 行逐函数（构造、stdin 循环、dispatch、16 个请求方法、19 种通知、MCP 刷新、任务取消）、`protocol.py` 的数据模型与信封函数。
