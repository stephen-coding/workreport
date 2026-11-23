# Agent 团队（多智能体协作）· 详细设计

> 实施文档，不是宣传稿。每节给出：改动位置、接口/字段、默认值、兼容性、验收标准。
> 与实现冲突时以代码为准，并回改本文。

| 项 | 值 |
|---|---|
| 目标版本 | **v3.3.0**（v3.2.0 已被「删除 ClassicTranscript 回退」占用）|
| 基线版本 | 3.1.2 |
| 能力 | 可创建/启用/自由组合 Agent 团队；内置默认团队；PM 编排多成员协作；默认关闭 |
| 适用仓库 | Agent Core（`core/`）、IntelliJ Shell（`src/main/`）、VS Code Shell（`vscode/`，**本期对齐**）|
| 验收基线 | 现有 Core 单测 298 例 + 10 冒烟不回退；新增团队单测 + `smoke_team.py`；两端编译 + VS Code 冒烟通过 |

---

## 0. 已确认决策（本次拍板）

| # | 决策 | 落地位置 |
|---|---|---|
| **D-A** | 团队成员写/命令采用 **「预审一次 → 该实现阶段自动执行」**：PM 在实现阶段开始前设一个预审检查点（计划 + 首个 diff 交用户确认一次），批准后阶段内写/命令成员自动执行；`delete_file`、白名单外 `run_command`、危险命令**不受此豁免** | §6 权限模型、TD8 |
| **D-B** | **团队默认关闭**：安装/升级后 `active_team=""`，未启用时行为逐帧等同 v3.1.2 | §5、§8 兼容性 |
| **D-C** | 目标版本 **v3.3.0**，`pom.xml`/`plugin.xml`/内置 Core 同步 | §11 收口 |
| **D-D** | **VS Code 本期对齐**（M5 不延后），两端 `settings/update` 字段集合一致 | §8、§10 |
| **D-E** | **成员自由组合**：成员=对角色库（`SUBAGENT.md`）的引用；团队只是「角色 + capability + 顺序/阶段」的组合。内置多团队 + 用户可在 global/project 作用域自建新团队，`TEAM.md` 与 UI 双入口 | §3、§7、§9 |

---

## 1. 现状基线与关键冲突

| 现状 | 位置 | 对团队的影响 |
|---|---|---|
| 子代理严格只读：`WRITE_TOOLS` 在 `allowed_tools()`/`_build_tasks()`/`_filtered_registry()` 三处剔除 | `agent/subagent.py:55-56,89-92,481-482,498-507` | **核心冲突**：全栈/测试成员需写与跑命令 |
| 禁止递归派发：`subagent_dispatch` 被剔除 | `subagent.py:92,482,501` | 保留（成员不再派子代理，避免树爆炸）|
| 成员独立会话、看不到主对话、goal 自带背景 | `subagent.py:438`（`run_chat` + `base_prompt`）| 需「共享交付物/黑板」降低上下文割裂 |
| `SubagentPool` 并发 + 角色级 model/output/timeout | `subagent.py:231-257` | 团队直接复用池与 `SubagentTask` |
| 加载器三级覆盖 builtin→global→project + 启动缓存 | `subagent.py:163-222`、`skills/loader.py` | 团队按同模式加载；新增/改团队需重启 Core |
| 远程写工具经 `tool/invoke{permission}` 回调 Shell，diff 确认 + `ProjectWriteGate` 串行 + 快照 | `rpc/server.py:656-691` | 安全护栏已在 Shell 侧，成员写走同一路径即天然受控 |

**结论**：安全（确认/串行/快照）本就落在 Shell，Core 只需把「谁能调写/命令」从「一刀切只读」改成「按成员 capability 授权」，即可放开团队写能力而不动 D1 对临时子代理的默认约束。

---

## 2. 目标与非目标

**目标**：① 团队是一等公民（新建/启用/禁用/自由组合）；② 内置默认「软件开发团队」（调研员 + 全栈工程师 + 测试工程师 + 代码审查员），开箱可启用；③ PM 按「阶段 + 依赖 + 交付物」编排、核验、推进、整合；④ 真多智能体（独立会话），写/命令成员在「预审一次 → 自动执行」下真实动手。

**非目标（本期）**：跨机器/分布式编排；成员之间直接对话（星型：一切经 PM/黑板中转）；自动提交/推送/发布；改协议既有字段语义（只新增）。

---

## 3. 概念与数据模型（含自由组合）

### 3.1 对象
- **Role（角色库）**：= 现有 `SUBAGENT.md`。团队只**引用**角色名，不复制职责。→ 自由组合的基础：任何已有/用户自定义角色都能进任何团队。
- **Team**：`name`、`description`、`lead`（固定=主代理/PM）、`members[]`、`playbook`(body)。
- **TeamMember**：`role`(指向角色库)、`capability`、可选 `alias`(UI 展示名)、可选 `model` 覆盖、可选 `phases[]`(该成员参与的阶段)。
- **TeamPhase**（可选，用于声明式编排）：`{name, members[], dependsOn[]}`；缺省时 PM 用 `todo_write` 动态排阶段。
- **TeamRun**：一次 `chat/start` 在启用团队时的执行实例：`teamRunId`、阶段清单、黑板 artifacts、活动写文件锁、预审通过标记。生命周期=一个 taskId。
- **Artifact（黑板交付物）**：`{kind, title, body, authorRole, producedAt, refs[]}`，kind ∈ `research|plan|impl|test|review|note`。

### 3.2 组合语义（D-E）
- 成员 = `(role, capability, alias)` 三元组；`capability` 是**团队对角色的授权**，不写回 `SUBAGENT.md`——同一角色可在 A 团队只读、在 B 团队授权写。
- 内置团队（可扩展多个，如「软件开发团队」「文档团队」「数据团队」）；用户团队放 `~/.agent/teams/` 或 `<项目>/.agent/teams/`，**同名覆盖内置**。
- 角色库为空/角色缺失时团队降级：`team_dispatch` 对该成员返回「角色不存在」，PM 改派其它成员。

### 3.3 TEAM.md 格式（新增，沿用 `skills/loader.py:parse_frontmatter`，支持嵌套 `- ` 列表）
搜索/覆盖：`builtin` → `~/.agent/teams` → `<项目>/.agent/teams`（后覆盖前，同 Skill/Subagent）；布局 `<teams>/<name>/TEAM.md`。

```markdown
---
name: software-dev-team
description: 由项目经理协调调研、全栈开发、质量验证和独立代码审查，完成从需求澄清到验收的软件开发工作
lead: 项目经理
members:
  - role: researcher
    capability: readonly
    alias: 调研员
  - role: fullstack-engineer
    capability: write
    alias: 全栈工程师
  - role: test-engineer
    capability: command
    alias: 测试工程师
  - role: code-reviewer
    capability: readonly
    alias: 代码审查员
---
（body = 需求 §3「说明」+ §4「协作指令」全文，注入为 PM 的 base_prompt，不改写）
```
> ⚠️ frontmatter 不支持行内注释；`capability` 只接受 `readonly|write|command`，非法值告警后按 `readonly`。

### 3.4 角色库新增成员（3 新增 + 复用 code-reviewer）
- `researcher`：调研/取证/技术结论（body 定义职责；capability 由团队赋）。
- `fullstack-engineer`：先读原文再改、改完自检、不越界改他人文件。
- `test-engineer`：合并代码验证 + 真实交互验收、产出可复现证据（命令 + 输出摘要）。

---

## 4. 运行时架构（Core）

### 4.1 新增模块
```
agent/teams_builtin/software-dev-team/TEAM.md
agent/subagents_builtin/{researcher,fullstack-engineer,test-engineer}/SUBAGENT.md
agent/team.py        # TeamDefinition/TeamMember/TeamLoader/TeamService + capability 过滤 + 预审状态
agent/blackboard.py  # Artifacts 存储（挂 Session，随会话落盘）
```
- `TeamLoader` 镜像 `SubagentLoader`（builtin/global/project、启动缓存、`set_project_root` 失效）。
- `TeamService`：装配 PM playbook + roster；`team_dispatch` handler 把成员派发到 `SubagentPool`，用 **capability 过滤** 取代一刀切只读。

### 4.2 与子代理耦合（最小侵入）
`SubagentTask` 增 `capability: str = "readonly"`（`subagent.py:105`）。三处剔除改为按 capability 判定：
```python
def strip_by_capability(tools, capability):
    allowed = CAPABILITY_TOOLS.get(capability, set())   # write/command 才含写/命令
    return [t for t in tools if t != "subagent_dispatch"
            and (t not in WRITE_TOOLS or t in allowed)]
```
> 🔁 临时 `subagent_dispatch` 默认 capability=readonly，行为与今天完全一致；只有 `team_dispatch` 注入成员声明的 write/command。

### 4.3 新增内置工具（`remote=False`）
| 工具 | 作用 |
|---|---|
| `team_list` | 列出可用团队、成员、capability、启用态 |
| `team_dispatch` | 按成员派发（`tasks[]`: role/goal/context/artifactRefs/writes）；复用 `SubagentPool` |
| `team_preapprove` | PM 在实现阶段前发起**预审检查点**：把计划 + 首个 diff 交用户确认一次（D-A）|
| `team_post` | 向黑板写交付物（PM 或成员）|
| `team_read` | 读黑板（按 kind/role 过滤）|

`team_dispatch` 的 `writes` 字段声明本次负责的文件/接口，供 TD6 冲突防护。

### 4.4 PM playbook 注入
启用团队时，`run_chat` 的 `base_prompt` = **默认 SYSTEM_PROMPT + playbook(TEAM.md body) + roster**。

> 注意：`run_chat` 把 `base_prompt` 当作**整体替换**（`loop.py:113`）。只传 playbook 会丢掉「先 read_file 再改」「用 run_command 验证」等基础规范，因此实现改为**追加**；roster 已包含在 `active_prompt_text()` 内，此时不再注入 `subagents.catalog_text()`，避免角色清单重复。

实现点：`rpc/server.py:_run_task()`
```python
team_prompt = self.teams.active_prompt_text() if team is not None else None
base_prompt = (SYSTEM_PROMPT + "\n\n" + team_prompt) if team_prompt else None
catalog = None if team_prompt else self.subagents.catalog_text()
result = await run_chat(..., base_prompt=base_prompt, subagent_catalog=catalog)
```

### 4.5 一次团队执行流（端到端）
```
用户启用团队 → settings/update{active_team}
用户 chat/start(需求)
  PM(playbook)：team_post(plan) → todo_write(阶段)
    team_dispatch([researcher/readonly])            # 需要时
      → 成员 run_chat（capability 过滤后 registry）
    实现阶段：team_preapprove(计划+首个diff) → 用户确认一次
      → 批准后该阶段 write/command 成员自动执行
      → 成员 tool/invoke → Shell(ProjectWriteGate 串行 + 快照)；delete/危险命令仍逐次确认/拒绝
      → 成员 team_post(交付物)
    PM 核验 → 未达验收排下一阶段（不重跑有效工作）→ 测试→审查
  → chat/finish（黑板/阶段随会话落盘）
```

---

## 5. 协议变更（只增不改）

### 5.1 请求（Shell → Core）
| 方法 | 参数 | 用途 |
|---|---|---|
| `team/list` | — | UI 列团队/成员/capability/启用态 |
| `team/artifacts` | `sessionId?`, `kind?` | 回看黑板（返回 `artifacts` + `teamRuns`）|
| ~~`team/preapproveResult`~~ | `{confirmId, approved}` | **实现改为复用 `agent/confirmResult`**：预审卡只新增 `kind="preapprove"`，不新增方法 |

### 5.2 通知
| 方向 | 方法 | 新增字段 |
|---|---|---|
| S→C | `settings/update` | `active_team: str`（空=不启用，D-B）|
| C→S | `chat/subagent` | 扩 `teamRunId`、`actor`(role/alias)、`capability`、`kind∈{start,tool,artifact,end}` |
| C→S | `chat/artifact` | `{teamRunId, kind, title, authorRole, refs[]}` 摘要 |
| C→S | `agent/confirm` | 新增 `kind="preapprove"`，负载 `{preapproveId, plan, firstDiff, files[]}`（复用现有确认回包通道）|
| C→S | `tool/invoke` | **可选** `member`(alias)：Shell 把写/确认卡标注「来自 全栈工程师」；阶段已预审通过时该字段仍带但不再弹卡 |
| C→S | `core/hello.capabilities` | 追加 `"team"` |

### 5.3 字段（`ChatConfig`，`protocol.py:66`）
```python
active_team: str = ""          # 默认关（D-B）；空=常规单代理
team_max_members: int = 4      # 单次团队派发并发成员上限（成本护栏）
team_turn_cap: int = 12        # 一次团队运行阶段总数上限（防打转）
team_auto_after_preapprove: bool = True   # D-A：预审通过后阶段内写/命令自动执行
```
> 兼容：旧 Shell 不发这些字段 → 全默认 → 单代理，行为不变；`_apply_settings` 已按 `ChatConfig.model_fields` 合并（`server.py:427`）。

### 5.4 会话落盘
`agent/store.py` 版本号 +1；`Session` 增 `artifacts: List[Dict]`、`teamRuns: List[Dict]`（旧会话读 `[]`），复用 todos 写入/回放路径。

---

## 6. 权限与安全模型

- **capability ≠ 免确认（除非预审通过）**：未过预审时 `write_file/edit_file` 仍 `preview`（diff 卡）。**预审检查点通过（D-A/TD8）后**，该实现阶段内 `write`/`command` 成员的常规写/命令**自动执行**（等价 `team_auto_after_preapprove` 置真），阶段结束/换范围时预审失效需重新确认。
- **不受豁免项**：`delete_file`、白名单外 `run_command`、危险命令（`server.py:662` 直接 deny）始终逐次确认/拒绝；跨阶段或触及新文件集时重新预审。
- **连续拒绝**：沿用 `PermissionTracker`，作用域细化到 `teamRunId+role`，单成员拒绝累计 3 次停该成员，不影响其它成员。
- **串行写 + 文件锁（TD6）**：所有写经 Shell `ProjectWriteGate` 串行；Core 记录「活动写成员→文件集」，重叠文件或耦合接口拒绝并行派发，提示改派/排队（落实 §4「不并行改相同文件」）。
- **快照/回退**：成员写前内存快照照旧；`revert_file` 由 PM/用户触发统一回退。
- **成本护栏**：`team_max_members`/`team_turn_cap` + 成员 `timeout_sec`/`max_iterations`；成员用量并入 `usageTotal`（沿用 loop 聚合）。
- **破坏性操作**：沿用 playbook「未经用户授权，不提交、推送、发布」。

---

## 7. 内置默认团队与自由组合（D-E）

- `teams_builtin/software-dev-team/TEAM.md`：`description`=需求 §3 全文；body=需求 §4 全文逐段嵌入，不改写。
- 成员↔capability：
  | 成员(alias) | role | capability | 主要工具 |
  |---|---|---|---|
  | 调研员 | researcher | readonly | read_file/search_code/git_log/git_diff/list_dir/memory_recall |
  | 全栈工程师 | fullstack-engineer | write | 只读集 + write_file/edit_file/revert_file/diagnostics |
  | 测试工程师 | test-engineer | command | write 集 + run_command |
  | 代码审查员 | code-reviewer | readonly | 现有只读审查 |
- **扩展空间**：`teams_builtin/` 允许并存多个内置团队；用户团队走 global/project 目录；`team_list` 汇总局点。后续可加「UI 团队构建器」（勾选角色 + 赋 capability + 命名 → 写出 `TEAM.md`），本期先保证**文件即配置**的双入口一致，UI 至少支持「选择/启用/停用 + 查看成员」，构建器留 M7 扩展位。

---

## 8. UI 改动

### 8.1 IntelliJ（`src/main/java/com/workreport/agent/ui`）
- **启用入口**：输入区工具栏「团队」下拉（数据 `team/list`）→ 选团队发 `settings/update{active_team}`；默认「不启用」。i18n 前缀 `ui.team.*`（双 properties 等量，`verify_all.ps1` 校验）。
- **预审检查点**：`agent/confirm{kind:preapprove}` → 一张「计划 + 首个 diff」确认卡（复用/扩展 `DiffConfirmCard`），批准 = 该阶段自动执行开。
- **阶段看板**：复用 `TodoStrip`（来自 `todo_write`）。
- **成员时间线**：扩展 `StepGroupView`/`chat/subagent` 按 `actor` 分组：`[全栈工程师] 实现 X`、`[测试工程师] pytest 通过`。
- **黑板抽屉**：复用 `PickerPopup`，`team/artifacts` 拉列表按 kind 分区。

### 8.2 VS Code（已对齐，D-D）
- 只新增 `agentCoder.activeTeam` 一个设置项（与 IntelliJ 的 `settings/update.active_team` 对等）；`teamMaxMembers` / `teamTurnCap` / `teamAutoAfterPreapprove` 暂不暴露给用户，走 Core 默认值（成本护栏仍生效）。
- webview 团队下拉（`team/list`）+ 「交付物」抽屉（`team/artifacts`）+ 预审卡（`agent/confirm{kind:preapprove}`，标题 + 多行正文）。
- `chat/subagent` 显示`别名·模型[capability]`、`chat/artifact` 时间线一行 + 状态行；`ide/hello` 声明 `team`；`settings.ts` 与 IntelliJ `settings/update` 的 14 个字段完全一致（`check-settings.js` 断言通过）；能力矩阵更新到 `vscode/README.md`。

---

## 9. 自由组合的兼容与校验
- `TeamLoader` 解析失败/字段非法：告警 + 安全默认（capability→readonly），不阻断其它团队。
- 角色缺失：`team_dispatch` 返回明确错误，PM 改派。
- 团队文件同名：project>global>builtin 覆盖，`team_list` 标注来源 scope（同 Skill 覆盖提示）。

---

## 10. 分阶段实施里程碑

| 里程碑 | 交付 | 验收 |
|---|---|---|
| M1 Core 团队内核 | `team.py`/`blackboard.py`、`TeamLoader/Service`、capability 过滤、`team_*` 工具、内置团队 | 单测：capability 剔除矩阵、冲突防护、playbook/roster 注入、D1 只读回归护栏 |
| M2 编排+黑板+预审 | `team_dispatch` 复用 `SubagentPool`、`team_preapprove`(D-A)、artifacts 落盘、阶段→todos | `smoke_team.py`：调研→预审→实现(自动执行)→测试→审查贯通 |
| M3 协议与事件 | §5 字段/通知、`core/hello.capabilities` 加 `team` | `test_protocol_contract` 快照；两端收 `chat/subagent{actor}`、`agent/confirm{preapprove}` |
| M4 IntelliJ UI | 启用下拉、预审卡、成员时间线、黑板抽屉、i18n | 编译 + 手工剧本 |
| M5 VS Code 对齐 | 设置项 + webview 渲染 + 能力矩阵 | `npm run smoke` 不回退、`check-settings` 一致 |
| M6 收口 | README/USER_GUIDE/CHANGELOG、**版本 3.3.0**、全量回归 | `verify_all.ps1` 全绿 |
| M7（扩展位）| UI 团队构建器（勾选角色→赋 capability→写 `TEAM.md`）| 本期不做，接口预留 |

---

## 11. 测试与验收

- **单测**：`test_team.py`（加载/覆盖顺序、capability 工具集、冲突防护、预审状态机、playbook 注入、自由组合非法值降级）、`test_blackboard.py`（artifacts 读写/落盘/旧会话兼容）。
- **回归护栏（关键）**：新增「临时 `subagent_dispatch` 仍剔全部写工具」用例，**证明 D1 未被削弱**；新增「未启用团队逐帧等同 3.1.2」用例（D-B）。
- **冒烟**：`core/scripts/smoke_team.py`（本地模拟端点）跑三剧本：①纯调查→调研员；②小改动→实现+定向测试；③复杂变更→五阶段含预审。
- **端到端手工**：
  1. 「给某模块加缓存」：PM 排 调研→预审→实现(自动执行)→测试→审查；预审卡出一次；阶段内成员写不再逐次弹卡。
  2. 让两成员并行改同一文件 → TD6 拒绝/排队。
  3. 阶段内请求 `delete_file`/`git push` → 仍逐次确认/拒绝（不被预审豁免）。
  4. 新建用户团队勾选部分角色 → `team_list` 可见并启用生效。
  5. 未启用团队 → 与 3.1.2 行为逐帧一致。

---

## 12. 风险登记

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| RT1 | 预审一次后自动执行 → 误改放大 | 高 | 预审含「计划+首个 diff」；触及新文件集/跨阶段重审；`ProjectWriteGate` 串行 + 快照 + `revert_file`；delete/危险命令不豁免 |
| RT2 | 多成员 LLM 成本/时延暴涨 | 中 | `team_max_members`/`team_turn_cap`/成员级 timeout+iterations；用量并入 usageTotal |
| RT3 | 黑板/上下文膨胀 | 中 | artifacts 结构化 + `truncate_to_budget`；playbook「相关研究集中派发」 |
| RT4 | 成员工具异常回传主循环 | 中 | 复用 `_run_task` 超时/失败报告；成员失败仅标记该阶段，PM 依证据重排 |
| RT5 | UI 归属/预审卡复杂度 | 中 | 事件带 `actor`；预审单层卡复用 `agent/confirm`；面板不可见时回退模态（沿用 3.1 兜底）|
| RT6 | 自由组合角色缺失/capability 越权 | 中 | 解析安全默认(readonly)；缺失角色明确报错改派；capability 白名单 Core 常量硬约束 |
| RT7 | 两端行为漂移 | 中 | §5.3 字段集合一致 + `check-settings` 断言 + 协议快照 |

---

## 13. 交付清单（勾选）

- [x] `agent/team.py`、`agent/blackboard.py`
- [x] capability 过滤改造（`subagent.py` 三处 + `SubagentTask.capability`）
- [x] `teams_builtin/software-dev-team/TEAM.md` + 3 个内置成员 `SUBAGENT.md`
- [x] `team_list`/`team_dispatch`/`team_preapprove`/`team_post`/`team_read` 工具
- [x] `ChatConfig` 4 新字段 + `Session.artifacts/teamRuns` 落盘 + store 版本 +1（→3）
- [x] §5 协议方法/通知 + `core/hello.capabilities` 加 `team`
- [x] IntelliJ：启用下拉 / 预审卡 / 成员时间线 / 黑板抽屉 / i18n(`ui.team.*`)
- [x] VS Code：`activeTeam` 设置项 / webview 团队下拉与交付物抽屉 / 预审卡 / `check-settings` 对齐
- [x] 单测 `test_team`/`test_blackboard` + 回归护栏 + `smoke_team.py`
- [x] README/USER_GUIDE/CHANGELOG + 版本 **3.3.0**（`pom.xml`/`plugin.xml`/内置 Core 同步）
- [x] `verify_all.ps1` 全绿

> 当前进度：M1–M6 已完成（3.3.0 已发布）；Core 单测 **298 例**、10 个协议冒烟（`verify_all.ps1` 跑其中 9 个，含 `smoke_team.py`）、`mvn test` 15 例、`npm run compile` 与 `check-settings.js` 均通过。M7（UI 团队构建器）仍为扩展位。