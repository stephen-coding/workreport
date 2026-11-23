# 第 05 卷 · LLM 层：提供方、流式、重试与上下文预算

> 本卷读完 `core/src/agent_core/llm/` 下的 9 个文件，共 1,379 行：
>
> `__init__.py`(44) · `base.py`(178) · `streaming.py`(181) · `transport.py`(135) · `context.py`(116) · `tool_text.py`(175) · `completion.py`(148) · `providers/openai_compat.py`(206) · `providers/ollama.py`(196)
>
> 第 04 卷讲的是「Agent 怎么循环」，本卷讲的是「那一次 `provider.chat_stream(...)` 里面到底发生了什么」——**请求报文怎么拼、字节流怎么拆成「思考 / 正文 / 工具调用」三路、失败怎么读得懂、历史怎么裁到预算内**。

---

## 0. 本卷读什么

### 0.1 九文件的分工

| 文件 | 一句话职责 | 对外暴露 |
|---|---|---|
| `__init__.py` | 用**端点形状**决定用哪种协议，并造 provider | `detect_provider()`、`build_provider()` |
| `base.py` | 所有提供方共用的数据契约 + 端点「假释」状态 | `ChatResult`、`LLMProvider`、`TokenCallback`（正文与思考共用）、`is_tool_unsupported()`、`tools_suppressed()` 系列、4 个报文转换函数 |
| `streaming.py` | 两条流式响应状态机（思考 / 正文 / 工具调用）+ SSE 行解析 | `OpenAIStreamAccumulator`、`OllamaStreamAccumulator`、`parse_sse_line()` |
| `transport.py` | 超时、把异常翻成人话、有界重试 | `HTTP_TIMEOUT`、`describe_transport_error()`、`run_with_retries()` |
| `context.py` | 按 token 预算裁剪历史 | `budget_for()`、`trim_history()`、`messages_tokens()` |
| `tool_text.py` | 把模型写在正文里的工具调用挤出来 | `TextToolCallFilter`、`recover_text_tool_calls()` |
| `completion.py` | 编辑器幽灵文本（FIM 补全） | `complete()`、`sanitize()` |
| `providers/openai_compat.py` | OpenAI / DeepSeek / Qwen / vLLM 协议 | `OpenAICompatProvider` |
| `providers/ollama.py` | Ollama 原生 `/api/chat` 协议 | `OllamaProvider` |

### 0.2 依赖方向（谁 import 谁）

```
rpc/server.py ──┬─→ llm/__init__.py (build_provider)
                └─→ llm/completion.py (complete as complete_inline)

agent/loop.py ──┬─→ llm/base.py (ChatResult / TokenCallback)
                └─→ llm/context.py (budget_for / trim_history)

llm/__init__.py ─→ providers/openai_compat.py ─┐
                └→ providers/ollama.py ────────┤
                                               ├→ llm/base.py
                                               ├→ llm/streaming.py
                                               ├→ llm/tool_text.py
                                               └→ llm/transport.py

llm/context.py ─→ native/count_tokens
llm/base.py    ─→ protocol.py
llm/transport.py ─→ protocol.py (RpcError / ErrorCode)
```

两个值得注意的点：

1. **`llm/__init__.py` 在模块加载时就 import 了两个 provider**，而 provider 又 import `llm.base`。这不构成循环导入：`base / streaming / transport / tool_text` 都**不**反过来 import 包级属性（只 import 兄弟子模块），Python 允许父包初始化到一半时导入子模块。
2. **`llm/providers/` 里没有 `__init__.py`**（该目录只有 `ollama.py` 与 `openai_compat.py` 两个源文件）。它靠 Python 3.3+ 的**隐式命名空间包**工作；PyInstaller 之所以能打进去，是因为这些模块是被静态 import 的，会被依赖分析扫到。

### 0.3 本卷只关心三个问题

| 问题 | 答案落在 |
|---|---|
| 一次对话请求的**线上报文**长什么样？ | §2.6、§8.3、§9.3、§10 |
| 流式字节怎么变成「一段思考 + 一段文本 + 一组工具调用」？ | §3、§6、§8.4、§9.4 |
| 模型把思考过程放在哪个字段？为什么以前不显示？ | §2.1、§3.1–3.3 |
| 端点坏了 / 模型不支持工具 / 历史太长，分别怎么活下来？ | §2.4–2.5、§4、§5 |

---

## 1. `llm/__init__.py`（44 行）：用端点形状决定协议

### 1.1 为什么需要「探测」而不是「照配置来」

设置页允许用户直接粘贴一个完整端点。于是出现过这个事故（源码注释与测试都记着）：

> 类型选 `ollama`、端点填 `https://host/v1/chat/completions`，
> 拼出来变成 `https://host/v1/chat/completions/api/chat` → 404。

配置里的 `provider` 是**用户的理解**，端点 URL 是**客观事实**。当两者冲突时，代码选择相信 URL。

### 1.2 `_OLLAMA_SUFFIXES`（13）

```python
_OLLAMA_SUFFIXES = ("/api/chat", "/api/generate", "/api/embed", "/api/embeddings", "/api/tags")
```

这 5 个结尾是 Ollama 原生 API 的「指纹」。注意这里包含 `embed` / `embeddings` / `tags`——它们不是聊天端点，但仍然能**证明**这个 base_url 指向 Ollama，从而决定走 Ollama 协议。

### 1.3 `detect_provider`（16-38）逐分支

```python
configured = (config.provider or "").strip().lower()
base = (config.base_url or "").strip().lower().rstrip("/")
if not base:
    return configured or "ollama"
if any(base.endswith(suffix) for suffix in _OLLAMA_SUFFIXES):
    detected = "ollama"
elif "/chat/completions" in base or base.endswith("/v1"):
    detected = "openai_compat"
else:
    detected = "openai_compat" if configured == "openai_compat" else "ollama"
if configured and configured != detected:
    LOGGER.info("provider auto-detect: configured=%s endpoint=%s -> %s", ...)
return detected
```

判定顺序有**严格的优先级**，把它画成表：

| `base_url` | `provider` 配置 | 结果 | 命中分支 |
|---|---|---|---|
| 空 | 空 | `ollama` | ① 空端点兜底 |
| 空 | `openai_compat` | `openai_compat` | ① |
| `http://localhost:11434` | `ollama` | `ollama` | ④ 回落到配置 |
| `http://localhost:11434` | `openai_compat` | `openai_compat` | ④ |
| `http://localhost:11434/api/chat` | `openai_compat` | `ollama` | ② 端点否决配置 |
| `http://localhost:11434/api/tags` | `openai_compat` | `ollama` | ② |
| `https://api.deepseek.com/v1` | `ollama` | `openai_compat` | ③ 端点否决配置 |
| `https://host/v1/chat/completions` | `ollama` | `openai_compat` | ③ |
| `https://host/openai/v1/chat/completions?api-version=1` | `ollama` | `openai_compat` | ③（用 `in` 而不是 `endswith`） |

⚠️ **两个「不一致」要记住**：

1. 第 ③ 条用的是 `"/chat/completions" in base`，而第 ② 条用的是 `endswith`。这个不对称是刻意的：Azure 风格端点会把 `?api-version=...` 挂在后面，`endswith` 会失效。但**下游的 URL 拼接函数 `resolve_chat_url` 仍然用 `endswith`**，于是「探测为 openai、拼出来却多一层」的组合是存在的（§10 会再提一次）。
2. 只有 `configured != detected` 才打日志，且只是 **INFO**——用户界面上不会看到任何提示。所以「我明明选了 Ollama，为什么走 OpenAI 协议」这个问题，只能靠日志回答。

### 1.4 `build_provider`（41-44）

```python
def build_provider(config: ChatConfig):
    if detect_provider(config) == "openai_compat":
        return OpenAICompatProvider()
    return OllamaProvider()
```

每次调用都返回**新实例**。这一点很重要，因为 provider 上有一个实例字段 `notice`（一次性提示）。也正因为「每任务新建」，所有需要跨任务共享的状态（假释、提示去重）必须放在**模块级**——`base.py` 就是这么做的（§2.5），并有测试专门钉住。

---

## 2. `llm/base.py`（178 行）：数据契约与会「过期」的降级

### 2.1 两个回调类型 + 一个可选思考通道（11-16）

```python
TokenCallback = Callable[[str], Awaitable[None]]
ToolCallCallback = Callable[[ToolCall], Awaitable[None]]
# 思考过程的可选通道（13-16 行注释）：模型把它和正文分开流式下发，
# 这里单独转给 Shell，不混进正文。
```

都是 `async` 的：`on_token` 在流式循环里被 `await`，Shell 侧表现为一行 `chat/token` 通知。所以**任何一次网络写阻塞都会直接拖慢首字延迟**。

3.3.0 起，`TokenCallback` 被用两次——`chat_stream` 的末位多了一个可选参数：

```python
async def chat_stream(self, messages, tools, config,
                      on_token: TokenCallback,
                      on_tool_call: ToolCallCallback,
                      on_reasoning: Optional[TokenCallback] = None) -> ChatResult: ...
```

| 回调 | 送什么 | 去向 |
|---|---|---|
| `on_token` | **可见正文**（已过 `TextToolCallFilter`） | `chat/token` → 会话历史 |
| `on_reasoning` | **思考过程**（`reasoning_content` / `thinking`） | `chat/reasoning` → 只显示，不落库 |

- 思考**不进 `ChatResult.content`、不进会话历史、不计 usage**：它是「给人看的旁白」，不是对话内容。
- 末位 + 默认 `None`：既有调用方（`loop.py` 之外的测试、其他集成代码）不用改签名；传 `None` 时思考片段被直接丢弃（等于老行为）。

### 2.2 `ChatResult`（33-41）

| 字段 | 类型 | 语义 |
|---|---|---|
| `content` | `str` | 本轮**可见正文**（工具标记已被剥离） |
| `tool_calls` | `List[ToolCall]` | 本轮要求执行的工具（已解析成 dict） |
| `finish_reason` | `Optional[str]` | OpenAI 的 `stop` / `tool_calls` / `length`；Ollama 只会给 `stop` 或 `None` |
| `usage` | `Optional[Dict]` | **最后一轮**的用量 |
| `usage_total` | `Optional[Dict]` | **整轮任务所有模型请求之和** |

### 2.3 `usage` 与 `usage_total` 的双轨制

```python
# Sum over every model round of the turn. ``usage`` stays the last round so a
# Shell that has not been updated yet keeps working.
usage_total: Optional[Dict[str, Any]] = None
```

- `usage` 由 provider 填，可能为 `None`（端点不报）。
- `usage_total` **只有 `agent/loop.py` 会填**（`_add_usage` 累加 + `_usage_snapshot` 收尾），provider 永远不填。
- 保留 `usage` 是为了「旧 Shell 不改也能显示」——这是典型的**加法式协议演进**：新字段可选，旧字段语义不变。

> 🔎 顺带一个事实：`finish_reason` 在 Core 里**没有任何消费方**（`grep finish_reason` 只命中 provider 与 `streaming` 的赋值处）。`loop.py` 从不读它，`chat/finish` 也不下发。所以模型因为 `length` 被截断时，Core 不会做任何补救（§14 第 3 条）。

### 2.4 `is_tool_unsupported`（59-64）：把 400 分成两类

```python
if status_code not in (400, 422):
    return False
lowered = (body or "").lower()
return any(hint in lowered for hint in _TOOL_UNSUPPORTED_HINTS)
```

七个关键字（22-30）：

| 关键字 | 典型出处 |
|---|---|
| `tool_choice` | vLLM：`"auto" tool choice requires --enable-auto-tool-choice ...` |
| `tool-call-parser` | 同上（vLLM 启动参数名） |
| `enable-auto-tool-choice` | 同上 |
| `does not support tools` | Ollama：`... does not support tools` |
| `do not support tools` | 语法变体 |
| `tools are not supported` | 语法变体 |
| `function calling is not supported` | 某些云厂商 |

**为什么状态码要限 400/422？** 500 也可能是同一句话，但那代表服务端内部炸了，此时把工具摘掉继续跑只会掩盖故障。测试里 `is_tool_unsupported(500, VLLM_REJECT)` 明确断言为 `False`。

⚠️ `tool_choice` 这个关键字很宽：任何 400 里只要出现它（例如「invalid value for `tool_choice`」），都会被判成「端点不支持工具」。这是刻意用**宽匹配换恢复率**——误判的代价只是本轮没有工具，而不是整轮失败。

### 2.5 端点「假释」（67-112）

这是本卷最有意思的一段。历史事故：

> Core 进程曾**在整个生命周期内**记住第一次 400，于是运维把 vLLM 修好（重启并加上 `--enable-auto-tool-choice`）后，插件仍然只说纯对话，直到重启 IDE。

现在的设计是「**假释期**」：

```python
TOOLS_RETRY_SECONDS = 300.0

_tools_suppressed: Dict[Tuple[str, str], float] = {}   # (chat url, model) -> monotonic 截止时间
_notice_shown: Set[Tuple[str, str]] = set()             # 已经提示过的端点
```

四个函数的分工：

| 函数 | 做什么 | 关键细节 |
|---|---|---|
| `tools_suppressed(url, model)`（82-91） | 查询是否还在假释期 | **过期就顺手 `pop`**，没有后台清理任务 |
| `suppress_tools(url, model)`（94-96） | 开始 300 秒假释 | 截止时间 = `time.monotonic() + 300` |
| `accept_tools(url, model)`（99-103） | 结束假释 | 同时 `discard` 提示标记，让「再次降级」能再提示一次 |
| `take_tools_notice(url, model)`（106-112） | 一次性取走「要不要提示」 | 第一次 True，之后 False |

四个设计选择：

1. **key 是 `(url, model)` 而不是 `(url, provider)`**：同一个 Ollama 服务上，`llama3` 不支持工具、`qwen2.5` 支持。换模型就该重新试探。
2. **用 `time.monotonic()` 而不是 `time.time()`**：系统时间被校准/改时区时，单调时钟不会跳变。
3. **状态在模块级**：provider 每任务新建，实例状态活不过一次任务；但假释必须跨任务共享。
4. **`accept_tools` 是「成功即恢复」**：只要一次带工具的请求成功，就立刻清空假释与提示标记（测试 `test_accept_tools_clears_probation_and_notice_flag` 钉住）。

状态机：

```
         首次 400(工具相关)
   ┌──────────────────────────────┐
   │  tools_suppressed = True     │
   │  deadline = now + 300s       │
   │  notice 取走一次             │
   └──────────────┬───────────────┘
        300s 内   │   300s 后
   每任务不带 tools│  重新带 tools 探测
                  ├─ 成功 → accept_tools() → 回正常
                  └─ 又 400 → suppress_tools() → notice 不再重复
```

### 2.6 四个报文转换函数（115-178）

#### 2.6.1 `to_openai_messages`

```python
if m.role == Role.TOOL:
    item["content"] = m.content or ""
    item["tool_call_id"] = m.tool_call_id or ""
else:
    item["content"] = m.content          # 可能是 None

if m.tool_calls:
    item["tool_calls"] = [{"id": ..., "type": "function",
                           "function": {"name": ..., "arguments": json.dumps(..., ensure_ascii=False)}}]
```

要点：

- `tool` 消息**必须**带 `tool_call_id`，且必须能对应到前面某条 assistant 的 `tool_calls[].id`——这就是 §5 里 `trim_history` 必须整轮丢弃、并去掉开头孤立 `tool` 的原因。
- assistant 的 `content` 允许为 `None`（只调工具不说话），JSON 里就是 `"content": null`。OpenAI 接受，严格的第三方实现可能不接受，代码没有为此做兜底。
- `arguments` 用 `ensure_ascii=False`：保留中文，不变成 `\uXXXX`（既省 token，也让日志可读）。

#### 2.6.2 `to_ollama_messages`

```python
item = {"role": m.role.value, "content": m.content or ""}
if m.tool_calls:
    item["tool_calls"] = [{"function": {"name": tc.name, "arguments": tc.arguments}} for tc in m.tool_calls]
```

#### 2.6.3 两家报文的逐字段差异

| 维度 | OpenAI 兼容 | Ollama |
|---|---|---|
| `content` 为 `None` | 输出 `null` | 输出 `""` |
| `tool` 消息的配对键 | `tool_call_id`（必需） | 无（按顺序对应） |
| `tool_calls[].id` | 有，且回传时要用 | **不发**（发了也不认） |
| `tool_calls[].type` | `"function"` | 不发 |
| `arguments` 形态 | **JSON 字符串** | **对象** |
| 消息里是否带 `name` | 不带 | 不带 |

> ⚠️ 最容易踩的坑：把 `arguments` 当字符串发给 Ollama，或当对象发给 OpenAI。两家的「正确形态」正好相反，而这两段代码看起来几乎一样。

#### 2.6.4 `to_openai_tools` 与 `to_ollama_tools`

两个函数**逐字相同**（153-178）：

```python
{"type": "function",
 "function": {"name": t.name, "description": t.description,
              "parameters": t.parameters or {"type": "object", "properties": {}}}}
```

刻意重复而不是抽一个公共函数：如果将来某家改了 schema（例如 Ollama 后来的 `function.index`），两处独立演进不会互相牵连。`parameters` 的兜底同样重要——`ToolDef.parameters` 默认是 `{}`，而两家都要求至少是 `{"type": "object", "properties": {}}`。

---

## 3. `llm/streaming.py`（181 行）：两条流式状态机

### 3.1 两家的流式形状差异（文件头注释）

| | OpenAI 兼容 | Ollama |
|---|---|---|
| 传输格式 | SSE：`data: {...}` + `data: [DONE]` | NDJSON：一行一个 JSON（也有代理加 `data:`） |
| 文本位置 | `choices[0].delta.content` | `message.content` |
| **思考位置** | `choices[0].delta.reasoning_content`（新解析器用 `delta.reasoning`） | `message.thinking` |
| 工具调用 | `delta.tool_calls` **增量数组**，按 `index` 分槽，`arguments` 是**字符串片段** | `message.tool_calls` **每次都是完整对象**，可能重复 |
| 结束信号 | `finish_reason` + `[DONE]` | `done: true` |
| 用量 | 末尾一个 `choices: []` 的 chunk，带 `usage` | 末尾 chunk 带 `prompt_eval_count` / `eval_count` |

⚠️ 思考与正文住在**同一个 chunk 的两个不同字段**里，所以两个累加器都把它拆成两步取：`reasoning_delta(chunk)` 先取思考，`feed(chunk)` 再取正文与工具调用（§8.4、§9.4）。两个方法各自累加，互不干扰。

### 3.2 `OpenAIStreamAccumulator`（22-107）

字段：

```python
self._content_parts: List[str] = []
self._reasoning_parts: List[str] = []       # 3.3.0 新增：思考单独累加
self._slots: Dict[int, Dict[str, str]] = {}   # index -> {"id","name","args"}
self.finish_reason: Optional[str] = None
self.usage: Optional[Dict[str, Any]] = None
```

#### `reasoning_delta`（32-48）

```python
choices = chunk.get("choices") or []
if not choices:
    return ""
delta = choices[0].get("delta") or {}
piece = delta.get("reasoning_content") or delta.get("reasoning") or ""
if not isinstance(piece, str) or not piece:
    return ""
self._reasoning_parts.append(piece)
return piece
```

三个要点：

1. **与 `feed` 分开取**：`feed` 的契约是「返回新增正文 token」，这个契约被既有测试钉住，所以思考不往 `feed` 里塞。
2. **两个字段名都要认**：vLLM 的 `--reasoning-parser`（deepseek_r1 / qwen3…）、DeepSeek 官方与多数网关用 `reasoning_content`；较新的解析器改叫 `reasoning`。
3. **这正是「思考展示不全」的根因**：3.3.0 之前这里完全不读这两个字段——模型思考了两千字，Shell 一个字都拿不到，正文里当然也没有（它走的是另一个字段）。

#### `feed`（50-76）

```python
if chunk.get("usage"):
    self.usage = chunk["usage"]          # ① 先取用量
choices = chunk.get("choices") or []
if not choices:
    return tokens                       # ② 用量尾包没有 choices，直接返回
choice = choices[0]                     # ③ 只认第 0 个
delta = choice.get("delta") or {}
piece = delta.get("content")
if piece:
    self._content_parts.append(piece); tokens.append(piece)
for tc in delta.get("tool_calls") or []:
    idx = int(tc.get("index", 0))
    slot = self._slots.setdefault(idx, {"id": "", "name": "", "args": ""})
    if tc.get("id"):  slot["id"] = tc["id"]          # ④ 覆盖
    if fn.get("name"): slot["name"] = fn["name"]      # ④ 覆盖
    if fn.get("arguments"): slot["args"] += fn["arguments"]   # ⑤ 追加
if choice.get("finish_reason"):
    self.finish_reason = choice["finish_reason"]
```

四个细节：

1. **`usage` 必须最先取**：`stream_options: {"include_usage": true}` 会让服务端在最后追加一个 `choices: []` 的包，里面只有 `usage`。若先判 `choices` 为空就 return，用量永远拿不到。
2. **`id` / `name` 是覆盖，`arguments` 是追加**：id 和 name 只在第一片出现（有些服务端每片都发；覆盖等价幂等），而参数 JSON 是**逐片切开**的。
3. **`int(tc.get("index", 0))`**：缺 index 的实现（少数）会全部落到 0 号槽，多个工具调用会被拼接成一坨坏 JSON。代码没有对这种情况做补救。
4. **`_slots` 是 dict，`finalize` 用 `sorted(self._slots)`**：保证工具按模型给的**顺序**执行，而不是插入顺序（两者通常一致，但明确排序更稳）。

#### `finalize`（78-99）

```python
for idx in sorted(self._slots):
    raw = slot["args"].strip()
    args = {}
    if raw:
        try:
            parsed = json.loads(raw)
            args = parsed if isinstance(parsed, dict) else {"_value": parsed}
        except json.JSONDecodeError:
            LOGGER.warning("tool call %s has non-JSON arguments", slot["name"])
            args = {"_raw": raw}
    calls.append(ToolCall(id=slot["id"] or "call_%d" % idx, name=slot["name"] or "", arguments=args))
```

参数解析有**四个出口**：

| 情况 | `arguments` | 后果 |
|---|---|---|
| 空串 | `{}` | 工具按默认值执行（多数工具会报「缺少参数」） |
| 合法 JSON 对象 | 原样 | 正常 |
| 合法 JSON 但不是对象（数组/数字） | `{"_value": ...}` | 工具通常报未知参数 |
| 坏 JSON（被截断） | `{"_raw": "..."}` | 工具报缺少参数；日志有一条 warning |

`id` 兜底 `call_%d`、`name` 兜底 `""`：名字为空时后续会命中「未知工具」，进而触发 `loop` 的未知工具计数（§04 卷 §1.10）。`content` 与 `reasoning` 是只读属性（101-107），分别拼正文与思考。

### 3.3 `OllamaStreamAccumulator`（110-173）

```python
msg = chunk.get("message") or {}
piece = msg.get("content")
if piece: ...
for tc in msg.get("tool_calls") or []:
    name = fn.get("name") or ""
    args = fn.get("arguments") or {}
    if isinstance(args, str):        # 有些版本给 JSON 字符串
        try: parsed = json.loads(args); args = parsed if isinstance(parsed, dict) else {"_value": parsed}
        except json.JSONDecodeError: args = {"_raw": args}
    if not isinstance(args, dict): args = {"_value": args}
    if any(c.name == name and c.arguments == args for c in self._tool_calls):
        continue                     # ← 去重
    self._tool_calls.append(ToolCall(id=tc.get("id") or "call_%d" % len(self._tool_calls), ...))
if chunk.get("done"):
    self.done = True
    if chunk.get("prompt_eval_count") is not None or chunk.get("eval_count") is not None:
        self.usage = {"in": chunk.get("prompt_eval_count"), "out": chunk.get("eval_count")}
```

三个差异点：

1. **去重靠 `(name, arguments)` 全等比较**，O(n²) 但工具调用数量个位数，无所谓。为什么必须去重：部分 Ollama 版本/代理会把同一个 `tool_calls` 在每个 chunk 里重发一次，不去重就等于让模型调用 N 次。
2. **`done` 与 `usage` 绑定**：Ollama 只在最后一个包给计数。注意 `usage` 里的值**可能是 `None`**（只给了一个计数时），下游 `_int(None)` 会变 0——见 §12。
3. **`id` 兜底是 `call_%d % len(...)`**：取决于加入顺序，所以同一响应重复解析结果稳定，但**跨响应**可能重名。因为 Core 只在一次任务内用 id 配对，这没问题。

#### `reasoning_delta`（120-127）

```python
msg = chunk.get("message") or {}
piece = msg.get("thinking") or ""
```

- 只有开启思考的模型（qwen3、deepseek-r1 等，请求里带 `think`）才会下发 `message.thinking`；普通模型永远返回 `""`。
- 同样只累加进 `_reasoning_parts`，**不参与 `content`、不参与工具解析**。

### 3.4 `parse_sse_line`（176-181）

```python
stripped = line.strip()
if not stripped.startswith("data:"):
    return None
return stripped[5:].strip()
```

- 只认 `data:` 前缀，`event:` / `:`（心跳注释）/ 空行一律 `None`。
- `[5:]` 直接切掉 `data:`，所以 `data:{...}`（无空格）也能用。
- **不含 `[DONE]` 判断**：那属于「某个协议怎么结束」，放在 provider 里（OpenAI 判断 `payload == "[DONE]"`；Ollama 用 `done` 字段）。

---

## 4. `llm/transport.py`（135 行）：把异常写成人话

### 4.1 四个超时（18-24）

```python
CONNECT_TIMEOUT = 10.0
READ_TIMEOUT    = 180.0
WRITE_TIMEOUT   = 30.0
POOL_TIMEOUT    = 10.0
HTTP_TIMEOUT = httpx.Timeout(connect=..., read=..., write=..., pool=...)
```

| 项 | 值 | 为什么是这个数 |
|---|---:|---|
| connect | 10 s | 本机 Ollama 秒连；云端点也要在 10 s 内握手 |
| read | 180 s | 长提示词 + 排队 + 慢速生成；比 30 s 宽松得多 |
| write | 30 s | 请求体可能带整段历史 |
| pool | 10 s | 每次请求都新建 `AsyncClient`，连接池基本不排队 |

### 4.2 `describe_transport_error`（39-70）

文件头注释解释了它存在的理由：

> httpcore 构造 `ReadTimeout` / `ReadError` / `ConnectError` 时**不带 message**，
> 于是 Shell 上出现过一行只有 `[错误:LLM_ERROR] ` 后面什么都没有。

函数**完全不依赖 `str(exc)`**（除最后一个兜底分支），而是按类型分支造文案：

| 异常类型 | 文案要点 | 用户下一步 |
|---|---|---|
| `TimeoutException` → 由 `_timeout_phase` 再细分 | 「LLM 请求超时：<端点>（模型 x）在**建立连接/发送请求/获取连接/等待模型输出**阶段超过 N 秒没有响应」+ 解释首字延迟 + 建议重发「继续」/调小 max_tokens | 重发「继续」 |
| `ConnectError` | 「无法建立连接」+ 检查地址/端口/服务/代理/防火墙 | 修端点后重发 |
| `NetworkError`（Read/Write/Close Error） | 「连接中断：在传输响应时被断开」+ 服务端重启/超时/闪断 | 重发「继续」 |
| `ProtocolError` | 「响应协议错误：数据不完整或无法解析」 | 重发「继续」 |
| 其它 | 「LLM 网络错误（类名）：端点；detail 或提示」 | — |

`_timeout_phase`（29-36）的顺序也重要：

```python
if isinstance(exc, httpx.ConnectTimeout): return "建立连接", CONNECT_TIMEOUT
if isinstance(exc, httpx.WriteTimeout):   return "发送请求", WRITE_TIMEOUT
if isinstance(exc, httpx.PoolTimeout):    return "获取连接", POOL_TIMEOUT
return "等待模型输出", READ_TIMEOUT       # ReadTimeout 与"其它超时"共用
```

因为 `ConnectTimeout` / `WriteTimeout` / `PoolTimeout` 都是 `TimeoutException` 的子类，必须**先判子类**，否则全被最后一行吃掉。

### 4.3 有界重试（73-135）

```python
RETRYABLE_STATUS = frozenset({429, 500, 502, 503, 504})
MAX_RETRIES = 2
BACKOFF_SEC = (0.5, 1.5)
```

为什么这五个状态码可重试：429 限流（等一会儿可能就好）、500/502/503/504 都是「服务端临时不可用」。**401 / 403 / 404 / 400 一律不重试**——重试也没有意义，只会拖慢报错。

`is_retryable`（94-99）：

```python
if isinstance(exc, RetryableStatus): return True
return isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout, httpx.ReadTimeout))
```

**注意这里没有 `ReadError` / `WriteError`**：

- `ReadError` 意味着响应已经开始又断了，重放可能造成重复副作用（虽然此时 `started()` 也会拦住）。
- `WriteTimeout` 意味着请求可能已经被服务端收到，不幂等。

`run_with_retries`（116-135）的控制流：

```python
retries = 0
while True:
    try:
        return await attempt()
    except Exception as exc:
        if not is_retryable(exc):
            if isinstance(exc, httpx.TransportError):
                raise describe(exc, 0) from exc     # ← 仍是可读文案
            raise                                   # ← 非传输错误，原样抛
        if started() or retries >= MAX_RETRIES:
            raise describe(exc, retries) from exc
        await asyncio.sleep(BACKOFF_SEC[retries])
        retries += 1
```

三个出口与一个分支：

| 情形 | 行为 |
|---|---|
| 成功 | 返回 `attempt()` 结果 |
| 可重试且**尚未输出**且还有额度 | 等 0.5 s → 重试；再失败等 1.5 s → 重试 |
| 可重试但**已输出** | 立即抛可读错误（**绝不重放**） |
| 可重试但**额度用尽**（共 3 次尝试） | 抛可读错误，文案带「已自动重试 2 次」 |
| 不可重试的**传输**错误 | 也翻成可读错误（因为 `str(exc)` 是空的） |
| 非传输、不可重试 | 原样抛出（例如 `_ToolRejected` 二次抛出、JSON 解析错误） |

`BACKOFF_SEC[retries]` 不会越界：能走到 `sleep` 说明 `retries < MAX_RETRIES = 2`，索引只能是 0 或 1。

📌 **`started()` 是调用方传进来的闭包**，在 provider 里它读的是 `delivered` 变量（§8.3）。这个词的字面意思是「已经交付给用户了」。它的实际赋值时机是「**收到了任意一个流式片段**」，哪怕该片段被工具标记过滤器整个吞掉（§6.5）——这是保守方向，宁可少重试一次，也不冒重复文本的风险。

### 4.4 `describe_failure`（102-113）

```python
if isinstance(exc, RetryableStatus):
    suffix = "，已自动重试 %d 次" % retries if retries else ""
    text = ("LLM 端点暂时不可用：%s（模型 %s）返回 HTTP %d%s。"
            "服务端重启、限流或过载都会这样；稍后重发一次「继续」即可。" % (...))
    return RpcError(ErrorCode.LLM_ERROR, text)
error = describe_transport_error(exc, url, model)
if retries:
    return RpcError(error.code, str(error) + "（已自动重试 %d 次）" % retries)
return error
```

两件事值得注意：

1. **HTTP 状态错误与传输错误是两套文案**：前者说「HTTP 503，端点暂时不可用」，后者说「连接中断/超时」。用户能据此判断是自己网络问题还是服务端问题。
2. `str(error)` 在 `RpcError` 上返回的就是 message，所以「追加后缀」是安全的（`RpcError.__init__` 把 message 传给了 `Exception`）。

---

## 5. `llm/context.py`（116 行）：预算与裁剪

### 5.1 四个常量（13-16）

```python
DEFAULT_BUDGET = 24000  # conservative budget for 8k-32k window models
BUDGET_RATIO = 0.75     # keep 25% of the window for the reply and tool output
MIN_BUDGET = 4000
MAX_BUDGET = 200000
```

历史教训（注释原文）：**写死 24000 会浪费 128k 窗口、又会撑爆 8k 窗口**。

### 5.2 `budget_for`（19-33）

```python
try: window = int(context_window or 0)
except (TypeError, ValueError): return DEFAULT_BUDGET
if window <= 0: return DEFAULT_BUDGET
return max(MIN_BUDGET, min(int(window * BUDGET_RATIO), MAX_BUDGET))
```

| 输入 `context_window` | 输出 | 说明 |
|---:|---:|---|
| `None` / `0` / `"abc"` | 24000 | 未配置/配错 → 默认 |
| `1000` | 4000 | 下限夹紧（不把提示词饿死） |
| `8000` | 6000 | 8k 模型留 25% 给回复 |
| `32000` | 24000 | 恰好等于 `DEFAULT_BUDGET`（巧合，但有测试钉住） |
| `128000` | 96000 | 大窗口不再被浪费 |
| `1000000` | 200000 | 上限夹紧（不向端点要超出其容量的量） |

> 📌 注意 `budget_for` 的入参在 `ChatConfig` 里叫 `context_window`，而 Shell 侧设置项叫 `contextWindow`，`settings/update` 的键是 `context_window`——这三处名字必须一致，否则 Core 永远拿默认值（第 02 卷 §7.4、第 01 卷 §3.4 都有这张对齐表）。

### 5.3 `estimate_tokens` 与 `_message_tokens`

```python
def estimate_tokens(text: str) -> int:
    return count_tokens(text or "")

def _message_tokens(message: Message) -> int:
    total = estimate_tokens(message.content or "")
    for call in message.tool_calls or []:
        total += estimate_tokens(call.name) + estimate_tokens(str(call.arguments))
    return total
```

- `count_tokens` 来自 `agent_core.native`：优先用 Rust/tiktoken 扩展，否则退化成字符启发式（第 07 卷讲 native）。
- `str(call.arguments)` 而不是 `json.dumps`：省一次序列化，估算误差可接受（dict 的 `str` 与 JSON 长度接近）。
- **工具调用的名字和参数都算 token**：一次 `write_file` 的 `content` 可能几千 token，如果只算 `content` 字段，预算会被严重低估。

### 5.4 `trim_history` 的四段文档（51-73）

docstring 里写了四件事，每件都对应一个真实故障：

| 论点 | 故障现象 |
|---|---|
| 「轮」要整体丢，带 `tool_calls` 的 assistant 必须和后面的 tool 结果一起丢 | 否则留下孤立 tool 消息 → 端点 400 `tool message must follow a tool_calls message` |
| 先丢历史、再丢本轮工具轮，**user 提问永不丢** | 整段没有 user 角色时 vLLM 返回 400 `No user query found in messages.` |
| 每条消息**只分词一次**，之后只做减法 | 每丢一轮就重算全量 → 150 轮要 40 s，同步阻塞事件循环 → 「聊久了换什么模型都卡死」 |
| 不要以孤立的 `tool` 结果开头 | 前缀被裁掉后，第一条就是 tool 结果 |

### 5.5 实现逐段

```python
system = [m for m in messages if m.role.value == "system"]
rest   = [m for m in messages if m.role.value != "system"]
counts = [_message_tokens(message) for message in rest]          # ← 只算一次
total  = sum(_message_tokens(message) for message in system) + sum(counts)
if total <= budget:
    return list(messages)
```

⚠️ 快路径 `return list(messages)` 保持**原始顺序**；慢路径返回 `system + kept`，会把所有 system 消息**提到最前**。正常调用方（`loop.py`）本来就把 system 放在第 0 位，所以两者等价；但如果将来有人在中间插 system，行为会变。

#### Pass 1（84-94）：整轮整轮地丢旧对话

```python
if rest and rest[0].role.value == "user":
    turn_starts = [i for i, m in enumerate(rest) if m.role.value == "user"]
    kept_turns = 0
    while total > budget and len(turn_starts) - kept_turns >= 2:
        next_start = turn_starts[kept_turns + 1]
        for i in range(cut, next_start):
            removed[i] = True
            total -= counts[i]
        cut = next_start
        kept_turns += 1
```

- 「轮」的边界 = **下一条 user 消息**。所以一轮 = `user + assistant(+tool_calls) + tool...`。
- `len(turn_starts) - kept_turns >= 2`：**永远保留最新一轮**，只丢更早的。
- 变量名 `kept_turns` 其实表示「已丢弃的轮数」（`kept_turns` 递增 = `cut` 后移），读代码时别被名字骗了。
- 前提是 `rest[0]` 是 user。`loop.py` 一定会先 `append(USER)`，所以正常成立；从磁盘恢复的会话若以 assistant 开头，Pass 1 直接跳过，全部交给 Pass 2。

#### Pass 2（96-109）：在「最新一轮」内部丢最旧的工具轮

```python
index = cut
while total > budget and index < len(rest):
    if removed[index] or rest[index].role.value == "user":
        index += 1
        continue
    end = index + 1
    if rest[index].tool_calls:
        while end < len(rest) and rest[end].role.value == "tool":
            end += 1
    for i in range(index, end):
        removed[i] = True
        total -= counts[i]
    index = end
```

- **跳过 user**：这是「提问永不丢」的落点。
- **带 `tool_calls` 的 assistant 会连带后面连续的 tool 消息一起删**，保证不成孤儿。
- 普通 assistant 消息单独删（`end = index + 1`）。
- 注意它从 `cut` 开始（Pass 1 丢弃区之前已跳过），不会重复减少 `total`。

#### 收尾（111-116）

```python
kept = [m for i, m in enumerate(rest) if not removed[i]]
while kept and kept[0].role.value == "tool":
    kept.pop(0)
return system + kept
```

最后这个 `while` 是第二道保险：即使 Pass 2 因为某种原因留下开头的 tool 消息，也会被剥掉。

### 5.6 两个可手算的示例

**示例 A（多轮，预算 2500）**：`sys=100`、`u1=800`、`round1=1000`、`u2=800`、`round2=1200`，`total=3900`。

| 步骤 | 动作 | `total` | 结果 |
|---|---|---:|---|
| 初始 | — | 3900 | 超预算 |
| Pass 1 第 1 次 | `turn_starts=[0,3]`，`2-0>=2` → 删 `rest[0..2]`（u1+round1） | 2100 | ≤ 2500，**停** |
| 结果 | — | — | `sys + u2 + round2` |

最新一轮**整轮保留**，旧的一轮整轮消失——不会出现「u1 还在、round1 没了」的孤儿组合。

**示例 B（单轮，预算 2500）**：`sys=100`、`u=800`、三轮各 1000，`total=3900`。

| 步骤 | 动作 | `total` |
|---|---|---:|
| Pass 1 | `turn_starts` 只有 1 个，`1-0 < 2` → 不动 | 3900 |
| Pass 2 第 1 次 | 删 round1 | 2900 |
| Pass 2 第 2 次 | 删 round2 | 1900 |
| 结果 | `sys + u + round3` | 1900 |

注意 `u` 始终被跳过；即使预算再小，`sys + u` 也会保留下来——**超预算也好过整段请求没有 user 角色**。

📌 记住结论：**裁剪只保证不留下孤立的 tool 消息、不丢掉 user 提问，不保证一定小于预算**（全是 user 消息时无计可施）。

---

## 6. `llm/tool_text.py`（175 行）：把正文里的工具调用挤出来

### 6.1 要解决的问题

有些端点**从不返回 `delta.tool_calls`**：

- vLLM 启动时没加 `--tool-call-parser`；
- Ollama 构建版本不带工具支持；
- 模型自己的 chat template 把调用**打印进正文**。

于是调用变成一段 XML 出现在聊天气泡里，工具一个都没执行：

```
<tool_call>
<function=read_file>
<parameter=path>
src/main.py
</parameter>
</function>
</tool_call>
```

这个模块做两件事：

| 类/函数 | 时机 | 作用 |
|---|---|---|
| `TextToolCallFilter` | **流式过程中** | 把标记从 token 流里吞掉，用户看不到 XML |
| `recover_text_tool_calls` | **流式结束后** | 从累积正文里解析出真正的 `ToolCall` |

### 6.2 常量与正则（32-43）

```python
_CALL_OPEN  = "<tool_call>"
_CALL_CLOSE = "</tool_call>"
_FUNC_OPEN  = "<function="
_FUNC_CLOSE = "</function>"

_CALL_BLOCK_RE = re.compile(re.escape(_CALL_OPEN) + r"(.*?)" + re.escape(_CALL_CLOSE), re.S)
_FUNC_RE  = re.compile(r"<function=([^>\s]+)>(.*?)</function>", re.S)
_PARAM_RE = re.compile(r"<parameter=([^>\s]+)>(.*?)</parameter>", re.S)
_INT_RE   = re.compile(r"^-?\d+$")

_HOLD = max(len(_CALL_OPEN), len(_FUNC_OPEN)) - 1   # = 11 - 1 = 10
```

- 全部用 `re.S`（`.` 匹配换行）：参数值里本来就有换行。
- `([^>\s]+)`：名字里不允许出现 `>` 或空白，避免把 `<function=a b>` 这类畸形当成名字。
- `_HOLD = 10` 的推导：最长的「可能是标记开头」的前缀是 `<tool_call>`（11 字节），所以要**扣住最后 10 个字符**，等下一片来了再判断。

### 6.3 四个小函数

#### `_coerce`（46-53）：把字符串还原成 JSON 标量

```python
text = value.strip()
if _INT_RE.match(text):            return int(text)
if text.lower() in ("true", "false"): return text.lower() == "true"
return value
```

| 输入 | 输出 |
|---|---|
| `"12"` | `12`（int） |
| `"-3"` | `-3` |
| `"true"` / `"True"` | `True` |
| `"1.5"` | `"1.5"`（**不做 float**） |
| `"a.java"` | `"a.java"` |

⚠️ 不做 float 是有意的：`1.5` 这类值在工具参数里几乎不出现，把 `"1.5"` 变 float 反而会让 `write_file` 的正文里偶然的 `1.5` 变成数字，回写时与原文不一致。

#### `_param_value`（56-64）：参数值去掉「包裹用的那一个换行」

```python
value = raw.replace("\r\n", "\n")
if value.startswith("\n"): value = value[1:]
if value.endswith("\n"):   value = value[:-1]
return value.strip() if "\n" not in value else value
```

| 原始片段 | 结果 | 说明 |
|---|---|---|
| `"\nsrc/main.py\n"` | `src/main.py` | 单行 → 两端全 strip |
| `"\n第一行\n第二行\n"` | `第一行\n第二行` | 多行 → **保留内部缩进** |
| `"  缩进\n  更多  "` | 原样（除首尾各一个 `\n`） | 多行不 strip，缩进是内容 |

📌 这条规则专为 `write_file` / `edit_file` 的 `content` 参数设计：多行正文的缩进**必须**保留，否则写进去的代码会全体左移。

#### `_make_call`（67-69）：id 必须每轮唯一

```python
return ToolCall(id="txt_" + uuid.uuid4().hex[:10], name=name.strip(), arguments=args)
```

Core 用 `tool_call_id` 给「等待 Shell 回传的 future」做键（第 03 卷 §16）。模型文案里的调用**没有 id**，所以必须自己造一个：前缀 `txt_` 便于日志区分「模型原生调用」与「文本恢复调用」，10 位 hex 足够避免一次任务内的碰撞。

#### `_parse_block`（72-104）：两种形状，XML 优先

```python
for match in _FUNC_RE.finditer(block):
    args = {m.group(1).strip(): _coerce(_param_value(m.group(2)))
            for m in _PARAM_RE.finditer(match.group(2))}
    if match.group(1).strip(): calls.append(_make_call(match.group(1), args))
if calls:
    return calls                     # ← XML 命中就不再试 JSON
text = block.strip()
if not text: return calls
try: parsed = json.loads(text)
except json.JSONDecodeError:
    LOGGER.warning("unparsed text tool call: %r", block[:200]); return calls
for item in parsed if isinstance(parsed, list) else [parsed]:
    ...
```

JSON 形状的宽容度（90-103）：

| 变体 | 处理 |
|---|---|
| `{"name":..., "arguments":{...}}` | 标准 |
| `{"tool":..., "arguments":{...}}` | `name` 取 `name` 或 `tool` |
| `{"name":..., "parameters":{...}}` | `arguments` 缺失时退化到 `parameters` |
| `"arguments": "{\"path\":\"a\"}"` | 字符串再 `json.loads`；失败变 `{"_raw": ...}` |
| 顶层是数组 | 逐个当作 item |
| 顶层不是 dict（如 `[1,2]`） | 跳过 |
| 解析失败 | warning + 放弃（**不会**生成半残调用） |

### 6.4 `recover_text_tool_calls`（107-122）

```python
if not content or (_CALL_OPEN not in content and _FUNC_OPEN not in content):
    return content, []                     # 快路径：普通回答零成本

calls = []
for match in _CALL_BLOCK_RE.finditer(content):
    calls.extend(_parse_block(match.group(1)))
cleaned = _CALL_BLOCK_RE.sub("", content)

if _FUNC_OPEN in cleaned:                  # 忘了包 <tool_call> 也算
    calls.extend(_parse_block(cleaned))
    cleaned = _FUNC_RE.sub("", cleaned)

return cleaned.strip(), calls
```

- **快路径很重要**：99% 的回答不含标记，一次 `in` 判断就返回，不做正则扫描。
- 先处理带 `<tool_call>` 包裹的块，再处理「裸」的 `<function=...>`。
- 返回的正文是 `cleaned.strip()`——前导/尾随空白被去掉，中间的空行保留。

⚠️ 一个未被处理的边角：如果模型把 `<parameter=...>` 写在**任何 `</function>` 之外**，`_FUNC_RE` 删不掉它，用户会在气泡里看到残留的 `<parameter=...>`。

### 6.5 `TextToolCallFilter`（125-175）：跨块的流式过滤器

三个字段：

```python
self._pending = ""        # 还没决定能不能显示的尾巴
self._in_block = False    # 是否正处在一个标记块内部
self._closer = _CALL_CLOSE  # 当前块要用哪个结束符
```

#### `feed`（137-160）

```python
if piece: self._pending += piece
while True:
    if self._in_block:
        end = self._pending.find(self._closer)
        if end < 0: return out                  # 块还没结束，全部扣住
        self._pending = self._pending[end + len(self._closer):]
        self._in_block = False
        continue
    index = self._marker_index()
    if index < 0:
        if len(self._pending) > _HOLD:
            cut = len(self._pending) - _HOLD
            out.append(self._pending[:cut])
            self._pending = self._pending[cut:]
        return out
    if index:
        out.append(self._pending[:index]); self._pending = self._pending[index:]
    self._closer = _FUNC_CLOSE if self._pending.startswith(_FUNC_OPEN) else _CALL_CLOSE
    self._in_block = True
```

要点：

1. **`while True` 而不是 `if`**：一个 chunk 里可能「文本 + 块 + 文本」都齐了，处理完块要回到循环继续放文本。
2. **`_in_block` 期间只找结束符**，块内的一切（包括 `</function>`）都不输出。
3. **`index > 0` 才输出前缀**（`if index:`），`index == 0` 说明标记就在开头。
4. **`_closer` 按起始标记决定**：`<function=` 用 `</function>`，`<tool_call>` 用 `</tool_call>`——所以裸 function 块也能被完整吞掉。
5. **`_HOLD` 只扣最后 10 个字符**，其余立刻放行，保证流式观感。

#### `_marker_index`（172-175）

```python
hits = [self._pending.find(marker) for marker in (_CALL_OPEN, _FUNC_OPEN)]
hits = [hit for hit in hits if hit >= 0]
return min(hits) if hits else -1
```

取**最早**出现的标记，保证「先出现的先处理」。

#### `flush`（162-170）

```python
rest, self._pending = self._pending, ""
if self._in_block:
    self._in_block = False
    return []          # 模型中途停了：半个标签也不显示
return [rest] if rest else []
```

与解析器保持一致：`flush` 丢掉未闭合块，`recover_text_tool_calls` 也解析不出它，两边都不会把它当工具，也不会把半截标签塞给用户。

#### 一次逐字符的推演

输入（逐字符喂）：`看<tool_call>\n<function=read_file>...(省略)...</tool_call>完`

| 时刻 | `_pending` | 动作 |
|---|---|---|
| 收到 `看` | `看` | 无标记，长度 ≤ 10，扣住 |
| 累积到 11 字符 | `看<tool_call` | 无完整标记，长度 11 > 10 → 输出 `看`，扣住 `<tool_call` |
| 收到 `>` | `<tool_call>` | `index=0` → 进入块，`_closer=</tool_call>` |
| 块内任意字符 | `...` | 找不到 closer → 一直扣住（可能有几百字节） |
| 收到 `</tool_call>` | `</tool_call>完` | 切掉 closer → 退出块 → 循环再找标记 → 无 → 长度 1 ≤ 10 → 扣住 `完` |
| 流结束 `flush()` | — | 输出 `完` |

🔎 副作用：**最后最多 10 个字符总要等到 flush 才显示**。对于聊天界面，这意味着结尾少数字会「顿一下」；对总体观感影响很小，换来的是「任何被切开 11 次的标记都不会漏出来」。

### 6.6 两个必须知道的边界

1. **过滤器不区分「用户想看的标记」**。如果用户问「你的 `<tool_call>` 格式是什么」，模型的回答里的这段标记会被整个吞掉，正文里留一个空洞。目前没有转义/开关。
2. **过滤器先于 `on_token` 生效**，所以 Shell 端无法再做「要不要显示」的决定——它只收到过滤后的文本。

---

## 7. `llm/completion.py`（148 行）：幽灵文本

### 7.1 定位：三条独立的链路

编辑器内联补全走的是 `inline/complete`，**不经过 Agent 循环、不经过工具、不经过历史**：

```
Kotlin AgentInlineCompletionProvider
   │ 去抖 300 ms；prefix 取 4000 字、suffix 取 1200 字、文件 > 512 KB 不请求
   ▼
InlineCompletionClient.request("inline/complete", ...)   等待 1500 ms
   ▼
RpcServer._run_inline → asyncio.wait_for(complete(...), timeout=12.0)
   ▼
llm/completion.py: complete() → httpx（connect 2 / read 8 / write 2 / pool 2）
```

**三个超时不匹配是刻意的倒金字塔**：Shell 1.5 s 就放弃（不卡编辑器），Core 12 s、HTTP 8 s 只是为了不让后台协程无限挂着。也就是说：**用户实际感受到的上限是 1.5 s**，后面的 8 s / 12 s 永远不会先到。

### 7.2 两个 URL 解析（21-40）

```python
def resolve_generate_url(base_url):      # Ollama FIM
def resolve_completions_url(base_url):   # OpenAI 兼容 /completions
```

| 输入 | `resolve_generate_url` | `resolve_completions_url` |
|---|---|---|
| `http://localhost:11434` | `.../api/generate` | — |
| `http://localhost:11434/api/chat` | `.../api/generate`（**去掉** `/api/chat`） | — |
| `http://localhost:11434/api/generate` | 原样 | — |
| `https://api.deepseek.com/v1` | — | `.../v1/completions` |
| `https://host/v1/chat/completions` | — | `.../v1/completions`（**去掉** `/chat/completions`） |
| `https://host/v1/completions` | — | 原样 |

这两个函数与 `providers/*.py` 里的 `resolve_chat_url` 是**同一套思路的另一种方向**：聊天时往「加后缀」方向拼，补全时往「换后缀」方向改。

### 7.3 `complete`（43-59）

```python
prefix = (prefix or "")[-MAX_PREFIX_CHARS:]   # 取最后 4000 字
suffix = (suffix or "")[:MAX_SUFFIX_CHARS]    # 取前 1200 字
if not prefix.strip(): return ""
try:
    if (config.provider or "").lower() == "openai_compat":
        text = await _complete_openai(config, prefix, suffix, max_tokens)
    else:
        text = await _complete_ollama(config, prefix, suffix, max_tokens)
except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
    LOGGER.debug("inline completion failed: %s", exc)
    return ""
return sanitize(text, prefix, suffix)
```

四个观察：

1. **前缀取尾、后缀取头**：离光标越近的上下文信息量越大。
2. **空 prefix 直接返回**：文件开头没有上下文，补全只会是噪声。
3. **错误全部吃成空串**：「幽灵文本必须在编辑器里永不报错」——这是产品约束，不是疏忽。只记 `debug`，连 `info` 都不打。
4. ⚠️ **这里按 `config.provider` 分派，不调用 `detect_provider`**。如果用户把类型选成 `ollama`、端点却填 `/v1/chat/completions`：聊天会被自动纠正为 OpenAI 协议，而补全会去请求 `.../v1/chat/completions/api/generate` → 404 → 永远返回空串。**这是本层唯一一处「探测规则」与「实际分派」不一致的地方。**

另外，`language` 参数**被接收但从未使用**（两个内部实现都没接它）——曾经想按语言加 stop 词或模板，后来没做。

### 7.4 `_complete_ollama`（62-85）

```python
body = {
  "model": config.model,
  "prompt": prefix,
  "suffix": suffix,
  "stream": False,
  "options": {"temperature": 0.1, "num_predict": max(16, int(max_tokens)), "stop": ["\n\n"]},
}
...
response = await client.post(url, json=body)
if response.status_code >= 400: LOGGER.debug(...); return ""
return str(data.get("response") or "")
```

| 参数 | 值 | 理由 |
|---|---|---|
| `temperature` | 0.1 | 补全要确定性，不要创造性 |
| `num_predict` | `max(16, max_tokens)` | 至少 16 个 token；Shell 传的默认是 128 |
| `stop` | `["\n\n"]` | 空行 = 段落边界，补全不该跨段 |
| `stream` | False | 幽灵文本是一次性渲染，流式无意义 |

### 7.5 `_complete_openai`（88-115）

```python
headers = {"Content-Type": "application/json"}
if config.api_key: headers["Authorization"] = "Bearer " + config.api_key
body = {"model":..., "prompt": prefix, "suffix": suffix,
        "max_tokens": max(16, int(max_tokens)), "temperature": 0.1, "stop": ["\n\n"]}
...
choices = data.get("choices") or []
if not choices: return ""
return str(choices[0].get("text") or "")
```

- 走的是 **legacy `/completions`**（`prompt` + `suffix` 的 FIM 形态），不是 `/chat/completions`。
- 很多云模型根本没有 `/completions` 端点（只有 chat），所以 `>= 400` 分支的注释写的是「Many cloud models have no /completions endpoint at all.」——**这是正常现象，不是故障**。
- 注意 Ollama 分支**不发送 `Authorization`**：即便用户在设置里填了 api_key（用于同机的鉴权代理），补全也不会带。

### 7.6 `sanitize`（118-142）：五道清洗

```python
text = text.replace("\r\n", "\n")      # ①
if not text: return ""
text = _strip_fences(text)              # ② 删掉整行的三反引号
for line in (suffix or "").splitlines():  # ③ 遇到已存在的代码就截断
    stripped = line.strip()
    if len(stripped) < 4: continue
    index = text.find(stripped)
    if index >= 0:
        text = text[:index]; break
text = text.rstrip()                    # ④
if not text: return ""
tail = prefix.strip().splitlines()[-1].strip() if prefix.strip() else ""   # ⑤ 去回声
if len(tail) >= 8 and text.strip().startswith(tail):
    text = text.strip()[len(tail):].lstrip("\n").rstrip()
if prefix.endswith("\n") and text.startswith("\n"):
    text = text[1:]
return text if text.strip() else ""
```

用测试里的五个用例反过来读它（`core/tests/test_completion.py`）：

| 输入（text / prefix / suffix） | 输出 | 哪一步生效 |
|---|---|---|
| `` ```java\nint a = 1;\n``` `` / `class A {` / `}` | `int a = 1;` | ② 去围栏 |
| `    int x = compute();\n    return result;` / `int f() {` / `    return result;\n}` | `    int x = compute();` | ③ 截断（suffix 行已在后面） |
| `public void run() {\n    start();` / `public void run() {` / `}` | `    start();` | ⑤ 去回声（tail 长 19 ≥ 8） |
| `\n    return value;` / `int f() {` / `}` | `\n    return value;` | 都不命中（tail=16，但 text 不以它开头） |
| `""` | `""` | 入口判空 |

⚠️ ③ 的两个诚实缺陷：

- 用 `text.find(stripped)` **全文搜索**，不是按行比对：补全内容里偶然出现「与后面某一行相同」的片段，会被提前截断。
- 只处理**第一个命中**的 suffix 行（`break`），短行（`< 4` 字符）被跳过——所以 `}`、`)` 这类行永远不会触发截断。

`_strip_fences`（145-148）只删「整行以三反引号开头」的行，不做配对检查：反引号不配对的残缺输出也会被处理成「去掉围栏行」，剩下的内容照常上屏。

---

## 8. `providers/openai_compat.py`（206 行）逐函数

### 8.1 模块开头（1-60）

```python
_TOOLS_DISABLED_NOTICE = (
    "端点未启用函数调用（vLLM 需启动参数 --enable-auto-tool-choice --tool-call-parser <parser>），"
    "已改用文本工具调用兼容模式：模型把 <tool_call> 写进正文时仍会执行。"
    "服务端补上参数后会自动恢复标准模式。"
)
```

这条提示文案把**怎么修**也告诉了用户（不是只说「不支持」），并预告了「300 秒后会自动重试」（§2.5）。

#### `resolve_chat_url`（42-51）

```python
base = (base_url or "").strip().rstrip("/")
if not base: return ""
if base.endswith("/chat/completions"): return base
if base.endswith("/v1"): return base + "/chat/completions"
return base + "/v1/chat/completions"
```

| 输入 | 输出 |
|---|---|
| 空 | `""`（上层抛「base_url 未配置」） |
| `https://host/v1/chat/completions` | 原样 |
| `https://host/v1` | `https://host/v1/chat/completions` |
| `https://api.deepseek.com` | `https://api.deepseek.com/v1/chat/completions` |
| `http://localhost:11434/api/chat` | 不会走到这里（探测为 ollama） |

⚠️ 与前文 §1.3 提到的缺口对应：带 `?api-version=` 的端点会被 `endswith` 判定失败而**多拼一层**。

#### `_ToolRejected`（54-60）

```python
class _ToolRejected(Exception):
    def __init__(self, status, body):
        super().__init__("%d: %s" % (status, body[:300]))
        self.status = status
        self.body = body
```

它是**控制流信号**，不是错误：只在 `attempt()` 内部被捕获并触发「去掉工具重发」。

### 8.2 类字段与构造（63-76）

```python
class OpenAICompatProvider(LLMProvider):
    provider_id = "openai_compat"
    notice: Optional[str] = None          # 类属性默认值，实例赋值时遮蔽

    def __init__(self, transport: Optional[httpx.AsyncBaseTransport] = None):
        self._transport = transport       # 测试注入 MockTransport；生产为 None

    def consume_notice(self) -> Optional[str]:
        notice, self.notice = self.notice, None
        return notice
```

- `transport` 参数是**测试钩子**：`httpx.MockTransport` 可以让整个 provider 不联网跑完（测试里大量使用）。
- `provider_id` 目前没有消费方，是留给日志/未来的元信息。

### 8.3 `chat_stream`（78-163）十步

#### ① 解析 URL（87-89）

```python
url = resolve_chat_url(config.base_url)
if not url:
    raise RpcError(ErrorCode.LLM_ERROR, "base_url 未配置")
```

错误码用 `LLM_ERROR` 而不是 `PROTOCOL_ERROR`：对用户而言这是「模型连不上」，不是协议错。

#### ② 请求头（91-93）

```python
headers = {"Content-Type": "application/json"}
if config.api_key: headers["Authorization"] = "Bearer " + config.api_key
```

没有 api_key 就不发头——比发一个空 Bearer 更安全（某些网关会因为空的 `Authorization` 直接 401）。

#### ③ 请求体（95-102）

```python
body = {
    "model": config.model,
    "messages": to_openai_messages(messages),
    "temperature": config.temperature,
    "max_tokens": config.max_tokens,
    "stream": True,
    "stream_options": {"include_usage": True},
}
```

⚠️ `stream_options` 是**无条件发送**的，没有「老端点不认这个字段就重发」的降级分支。不认它的实现会回 400，而 400 里通常没有 §2.4 的关键字，于是变成一个普通 `LLM 请求失败(400)`——用户只能从 body 里自己看出来。

#### ④ 工具开关（103-109）

```python
tools_key = (url, config.model or "")
wants_tools = bool(tools) and not tools_suppressed(*tools_key)
if wants_tools:
    body["tools"] = to_openai_tools(tools)
    body["tool_choice"] = "auto"
elif tools:
    self._warn_once(tools_key)
```

- `wants_tools` 在假释期内为 False → 不发给 `tools`，也**不提示**（提示只在第一次降级时发一次）。
- `tool_choice: auto` 是 vLLM 400 的引火线：没开 `--enable-auto-tool-choice` 时它会直接报错，正好被 §2.4 识别为「不支持工具」。

#### ⑤ 文本工具调用的过滤器（111-120）

```python
guard = TextToolCallFilter()
delivered = False
async def emit(piece: str) -> None:
    nonlocal delivered
    delivered = True
    for visible in guard.feed(piece):
        await on_token(visible)
```

📌 `delivered = True` 在 `guard.feed` **之前**，所以「只收到被吞掉的标记」也算已交付——重试被禁用（保守，§4.3）。

#### ⑥ `attempt()`：一次完整尝试（122-139）

```python
async def attempt():
    async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, transport=self._transport) as client:
        try:
            accumulator = await self._stream(client, url, headers, body, emit, on_reasoning)
            if wants_tools:
                accept_tools(*tools_key)        # 成功 → 结束假释
            return accumulator
        except _ToolRejected as rejected:
            LOGGER.warning(...)
            suppress_tools(*tools_key)          # 开始 300s 假释
            self._warn_once(tools_key)          # 本次进程第一次降级 → 提示
            body.pop("tools", None)             # 去掉工具定义
            body.pop("tool_choice", None)
            return await self._stream(client, url, headers, body, emit, on_reasoning)
```

四个要点：

1. **每次尝试新建 `AsyncClient`**：重试不会复用上一个可能已经坏掉的连接。`async with` 保证退出时关闭。
2. **`accept_tools` 只在「第一次 `_stream` 成功」时调用**；降级重发成功的那条路径**不**调用——否则刚设的假释会被自己立即清除。
3. **`body.pop` 是原地修改**：后续外层的重试（例如无工具请求又遇 503）发出去的也是无工具体，但 `wants_tools` 仍是 True → 若这次成功，会错误地 `accept_tools`（§14 第 7 条）。
4. **降级重发不会再被 `_ToolRejected` 包一层**：若第二次又抛 `_ToolRejected`，它会穿越 `run_with_retries`（不可重试、非 `TransportError`）原样上抛。

#### ⑦ 有界重试（141-147）

```python
accumulator = await run_with_retries(
    attempt,
    lambda: delivered,
    lambda exc, retries: describe_failure(exc, url, config.model, retries),
)
```

三个参数就是 `run_with_retries(attempt, started, describe)`——注意这里传的是 **lambda**，所以 `delivered` 每次失败时读的是**最新值**。

#### ⑧ 放回被扣住的尾巴（149-150）

```python
for visible in guard.flush():
    await on_token(visible)
```

必须在 `finalize()` **之前**：`finalize` 之后就要把 `content` 交给 `recover_text_tool_calls`，但显示顺序应该先文后工具。

#### ⑨ 工具调用汇总（152-157）

```python
calls = accumulator.finalize()
content = accumulator.content
if not calls:
    content, calls = recover_text_tool_calls(content)
for call in calls:
    await on_tool_call(call)
```

**只在原生调用完全为空时才做文本恢复**——两者同时存在时不合并，优先信任原生通道。

#### ⑩ 返回结果（158-163）

```python
return ChatResult(content=content, tool_calls=calls,
                  finish_reason=accumulator.finish_reason, usage=accumulator.usage)
```

`usage_total` 不在这里填（由 `loop` 汇总）；`finish_reason` 原样传出（但目前无人消费）。

### 8.4 `_stream`（165-199）

```python
async with client.stream("POST", url, headers=headers, json=body) as response:
    if response.status_code >= 400:
        raw = (await response.aread()).decode("utf-8", "replace")
        if is_tool_unsupported(response.status_code, raw):
            raise _ToolRejected(response.status_code, raw)
        if response.status_code in RETRYABLE_STATUS:
            raise RetryableStatus(response.status_code, raw)
        raise RpcError(ErrorCode.LLM_ERROR, "LLM 请求失败(%d) %s: %s" % (status, url, raw[:500]))
    async for line in response.aiter_lines():
        payload = parse_sse_line(line)
        if payload is None: continue
        if payload == "[DONE]": break
        try: chunk = json.loads(payload)
        except json.JSONDecodeError: LOGGER.warning(...); continue
        reasoning = accumulator.reasoning_delta(chunk)      # ① 先取思考
        if reasoning and on_reasoning is not None:
            await on_reasoning(reasoning)
        for token in accumulator.feed(chunk): await on_token(token)   # ② 再取正文与工具
```

⚠️ 先转思考再转正文的顺序不是风格问题：`reasoning_delta` 与 `feed` 读的是**同一个 chunk 的两个字段**，先发思考才能保证界面上的「思考行」在正文第一字之前出现。两个 if 都不写进 `delivered`——思考不算「已交付正文」，但也不会触发重试（重试只看 `emit`）。

错误分支的**优先级不能换**：

```
400/422 + 工具关键字  →  _ToolRejected（降级重发，不算失败）
429/500/502/503/504  →  RetryableStatus（可重试）
其它 >=400             →  RpcError（终态失败，body 前 500 字上屏）
```

若先判 `RETRYABLE_STATUS`，400 依然不会被误重试；但若先判「其它」，`_ToolRejected` 就永远到不了。

逐行解析的三个宽容之处：

| 情况 | 处理 |
|---|---|
| 非 `data:` 行（`event:`、心跳、空行） | 跳过 |
| `data: [DONE]` | `break` 结束流，**不**解析 JSON |
| 某行 JSON 坏了 | warning + 跳过，其余继续 |

### 8.5 `_warn_once`（201-206）

```python
if take_tools_notice(*tools_key):
    self.notice = _TOOLS_DISABLED_NOTICE
```

两步分离：**模块级 set 决定「要不要提示」**（跨任务），**实例字段决定「提示什么」**（一次性取走）。

---

## 9. `providers/ollama.py`（196 行）逐函数

### 9.1 与 openai 文件的结构对应

两份文件是**对称的**：常量提示、`resolve_chat_url`、`_ToolRejected`、类、`chat_stream`、`_stream`、`_warn_once` 一一对应。差异全在协议细节上。

### 9.2 `resolve_chat_url`（41-49）

```python
if base.endswith("/api/chat"): return base
if base.endswith("/api/generate"): return base[: -len("/api/generate")] + "/api/chat"
return base + "/api/chat"
```

| 输入 | 输出 |
|---|---|
| `http://localhost:11434` | `http://localhost:11434/api/chat` |
| `http://localhost:11434/api/chat` | 原样 |
| `http://localhost:11434/api/generate` | `http://localhost:11434/api/chat` |
| `http://host/ollama/` | `http://host/ollama/api/chat`（支持反代子路径） |

### 9.3 请求体（89-101）

```python
options = {"temperature": config.temperature, "num_predict": config.max_tokens}
body = {"model": config.model, "messages": to_ollama_messages(messages),
        "stream": True, "options": options}
if wants_tools:
    body["tools"] = to_ollama_tools(tools)
```

与 OpenAI 的差异：

| OpenAI | Ollama |
|---|---|
| `temperature` / `max_tokens` 在顶层 | 在 `options` 里，且 `max_tokens` 叫 **`num_predict`** |
| `tool_choice: auto` | 无（默认自动决定） |
| `stream_options` | 无（用量随 `done` 包一起给） |
| `Authorization` 头 | 无 |

### 9.4 `_stream`（156-189）

```python
async for line in response.aiter_lines():
    payload = parse_sse_line(line)
    if payload is None:
        payload = line.strip()          # ← 关键：Ollama 是 NDJSON，没有 data: 前缀
    if not payload: continue
    try: chunk = json.loads(payload)
    except json.JSONDecodeError:
        LOGGER.warning("drop invalid NDJSON chunk: %r", payload[:200]); continue
    reasoning = accumulator.reasoning_delta(chunk)      # ① 先取 message.thinking
    if reasoning and on_reasoning is not None:
        await on_reasoning(reasoning)
    for token in accumulator.feed(chunk): await on_token(token)
```

这一行 `if payload is None: payload = line.strip()` 让 Ollama 同时兼容两种下游：

- 直连 Ollama：一行一个裸 JSON → `parse_sse_line` 返回 None → 用 `line.strip()`。
- 经过会把 NDJSON 包装成 SSE 的代理：`data: {...}` → `parse_sse_line` 正常取出。

⚠️ 副作用：**它也会把任何非 `data:` 行当候选 JSON**——空串被 `if not payload` 拦住，真正的 NDJSON 行能正常解析，只有代理插入的事件注释之类才会进 `drop invalid NDJSON chunk` 分支。

错误状态码的处理与 OpenAI 完全同构，只是文案换成 `Ollama 请求失败(%d)`。

### 9.5 `chat_stream` 尾段（143-154）

```python
calls = accumulator.finalize()
content = accumulator.content
if not calls:
    content, calls = recover_text_tool_calls(content)
for call in calls:
    await on_tool_call(call)
return ChatResult(content=content, tool_calls=calls,
                  finish_reason="stop" if accumulator.done else None,
                  usage=accumulator.usage)
```

- Ollama 没有 `finish_reason` 字段，所以用 `done` 拼一个 `stop`。
- 流被中断时 `done` 为 False → `finish_reason=None`（同样无人消费）。
- 文本恢复逻辑与 OpenAI 一字不差——**因为「模型把调用写进正文」与协议无关**。

---

## 10. 两家 provider 的逐项差异（一张表看完）

| 维度 | `OpenAICompatProvider` | `OllamaProvider` |
|---|---|---|
| 端点 | `.../v1/chat/completions` | `.../api/chat` |
| 鉴权 | `Authorization: Bearer <api_key>` | 无 |
| 消息里的 `arguments` | JSON **字符串** | 对象 |
| 工具调用 id | 保留并回传 | 不存（丢弃模型给的 id 以外无意义） |
| 温度/长度 | 顶层 `temperature` / `max_tokens` | `options.temperature` / `options.num_predict` |
| 工具选择 | `tool_choice: "auto"` | 无 |
| 用量 | 尾包 `usage`（`prompt_tokens` 等） | `done` 包的 `prompt_eval_count` / `eval_count` |
| 流格式 | SSE + `[DONE]` | NDJSON（兼容 `data:` 包装） |
| 结束标志 | `finish_reason` | `done: true` |
| 工具调用聚合 | 按 `index` 拼字符串片段 | 整对象 + `(name, arguments)` 去重 |
| 思考过程 | `delta.reasoning_content`（兼容 `delta.reasoning`） | `message.thinking` |
| 相同之处 | 超时、重试、错误文案、400 降级、思考/正文分流、文本调用恢复、`consume_notice` | 同左 |

📌 「相同之处」比「不同之处」多得多，这正是把公共逻辑放在 `base / streaming / transport / tool_text` 里、两个 provider 只做协议呑吐层的原因。

---

## 11. 一次 `chat_stream` 的完整时序（含降级）

### 11.1 正常路径

```
loop.run_chat
  │ messages = [SYSTEM] + history（已 trim）
  │ result = await provider.chat_stream(messages, tools, config, emit_token, _noop, emit_reasoning)
  ▼
provider.chat_stream
  ├─ resolve_chat_url(config.base_url)
  ├─ 拼 headers / body（含 tools、tool_choice）
  ├─ guard = TextToolCallFilter(); delivered = False
  ├─ run_with_retries(attempt, lambda: delivered, describe_failure)
  │    └─ attempt: AsyncClient → _stream
  │         ├─ response.status_code 判定（400/可重试/其它）
  │         └─ async for line: parse → json → reasoning_delta → feed → emit
  │              ├─ reasoning_delta → on_reasoning（→ chat/reasoning 通知）
  │              └─ emit → guard.feed → on_token（→ chat/token 通知）
  ├─ guard.flush() → 最后 ≤10 字的尾巴
  ├─ accumulator.finalize() → List[ToolCall]
  ├─ if not calls: recover_text_tool_calls(content)
  ├─ for call in calls: await on_tool_call(call)   ← loop 里是 noop
  └─ return ChatResult(content, tool_calls, finish_reason, usage)
  ▼
loop
  ├─ _flush_notice(provider, on_notice)     ← 降级提示（如果有）
  ├─ _add_usage(usage_total, result.usage)
  └─ 有工具调用 → scheduler.run_parallel（第 04 卷）
```

### 11.2 降级路径（引擎不支持工具）

第一条请求带工具，服务端 400 且命中关键字：

```
_stream  ── is_tool_unsupported(400, raw) == True
   └─ raise _ToolRejected(400, raw)
attempt
   └─ except _ToolRejected:
        suppress_tools(url, model)        # 300s
        _warn_once(tools_key)             # notice = "端点未启用函数调用…"
        body.pop("tools"); body.pop("tool_choice")
        return await _stream(...)         # 第二次请求：不带工具
chat_stream
   ├─ wants_tools 仍为 True，但成功路径不经过 accept_tools（因为 return 在 except 内）
   └─ 若无原生 tool_calls → recover_text_tool_calls → 仍有工具可用
loop
   └─ _flush_notice → on_notice → chat/notice → 气泡里一条灰色提示
```

时效：**300 秒内的后续任务不再带工具**（不再产生 400），300 秒后自动重探。

### 11.3 失败路径

| 失败 | 处理 | 用户看到 |
|---|---|---|
| 连接失败 / 首字前 429、5xx | 退避重试（最多 2 次） | 成功则无感；失败则「已自动重试 2 次」 |
| 已输出后断开 | **不重试**，转可读错误 | `chat/error` +「连接中断…重发一次「继续」」 |
| 401/403/404/400（非工具） | 不重试 | `LLM 请求失败(401) <url>: <body 前 500 字>` |
| 工具定义被拒且降级后仍 400 | `_ToolRejected` 穿出 | 落到 `_run_task` 的兜底 except，文案是 `400: …` |

最后一行值得单独说：`_ToolRejected` 不是 `RpcError`，所以 `server._run_task` 里 `except RpcError` 拦不住它，最后由 `except Exception` + `_error_text` 兜底成 `LLM_ERROR`。文案不如其它分支耐看，但至少不会变成空白。

---

## 12. 用量数字的完整链路（含一个真实缺口）

### 12.1 四个环节

```
① provider：ChatResult.usage
     OpenAI  → 尾包 chunk["usage"]（原样）
     Ollama  → {"in": prompt_eval_count, "out": eval_count}

② loop._add_usage：累加到 usage_total = {"in":0,"out":0,"total":0}
     in  += int(usage.get("in"))
     out += int(usage.get("out"))
     total += int(usage.get("total")) or (in + out)

③ loop._usage_snapshot：全为 0 → None（界面就不显示这一行）

④ server._run_task：
     chat/finish → {"usage": <最后一轮>, "usageTotal": <全轮合计>}
     _record_usage → ~/.agent/logs/usage.jsonl（只在本机）
```

Shell 侧两边读的都是 `usageTotal.in` / `usageTotal.out`（`ChatSessionPanel:791-792`、`session.ts:444-445`）。

### 12.2 缺口：OpenAI 的用量键名对不上

代码事实：

- `OpenAIStreamAccumulator.feed`（`streaming.py:53-54`）把 `chunk["usage"]` **原样**存下；
- 标准 OpenAI 流的 usage 是 `{"prompt_tokens": N, "completion_tokens": M, "total_tokens": T}`；
- `loop._add_usage`（`loop.py:75-79`）只读 `"in" / "out" / "total"`。

于是：`in=0, out=0, total=0` → `_usage_snapshot` 返回 `None` → **OpenAI 兼容端点的用量既不显示、也不落盘**。

对比：Ollama 分支在 `streaming.py:155-161` 主动做了 `{"in":..., "out":...}` 的**重命名**，所以能统计。

这不影响对话，只影响可观测性。修复点很小（在 `feed` 里把 `prompt_tokens/completion_tokens` 映射成 `in/out`），但属于**行为变更**：会让历史用量统计从 0 突然变成真实值，需要同步更新 `test_streaming` 与 `test_usage`。

> 若你只想验证这个判断：在 `_add_usage` 里加一行 `print(usage)`，用一个真实 OpenAI 兼容端点跑一轮工具任务，就能看到三个陌生的键名。

---

## 13. 测试锁定的行为（映射表）

本层的 6 个测试文件（共 984 行）几乎逐条钉住了上文每个结论：

| 测试文件 | 钉住了什么 |
|---|---|
| `test_llm_providers.py`（329） | 六个 class：探测四类用例 + `build_provider` 跟随探测；两个 URL 解析；`is_tool_unsupported` 正反例；OpenAI 降级（发 2 次、第二次无 `tools`/`tool_choice`）；假释期内跳过工具；假释过期重探；提示跨任务只一次；`accept_tools` 清假释；**思考通道两条**（`TestReasoningStream`：OpenAI 的 `reasoning_content` 与 Ollama 的 `thinking` 都必须先于正文回调） |
| `test_streaming.py`（117） | `parse_sse_line`；文本 token；跨块参数拼接；双槽并行；坏参数变 `_raw`；Ollama 用量；Ollama 去重；字符串参数转 dict；**两个累加器各一条「思考不混进 `content`」** |
| `test_tool_text.py`（149） | 逐字符切分的标记被吞；纯文本不变；裸 function 块；未闭合块丢弃；Hermes/JSON/多行内容/裸块恢复；provider 端到端（流里无标签、有真实工具） |
| `test_transport_errors.py`（195） | 五种传输错误的文案；provider 永不返回空 message；503 重试三次成功；429 用尽次数带「已自动重试 2 次」；已输出不重试；401 不重放；`_error_text` 兜底 |
| `test_context.py`（135） | 预算内不动；整轮丢弃不留孤儿；开头孤立 tool 被删；user 永远存活；最新一轮被保留；**每条消息只分词一次**（≤ `3×n+5` 次调用）；`budget_for` 三种取值 |
| `test_completion.py`（59） | 两个 URL 解析；`sanitize` 五个用例（围栏/截断/回声/普通/空） |

其中 `test_context.py:92-116` 那个「只分词一次」的用例尤其值得看：它用 `mock.patch` 把 `estimate_tokens` 换成计数器，断言总调用次数不超过 `3 × len(messages) + 5`。这是把**性能回归**写成了功能测试。

---

## 14. 十二条易错点

1. **补全不走协议探测**。聊天会因端点形状纠正 `provider`，补全只信 `config.provider`；类型与端点不一致时，补全静默失效（§7.3）。
2. **`detect_provider` 用 `in`、`resolve_chat_url` 用 `endswith`**。带 `?api-version=` 的端点会被判为 OpenAI，却拼成 `...<query>/v1/chat/completions`。
3. **`arguments` 两家形态相反**：OpenAI 要字符串，Ollama 要对象。写测试时对着错误的形态调，会得到「参数丢失」而不是报错。
4. **`stream_options` 无降级分支**。不认此字段的端点会以普通 400 失败，不会被当成「协议不兼容」处理。
5. **`delivered` 的含义是「收到过流式片段」**，不是「用户看到过字」。一个被过滤器整段吞掉的标记也会禁用重试。
6. **降级后可能「假恢复」**：拒绝工具 → `body.pop("tools")` → 无工具请求遇 503 → 重试成功 → `wants_tools` 仍为 True → `accept_tools` 清掉假释。后果是之后每个任务多一次 400（提示已被去重，用户无感）。
7. **`_ToolRejected` 不是 `RpcError`**。二次抛出时它会走到 `_run_task` 的兜底 `except Exception`，文案是 `400: ...`。
8. **Ollama 的 `usage` 可能含 `None`**。只给了 `eval_count` 时 `"in"` 是 `None`，`_int(None)` 变 0——数字不会爆炸，但会偏低。
9. **OpenAI 的用量键名不会被识别**（§12.2）。想看到用量行，目前只能用 Ollama，或给 `feed` 加重命名。
10. **`finish_reason` 没有任何消费方**。模型因 `length` 截断工具参数时，得到的是 `{"_raw": ...}`，下游只会报「缺少参数」，不会提示「被截断了」。
11. **`trim_history` 快路径与慢路径对 system 位置处理不同**（一个保留原序、一个提前），且它**不保证**一定小于预算（全是 user 时无计可施）。
12. **文本工具调用过滤器会误吞正文**：用户问「你的 `<tool_call>` 格式是什么」，那段标记会从回答里消失；且尾部最多 10 个字符要等 `flush` 才显示。

---

## 15. 自测清单

### 15.1 跑测试

```powershell
cd core
python -m unittest tests.test_llm_providers tests.test_streaming tests.test_tool_text tests.test_transport_errors tests.test_context tests.test_completion
```

### 15.2 先预测、再验证

1. `detect_provider(ChatConfig(provider="ollama", base_url="http://host/api/tags"))` 返回什么？把 `base_url` 换成 `http://host/api/tags/` 呢？（提示：`rstrip("/")`）
2. `trim_history` 的 `system` 列表里如果有 **2 条** system 消息，快路径与慢路径的返回顺序分别是什么？
3. 一个 OpenAI 流依次给出三片：`{id, name, arguments: "{"}`、`{arguments: "\"path\":"}`、`{arguments: "\"a\"}"}`，`finalize()` 的 `arguments` 是什么？如果第三片把 `}` 拉下，又会变成什么？
4. `TextToolCallFilter` 收到 `"<tool_call>"` 后流就断了，`flush()` 返回什么？为什么这样设计？
5. 把 `RETRYABLE_STATUS` 加上 `408`，会发生什么？（提示：`_stream` 在 408 时抛 `RetryableStatus`，但 408 一般在**请求阶段**）
6. 若给 `to_ollama_messages` 里的 `arguments` 改成 `json.dumps(...)`，第一个出问题的测试是哪个？

### 15.3 改代码前先想清楚

- 想给 OpenAI 补上用量统计：改 `streaming.py` 的 `feed`（映射键名）还是改 `loop._add_usage`（兼容两套键）？两边都要动哪些测试？
- 想让补全也用协议探测：把 `(config.provider or "").lower() == "openai_compat"` 改成 `detect_provider(config) == "openai_compat"`，会引入对 `llm/__init__` 的循环依赖吗？（提示：`completion` 在包内，`__init__` 会 import providers）

---

## 16. 下一卷

第 06 卷进入**工具系统**：Core 侧 `tools/registry.py` 的注册/覆盖语义与 `tools/remote.py` 的「调用—等待—回传」桥，Java 侧 `agent/tools/*.java` 的六个工具类（文件、编辑、搜索、终端、诊断、Git）、`ToolDispatcher` 的路由与权限确认，以及两端共享的 `ToolDef.permission` / `timeout_sec` / `max_concurrency` 约定。
