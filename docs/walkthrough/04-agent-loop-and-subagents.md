# 第 04 卷 · Agent 循环、调度、清单与子代理

> 本卷读完 Agent 层的 9 个文件，共约 1,550 行：
>
> `loop.py`(200) · `scheduler.py`(108) · `permissions.py`(29) · `compression.py`(56) · `status.py`(123) · `commands.py`(130) · `todos.py`(128) · `ask.py`(194) · `subagent.py`(583)
>
> （团队编排层 `team.py`（733 行）不在本卷展开，详见 `docs/AGENT_REFACTOR_DETAILED_DESIGN.md` 与第 03 卷的服务端回调。）
>
> 第 01 卷讲的是「一条消息怎么流动」，本卷讲的是「流动过程中每一步的判定规则」——**轮次怎么算、工具怎么并发、输出怎么压缩、什么条件下必须停下来**。

---

## 0. 一次 `run_chat` 的精确时序

```
run_chat(session, user_input, ...)
│
├─ session.append(USER, user_input)                    ① 先落用户消息
├─ 构造：tools / scheduler / tracker / stats / usage_total / unknown_tools
├─ system_prompt = base_prompt or SYSTEM_PROMPT
├─ +subagent_catalog  +memory_overview  +skill_instruction   ② 固定顺序
│
└─ for _ in range(max_iterations):
     ├─ stats.begin_round()                             ③ rounds += 1
     ├─ messages = [SYSTEM] + session.messages
     ├─ messages = trim_history(messages, budget_for(window))
     ├─ if 无 USER: 补回本轮输入                          ④ vLLM 兜底
     ├─ status = stats.render(max_iterations); 追加 USER   ⑤ 裁剪之后
     ├─ result = await provider.chat_stream(...)          ⑥ 流式（思考/正文两路）
     ├─ _flush_notice(provider, on_notice)                ⑦ 降级提示
     ├─ _add_usage(usage_total, result.usage)
     ├─ if 没有 tool_calls: append(ASSISTANT) → return     ⑧ 正常结束
     ├─ append(ASSISTANT with tool_calls); stats.record_calls
     ├─ results = await scheduler.run_parallel(...)        ⑨ 并发执行
     ├─ for call, r in zip(...): append(TOOL, 压缩后内容)    ⑩ 回填
     ├─ if 同一未知工具 ≥3: return 停止文案                  ⑪
     └─ if 连续拒绝 ≥3: return 停止文案                      ⑫
└─ for 正常跑完 → return 「已达最大轮次」                    ⑬
```

---

## 1. `loop.py`（200 行）

### 1.1 `SYSTEM_PROMPT`（19-30）逐句对照

| 提示词要求 | 由哪个机制保证 |
|---|---|
| 「嵌入在 IntelliJ 与 VSCode」 | 同一份提示词服务两端，Shell 差异不下发 |
| 「涉及代码时给出完整可运行片段」 | 纯提示词约束，无代码校验 |
| 「优先调用工具获取事实」 | 工具清单由 `registry.defs()` 注入 |
| 「修改文件前先用 read_file 确认原文」 | 纯提示词；`EditTools` 有 `FileSnapshots` 兜底（旧内容找不到就报错） |
| 「改完代码后用 run_command 跑构建或测试」 | `run_command` 的 `auto` 白名单里就有 `mvn` / `pytest`，正是为了让这句能落地 |
| 「删除文件用 delete_file 逐个确认」 | `delete_file` 权限为 `confirm`；`rm -rf` 等被 `DENY_PATTERNS` 拦死 |
| 「任务需要 3 步以上时先列清单」 | `todo_write` 工具 + `_publish_todos` 推给 UI |
| 「同一时间最多一个 in_progress」 | 只在工具描述里约束，`normalize_todos` **不强制**（模型可以违规，UI 也能显示两个进行中） |
| 「不要捏造事实」 | 纯提示词 |

⚠️ 唯一「提示词里说了但代码不校验」的是「最多一个 in_progress」。要强制的话得在 `normalize_todos` 里把多余的重置为 `pending`——目前刻意留给模型自我管理，避免它写清单时被静默改写。

### 1.2 常量与类型（32-40）

```python
UNKNOWN_TOOL_LIMIT = 3
```

注释给了完整动机：**模型一旦认准一个不存在的工具名（例如 `bash`），会连续几十轮重复「让我用正确的工具名」**，把 `max_iterations=40` 的预算烧光，用户只看到一堆自说自话。所以同一未知工具名累计 3 次就终止。

### 1.3 六个小工具函数

#### `_noop_tool_call`（43-46）

```python
async def _noop_tool_call(call: ToolCall) -> None:
    return None
```

provider 的 `chat_stream` 需要 `on_tool_call` 回调。但工具调用是**从最终累加器里取的**（流式过程中参数 JSON 还不完整），所以这个回调什么也不做。传 `None` 也可以，传一个空函数是为了让 provider 不必判空。

#### `_tool_path`（49-51）

```python
path = (call.arguments or {}).get("path")
return path if isinstance(path, str) else None
```

`compress_tool_output` 需要 `path` 来判断语言（决定是否做结构概览）。模型可能把 `path` 写成数字/对象，这里统一过滤成 `str` 或 `None`。

#### `_flush_notice`（54-61）

```python
consume = getattr(provider, "consume_notice", None)
if consume is None: return
text = consume()
if text and on_notice is not None:
    await on_notice(text)
```

- **`getattr` 而不是直接调用**：`consume_notice` 是可选协议，不是所有 provider 都实现（fake provider 就没有）。
- 「consume」的语义是**取走并清空**：同一条降级提示只会通知一次，不会每轮重复。
- 典型场景：端点不支持 tool calling，provider 关掉工具继续跑，并通过这条提示告诉用户「本轮没有工具能力」。

#### `_int`（64-68）

```python
try: return int(value or 0)
except (TypeError, ValueError): return 0
```

用量数字可能来自任意 provider，`None`、`"123"`、`"n/a"` 都要能兜住。

#### `_add_usage`（71-79）

```python
in_tokens = _int(usage.get("in"))
out_tokens = _int(usage.get("out"))
total["in"] += in_tokens
total["out"] += out_tokens
total["total"] += _int(usage.get("total")) or (in_tokens + out_tokens)
```

| 细节 | 原因 |
|---|---|
| `total` 优先用 provider 报的 | 有些端点把 cache 命中算进 total，自己加会少算 |
| provider 没报 total 时自己加 | `0` 是假值，`or` 正好兜住 |
| `total["in"]` 是**本轮所有轮次累加** | 一次工具循环 5 轮，用户看到的是 5 轮之和（真实的账单口径） |

#### `_usage_snapshot`（82-87）

```python
if not (total["in"] or total["out"] or total["total"]):
    return None
return dict(total)
```

**返回 `None` 而不是全 0**：Shell 的 `appendUsage` 收到 `None` 就不显示用量行（本地 Ollama 常常不报用量，显示 `0 tokens` 反而像 bug）。返回 `dict(...)` 是拷贝，防止调用方改动累加器。

### 1.4 `run_chat` 签名（90-105）

| 参数 | 类型 | 作用 |
|---|---|---|
| `session` | `Session` | 消息历史 + 清单，最终落盘 |
| `user_input` | str | 本轮输入（也用于④的兜底补回） |
| `config` | `ChatConfig` | 轮次预算、并发度、上下文窗口 |
| `provider` | 鸭子类型 | 有 `chat_stream` / 可选 `consume_notice` |
| `emit_token` | `TokenCallback` | 每个增量 token 的回调 |
| `registry` | `Optional[ToolRegistry]` | `None` 表示「不要工具」（子代理过滤后可能为空） |
| `on_tool_start` / `on_tool_end` | 回调 | 只做 UI 通知，不影响逻辑 |
| `on_notice` | 回调 | provider 降级提示 |
| `on_reasoning` | `Optional[TokenCallback]` | 思考过程通道（3.3.0）；`None` 时丢弃思考，只留正文 |
| `memory_overview` | str | 记忆索引（正文靠 `memory_recall` 工具按需取） |
| `skill_instruction` | str | 命中技能的正文 |
| `base_prompt` | str | **子代理专用**：替换默认 SYSTEM_PROMPT |
| `subagent_catalog` | str | 角色清单 + 委派策略 |

### 1.5 初始化（106-124）

```python
session.append(Message(role=Role.USER, content=user_input))
tools = registry.defs() if registry is not None else None
scheduler = ToolScheduler(registry, config.tool_concurrency) if registry is not None else None
max_iterations = max(1, config.max_iterations)
tracker = PermissionTracker()
stats = TurnStats()
usage_total = {"in": 0, "out": 0, "total": 0}
unknown_tools: Counter = Counter()
```

- **先 append 用户消息**：即使随后 LLM 报错，这一轮用户说了什么也已经进了 session（`_run_task` 的 `finally` 会落盘）。反过来就会丢用户输入。
- `tools=None` 时 provider 收到的是「无工具模式」；子代理传空 registry 也走这条路。
- `max(1, ...)`：配置成 0 也不会变成「一轮都不跑」。
- `unknown_tools` 是 `Counter`，key 是工具名 → 支持「不同未知工具各自计数」。

### 1.6 系统提示词的三段追加（114-124）

顺序固定：**子代理清单 → 记忆索引 → 技能正文**。

```python
system_prompt = base_prompt or SYSTEM_PROMPT
if subagent_catalog:  system_prompt += "\n\n" + subagent_catalog
if memory_overview:   system_prompt += "\n\n以下是项目长期记忆索引（需要某条正文时调用 memory_recall）：\n" + memory_overview
if skill_instruction: system_prompt += "\n\n" + skill_instruction
```

📌 **为什么顺序不能反？** 技能正文常以「## 步骤」开头并自带指令，放最后能覆盖前面的通用规则（例如日报技能要求「先列三个阶段」）；如果把技能放中间，后面的委派策略会打断它的结构。

📌 `base_prompt`（子代理）走的是「完全替换」而非追加：子代理不需要知道主代理的记忆/技能/委派策略，它只接收自己的 `system_prompt()`。

### 1.7 循环体七步（126-148）

```python
for _ in range(max_iterations):
    stats.begin_round()
    messages = [Message(role=Role.SYSTEM, content=system_prompt)] + session.messages
    messages = trim_history(messages, budget_for(config.context_window))
    if not any(m.role == Role.USER for m in messages):
        messages.append(Message(role=Role.USER, content=user_input))
    status = stats.render(max_iterations)
    if status:
        messages.append(Message(role=Role.USER, content=status))
    result = await provider.chat_stream(messages, tools=tools, config=config,
                                        on_token=emit_token, on_tool_call=_noop_tool_call,
                                        on_reasoning=on_reasoning)
```

| 步 | 细节 | 不这么做会怎样 |
|---|---|---|
| ① `begin_round` | 每轮 +1，供状态栏显示 `轮次: 3/40` | 状态栏永远是 0 |
| ② 每轮重建 `messages` | `session.messages` 每轮都在增长，必须重新拼 | 复用上一轮列表会漏掉新工具结果 |
| ③ `trim_history` | 按 token 预算裁剪（详见第 05 卷 `context.py`） | 超窗口直接 400 |
| ④ 无 USER 时补回 | 裁剪算法可能把头部的用户消息也裁掉 | vLLM 报 `No user query found in messages.` |
| ⑤ 状态栏在裁剪**之后** | 保证它不会被裁掉，且只改动前缀末尾（缓存友好） | 状态栏被裁掉 = 模型看不到自己重复调用了什么 |
| ⑥ `tools=tools` | `registry.defs()` 的快照（构造时取的） | 运行期新增的工具在本轮不可见（MCP 刷新后下一轮才生效） |

### 1.8 状态栏的 role 为什么是 USER（139-140）

```python
messages.append(Message(role=Role.USER, content=status))
```

- 用 `role="system"`：会在前缀中间插一条 system，破坏「系统提示词 + 历史」的缓存前缀；部分 OpenAI 兼容网关甚至只允许**第一条**是 system。
- 用 `role="assistant"`：模型会把它当成自己说过的话，容易混淆「我声称的」和「事实」。
- 用 `role="user"` 且**放在最后**：既不动前缀缓存，也明确表示「这是外部注入的观察结果」。

这是 📌 一个权衡：语义上它不是用户说的，但工程上它是代价最小的选择。

### 1.9 正常结束（152-155）

```python
if not result.tool_calls:
    session.append(Message(role=Role.ASSISTANT, content=result.content))
    result.usage_total = _usage_snapshot(usage_total)
    return result
```

- `result.content` 可能为 `None`（模型只说了空话）：照样追加以保证消息序列完整。
- `usage_total` 是**整个 turn 的累加**，`result.usage` 只是最后一轮。

### 1.10 工具轮（157-177）

```python
session.append(Message(role=Role.ASSISTANT, content=result.content, tool_calls=result.tool_calls))
stats.record_calls(result.tool_calls)
tool_results = await scheduler.run_parallel(result.tool_calls, on_start=on_tool_start, on_end=on_tool_end)
denied_too_often = False
for call, tool_result in zip(result.tool_calls, tool_results):
    if tool_result.ok:
        content = compress_tool_output(call.name, tool_result.content or "", _tool_path(call))
    else:
        content = "ERROR: " + (tool_result.error or "tool failed")
    session.append(Message(role=Role.TOOL, tool_call_id=call.id, name=call.name, content=content))
    stats.record_result(call, tool_result.ok, tool_result.error or "")
    if not tool_result.ok and (tool_result.error or "").startswith("未知工具"):
        unknown_tools[call.name] += 1
    if tracker.record_result(tool_result.ok, tool_result.error or ""):
        denied_too_often = True
```

四个要点：

**① 失败的 tool 消息也必须 append。** OpenAI 协议要求每个 `tool_call_id` 都有对应的 tool 消息，少一条下一次请求就会 400。

**② 失败信息统一加 `ERROR: ` 前缀。** 这是给模型的信号词，比裸文本更容易被识别为「工具失败」而不是「工具的输出内容就是这个」。

**③ 压缩只作用于成功结果。** 错误信息通常很短，而且**原样保留**更利于模型自我修正（截断错误会把关键字截掉）。

**④ `startswith("未知工具")` 必须与 `scheduler.unknown_tool_error` 的文案一致。** 这是**字符串契约**：

```python
# scheduler.py:43
return "未知工具: %s。可用工具: %s" % (name, hint or "无")
# loop.py:174
if not tool_result.ok and (tool_result.error or "").startswith("未知工具"):
```

⚠️ 改文案时两边必须同步，否则「未知工具熔断」会静默失效。

### 1.11 三个提前终止

#### ① 同一未知工具 ≥ 3（182-189）

```python
stuck = [name for name, count in unknown_tools.items() if count >= UNKNOWN_TOOL_LIMIT]
if stuck:
    stop_text = ("模型反复要求调用不存在的工具「%s」（已尝试 %d 次），已停止本轮。"
                 "可用工具里没有它，换一个支持工具调用的模型，或直接告诉我用哪个工具。"
                 % (stuck[0], unknown_tools[stuck[0]]))
    session.append(Message(role=Role.ASSISTANT, content=stop_text))
    return ChatResult(content=stop_text, usage_total=_usage_snapshot(usage_total))
```

- 取 `stuck[0]`：`Counter` 的插入顺序，第一个达到阈值的名字。
- 文案直接给出**两条修复路径**（换模型 / 指定工具名），因为这是能力缺陷而不是用户错误。
- ⚠️ 这条消息会被当作 assistant 回复显示给用户，所以必须写成「给人看」的语气。

#### ② 连续拒绝 ≥ 3（191-195）

```python
if denied_too_often:
    stop_text = "连续多次未获得授权，已停止工具执行。请确认后重新发起请求。"
```

判据在 `PermissionTracker`（见第 3 节）。注意是**连续**：只要中间成功一次就清零。

#### ③ 跑满 `max_iterations`（197-200）

```python
limit_text = "（已达到最大迭代轮次 %d，任务可能未完成，回复「继续」可接着跑。）" % max_iterations
```

📌 这里的「回复『继续』」是**真的有效**：`Session` 的历史还完整，下一轮 `chat/start` 会带着全部上下文重新进入循环，轮次计数从 0 重新开始（`run_chat` 每次调用都是新的 `for`）。

### 1.12 `loop.py` 的三个边界

1. **`max_iterations` 是「每次 run_chat」的上限，不是「每个会话」的**。用户连点三次「继续」，理论上可以跑 120 轮。
2. **`stats` 也是每次重新创建**：状态栏里的「重复 ×3」不会跨 turn 累计。
3. **`registry.defs()` 是快照**：MCP 刷新发生在 `settings/update`，下一次 `run_chat` 才拿到新工具。

---

## 2. `scheduler.py`（108 行）

### 2.1 `unknown_tool_error`（31-43）

```python
names = registry.names()
hint = ", ".join(names[:MAX_NAME_HINTS])
if len(names) > MAX_NAME_HINTS:
    hint += " 等 %d 个" % len(names)
return "未知工具: %s。可用工具: %s" % (name, hint or "无")
```

注释里的经验值得单独记：

> 只报「未知工具: bash」等于让模型盲猜；把真实工具名列出来，它才能一次改对。

- `MAX_NAME_HINTS = 12`：工具名平均 10 个字符，12 个约 150 字符，对上下文可忽略；列全了（40+）就会挤压正文。
- `names` 排过序（`registry.names()` 返回 `sorted`）→ 同一份提示词每次生成的错误信息**完全一致**，不会因为 dict 顺序变化扰动缓存。

### 2.2 `ToolScheduler.__init__`（47-49）

```python
self.max_concurrency = max(1, max_concurrency)
```

`tool_concurrency=0` 会让 `Semaphore(0)` 永久死锁，这里兜住。

### 2.3 `run_one` 的五个分支（59-83）

```python
tool_def = self.registry.get(call.name)
if tool_def is None:
    return ToolResult(ok=False, error=unknown_tool_error(self.registry, call.name))
handler = self.registry.handler(call.name)
if handler is None:
    return ToolResult(ok=False, error="工具未实现: " + call.name)

async with semaphore:
    if on_start is not None:
        await on_start(call)
    try:
        result = await asyncio.wait_for(handler(call), timeout=tool_def.timeout_sec)
    except asyncio.TimeoutError:
        result = ToolResult(ok=False, error="工具执行超时")
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        LOGGER.exception("tool %s failed", call.name)
        result = ToolResult(ok=False, error=str(exc))
```

| 分支 | 结果 | 说明 |
|---|---|---|
| 定义不存在 | `未知工具: ...` | 触发 loop 的熔断计数 |
| 定义在但 handler 缺 | `工具未实现: X` | 例如只注册了 `ToolDef` 却忘了 handler；**不会**计入未知工具熔断 |
| 超时 | `工具执行超时` | 超时时长来自 `ToolDef.timeout_sec` |
| `CancelledError` | **重新抛** | 用户点「停止」时必须能中断，不能被当成普通异常吞掉 |
| 其它异常 | `str(exc)` | 完整堆栈进日志，模型只看到一行 |

⚠️ **两个细节容易忽略**：

1. **`semaphore` 在 `on_start` 之前获取**。因此 `chat/toolStart` 通知是在拿到并发槽之后才发出的——UI 上显示的「同时运行中」卡片数永远不会超过 `tool_concurrency`，与实际并发一致。
2. **`await on_end` 在 `async with` 之外**。工具已经结束，通知回调里再慢也不会占着并发槽（否则一个卡住的 UI 回调会拖慢整批）。

### 2.4 执行顺序与结果回填（85-108）

```python
results = {}
def run_batch(batch): return asyncio.gather(*(run_one(call) for call in batch))
def store(calls_batch, batch_results):
    for call, call_result in zip(calls_batch, batch_results):
        results[call.id] = call_result

interactive = [call for call in calls if call.name in INTERACTIVE_TOOLS]
rest = [call for call in calls if call.name not in INTERACTIVE_TOOLS]

for call in interactive:
    store([call], await run_batch([call]))
if rest:
    store(rest, await run_batch(rest))

return [results[call.id] if call.id in results
        else ToolResult(tool_call_id=call.id, ok=False, error="工具未执行")
        for call in calls]
```

**为什么用 `results` 字典按 id 回填，而不是直接用 `gather` 的返回列表？**

因为执行是**分两批**的（interactive 先跑），`gather` 的列表只对应各自批次。按 id 回填后，最后一步按**调用方原始顺序**输出——`loop` 用 `zip(result.tool_calls, tool_results)` 一一配对，顺序错了就会把 A 的输出挂到 B 的 name 上。

**`工具未执行` 兜底**：正常不会出现；一旦出现（比如将来加了「跳过某类工具」），至少保证每个 call 都有结果，不会让 `zip` 少一条导致 400。

### 2.5 `INTERACTIVE_TOOLS` 为什么独占（24-28、94-102）

```python
INTERACTIVE_TOOLS = frozenset({"ask_user"})
```

注释原文：

> 需要等人回答的工具。它们独占一轮：否则用户还在选，文件已经被改了。

场景：模型同一轮返回 `[edit_file, ask_user]`。如果并行执行，`edit_file` 会立刻写盘，而用户此时正在回答「要不要改这个文件」——**答案已经没有意义了**。

所以 `ask_user` 单独先跑，等回答回来，再跑其余工具。

📌 这个设计有一个副作用：一轮里若有 3 个 `ask_user`，它们**彼此也是串行**的（`for call in interactive` 逐个 `await`）。这是刻意的——同时弹三张提问卡，用户会直接放弃。

### 2.6 并发度的真实含义

`Semaphore(tool_concurrency)` 是**每次 `run_parallel` 新建的**，不是全局的。因此：

- 同一轮的 N 个工具共享 `tool_concurrency` 个槽；
- 不同 turn 之间不共享（但 Core 只允许一个会话任务在跑，实际不会叠加）。

---

## 3. `permissions.py`（29 行）

### 3.1 全文逻辑

```python
DENIAL_PREFIXES = ("用户拒绝", "user denied", "denied by user")

class PermissionTracker:
    def __init__(self, max_consecutive_denials: int = 3):
        self.max_consecutive_denials = max_consecutive_denials
        self.consecutive_denials = 0

    def record_result(self, ok: bool, error: str = "") -> bool:
        if ok or not self._is_denial(error):
            self.consecutive_denials = 0
            return False
        self.consecutive_denials += 1
        return self.consecutive_denials >= self.max_consecutive_denials
```

### 3.2 三个要点

**① 「拒绝」是靠文案识别的，不是靠结构化字段。**

Shell 点「拒绝」时，`ToolCallCard.decide(false)` 产生的 error 以「用户拒绝」开头；VS Code 侧用英文 `user denied`。Core 只能看字符串。

⚠️ 这是**跨端字符串契约**：改 Shell 的拒绝文案必须同步 `DENIAL_PREFIXES`，否则连续拒绝检测失效（用户会一直被迫点卡）。

**② 只要有一次成功或者非拒绝类失败，计数清零。**

`_is_denial` 用 `startswith` 而不是 `in`：错误正文里可能引用「用户拒绝」这四个字（例如把上一次的错误回显给模型），用 `in` 会误判。

**③ 三次是经验值。**

两次可能是误点，四次用户已经烦了。3 次刚好是「确认你真的不想要这个操作」的窗口。

---

## 4. `compression.py`（56 行）

### 4.1 常量（18-21）

```python
TOOL_OUTPUT_BUDGET = 1800
HEAD_SHARE = 0.55
_LINE_PREFIX = re.compile(r"^\s*\d+\u2192")
```

- `1800` tokens：约 7 KB 中文或 1,800 行短代码。超过这个量，单条工具结果就会挤占对话预算。
- `HEAD_SHARE=0.55`：保留 55% 给文件开头，45% 给结构概览。
- `\u2192` 就是 `→`：Shell 的 `read_file` 输出带 `123→` 行号前缀（IntelliJ 的行号样式）。

### 4.2 `strip_line_numbers`（24-28）

```python
if "\u2192" not in text:
    return text
return "\n".join(_LINE_PREFIX.sub("", line) for line in text.splitlines())
```

- **先做整体快速判断**：没有 `→` 就直接返回，避免对 100 KB 文本跑正则。
- 正则只匹配**行首的「数字 + 箭头」**，不匹配正文里出现的 `→`（注释或字符串里的箭头不受影响）。
- 为什么要剥掉？结构解析器（`outline_of`）需要干净的源码；带行号前缀会把缩进和语法搞乱。

### 4.3 `compress_tool_output`（37-56）

```python
if not content or count_tokens(content) <= budget:
    return content

if tool_name == "read_file":
    source = strip_line_numbers(content)
    language = _language_hint(path, source)
    if language:
        outline = outline_of(path or "", source)
        if outline:
            head = truncate_to_budget(source, int(budget * HEAD_SHARE))
            tail = truncate_to_budget(outline, max(1, budget - int(budget * HEAD_SHARE)))
            return "%s\n\n...（文件过长，以下仅列出结构概览）...\n%s" % (head, tail)

return truncate_to_budget(content, budget)
```

#### 只有 `read_file` 享受「头部 + 结构」

| 工具 | 处理 |
|---|---|
| `read_file` + 能识别语言 | 55% 原文头部 + 45% 函数/类概览 |
| `read_file` + 语言未知 | 纯截断 |
| 其它一切（`search_code`、`git_diff`、`run_command`…） | 纯截断 |

**为什么 `search_code` 不做结构概览？** 它返回的本来就是「一堆命中行」，没有结构可提取；而 `run_command` 的输出是日志，截断比概括更有信息量。

**为什么保留头部而不是尾部？** 文件开头通常是 import / 类声明 / 文件说明，是理解全文最关键的 20 行。尾部往往是重复的 getter。

#### `truncate_to_budget` 的单位是 token

`head` 与 `tail` 的预算是**加起来正好 `budget`**（55% + 45%），不会超预算。

⚠️ 但拼接后的 `\n\n...（文件过长...）...\n` 本身也占 token，所以最终结果会**略超** `budget` 十几 token。这是可接受的误差（1800 → 1815），不值得为它做二次裁剪。

### 4.4 压缩的时机

只在 `loop.py:165` 调用，且**只在 `tool_result.ok` 时**：

```python
if tool_result.ok:
    content = compress_tool_output(call.name, tool_result.content or "", _tool_path(call))
```

📌 这意味着 **`session.messages` 里存的是压缩后的内容，不是原始输出**。所以：
- 好的一面：磁盘会话文件小，重放时上下文也小；
- 代价：模型再也看不到被裁掉的中间部分（需要时会重新 `read_file` 指定行范围）。

---

## 5. `status.py`（123 行）

### 5.1 为什么需要状态栏（模块文档 1-14）

> The model is good at retrieving facts that are already in the context but bad at recounting them（"how many times did I call this tool?"）. The harness therefore pre-counts the turn and hands the result over.

三条硬规则：

1. 追加在**最后一条消息** → 系统提示词与整段缓存前缀保持字节一致（prompt cache 命中）。
2. 每个数字都由**代码计算** → 模型会无条件相信状态栏，绝不能由另一次 LLM 调用生成。
3. 没有工具调用时**整块不出现** → 普通闲聊不付这份成本。

### 5.2 常量（24-31）

| 常量 | 值 | 含义 |
|---|---|---|
| `REPEAT_MIN` | 2 | 只有第 2 次相同调用才算「重复」 |
| `MAX_TOOLS` | 4 | 工具统计最多列 4 个 |
| `MAX_REPEATS` | 3 | 重复调用最多列 3 条 |
| `MAX_EDITED` | 3 | 已改文件最多列 3 个 |
| `MAX_ERROR` | 40 | 单条错误摘要 40 字 |
| `EDIT_TOOLS` | write/edit/delete | 只有这三个算「已改」 |

📌 `EDIT_TOOLS` 不含 `revert_file`：回滚不是「改动」，列进「已改」会让模型误以为文件处于新状态。

### 5.3 `_digest`（34-40）

```python
try:
    raw = json.dumps(call.arguments or {}, sort_keys=True, ensure_ascii=False)
except (TypeError, ValueError):
    raw = str(call.arguments)
return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:8]
```

- `sort_keys=True`：`{"a":1,"b":2}` 与 `{"b":2,"a":1}` 指纹相同——模型换个参数顺序不算新调用。
- `ensure_ascii=False`：中文参数不转义，指纹稳定。
- 8 位 hex = 32 bit：一个 turn 内几十次调用，碰撞概率约 1e-7，够用。
- `TypeError` 兜底：参数里有不可序列化对象时退化成 `str()`。

### 5.4 `_short_path` / `_clip`（43-51）

```python
def _short_path(value):
    return os.path.basename(value.rstrip("/\\")) or value

def _clip(text, limit):
    flat = " ".join((text or "").split())
    return flat if len(flat) <= limit else flat[:limit] + "…"
```

- 只显示 basename：状态栏要的是「哪个文件」，不是完整路径（完整路径 80 字符会挤占一整行）。
- `_clip` 先折叠所有空白（包括换行）：多行错误压成一行。
- 结尾用 `…` 而不是 `...`：省一个字符，且视觉上更明确是截断。

### 5.5 `TurnStats` 七个字段（54-66）

| 字段 | 类型 | 含义 |
|---|---|---|
| `rounds` | int | 已进行的轮次 |
| `calls` | `Counter` | 工具名 → 调用次数 |
| `failures` | `Counter` | 工具名 → 失败次数 |
| `last_error` | `Dict[str,str]` | 工具名 → 最近一次错误摘要 |
| `call_counts` | `Counter` | `(工具名, 参数指纹)` → 次数 |
| `repeat_labels` | `Dict[key,str]` | 指纹 → 可读标签（`read_file(a.py)`） |
| `edited` | `List[str]` | 已成功修改的文件 basename（去重、保序） |

### 5.6 `record_calls`（71-78）

```python
for call in calls:
    self.calls[call.name] += 1
    key = (call.name, _digest(call))
    self.call_counts[key] += 1
    if key not in self.repeat_labels:
        label = _short_path((call.arguments or {}).get("path"))
        self.repeat_labels[key] = call.name + ("(" + label + ")" if label else "")
```

- 标签**只在第一次出现时生成**：同一指纹的参数相同，重复计算没必要。
- 没有 `path` 参数的工具（如 `run_command`）只显示工具名。

### 5.7 `record_result`（80-88）

```python
if ok:
    if call.name in EDIT_TOOLS:
        path = _short_path((call.arguments or {}).get("path"))
        if path and path not in self.edited:
            self.edited.append(path)
    return
self.failures[call.name] += 1
self.last_error[call.name] = _clip(error or "未知错误", MAX_ERROR)
```

- 只有**成功**的写操作才进 `edited`（失败没改成）。
- `last_error` 只保留最近一次：模型需要知道的是「现在为什么不行」，不是完整错误史。

### 5.8 `render`（90-123）的五段

```python
if not self.calls:
    return ""
lines = ["<agent_status>", "轮次: %d/%d" % (self.rounds, max(1, max_rounds))]
tools = " · ".join("%s %d" % (name, count) for name, count in self.calls.most_common(MAX_TOOLS))
lines.append("工具: " + tools)

repeats = [(self.repeat_labels[key], count) for key, count in self.call_counts.items()
           if count >= REPEAT_MIN]
repeats.sort(key=lambda item: item[1], reverse=True)
if repeats:
    lines.append("重复: " + " · ".join("%s ×%d" % (label, count) for label, count in repeats[:MAX_REPEATS]))

if self.failures:
    parts = []
    for name, count in self.failures.most_common(2):
        detail = self.last_error.get(name, "")
        parts.append("%s ×%d — %s" % (name, count, detail) if detail else "%s ×%d" % (name, count))
    lines.append("失败: " + " · ".join(parts))

if self.edited:
    lines.append("已改: " + "、".join(self.edited[:MAX_EDITED]))

lines.append("</agent_status>")
return "\n".join(lines)
```

输出样例：

```xml
<agent_status>
轮次: 5/40
工具: read_file 7 · search_code 3 · edit_file 2 · run_command 1
重复: read_file(a.py) ×3 · search_code(b.py) ×2
失败: edit_file ×2 — 找不到原文，请先 read_file 确认
已改: a.py、b.py
</agent_status>
```

关键设计：

**① 用 XML 标签包裹。** `<agent_status>` 让模型清楚边界，也方便未来用正则从日志里抽取。

**② 「先过滤再截断」。** 注释原文：

```python
# Filter first, then truncate: the top-N list may be full of one-off calls.
```

如果先按 `most_common(3)` 取再过滤 `>=2`，很可能取到的 3 个都只调用过一次，于是「重复」这一行永远消失。

**③ 失败只列前 2 个工具。** 失败 → 行动（改参数/换工具）的对应关系很直接，列多了是噪音。

**④ `重复` 与 `工具` 是两套视图。** 「工具」按名字聚合（`read_file 7`），「重复」按参数指纹聚合（`read_file(a.py) ×3`）。前者看整体工作量，后者看原地打转。

**⑤ 空则整块不出现。** `if not self.calls: return ""` —— 模型没调工具时，状态栏无信息可给。

---

## 6. `commands.py`（130 行）

### 6.1 三个决策值

```python
AUTO = "auto"      # 免确认
CONFIRM = "confirm"  # 每次确认
DENY = "deny"      # 直接拒绝
```

模块文档一句话概括所有权：**The Core decides, the Shell only obeys**。决策结果随 `tool/invoke.permission` 下发。

### 6.2 `DEFAULT_ALLOWLIST`（24-56）

30 条前缀，覆盖四类：

| 类别 | 条目 |
|---|---|
| Java 构建 | `mvn`、`mvnw`、`gradle`、`gradlew` |
| 前端 | `npm`、`npx`、`pnpm`、`yarn` |
| Python 测试 | `pytest`、`python -m unittest`、`python -m pytest`、`python3 -m ...` |
| 其它构建 | `cargo`、`go test`、`go build`、`go vet`、`dotnet test`、`dotnet build` |
| 只读 Git | `git status`、`git diff`、`git log`、`git branch`、`git show` |
| 只读文件 | `ls`、`dir`、`cat`、`type` |
| 版本 | `node -v`、`node --version`、`java -version` |

⚠️ 注意几个**故意不在名单里**的：`git add`、`git commit`、`git checkout`、`pip install`、`python script.py`、`rm`。它们会走 `confirm`。

📌 `git branch` 在名单里，但 `git branch -d` 在黑名单里（见下）——**开除分支要确认，查看分支不用**。这就是「前缀匹配 + 整行黑名单」两层配合的意义。

### 6.3 `DENY_PATTERNS`（61-87）

命中即 `deny`，**不弹确认框**：

| 类别 | 模式 |
|---|---|
| 递归删除 | `rm -rf`、`rm -fr`、`rm -r /`、`rm --recursive`、`del /f`、`del /s`、`del /q`、`rd /s`、`rmdir /s` |
| 系统级 | `format `、`shutdown`、`reboot`、`mkfs`、`diskpart` |
| fork 炸弹 | `:(){` |
| Git 危险操作 | `git reset --hard`、`git push`、`git clean -`、`git branch -d` |
| 远程执行 | `curl | sh`、`curl|sh`、`wget | sh`、`wget|sh`、`invoke-expression`、`iex(` |

**匹配方式是「整行小写子串」**，注释说明了原因：

> 按整行子串匹配，链式命令中的危险片段同样会被拦下。

所以 `ls && rm -rf .` 会被拦——即使 `ls` 在白名单里。

⚠️ 这也是 `git push` 永远 deny 的原因：**推送必须由人执行**（与项目约定一致）。模型想推只能让用户自己推。

### 6.4 管道解释器检测（89-96、123-125）

```python
_PIPE_INTERPRETER = re.compile(r"^(sh|bash|zsh|powershell|pwsh|iex)(\s|$)")

for piped in lowered.split("|")[1:]:
    if _PIPE_INTERPRETER.match(piped.strip()):
        return DENY
```

为什么需要这一层？因为 `DENY_PATTERNS` 里的 `curl | sh` 只能匹配**这一种写法**：

- `curl -s http://x | sh`：含 `| sh` 但**不含** `curl | sh`（多了一个 `-s`）→ 漏！
- `wget -qO- http://x|bash`：同理。

所以不管前面是什么，只要**管后第一段**以解释器开头就拒绝。

⚠️ 正则用 `(\s|$)` 而不是 `\b`：`sh` 后面必须是空白或行尾，所以 `shuf`、`shadow`、`shutdown` 不会被误判为 `sh`（`shutdown` 另有黑名单条目）。

### 6.5 `normalize` / `_matches_prefix`（99-111）

```python
def normalize(command):
    return " ".join(str(command or "").split())

def _matches_prefix(segment, prefixes):
    for raw in prefixes:
        prefix = raw.strip().lower()
        if not prefix: continue
        if segment == prefix or segment.startswith(prefix + " "):
            return True
    return False
```

- `normalize`：`git    status` → `git status`（多空格、Tab、换行都会被折叠）。
- `_matches_prefix` 的关键是 **`prefix + " "`**：
  - `mvn` 匹配 `mvn`、`mvn test`；
  - `mvn` **不匹配** `mvnw`（否则 `mvnw` 会被 `mvn` 顺带放行——虽然 `mvnw` 也在名单里，但其它条目会出问题，例如 `go` 不能匹配 `gofmt`）。
- 整词匹配也意味着：`npm run build > /tmp/log` 里的重定向**不影响**匹配（仍在 `npm` 前缀下）。

### 6.6 `decide` 的五步（114-130）

```python
text = normalize(command)
if not text:                      return DENY          # ① 空命令
lowered = text.lower()
for pattern in DENY_PATTERNS:
    if pattern in lowered:        return DENY          # ② 整行黑名单
for piped in lowered.split("|")[1:]:
    if _PIPE_INTERPRETER.match(piped.strip()): return DENY   # ③ 管道解释器
prefixes = allowlist if allowlist else DEFAULT_ALLOWLIST
segments = [p.strip() for p in _SEGMENT_SPLIT.split(lowered) if p.strip()]
if segments and all(_matches_prefix(p, prefixes) for p in segments):
    return AUTO                                        # ④ 每段都在白名单
return CONFIRM                                          # ⑤ 其余
```

`_SEGMENT_SPLIT = re.compile(r"&&|\|\||;|\||&|\n")`：`&&`、`||`、`;`、`|`、`&`、换行都算分段。

| 例子 | 结果 | 原因 |
|---|---|---|
| `mvn -q test` | auto | 单段，前缀匹配 |
| `mvn test && npm run lint` | auto | 两段都在白名单 |
| `mvn test && curl x` | confirm | 第二段不在 |
| `git status` | auto | |
| `git status && git push` | **deny** | 黑名单先命中 |
| `ls` | auto | |
| `ls; rm -rf build` | **deny** | 黑名单先命中 |
| `cat a.py | sh` | **deny** | 管道解释器 |
| `echo hi` | confirm | 不在白名单 |
| `""` | **deny** | 空命令 |

⚠️ **顺序不能换**：黑名单必须在白名单**之前**判断。假设反过来先分段查白名单，那么 `git status && git push` 的结果会是 `confirm`（第二段不在白名单）——也就是弹一个确认框，用户点「允许」就推了。而现在的顺序让黑名单先命中整行 `git push`，直接 `deny`，**连确认的机会都没有**。

### 6.7 用户自定义白名单的作用范围（126）

```python
prefixes = allowlist if allowlist else DEFAULT_ALLOWLIST
```

- 用户填了白名单 → **完全替换**默认表（不是追加）。想保留默认能力，得自己把 `mvn` 等抄进去。
- 用户**不能**通过白名单放行 `DENY_PATTERNS` 里的任何东西——黑名单是硬编码的，没有配置项。
- 空列表/`None` → 用默认表。

---

## 7. `todos.py`（128 行）

### 7.1 为什么清单在 Core（模块文档 1-8）

> The list belongs to the running session, lives in the Core and is persisted with the conversation, so the Shell only renders what it is told.

三个后果：

1. 清单随会话落盘 → 重开会话能看到上次的清单；
2. Shell 不持有状态 → IntelliJ 与 VS Code 渲染同一份数据；
3. 工具是 `remote=False` → 更新清单不需要往 Shell 跑一个来回。

### 7.2 状态与标记（19-27）

```python
STATUSES = ("pending", "in_progress", "completed", "cancelled")
MARKS = {"pending": "[ ]", "in_progress": "[>]", "completed": "[x]", "cancelled": "[-]"}
```

`MARKS` 只用于**工具结果文本**（给模型看），UI 用的是矢量勾选（`TodoStrip`），但状态词汇表两边一致。

### 7.3 `TODO_DEF`（29-56）

- `description` 明确要求：「任务需要 3 步以上时先列清单」「开始做某项时置为 in_progress」「同一时间最多一个 in_progress」。
- `parameters` 里 `id` 是 `required`，但 `normalize_todos` 会在缺失时补位置序号——**schema 严格、实现宽容**。
- `permission=AUTO`、`remote=False`。

### 7.4 ContextVar 绑定（58-69）

```python
_active_session = contextvars.ContextVar("todo_session", default=None)

def bind_session(session): return _active_session.set(session)
def reset_session(token):  _active_session.reset(token)
```

由 `server._run_task` 在任务开始时绑定、`finally` 里 reset。这样工具 handler 不需要 `session` 参数。

⚠️ 与 `_current_task` 一样，**必须 reset**；否则同一事件循环里的下一个任务可能读到上一个会话。

### 7.5 `normalize_todos`（72-89）的四条清洗规则

```python
if not isinstance(raw, list): return []
for entry in raw:
    if not isinstance(entry, dict): continue
    content = str(entry.get("content") or "").strip()
    if not content: continue                                  # ① 空内容丢弃
    status = str(entry.get("status") or "pending").strip().lower()
    if status not in STATUSES: status = "pending"              # ② 非法状态降级
    todo_id = str(entry.get("id") or "").strip() or str(len(todos) + 1)  # ③ 缺 id 用序号
    todos.append({"id": todo_id, "content": content, "status": status})
```

| 规则 | 例子 |
|---|---|
| ① 空内容丢弃 | `{"id":"1","content":"  "}` → 丢掉 |
| ② 非法状态降级 | `"status":"done"` → `pending`（`done` 不是合法值） |
| ③ 缺 id 用序号 | 第三项的 id 变成 `"3"` |
| ④ 非 dict 项丢弃 | 列表里混进字符串 → 跳过 |

📌 注意 `num` 用的 `len(todos)+1` 是**清洗后的长度**，所以如果第 2 项被丢弃，第 3 项会拿到 id `"2"`——id 不代表原始位置。

### 7.6 `render`（92-101）

```python
if not todos: return "清单已清空。"
lines = ["任务清单（%d 项）：" % len(todos)]
for todo in todos:
    lines.append("%s %s. %s" % (MARKS.get(todo["status"], "[?]"), todo["id"], todo["content"]))
done = sum(1 for todo in todos if todo["status"] in ("completed", "cancelled"))
lines.append("进度：%d/%d" % (done, len(todos)))
```

- `cancelled` 计入「完成」：不再做的事也是「已处理」。
- 未知状态用 `[?]` 兜底（理论上进不来，因为 normalize 已经过滤）。

样例输出：

```
任务清单（3 项）：
[>] 1. 读 pom.xml 确认版本
[ ] 2. 改 plugin.xml
[x] 3. 跑 mvn compile
进度：1/3
```

### 7.7 `make_handler`（104-123）

```python
async def handle(call):
    session = _active_session.get()
    if session is None:
        return ToolResult(ok=False, error="当前没有可写入清单的会话")
    todos = normalize_todos((call.arguments or {}).get("todos"))
    if not todos:
        return ToolResult(ok=False, error="todos 不能为空：每项需要 id / content / status")
    session.todos = todos
    try:
        publish(todos)
    except Exception:
        pass
    return ToolResult(ok=True, content=render(todos))
```

三条边界：

1. **没有绑定会话 → 失败**（不是静默成功）。子代理里调 `todo_write` 时也是复用主任务的绑定，所以不会触发这条。
2. **空清单 → 失败**，而不是「清空清单」。想清空得传一个只有一项且内容明确的清单？——实际上无法清空（见下）。
3. **`publish` 失败被吞掉**：UI 通知失败不能影响工具结果，否则模型会以为清单没写成功而重写。

⚠️ 一个实际限制：`session.todos = todos` 是**整表覆盖**，而 `todos` 不能为空 → **无法通过 `todo_write` 清空清单**。`render` 里的「清单已清空」分支只有直接调用 `render([])` 才会出现。

---

## 8. `ask.py`（194 行）

### 8.1 设计三要点（模块文档 8-13）

```text
- 提问没有短超时：用户可能离开几分钟。chat/cancel 会取消任务，挂起的 future 随之清理
  并向 Shell 发 ask/withdraw，卡片不会一直挂着。
- 一轮里若出现 ask_user，调度器先单独跑它：模型不能一边问用户一边改文件。
- 没有可用界面时返回一条说明，让模型带假设继续，绝不空等。
```

### 8.2 常量与 schema（25-88）

| 常量 | 值 | 理由 |
|---|---|---|
| `MAX_QUESTIONS` | 3 | 「超过三个问题的卡片是表单，不是提问」 |
| `MAX_OPTIONS` | 4 | 选项超过 4 个，用户开始逐字读而不是扫 |
| `timeout_sec` | 1800.0 | 与 server 的 `ASK_TIMEOUT` 一致 |

`ASK_DEF.description` 里有三条**行为约束**，值得抄进任何类似的工具描述：

1. 「只在信息确实缺失、且无法从代码或上下文推断时使用」；
2. 「能从仓库里读到的信息请自己读，**不要拿它当确认工具**」；
3. 「用户点跳过时…请基于合理假设继续，并在结论里标注假设」。

⚠️ 第 2 条是重点：模型很容易把「我要不要改这个文件」当成问题问用户，而正确做法是用 `edit_file` + Shell 的 diff 卡片确认。

### 8.3 `normalize_questions`（95-135）——输入有多脏

```python
if isinstance(raw, dict): raw = [raw]          # ① 单个对象包成列表
if not isinstance(raw, list): return []
for entry in raw:
    if not isinstance(entry, dict): continue
    text = str(entry.get("question") or "").strip()
    if not text: continue                      # ② 没正文丢弃
    for option in entry.get("options") or []:
        if isinstance(option, str):            # ③ 字符串选项
            label, description = option.strip(), ""
        elif isinstance(option, dict):
            label = str(option.get("label") or option.get("text") or "").strip()  # ④ label/text 都认
            ...
        if len(options) >= MAX_OPTIONS: break  # ⑤ 截断
    header = str(entry.get("header") or "").strip()
    questions.append({
        "header": header[:24],
        "question": text,
        "options": options,
        "multiSelect": bool(entry.get("multiSelect")),
        "allowCustom": entry.get("allowCustom", True) is not False,   # ⑥ 默认 True
    })
    if len(questions) >= MAX_QUESTIONS: break  # ⑦ 截断
```

| 规则 | 说明 |
|---|---|
| ① | 模型有时会传 `{"question": ...}` 而不是 `[{...}]` |
| ② | 只有选项没有正文的题没有意义 |
| ③ | `"options": ["A","B"]` 也接受 |
| ④ | `label` 与 `text` 混用是常见模型行为 |
| ⑤⑥⑦ | 三个上限都在 normalize 里兜，**不依赖模型遵守 schema** |
| — | `options` 可以为空：**纯自由填写题**同样有用 |

📌 `allowCustom` 用 `is not False` 而不是 `bool(...)`：只有显式 `false` 才关闭自定义答案，`None`/`0`/缺省都视为开启（对用户更宽松）。

📌 `header[:24]` 而不是 12：描述里说「12 字以内」，实现给到 24 字符（中文约 12 字），是给英文/混排留余量。

### 8.4 `render`（138-167）

```python
if reply.get("skipped"):
    return ("用户跳过了这次提问，没有给出答案。"
            "请基于合理假设继续完成任务，并在最终回答里列出你采用的假设。")

answers = reply.get("answers") or []
by_index = {}
for entry in answers:
    if not isinstance(entry, dict): continue
    try: index = int(entry.get("index"))
    except (TypeError, ValueError): continue
    by_index[index] = entry

lines = ["用户已回答："]
for index, question in enumerate(questions):
    answer = by_index.get(index) or {}
    picked = [str(i).strip() for i in (answer.get("picked") or []) if str(i).strip()]
    custom = str(answer.get("custom") or "").strip()
    parts = list(picked)
    if custom: parts.append("其它：" + custom)
    label = question.get("header") or ("Q%d" % (index + 1))
    if parts: lines.append("%s. %s → %s" % (index + 1, label, "；".join(parts)))
    else:     lines.append("%s. %s → （未选择）" % (index + 1, label))
return "\n".join(lines)
```

关键点：

- **`index` 是数字下标**，不是 id。Shell 必须回传题目在 `questions` 数组里的位置。
- 越界的 index 会被忽略（`by_index` 里多余项不影响渲染）。
- 某题没答案 → 「（未选择）」而不是整块省略：模型需要知道「这题用户没答」，否则会以为问题没问出去。
- 自定义答案加「其它：」前缀，与选项区分。

### 8.5 `make_handler`（170-190）的三条失败路径

| 情况 | 返回 |
|---|---|
| `questions` 归一化后为空 | `ok=False` + 「questions 不能为空…」 |
| `ask()` 抛异常 | `ok=False` + 「提问失败：%s。请基于合理假设继续，并标注假设。」 |
| `ask()` 返回 `None`（无 UI / 超时） | `ok=False` + 「当前没有可以接收提问的界面（或用户未在时限内回答）…」 |

⚠️ 三条都是 `ok=False`，但**都不该让 turn 失败**——工具失败只是这一条 tool 消息，模型会读到错误文案并继续。这正是「绝不空等」的落地方式。

📌 为什么用 `ok=False` 而不是 `ok=True` 加一段说明？因为 `ok=False` 会让 `PermissionTracker` 检查错误前缀——这里的错误文案不以「用户拒绝」开头，所以不会误计为拒绝。同时 `stats` 会把它记成「ask_user 失败」，状态栏能反映出来。

---

## 9. `subagent.py`（583 行）

> 3.3.0 起本文件多了一层「能力档」（capability）：临时派发的子代理仍是 `readonly`，只有启用团队后的成员才可能拿到 `write` / `command`。团队编排本身在 `agent/team.py`（733 行），本卷只讲被团队复用的执行路径。

### 9.1 定义格式与三级覆盖（模块文档 1-25）

```markdown
---
name: code-reviewer
description: 代码审查
tools: [read_file, search_code, git_diff]
max_iterations: 6
output: bullets
model: qwen2.5-7b
timeout_sec: 240
---
# 指令
...
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `name` | 必填 | 没有 name 的定义被丢弃 |
| `description` | `""` | 出现在主代理的清单里 |
| `tools` | `[]` → `DEFAULT_TOOLS` | 白名单；写工具一律剔除 |
| `max_iterations` | 5 | 子代理自己的轮次预算 |
| `model` | `""` | 空 = 继承主代理模型 |
| `output` | `text` | `text` / `bullets` / `json` |
| `timeout_sec` | 240 | 单任务墙钟超时 |

加载顺序（后者覆盖前者）：`builtin` → `~/.agent/subagents` → `<project>/.agent/subagents`。目录结构既支持 `xxx/SUBAGENT.md`，也支持平铺的 `xxx.md`。

### 9.2 默认工具与能力档（49-99）

```python
DEFAULT_TOOLS = ("read_file", "list_dir", "search_code", "git_log", "git_diff",
                 "memory_recall", "skill_load")

WRITE_TOOLS = frozenset(("write_file", "edit_file", "delete_file", "revert_file",
                         "apply_patch", "create_file"))
COMMAND_TOOLS = frozenset(("run_command",))

CAPABILITY_READONLY = "readonly"
CAPABILITY_WRITE = "write"
CAPABILITY_COMMAND = "command"
CAPABILITIES = (CAPABILITY_READONLY, CAPABILITY_WRITE, CAPABILITY_COMMAND)

CAPABILITY_EXTRA_TOOLS = {
    CAPABILITY_READONLY: frozenset(),
    CAPABILITY_WRITE: frozenset(("write_file", "edit_file", "revert_file", "diagnostics")),
    CAPABILITY_COMMAND: frozenset(("write_file", "edit_file", "revert_file",
                                   "diagnostics", "run_command")),
}

DEFAULT_TIMEOUT_SEC = 240.0
REPORT_TOKENS = 500
```

`strip_by_capability(tools, capability)`（80-96）是唯一出口：先丢掉 `subagent_dispatch`（不递归），再丢掉「本能力没授予」的写工具与 `run_command`。三档的差别：

| 能力档 | 额外授予 | 谁在用 |
|---|---|---|
| `readonly`（默认） | 无（只有 `DEFAULT_TOOLS` 那套读工具） | 所有 `subagent_dispatch` 子任务 |
| `write` | `write_file` / `edit_file` / `revert_file` / `diagnostics` | 团队里的实现类成员 |
| `command` | 上面四个 + `run_command` | 需要跑构建/测试的成员 |

为什么默认只读？——子代理没有 diff 卡片、没有用户确认、并行三个子代理同时改同一文件必然冲突。**读可以并行，写必须串行由主代理（或被显式授权的团队成员）做。**

📌 `delete_file` 三档都不在授予集里：删除永远是用户的决定，不会被能力档顺带放开。

### 9.3 输出契约（111-120）

```python
OUTPUT_CONTRACTS = {
    "text": "",
    "bullets": "输出格式：按要点逐行列出（必要时附文件路径与行号），最后给一段不超过 3 行的结论。",
    "json": "输出格式：只输出一个 JSON 对象，字段为 summary（字符串）、"
            "findings（数组，每项含 location / issue / severity / fix）、confidence（0-1）。"
            "不要包裹 Markdown 代码块。",
}
```

- `text` 不加任何约束（自由发挥）。
- `bullets`：审查类角色用，一行一条，主代理容易合并去重。
- `json`：结构化合并；「不要包裹代码块」是因为模型习惯性写 ```json，主代理解析会失败。

### 9.4 `SubagentDefinition`（125-152）

```python
def allowed_tools(self):
    declared = self.tools or list(DEFAULT_TOOLS)
    return [name for name in declared if name not in WRITE_TOOLS and name != "subagent_dispatch"]

def system_prompt(self):
    header = ("你现在是一个独立的子代理（%s），只负责完成被分配的目标；"
              "完成后用简洁中文给出结论与依据，不要闲聊。" % self.name)
    if self.description: header += "\n职责: " + self.description
    contract = OUTPUT_CONTRACTS.get(self.output)
    if contract: header += "\n" + contract
    return header + "\n\n" + self.body.strip()
```

- `allowed_tools` 剥两次壳：`self.tools or DEFAULT`（空则默认）→ 过滤写工具与 `subagent_dispatch`。这里仍是**定义级**过滤；运行时的能力档过滤在 `_filtered_registry`（559-567）再做一次。
- `system_prompt` 三段拼：角色声明 → 职责 → 输出契约 → 正文。
- `scope` 与 `path` 只是元数据（便于排查「这条定义从哪来的」）。

### 9.5 三个 pydantic 模型（155-177）

| 模型 | 用途 | 关键字段 |
|---|---|---|
| `SubagentTask` | 派发单元 | `id`（`role-N`）、`goal`（含背景）、`role`、`allowed_tools`、**`capability`（默认 `readonly`）**、`system_prompt`、`max_iterations`、`model`、`output`、`timeout_sec` |
| `SubagentReport` | 回传单元 | `task_id`、`ok`、`summary`（截断到 500）、`detail`（全文）、`role`、`model`、`duration_ms` |

📌 `SubagentTask` 把定义「拍平」了：运行时不再引用 `SubagentDefinition`，改定义文件不会影响已派发的任务。

📌 `_current_member`（101-105）是个 ContextVar，`current_member()`（108-109）读取它。子代理执行期间它被设成自己的 `role`，Shell 收到写/确认请求时就能把卡片标注成「来自 全栈工程师」，而不是一条匿名的工具调用。

### 9.6 `parse_definition`（184-213）的容错

```python
try:    iterations = int(str(meta.get("max_iterations") or 5))
except ValueError: iterations = 5
output = str(meta.get("output") or "text").strip().lower()
if output not in OUTPUT_CONTRACTS:
    LOGGER.warning("subagent %s has unknown output %r, using text", name, output)
    output = "text"
try:    timeout_sec = float(str(meta.get("timeout_sec") or DEFAULT_TIMEOUT_SEC))
except ValueError: timeout_sec = DEFAULT_TIMEOUT_SEC
...
tools=[str(t) for t in tools] if isinstance(tools, list) else [str(tools)],
max_iterations=max(1, iterations),
timeout_sec=max(1.0, timeout_sec),
```

| 容错 | 行为 |
|---|---|
| 无 `name` | 返回 `None`，整个文件被忽略 |
| `max_iterations: abc` | 回退 5，**不警告**（YAML 里写错数字很常见） |
| `output: markdown` | 警告 + 回退 `text` |
| `tools: read_file`（单字符串） | 包成 `["read_file"]` |
| 数值 ≤ 0 | 抬到 1 |

### 9.7 `SubagentLoader`（216-274）

```python
def set_project_root(self, project_root):
    if project_root != self.project_root:
        self.project_root = project_root
        self._cache = None       # ← 只有路径真变了才失效缓存

def load_all(self, refresh=False):
    if self._cache is not None and not refresh:
        return self._cache
    definitions = {}
    for directory, scope in self._candidate_dirs():
        for definition in self._read_dir(directory, scope):
            definitions[definition.name] = definition
    self._cache = definitions
    return definitions
```

- **缓存**：`load_all` 每次 `chat/start` 都会被 `catalog_text()` 调用；不缓存就要扫三遍目录。
- **覆盖靠字典赋值顺序**：后读到的同名定义直接覆盖。
- `_read_dir` 用 `sorted(os.listdir())`：同一目录里同名定义不可能出现（文件名唯一），排序只是为了日志稳定。
- 读文件失败只警告并跳过（`OSError`）。

📌 缓存失效只有两个入口：`refresh=True`（工具 `subagent_list` 没用）和 `set_project_root`（项目切换）。**改 `SUBAGENT.md` 后需要重启 Core 或改一次项目路径才会生效。**

### 9.8 `SubagentPool`（284-310）

```python
async def dispatch(self, tasks, runner, on_event=None):
    semaphore = asyncio.Semaphore(self.max_concurrent)
    async def run_one(task):
        async with semaphore:
            if on_event is not None: await on_event("start", task)
            try:
                report = await runner(task)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOGGER.exception("subagent %s failed", task.id)
                report = SubagentReport(task_id=task.id, ok=False, summary="子任务失败: %s" % exc)
            if on_event is not None: await on_event("end", task, report)
            return report
    return list(await asyncio.gather(*(run_one(task) for task in tasks)))
```

与 `ToolScheduler` 的三处结构相似（信号量、`CancelledError` 重抛、异常包成结果），两处不同：

1. **`on_event("end")` 在 `async with` 内部**：`start`/`end` 是配对的生命周期事件，`end` 必须在释放槽位之前发出，否则 UI 可能看到「槽位空了但卡片还在转」。
2. **没有 per-task 超时**：超时在 `SubagentService._run_task` 里用 `wait_for` 实现，因为需要包装成 `子代理超时` 报告而不是抛异常。

### 9.9 两个工具定义（317-364）

| 工具 | 参数 | 超时 | 说明 |
|---|---|---|---|
| `subagent_dispatch` | `tasks[]`（混合角色）或 `type` + `goals[]`；`context` | **900 s** | 描述里明确「子代理看不到你的对话，goal 要自带文件路径与背景」 |
| `subagent_list` | 无 | 默认 30 s | 列出角色与默认工具 |

- `permission="auto"`：派发本身不危险（子代理只读）。
- `900 s` 是**外层**（整个 dispatch，包含所有并行子任务）；每个子任务还有自己的 `timeout_sec`（默认 240）。
- `remote=False`：Core 内部执行。

### 9.10 `catalog_text` 注入（393-419）

```python
lines = ["可委派的子代理（只读，不能改文件）："]
for definition in definitions:
    extra = "，模型 %s" % definition.model if definition.model else ""
    lines.append("- %s：%s（工具 %s；输出 %s；最多 %d 轮%s）" % (...))
lines.extend([
    "何时委派：",
    "1. 需要同时从多个角度或多批文件得到结论（审查 / 找 bug / 补测试）时，一次 subagent_dispatch 用 tasks 列表混合派发；",
    "2. 单个文件的小改动、明确的一步操作，自己动手即可，不要为几行改动派发；",
    "3. 子代理看不到你的对话，goal 必须写清目标、文件路径和你已确认的现象；",
    "4. 子代理只给结论与依据，改文件仍由你统一执行。",
])
```

这是**动态提示词**：新增一个 `SUBAGENT.md` 就自动出现在主代理的指令里，`loop.py` 一行不改。

📌 第 2 条是成本控制：每个子代理都是一次独立的 LLM 会话（从零开始读文件），派发 5 个审查员的 token 成本远高于主代理自己读 5 个文件。

### 9.11 `_list_handler`（423-435）

```python
if not definitions: return ToolResult(ok=True, content="（没有可用的子代理）")
```

注意是 `ok=True`：**「没有子代理」不是错误**，是正常状态。

### 9.12 `_dispatch_handler`（437-461）主流程

```python
requests = self._normalise_requests(args)
if not requests: return error("缺少参数：请用 tasks 列表，或 type + goals")

shared = str(args.get("context") or "").strip()
tasks = self._build_tasks(requests, shared)
if not tasks: return error("缺少参数：每个子任务都要有 goal")

reports = await self.run_tasks(tasks, self._on_event)

ok_count = sum(1 for report in reports if report.ok)
roles = "+".join(dict.fromkeys(task.role for task in tasks))
blocks = ["## 子代理结果（%s，成功 %d/%d）" % (roles, ok_count, len(reports))]
for report in reports:
    blocks.append("### %s %s（%.1fs%s）\n%s" % (
        report.task_id, "OK" if report.ok else "FAILED",
        report.duration_ms / 1000.0,
        "·" + report.model if report.model else "",
        truncate_to_budget(report.summary or report.detail, REPORT_TOKENS)))
return ToolResult(tool_call_id=call.id, ok=ok_count > 0, content="\n\n".join(blocks))
```

| 细节 | 说明 |
|---|---|
| `run_tasks` 每次新建池子 | 并发度取**当前配置**，改设置后下一轮生效；团队派发也走同一个入口（385-389），不必各自拿信号量 |
| `roles` 用 `dict.fromkeys` 去重保序 | `code-reviewer+bug-hunter` |
| 标题里 `成功 x/y` | 让模型知道是否有失败，决定要不要重派 |
| `### task_id OK（12.3s·qwen2.5-7b）` | 一行包含全部诊断信息 |
| `report.summary or report.detail` | 优先用截断摘要，没有才用全文 |
| `ok = ok_count > 0` | **部分成功也算成功**（否则 1 失败 4 成功时整条工具结果变红，模型会重派全部） |
| 任务默认 `readonly` | `_build_tasks` 已按 `CAPABILITY_READONLY` 过滤；团队复用时才会带上成员自己的 capability |

### 9.13 `_run_task`（463-477）

```python
started = time.monotonic()
try:
    report = await asyncio.wait_for(self._execute(task), timeout=task.timeout_sec)
except asyncio.TimeoutError:
    report = SubagentReport(task_id=task.id, ok=False, role=task.role, model=task.model,
                            summary="子代理超时：%.0f 秒内没有给出结论，已跳过。" % task.timeout_sec)
report.role = task.role
report.model = task.model or (self._get_config().model or "")
report.duration_ms = int((time.monotonic() - started) * 1000)
return report
```

- 超时**不抛异常**，而是产出一条失败报告 → 其它子任务不受影响。
- 三行统一补 `role`/`model`/`duration`：即使 `_execute` 正常返回，这些字段也可能为空。
- `report.model` 显示**实际生效**的模型（未指定则为当前主模型）。

### 9.14 `_execute`（479-503）

```python
provider = self._get_provider()
base = self._get_config()
config = base.model_copy(update={
    "max_iterations": max(1, task.max_iterations),
    "model": task.model or base.model,
})
session = Session("subagent-%s" % task.id)
registry = self._filtered_registry(task.allowed_tools, task.capability)

async def emit_token(_delta): return None

member_token = _current_member.set(task.role)   # Shell 据此标注「来自某成员」
try:
    result = await run_chat(session, task.goal, config, provider, emit_token,
                            registry=registry, base_prompt=task.system_prompt)
finally:
    _current_member.reset(member_token)
text = (result.content or "").strip()
summary = truncate_to_budget(text, REPORT_TOKENS) if text else "（子代理没有输出）"
return SubagentReport(task_id=task.id, ok=True, summary=summary, detail=text)
```

| 细节 | 为什么 |
|---|---|
| `model_copy(update=...)` | pydantic v2 的浅拷贝 + 覆盖；端点/Key/温度全部继承 |
| `Session("subagent-%s")` **不传 store** | 子代理的对话**不落盘**：它是过程，不是历史 |
| `emit_token` 空实现 | 子代理的 token 不推给 UI（否则主对话里会混入子代理的话） |
| `registry` 带能力档 | 同一个 `allowed_tools` 在 `readonly` 下会自动剥掉写/命令工具；团队成员则保留其 capability 授予的部分 |
| `_current_member.set/reset` | 用 `try/finally` 成对出现；不 reset 会把成员身份泄给后续工具调用 |
| `base_prompt=task.system_prompt` | 完全替换主系统提示词 |
| `ok=True` 恒成立 | 只要 `run_chat` 返回就视为「有结论」；内容为空另作提示 |

⚠️ 注意 `ok=True` 的判断非常宽松：子代理在轮次耗尽时返回「已达最大迭代轮次」的文案，也会被当作成功。主代理看到的是这段文案，需要自己判断是否重派。

### 9.15 `_normalise_requests`（507-516）

```python
raw_tasks = args.get("tasks")
if isinstance(raw_tasks, list):
    requests = [item for item in raw_tasks if isinstance(item, dict)]
    if requests: return requests
type_name = str(args.get("type") or "").strip() or "general"
return [{"type": type_name, "goal": goal} for goal in self._normalise_goals(args.get("goals"))]
```

- **新写法优先**：`tasks` 列表只要非空就完全忽略 `type`/`goals`。
- 旧写法（`type` + `goals`）保留兼容：一个角色多个目标。
- 都没给 → 返回空列表 → handler 报「缺少参数」。

### 9.16 `_build_tasks`（518-550）

```python
counters = {}
for item in requests:
    role = str(item.get("type") or "general").strip() or "general"
    goal = str(item.get("goal") or "").strip()
    if not goal: continue
    definition = self.loader.get(role)
    counters[role] = counters.get(role, 0) + 1
    context = "\n".join(part for part in (shared, str(item.get("context") or "").strip()) if part)
    if context:
        goal = "%s\n\n背景与相关线索：\n%s" % (goal, context)
    declared = self._normalise_tools(item.get("tools"))
    tools = declared or (definition.allowed_tools() if definition else list(DEFAULT_TOOLS))
    tasks.append(SubagentTask(
        id="%s-%d" % (role, counters[role]),
        ...
        role=role,
        # 即便调用方临时指定了工具，也要过 capability 检查：写/命令工具只在被授予时保留。
        allowed_tools=strip_by_capability(tools, CAPABILITY_READONLY),
        system_prompt=self._prompt_for(definition),
        max_iterations=definition.max_iterations if definition else 5,
        model=definition.model if definition else "",
        ...
    ))
```

| 规则 | 说明 |
|---|---|
| 角色不存在 | `definition is None` → 用 `_prompt_for(None)` 的通用提示词 + 默认工具 + 5 轮 |
| id 形如 `code-reviewer-1`、`code-reviewer-2` | 同一角色多目标时序号递增 |
| 背景拼接 | `shared`（全局 context）+ `item.context`（该任务专属），用「背景与相关线索：」分隔 |
| 每任务覆盖 `tools` | 优先级：任务级 `tools` > 定义的白名单 > `DEFAULT_TOOLS` |
| 覆盖仍只读 | `strip_by_capability(tools, CAPABILITY_READONLY)`：写/命令工具在这一层就丢掉，`_filtered_registry` 再按运行时能力档过滤一层 |

📌 双重过滤不是冗余：真正生效的是 `_filtered_registry`，`allowed_tools` 只是任务元数据；但在这一层先收口，审计/显示时拿到的也是「实际可用」的清单，团队改出写权限时只需替换 capability 参数。

### 9.17 `_filtered_registry`（559-567）

```python
filtered = ToolRegistry()
for name in strip_by_capability(allowed, capability):
    tool_def = self.registry.get(name)
    handler = self.registry.handler(name)
    if tool_def is not None and handler is not None:
        filtered.register(tool_def, handler)
return filtered
```

- **定义与 handler 必须同时存在**才注册：只注册定义会让 `run_parallel` 走到「工具未实现」。
- 能力档在这里第二次生效：`capability` 默认 `CAPABILITY_READONLY`，团队成员传入自己的档位。
- 传播只读：`subagent_dispatch` 被剔除 → **不会递归派发**。
- handler 是**共享引用**（同一个函数对象），不是拷贝：子代理调 `read_file` 走的还是主 registry 的同一实现。

### 9.18 `_normalise_goals` / `_normalise_tools`（570-583）

```python
def _normalise_goals(raw):
    if isinstance(raw, str):  return [raw.strip()] if raw.strip() else []
    if isinstance(raw, list): return [str(i).strip() for i in raw if str(i).strip()]
    return []

def _normalise_tools(raw):
    if isinstance(raw, str):  return [p.strip() for p in raw.split(",") if p.strip()]
    if isinstance(raw, list): return [str(i).strip() for i in raw if str(i).strip()]
    return []
```

工具名支持 `"read_file, search_code"` 逗号分隔字符串——模型经常这么写。

---

## 10. 三层并发的数字关系

| 层级 | 并发度 | 来源 | 上限由谁控制 |
|---|---|---|---|
| 子代理 | `subagent_concurrency`（默认 3） | `ChatConfig` | `SubagentPool` 信号量 |
| 主代理工具 | `tool_concurrency`（默认 4） | `ChatConfig` | `ToolScheduler` 信号量 |
| 同一轮里的 `ask_user` | 1 | 硬编码 `INTERACTIVE_TOOLS` | 串行 for 循环 |

三层**可以叠加**：3 个子代理 × 每个子代理内部 4 个工具 = 最多 12 个工具同时在跑。这些工具大多是 `tool/invoke` 转到 Shell 执行，Shell 侧 `ToolDispatcher` 用的是**无界线程池**（`executeOnPooledThread`），所以 Core 的并发度就是实际压力上限。

## 11. 超时的七层

| 层 | 时长 | 位置 | 超时后果 |
|---|---|---|---|
| Shell 行内补全 | 1.5 s | `InlineCompletionClient` | 不显示 ghost text |
| Core 行内补全 | 12 s | `server._run_inline` | 返回 `{"text": ""}` |
| 单个工具 | `ToolDef.timeout_sec`（默认 30 s） | `scheduler` | `工具执行超时` |
| `subagent_dispatch` 整体 | 900 s | `SUBAGENT_TOOLS[0]` | 整条工具失败 |
| 单个子代理 | 240 s | `SubagentDefinition.timeout_sec` | 该子代理失败，其它不受影响 |
| MCP 确认 | 120 s | `server.CONFIRM_TIMEOUT` | 视为拒绝 |
| `ask_user` | 1800 s | `ask.py` + `server.ASK_TIMEOUT` | 返回 None + `ask/withdraw` |

⚠️ `ask_user` 的 1800 s **大于** `subagent_dispatch` 的 900 s，但 `ask_user` 不会出现在子代理的工具集里（`DEFAULT_TOOLS` 不含它），所以不会互相打架。

## 12. 八条易错点

1. **改「未知工具」文案必须两边同步**（`scheduler` 生成、`loop` 用 `startswith` 判定）。
2. **改 Shell 的拒绝文案必须同步 `DENIAL_PREFIXES`**，否则连续拒绝检测失效。
3. **`ask_user` 的 `index` 是数组下标**，不是题目 id。
4. **子代理默认只读**：定义级过滤 + `strip_by_capability` + 能力档声明三层；写/命令只能由团队成员按 capability 显式授予，且 `delete_file` 永不随能力档放开。
5. **`todo_write` 无法清空清单**（空数组会报错）。
6. **`Semaphore` 是每次 `run_parallel` 新建**，不要以为是全局的。
7. **压缩只发生在成功结果、且发生在存入 session 之前**——历史里没有原始长文本。
8. **状态栏用 role=USER 且必须在裁剪之后追加**，否则会被裁掉或破坏缓存前缀。

## 13. 自测清单

```powershell
cd core
python -m unittest tests.test_scheduler tests.test_commands tests.test_todos tests.test_status tests.test_subagent tests.test_ask
```

- 手工构造 `decide("git status && git push", [])`、`decide("cat a | sh")`、`decide("mvnw test")`，预测三个结果再跑。
- 读 `status.render` 的输出样例，解释「先过滤再截断」不给会怎样。
- 解释：为什么 `subagent_dispatch` 的 `ok` 是 `ok_count > 0` 而不是 `ok_count == len(reports)`。
- 在 `TurnStats` 里加一个「本轮最慢工具」字段，需要改哪几个函数？（提示：`record_result` 拿不到耗时，得改 `loop` 或 `scheduler`）

## 14. 下一卷

第 05 卷进入 LLM 层：`llm/base.py` 的 provider 协议与 `ChatResult`、`llm/streaming.py` 的 SSE 解析、`llm/transport.py` 的重试与退避、`llm/context.py` 的 `budget_for`/`trim_history`、`llm/tool_text.py` 的「无工具模型」降级、`llm/completion.py` 的 FIM 补全、`providers/ollama.py` 与 `providers/openai_compat.py` 的报文差异。
