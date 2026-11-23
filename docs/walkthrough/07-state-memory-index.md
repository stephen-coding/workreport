# 第 07 卷 · 状态、记忆、检索、索引、MCP、Skills、用量

> 本卷读完 21 个文件，共 **3,746 行**：
>
> **会话状态（800 行）**：`agent/session.py`(222) · `agent/store.py`(237) · `agent/search.py`(212) · `agent/todos.py`(129)
>
> **长期记忆（950 行）**：`memory/store.py`(260) · `memory/files.py`(105) · `memory/service.py`(254) · `memory/pending.py`(331)
>
> **代码索引（692 行）**：`codeindex/chunker.py`(120) · `codeindex/embeddings.py`(124) · `codeindex/store.py`(225) · `codeindex/service.py`(223)
>
> **外部扩展（765 行）**：`mcp/config.py`(87) · `mcp/client.py`(281) · `mcp/manager.py`(147) · `skills/loader.py`(183) · `skills/service.py`(67)
>
> **用量与原生层（522 行）**：`usage.py`(162) · `native/__init__.py`(92) · `native/tokenizer.py`(73) · `native/codesoup.py`(195)
>
> 再加 4 个包 `__init__.py`（纯 re-export）与 11 个测试文件共 1,601 行（另有 1 个假 MCP 服务器 71 行）。
>
> 前六卷讲的是「一次请求怎么跑完」。这一卷讲的是**跑完之后留下了什么**：会话怎么活过 Core 重启、历史怎么被搜到、记忆怎么从一句话变成一条长期事实、代码库怎么被向量化、外部 MCP 服务怎么被接进来、Skill 怎么被匹配、用量怎么落账。

---

## 0. 本卷读什么

### 0.1 二十一个文件的分工

| 文件 | 行数 | 一句话职责 |
|---|---|---|
| `agent/session.py` | 222 | 内存里的会话对象 + 上限裁剪 + 历史列表合并 |
| `agent/store.py` | 237 | 一个会话一个 JSON 文件，原子写、增量列表、墓碑清理 |
| `agent/search.py` | 212 | 历史会话全文检索（FTS5 trigram，退化 LIKE） |
| `agent/todos.py` | 129 | 任务清单的规范化与落盘字段（随会话一起存） |
| `memory/store.py` | 261 | 记忆的 SQLite 表 + FTS5 外部内容表 + 相似度打分 |
| `memory/files.py` | 106 | 记忆的 Markdown 镜像 + `overview.md` 索引 |
| `memory/service.py` | 255 | 记忆服务：双写、overview 缓存、`memory_*` 三个工具 |
| `memory/pending.py` | 332 | 候选记忆：两条抽取规则、`_pending/*.md`、人工晋升 |
| `codeindex/chunker.py` | 121 | 把源文件切成代码块（函数优先，滑窗兜底） |
| `codeindex/embeddings.py` | 125 | Ollama / OpenAI 兼容两套 embedding 客户端 |
| `codeindex/store.py` | 226 | 向量 BLOB 存储 + 暴力余弦检索 + 内存缓存 |
| `codeindex/service.py` | 224 | 增量构建索引 + `semantic_search` / `build_index` 两个工具 |
| `mcp/config.py` | 88 | MCP 配置模型与「项目级 + IDE 级」合并 |
| `mcp/client.py` | 282 | 手写的 MCP 客户端（stdio 与 streamable-HTTP 两种传输） |
| `mcp/manager.py` | 148 | 连接管理、工具注册、失败重试与确认回调 |
| `skills/loader.py` | 184 | `SKILL.md` 的 frontmatter 解析与三级覆盖加载 |
| `skills/service.py` | 68 | `skill_list` / `skill_load` 两个工具 + 场景自动匹配 |
| `usage.py` | 163 | 本地用量账本（JSONL，10MB 轮转，报表按需算） |
| `native/__init__.py` | 93 | 原生加速层门面：能 Rust 就 Rust，否则纯 Python |
| `native/tokenizer.py` | 74 | 启发式 token 计数 + 句界截断 |
| `native/codesoup.py` | 196 | 纯 Python 结构解析（`ast` + 正则 + 花括号配对） |

### 0.2 五条互相独立的存储线

这一卷最容易读晕的地方是：**看起来都是「存东西」，其实是五套互不相干的东西**。

```
                         ┌──────────────── 同一次聊天 ────────────────┐
                         │                                            │
chat/finish ─────────────┼──→ SessionManager.save()                   │
                         │        │                                   │
                         │        ├──→ SessionStore  ──→ sessions/<slug>.json     （线 1：会话正文）
                         │        └──→ SessionSearch ──→ sessions.db (FTS5)       （线 2：会话全文）
                         │
                         ├──→ _record_usage() ──→ ~/.agent/logs/usage.jsonl       （线 5：用量）
                         │
                         └──→ MemoryService.capture() ──→ memories/_pending/*.md （候选，非正式记忆）

memory_save / memory/promote ──→ memory.db (FTS5) + <project>/.agent/memories/*.md + overview.md  （线 3：记忆）

build_index ──→ code_index.db（chunk 文本 + 向量 BLOB）                                    （线 4：代码索引）
```

| 线 | 落盘位置 | 引擎 | 谁写 | 谁读 |
|---|---|---|---|---|
| 1 会话正文 | `<data_dir>/sessions/<slug>.json` | JSON v2 | `SessionManager.save`（每任务 finally） | `session/list`、`session/get`、索引迁移 |
| 2 会话全文 | `<data_dir>/sessions.db` | SQLite + FTS5(trigram) | `on_saved` 钩子、`on_deleted` 钩子 | `session/search` |
| 3 长期记忆 | `<data_dir>/memory.db` + `<project>/.agent/memories/*.md` + `~/.agent/memories/*.md` | SQLite + FTS5(外部内容) + Markdown | `memory_save`、`memory/promote` | 系统提示词里的 overview、`memory_recall` |
| 4 代码索引 | `<data_dir>/code_index.db` | SQLite + float32 BLOB | `build_index` | `semantic_search` |
| 5 用量 | `~/.agent/logs/usage.jsonl` | JSONL + 轮转 | `_record_usage`（每任务 finally） | **没有任何 Shell 调用 `usage/report`**（§17.4） |

`data_dir` 由 `__main__.py` 按 `~/.agent/data/<projectId>` 给出（卷 03）；`AGENT_LOG_DIR` / `AGENT_USAGE_DIR` 可覆盖用量目录（`usage.default_log_dir`，28 行）。

### 0.3 一条贯穿全卷的不变量

> **每份状态只有一个权威源，其余都是派生视图；派生视图坏了，聊天必须照常。**

这条不变量在代码里表现为**成片的 `try/except` 包裹**：

```python
try:
    self._store.save(session)
except Exception:          # pragma: no cover - defensive
    LOGGER.exception("failed to persist session %s", session.session_id)
    return
```

（`SessionManager.save`，131-140）

同样的形状出现在 `_index`（142）、`list_sessions`（156）、`detail`（179）、`delete`（198）、`_on_session_saved` 的调用方、`usage.record`、`_capture_memories`、`_reindex_sessions`。**没有一个存储失败会冒泡到 `chat/*`**——这是设计，不是巧合。

代价是：磁盘满、数据库被锁、JSON 损坏时，用户**什么都看不到**，只有日志里一行 `failed to persist session ...`。§19 会把这条列进「易错点」第一条。

---

## 1. `agent/session.py`（226 行）：内存里的会话

### 1.1 三个常量与一个标题函数

```python
TITLE_CHARS = 40
MAX_SESSION_MESSAGES = 240

def make_title(text: str) -> str:
    flat = " ".join((text or "").split())
    if not flat:
        return ""
    return flat[:TITLE_CHARS]
```

（17-32）

`" ".join(text.split())` 是「压平空白」的标准写法：把所有连续空白（含 `\n`、`\t`、全角空格以外的一切 `str.split` 认得的字符）替换成单个半角空格，再去首尾。所以标题永远是单行。

`MAX_SESSION_MESSAGES = 240` 的注释值得逐字读：

> every request is already trimmed to the token budget, so keeping more than this only makes each send tokenize history that will never be sent (and that is what used to freeze long conversations).

翻译：每次发送本来只按 token 预算取最近若干条，内存里留更多只会让**每次发送都重新分词那些永远发不出去的历史**——这就是长对话卡死的根因。而 `ContextBuilder` 每次发请求都要对消息列表跑一遍 token 计数（卷 05），O(消息数) 的成本乘上 O(历史长度) 就是 O(n²)。

📌 所以 240 不是「能存多少」的限制，而是**性能补丁**。它跟 `SessionStore.MAX_MESSAGES = 240` 是同一个数字的两份拷贝（🔁 改一处必须改另一处，否则内存和磁盘的裁剪时机不一致）。

### 1.2 `display_messages()`（35-48）：什么是「可以回放的一条」

```python
for message in messages:
    content = message.content or ""
    if message.role == Role.TOOL:
        continue
    if message.role == Role.ASSISTANT and not content.strip():
        continue  # a round that only carried tool calls
    if message.role not in (Role.USER, Role.ASSISTANT):
        continue
    out.append({"role": message.role.value, "content": content})
```

三个过滤条件，顺序有意义：

1. **丢弃所有 TOOL 消息**——工具输出是给模型看的中间态，回放给用户只会刷屏。
2. **丢弃「只有工具调用、没有文字」的 assistant 消息**——这类消息 `content == ""`，渲染出来是一个空气泡。
3. **白名单兜底**：将来如果 `Role` 加了 `SYSTEM`，这里也不会把它漏给 Shell。

这个函数被四处复用：历史列表的 `messageCount`、`session/get` 的回放消息、全文索引的正文、`_index` 的 content 拼接。**一处改，四处的计数口径同时变**——这是好事（口径统一），但也意味着「历史列表里显示 12 条」和「打开后看到 12 个气泡」是对得上的，而模型实际看到的远多于 12 条。

> ⚠️ 由此产生一个常见误解：用户以为「历史里只存了 12 条」，其实磁盘上（最多 240 条）和下次发给模型的上下文里，工具流水一条不少。

### 1.3 `Session` 的七个非消息字段

```python
self.last_context: Optional[ContextSnapshot] = None
self.todos: List[Dict[str, Any]] = []
self.artifacts: List[Dict[str, Any]] = []
self.team_runs: List[Dict[str, Any]] = []
self.title: str = ""
self.updated_at: int = 0
self.deleted: bool = False
```

（54-69）

- `last_context`：上一次 `chat/start` 带来的 IDE 快照（当前文件、选区、打开的文件）。**注意它不落盘**——`SessionStore.save` 的 payload 里没有它。所以 Core 重启后「上次打开的文件」就丢了。
- `todos`：`todo_write` 的清单，**落盘**（v2 新增字段）。
- `artifacts` / `team_runs`：团队黑板交付物与成员运行记录，**落盘**（v3 新增，`store.py` 里叫 `artifacts` / `teamRuns`）。
- `title` / `updated_at`：历史列表要显示的两样东西。`updated_at` 是**毫秒**（`touch()` 用 `time.time() * 1000`），跟 SQLite 里 `memories.updated_at` 的**秒**不是一个单位（🔁 §5.1 会再提一次）。
- `deleted`：墓碑。见 §1.5。

### 1.4 `append` / `_trim_overflow`（71-85）：按「整轮」而不是按「条」裁剪

```python
def append(self, message: Message) -> None:
    self.messages.append(message)
    self._trim_overflow()

def _trim_overflow(self) -> None:
    while len(self.messages) > MAX_SESSION_MESSAGES:
        starts = [i for i, m in enumerate(self.messages) if m.role == Role.USER]
        if len(starts) < 2:
            return
        del self.messages[:starts[1]]
```

这是全卷最值得细看的 15 行。

**为什么必须按 USER 切**：一条 `assistant(tool_calls=[c1,c2])` 后面必须跟着 `tool(c1)` 和 `tool(c2)`。如果按条数硬切，切点可能落在中间，于是列表开头变成一条 `tool` 消息——发给 OpenAI 兼容端点会直接 400（`tool` 消息前面没有对应的 `tool_calls`）。测试 `test_cap_never_orphans_a_tool_result`（`test_session_store.py:130`）就在验证这件事。

**`while` 而不是 `if`**：一次 append 只多一条，理论上 `if` 就够。用 `while` 是为了容忍「上限被改小」或「批量恢复历史后超限」这类情况。

**`len(starts) < 2` 时直接 return**：只有一个 USER 轮次时**完全不裁剪**。这意味着：

> ⚠️ **单轮无限增长**：用户问一句「把整个仓库重构一遍」，模型在这一轮里连续 40 次调用工具，消息数可能冲到几百条。此时 `starts == [0]`，`_trim_overflow` 每次都 return，`session.messages` 无上限增长，直到 `loop` 的 `max_iterations` 兜住。内存和后续每次 token 计数的成本都飙在这一轮上。

这个缺口是真实存在的，只是被 `max_iterations=40`（`ChatConfig`）间接限住了。如果将来把 `max_iterations` 调大，这里会成为第一个卡点。

**`del self.messages[:starts[1]]`**：删掉「从开头到第二个 USER 之前」的全部内容，即第一整轮。于是列表永远以 USER 开头，且不会出现孤儿 TOOL。

### 1.5 墓碑：为什么 `delete` 要清空消息

```python
def delete(self, session_id: str) -> int:
    session = self._sessions.pop(session_id, None)
    if session is not None:
        session.deleted = True
        session.messages.clear()
```

（198-206）

场景：用户在历史里删掉一个会话，**同时**这个会话里还有任务在跑。任务的 `finally` 一定会执行 `self.sessions.save(session)`（`server.py:664`）。如果不打墓碑：

1. `delete` 删掉文件；
2. 任务结束，`finally` 里的 `save` 把内存里的会话**又写回磁盘**；
3. 用户刷新历史，被删的会话原地复活。

墓碑 + 清空消息是双重保险：`save` 看到 `deleted` 直接 return（133 行），即使它没看到，`messages` 已空，`SessionStore.save` 的第一行 `if not session.messages: return` 也会拦住（`store.py:106`）。

测试 `test_deleted_session_is_not_written_back_by_a_running_task`（`test_session_store.py:213`）把整条链路跑了一遍，并断言 `session.messages == []`。

### 1.6 `SessionManager`：六个方法，六层 try/except

| 方法 | 行 | 失败时返回 |
|---|---:|---|
| `get_or_create` | 107 | 落盘读失败 → 新建空会话（`store.load` 自己吞异常返回 `None`） |
| `save` | 131 | 记录日志，继续 |
| `_index` | 142 | 记录日志，继续（**不影响落盘**） |
| `list_sessions` | 156 | `[]` |
| `detail` | 179 | `{"id":..., "title":"", "updatedAt":0, "messages":[]}` |
| `delete` | 198 | `0` 字节 |

`get_or_create` 的顺序值得注意：

```python
session = self._sessions.get(session_id)
if session is not None:
    return session
if self._store is not None:
    restored = self._store.load(session_id)
    if restored is not None:
        ...
```

**内存优先**。这就是「Core 重启后能接着聊」的实现：新进程 `_sessions` 为空，第一次 `session/list` 之后的 `chat/start` 会走到 `load`，从 JSON 恢复消息。恢复的消息里 `last_context` 是 `None`，`todos` 从文件读。

### 1.7 `list_sessions` 的合并逻辑（156-177）

这是「磁盘列表 + 内存列表」的合并，两个方向都要照顾：

```python
by_id = {entry["id"]: entry for entry in entries}
for session in self._sessions.values():
    entry = by_id.get(session.session_id)
    if entry is None:
        entry = {"id": session.session_id, "preview": _first_user_text(session.messages),
                 "messageCount": 0}
        by_id[session.session_id] = entry
    entry["title"] = session.title or entry.get("title") or ""
    entry["messageCount"] = len(display_messages(session.messages))
    entry["updatedAt"] = max(int(entry.get("updatedAt") or 0), session.updated_at)
```

三个要点：

1. **内存里的会话一定覆盖磁盘的数字**。`messageCount` 直接重算，`updatedAt` 取 `max`（内存可能更新但还没落盘）。
2. **`preview` 不覆盖**。刚创建、还没落盘的会话，`preview` 用 `_first_user_text` 现算；已在磁盘上的会话，`preview` 保留磁盘值（`store._list_entry` 里 `_preview` 取的是**第一条 user**，与 `_first_user_text` 语义一致）。
3. **`entry["title"] = session.title or entry.get("title") or ""`**：内存标题优先，但内存标题为空时不覆盖磁盘标题。

> ⚠️ 这里有一个「删了又出现」的边缘：`_sessions` 里的会话如果 `deleted=True`（删除瞬间还有一个协程没跑完），它仍会出现在 `list_sessions` 的合并循环里。但 `delete` 已经把它 `pop` 出 `_sessions`，所以不会。真正的问题在别处——`list_sessions` 从磁盘读到的列表**最多 50 条**（`MAX_LIST_ITEMS`），内存会话却全量并入，于是「内存里有 8 个标签 + 磁盘 50 条」可能算出 58 行。Shell 侧 `MAX_HISTORY_ROWS = 30` 再截一次（`AgentChatPanel.java:262`）。三层上限，各管一段。

### 1.8 `detail`（179-196）：回放 + 清单

```python
return {
    "id": session_id,
    "title": session.title or make_title(_first_user_text(session.messages)),
    "updatedAt": session.updated_at,
    "messages": display_messages(session.messages),
    "todos": list(session.todos),
}
```

`todos` 是 v2 才有意义的字段。老 JSON 没有它 → `normalize_todos(None) == []` → Shell 画一个空清单条。

⚠️ `updatedAt` 在这里**不和磁盘值取 max**，直接用内存对象的。如果会话是从磁盘 `load` 出来的，值一致；如果是内存里新会话，值是最新 `touch()` 的。看起来没问题，但和 `list_sessions` 的 `max()` 语义不一致——两个接口对同一个会话可能报出不同的 `updatedAt`（差几毫秒，无害但不对称）。

---

## 2. `agent/store.py`（249 行）：一个会话一个 JSON

### 2.1 常量表

| 常量 | 值 | 作用 |
|---|---|---|
| `STORE_VERSION` | 3 | v2 加 `todos`；v3 加团队黑板（`artifacts` / `teamRuns`）；旧文件缺键也能读 |
| `MAX_MESSAGES` | 240 | 落盘条数上限（与 `MAX_SESSION_MESSAGES` 同步） |
| `MAX_LIST_ITEMS` | 50 | 历史列表最多返回多少条 |
| `PREVIEW_CHARS` | 80 | 列表里那行摘要的长度 |
| `TEMP_MAX_AGE_SEC` | 3600 | `.tmp` 超过 1 小时视为崩溃残留 |
| `LIST_THREADS` | 8 | 超过 8 个文件时用线程池读 |

### 2.2 `_file_name`（43-46）：sessionId → 文件名

```python
cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", session_id or "")
return (cleaned or "session")[:120] + ".json"
```

`sessionId` 形如 `3f2a9c1e7b#a1b2c3d4`（§2 全局编号），`#` 在 Windows 文件名里合法，但在某些工具/同步盘里是雷，所以统一替换成 `_`。

> ⚠️ **两处静默风险**：
> 1. **碰撞**：`a#b` 与 `a_b` 映射到同一个文件。真实 id 里 `#` 只出现在 projectId 与 uuid 之间一次，且 projectId 是 location hash（十六进制），所以碰撞概率极低——但这是「靠 id 生成格式保证」的，不是代码保证的。
> 2. **截断**：`:120`。`projectId`（32 位 hex）+ `#` + 8 位 = 41 字符，远小于 120，安全。但如果有 Shell 用更长的 id（VS Code 侧可自定义），截断后两个 id 可能撞车，后写的覆盖前写的，且无任何提示。

### 2.3 `_decode` / `_encode`（49-73）：camelCase 的落盘格式

```python
item["toolCalls"] = [{"id": call.id, "name": call.name, "arguments": call.arguments} ...]
item["toolCallId"] = message.tool_call_id
```

Python 侧字段是 `tool_calls` / `tool_call_id`，落盘是 `toolCalls` / `toolCallId`。**这与 RPC 的命名风格一致**（`toolCallId`、`taskId`），而不是 Python 的 snake_case——因为同一份数据两边都要读。

`_decode` 的容错：

```python
try:
    role = Role(str(raw.get("role") or ""))
except ValueError:
    return None
```

未知 role 的消息**被静默丢弃**，不报错。将来如果加了 `Role.SYSTEM`，旧版 Core 读新文件会少一条消息而不是崩溃。这是对的取舍。

`arguments=c.get("arguments") or {}`：模型没给参数时落 `{}` 而不是 `null`，避免下游 `None.get`。

### 2.4 `load`（84-103）：三行恢复 + 一条消息一条

```python
session = Session(session_id)
session.title = str(data.get("title") or "")
session.updated_at = int(data.get("updatedAt") or 0)
session.todos = normalize_todos(data.get("todos"))
session.artifacts = normalize_artifacts(data.get("artifacts"))
session.team_runs = _normalise_records(data.get("teamRuns"))
for raw in data.get("messages") or []:
    message = _decode(raw)
    if message is not None:
        session.messages.append(message)
```

注意这里**直接 `append` 到列表**，而不是 `session.append()`——后者会触发 `_trim_overflow`，逐个 append 会做 O(n²) 次扫描（每次重新找 USER 起点）。读的是已经裁剪过的文件，不需要再裁。📌 这是一个「用错方法就要付平方代价」的典型点。

`normalize_todos` 是唯一对 `todos` 做的校验：旧文件、手改坏的文件、模型乱写的结构，都会被它压成合法结构（§4.2）。团队两个字段同理：`normalize_artifacts` 逐条归一，`_normalise_records` 只留 dict 行（`store.py:245-249`）。

### 2.5 `save`（105-126）：原子写的标准姿势

```python
messages = session.messages[-MAX_MESSAGES:]
while messages and messages[0].role == Role.TOOL:
    messages.pop(0)
...
path = self._path(session.session_id)
tmp = path.with_name(path.name + ".tmp")
self.root.mkdir(parents=True, exist_ok=True)
tmp.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
os.replace(tmp, path)
```

几个要点：

1. **`if not session.messages: return`**：空会话不落盘。新建标签页、发第一条消息前，磁盘上什么都没有。
2. **`[-MAX_MESSAGES:]` 是按条硬切**，与 `Session._trim_overflow` 的「按 USER 整轮切」**语义不同**。硬切之后，切点可能落在 `assistant(tool_calls)` 上——前面那条 USER 被丢掉，但后面的 TOOL 结果还在。此时文件开头的消息是 assistant，不会被 `while` 循环弹掉。
   - 对大多数端点，以 assistant 开头是合法的（消息列表本来就可以不以 user 开头）。
   - 但对「必须 user 开头」的严格端点，**磁盘恢复出来的会话第一次发送可能 400**。内存里的会话不会（它按整轮切）。🔁 这是一个「同一份数据两种裁剪规则」带来的不对称。
3. **`while messages and messages[0].role == Role.TOOL: messages.pop(0)`**：兜住「切点正好落在 TOOL」的情况。`pop(0)` 在 240 条的列表上是 O(n²)，但最多弹几条，无所谓。
4. **`.tmp` + `os.replace`**：`Path.replace`/`os.replace` 在 POSIX 与 Windows 上都是原子的（同一卷内）。所以任何时刻磁盘上的那个 `.json` 要么是旧的完整内容，要么是新的完整内容，**不会出现半个 JSON**。
5. **`ensure_ascii=False`**：中文直接落 UTF-8，文件可读、可 diff、可 grep。代价是文件比 `\uXXXX` 小得多（好事）。

`title` 的兜底：`session.title or make_title(_first_user_text(session.messages))`——`chat/start` 已经设过标题（`server.py:500`），这里只是兜底。

payload 里还有 `todos` / `artifacts` / `teamRuns` 三个派生字段（112-121 行），与 §2.4 的恢复逐项对应；`version: 3` 就在同一个 payload 里。

`updatedAt` 的兜底：`session.updated_at or int(time.time() * 1000)`——**毫秒**。

### 2.6 `list_sessions`（130-142）：8 是并行阈值，不是限流

```python
paths = list(self.root.glob("*.json"))
if len(paths) <= LIST_THREADS:
    entries = [entry for entry in map(_list_entry, paths) if entry is not None]
else:
    with ThreadPoolExecutor(max_workers=LIST_THREADS) as pool:
        entries = [entry for entry in pool.map(_list_entry, paths) if entry is not None]
```

注释说得很直白：*the per-file round trip (NTFS is the slow part, not the JSON)*。JSON 解析很快，慢的是**打开文件**。8 个文件以下，开池的开销大于收益；以上，8 路并发把 NTFS 的延迟叠起来。

> ⚠️ 这个阈值是**经验值**，没有 benchmark 支撑（`core/scripts/bench_core.py` 压的是别的路径）。在 SSD 上、50 个会话时，串行大约 50×0.2ms=10ms，并行可能更慢——列表本身是**用户点开历史才触发**的操作，10ms 完全无感。📌 这里真正解决的不是延迟，而是「网络盘/同步盘上单个打开可能卡 100ms」的尾延迟。

`entries.sort(key=...updatedAt, reverse=True)[:MAX_LIST_ITEMS]`：**先全读、再排序、再截 50**。没有「读到 50 条就停」的优化，因为文件名和修改时间不可靠（`os.replace` 不改文件名），必须读内容才知道 `updatedAt`。

### 2.7 `_list_entry`（206-226）：列表行就是「计数 + 摘要」

```python
messages = [m for m in (data.get("messages") or [])
            if m.get("role") in ("user", "assistant")
            and str(m.get("content") or "").strip()]
return {
    "id": session_id,
    "title": str(data.get("title") or ""),
    "preview": _preview(messages),
    "messageCount": len(messages),
    "updatedAt": int(data.get("updatedAt") or 0),
}
```

注意过滤条件比 `display_messages` **更严**：这里额外要求 `content.strip()` 非空，而 `display_messages` 只对 assistant 做这个判断（USER 的空消息会保留）。差异原因：列表计数要跟「用户看到的气泡数」一致，空气泡不该计入。实际影响：一条空的 USER 消息在 `detail` 里会被回放成空气泡，但不计入 `messageCount`——**两个数字可能差 1**。

`_preview`（235-243）取第一条非空 user 的压平文本，截 80 字符。没有省略号——80 字符处直接断，Shell 侧自己加 `…`（`AgentChatPanel` 的 `HistoryEntry.tooltip`）。

也注意 **`_list_entry` 与 `iter_documents` 各自重新实现了「哪些消息算正文」**，逻辑相同、代码两份。🔁 将来加 `Role.SYSTEM` 时，这两处都要改。

### 2.8 `delete`（144-161）：返回「真正释放的字节数」

```python
for target in (path, path.with_name(path.name + ".tmp")):
    try:
        freed += target.stat().st_size
        target.unlink()
    except FileNotFoundError:
        continue
    except OSError:
        LOGGER.warning("failed to delete %s", target, exc_info=True)
return freed
```

这个返回值一路传到用户面前：`session/delete` → `{"deleted": freed > 0, "freedBytes": freed}` → Java 的 `history.deletedWithFreed`（`AgentChatPanel.java:799`）。**先 `stat` 再 `unlink`** 是必须的，`unlink` 之后就量不到大小了。

`.tmp` 也一起删：一个崩溃残留的 `.tmp` 可能比正式文件还大，不删就是「删了但没释放空间」。测试 `test_delete_frees_bytes_and_drops_temp_file`（`test_session_store.py:73`）断言 `freed >= file_size + len("leftover")`。

### 2.9 `_sweep_temps`（194-204）：只在列表时打扫

```python
for path in self.root.glob("*.tmp"):
    if now - path.stat().st_mtime > TEMP_MAX_AGE_SEC:
        path.unlink()
```

为什么不在 `save` 里打扫？因为 `save` 是热路径（每任务一次），`list_sessions` 是冷路径（用户点开历史）。为什么用 1 小时而不是立即删？因为**正在写的 `.tmp` 也可能存在**：两个 Core 进程（不同项目共享 data_dir 不可能，但同一个项目被 IDE 重启两次可能短暂并存）同时写同一个会话时，立即删会删掉对方正在写的临时文件。1 小时远大于一次 `write_text` 的耗时，安全。

⚠️ `_sweep_temps` 只清 `.tmp`，不校验对应 `.json` 是否存在。所以一个「只有 `.tmp` 没有 `.json`」的会话（第一次保存就崩了）在 1 小时后连痕迹都没了——这是期望行为（它从来没有成为过会话），但用户如果期待「崩溃恢复草稿」，这里没有。

### 2.10 `iter_documents`（163-192）：给全文索引的一次性迁移

```python
for message in data.get("messages") or []:
    if message.get("role") not in ("user", "assistant"):
        continue
    text = str(message.get("content") or "").strip()
    if text:
        parts.append(text)
yield {"id": session_id, "title": ..., "content": "\n".join(parts), "updatedAt": ...}
```

它是 `_reindex_sessions`（`server.py:323`）的输入：Core 每次启动在后台跑一遍，把老版本存的文件补进索引。**每启动一次就全量扫一遍**，靠 `_index_one` 的 `updated_at` 单调判断跳过（§3.4），所以稳态开销是「读 N 个 JSON + N 次 SQLite 查询」。

📌 这里 `sorted(self.root.glob("*.json"))` 的排序对结果没有意义（索引按 id 覆盖），但**对进度上报有意义**：同样的文件集合，进度条的出现顺序稳定，便于复现问题。

---

## 3. `agent/search.py`（211 行）：历史会话检索

### 3.1 为什么要有「镜像表 + FTS 表」两份

```python
FTS_SCHEMA = """
CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
  session_id UNINDEXED, title, content, tokenize='trigram'
);
"""
```

（32-36）

文件头注释（1-11）把原因写全了：

> FTS5's default tokenizer treats a whole CJK run as a single token; the `trigram` tokenizer matches substrings instead, and the mirror lets short queries fall back to LIKE.

翻译：

1. **默认分词器对中文是灾难**：`前缀缓存命中率` 会被当成**一个** token，搜「缓存」搜不到。
2. **`trigram` 分词器**（SQLite 3.34+）按每 3 个字符切，于是任何长度 ≥3 的子串都能命中——中文天然满足。
3. **2 个字的查询**（「缓存」「命中」）在 trigram 下**无法建索引**，必须走 `LIKE`。这就是 `sessions_text` 这张普通表存在的理由：`LIKE` 需要一个能全表扫的正文列。

所以两张表是「同一份数据的两种索引」，写入时**必须同时更新**（131-137）。

### 3.2 `_init_schema`（65-73）：FTS 不可用就降级

```python
connection.executescript(SCHEMA)
try:
    connection.executescript(FTS_SCHEMA)
    self.fts_enabled = True
except sqlite3.OperationalError:
    LOGGER.warning("FTS5 trigram unavailable, session search uses LIKE")
```

`fts_enabled` 是**实例级**标志，不是全局。如果用户的 Python 自带 SQLite 太老（<3.34）或编译时没开 FTS5，`CREATE VIRTUAL TABLE` 抛 `OperationalError`，整个检索退化成 `LIKE`——功能还在，只是慢且只能匹配连续子串。

📌 这是「可选能力探测」的标准写法：**探测一次、记在实例上、所有写入路径都看这个标志**。注意 `remove`（140）也检查它，否则会在没有 FTS 表时执行 `DELETE FROM sessions_fts` 而抛异常。

### 3.3 `index` / `index_many`（85-111）

```python
def index(self, session_id, title, content, updated_at) -> bool:
    with self._connect() as connection:
        return self._index_one(connection, session_id, title, content, updated_at)
```

每次开一个新连接（`sqlite3.connect`，timeout 5s）。`index` 是「每任务一次」的热路径，开销可接受。

`index_many` 则明确为迁移优化：

```python
with self._connect() as connection:
    for document in documents:
        seen += 1
        if self._index_one(...):
            changed += 1
        if progress is not None and seen % 25 == 0:
            progress(seen, changed)
return seen
```

**一个连接跑完全部**——否则 1000 个会话就是 1000 次开关库。注释里写明了这个理由（94-97）。

`progress` 每 **25** 条回调一次（不是每条），因为跨线程回调要 `loop.call_soon_threadsafe` + 写 stdout，成本不低（`server.py:336-339`）。

⚠️ 返回值语义容易搞混：`index()` 返回「是否写入」，`index_many()` 返回 **`seen`（总数）**，不是 `changed`。`server.py:330` 的 `if total:` 只是判断「有没有扫到东西」。

### 3.4 `_index_one`（113-138）：`>=` 一个符号决定了「同毫秒更新会丢」

```python
content = (content or "")[:MAX_INDEX_CHARS]
row = connection.execute("SELECT updated_at FROM sessions_text WHERE session_id = ?", ...).fetchone()
if row is not None and int(row["updated_at"]) >= int(updated_at):
    return False
```

三个可讲的点：

**① `MAX_INDEX_CHARS = 200_000` 静默截断。** 超长会话只索引前 20 万字符（约 6 万 token 的中文）。后半段的对话搜不到，且没有任何提示。为什么是 20 万：SQLite 单行无硬限，但 FTS5 索引体积与插入耗时随长度线性增长，20 万字符大约几百 KB，一份索引文件还是 MB 级。

**② `>=` 而不是 `>`。** 同一毫秒内的第二次保存**会被跳过**。`updated_at` 来自 `Session.touch()`（毫秒），两次 `save` 在同一毫秒完全可能（一次任务结束 + 一次手动改名）。后果：索引里的正文是**上一毫秒的版本**，直到下一次保存 ≥1ms 后才追上。测试 `test_reindex_is_skipped_when_not_newer`（`test_session_search.py:54`）把这个行为**固化成了契约**：

```python
self.assertTrue(self.search.index("s1", "t", "first body", 1000))
self.assertFalse(self.search.index("s1", "t", "second body", 1000))
```

**③ FTS 更新是 DELETE + INSERT**，不是 UPDATE：

```python
connection.execute("DELETE FROM sessions_fts WHERE session_id = ?", (session_id,))
connection.execute("INSERT INTO sessions_fts(session_id, title, content) VALUES (?,?,?)", ...)
```

FTS5 虚拟表支持 `UPDATE`，但对「非 rowid 主键」的外部映射，DELETE+INSERT 更稳且更简单。两次写在同一连接、同一隐式事务里，不会出现「删了没插」的中间态。

### 3.5 `search`（149-161）：一条岔路

```python
text = _WS_RE.sub(" ", (query or "")).strip()
if not text:
    return []
limit = max(1, min(int(limit or DEFAULT_LIMIT), MAX_LIMIT))
terms = [term for term in text.split(" ") if len(term) >= 3]
if self.fts_enabled and terms:
    try:
        return self._search_fts(terms, limit)
    except sqlite3.OperationalError:
        LOGGER.warning("session FTS query failed, falling back to LIKE", exc_info=True)
return self._search_like(text, limit)
```

- 空白归一化成单空格（`_WS_RE = re.compile(r"\s+")`），所以「prefix   cache」与「prefix cache」等价。
- `limit` 夹在 `[1, 50]`。Java 传的是 30（`MAX_HISTORY_ROWS`）。
- **`len(term) >= 3` 是 trigram 的硬约束**：2 字符的 term 交给 FTS 会命中不了（trigram 索引里没有 2 字符条目），而且 `MATCH` 可能直接报错。所以短词一律留给 LIKE。
- **`terms` 为空**（整句都是 1-2 字的词，比如「缓存」）：直接走 LIKE。

> ⚠️ **两条路径的查询语义不同**，这是本文件最大的坑：
> - `_search_fts` 用 `"term1" OR "term2"`，**按词 OR**，命中任意一个词就算。
> - `_search_like` 用 `needle = "%" + text + "%"`，**用整句（含空格）做子串匹配**。
>
> 所以搜「prefix cache」时：如果 FTS 可用 → 匹配含 `prefix` **或** `cache` 的会话；如果 FTS 不可用（老 SQLite）→ 只匹配正文里连续出现 `prefix cache` 的会话。同一个查询，两种结果集。用户不会知道自己的 SQLite 是哪个版本。

### 3.6 `_search_fts`（163-180）：`snippet` + `bm25`

```python
match = " OR ".join('"%s"' % term.replace('"', " ") for term in terms)
rows = connection.execute(
    "SELECT session_id, title, "
    "snippet(sessions_fts, 2, ?, ?, '…', ?) AS snippet, "
    "(SELECT updated_at FROM sessions_text t WHERE t.session_id = sessions_fts.session_id) AS updated_at "
    "FROM sessions_fts WHERE sessions_fts MATCH ? "
    "ORDER BY bm25(sessions_fts) LIMIT ?",
    (MARK_OPEN, MARK_CLOSE, SNIPPET_TOKENS, match, limit),
).fetchall()
```

- `term.replace('"', " ")`：把用户输入里的引号换成空格，**防 FTS 语法注入**（`"` 是 FTS 的短语界定符，未转义会破坏 `MATCH` 表达式并抛 `OperationalError`）。
- `snippet(..., 2, ...)` 的 `2` 是**列序号**（0=session_id, 1=title, 2=content）——只看正文，不看标题。
- `SNIPPET_TOKENS = 16`：片段约 16 个 token 长（trigram 下每 token 3 字符，约 48 字符）。
- `'…'` 是省略号，FTS5 在片段首尾自动加。
- `bm25(sessions_fts)` 返回**负数**（越相关越小），所以 `ORDER BY` 直接升序——这里不需要 `DESC`。
- `updated_at` 用**子查询回镜像表**取，因为 FTS 表里没有这一列（`session_id UNINDEXED` 只存了 id）。

### 3.7 `_search_like`（182-196）与 `_snippet`（199-211）

```python
needle = "%" + text + "%"
rows = connection.execute(
    "SELECT session_id, title, content, updated_at FROM sessions_text "
    "WHERE title LIKE ? OR content LIKE ? "
    "ORDER BY updated_at DESC LIMIT ?", (needle, needle, limit)).fetchall()
```

`LIKE` 排序是**按时间**，不是按相关性——完全没有打分。所以「短查询」返回的是「最近提到过这个词的 30 个会话」，而不是「最相关的 30 个」。

`_snippet` 手工打 `<mark>`：

```python
index = flat.lower().find(query.lower())
if index < 0:
    return flat[:width * 2]
start = max(0, index - width)
end = min(len(flat), index + len(query) + width)
head = "…" if start > 0 else ""
tail = "…" if end < len(flat) else ""
return "%s%s%s%s%s%s" % (head, flat[start:index], MARK_OPEN,
                         flat[index:index + len(query)], MARK_CLOSE,
                         flat[index + len(query):end] + tail)
```

`width=80`，所以片段最长 160 + 匹配词长度。`head`/`tail` 手动补省略号，与 FTS 路径的 `'…'` 视觉一致。

⚠️ **`index < 0` 的分支**返回 `flat[:160]`，**没有 mark**。什么时候会命中这个分支：`title` 里有词、`content` 里没有（SQL 是 `title LIKE OR content LIKE`）。于是 Shell 拿到一个「高亮了零处」的 snippet，`stripTags` 后照常显示——看上去像「搜到了但不知道为什么」。

⚠️ 用户输入里的 `<`、`>` **不转义**就拼进 `<mark>` 包围的 HTML。Java 侧 `HistoryEntry` 用 `snippetHtml` 直出（`AgentChatPanel.java:427` 前后，`stripTags` 只剥标记）。如果某个会话正文里含 `<script>`，而它正好被搜到，标签会进到 Swing 的 HTML 渲染里。Swing 的 `HTMLEditorKit` 不执行脚本，所以不是 XSS，但**会破坏排版**（未知标签被忽略或吞字符）。这是一处「没转义」的诚实缺陷。

### 3.8 `indexed_updated_at`（77-83）：一个只给测试用的查询

```python
row = connection.execute("SELECT updated_at FROM sessions_text WHERE session_id = ?", ...).fetchone()
return int(row["updated_at"]) if row else 0
```

生产代码里没有调用方（grep `indexed_updated_at` 只命中 `test_session_search.py:65`）。它的价值是让测试能断言「索引确实删掉了」而不用翻数据库文件。📌 读代码时看到「只有测试在用」的方法不用怀疑有隐藏逻辑，它就是测试钩子。

---

## 4. `agent/todos.py`（128 行）：清单的状态部分

> 工具的语义（什么时候列清单、`in_progress` 只能有一个）在卷 04 讲过。这里只讲它**为什么属于状态层**：`todo_write` 往 `Session.todos` 上写，而这个字段会随会话落盘。

### 4.1 `normalize_todos`（72-89）：把模型的输出压成可渲染结构

```python
def normalize_todos(raw: Any) -> List[Dict[str, str]] -> ...
```

真实实现逐条过：

```python
if not isinstance(raw, list):
    return []
for entry in raw:
    if not isinstance(entry, dict):
        continue
    content = str(entry.get("content") or "").strip()
    if not content:
        continue
    status = str(entry.get("status") or "pending").strip().lower()
    if status not in STATUSES:
        status = "pending"
    todo_id = str(entry.get("id") or "").strip() or str(len(todos) + 1)
    todos.append({"id": todo_id, "content": content, "status": status})
```

四道过滤：

1. **不是 list → 空清单**（不是异常）。
2. **条目不是 dict → 丢**。
3. **`content` 空 → 丢**（一个没有文字的待办无法渲染）。
4. **`status` 不在四个合法值里 → 降级成 `pending`**（不丢条目，因为内容还是有用的）。

`todo_id` 的兜底是 `str(len(todos) + 1)`：模型漏给 id 时用「清理后的位置」当 id。这带来一个微小问题——**同一张清单两次规范化，漏 id 的条目可能拿到不同 id**（如果前一次有被丢弃的条目）。实际影响为零（id 只用于渲染序号），但读代码时要知道它不是稳定标识。

📌 `normalize_todos` 被两边调用：`SessionStore.load`（读文件时校验）与 `make_handler`（写之前校验）。于是**磁盘上的 todos 永远是规范化过的**，`Session.todos` 在内存里可能有未规范化的瞬间，但落盘前一定过一遍。

### 4.2 上下文变量：清单属于「正在跑的那个会话」

```python
_active_session: contextvars.ContextVar[Optional[Session]] = contextvars.ContextVar(
    "todo_session", default=None)

def bind_session(session: Session):
    return _active_session.set(session)
```

（59-69）

`ToolRegistry` 只存「名字 → 无参处理函数」，处理函数拿不到「当前是哪个会话」。而 `todo_write` 必须知道往哪写。解法是 `contextvars`：

```python
# server.py:523
todo_token = todo_tools.bind_session(session)
...
# server.py:594（finally）
todo_tools.reset_session(todo_token)
```

为什么不用全局变量：并发会话（多个标签同时跑）会互相覆盖。`ContextVar` 在 `asyncio` 里是**每个 Task 一份**，所以 `chat/start` 起的两个任务各看各的。为什么用 token + reset 而不是 `set(None)`：`reset` 能精确恢复上层值（如果将来有嵌套），且不会泄漏引用——`set(None)` 之后旧 Session 对象仍被闭包捕获直到 GC。

⚠️ 但注意：`bind_session` 在 `_run_task` 里调用，而 `_run_task` 由 `asyncio.create_task` 启动。**Task 会复制父上下文的 ContextVar 值**，所以 `bind_session` 必须在 Task 内部（而不是 `_chat_start` 里）执行——代码确实是这样（`server.py:517-523`）。如果哪天有人把它挪到 `create_task` 之前，「清单写到别人会话里」的 bug 就会立刻出现。

### 4.3 落盘链路

```
todo_write(todos) → normalize → session.todos = todos → publish(→ chat/todos 通知)
                                                  └→ 任务结束 finally: sessions.save(session)
                                                        └→ payload["todos"] → JSON v2
```

Shell 收到 `chat/todos` 时立刻渲染；用户关掉 IDE 再打开，`session/get` 返回 `todos`，`ChatSessionPanel.parseTodos` 还原成清单条（卷 08）。

> 🔁 **v1 → v2 的兼容**：老文件没有 `todos` 键 → `normalize_todos(None) → []`。新文件被老 Core 读 → 未知键被忽略。双向兼容，不需要迁移脚本。这就是「加法式 schema 演进」的好处：**永远只加字段，不改字段语义，不删字段**。

---

## 5. `memory/store.py`（260 行）：记忆的 SQLite 索引

### 5.1 表结构与那个「唯一索引」

```sql
CREATE TABLE IF NOT EXISTS memories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  content TEXT NOT NULL,
  category TEXT NOT NULL,
  keywords TEXT NOT NULL,
  scope TEXT NOT NULL,
  project_path TEXT,
  created_at INTEGER,
  updated_at INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_identity
  ON memories(title, scope, IFNULL(project_path, ''));
```

（18-32）

**`keywords` 是 JSON 字符串**（`json.dumps(list)`），不是关联表。取舍：记忆条数在千级，一次 `LIKE '%kw%'` 全表扫可以接受；换成关联表要三次 join、还要处理「删一关键词」，不值。

**唯一索引的三个列**决定了「什么算同一条记忆」：

| 场景 | 是否覆盖 |
|---|---|
| 同 title、同 scope、同项目 | ✅ 覆盖（`save` 走 UPDATE） |
| 同 title、同 scope、不同项目 | ❌ 两条独立记忆（每个项目一份） |
| 同 title、project vs global | ❌ 两条（作用域不同） |

`IFNULL(project_path, '')` 是必须的：SQLite 的唯一索引把多个 `NULL` 视为**互不相等**，global 记忆的 `project_path` 是 `NULL`，不加 `IFNULL` 就会允许无限多条同名 global 记忆。

⚠️ **`updated_at` 是秒**（`int(time.time())`），而 `Session.updated_at` 是**毫秒**。两个「updatedAt」在 RPC 上出现时一个是 13 位、一个是 10 位。Shell 目前不显示记忆的时间，所以没暴露；但任何「按时间排序合并两种对象」的代码都会踩到。

### 5.2 FTS5 外部内容表 + 三个触发器

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
  title, content, keywords, content='memories', content_rowid='id'
);
CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN ... END;
CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN ... END;
CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN ... END;
```

（34-52）

`content='memories'` 是**外部内容表**模式：FTS 表不存正文副本，只存倒排索引，正文从 `memories` 读。省一半空间，代价是必须用触发器同步。

三个触发器的写法是 FTS5 官方模板：`ai` 插入、`ad` 用 `('delete', old...)` 这一特殊行从索引里删、`au` 先删旧再插新。**`au` 必须先 delete 再 insert**，直接 insert 会留下重复条目。

📌 用触发器而不是在 `save`/`delete` 里手写同步：**任何路径**（包括将来新增的批量导入、手工 SQL）都自动一致。代价是「写一行 memories 会隐式写一次 FTS」，INSERT 稍慢；但记忆写入是低频操作（一次对话最多几条），完全不敏感。

⚠️ `keywords` 列存的是 JSON 串 `["maven", "compile"]`，被 FTS 索引后可以按词命中（JSON 的标点不算分隔符，但 trigram/unicode61 能切开），所以搜 `maven` 能命中关键词。副作用：**搜 `[` 或 `"` 也会命中**——极端查询会有噪音结果。

### 5.3 `save`（90-112）：upsert 手写版

```python
row = connection.execute(
    "SELECT id FROM memories WHERE title = ? AND scope = ? "
    "AND IFNULL(project_path, '') = IFNULL(?, '')",
    (record.title, record.scope, record.project_path)).fetchone()
if row is None:
    cursor = connection.execute("INSERT INTO memories(...) VALUES (?,?,?,?,?,?,?,?)", ...)
    return int(cursor.lastrowid)
connection.execute("UPDATE memories SET content = ?, category = ?, keywords = ?, updated_at = ? WHERE id = ?",
                   (record.content, record.category, keywords, now, row["id"]))
return int(row["id"])
```

不用 `INSERT ... ON CONFLICT DO UPDATE` 的原因：**需要区分「新增」与「覆盖」以便返回 id**（`ON CONFLICT` 也能配合 `RETURNING`，但 `RETURNING` 要 SQLite 3.35+，而 trigram 只要 3.34+，差一个版本）。手写「先查后写」在两个版本上都成立。

⚠️ 更新时**只改 4 个字段**：`content`、`category`、`keywords`、`updated_at`。`title`、`scope`、`project_path` 是身份的一部分（改了就变成另一条记忆），`created_at` 保持不变。所以「改名」在记忆系统里**做不到**——只能新建 + 删除。这也是 §7.4 那个「旧 md 文件残留」bug 的根源。

### 5.4 `find_similar`（144-169）：FTS 命中 + 最近 50 条，一起打分

```python
terms = derive_terms(text)
candidates = self.search(terms, scope=scope, project_path=project_path, limit=10)
for record in candidates + self.list_all(scope, project_path)[:50]:
    if record.id in seen: continue
    ...
    score = similarity(text, "%s\n%s" % (record.title, record.content))
    if score > best_score:
        best, best_score = record, score
return best if best_score >= threshold else None
```

两个来源合并去重的理由（注释原文）：

> The FTS hit list alone is not enough: a CJK sentence is one token to the default tokenizer, so a rephrased fact often misses the index. The most recently updated records are therefore scored as well.

翻译：FTS 默认分词对中文长句切得稀烂，**换个说法就搜不到**。所以除了 FTS 的 10 条，还把**最近更新的 50 条**一起算相似度——用字符二元组（§5.6），绕开分词。

`threshold = 0.55`：字符二元组重叠率过半才算「可能是同一条」。这个数很主观，但测试 `test_promote_reports_a_likely_duplicate`（`test_memory_pending.py:168`）用「以后用中文写提交信息」vs「以后都用中文写提交信息」验证过它够灵敏。

⚠️ 代价：**每条候选记忆要跑一次「FTS 10 条 + 全表 50 条」的相似度计算**。`list_all` 是全表 `SELECT *`（不带 LIMIT 的 SQL，Python 侧才 `[:50]`）——记忆到几万条时，这一步会开始卡。当前规模（单项目几百条）无感。

### 5.5 `search`（171-206）：FTS 与 LIKE 的又一条岔路

```python
terms = [k.strip() for k in keywords if k and k.strip()]
if not terms:
    return self.list_all(scope, project_path)[:limit]
```

**空关键词 → 返回最近的 N 条**（`list_all` 按 `updated_at DESC`）。这是「不知道该搜什么」时的合理兜底。

FTS 分支的 SQL 值得逐字看：

```sql
SELECT m.* FROM memories m JOIN memories_fts f ON f.rowid = m.id
WHERE memories_fts MATCH ?
  AND (? IS NULL OR m.scope = ?)
  AND (? IS NULL OR IFNULL(m.project_path, '') = ?)
ORDER BY bm25(memories_fts) LIMIT ?
```

`(? IS NULL OR m.scope = ?)` 是「可选过滤」的经典写法：传 `None` 时条件恒真。**参数要传两遍**（`scope, scope`），这是位置参数的必然。

`LIKE` 分支是动态拼的：

```python
like = " OR ".join(["title LIKE ? OR content LIKE ? OR keywords LIKE ?"] * len(terms))
```

N 个词 → 3N 个 `?`，再追加 4 个过滤参数 + limit。**任何词命中任一列就算命中**（OR 语义）。

⚠️ 两个分支的排序不同：FTS 按 `bm25`（相关性），LIKE 按 `updated_at DESC`（时间）。同一个查询在两种后端下返回顺序完全不同——和 §3.5 的会话检索是同一类问题，说明这不是某一个人的疏忽，而是「FTS 可选」这个设计带来的系统性代价。

### 5.6 `derive_terms` 与 `similarity`（209-242）

```python
def derive_terms(text: str, limit: int = 6) -> List[str]:
    for match in re.findall(r"[A-Za-z_][A-Za-z0-9_.\-]{1,30}", text or ""):
        word = match.strip("._-")
        if len(word) >= 2 and word not in terms:
            terms.append(word)
        if len(terms) >= limit: return terms
    for match in re.findall(r"[\u4e00-\u9fff]{2,6}", text or ""):
        ...
```

先取英文标识符（`CamelCase`、`snake_case`、`a.b`），再取 2-6 字的连续中文。**先后顺序有意义**：一段中英混排的文字，英文术语通常更区分度（`Semaphore` vs 「使用」）。

`limit=6` 与 `find_similar` 的 FTS 候选 10 条配合：6 个词 OR 起来已经能捞到不少，再多只会引入噪音。

```python
def _bigrams(text: str):
    flat = re.sub(r"[\s\W_]+", "", (text or "").lower())
    return {flat[i:i + 2] for i in range(len(flat) - 1)}

def similarity(left: str, right: str) -> float:
    first, second = _bigrams(left), _bigrams(right)
    if not first or not second:
        return 0.0
    return len(first & second) / float(min(len(first), len(second)))
```

**`\W` 在 Python 3 的 `re` 里默认是 Unicode 语义**，所以中文不会被当成「非单词字符」删掉——`re.sub(r"[\s\W_]+", "", "以后都用中文")` 结果是 `以后都用中文` 本身。这是本函数能在中文上工作的前提（如果写成 `re.ASCII`，整个记忆去重就废了）。

**除以较短侧**（不是并集、不是 Jaccard）：注释解释得很清楚——*a short fact scores high when it is fully contained in a longer existing memory, which is exactly the duplicate we want to warn about*。短句被长记忆完全包含时得分 1.0，正是要预警的情况。

⚠️ 反向不成立：**长记忆被短句包含**时，分母是短句的 bigram 数，得分同样容易接近 1。所以「用中文」vs「以后都用中文写提交信息」这种关系也会被判为高相似。对「是否重复」这个判断来说，这个偏向是对的（宁报勿漏）。

### 5.7 `_row_to_record`（245-260）：宽容的反序列化

```python
try:
    keywords = json.loads(row["keywords"] or "[]")
except (TypeError, ValueError):
    keywords = []
```

`keywords` 坏了（手改库、老版本格式）→ 空列表，不抛。其余字段直接取。`created_at or 0` / `updated_at or 0` 把 `NULL` 变成 0。

📌 整个 store 层没有一处对外抛异常的设计，全部是「降级 + 日志」。原因在 §0.3：记忆坏了不能影响聊天。

---

## 6. `memory/files.py`（105 行）：Markdown 镜像

### 6.1 为什么要有镜像

文件头（1-6）：

> Every memory lives in two places: a row in SQLite (fast search) and a Markdown file under `.agent/memories` (readable, diff-able, greppable).

三个形容词对应三种使用场景：

| 场景 | 用哪份 |
|---|---|
| 模型检索、去重 | SQLite |
| 人看、人改、`git diff` | Markdown |
| 系统提示词里的廉价索引 | `overview.md` |

**双写必然有一致性问题**，这里的答案是：**SQLite 是权威源，Markdown 是导出**。证据：`overview_text` 在文件缺失时会从 DB 重建（§7.2），而没有任何「从 md 导入 DB」的路径。所以手改 md **不会**改到检索结果，只会改到人看到的那份副本。

### 6.2 `slugify`（18-20）：保留中文

```python
_SLUG_RE = re.compile(r"[^0-9A-Za-z\u4e00-\u9fff_-]+")

def slugify(text: str, limit: int = 40) -> str:
    slug = _SLUG_RE.sub("-", text.strip()).strip("-")
    return (slug or "memory")[:limit]
```

白名单是「ASCII 字母数字 + 下划线 + 连字符 + 常用汉字区」。**保留中文**是个明确决策：文件名 `12-提交信息统一用中文.md` 比 `12-memory.md` 有用得多，Windows/macOS/Linux 都用 UTF-8 文件系统，没有兼容问题。

`(slug or "memory")`：全是标点的标题 → `memory`，避免出现 `12-.md`。

⚠️ 白名单只覆盖 `\u4e00-\u9fff`（基本汉字区）。日文假名、韩文、emoji 都会被替换成 `-`。这个函数还被 `pending._path` 用来处理 sessionId（那里全是 ASCII，不受影响）。

### 6.3 `render_markdown`（23-36）：带 front-matter 的正文

```
---
id: 12
title: 提交信息偏好
category: user_preference
keywords: [commit, 中文]
scope: project
updated_at: 1712345678
---

# 提交信息偏好

正文……
```

注意 `keywords: [%s]` 里是 `", ".join(...)`——**没有对含逗号或 `]` 的关键词做转义**。一个关键词写成 `a]b` 会让这个 front-matter 变成不合法的 YAML。当前没有解析方（md 只给人看），所以无害；但如果将来加「从 md 导入」，这是第一个要修的。

### 6.4 `path_for`（52-56）：`id-slug.md`

```python
name = "%s-%s.md" % (record.id if record.id is not None else "new", slugify(record.title))
```

**id 打头**让 `ls` 的顺序就是创建顺序（`10-` vs `9-` 的字典序问题存在，但记忆数量少、人能忍）。`new` 兜底：`record.id` 为空只可能发生在「还没入库就取路径」，正常流程不会。

⚠️ **路径依赖 title**，所以「同一条记忆换了标题」会算出**新路径**，`write` 写新文件，**老文件留在原地**。而 `remove` 也是按当前 title 算路径的，所以删除也删不掉那个老文件。

真实后果：

1. `memory_save` 用同一个 title 改内容 → 路径不变 → 正常覆盖；
2. 但如果你先存一条「构建命令」，再存一条同 title 的（正常），不会产生孤儿；
3. **只有「同一 id 的 title 变化」才会产生孤儿**——而 store 的 upsert **不允许改 title**（§5.3）。

所以孤儿文件在当前代码路径下**不会产生**。这是一个「靠上游约束保住的正确性」：如果哪天 store 允许 upsert 时更新 title（很自然的演进），这里立刻开始漏文件。📌 读代码时要区分「逻辑上安全」和「约定上安全」，这是后者。

### 6.5 `rebuild_overview`（76-93）：按 category 分组的廉价索引

```python
lines = ["# 记忆概览", ""]
grouped = {}
for record in records:
    grouped.setdefault(record.category, []).append(record)
for category in sorted(grouped):
    lines.append("## %s" % category)
    for record in sorted(grouped[category], key=lambda r: r.title):
        suffix = " (global)" if record.scope == "global" else ""
        lines.append("- [%s] %s%s" % (record.id, record.title, suffix))
    lines.append("")
```

输出形如：

```
# 记忆概览

## development_code_specification
- [7] 注释用中文

## project_info
- [12] 提交信息偏好 (global)
```

`[id]` 是给模型看的：`memory_delete` 需要 id，`memory_recall` 的搜索结果也用 `## [id] title` 格式。**模型可以在 overview 里看到全部 id，然后按需精确操作**——这是「廉价索引 + 按需取正文」模式的核心。

三条排序规则：category 字典序、组内 title 字典序、global 加后缀。全部确定性，所以**同样的记忆集合永远生成同样的文件**（`git diff` 干净）。

⚠️ `rebuild_overview` **永远写 `<project>/.agent/memories/overview.md`**，哪怕传入的 records 里有 global 记忆。所以 global 记忆只出现在「项目 A 的 overview」里，不会出现在 `~/.agent/memories/overview.md`（那个文件根本不存在）。多项目场景下，每个项目各有一份包含自己 + 全部 global 的 overview——**没有一份「global 专属」的索引**。这是有意的：overview 只服务于「当前项目的系统提示词」。

### 6.6 `read_overview`（97-105）：读失败返回空串

```python
if not os.path.exists(path):
    return ""
try:
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read().strip()
```

没有缓存——缓存的责任在 `MemoryService.overview_text`（§7.2）。

---

## 7. `memory/service.py`（254 行）：服务层与三个工具

### 7.1 构造：三个存储点

```python
project_memory_dir = (os.path.join(project_root, ".agent", "memories")
                      if project_root else os.path.join(data_dir, "memories"))
global_memory_dir = os.path.join(os.path.expanduser("~"), ".agent", "memories")
self.files = MemoryFiles(project_memory_dir, global_memory_dir)
self.store = MemoryStore(os.path.join(data_dir, "memory.db"))
self.pending = PendingStore(os.path.join(project_memory_dir, PENDING_DIR_NAME))
```

（83-90）

三个观察点：

1. **没有 project_root 时记忆落在 data_dir**（`<data_dir>/memories`）。这是「Core 先启动、IDE 后报告项目」的过渡态（`_bind_project_root` 会重建 MemoryService），或者是 CLI 直跑。
2. **`memory.db` 在 data_dir，md 在项目里**。所以「换项目」不会共享索引，但索引本身是按 `project_path` 列分区的——迁移项目目录后老记忆还在库里，只是查不到（`scope='project'` + 旧路径）。
3. **`_pending` 在项目的 memories 下**，因此候选记忆会随项目一起被 `git add`（如果用户没 ignore）。注释说它们「still Markdown, still local」——是否提交由用户决定。

### 7.2 `overview_text`（95-104）：一趟读取 + 一次重建 + 永久缓存

```python
if self._overview_cache is not None:
    return self._overview_cache
text = self.files.read_overview()
if not text:
    records = self._all_records()
    if records:
        text = self.files.rebuild_overview(records)
self._overview_cache = text[:OVERVIEW_BUDGET_CHARS]
return self._overview_cache
```

三步：

1. **有缓存 → 直接返回**。缓存只在 `save`（133）和 `delete`（144）里被清空。
2. **没缓存 → 读 md**。读到了就用 md 的内容——**这意味着用户手工编辑 `overview.md` 是生效的**（在下次 save/delete/Core 重启之前）。
3. **md 不存在/为空 → 从 DB 重建并落盘**。

⚠️ `OVERVIEW_BUDGET_CHARS = 4000` 是**硬截断**，没有「还有 N 条」的提示。记忆超过约 100 条（每条 `- [id] title` 约 30 字）时，后面的条目在系统提示词里**完全消失**，模型不知道它们存在——除非主动 `memory_recall`。这是一处会随使用逐渐显现的坑：**加记忆这件事没有「满了」的反馈**。

⚠️ 缓存之外的另一个坑：Core 运行期间**另一台/另一进程**改了 `overview.md`，当前进程看不到（缓存不失效）。单项目单 Core 的正常场景下不会发生。

### 7.3 `search`（109-116）：project 优先，global 补齐

```python
project_scope = self.store.search(keywords, scope="project", project_path=self.project_root, limit=limit)
if len(project_scope) >= limit:
    return project_scope
global_scope = self.store.search(keywords, scope="global", limit=limit)
merged = project_scope + [r for r in global_scope if r.id not in {p.id for p in project_scope}]
return merged[:limit]
```

**项目记忆优先**：项目性的事实（构建命令、目录结构）比全局偏好更相关。补齐时去重（虽然 id 跨 scope 不会重复，防御性写法）。

⚠️ 短路的条件是 `len(project_scope) >= limit`，所以「项目里有 5 条命中、全局有 3 条更相关」时，全局那 3 条**永远不返回**。这是取舍：宁可少给，也不让全局噪音盖住项目事实。

### 7.4 `save`（120-135）：DB → md → overview 三步

```python
record.id = self.store.save(record)
saved = self.store.get(record.id) or record
self.files.write(saved)
self._overview_cache = None
self.files.rebuild_overview(self._all_records())
return saved
```

为什么 `store.get` 再读一遍：upsert 时 `created_at` 用的是库里原有的值，而传入的 `record.created_at` 是 0。重读拿到真实时间戳，写进 md 的 front-matter。**多一次查询换一份正确的镜像**，值。

顺序也重要：先 DB（权威）→ 再 md（镜像）→ 最后 overview（派生）。如果 md 写失败（磁盘只读），DB 已经成功，overview 也会从 DB 重建成功——**检索功能完全正常，只是人看不到文件**。这是正确的失败方向。

⚠️ `save` 每次都会 **全量重建 overview**（`_all_records()` 是 `SELECT *` 全表）。1000 条记忆时，每次 `memory_save` 都要读 1000 行 + 拼 1000 行 + 写文件。低频操作，可忍；但没有增量更新。

### 7.5 `promote`（160-186）：候选转正 + 重复预警

```python
entry = self.pending.get(pending_id)
if entry is None:
    raise KeyError(pending_id)
text = (content or entry["content"]).strip()
if not text:
    raise ValueError("content is empty")
duplicate = self.store.find_similar(text, scope="project", project_path=self.project_root)
record = self.save(title=(title or entry["title"]).strip(), content=text,
                   category=category or entry["category"],
                   keywords=keywords or self.pending_derive_keywords(text, entry),
                   scope="project")
self.pending.remove(pending_id)
return {"memoryId": record.id, "title": record.title, "content": record.content,
        "duplicate": ({"id": duplicate.id, "title": duplicate.title}
                      if duplicate is not None and duplicate.id != record.id else None)}
```

五步：查候选 → 校验 → **先查重** → 写记忆 → 删候选。

**查重在写之前**：`find_similar` 要拿「已有记忆」比，写进去之后自己会跟自己相似（得分 1.0），所以顺序不能反。而 `duplicate.id != record.id` 是为了处理「同 title 覆盖」——如果你 promote 的正好是已存在的那条，`save` 返回旧 id，`find_similar` 找到的也是它，此时不该报「重复」。

**只返回 `duplicate` 的 id 与 title**：Shell 只要显示「和第 12 条『提交信息偏好』很像」，不需要正文。Java 只用了 id（`AgentChatPanel.java:684`），VS Code 类似——**`title` 字段目前没有消费者**。

📌 这里有一个重要的产品决策：**发现重复也不阻止写入**。`promote` 照常 `save`，只是把 `duplicate` 带回去让 UI 提示。理由：用户可能就是想用这条新的覆盖旧的（同 title 会 upsert），或者两条确实该并存。**系统不替用户做最后判断**——这和 `pending` 整个设计（永不自动晋升）是同一个价值观。

`pending_derive_keywords`（191-197）：如果审核时改写了正文，重新抽关键词；没改写就沿用候选的关键词。

### 7.6 `capture`（150-155）与三个 handler

```python
def capture(self, session) -> List[Dict[str, Any]]:
    candidates = extract_candidates(getattr(session, "messages", []) or [])
    if not candidates:
        return []
    return self.pending.add(getattr(session, "session_id", ""), candidates)
```

`getattr` 三连是防御性的：`capture` 的入参是 `Any`，理论上可能是任何对象（测试里传的是真 `Session`）。用 `getattr(..., [])` 而不是 `session.messages`，是为了「对象不对也不炸」。

三个 handler 的行为表：

| 工具 | 参数 | 返回 | 权限 |
|---|---|---|---|
| `memory_recall` | `mode=overview` | overview 文本或「（暂无记忆）」 | `auto` |
| `memory_recall` | `mode=search` + `keywords[]` + `limit` | `## [id] title\n正文` 多块 | `auto` |
| `memory_recall` | `keywords` 传成字符串 | 自动包成 `[str]`（219-220） | — |
| `memory_save` | `title` + `content` + 可选 `category/keywords/scope` | 「已保存记忆 #id: title」 | `auto` |
| `memory_delete` | `memory_id` | 「已删除记忆 #id」/「不存在」 | **`confirm`** |

`memory_delete` 是三个里唯一要确认的——它真的删数据，且不可恢复（md 一起删）。

⚠️ `limit = int(args.get("limit") or 5)` 后面又 `max(1, min(limit, 20))`：**两次夹取**。第一次防 `None`，第二次防越界。schema 里写了 `minimum: 1, maximum: 20`，但**模型不一定遵守 schema**，所以代码必须自己夹。

---

## 8. `memory/pending.py`（331 行）：候选记忆

### 8.1 核心原则：只生成候选，永不自动写入

文件头（1-11）：

> The Core never writes these into long-term memory on its own. ... Fewer but clean is the whole point: rule one and rule two below are deliberately narrow, everything else is dropped.

翻译：**宁可漏，不可脏**。两条规则故意做得很窄，其他一切丢弃。

这个决策值得停一下：为什么不用 LLM 来抽取？因为一次额外调用要花钱、要等、还会编。规则抽取的召回率低，但**精确率高**（命中就是真指令），且零成本零延迟。配上「人工确认」这一步，整体体验是「偶尔弹一两条确实该记住的」而不是「每次都弹一堆垃圾」。

### 8.2 规则一：指令标记

```python
_INSTRUCTION_MARKERS = (
    "记住", "以后都", "以后", "不要", "必须", "别再", "永远",
    "一律", "统一用", "记得", "禁止",
)
```

（30-33）

纯子串匹配（`any(marker in sentence for marker in _INSTRUCTION_MARKERS)`）。

⚠️ **「以后」是这里面最容易误命中的词**：「以后端为主」会命中「以后」；「不要」也会命中「要不要试试」这样的疑问（虽然后面有问句过滤，但「要不要试试这个方案」不以问号结尾时就会漏过）。这是规则抽取的固有精度损失，靠人工确认兜底。

`MIN_CONTENT_CHARS = 6`、`MAX_CONTENT_CHARS = 300`（25-26）：太短没有信息量，太长不是一条「事实」。

### 8.3 规则二：纠正标记

```python
_CORRECTION_PATTERNS = (
    re.compile(r"不对[，,。！!：:\s]"),
    re.compile(r"^\s*不是"),
    re.compile(r"不是[^。！？\n]{0,20}而是"),
    re.compile(r"说错了"),
    re.compile(r"应该是"),
    re.compile(r"纠正"),
)
```

（37-44）

六个正则覆盖中文里「纠正事实」的常见说法。注意第一条 `不对[，,。！!：:\s]` **要求「不对」后面跟标点或空白**——这样「不对齐」不会误命中。第三条 `不是[^。！？\n]{0,20}而是` 匹配「不是 A 而是 B」这个句式，中间限 20 字。

📌 正则写得这么细，说明作者真的拿真实对话调过。这类规则的维护成本很高（新增说法就要加一条），但收益是零成本零延迟。

### 8.4 `extract_candidates`（107-140）：逐句过滤

```python
for message in messages:
    if message.role != Role.USER or not (message.content or "").strip():
        continue
    for sentence in _sentences(message.content or ""):
        if len(sentence) < MIN_CONTENT_CHARS or _is_question(sentence):
            continue
        category = ""
        if any(marker in sentence for marker in _INSTRUCTION_MARKERS):
            category, source = "user_preference", SOURCE_INSTRUCTION
        elif any(pattern.search(sentence) for pattern in _CORRECTION_PATTERNS):
            category, source = "correction", SOURCE_CORRECTION
        if not category:
            continue
        content = _clip(sentence)
        key = normalize_content(content)
        if key in seen: continue
        seen.add(key)
        candidates.append({"title": derive_title(content), "content": content,
                           "category": category, "source": source,
                           "keywords": derive_keywords(content)})
        if len(candidates) >= MAX_CANDIDATES: return candidates
```

五道闸门：**只看 USER** → **切句** → **长度 + 问句过滤** → **两条规则** → **去重** → **上限 8**。

`_sentences`（61-67）先 `_flatten_blocks` 去掉代码块（```...```），再按 `[。．！!\n；;]+` 切。**去代码块是关键**：一段贴进来的代码里出现「不要」不该变成记忆。

`_is_question`（70-71）看结尾是不是 `？?吗么`。`吗/么` 这两个字面量是中文特有的疑问标记——「以后都用中文吗」不以问号结尾也能被拦住。

⚠️ **`else if` 的顺序导致「纠正」被「指令」吃掉**：如果一句话同时含「记住」和「应该是」，会被判成 `user_preference`。实际影响小（category 只影响分组展示）。

⚠️ **`seen` 只在本次调用内有效**，跨会话去重在 `PendingStore.add` 里做（§8.6）。

### 8.5 `derive_title` / `derive_keywords`（78-104）

```python
for marker in _INSTRUCTION_MARKERS:
    if flat.startswith(marker):
        flat = flat[len(marker):].lstrip("，,：: ")
        break
...
return flat[:24]
```

「记住：日志目录是 ~/.agent/logs」→「日志目录是 ~/.agent/logs」。**只剥开头**，中间的不动。`flat[:24]` 是标题长度上限。

`derive_keywords` 与 `store.derive_terms` 逻辑几乎一样（英文词 2-30 字符 + 中文 2-6 字），但独立实现、`limit=5`。🔁 两处规则相同、代码两份，改一处要记得另一处。

### 8.6 文件格式与 `PendingStore`

一个会话一个文件：`<project>/.agent/memories/_pending/<slugify(sessionId, 60)>.md`。

```
---
session: 3f2a9c1e7b#a1b2c3d4
source: chat
created: 1712345678
---

## 3f2a9c1e7b-a1b2c3d4-1
title: 用中文写提交信息
category: user_preference
source: user_instruction
keywords: 中文, 提交信息
created: 1712345678

以后都用中文写提交信息
```

`_read`（161-213）与 `_write`（215-238）是一对镜像解析器。三个细节：

1. **`_read` 用 `re.split(r"(?m)^## ", body)`** 切条目：多行模式下「行首 `## `」才是分隔符，正文里的 `##` 不误伤。
2. **meta 与正文之间用空行分隔**：`while index < len(lines) and lines[index].strip()` 读到空行为止，剩下的都是正文（正文可以多行、可以含 `:`）。
3. **`_write` 在 `items` 为空时直接删文件**（217-222）：最后一条被 ignore/promote 掉时，文件消失，目录保持干净。

### 8.7 `add`（263-299）：跨文件去重 + 编号单调

```python
known = {normalize_content(item["content"]) for item in self.list_all()}
...
for item in items:
    tail = item["id"].rsplit("-", 1)[-1]
    if tail.isdigit():
        used.add(int(tail))
...
number = max(used) + 1 if used else 1
entry = {"id": "%s-%d" % (prefix, number), ...}
```

三件事：

1. **`known` 扫全部候选文件**，所以「上一轮在会话 A 说过、这一轮在会话 B 又说」也不会重复。
2. **编号取 `max(used) + 1`**，而不是 `len(items) + 1`。所以删掉中间一条后，新条目的编号**继续往上走**，不复用。测试 `test_ids_keep_increasing_after_a_discard`（`test_memory_pending.py:104`）就是在守这条——理由很实际：**id 复用会让「用户刚 ignore 的那条」以同一个 id 复活**，UI 里看起来像没生效。
3. **`prefix = slugify(session_id, 40)`**：id 形如 `3f2a9c1e7b-a1b2c3d4-1`，全局唯一（会话 id + 序号）。

### 8.8 ⚠️ 一处真实的跨进程契约错位

Core 写入的 `source` 只有两个值：

```python
SOURCE_INSTRUCTION = "user_instruction"
SOURCE_CORRECTION = "user_correction"
```

而两个 Shell 都在判断「是不是纠正」时比较 `"correction"`：

```java
// AgentChatPanel.java:528
return "correction".equals(source) ? "纠正" : "偏好";
```

```ts
// vscode/src/panel.ts:432
meta.textContent = (item.source === "correction" ? "纠正" : "偏好") + ...
```

`"user_correction" != "correction"`，所以**这个标签永远显示「偏好」**。

真正表示类别的字段是 `category`（值为 `user_preference` / `correction`），两个 Shell 都已经解析了它（`PendingMemory.category`、`item.category`），只是把判断挂在了 `source` 上。

📌 这是一个极好的教材：**跨进程的「字符串契约」一旦有第二份实现就会漂移**。§0.2 讲的工具名清单、§16 的错误前缀是显式契约（有注释、有测试）；而 `source` 的取值是隐式契约（没有任何文档说它等于 `correction`），于是没人发现。修法只有一条：**枚举值只在一个地方定义**，两侧从协议生成。

（本卷只陈述现状，不改代码。）

---

## 9. `codeindex/chunker.py`（120 行）：把源文件切成块

### 9.1 常量：索引什么、不索引什么

```python
IGNORED_DIRS = {".git", ".idea", ".gradle", ".venv", "venv", "env", "node_modules",
                "__pycache__", "target", "build", "dist", "out", "vendor", ".next",
                ".nuxt", "coverage", ".mypy_cache", ".pytest_cache", ".tox", "bin", "obj"}

INDEXED_EXTENSIONS = {".py", ".java", ".kt", ".kts", ".scala", ".go", ".rs", ".c", ".h",
                      ".cpp", ".cc", ".hpp", ".cs", ".ts", ".tsx", ".js", ".jsx", ".vue",
                      ".rb", ".php", ".swift", ".dart", ".lua", ".sh", ".ps1", ".sql",
                      ".md", ".rst", ".html", ".css", ".scss", ".yml", ".yaml", ".toml",
                      ".gradle", ".xml", ".json"}

MAX_FILE_BYTES = 400 * 1024
MAX_CHUNK_CHARS = 2400
WINDOW_LINES = 60
WINDOW_OVERLAP = 10
```

（14-32）

三张白/黑名单的取舍：

| 名单 | 覆盖 | 遗漏（故意的） |
|---|---|---|
| `IGNORED_DIRS` | 21 个常见构建/缓存目录 | 自定义输出目录（如 `out2/`）不在内 |
| `INDEXED_EXTENSIONS` | 39 种源码/配置/文档 | `.lock`、`.min.js`、`.map`、`.svg`、`.proto` |
| `MAX_FILE_BYTES` | 400KB | 超大文件直接跳，无提示 |

⚠️ **`.json` 在索引范围内**：`package-lock.json` 这类巨型文件如果小于 400KB 会被切成 60 行一个的窗口块，全部 embed。一次 `npm install` 后重建索引，可能多出上百个几乎无意义的块——既有成本（embedding 调用）也有噪音（检索命中锁文件）。`IGNORED_DIRS` 掩住了 `node_modules`，但项目根的 `package-lock.json` 掩不住。

`MAX_CHUNK_CHARS = 2400` 的比例：约 600 token（4 字符/token），与 `embeddings.BATCH_SIZE = 16` 配合，一批约 10K token。

### 9.2 `Chunk` 与 `identity`（35-46）

```python
@dataclass
class Chunk:
    path: str          # project-relative path with forward slashes
    lang: Optional[str]
    symbol: str        # function/class name, or "" for window chunks
    start_line: int    # 1-based
    end_line: int
    text: str          # body stored and shown to the model
    embed_text: str    # text sent to the embedding model (with a header)

    def identity(self) -> str:
        return "%s|%s|%d" % (self.path, self.symbol, self.start_line)
```

**`text` 与 `embed_text` 分开**是本文件最重要的设计：

- `text`：只存函数体。检索命中后给模型看的就是它。
- `embed_text`：`path (symbol:start-end)\n` + 函数体。**多一个头，让向量知道「这段代码在哪个文件的哪个函数里」**。

为什么头只进 embed 不进 text：给模型看的正文里再重复一遍路径是浪费 token（结果里已经单独有 `path`/`start_line` 字段）；但向量需要它——同样的 `return null;` 写在 `Auth.java` 和 `Cache.java` 里语义完全不同。

`identity()` **在生产代码里没有调用方**（grep 只命中定义处）。它是为「增量更新时按 identity 对齐」预留的接口，当前增量策略是「整文件按 sha1 替换」（§12.2），所以用不上。📌 又一处「预留字段/方法」。

### 9.3 `iter_source_files`（54-70）：边遍历边剪枝

```python
for current, dirs, files in os.walk(root):
    dirs[:] = [d for d in dirs if d not in IGNORED_DIRS and not d.startswith(".git")]
    for name in files:
        if not is_indexable(name):
            continue
        absolute = os.path.join(current, name)
        try:
            if os.path.getsize(absolute) > MAX_FILE_BYTES:
                continue
        except OSError:
            continue
        relative = os.path.relpath(absolute, root).replace("\\", "/")
        yield relative
```

**`dirs[:] = ...` 是原地切片赋值**，这是 `os.walk` 剪枝的**唯一正确写法**。写成 `dirs = [...]` 只是把局部名字绑定到新列表，`os.walk` 内部仍按原列表递归——`node_modules` 一样会被走一遍（浪费几分钟）。这是 Python 里最经典的坑之一。

`not d.startswith(".git")`：兜住 `.git-worktrees`、`.github` 之类。注意 `.github` 被跳过意味着**工作流文件不索引**——`INDEXED_EXTENSIONS` 里有 `.yml`，本来是想索引它们的。

**`replace("\\\\", "/")`**：Windows 下 `os.path.relpath` 返回 `src\\a.py`，统一成 `/` 才能和 SQLite 里存的、以及 `path_filter` 传进来的格式对上。

**`getsize` 失败就 `continue`**：文件在遍历和取大小之间被删、权限不足、符号链接断链，都会走到这里。静默跳过是唯一合理选择（索引构建不应因一个文件失败）。

⚠️ **不跟随符号链接**：`os.walk` 默认 `followlinks=False`，所以「软链到外部目录」的内容不会被索引。对单仓库项目是好事（不会把 `~/` 卷进来），但 monorepo 里用 symlink 组织包的场景会漏。

### 9.4 `chunk_file`（73-106）：函数优先，滑窗兜底

```python
lang = detect_language(relative_path, content)
lines = content.splitlines()

if lang:
    try:
        parsed = parse_text(content, lang)
    except Exception:
        parsed = None
    for node in getattr(parsed, "functions", []) or []:
        start = max(1, int(node.start_line))
        end = min(len(lines), int(node.end_line))
        if end < start: continue
        body = "\n".join(lines[start - 1:end]).strip()
        if len(body) < 16: continue
        chunks.append(_make_chunk(relative_path, lang, node.name, start, end, body[:MAX_CHUNK_CHARS]))

if chunks:
    return chunks

for start in range(0, len(lines), WINDOW_LINES - WINDOW_OVERLAP):
    window = lines[start:start + WINDOW_LINES]
    body = "\n".join(window).strip()
    if len(body) < 40: continue
    chunks.append(_make_chunk(relative_path, lang, "", start + 1, start + len(window), body[:MAX_CHUNK_CHARS]))
return chunks
```

四道防护：

1. **`parse_text` 包在 `try` 里**：解析器崩了不影响索引（注释原文：*parser failure must not break indexing*）。
2. **行号夹取**：`max(1, ...)` / `min(len(lines), ...)`——解析器给的区间可能越界（正则解析器用花括号配对，可能跑到文件尾）。
3. **`len(body) < 16` 丢弃**：一行 getter/setter 不值得单独成块。
4. **`body[:MAX_CHUNK_CHARS]` 硬截断**：2400 字符以上的单个函数（长 Java 类）被切掉尾巴，且不加标记。

**`if chunks: return chunks` 是一刀切**：只要解析出任何一个函数块，就**整个文件不再走滑窗**。后果：

> ⚠️ **文件顶部的 import / 常量 / 配置不进入索引**。一个 `AppConfig.java` 里如果有一个方法，那么「这个项目的超时是 30 秒」这条写在字段上的事实，语义搜索**永远搜不到**。

滑窗参数：`range(0, len, 50)` + 60 行窗口 → 每块与前一块重叠 10 行。重叠是为了不让「刚好跨边界的函数」被切碎成两个都不完整的块。`len(body) < 40` 的尾部小块被丢弃。

### 9.5 `_make_chunk`（109-120）：头的格式

```python
header = "%s (%s:%d-%d)" % (path, symbol or "block", start, end)
return Chunk(path=path, lang=lang, symbol=symbol, start_line=start, end_line=end,
             text=body, embed_text=header + "\n" + body)
```

`symbol or "block"`：滑窗块没有符号名，用字面量 `block`。于是向量里看到的是 `src/a.py (block:1-60)`。

为什么用括号和冒号而不是自然语言（如 `文件 src/a.py 的第 1 到 60 行`）：embedding 模型对代码片段更敏感于**类代码的形状**，`path (symbol:1-60)` 看起来像一条注释/签名，比中文句子更接近训练分布。这是一个没有写在注释里的经验判断。

---

## 10. `codeindex/embeddings.py`（124 行）：两套 embedding 客户端

### 10.1 URL 推导（21-40）：从聊天端点推出 embedding 端点

```python
def resolve_embed_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base: return ""
    if base.endswith("/api/embed"): return base
    if base.endswith("/api/chat"): base = base[: -len("/api/chat")]
    return base + "/api/embed"

def resolve_openai_embed_url(base_url: str) -> str:
    base = (base_url or "").strip().rstrip("/")
    if not base: return ""
    if base.endswith("/embeddings"): return base
    if base.endswith("/chat/completions"): base = base[: -len("/chat/completions")]
    return base + "/embeddings"
```

**为什么不加一个 `embedding_base_url` 配置**：用户只需要填一次地址。设置页里的「端点」既用于聊天也用于 embedding，`resolve_*` 负责把它规整成对应端点的形式。

四种输入分别处理：

| 用户填的 | Ollama 结果 | OpenAI 结果 |
|---|---|---|
| `http://localhost:11434` | `.../api/embed` | `.../embeddings` |
| `http://localhost:11434/api/chat` | `.../api/embed` | `.../embeddings`（不认 `/api/chat`，直拼） |
| `https://api.x.com/v1` | `.../v1/api/embed` ❌ | `.../v1/embeddings` ✅ |
| `https://api.x.com/v1/chat/completions` | `.../v1/chat/completions/api/embed` ❌ | `.../v1/embeddings` ✅ |

⚠️ 第二行/第四行说明：**这两个函数各自只认自己生态的路径后缀**。如果用户把 base_url 填成 `/api/chat` 又切到 `openai_compat`，拼出来的是 `http://localhost:11434/api/chat/embeddings`——一个不存在的地址。报错会是 HTTP 404，而错误信息只说「embedding 失败(404)」，**不会提示「你的 base_url 和 provider 不匹配」**。

### 10.2 `Embedder` 与 `embed`（43-66）：批 16，逐批建连接

```python
BATCH_SIZE = 16

async def embed(self, texts: List[str]) -> List[List[float]]:
    if not texts: return []
    if not self.model:
        raise RpcError(ErrorCode.PROTOCOL_ERROR, "未配置 Embedding 模型")
    vectors = []
    for start in range(0, len(texts), BATCH_SIZE):
        batch = [t[:4000] for t in texts[start:start + BATCH_SIZE]]
        if (self.config.provider or "").lower() == "openai_compat":
            vectors.extend(await self._openai(batch))
        else:
            vectors.extend(await self._ollama(batch))
    return vectors
```

三点：

1. **`t[:4000]` 再截一次**：chunk 已经 `[:2400]`，但 `embed_text` 还带了个头；这里 4000 是**字符**上限，防某些服务端拒绝超长输入。两层截断（2400 正文 + 4000 总长）的 4000 实际上永远不会触发——除非有人直接调 `embed()` 传长文本。
2. **批 16**：Ollama 的 `/api/embed` 支持 `input: [..]` 批量，16 是「单请求耗时」与「失败重传代价」的折中。
3. **provider 判断用 `.lower()`**：`OpenAI_Compat` 也能识别。但注意这是**字符串比较**，不是枚举——`settings/update` 传什么就是什么（卷 03 讲过 `ChatConfig` 没有校验 provider 取值）。

⚠️ **每批新建一个 `httpx.AsyncClient`**（`async with httpx.AsyncClient(...)` 在 `_ollama` / `_openai` 内部）。1000 个块 = 63 批 = 63 次 TCP+TLS 握手。本地 Ollama 无感，远端 OpenAI 每批多 50-100ms。📌 这是一个「正确性优先、性能后置」的写法；如果索引构建慢到不可忍，第一件事就是把 client 提到构造器里。

### 10.3 `_ollama`（68-83）：404 时退到 legacy 接口

```python
response = await client.post(url, json={"model": self.model, "input": texts})
if response.status_code == 404:
    return await self._ollama_legacy(texts)
if response.status_code >= 400:
    raise RpcError(ErrorCode.LLM_ERROR, "Ollama embedding 失败(%d): %s" % (...))
data = response.json()
vectors = data.get("embeddings") or []
```

`/api/embed` 是 Ollama 0.1.30+ 的新接口（支持批量、字段名 `input`/`embeddings`）；老版本只有 `/api/embeddings`（单条、字段名 `prompt`/`embedding`）。

**`404` 才退**，`400` 不退：404 = 端点不存在（老版本）；400 = 参数错（模型名错、输入超限），退了也没用。这个区分很关键——如果写成 `>= 400 全退`，模型名写错会变成 16 次 legacy 请求，每个都失败，且错误信息变成 legacy 的。

`_ollama_legacy`（85-97）逐条 POST，16 个块就是 16 次请求。慢，但只在老版本上发生。

⚠️ `data.get("embeddings") or []`：如果服务端返回 200 但字段名不对（比如某个兼容层返回 `data`），这里得到空列表，`_remember_dim` 什么都不做，`replace_file` 里 `if not vector: continue` 会**静默跳过所有块**，最终索引文件数 +1、块数 +0。用户看到「索引已更新：N 个文件 / 0 个代码块」，但 `semantic_search` 什么都搜不到。错误被延后到「搜索结果为空」才暴露。

### 10.4 `_openai`（99-118）：按 index 排序

```python
rows = data.get("data") or []
rows.sort(key=lambda item: int(item.get("index") or 0))
vectors = [list(item.get("embedding") or []) for item in rows]
```

OpenAI 的 `/v1/embeddings` 返回 `{"data": [{"index": 0, "embedding": [...]}, ...]}`。**规范说 index 可能乱序**，所以必须排序后才能与输入对齐。不排序的话，向量会错位挂到别的块上——检索结果看起来「像但是不对」，极难排查。📌 这是少数「注释里没提、但代码做对了」的地方。

`Authorization` 只在 `api_key` 非空时加（104-105）：Ollama 不需要，某些内网网关也不要。

### 10.5 `_remember_dim`（120-125）

```python
for vector in vectors:
    if vector:
        self.dim = len(vector)
        return
```

只记第一个非空向量的维度。`CodeIndexService` 里没读它（`status()` 的 `dim` 来自数据库），所以这个字段目前**只为调试/日志存在**。

---

## 11. `codeindex/store.py`（225 行）：向量存储与暴力检索

### 11.1 两张表

```sql
CREATE TABLE IF NOT EXISTS code_files (
  project_path TEXT NOT NULL, path TEXT NOT NULL, file_hash TEXT NOT NULL,
  updated_at INTEGER, PRIMARY KEY (project_path, path));
CREATE TABLE IF NOT EXISTS code_chunks (
  id INTEGER PRIMARY KEY AUTOINCREMENT, project_path TEXT NOT NULL, path TEXT NOT NULL,
  lang TEXT, symbol TEXT NOT NULL DEFAULT '', start_line INTEGER NOT NULL,
  end_line INTEGER NOT NULL, file_hash TEXT NOT NULL, text TEXT NOT NULL,
  vector BLOB NOT NULL, dim INTEGER NOT NULL, model TEXT NOT NULL, updated_at INTEGER);
CREATE INDEX IF NOT EXISTS idx_code_chunks_path ON code_chunks(project_path, path);
CREATE INDEX IF NOT EXISTS idx_code_chunks_model ON code_chunks(project_path, model);
```

（23-48）

`code_files` 存**文件级**的 sha1（增量构建的依据），`code_chunks` 存**块级**的正文与向量。`file_hash` 在两张表里都有——块表里的那份是冗余，但方便「按 hash 找块」时不用 join。

两个索引对应两个查询：按文件删（`replace_file`/`prune`）与按模型取（`_load`）。

### 11.2 `normalize` / `pack` / `unpack`（51-65）

```python
def normalize(vector):
    norm = math.sqrt(sum(float(v) * float(v) for v in vector))
    if norm <= 0.0:
        return [0.0] * len(vector)
    return [float(v) / norm for v in vector]

def pack(vector):
    return array.array("f", normalize(vector)).tobytes()
```

**写入时就归一化**，于是检索时余弦相似度 = 点积。这是本卷最值得记住的一个数学优化：

- 朴素做法：每对（查询, 文档）都算一次 `dot / (|a||b|)`，除法在循环里。
- 这里：文档先除以自身模长存起来，查询向量也归一化，之后只要点积。**省掉 N 次开方和除法**。

`array.array("f")` 是 float32（4 字节），比 `struct.pack` 快、比 JSON 小、比 `numpy` 无依赖。`norm <= 0` 时返回全零向量（而不是抛异常）——一个空块或全零向量至少不会让检索崩掉。

⚠️ **无维度校验**：`pack` 不检查输入长度，`unpack` 也不检查。混维度的向量存进去，`_load` / `search` 才发现（§11.6）。

### 11.3 `replace_file`（89-115）：先删后插

```python
rows = []
for chunk, vector in zip(chunks, vectors):
    if not vector: continue
    rows.append((project_path, path, lang, chunk.symbol, chunk.start_line, chunk.end_line,
                 file_hash, chunk.text, pack(vector), len(vector), model, now))
with self._connect() as connection:
    connection.execute("DELETE FROM code_chunks WHERE project_path = ? AND path = ?", ...)
    connection.execute("INSERT INTO code_files(...) VALUES (?,?,?,?) ON CONFLICT(project_path, path) DO UPDATE SET ...", ...)
    connection.executemany("INSERT INTO code_chunks(...) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", rows)
self._cache = None
return len(rows)
```

**`zip(chunks, vectors)`**：长度不等时按短的来，不报错。**`if not vector: continue`**：embedding 失败的块直接不存（而不是存个零向量占位）。

三句话在同一个 `with` 里，构成一个事务：要么文件记录 + 全部块都更新，要么都不变。**`DELETE` 在前**是关键——先插后删会把这次的块一起删掉。

`self._cache = None` 在事务之后：缓存失效必须发生在数据变更**之后**（如果在之前，中途异常会让缓存永久失效；如果在事务内，读路径可能读到半成品）。

⚠️ **`return len(rows)` 是「实际存了几块」，不是「切了几块」**。§10.3 那个「embedding 返回空」的场景下，`chunks` 有 50 个、`rows` 是 0，函数返回 0，而 `code_files` 里已经记了 sha1——**下次增量构建会认为这个文件「没变」而跳过**。这是「空向量导致索引永久空洞」的完整链路。

### 11.4 `prune`（117-133）：删掉不在清单里的文件

```python
keep = set(keep_paths)
...
stale = [row["path"] for row in rows if row["path"] not in keep]
for path in stale:
    connection.execute("DELETE FROM code_chunks WHERE project_path = ? AND path = ?", ...)
    connection.execute("DELETE FROM code_files WHERE project_path = ? AND path = ?", ...)
if stale:
    self._cache = None
return len(stale)
```

`keep_paths` 是**本次扫描到的全部 indexable 文件**（`iter_source_files` 的输出）。所以「被删掉的文件」与「超过 400KB 的文件」都会被 prune（后者本来就没进过库）。

⚠️ **逐条 DELETE，没有用 `executemany` 或 `WHERE path NOT IN (...)`**。1000 个文件删 100 个就是 200 次 execute——在事务里，成本可忍，但这是可以优化却没优化的地方。为什么没优化：`NOT IN` 的绑定参数上限（SQLite 默认 999），大仓库会直接报错。逐条删是**安全但慢**的选择。

### 11.5 `stats`（145-161）：`MAX(model)` 是个隐患

```python
row = connection.execute(
    "SELECT COUNT(*) AS chunks, COUNT(DISTINCT path) AS files, "
    "MAX(dim) AS dim, MAX(updated_at) AS updated_at, MAX(model) AS model "
    "FROM code_chunks WHERE project_path = ?", (project_path,)).fetchone()
```

`MAX(model)` 取的是**字典序最大值**的模型名。表里理论上只有一种模型（`build` 在换模型时会 `clear`），但如果因为异常留下两种（比如换模型后 clear 失败），`stats` 报出的 `model` 是字典序大的那个，而 `_load` 用 `model` 精确过滤——**统计与检索看到的数据集可能不同**。

`files` 用 `code_files` 的 count，`chunks` 用 `code_chunks` 的 count。两者可能不匹配（空向量导致 `code_files` 有记录、`code_chunks` 无块），这正是 §11.3 那个链路的表现。

`"numpy" : _np is not None` 直接告诉调用方当前用的是哪条检索路径。

### 11.6 `search`（163-191）：numpy 与纯 Python 双实现

```python
matrix, rows = self._load(project_path, model)
if not rows: return []
query = normalize(vector)
if _np is not None:
    scores = matrix @ _np.asarray(query, dtype="float32")
    order = scores.argsort()[::-1][:limit]
    hits = [(int(i), float(scores[i])) for i in order]
else:
    scored = [(index, _dot(matrix[index], query)) for index in range(len(rows))]
    scored.sort(key=lambda item: item[1], reverse=True)
    hits = scored[:limit]
```

`matrix @ query` 是一次 BLAS 矩阵向量乘。**没有近似索引（HNSW/IVF）**——就是全量暴力。为什么够用：单项目代码块在万级，float32 万×768 矩阵约 30MB，一次点积 768 万次浮点运算，numpy 下 5-10ms。

纯 Python 回退是 `sum(a*b for a,b in zip(...))`，慢 100 倍以上（万级块约 1 秒），但**保证没有 numpy 也能工作**（打包的 Core 不带 numpy 依赖）。

`argsort()[::-1]` 是「升序再反转」= 降序。`argsort` 是 O(n log n)，比 `argpartition`（O(n)）慢，但 n 小，且需要完整排序语义的可预测性。

### 11.7 `_load`（193-221）：缓存键是「数据指纹」

```python
meta = connection.execute(
    "SELECT COUNT(*) AS chunks, IFNULL(MAX(id), 0) AS max_id "
    "FROM code_chunks WHERE project_path = ? AND model = ?", ...).fetchone()
key = (project_path, model, int(meta["chunks"] or 0), int(meta["max_id"] or 0))
if self._cache is not None and self._cache[0] == key:
    return self._cache[1], self._cache[2]
```

**缓存键 = (项目, 模型, 块数, 最大 id)**。这四个数能唯一识别一次「加载结果」吗？

- 插入新块 → `max_id` 变 → 失效 ✅
- 删文件 → `chunks` 变 → 失效 ✅
- 替换同文件（先删后插）→ `max_id` 变 → 失效 ✅
- **纯更新**（同一 id 改内容）→ 不失效 ❌

最后一种在当前代码里不会发生（`replace_file` 永远 DELETE+INSERT，id 只增）。所以这个键是**正确的**，但它依赖「没有 UPDATE 路径」这个实现细节。🔁 如果将来加「原地重算向量」，这个缓存会返回旧矩阵。

```python
dim = int(records[0]["dim"] or 0)
usable = [record for record in records if int(record["dim"] or 0) == dim]
if not usable:
    self._cache = (key, None, [])
    return None, []
```

**维度对齐**：以第一行的 `dim` 为准，其他维度的块被丢弃。这挽救了「换模型但没 clear」的部分场景（不同模型的维度通常不同），但代价是**静默丢块**。如果两个模型维度恰好相同（比如都是 768），对齐会通过，然后拿两种模型的向量算相似度——结果是无意义的分数。

```python
matrix = _np.frombuffer(b"".join(record["vector"] for record in usable),
                        dtype="float32").reshape(len(usable), dim)
```

`frombuffer` 零拷贝（只做视图），`reshape` 也不需要移动内存。整个矩阵构建在 numpy 下是 O(n) 的内存拼接——**没有逐行 unpack，所以很快**。

---

## 12. `codeindex/service.py`（223 行）：增量构建

### 12.1 两个工具定义

| 工具 | permission | 参数 | 说明 |
|---|---|---|---|
| `semantic_search` | `auto` | `query`（必填）、`limit` 1-20、`path_filter` | 语义检索 |
| `build_index` | `auto` | `full`（bool） | 构建/增量更新 |

两个都是 `remote=False`（Core 内部执行）。`permission=auto` 意味着**模型可以不问用户就重建整个索引**——一次 `build_index` 会调用几十次 embedding API。这是一个权限上的宽松点：它是「只读语义」的操作，但成本不低。

### 12.2 `build`（95-147）：四道关卡

```python
root = self.project_root
if not root or not os.path.isdir(root):
    raise RpcError(ErrorCode.PROTOCOL_ERROR, "未绑定项目根目录，无法构建索引")
if self._lock is None:
    self._lock = asyncio.Lock()
async with self._lock:
    self._building = True
    try:
        ...
    finally:
        self._building = False
```

**`asyncio.Lock` 懒初始化**：`__init__` 里不能建（此时可能还没事件循环，而且 `CodeIndexService` 是在 `RpcServer.__init__` 里同步构造的）。所以第一次 `build` 时才建。`_building` 只是给 `status()` 报告用的布尔量，**不是锁**。

第二道关卡——**模型变更自动清库**：

```python
stored = self.store.stats(root)
if full or (stored["chunks"] and stored["model"] and stored["model"] != model):
    self.store.clear(root)
```

注释：*Vectors from another embedding model are not comparable.* 不同模型的向量空间不可比，混着算余弦相似度是纯粹的数字垃圾。所以换模型 = 全量重建，没有商量。

⚠️ `stored["model"]` 来自 `MAX(model)`（§11.5），在「一库两模型」的异常态下取字典序大的那个——如果新模型字典序更小，这个判断会漏掉，于是库不清理，两种向量共存。这是一个**异常态才会触发**的漏洞。

第三道关卡——**sha1 增量**：

```
files = list(iter_source_files(root))
known = self.store.file_hashes(root)
pending = []
for relative in files:
    content = _read_text(os.path.join(root, relative))
    if content is None: continue
    digest = hashlib.sha1(content.encode("utf-8", "ignore")).hexdigest()
    if known.get(relative) != digest:
        pending.append((relative, content, digest))
self.store.prune(root, files)
```

**sha1 是「文本」的哈希，不是文件字节的哈希**——先 `open(..., errors="ignore").read()` 解码，再 `encode("utf-8", "ignore")`，然后 sha1。这两个 `ignore` 意味着：

> ⚠️ **GBK 编码的源文件里的中文全部变成空**（无法用 UTF-8 解码的字节被丢掉）。哈希是基于「去掉中文后的文本」算的，索引里存的 `chunk.text` 也基于这份文本。后果：**GBK 项目里中文注释永远搜不到**，而且不会报错。这是 `_read_text` 的一行 `errors="ignore"`（220）造成的系统性行为。

第四道关卡——**逐文件 embed + 进度**：

```python
for index, (relative, content, digest) in enumerate(pending, start=1):
    chunks = chunk_file(relative, content)
    if not chunks:
        self.store.replace_file(root, relative, digest, None, [], [], model)
    else:
        vectors = await embedder.embed([chunk.embed_text for chunk in chunks])
        embedded_chunks += self.store.replace_file(root, relative, digest, chunks[0].lang, chunks, vectors, model)
    if progress is not None:
        progress(index, len(pending), relative)
```

- **`chunks` 为空也要 `replace_file`**：否则这个文件每次构建都是「pending」，永远重复处理。代价是 `code_files` 有记录、`code_chunks` 无块（`stats` 的 files/chunks 不相等，§11.5）。
- **`chunks[0].lang`**：一个文件所有块的 lang 相同，取第一个即可。
- **一次 embed 一个文件**：不是「把所有文件的块攒起来一起 embed」。好处是失败隔离（一个文件失败不影响其他）、进度真实；坏处是每个小文件都是一次批请求（批内可能只有 2-3 块，`BATCH_SIZE=16` 用不满）。
- **`await` 在循环里**：整个 `build` 是**串行**的。1000 个文件、每个 10ms embed = 10 秒起步。串行是刻意的（`asyncio.Lock` 本身就禁止并发构建），并发会让进度与错误处理复杂得多。

返回值：

```python
stats.update({"root": root, "scanned": len(files), "changed": len(pending), "embeddedChunks": embedded_chunks})
```

`scanned` = 扫描到的文件数，`changed` = 内容变了的文件数，`embeddedChunks` = 本次新写入的块数。三个数字的关系：`changed == 0` 时 `embeddedChunks == 0`（增量命中）；`changed > 0` 但 `embeddedChunks == 0` 时说明**embedding 全部失败**（§10.3）。

### 12.3 `search`（151-171）：取 3 倍再过滤

```python
query = (query or "").strip()
if not query: return []
root = self.project_root
if not root: return []
embedder = self._embedder()
stats = self.store.stats(root)
if not stats["chunks"]: return []
vectors = await embedder.embed([query])
if not vectors or not vectors[0]: return []
limit = max(1, min(int(limit or 6), 20))
hits = self.store.search(root, vectors[0], embedder.model, limit=limit * 3)
if path_filter:
    needle = path_filter.replace("\\", "/").lower()
    hits = [hit for hit in hits if needle in str(hit["path"]).lower()]
return hits[:limit]
```

五道早退：空查询、无项目根、库为空、embed 失败、**`vectors[0]` 为空**。每一道都返回 `[]` 而不是抛异常——因为这是被模型调用的工具，空列表就是「没找到」。

**`limit * 3` 是为了 `path_filter`**：先多取三倍，过滤后再截到 limit。但注意：

> ⚠️ 如果 `path_filter` 命中率低于 1/3，返回条数会**少于 limit**，而且不会「扩大候选再取」。搜「超时配置」限定 `src/net/` 时，如果前 18 条里只有 2 条在这个目录，就只返回 2 条——明明库里还有更多匹配。这是一个「多取三倍」这个魔数带来的硬上限。

`needle in str(hit["path"]).lower()`：子串匹配，`path_filter="service"` 会命中 `services/` 和 `a_service.py`。反斜杠先转 `/`——用户可能填 Windows 路径。

⚠️ **每次 `search` 都要先 `stats()` 再 `embed()`**：两次网络/磁盘往返。`stats` 只为了判断「库空不空」，其实可以靠 `search` 返回空来推断。多这一次查询是为了给出**不同的空结果文案**（见 §12.4）。

### 12.4 `_search_handler`（183-204）：空结果的两种文案

```python
if not hits:
    stats = self.store.stats(self.project_root or "")
    if not stats["chunks"]:
        return ToolResult(tool_call_id=call.id, ok=True,
                          content="（语义索引为空，先调用 build_index 建立索引）")
    return ToolResult(tool_call_id=call.id, ok=True, content="（没有匹配的代码块）")
```

**两种空结果给模型完全不同的下一步**：库空 → 去建索引；库有内容但没命中 → 换关键词或换 `path_filter`。这是「工具应该教会模型下一步」的范例。

命中时的格式（199-204）：

```python
blocks.append("### %s:%s (行 %s-%s, 相似度 %s)\n```%s\n%s\n```" % (
    hit["path"], hit["symbol"] or "block", hit["start_line"], hit["end_line"],
    hit["score"], hit["lang"] or "", str(hit["text"])[:TOOL_OUTPUT_CHARS]))
```

`TOOL_OUTPUT_CHARS = 1200`：每个块最多 1200 字符（块本身最多 2400，所以长块会被截一半）。**6 条 × 1200 = 7200 字符**，约 1800 token，正好是 `compress_tool_output` 的预算（卷 06 §17）——不是巧合，这是按下游预算倒推出来的。

`相似度 %s` 直接显示 `round(score, 4)`：模型可以看到 0.83 与 0.61 的差别，从而判断「要不要再搜一次」。

### 12.5 `_build_handler`（206-215）：一句话报告

```
索引已更新：%s 个文件 / %s 个代码块，模型 %s，扫描 %s 个文件，本次更新 %s 个
```

注意「N 个文件」是 `stats["files"]`（库里总数），「扫描 N 个」「本次更新 N 个」是本次的。**三个数字并列很容易读错**——模型完全可能理解成「这次索引了 N 个文件」。实际的「本次」只有最后一个。

---

## 13. `mcp/`：三个文件，把外部服务变成一个工具

### 13.1 `config.py`（87 行）：两处配置，项目级优先

```python
class MCPServerConfig(BaseModel):
    name: str
    transport: str = "stdio"          # stdio | http
    command: Optional[str] = None
    args: List[str] = Field(default_factory=list)
    env: Dict[str, str] = Field(default_factory=dict)
    url: Optional[str] = None
    enabled: bool = True
    auto_approve: bool = False
```

`parse_servers`（47-64）接受两种 JSON 形状：

```json
[{"name": "fs", "command": "npx", "args": ["-y", "@mcp/fs"]}]
{"fs": {"command": "npx", "args": ["-y", "@mcp/fs"]}}
```

第二种是社区常见的「名字做键」写法（Claude Desktop 风格），`payload.setdefault("name", name)` 把键变成名字。

`load_project_config`（67-79）：读 `<project>/.agent/mcp.json`，接受 `{"mcpServers": [...]}` 或裸数组/对象。**读失败只 warning**（77）——配置文件写坏了不该让整个 MCP 功能消失，只是这台服务器不出现。

`merge_configs`（82-87）：

```python
merged = {config.name: config for config in settings}
for config in project:
    merged[config.name] = config  # project level wins
return [config for config in merged.values() if config.enabled]
```

**项目级覆盖 IDE 级**（同名）。这允许「团队共享 `.agent/mcp.json`，个人在设置里加私有服务器」。

⚠️ 一个不明显的副作用：如果项目级写了 `{"name": "fs", "enabled": false}`，它会**覆盖并删除** IDE 级那个 `fs`——这正好是「项目里禁用某个服务器」的用法，但只能靠「写一个空壳加 enabled:false」实现，不直观。

### 13.2 `client.py`（281 行）：为什么手写

文件头（1-6）：

> The official `mcp` package requires Python 3.10+, while the Core targets 3.9, so the stdio transport is implemented directly over subprocess pipes.

**目标 Python 3.9** 是硬约束（打包的独立 Core 用 3.9 运行时）。所以不能用官方 SDK，只能用 `asyncio.create_subprocess_exec` + 手写 JSON-RPC。

`PROTOCOL_VERSION = "2024-11-05"`（21）：MCP 协议版本。`CALL_TIMEOUT = 30.0`：单次调用上限，同时也是 `manager` 注册工具时的 `timeout_sec`。

### 13.3 `_flatten_result` / `_text_of`（29-57）：把 content blocks 拍平成文本

```python
def _flatten_result(result):
    if not isinstance(result, dict): return str(result)
    if result.get("isError"):
        raise MCPError(_text_of(result) or "工具返回错误")
    text = _text_of(result)
    if text: return text
    structured = result.get("structuredContent")
    if structured is not None: return json.dumps(structured, ensure_ascii=False)
    return json.dumps(result, ensure_ascii=False)
```

优先级：**isError → 文本块 → structuredContent → 整个 result 的 JSON**。

`isError=True` 时**抛异常**而不是返回字符串——因为 `call_tool` 的契约是「成功返回文本、失败抛 `MCPError`」，`manager` 里 `except MCPError` 把它变成 `ToolResult(ok=False)`。这样「MCP 工具报错」和「MCP 传输失败」在 manager 层是同一个处理路径。

`_text_of`（43-57）遍历 `content[]`：`type=text` 取 `text`；`type=resource` 把 `resource` 序列化成 JSON；其他类型整体序列化。**不认识的块类型不丢**，而是 dump 成 JSON——保证信息不丢，代价是模型可能看到一堆原始字段。

### 13.4 `StdioMCPClient`（86-193）：一条读循环 + 一张 pending 表

```python
async def start(self):
    if not self.config.command:
        raise MCPError("stdio 传输缺少 command")
    env = dict(os.environ); env.update(self.config.env)
    self._proc = await asyncio.create_subprocess_exec(
        self.config.command, *self.config.args,
        stdin=PIPE, stdout=PIPE, stderr=DEVNULL, env=env)
    self._reader_task = asyncio.create_task(self._read_loop())
    result = await self._request("initialize", self._initialize_params())
    self.server_info = result.get("serverInfo") or {}
    await self._notify("notifications/initialized", {})
```

握手三步不能少：**initialize（请求）→ 读 serverInfo → notifications/initialized（通知）**。第三步是协议要求，不发的话部分服务端会拒绝后续 `tools/list`。

`stderr=DEVNULL`：MCP 服务端的日志全部丢弃。好处是不会污染 stdout（协议通道）；坏处是服务端崩溃时**看不到任何原因**——`_start_one` 只能报 `MCPError("MCP 进程已退出")` 或 `OSError`。

`_read_loop`（115-141）：

```python
while True:
    line = await self._proc.stdout.readline()
    if not line: break
    try:
        message = json.loads(line.decode("utf-8", errors="replace"))
    except ValueError:
        continue
    message_id = message.get("id")
    if message_id is None:
        continue
    future = self._pending.pop(message_id, None)
    if future is None or future.done(): continue
    if "error" in message:
        future.set_exception(MCPError(str(message["error"])))
    else:
        future.set_result(message.get("result") or {})
for future in list(self._pending.values()):
    if not future.done():
        future.set_exception(MCPError("MCP 进程已退出"))
self._pending.clear()
```

- **逐行读**：MCP stdio 的帧是「一行一个 JSON」，所以 `readline()` 就是帧解析器。
- **`errors="replace"`**：非法 UTF-8 不炸，变成替换字符（U+FFFD）。
- **解析失败 `continue`**：服务端往 stdout 打了一行日志（不规范但常见），不因此断连接。
- **`message_id is None` 直接丢弃**：服务端主动发的通知（`notifications/*`）被忽略。

> ⚠️ **服务端发起的请求也被丢弃**。MCP 允许服务端向客户端发请求（`sampling/createMessage`、`roots/list`、`elicitation/*`）。这些请求**有 id**，会走到 `_pending.pop(id, None)` → `None` → `continue` → **既不执行也不回复**。严格的服务端会等这个响应直到超时。当前没有实现任何服务端→客户端能力，所以这是「不支持」，但代码里没有一行说明——只能从「没有 response 分支」推出来。
- **循环结束后**把所有 pending future 都设为异常：进程退出时挂着的调用全部立即失败，不用等 30s 超时。这是正确的收尾。

`_send`（143-149）用 `asyncio.Lock` 串行化写入：多个并发 `_request` 写同一个 stdin，不加锁会交错成两行的一半。`_request`（151-163）：

```python
self._next_id += 1
future = asyncio.get_running_loop().create_future()
self._pending[request_id] = future
await self._send({...})
try:
    return await asyncio.wait_for(future, timeout=CALL_TIMEOUT)
except asyncio.TimeoutError:
    raise MCPError("MCP 调用超时: %s" % method)
finally:
    self._pending.pop(request_id, None)
```

**`finally` 里 pop 是必须的**：超时后 future 还留在表里的话，服务端稍后返回的响应会 pop 到一个已完成的 future（`future.done()` → 跳过），本身无害；但表会慢慢长大。这里清得干净。

`close`（177-192）：cancel reader → close stdin → `terminate()` → 等 3 秒 → `kill()`。⚠️ `self._reader_task.cancel()` 之后**没有 await**，所以可能看到 `Task was destroyed but it is pending!` 警告。无害，但日志不干净。

### 13.5 `HttpMCPClient`（195-250）：一次 POST 一个请求

```python
headers = {"Content-Type": "application/json",
           "Accept": "application/json, text/event-stream"}
if self._session_id:
    headers["Mcp-Session-Id"] = self._session_id
```

`Accept` 同时声明两种响应格式；`Mcp-Session-Id` 由服务端在 initialize 响应头里给出，之后每次请求回传（230 行从响应头里取）。

`_parse_body`（253-275）同时吃两种响应：

```python
if text.startswith("{"):
    return json.loads(text)     # 普通 JSON
for line in text.splitlines():
    if line.startswith("data:"):
        chunk = line[5:].strip()
        if not chunk or chunk == "[DONE]": continue
        parsed = json.loads(chunk)
        if isinstance(parsed, dict):
            last = parsed
return last
```

⚠️ SSE 分支**只保留最后一帧**（`last`）。如果一个响应由多个 `data:` 帧组成（流式工具结果），前面的全丢。当前 MCP 的 `tools/call` 在 streamable-HTTP 下通常只有一个事件包含完整结果，所以能工作；但这不是协议保证的。

⚠️ **HTTP 传输不发 `notifications/initialized`**（对比 stdio 的 113 行）。某些服务端在收到这个通知前会用 400 拒绝一切非 initialize 请求。测试只覆盖了 stdio（`test_mcp.py:93`），所以这个问题不会被测试抓到。

### 13.6 `manager.py`（147 行）：注册、确认、重试

**`tool_name`（21-24）**：

```python
server_slug = _SLUG_RE.sub("_", server).strip("_") or "server"
tool_slug = _SLUG_RE.sub("_", tool).strip("_") or "tool"
return ("mcp__%s__%s" % (server_slug, tool_slug))[:64]
```

`mcp__<server>__<tool>` 是社区约定（Claude 的命名法）。**`[:64]` 静默截断**：

> ⚠️ 两个不同的工具名如果前 64 字符相同，会映射到同一个工具名 → `ToolRegistry.register` 后一个覆盖前一个（只打一条 `re-registered` 警告）→ **模型只能看到其中一个**。服务端名 + 工具名超过约 52 字符时就会发生。

**`_start_one`（71-89）**：

```python
previous = self.status.get(config.name) or {}
attempts = int(previous.get("attempts") or 0) + 1
if attempts > MAX_RESTARTS and not previous.get("ok"):
    self.status[config.name] = dict(previous, attempts=attempts)
    LOGGER.warning("MCP server %s disabled after %d failures", config.name, attempts - 1)
    return
```

`status[name]` 结构：`{"ok": bool, "attempts": int, "error"|"tools": ...}`。`attempts` **跨 refresh 累计**（存在 `self.status` 里，不随重启清零）。

> ⚠️ **熔断条件 `not previous.get("ok")` 导致「成功过就永不熔断」**：一旦某台服务器成功连上一次，`status[name]["ok"]=True`；之后它崩了，下一次 `refresh` 时 `previous.ok` 是 True，条件为假 → 不禁用 → 无限重试。这个「成功即永久豁免」的逻辑在多服务器场景下会产生「每次刷新都等一个死服务器超时」的持续成本。`MAX_RESTARTS=3` 实际只对「从未成功过」的服务器生效。

**`refresh`（44-58）**：

```python
await self.shutdown_clients()
configs = merge_configs(load_project_config(self.project_root), self.settings_configs)
if not configs: return
await asyncio.gather(*(self._start_one(config) for config in configs), return_exceptions=True)
```

**先全关再全开**：任何一次 `settings/update`（改了 MCP 配置）都会**重建所有连接**。`return_exceptions=True` 保证一个失败不影响其他。

⚠️ 这里有一个「改一个服务器重启全部」的成本：每个 stdio 服务器都要重新 spawn 进程 + initialize + list_tools。5 台服务器、每台 300ms，就是 1.5 秒的卡顿（在后台任务里，不阻塞聊天，但工具会短暂不可用）。

**`_register`（93-111）** 与 **`_make_handler`（113-137）**：

```python
tool_def = ToolDef(
    name=tool_name(config.name, raw_name),
    description="[MCP:%s] %s" % (config.name, tool.get("description") or raw_name),
    parameters=schema if isinstance(schema, dict) else {"type": "object", "properties": {}},
    permission=Permission.AUTO if config.auto_approve else Permission.CONFIRM,
    timeout_sec=CALL_TIMEOUT,
    remote=False)
```

- `description` 前缀 `[MCP:server]`：模型能看出这是外部工具，且能在提示里区分同名工具。
- `auto_approve` 直接映射到 `Permission.AUTO`——**Core 侧的权限**，与 Shell 的确认卡无关（卷 06 §2.4）。
- `timeout_sec=30`：与客户端内部的 `CALL_TIMEOUT=30` 相同。所以 `scheduler.wait_for(30)` 与客户端 `wait_for(30)` **几乎同时**触发，谁先谁后不确定——如果是 scheduler 先超时，工具结果变成「工具超时」，而客户端的 future 稍后也会超时并抛异常，那个异常会被 `_request` 的 `finally` 清掉，无人接收。日志里会有一次 `MCP 调用超时` 但用户看到的是另一条消息。

handler 的四步：

```python
if not config.auto_approve and self._confirm is not None:
    granted = await self._confirm(config.name, raw_name, call.arguments or {})
    if not granted:
        return ToolResult(tool_call_id=call.id, ok=False,
                          error="用户拒绝：MCP 工具 %s 未获授权" % raw_name)
client = self.clients.get(config.name)
if client is None:
    await self._start_one(config)
    client = self.clients.get(config.name)
if client is None:
    detail = (self.status.get(config.name) or {}).get("error") or "不可用"
    return ToolResult(tool_call_id=call.id, ok=False, error="MCP 服务 %s %s" % (config.name, detail))
try:
    content = await client.call_tool(raw_name, call.arguments or {})
    return ToolResult(tool_call_id=call.id, ok=True, content=content)
except MCPError as exc:
    return ToolResult(tool_call_id=call.id, ok=False, error=str(exc))
```

**「首次调用时懒启动」**：`refresh` 里启动失败的服务器不在 `self.clients` 里，但工具**已经注册了**（注册发生在 `_start_one` 成功之后……等一下——失败时 `return` 在 `_register` 之前，所以工具根本没注册，模型看不到它）。那 `client is None` 分支什么时候走到？只有在「注册成功过、之后 `shutdown_clients` 清空了 clients」的窗口里（`refresh` 的 `shutdown` 与 `_start_one` 之间）。所以这是一个**竞态兜底**，不是常规路径。

`self._confirm` 由 `RpcServer.__init__` 设为 `_confirm_via_shell`（`server.py:90`）——即「弹 IDE 确认卡」。**注意它在 handler 里 `await`，所以一次 MCP 调用会占用一个工具并发槽**（`Semaphore(4)`）等用户点按钮。这与卷 06 §5 讲的「阻塞点」是同一个模型：Core 协程挂起，Shell 弹卡。

**`summary`（141-147）**：

```python
{"servers": [dict(status, name=name, tools=self.status.get(name, {}).get("tools", 0))
             for name, status in self.status.items()]}
```

`dict(status, ...)` 把已有的 `ok`/`attempts`/`error`/`tools` 展开，再补 `name` 与 `tools`（后者冗余，因为成功时 status 里已经有 `tools`）。`self.status` 里可能有**已经 shutdown 的服务器**（`shutdown_clients` 不清 status）——所以 summary 会列出「成功过但现在没连」的服务器为 `ok:true`。目前没有任何 Shell 调用这个 summary（grep 只命中测试），它属于「为设置页准备的接口」。

---

## 14. `skills/`（250 行）：把 Markdown 变成「可执行的指令」

### 14.1 两个文件的分工

| 文件 | 行 | 职责 |
|---|---:|---|
| `skills/loader.py` | 184 | 找文件、解析 frontmatter、三级覆盖、缓存、匹配 |
| `skills/service.py` | 68 | 两个工具（`skill_list` / `skill_load`）+ `instruction_for` |

`skills/__init__.py` 只做 re-export（`Skill` / `SkillLoader` / `SkillService` / `SKILL_TOOLS` / `parse_skill`）。

一个 Skill 就是磁盘上的一个 Markdown：

```markdown
---
name: daily-report
description: 生成日报
scenarios: ["生成日报", "写日报"]
tools: [git_log]
---
# 指令
...
```

📌 为什么用 Markdown 而不是 JSON/DSL：模型最终要读的是**自然语言指令**，Markdown 既是数据也是最终 prompt 的片段；用户可以用编辑器改、可以进 git、可以让模型自己生成。

### 14.2 `BUILTIN_DIR` 与打包约束

```python
BUILTIN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "builtin")
SKILL_FILE = "SKILL.md"
```

（26-27）

`BUILTIN_DIR` 是**相对源码目录**算出来的，不是配置项。这意味着：

- 内置 Skill 必须作为 package data 打进 wheel（卷 02 的 packaging 一节）；漏打 → 运行时 `os.path.isdir` 为假 → `_read_dir` 返回 `[]` → 静默变成「没有内置 Skill」。
- 装成 editable 模式时它指向源码目录，所以开发期改文件立刻可见——但 `_cache` 不会（§14.7）。

### 14.3 `_FRONTMATTER_RE`：一个正则吃掉整个文件头

```python
_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n?(.*)$", re.S)
```

（28）

逐段读：

- `^---\s*\n`：第一行必须是 `---`（允许行尾空白）。
- `(.*?)` + `re.S`：非贪婪地吃到**第一个**「换行 + `---`」。
- `\n---\s*\n?`：结束标记；`\n?` 让「`---` 正好是最后一行」也能匹配。
- `(.*)$`：剩下的全部是正文。

⚠️ **正文里不能有单独一行的 `---`**。因为 `(.*?)` 非贪婪，正文中第一个顶格的 `---`（Markdown 水平分割线）会被当作 frontmatter 结束，于是 frontmatter 里混进正文、body 被截断。用 `***` 或 `___` 做分割线即可。

`match(text.lstrip("\ufeff"))`（58）：剥掉 UTF-8 BOM。Windows 记事本保存的 UTF-8 会带 BOM，不剥的话 `^---` 匹配不上 → 整个文件被当正文 → `name` 为空 → **Skill 被静默丢弃**。这是最容易踩的坑之一。

### 14.4 `parse_frontmatter`（57-88）：手写 YAML 子集

只支持四种形态：

| 写法 | 结果 |
|---|---|
| `key: value` | `str`（首尾成对引号会剥掉） |
| `key:` 然后若干 `- item` | `list[str]` |
| `key: [a, b]` | `list[str]` |
| `key:` 空值且后面没有 `- item` | `[]` |

```python
if stripped.startswith("- ") and current_key:
    bucket = meta.get(current_key)
    if not isinstance(bucket, list):
        bucket = []
        meta[current_key] = bucket
    bucket.append(_strip_quotes(stripped[2:].strip()))
    continue
```

（68-74）

- 只要行以 `- ` 开头且**有上一个 key**，就追加到那个 key，**不管缩进**。所以正文里以 `- ` 开头的行只要落在 frontmatter 区内，也会被吞进上一个列表。
- `current_key` 是「最近出现的 key」，不是「最近一个值为空的 key」。`name: x` 后面跟一行 `- foo`，`foo` 会被追加到 `name`，把 `name` 从 `str` 变成 `list`——随后 `str(meta.get("name"))` 得到 `"['x', 'foo']"`，Skill 名字就坏了。⚠️ 这是手写解析器的边界。

```python
if ":" not in line:
    continue
key, _, value = line.partition(":")
```

（75-77）

- 用**第一个**冒号切分，所以 `description: 用法: 先跑测试` 能得到 `用法: 先跑测试`。
- 没有冒号的行**静默丢弃**。
- 不支持注释（`#` 成为值的一部分）、不支持嵌套、不支持多行字符串（`|` / `>`）、不支持转义引号。

`_strip_quotes`（51-54）：只有**首尾同一种引号**才剥，`'a"` 原样保留。

📌 取舍：完整 YAML 要引入 PyYAML，而 Core 的依赖表是刻意精简的（卷 02）。这里覆盖「够写 90% SKILL.md」的语法，剩下 10% 用「不要那么写」规避。

### 14.5 `parse_skill`（91-106）：name 是唯一的硬要求

```python
name = str(meta.get("name") or "").strip()
if not name:
    return None
```

（93-95）

- 没有 `name` 的 `.md` 被**静默跳过**（连日志都没有）。所以「我放了个 SKILL.md 但没生效」的第一排查点是 frontmatter 里有没有 `name`。
- `scenarios` / `tools` 允许标量，统一包成列表：

```python
scenarios=[str(s) for s in scenarios] if isinstance(scenarios, list) else [str(scenarios)],
```

（101-102）

- `body=body.strip()`：去掉正文首尾空白，`render()` 时再拼头。
- `scope` / `path` 是**运行时元数据**，不来自 frontmatter——防止文件自称 `scope: project` 骗过覆盖逻辑。

### 14.6 `Skill.matches`（40-42）：子串匹配

```python
lowered = (text or "").lower()
return any(scenario and scenario.lower() in lowered for scenario in self.scenarios)
```

两句 `.lower()`：中文无影响，英文 `Fix Bug` 能匹配 `fix bug`。

⚠️ 三个后果：

1. **子串而非词边界**：scenario `"日报"` 会匹配 `"生成日报"`（预期），但 scenario `"报"` 会匹配 `"报告"`、`"汇报"`、`"报警"`（超范围）。
2. **只看 scenarios**，不看 description / name。
3. `scenario and` 跳过空串，不会「匹配一切」。

### 14.7 `SkillLoader`：三级目录 + 永不失效的缓存

```python
dirs = [(BUILTIN_DIR, "builtin")]
if self.global_dir:
    dirs.append((self.global_dir, "global"))
if self.project_root:
    dirs.append((os.path.join(self.project_root, ".agent", "skills"), "project"))
```

（118-124）

顺序即优先级：后读的覆盖先读的（`skills[skill.name] = skill`，同名后写赢）。

`_read_dir`（126-148）逐条：

```python
for entry in sorted(os.listdir(directory)):
    entry_path = os.path.join(directory, entry)
    if os.path.isdir(entry_path):
        skill_path = os.path.join(entry_path, SKILL_FILE)
        if not os.path.isfile(skill_path):
            continue
    elif entry.endswith(".md"):
        skill_path = entry_path
    else:
        continue
```

- 一个子目录 = 一个 Skill，**文件名必须是 `SKILL.md`**（大小写敏感；Windows 上 `skill.md` 也能命中，Linux 上不行——测试跑在哪个平台就只在哪个平台过）。
- 也可以直接在目录里放 `xxx.md`（平铺形态，`test_flat_markdown_file_is_accepted`）。
- `sorted` 保证同目录内顺序确定；同名（一个 `foo/` 目录 + 一个 `foo.md`）时**按文件名字母序后者覆盖前者**，并会打一行 `overridden by ... scope`（157）——但日志说的是 scope，实际可能是同一 scope 内的覆盖，会误导排查。

```python
try:
    with open(skill_path, "r", encoding="utf-8") as handle:
        skill = parse_skill(handle.read(), skill_path, scope)
except OSError:
    LOGGER.warning("cannot read skill %s", skill_path)
    continue
```

（140-145）

⚠️ **只捕获 `OSError`**。`encoding="utf-8"` 遇到 GBK 文件会抛 `UnicodeDecodeError`，它不是 `OSError`（继承链是 `UnicodeError → ValueError`），于是**直接冒泡**。冒泡路径：`load_all` → `list_skills` / `get` → `instruction_for`（`server.py:562`）→ `_run_task` 里 `skill_instruction=` 是 `run_chat` 的参数、位于 `try` 之外 → 整轮 `chat/start` 抛异常 → 用户看到 `chat/error`。**一个用记事本存成 ANSI 的 SKILL.md 可以让这个项目的聊天功能整体不可用**。这是本卷最值得修的一处（改成 `except (OSError, UnicodeError)`）。

同样未被捕获的还有 `os.listdir` 的 `PermissionError`——它虽然是 `OSError`，但调用点在 `try` 之外。

**缓存**：

```python
if self._cache is not None and not refresh:
    return self._cache
```

（151-152）

`refresh` 参数**没有任何调用方传 True**（全仓库 grep 只有 `load_all()`）。所以：

- 新建/修改 Skill 文件 → **不重启就看不到**。
- 但 `_bind_project_root`（`server.py:511`）会 **new 一个 `SkillLoader`**，所以「切换项目」会顺手刷新；`settings/update` 不会。
- 与 MCP 形成对比：MCP 有设置变更钩子（`refresh`），Skills 没有。

### 14.8 覆盖与「字母序决定谁生效」

`load_all` 只有「同名覆盖」，没有「优先级权重」。`match` 是：

```python
for skill in self.list_skills():
    if skill.matches(user_input):
        return skill
```

（170-174）

`list_skills` 按 `name` 字母序（165）。于是：

⚠️ **两个不同名的 Skill 都能匹配时，名字字典序小的赢**，跟 builtin / global / project 的层级**无关**。项目级 `aaa-report` 永远输给内置 `daily-report`。想强制指定只能靠 Shell 传 `skill` 参数——而**两个 Shell 都不传**（全仓库 grep `skill` 只命中 `server.py` 与一句注释）。所以自动匹配是唯一路径。

### 14.9 `SkillService.instruction_for`（45-47）：每次任务只算一次

```python
skill = self.loader.get(skill_name) if skill_name else self.loader.match(user_input)
return skill.render() if skill else ""
```

- `skill_name` 非空 → **只按名字取，不再回退自动匹配**；名字打错就是空字符串，**没有任何提示**。
- 自动匹配只看**用户这一条消息**（`user_input`），不看附件、不看 `@文件`、不看历史。
- 调用点是 `server.py:562`，作为 `run_chat(...)` 的参数——即 **`chat/start` 时算一次，整轮不变**。用户在第二轮说「用日报技能」不会重新匹配（但模型可以自己调 `skill_load`）。

`render()`（44-48）：

```python
header = "遵循以下 Skill 指令完成任务：\n# Skill: %s\n%s" % (self.name, self.description)
if self.tools:
    header += "\n建议工具: %s" % ", ".join(self.tools)
return header + "\n\n" + self.body.strip()
```

`tools` 只是**文字建议**：不校验、不授权、不会自动注册。写一个不存在的工具名进去，模型会去调它，然后拿到「未知工具」。

### 14.10 两个工具

| 工具 | 权限 | 参数 | 返回 |
|---|---|---|---|
| `skill_list` | `auto` | 无 | 每个 Skill 一段：`## name [scope]` + description + `场景: a、b` |
| `skill_load` | `auto` | `name` | `render()` 全文 |

`permission="auto"` 传的是**字符串**，`ToolDef` 的 pydantic 字段会把它转成 `Permission.AUTO`（卷 06 §2.2）。两个都 `remote=False`，纯 Core 侧执行。

`_load_handler`（58-67）：

```python
name = (args.get("name") or "").strip()
if not name:
    return ToolResult(tool_call_id=call.id, ok=False, error="缺少参数 name")
skill = self.loader.get(name)
if skill is None:
    return ToolResult(tool_call_id=call.id, ok=False, error="未知 Skill: %s" % name)
```

错误信息会进模型上下文，所以模型能自我纠正（换成 `skill_list` 里的名字重试）。

⚠️ **`skill_load` 的返回值会被压缩**。`loop.py:165` 对**所有成功**的工具结果调用 `compress_tool_output`，预算 `TOOL_OUTPUT_BUDGET=1800` token。`skill_load` 没有 `path` 参数，走不到「读文件保留头部 + 结构化概览」的分支（`compression.py:46` 要求 `tool_name == "read_file"`），只能落到最后一行：

```python
return truncate_to_budget(content, budget)
```

（`compression.py:56`）

而 `truncate_to_budget` **保留头部、丢掉尾部**（§16.4）。SKILL.md 的常见写法是「背景 → 步骤 → 输出格式 → 禁止事项」，被截掉的正好是**最后几步和约束**。所以长 Skill 要把最重要的步骤写在前面，或者拆成多次 `skill_load`。

### 14.11 `summary()` 是死代码

`SkillLoader.summary()`（176-183）生成「可用 Skill：」列表。全仓库**没有任何调用方**（`grep summary()` 只命中 `subagent.py` 的同名方法与测试）。它大概是为「把 Skill 列表塞进系统提示词」准备的，但系统提示词目前只拼 `skill_instruction`（§14.9）。📌 读到这里不要以为「不匹配就没 Skill 信息」——`skill_list` 工具才是模型发现 Skill 的正规入口。

---

## 15. `usage.py`（162 行）：只写不读的账本

### 15.1 三条设计约束

文件头（1-10）写得很明确：

> Nothing leaves the machine: there is no user id and no network request. The file is rotated once it passes 10 MB ... and the report is only computed when the Shell asks for it.

翻译成三条：

1. **不出网**：没有上报、没有 user id。
2. **不能撑爆磁盘**：10MB 轮转。
3. **按需计算**：不是实时计数器，是「读日志算报表」。

| 常量 | 值 | 说明 |
|---|---|---|
| `USAGE_FILE` | `usage.jsonl` | 当前文件 |
| `MAX_BYTES` | 10 MiB | 轮转阈值 |
| `MAX_REPORT_DAYS` | 90 | `days` 上限 |
| `DEFAULT_REPORT_DAYS` | 7 | 默认窗口 |
| `TIME_FORMAT` | `%Y-%m-%dT%H:%M:%SZ` | **UTC**，结尾 `Z` |

### 15.2 目录：两个环境变量

```python
return os.environ.get("AGENT_LOG_DIR") or os.path.join(
    os.path.expanduser("~"), ".agent", "logs")
```

（30-31）

但 `RpcServer.__init__` 用的是 **`AGENT_USAGE_DIR` 或 `default_log_dir()`**（`server.py:84`）：

```python
self.usage = UsageLog(os.environ.get("AGENT_USAGE_DIR") or default_log_dir())
```

所以「用量目录」与「日志目录」默认同一个（`~/.agent/logs`），但可以分别覆盖。🔁 `usage.jsonl` 与 `core-*.log` 混在一个目录里，`files()` 靠前缀过滤（§15.6），不会误读日志。

### 15.3 UTC 时间戳

```python
def timestamp(epoch: float) -> str:
    return time.strftime(TIME_FORMAT, time.gmtime(epoch))

def _parse_timestamp(text: str) -> float:
    try:
        return float(calendar.timegm(time.strptime(text, TIME_FORMAT)))
    except (TypeError, ValueError):
        return 0.0
```

（34-42）

- 写用 `gmtime`，读用 `calendar.timegm`（把 struct_time 当 UTC 解释），两边对称。
- **不能用 `time.mktime`**：它按本地时区解释，跨时区读旧文件会整体偏移。
- 解析失败返回 `0.0` → 一定小于 `cutoff` → **该行被静默丢弃**（不是计入总数）。手改坏一行不会让报表崩，只会少一条。

### 15.4 `record`（52-70）：一行一个任务

| 字段 | 来源 |
|---|---|
| `ts` | 写盘时的 UTC 时间 |
| `taskId` | `_run_task` 的 12 位 id |
| `model` | `self.config.model`（**当前配置**） |
| `in` / `out` | `chat/finish` 里的 `usageTotal` |
| `toolCalls` | 本轮 `on_tool_start` 的计数 |
| `durationMs` | `time.monotonic()` 差值 |
| `ok` | `ok and not session.deleted` |

三个细节：

1. `int(x or 0)` 把 `None` 变 0；`bool(ok)` 归一。
2. `open(..., "a", ...)` + `newline="\n"`：Windows 上不会写出 `\r\n`，保证 JSONL 跨平台一致。
3. `except OSError` 只包住**写**；`json.dumps` 在 `try` 里但不会失败（全是 int/str/bool）。

⚠️ **`ok` 的语义是「任务成功且会话没被删」**：`server.py:597-598` 传的是 `ok and not session.deleted`。取消的任务 `ok=False`，但**仍会记一行**（`finally` 总会执行），token 数为 0（`usage_total` 还是 `{}`）。所以报表里 `tasks` 包含被取消的任务，`failed` 也包含取消——「失败率」不等于「错误率」。

### 15.5 轮转（72-89）：两个分支，一个竞态

```python
if not os.path.exists(self.path) or os.path.getsize(self.path) < MAX_BYTES:
    return
target = os.path.join(self.directory, "usage-%s.jsonl" % time.strftime("%Y%m"))
```

- 归档名按**本地时区**的月份（`time.strftime` 不带 `gmtime`），而内容里的 `ts` 是 UTC。跨月边界上归档文件名可能与内容日期差一天——不影响统计（`report` 不按文件名分桶），只影响人肉排查。
- 目标已存在（同月第二次轮转）：**读出源文件追加到归档，再删除源文件**；不存在则直接 `os.replace`。
  - 为什么不在目标已存在时 `os.replace`：会覆盖当月归档的前半段。
  - 为什么先追加再删：避免「删了源、追加失败」导致数据全丢。

⚠️ **多进程并发**：每个 IDE 项目一个 Core 进程，但它们共享 `~/.agent/logs`。轮转没有文件锁：

1. A 读完源文件、还没 `os.remove` 时，B 追加了一行到源文件；
2. A 删除源文件 → B 刚写的那行**永久丢失**。

概率低（10MB 才触发一次、窗口是毫秒级），但这是真实存在的数据丢失路径。要修就是 `msvcrt.locking` / `fcntl.flock`，或者每进程一个文件名（`usage-<pid>.jsonl`）。

### 15.6 `files()`（93-101）：按名字排序

```python
names = [name for name in os.listdir(self.directory)
         if name == USAGE_FILE
         or (name.startswith("usage-") and name.endswith(".jsonl"))]
return [os.path.join(self.directory, name) for name in sorted(names)]
```

`usage-202511.jsonl` 的 `-`（0x2D）排在 `usage.jsonl` 的 `.`（0x2E）前面，所以**归档在前、当前文件在后**。聚合不关心顺序，这个顺序只影响手动单步调试时先看到哪一批。

### 15.7 `report`（103-162）：五个聚合

```python
days = max(1, min(int(days or DEFAULT_REPORT_DAYS), MAX_REPORT_DAYS))
cutoff = time.time() - days * 86400
```

- `days=0` → 默认 7（因为 `0 or 7`）；`days=-5` → `max(1, ...)` → 1；`days=9999` → 90。
- `cutoff` 是**滚动窗口**：现在 14:30，`days=1` 是「过去 24 小时」，不是「今天」。

| 输出 | 含义 | 注意 |
|---|---|---|
| `tasks` / `ok` / `failed` | 任务数 | `ok` 缺省视为 `true`（旧行兼容） |
| `tokensIn` / `tokensOut` | 总 token | 只有正常结束的任务有值 |
| `toolCalls` | 总工具调用 | 取消的任务为 0 |
| `durationMs` / `avgDurationMs` | 总耗时 / 平均 | 平均是整数除法，`tasks=0` 时为 0 |
| `byDay` | 按天 | key 是 `ts[:10]`，**UTC 日期** |
| `models` | 按模型 | 按 `tasks` 降序，缺省 `"unknown"` |

⚠️ **UTC 日期桶 vs 滚动窗口**：`days=7` 的窗口是 7×24 小时，`byDay` 最多能出现 **8 个不同日期**（跨日边界）。且中国用户本地 08:00 前的任务会被记到**前一天**的 UTC 桶里。要按本地日统计，得把 `ts` 转本地时区再取日期——现在没有。

```python
"byDay": [dict(date=day, **by_day[day]) for day in sorted(by_day)],
"models": [dict(model=name, **models[name])
           for name in sorted(models, key=lambda n: models[n]["tasks"], reverse=True)],
```

`models` 的排序键只有 `tasks`，**并列时保持插入顺序**（`sorted` 稳定）——即「先出现的模型排前面」，不是字母序。

### 15.8 谁写、谁读

| 方向 | 位置 |
|---|---|
| 写 | `server.py:597` 每个任务 `finally` → `_record_usage`（`server.py:606-617`） |
| 读 | `usage/report`（`server.py:287-288`） |

⚠️ **没有任何 Shell 调用 `usage/report`**（对 `src/main/java` 与 `vscode/src` 双 grep 零命中；唯一调用方是 `core/scripts/smoke_v3.py:177`）。聊天界面上的「用量 chip」（`StatusRow`）用的是 `chat/finish` 里现成的 `usage` / `usageTotal`，不是这个报表。

📌 所以这条存储线目前是**只写不读**：数据在攒，接口在，但没有消费方。第 10 卷讲设置页时可以回头看它是否被接上。

---

## 16. `native/`（360 行）：能 Rust 就 Rust，否则纯 Python

### 16.1 分工

| 文件 | 行 | 职责 |
|---|---:|---|
| `native/__init__.py` | 93 | 门面：逐函数探测 `agent_core_native`，失败回落 |
| `native/tokenizer.py` | 74 | 纯 Python 的 token 估算 + 预算截断 |
| `native/codesoup.py` | 196 | 纯 Python 结构解析（`ast` + 正则 + 花括号配对） |

### 16.2 门面契约（16-22）

```python
try:  # pragma: no cover - depends on the built wheel being importable
    import agent_core_native as _rust  # type: ignore
except Exception:  # ImportError, ABI mismatch, missing symbol ...
    _rust = None

BEHIND_RUST = _rust is not None
BACKEND = "rust" if BEHIND_RUST else "python"
```

📌 `except Exception` 而不是 `except ImportError`：一个 ABI 不匹配的 `.pyd` 抛的可能是 `OSError` / `AttributeError`；**加速层坏掉不能让 Core 起不来**。这是「可选依赖」的正确写法。

但接下来每个函数又各自 `hasattr` + `try/except`：

```python
def count_tokens(text: str, model: str = "gpt-4o") -> int:
    if _rust is not None and hasattr(_rust, "count_tokens"):
        try:
            return int(_rust.count_tokens(text, model))
        except Exception:  # pragma: no cover - defensive
            LOGGER.warning("native tokenizer failed, falling back", exc_info=True)
    return _py_tokenizer.count_tokens(text, model)
```

（36-42）

三层意义：**模块在** ≠ **函数在** ≠ **函数能跑**。Rust 扩展可能只实现 `codesoup` 没实现 `tokenizer`，也可能某个函数在特定输入上 panic（Python 侧表现为异常）。三层都过才用 Rust。

📌 代价：每次调用都要 `hasattr` + `try`，相对真正的分词/解析开销可忽略；但失败一次只 warning，**没有「记住它坏了」的标志**，所以一个稳定失败的 Rust 函数会每次调用都刷一条 warning。

### 16.3 `native_info()`（28-33）：报告得比实际准

```python
return {
    "backend": BACKEND,
    "tokenizer": BACKEND,
    "codesoup": "rust" if BEHIND_RUST and hasattr(_rust, "parse_text") else "python",
}
```

⚠️ `tokenizer` 字段**只看了 `_rust` 是否存在**，没检查 `hasattr(_rust, "count_tokens")`，更没检查调用是否成功。所以可能出现「`tokenizer: "rust"` 但每次 `count_tokens` 都回落纯 Python」的报告。`codesoup` 那一行检查了 `parse_text`，两个字段口径不一致。

它被两处使用：`core/hello` 的 `native` 字段（`server.py:175`）与 `python -m agent_core --native-info`（`__main__.py:86-89`）。

### 16.4 `tokenizer.py`：1 个宽字符 = 1 token，其余 4 字符 = 1 token

```python
CHARS_PER_TOKEN = 4
SENTENCE_ENDINGS = "\u3002\uff01\uff1f\uff1b\n.!?;"
TRUNCATION_MARKER = "\n...(已截断)"
```

（13-15）

```python
return max(1, wide + (narrow + CHARS_PER_TOKEN - 1) // CHARS_PER_TOKEN)
```

（38）

`_is_wide`（18-27）覆盖六段：CJK 扩展 A、CJK 统一表意、假名、韩文音节、CJK 兼容表意、全角形式。

⚠️ **`。`「」`、` 落在 `0x3000–0x303F`，不在任何区间里** → 按窄字符算 1/4 token；而 `，！？`（`0xFF0C` / `0xFF01` / `0xFF1F`）属于全角形式 → 按 1 token。同样是中文标点，成本差 4 倍。不过因为最后整体向上取整，零散的窄字符往往还是被进位成 1 token，1:4 的差异只在大段 ASCII 里才明显。Emoji（U+1F600 等）也全在区间外 → 1/4 token，明显低估。

这是**预算估计**不是**计费**：低估会多发内容，`ContextBuilder` 还有别的安全边界（卷 05）。

`max(1, ...)`：非空文本至少 1 token，空串返回 0（31-32 提前返回）。**不可加**：`count("a") + count("b") = 2`，而 `count("ab") = 1`。按消息逐条累加会略微**高估**（安全方向）。

`truncate_to_budget`（45-73）五步：

1. 空文本 / `budget<=0` / 已够 → 原样返回。
2. `effective = budget - count_tokens(TRUNCATION_MARKER)`：**标记本身要占预算**，否则截完加标记会超。`effective<=0` 时直接返回 `TRUNCATION_MARKER.strip()`（去掉前导换行）。
3. 二分找最长可行前缀：

```python
low, high = 0, len(text)
while low < high:
    mid = (low + high + 1) // 2
    if count_tokens(text[:mid], model) <= effective:
        low = mid
    else:
        high = mid - 1
```

用**上中位数** `(low+high+1)//2`。换成 `(low+high)//2` 会在 `low+1 == high` 时死循环——这是二分求「最大满足值」的标准写法。

每次判断都重新 `count_tokens(text[:mid])`，是 O(mid) 的 Python 逐字符循环；二分扫的前缀长度构成几何级数，合计约 2n 字符，所以整体 O(n)，但常数很大（每字符一次函数调用）。**这就是纯 Python 回退的真实成本**，Rust 扩展存在的意义就在这里。

4. 句界回退：

```python
cut = low
boundary = max(text.rfind(ch, 0, cut) for ch in SENTENCE_ENDINGS)
if boundary >= int(cut * 0.6):
    cut = boundary + 1
```

- 在 `text[:cut]` 里找最后一个句界（中英文句号/问号/分号/换行）。
- 只有句界在**切点后 40% 以内**（`boundary >= cut*0.6`）才回退；否则宁可切在句中，也不扔 40% 预算。📌 这是「别为了美观损失 40%」的阈值。
- `\n` 也在集合里，所以代码块优先切在行尾。
- 找不到任何句界时 `max(...) == -1`，条件为假，保留 `cut`。

5. **保留头部**：`text[:cut]`。

🔁 **两个「截断」方向相反**：
- `truncate_to_budget`（工具输出）→ **保留开头**，文件头有 import / 签名 / 定义。
- `ContextBuilder`（对话历史）→ **丢最旧的轮次、保留尾部**，最近的话最相关。

### 16.5 `codesoup.py`：没有 tree-sitter 时的结构解析

`detect_language`（90-101）：

```python
if path:
    _, ext = os.path.splitext(path.lower())
    language = LANGUAGE_BY_EXTENSION.get(ext)
    if language:
        return language
if content and content.startswith("#!"):
    ...
```

- 先看扩展名（**20 个后缀 → 10 种语言**：python / rust / javascript / typescript / java / go / c / cpp / csharp / kotlin），大小写不敏感。
- 扩展名不认识才看 shebang，且**只认 `python` 与 `node`**。
- `marker in first_line` 是**子串**判断：`#!/usr/bin/env node` 命中；以 `#!` 开头且注释里含 `node` 的脚本也会误判（影响很小）。
- 返回 `None` 表示「不认识」；`chunker` 拿到 `None` 直接走滑窗（§9）。

`_brace_end`（104-117）：

```python
depth = 0
opened = False
for index in range(start_index, len(lines)):
    for ch in lines[index]:
        if ch == "{": depth += 1; opened = True
        elif ch == "}":
            depth -= 1
            if opened and depth <= 0:
                return index + 1
return len(lines)
```

- `opened` 解决 Allman 风格（`{` 在下一行）：函数头那一行没有 `{`，`depth` 还是 0，不能因为看到 `}` 就返回。
- **不识别字符串与注释**：`if (x) { s = "}"; }` 会提前结束，`/* } */` 同理。
- 花括号不平衡（缺 `}`）→ 返回文件末行，这块代码「一直到文件尾」。
- 每个函数从自己的起点扫到自己的终点，嵌套函数重复扫（外层包含内层）——最坏 O(n²)，实际源码很少嵌套很深。

`_parse_python`（120-150）：

```python
try:
    tree = ast.parse(content)
except SyntaxError:
    return []
```

- 整文件 `ast.parse`；**一处语法错误 → 全文没有函数 → chunker 落到滑窗**。保守但保证不给错的行号。
- 递归 `visit`：遇到类先递归类体（方法名带 `类名.` 前缀），**然后**把类本身也 append 进去。类的 `start_line` 比方法小，最后 `nodes.sort(key=start_line)` 把类排到方法前。
- 类的 `signature` 是字面量 `"class"`，不是真实继承列表。
- `_python_signature` 用 `ast.unparse(node.args)`（3.9+），失败返回 `""`。
- `end_lineno` 用 `getattr(..., None) or node.lineno`。⚠️ `lineno` 指向 `def` 行，**装饰器行不在函数块内**——chunk 会漏掉 `@decorator`。

`_parse_regex`（162-185）：

- 九种语言的函数头正则（Rust / JS / TS / Java / Go / C / C++ / C# / Kotlin）。
- Go 跳过缩进行：`if line.startswith((" ", "\t")) and language in ("go",)`——Go 的方法必须顶格，缩进的是函数体内的闭包。
- 名字黑名单 `("if","for","while","switch","catch","return","new","sizeof")` 过滤 C/Java 的 `if (` 误匹配。
- `signature=line.strip()[:160]`：截断签名，避免 `render` 时撑爆。
- 每个匹配都调 `_brace_end`，所以是 O(匹配数 × 块长)。

### 16.6 谁在用

| 调用方 | 用的函数 | 目的 |
|---|---|---|
| `agent/compression.py:11-16` | `count_tokens` / `detect_language` / `outline_of` / `truncate_to_budget` | 压缩 `read_file` 输出（卷 06 §7） |
| `codeindex/chunker.py:76,82` | `detect_language` + `parse_text` | 函数优先切块（§9） |

`outline_of`（84-92）是门面的组合函数：`detect_language` → `parse_text` → `"\n".join(fn.label() ...)`。注意它输出 `FunctionNode.label()`（`name [L1-L20]`），而 `ParsedFile.outline()` 输出 `name(signature)`——**两个 outline 格式不同**，`compression` 用前者，`test_outline_of_returns_labelled_lines` 固化的也是前者。

⚠️ **门面丢掉了 Rust 的 query 能力**：`parse_text` 把 Rust 返回的 `functions` 重新包成 **Python 的 `ParsedFile`**（65-78），而 Python 版的 `query()` 直接 `return []`（80-82）。所以即使装了 Rust 扩展，也拿不到 tree-sitter 查询结果——除非调用方直接 `import agent_core_native`。目前没有调用方这么干。

---

## 17. 这一卷的 RPC 表面与消费者

### 17.1 请求方法全表

`_dispatch`（234-263）先分流：`id is None` → 通知；`inline/complete` / `index/build` → 后台 task；其余 → `_handle_request`（265-309）。

| 方法 | 行 | 归属 | Shell 调用方 |
|---|---:|---|---|
| `chat/start` | 266 | 卷 03 | Java `AgentChatPanel` / VS Code `panel.ts` |
| `ping` | 268 | 卷 01 | 就绪探测 |
| `tool/result` | 270 | 卷 06 | Java `ToolDispatcher` / VS Code |
| `agent/confirmResult` | 272 | 卷 06 | `DiffConfirmCard` / `CommandConfirmCard` / 预审卡 |
| `ask/userAnswer` | 274 | 卷 04 | Java `AskCard` |
| `index/status` | 276 | 本卷 §12 | **无** |
| `session/list` | 278 | 本卷 §1 | Java 历史面板 |
| `session/get` | 280 | 本卷 §1 | Java 打开标签 |
| `session/delete` | 282 | 本卷 §1/§2 | Java 历史删除 |
| `session/search` | 290 | 本卷 §3 | Java 搜索框 |
| `memory/pending` | 293 | 本卷 §8 | Java 记忆候选面板 |
| `memory/promote` | 295 | 本卷 §7/§8 | Java「保存」 |
| `memory/discard` | 297 | 本卷 §8 | Java「忽略」 |
| `usage/report` | 303 | 本卷 §15 | **无** |
| `team/list` | 305 | 卷 03 §10 | Java / VS Code 团队选择器 |
| `team/artifacts` | 307 | 卷 03 §19 | 重开会话时回放黑板 |
| `inline/complete` | 249 | 卷 08 | `InlineCompletionClient`（后台 task） |
| `index/build` | 252 | 本卷 §12 | **无**（后台 task） |

⚠️ 三个「无消费者」的方法：`usage/report`、`index/status`、`index/build`。grep 两个 Shell 的源码都是零命中——只有测试/冒烟脚本在调。`index/build` 在 `README.md:91` 的协议表里被列为 Shell→Core 请求，但**实现先于调用方**。

### 17.2 两个必须后台化的方法

```python
if method == "inline/complete":
    asyncio.create_task(self._run_inline(req_id, params))
    return
if method == "index/build":
    asyncio.create_task(self._run_index_build(req_id, params))
    return
```

（249-254）

原因（源码注释 246-248）：stdin 循环是**单协程串行**的，在这里 `await` 一个跑几秒到几分钟的活，期间到达的 `chat/cancel`、`tool/result`、别的 `inline/complete` 全部排队。编辑器每敲一个字符发一次补全请求，一旦排队，聊天就卡死。

📌 两个方法**自己负责发响应**（`_run_inline` 里 `self._send(make_response(...))`），`_dispatch` 只负责 `return`。所以它们的异常不能冒泡——`_run_inline` 连 `except Exception` 都写了，并返回空文本。

### 17.3 通知表

| 通知 | 发出点 | 消费者 |
|---|---|---|
| `core/hello` | `run()` 启动时（182） | Java `AgentCoreService` / VS Code `session.ts` |
| `agent/ping` | 每 3 秒（230） | 心跳 |
| `session/indexProgress` | `_reindex_sessions` 363/369 | Java `AgentChatPanel` / VS Code 状态行 |
| `index/progress` | `_run_index_build`（427） | **无**（`index/build` 没人调） |
| `chat/token` | `emit_token`（577） | 面板正文 |
| `chat/reasoning` | `emit_reasoning`（583） | 思考折叠行（**不进会话历史、不计 usage**） |
| `chat/toolStart` / `chat/toolEnd` | 588 / 597 | 工具时间线 |
| `chat/notice` | `on_notice`（607） | 状态行 / 提示行 |
| `chat/finish` | 628 | 收尾 + 用量 + 就绪 |
| `chat/error` | 639 / 644 / 650 | 错误行 + 出错状态 |
| `memory/pendingChanged` | `_capture_memories`（698） | Java `AgentChatPanel` / VS Code |
| `chat/todos` | `_publish_todos`（702） | `TodoStrip` |
| `chat/artifact` | `_publish_artifact`（709） | 交付物抽屉（团队黑板） |
| `chat/subagent` | `_on_subagent_event`（744） | 子代理 / 成员进度 |
| `tool/invoke` | `_invoke_shell_tool`（791） | Shell 工具执行 |
| `agent/confirm` | 804（MCP）/ 835（预审） | 确认卡 |
| `ask/user` / `ask/withdraw` | 863 / 880 | 问答卡 |

一共 **19 种**通知。两个壳都只接一部分：IntelliJ 接了 17 个（`agent/ping` 与 `index/progress` 无分支，08 卷 §17.1），VS Code 接了 14 个（除这两个外还忽略 `chat/reasoning` 与 `ask/user` / `ask/withdraw`，09 卷 §7.11）。

### 17.4 会话索引的两个钩子

```python
self.sessions = SessionManager(self.store,
                               on_saved=self._on_session_saved,
                               on_deleted=self._on_session_deleted)
```

（81-83）

```python
def _on_session_saved(self, session_id, title, content, updated_at):
    self.search.index(session_id, title, content, updated_at)
```

`SessionManager.save` 在 `try/except` 里调它（§0.3），所以**索引失败不影响落盘**。

`_reindex_sessions`（354-364）在 `run()` 里 `create_task`，用 `asyncio.to_thread` 跑 `_index_all_documents`——`iter_documents` 要读最多 50 个 JSON、`index_many` 要写 SQLite，都是阻塞 IO。进度通知里的 `"total": 0` 是刻意的：`index_many` 只知道「处理到第几个」，不知道总数（`iter_documents` 是生成器，不预先 count）。

### 17.5 `_bind_project_root`（534-547）：一次懒绑定，重建五个服务

```python
if not project_root or project_root == self.project_root:
    return
self.project_root = project_root
self.memory = MemoryService(project_root, self.data_dir)
self.memory.register_tools(self.tools)
self.index.set_project_root(project_root)
self.skills = SkillService(SkillLoader(project_root))
self.skills.register_tools(self.tools)
self.mcp.set_project_root(project_root)
self._schedule_mcp_refresh()
self.subagents.loader.set_project_root(project_root)
```

触发点：`chat/start` 的 `contextSnapshot.project_root`（`_chat_start`，490）。Core 启动时 `project_root` 来自 `AGENT_PROJECT_ROOT` 环境变量，可能为空；第一次收到快照时补上。

⚠️ 五件事同时发生：

1. **`MemoryService` 被整个替换** → `overview_text()` 缓存丢掉；`pending` 列表切到新项目；三个 `memory_*` 工具重新注册（handler 变成新对象的方法）。
2. **`SkillLoader` 被重建** → 缓存清空（唯一让 Skill 变更生效的路径，§14.7）。
3. **`CodeIndexService.set_project_root`** → 下一次 `search` / `build` 用新根目录；**已打开的 SQLite 连接与向量缓存不会自动清空**（§12 只在模型变更时清）。
4. **MCP 全量重连**（`refresh` 先关后开，§13.6）。
5. **`SubagentLoader` 缓存失效**（3.3.0）：`SUBAGENT.md` 的改动因此会在切项目后生效；团队定义不在这里，`team/*` 另有自己的加载器。

📌 为什么「重建」而不是「改字段」：`MemoryService` 的 `paths` / `store` 都在 `__init__` 里算好并打开 SQLite，改 `project_root` 字段不会让它们跟着变——重建最不容易出错（代价是丢缓存）。

🔁 **同一个 `project_root` 有两个来源**：环境变量（启动参数）与 `contextSnapshot`（每次 `chat/start`）。后者优先，但**只在字符串不等时才重建**；两个 Shell 传的路径写法不同（`D:\a\b` vs `D:/a/b`）会让它反复重建 → 每次都清 overview 缓存 + 重连 MCP。这是值得留意的性能陷阱。

### 17.6 参数校验与错误码

| 场景 | 行为 |
|---|---|
| 未知方法 | `RpcError(PROTOCOL_ERROR, "unknown method: ...")`（309） |
| `chat/start` 空 message | `PROTOCOL_ERROR`（`_chat_start` 490） |
| `memory/promote` / `memory/discard` 空 id | `PROTOCOL_ERROR`（324-325 / 299-300） |
| `memory/promote` 找不到 pending | `KeyError` → `PROTOCOL_ERROR`（339-341） |
| `memory/promote` 参数非法 | `ValueError` → `PROTOCOL_ERROR`（342-343） |
| 其余任何异常 | `INTERNAL_ERROR`（261-263），并 `LOGGER.exception` |

📌 `int(params.get("days") or 7)`、`int(params.get("limit") or 20)` 这类写法**没有 try**：`days="abc"` 会抛 `ValueError` → 落到 `INTERNAL_ERROR`。不是安全问题（本地进程），但会让「参数错」看起来像「Core 崩了」。

### 17.7 跨进程字段契约：本卷发现的三处错位

| 字段 | Core 写 | Shell 读 | 后果 |
|---|---|---|---|
| 候选记忆 `source` | `user_instruction` / `user_correction`（§8） | Java / VS Code 判 `"correction"` | 永远显示「偏好」，纠错类从不显示「纠正」 |
| `memory/promote` 的 `duplicate.title` | 返回标题 | Java 只用 `duplicate.id` | 提示文案拿不到标题，只能显示 id |
| `index/status` 的 `numpy` | 返回布尔 | 无消费者 | 无法诊断「是否走了纯 Python 回退」 |

前两条是**同一种错误**：Core 与 Shell 各写各的字符串，没有共享常量。🔁 修法只有两个：Core 改成 Shell 认的值（向后兼容要同时认两种），或两边共享一份枚举（需要跨语言生成）。

---

## 18. 测试映射：131 个用例在守什么

全仓 `core/tests` 现在是 **27 个文件、4,112 行、298 个 `def test_`**（26 个 `test_*.py` + `fake_mcp_server.py` 71 行作为 stdio MCP 的假服务器）。其中与本卷（会话 / 记忆 / 索引 / 用量）直接相关的 11 个如下：

| 文件 | 行 | 用例 | 守的是 |
|---|---:|---:|---|
| `test_session_store.py` | 228 | 14 | JSON 落盘/恢复、两种裁剪、墓碑、`.tmp` 清扫、列表合并 |
| `test_session_search.py` | 120 | 9 | trigram 中文子串、LIKE 回退、`>=` 跳过、删除即失效 |
| `test_todos.py` | 147 | 11 | 规范化、渲染、无绑定会话时拒写、v1 兼容 |
| `test_memory.py` | 127 | 11 | upsert 身份、FTS 搜索、Markdown 镜像、global 作用域 |
| `test_memory_pending.py` | 203 | 22 | 两条抽取规则、否定句保留、编号单调、晋升五步 |
| `test_codeindex.py` | 128 | 9 | 切块、余弦排序、模型变更清空、增量跳过 |
| `test_mcp.py` | 189 | 14 | 两种配置形状、项目级覆盖、SSE、熔断、拒绝授权 |
| `test_skills.py` | 132 | 12 | 两种列表语法、三级覆盖、场景匹配、显式加载 |
| `test_usage.py` | 98 | 6 | JSONL、按天聚合、窗口过滤、轮转保全文 |
| `test_usage_totals.py` | 66 | 3 | 多轮工具调用累加、缺失 usage、OpenAI total |
| `test_native.py` | 163 | 20 | 计数单调、预算截断、Python/Rust 后端一致 |

重点用例与它固化的**契约**（改代码前先看这些）：

| 用例 | 固化的事实 |
|---|---|
| `test_cap_never_orphans_a_tool_result` | 内存裁剪按整轮切，绝不产生孤儿 `tool` |
| `test_trimming_never_starts_with_an_orphan_tool_result` | 落盘 `[-240:]` 后弹掉头部 TOOL |
| `test_deleted_session_is_not_written_back_by_a_running_task` | 墓碑 + 清空消息双保险 |
| `test_stale_temp_file_is_swept_on_list` | `.tmp` 超 1 小时在 `list` 时顺手删 |
| `test_reindex_is_skipped_when_not_newer` | `_index_one` 用 `>=`，同毫秒不重写 |
| `test_short_query_falls_back_to_like` | `len(query) < 3` 走 LIKE |
| `test_negated_instruction_is_kept` | 「不要用 mock」是**有效**记忆，不是否定即忽略 |
| `test_ids_keep_increasing_after_a_discard` | 候选编号单调，不复用 |
| `test_promote_reports_a_likely_duplicate` | 重复只报告、不阻止写入 |
| `test_model_change_returns_no_hits` | 换 embedding 模型后旧向量不可用 |
| `test_build_then_incremental_skips_unchanged` | sha1 相同的文件不重 embedding |
| `test_failed_server_is_recorded_and_disabled` | 失败 3 次后的 `ok:false` 状态 |
| `test_project_skill_overrides_global` | 项目级同名 Skill 覆盖全局 |
| `test_rotation_archives_and_keeps_every_line` | 轮转后两处文件行数之和不变 |
| `test_truncate_respects_budget` | 截断 + 标记 ≤ 预算 |
| `test_without_a_bound_session_the_tool_refuses` | `todo_write` 没有 ContextVar 绑定时报错而不是写到无处 |

📌 这个仓库的测试风格是「**行为契约**」而不是「覆盖率」：`test_session_store.py` 里 `test_cap_never_orphans_a_tool_result` 这个名字本身就在解释为什么不能按条裁剪。读测试比读实现更快理解设计意图。

⚠️ 测试没有覆盖的几条：`skills/loader.py` 的非 UTF-8 分支（§14.7）、`usage` 的并发轮转（§15.5）、`_bind_project_root` 对路径写法的敏感性（§17.5）、`native_info` 的 `tokenizer` 字段误报（§16.3）。这四处都是「读代码才能发现」。

---

## 19. 易错点清单

按「踩到会浪费多少时间」排序：

| # | 陷阱 | 位置 |
|---:|---|---|
| 1 | 存储失败全部静默（只有日志），用户看不到 | 全卷 `try/except` |
| 2 | `MAX_SESSION_MESSAGES` 与 `MAX_MESSAGES` 是两份 240，改一处会不一致 | §1.1 / §2.1 |
| 3 | 单轮对话不裁剪（`starts < 2` 直接 return） | §1.4 |
| 4 | 内存按整轮切、落盘按 `[-240:]` 硬切，可能以 `assistant` 开头 | §2.5 |
| 5 | 删除会话要打墓碑，否则 `finally` 的 save 会写回 | §1.5 |
| 6 | `_index_one` 用 `>=`，同毫秒第二次保存不更新索引 | §3.3 |
| 7 | FTS 与 LIKE 两条路径语义不同（OR+bm25 vs 整句子串+时间） | §3.6 |
| 8 | 记忆 `updated_at` 是秒，会话是毫秒 | §1.3 / §5.1 |
| 9 | 记忆 upsert 身份含 `IFNULL(project_path,'')`，少一列就重复 | §5.3 |
| 10 | `overview_text` 硬截 4000 字符，超 100 条条目静默消失 | §7.2 |
| 11 | 候选 `source` 写 `user_correction` 但 Shell 判 `"correction"` | §8.8 / §17.7 |
| 12 | 代码索引按函数优先，文件顶部 import/常量不进索引 | §9.5 |
| 13 | `errors="ignore"` 读文件，GBK 中文全部静默丢失 | §12.3 |
| 14 | `replace_file` 返回实际写入块数，空向量会留下「永久空洞」 | §11.4 |
| 15 | `stats` 用 `MAX(model)` / `MAX(dim)`，多模型混存时报错值 | §11.6 |
| 16 | MCP `tool_name` 截到 64 位可能碰撞；`mcp__<s>__<t>` 不是双射 | §13.3 |
| 17 | MCP 熔断条件 `not previous.get("ok")`：成功过就永不熔断 | §13.5 |
| 18 | Skill 缓存永不失效，加文件要重启或切项目 | §14.7 |
| 19 | Skill 文件非 UTF-8 → `UnicodeDecodeError` 冒泡到 `chat/start` | §14.7 |
| 20 | 长 `skill_load` 输出被截断，丢的正好是尾部步骤 | §14.10 |
| 21 | `usage.jsonl` 多 Core 进程共享，轮转无锁可能丢行 | §15.5 |
| 22 | `byDay` 用 UTC 日期，本地 08:00 前算前一天 | §15.7 |
| 23 | `usage/report` / `index/status` / `index/build` 没有 Shell 消费者 | §17.1 |
| 24 | `parse_text` 门面丢掉 Rust 的 tree-sitter `query` | §16.6 |

---

## 20. 自测清单

命令在仓库根目录跑；Core 的 venv 按卷 02 的方式准备。

1. **看一条会话的落盘**
   ```powershell
   Get-ChildItem "$env:USERPROFILE\.agent\data\*\sessions\*.json" | Select-Object -First 3
   ```
   打开一个：顶层是 `version` / `title` / `updatedAt` / `todos` / `messages`，`messages` 里的字段是 `toolCalls`（camelCase）。

2. **看全文索引的表**
   ```powershell
   python -c "import glob,sqlite3,os; p=glob.glob(os.path.expanduser('~/.agent/data/*/sessions.db'))[0]; print([r[0] for r in sqlite3.connect(p).execute('select name from sqlite_master')])"
   ```
   应能看到 `sessions_text`（镜像表）、`sessions_fts`（trigram 虚表）与若干触发器。

3. **验证短词回退**：在历史搜索框输入两个汉字（如「日报」）能搜到；输入一个汉字走 LIKE，结果按时间排序而不是相关度。

4. **验证记忆晋升**：说一句「以后都用中文回答」→ 面板出现候选 → 点保存 → `<项目>/.agent/memories/*.md` 出现文件、`overview.md` 多一行；再点一次同样的候选，`memory/promote` 返回 `duplicate`。

5. **验证 Skill 覆盖**：在 `<项目>/.agent/skills/daily-report/SKILL.md` 写同名 Skill，重启 Core，`skill_list` 里 `scope` 变成 `project`。

6. **验证索引**：让模型调 `build_index`，观察 `index/progress`；第二次调，`updated` 文件数应接近 0。

7. **验证用量**
   ```powershell
   Get-Content "$env:USERPROFILE\.agent\logs\usage.jsonl" -Tail 3
   ```
   每个任务一行，`ts` 是 UTC（结尾 `Z`）。

8. **验证原生回退**
   ```powershell
   python -m agent_core --native-info
   ```
   `backend` 为 `python` 说明没装 Rust 扩展；此时 `count_tokens("你好")` 应为 2。

9. **验证墓碑**：在一个会话正在跑长任务时删掉它，任务结束后刷新历史，会话不应复活。

---

## 21. 下一卷

第 08 卷进入 **Java UI**（约 7,000 行）：`ChatSessionPanel` 如何把一条 `chat/token` 变成屏幕上的字符、`ChatView` / `ClassicTranscript` 两套渲染路径怎么选、`AskCard` 的 627 行怎么组织问答、`SessionTabsBar` 的标签状态机、`MarkdownRenderer` 的 389 行如何避免「每次都重建整棵组件树」，以及 `ui/design/` 那套颜色/间距常量为什么不能硬编码。

读 08 之前建议回看：§17.3 的通知表（UI 的所有输入都来自那张表）、卷 01 §6（`chat/token` → `appendDelta` 的时序）、卷 06 §2（`tool/invoke` 到确认卡的数据形状）。
