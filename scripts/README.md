# scripts 使用说明

发版相关的三个脚本，按顺序使用：

| 脚本 | 作用 | 是否入库 |
|---|---|---|
| `bump_version.ps1` | 把版本号从当前值升到新值，并同步所有声明版本的位置与发版说明 | 入库 |
| `build_plugin.ps1` | 全量打包：Rust native → PyInstaller Core → Maven 组装 zip | 入库 |
| `upload_plugin_local.ps1` | 把 zip 上传到 JetBrains Marketplace（本地专用） | **不入库**（已在 `.git/info/exclude`） |

`version-notes.zh.txt` 是 `bump_version.ps1` 读取中文文案与中文锚点的模板文件（8 行，勿随意增删行序）。

---

## 一、版本号升级：`bump_version.ps1`

### 用法

```powershell
# 先演练，只看会改什么，不落盘
powershell -File scripts/bump_version.ps1 -Version 3.3.2 -DryRun

# 确认后真正写入
powershell -File scripts/bump_version.ps1 -Version 3.3.2

# 覆盖默认的中文说明（默认是"维护构建"文案）
powershell -File scripts/bump_version.ps1 -Version 3.3.2 -NotesZh "3.3.2 修复 XXX"
```

参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `-Version` | 是 | 目标版本，`MAJOR.MINOR.PATCH`，必须大于当前版本 |
| `-NotesZh` | 否 | 覆盖 CHANGELOG / change-notes 里的中文摘要 |
| `-DryRun` | 否 | 只打印将要修改的内容，不写文件 |

### 会改哪些地方

| 文件 | 改动 |
|---|---|
| `pom.xml` | `<version>` |
| `src/main/resources/META-INF/plugin.xml` | `<version>`，并在 `<change-notes>` 顶部插入新版本条目（英文 + 中文） |
| `CHANGELOG.md` | 顶部插入 `## <新版本>` 小节 |
| `README.md` | 版本 badge、产物 zip / jar 名、兼容性表「插件版本」、版本演进标题、版本演进表新增一行 |
| `USER_GUIDE.md` | 适用版本行、zip 名、「重新安装 x 的 zip」、`agent-core --version` 校验值 |

不改历史记录：`plugin.xml` 里旧版本的 `<h3>` 段、CHANGELOG 旧小节、版本演进表的旧行都保持原样。

### 安全检查

- 先读 `pom.xml` 与 `plugin.xml` 的版本，两者不一致直接报错退出，避免在错误状态上继续改。
- 新版本必须严格大于当前版本（`[version]` 比较，`3.3.10 > 3.3.9` 成立）。
- 每个替换锚点都必须命中，未命中的会打印 `WARNINGS`（例如文档改版后「| 插件版本 | x |」不再是这个格式），此时应人工补齐再提交。

### 注意事项

- 脚本本身必须是**纯 ASCII**：Windows PowerShell 5.1 按系统代码页读取无 BOM 脚本，脚本里的中文注释会把下一行代码"吞掉"。所有中文文案与中文锚点都放在 `version-notes.zh.txt`，脚本在运行时用 UTF-8 读取。
- 改文档结构（例如把「版本演进」表换成列表）后要同步更新 `version-notes.zh.txt` 里的锚点，否则对应替换会变成 WARNING。
- 版本号改了但没重新打包时，IDE 可能仍显示旧界面（缓存按版本号区分）。

---

## 二、打包：`build_plugin.ps1`

```powershell
$env:JAVA_HOME = 'D:\IntelliJ IDEA 2025.2.5\jbr'   # 必须 JDK 25
powershell -File scripts/build_plugin.ps1
```

- 平台 `263.7307.259` 的 class 文件版本是 69，`javac` 低于 25 会在预检阶段直接报错。本机可借用 IDEA 自带的 JBR 25。
- 需要 `core/dist/agent-core*` 存在；没有时先跑 `scripts/build_core.ps1`（或让 `build_plugin.ps1` 一并处理，视脚本参数而定）。
- 产物：`target/workreport-ai-assistant-<版本>.zip`，内含 `lib/workreport-intellij-plugin-<版本>.jar` 与 `agent-core/agent-core.exe`。

打包后建议自检：

```powershell
core\dist\agent-core.exe --version          # 应等于新版本号
core\dist\agent-core.exe --native-info      # backend: rust
```

---

## 三、上传：`upload_plugin_local.ps1`

只在本机存在、不提交不推送。token 不写进脚本，放在仓库外的文件里。

### 一次性准备

1. 用 Marketplace 上 **vendor `WeeklyReport`** 所属账号登录
   <https://plugins.jetbrains.com/author/me/tokens> → `Generate token`（只显示一次）。
2. 存到仓库外的文件，例如 `C:\Users\<你>\.jb-marketplace-token`（内容只有那一行 token）。
3. 插件条目已存在（id `36242`），**不要走「新建插件」入口**，否则会报
   `The id ... is already taken` / `A plugin with the name ... already exists`。

### 用法

```powershell
# 演练：校验版本号、文件、token 是否就绪，不上传
powershell -File scripts/upload_plugin_local.ps1 -TokenFile "$env:USERPROFILE\.jb-marketplace-token" -DryRun

# 真正上传
powershell -File scripts/upload_plugin_local.ps1 -TokenFile "$env:USERPROFILE\.jb-marketplace-token"
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `-PluginId` | `36242` | Marketplace 插件条目 id |
| `-Channel` | `default` | `default`(Stable) / `beta` / `eap`；`default` 时不发 channel 字段 |
| `-Zip` | 自动取 `target` 下最新 zip | 指定要上传的包 |
| `-Token` | `$env:JB_MARKETPLACE_TOKEN` | 直接传 token（不推荐） |
| `-TokenFile` | 空 | 从文件读 token（推荐） |
| `-DryRun` | 关闭 | 只校验不上传 |
| `-Force` | 关闭 | 跳过版本一致性确认 |

### 关键实现细节

- 上传端点是 **`POST https://plugins.jetbrains.com/api/updates/upload`**，multipart 字段 `pluginId` / `file` / `channel`。
  旧地址 `POST /api/plugins/upload` 只接受 `PUT/GET/HEAD/DELETE`，POST 会返回 `405 Method Not Allowed`。
- 脚本会解析响应体 JSON 并打印 `id` / `version`，没有 JSON 响应体视为上传失败（HTTP 2xx 不代表入库）。

### 上传后确认

```powershell
$p = Invoke-RestMethod https://plugins.jetbrains.com/api/plugins/36242
$p.hasUnapprovedUpdate      # True = 有版本在审核队列
$p.icon                     # 指向新文件的 id
$p.cdate
```

`GET /api/plugins/36242/updates` 只列出**已发布**的版本，刚上传的不会出现在里面，属正常。

---

## 四、典型发版流程

```powershell
# 1. 升版本
powershell -File scripts/bump_version.ps1 -Version 3.3.2 -DryRun
powershell -File scripts/bump_version.ps1 -Version 3.3.2

# 2. 打包（JDK 25）
$env:JAVA_HOME = 'D:\IntelliJ IDEA 2025.2.5\jbr'
powershell -File scripts/build_plugin.ps1

# 3. 自检
core\dist\agent-core.exe --version

# 4. 上传
powershell -File scripts/upload_plugin_local.ps1 -TokenFile "$env:USERPROFILE\.jb-marketplace-token"

# 5. 提交代码（bump_version.ps1 改过的 5 个文件）
git add pom.xml CHANGELOG.md README.md USER_GUIDE.md src/main/resources/META-INF/plugin.xml
git commit -m "build: 版本号收口 3.3.2"
```

审核通过前请勿删除 `target` 下的 zip；回滚时把 Marketplace 上的版本设回上一个已发布版本。

---

## 五、常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `HTTP 405 Method 'POST' is not supported` | 用了 `/api/plugins/upload` | 改用 `/api/updates/upload` |
| `The id ... is already taken` / 名称已存在 | 走了「新建插件」入口 | 在已有条目 `36242` 下「Upload New Version」 |
| 上传返回 200 但库里没有 | 实际未入库（文件 id 未变化） | 用 `hasUnapprovedUpdate` + `icon` 指向的文件 id 核对 |
| 脚本里某行"凭空消失" | PowerShell 5.1 按 ANSI 读无 BOM 脚本，中文注释吞行 | `.ps1` 保持纯 ASCII，中文放 `version-notes.zh.txt` |
| `javac 21` 预检失败 | `JAVA_HOME` 指向 JDK 21 | 指向 JBR 25 后重跑 |
| 装上新包但界面没变 | 版本号未变，IDE 缓存 | 版本号递增，或卸载后重装 |
