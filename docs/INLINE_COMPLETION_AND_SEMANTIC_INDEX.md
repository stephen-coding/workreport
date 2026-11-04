# 行内补全与语义索引：技术详情（v2.2.0）

> 本文是 [使用手册](../USER_GUIDE.md) 的配套技术说明，面向"想搞懂内部怎么实现"的读者。
> 内容全部对应当前仓库代码，可直接跳转阅读。

---

## 0. 名词与阅读地图

| 名词 | 含义 |
|---|---|
| **Ghost text / 行内补全** | 编辑器里以灰色文字内联展示的补全结果，Tab 接受、Esc 取消 |
| **FIM（fill-in-the-middle）** | 给定"光标前文 prefix + 光标后文 suffix"，让模型只生成中间缺失部分 |
| **Shell** | 宿主侧代码：IntelliJ 插件（Java + Kotlin）/ VS Code 扩展（TS） |
| **Core** | IDE 无关的 Agent 内核（Python），负责 LLM 调用、工具调度、索引 |
| **Embedding** | 把文本映射成定长浮点向量，语义相近则向量方向相近 |
| **余弦相似度** | 两个向量夹角余弦；向量归一化后等于点积 |

**建议阅读顺序**（第一次看）：

1. [§1 全局架构](#1-全局架构为什么拆成-shell-与-core) → 建立进程与职责概念
2. [§2 行内补全全链路](#2-行内补全全链路) → 跟一条请求从头走到尾
3. [§3 语义索引](#3-语义索引) → 搞懂切块、向量、SQLite 检索
4. [§5 调试手册](#5-调试手册) → 亲手跑一遍，加深印象

---

## 1. 全局架构：为什么拆成 Shell 与 Core

### 1.1 进程模型

```
IntelliJ IDEA 进程
├── 插件 JVM 代码（Java/Kotlin）
│     ├── AgentChatPanel      聊天 UI
│     ├── AgentCoreService    项目级：持有唯一的 Core 进程 + RPC 连接
│     ├── ToolDispatcher      执行 Core 派来的文件/Git 操作
│     └── AgentInlineCompletionProvider  行内补全入口
│
└── 子进程 agent-core.exe（PyInstaller 打包的 Python）
      ├── RpcServer      stdin/stdout 处理 JSON Lines
      ├── Agent loop     LLM 决策 + 工具调度
      ├── CodeIndexService / llm.completion
      └── ~/.agent/data/<项目哈希>/  (SQLite)
```

**为什么不让插件直接调模型？**

| 问题 | 放在 JVM 里 | 放在 Core 里 |
|---|---|---|
| 换 IDE（VS Code / 其他 JetBrains） | 每个宿主重写一遍 | 一份代码，协议对等 |
| 工具循环、上下文压缩、子代理 | Java 里做很重 | Python 生态成熟 |
| 升级迭代 | 要重新发插件 | 只换 Core 可执行文件 |
| 用户机器依赖 | — | PyInstaller 打包后免装 Python |

代价是**跨进程通信**，所以协议必须简单可靠 → 选 JSON Lines（一行一个 JSON，天然可流式）。

### 1.2 三个角色

1. **Shell 提供"能力"**：文件读写、正则搜索、Git、终端、diff 确认卡 —— 这些必须用 IDE API
2. **Core 提供"决策"**：调哪个模型、下一步做什么、并发调度
3. **双方对等**：Core 也能主动向 Shell 发请求（`tool/invoke`、`agent/confirm`）

### 1.3 共享会话：`AgentCoreService`

v2.1.0 之前，聊天面板自己 new 进程、自己发 settings；v2.2.0 又多了行内补全，
如果两个组件各起一个 Core，会出现**双进程、双份内存、设置不一致**。

于是抽出项目级服务：

```java
@Service(Service.Level.PROJECT)
public final class AgentCoreService implements Disposable {
    public JsonRpcConnection connection() throws IOException { ... }   // 懒启动，exe 优先
    public JsonRpcConnection connectionOrNull() { ... }               // 不触发启动
    public void pushSettings(JsonRpcConnection conn) { ... }          // 下发模型/MCP/Embedding
    public void restart() { ... }                                     // 设置页关闭后重建
}
```

- 聊天面板：`coreService.setMessageHandler(this::onCoreMessage)` 接收通知
- 行内补全：只借用 `connection()`，**不注册** handler（它只关心响应）
- `restart()` 只断开连接，下一次 `connection()` 才真正拉起新进程

---

## 2. 行内补全全链路

### 2.1 平台提供了什么

IntelliJ 平台把"行内写灰色文字"抽象成扩展点 `com.intellij.inline.completion.provider`：

```xml
<inline.completion.provider
        id="com.workreport.agent.inline"
        implementation="com.workreport.agent.inline.AgentInlineCompletionProvider"
        order="first"/>
```

对应的 API（`ide-codeinsight-inline` 产物）三件套：

| 类型 | 作用 |
|---|---|
| `InlineCompletionProvider` | 接口：`id` / `isEnabled(event)` / `suspend getSuggestion(request)` |
| `DebouncedInlineCompletionProvider` | 抽象类：帮你实现"停笔 N 毫秒后再算"，只需实现 `getDebounceDelay` 与 `getSuggestionDebounced` |
| `InlineCompletionSuggestion` | 结果：`Empty` 或 `SingleSuggestion.build(elements = Flow<InlineCompletionElement>)` |

元素用 `InlineCompletionTextElement(text, TextAttributes)` 表示一段纯文本，
`TextAttributes` 通常取自 `InlineCompletionFontUtils.attributes(editor)`，保证灰色样式与 IDE 一致。

> **版本门槛**：该产物在 233/251 的仓库里拿不到稳定 artifact，252（2025.2）起才可用，
> 所以 `plugin.xml` 的 `since-build` 从 233 提到 **252**。

### 2.2 Provider 实现（Kotlin）

```kotlin
class AgentInlineCompletionProvider : DebouncedInlineCompletionProvider() {
    override val id = InlineCompletionProviderID(AgentInlineCompletionProvider::class.java.name)

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!ReportSettingsState.getInstance().inlineCompletionEnabled) return false
        val request = event.toRequest() ?: return false
        val file = request.file.virtualFile ?: return false
        return file.isInLocalFileSystem && !file.isDirectory
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest) = DEBOUNCE // 300ms

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        // 1) 超 512KB 直接放弃
        // 2) 从 document 取 [offset-4000, offset) 作为 prefix，[offset, offset+1200) 作为 suffix
        // 3) withContext(Dispatchers.IO) 调 InlineCompletionClient.complete(...)
        // 4) 空结果返回 Empty；否则 build(elements = flowOf(TextElement(...)))
    }
}
```

**几个设计选择**

| 选择 | 原因 |
|---|---|
| `isEnabled` 里读设置 | 关闭时平台根本不会调度，省掉一次 RPC |
| 限制 `isInLocalFileSystem` | 远程/内存文件没有稳定文本，且可能很慢 |
| 512KB 上限 | 大文件取文本、渲染都耗内存 |
| `Dispatchers.IO` | Java 侧 `future.get()` 是阻塞调用，不能占用 UI 协程线程 |
| 空结果返回 `Empty` | 平台对 `Empty` 不渲染任何东西，用户无感 |

### 2.3 为什么是 4000 / 1200 字符

- **prefix 4000**：约 100~150 行代码，足够模型判断"现在在写什么函数、什么风格"
- **suffix 1200**：约 30 行，用来避免生成"已经存在的代码"（真正的 FIM 需要右侧上下文）
- 再大：首 token 延迟明显上升，而行内补全的体验红线是"别卡住输入"

Core 侧还会再裁一次（`MAX_PREFIX_CHARS = 4000`、`MAX_SUFFIX_CHARS = 1200`），
防止其他 Shell（如 VS Code）传超长文本。

### 2.4 客户端与超时（Java）

```java
public static String complete(Project project, String path, String language,
                              String prefix, String suffix) {
    JsonRpcConnection conn = AgentCoreService.getInstance(project).connection(); // 可能启动 Core
    Map<String, Object> params = Map.of("prefix", prefix, "suffix", suffix, ...);
    JsonObject response = conn.request("inline/complete", params).get(1500, MILLISECONDS);
    ...
}
```

- `JsonRpcConnection.request()` 返回 `CompletableFuture<JsonObject>`，
  内部把 `id → future` 存进 `ConcurrentHashMap`，收到同 id 的响应就 `complete()`
- **超时 1.5 s** 是体验阈值：宁可这次不补，也不能让灰色文字迟到
- 任何异常（Core 挂了、超时、JSON 结构不对）→ 返回 `""`

### 2.5 Core 为什么必须放到后台 task

`_dispatch` 是 stdin 读取循环里的"分发器"。如果直接 `await` 一个 8 秒的模型请求：

- 期间 `chat/cancel`、`tool/result` 都读不到 → 聊天卡死、工具结果丢失

所以：

```python
if method == "inline/complete":
    asyncio.create_task(self._run_inline(req_id, params))
    return
if method == "index/build":
    asyncio.create_task(self._run_index_build(req_id, params))
    return
```

同理 `index/build` 可能要跑几十秒（大量文件 × embedding），也必须后台化。
`index/status` 是纯读 SQLite，几十毫秒，留在同步路径没问题。

`_run_inline` 里的兜底策略：

```python
text = await asyncio.wait_for(complete_inline(...), timeout=12.0)
except asyncio.TimeoutError:  return {"text": ""}
except Exception:             return {"text": ""}   # 永不向编辑器抛错
```

> 客户端 1.5 s 先超时，Core 的 12 s 只是为了防止 task 泄漏；两边都放弃时用户只看到"没建议"。

### 2.6 FIM 是怎么回事

普通 chat 是"给完整对话，生成下一条消息"。FIM 是"给前文和后文，只生成缺口"：

```
prefix:  def add(a, b):
suffix:  
             return a + b

模型应补：  """两数相加"""

```

不同提供商的 FIM 姿势不同：

**Ollama**（`/api/generate` 支持 `suffix`）：

```json
POST http://localhost:11434/api/generate
{
  "model": "qwen2.5-coder:7b",
  "prompt": "<prefix>",
  "suffix": "<suffix>",
  "stream": false,
  "options": { "temperature": 0.1, "num_predict": 128, "stop": ["\n\n"] }
}
→ { "response": "……补全文本……" }
```

**OpenAI 兼容**（`/v1/completions` 支持 `suffix`）：

```json
POST https://api.example.com/v1/completions
{
  "model": "xxx",
  "prompt": "<prefix>",
  "suffix": "<suffix>",
  "max_tokens": 128,
  "temperature": 0.1,
  "stop": ["\n\n"]
}
→ { "choices": [ { "text": "……补全文本……" } ] }
```

**URL 推导**（用户在设置页只填聊天端点，`completion.py` 自己换路径）：

| 用户填的 base_url | Ollama 推导结果 | OpenAI 兼容推导结果 |
|---|---|---|
| `http://localhost:11434/api/chat` | `http://localhost:11434/api/generate` | — |
| `http://localhost:11434/api/generate` | 原样 | — |
| `https://api.x.com/v1/chat/completions` | — | `https://api.x.com/v1/completions` |
| `https://api.x.com/v1/completions` | — | 原样 |

端点没有 `/completions`（如只提供 chat 的云端）时返回 4xx → 记 debug 日志、返回空串，**不弹错误**。

### 2.7 后处理：`sanitize()` 的四条规则

模型即使被要求"只输出补全片段"，也常犯错。四种典型脏数据的处理：

| 问题 | 现象 | 处理 |
|---|---|---|
| 加了 Markdown 围栏 | 返回 ```` ```python\n...\n``` ```` | 丢掉所有以 ```` ``` ```` 开头的行 |
| 重复后文 | 补全内容又把光标后已有的行抄了一遍 | 找到 suffix 中第一个长度 ≥4 的非空行，从它开始截断 |
| 回显前文 | 把 prefix 最后一行又输出了一遍 | prefix 末行 ≥8 字符且文本以它开头 → 去掉这段并 `lstrip("\n")` |
| 多余空行 | prefix 已换行，返回还以换行开头 | 去掉开头一个 `\n` |

处理完再 `rstrip()`，如果只剩空白就返回 `""`（宁可没有，也不要插入空行）。

### 2.8 失败路径一览

| 场景 | 用户看到 | 日志 |
|---|---|---|
| Core 未启动且启动失败 | 无补全 | `LOG.debug("agent core unavailable ...")` |
| 1.5 s 内没响应 | 无补全 | `LOG.debug("inline completion request failed: timeout")` |
| 端点 404/不支持 FIM | 无补全 | Core `LOGGER.debug("completions 404 ...")` |
| 模型返回空/全是空白 | 无补全 | — |
| 设置里关闭开关 | 平台不调度 | — |

设计原则：**行内补全是"锦上添花"，任何情况下都不能打断用户打字**。

### 2.9 关键常量

| 常量 | 值 | 位置 |
|---|---|---|
| 防抖 | 300 ms | `AgentInlineCompletionProvider.DEBOUNCE` |
| 前文上限 | 4000 字符 | Provider / `completion.MAX_PREFIX_CHARS` |
| 后文上限 | 1200 字符 | Provider / `completion.MAX_SUFFIX_CHARS` |
| 文件大小上限 | 512 KB | `AgentInlineCompletionProvider.MAX_FILE_BYTES` |
| 客户端超时 | 1500 ms | `InlineCompletionClient.TIMEOUT_MS` |
| Core 超时 | 12 s | `RpcServer._run_inline` |
| HTTP 超时 | connect 2s / read 8s | `completion._TIMEOUT` |
| 温度 / 最大 token | 0.1 / ≥16（默认 128） | `completion.complete()` |

---

## 3. 语义索引

### 3.1 需求拆解

"帮我在这个仓库里找到实现某功能的代码"需要三件事：

1. **切块**：把文件切成适合模型消化的片段（整文件太大）
2. **向量化**：片段 → 向量（Embedding 模型）
3. **检索**：查询 → 向量 → 找最相似的片段

只有第 3 步"找最近邻"可以交给向量数据库；**前两步必须自己实现**。
这三步里，只有第 3 步是"数据库问题"，而它的规模在本场景很小：

| 仓库规模 | 代码块数（约） | 暴力点积耗时（numpy, 1024 维） |
|---|---|---|
| 5 万行 | 1~2 千 | < 5 ms |
| 50 万行 | 1~2 万 | 20~50 ms |
| 500 万行 | 10~20 万 | 200~500 ms |

一次对话里最多检索几次，几百毫秒完全可接受 → **不需要 ANN 索引，更不需要独立向量库**。

### 3.2 为什么不用 Chroma / FAISS / pgvector

| 方案 | 优点 | 本项目不选的理由 |
|---|---|---|
| **SQLite + BLOB**（当前） | 零依赖、单文件、随插件分发、事务安全 | 十万级以后全表扫描变慢（可接受） |
| FAISS | 极快、支持 HNSW/IVF | 需要为每个平台编译/分发二进制，PyInstaller 体积和兼容性成本高 |
| Chroma | 开箱即用 | 额外 Python 依赖树（含 onnx 等），打包体积暴涨，且要管进程/端口 |
| pgvector / ES | 团队共享、可扩展 | 要用户自己起数据库 —— 与"装个插件就能用"冲突 |

结论：**向量存储用 SQLite 的一列**，检索用 numpy 矩阵点积，numpy 缺失时纯 Python 回退。

### 3.3 数据模型

```sql
CREATE TABLE code_files (              -- 文件级：增量判断用
  project_path TEXT NOT NULL,          -- 项目根（绝对路径），支持一个 Core 数据目录多项目
  path         TEXT NOT NULL,          -- 项目内相对路径（统一 /）
  file_hash    TEXT NOT NULL,          -- 内容 sha1
  updated_at   INTEGER,
  PRIMARY KEY (project_path, path)
);

CREATE TABLE code_chunks (             -- 块级：检索用
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  project_path TEXT NOT NULL,
  path TEXT NOT NULL,
  lang TEXT,                           -- 语言（tree-sitter 识别结果）
  symbol TEXT NOT NULL DEFAULT '',     -- 函数/类名，窗口块为空
  start_line INTEGER NOT NULL,
  end_line   INTEGER NOT NULL,
  file_hash  TEXT NOT NULL,
  text TEXT NOT NULL,                  -- 原始片段（展示给模型）
  vector BLOB NOT NULL,                -- float32 小端，已归一化
  dim INTEGER NOT NULL,                -- 维度，防止混模型
  model TEXT NOT NULL,                 -- embedding 模型名
  updated_at INTEGER
);
```

**为什么向量存 BLOB 而不是 JSON**：`array.array("f").tobytes()` 是 4 字节/维的紧凑二进制；
JSON 数组会膨胀 3~5 倍且解析慢。1024 维 = 4 KB/块，1 万块 ≈ 40 MB。

**为什么存 `dim`**：万一用户换了模型但没触发重建，查询向量和存量向量维度不同，
`_load()` 会过滤掉维度不一致的行，避免点积直接崩。

### 3.4 切块策略

```
文件 → 语言识别（detect_language）
     ├─ 成功 → tree-sitter 解析 → 每个函数/类一个 chunk（body ≥16 字符）
     └─ 解析失败 / 无可切块 → 60 行窗口、10 行重叠
```

| 参数 | 值 | 说明 |
|---|---|---|
| `MAX_FILE_BYTES` | 400 KB | 更大的文件直接跳过 |
| `MAX_CHUNK_CHARS` | 2400 | 单块字符上限（超出截断） |
| `WINDOW_LINES` / `WINDOW_OVERLAP` | 60 / 10 | 回退窗口：保证跨行上下文，重叠避免"切断语义" |
| 函数最小长度 | 16 字符 | 过滤 getter 之类的碎片 |

**为什么按函数切比按行切好**：函数是语义自洽的最小单元，"这个函数干什么"更接近用户提问的粒度；
检索命中后返回的是完整函数，模型可以直接读懂，无需再拼上下文。

**embed_text 与 text 的区别**：入库向量时会在正文前拼一行头部 `path (symbol:start-end)`，
让"文件名 + 函数名"也参与语义匹配；而返回给模型、展示给用户的是干净的 `text`。

### 3.5 Embedding 客户端

```python
class Embedder:
    async def embed(self, texts):
        for batch in chunks(texts, 16):              # BATCH_SIZE = 16
            batch = [t[:4000] for t in batch]        # 单条 4000 字符上限
            if provider == "openai_compat": self._openai(batch)
            else:                           self._ollama(batch)
```

| 提供商 | 首选接口 | 回退 |
|---|---|---|
| Ollama | `POST /api/embed` `{model, input: [...]}` → `{embeddings: [[...]]}` | 404 时按文本逐条调 legacy `POST /api/embeddings` `{model, prompt}` → `{embedding: [...]}` |
| OpenAI 兼容 | `POST /v1/embeddings` `{model, input: [...]}` → `{data:[{index, embedding}]}` | — |

- URL 同样由聊天端点推导（`/api/chat` → `/api/embed`，`/chat/completions` → `/embeddings`）
- 返回的 `data` 会按 `index` 排序，保证与输入顺序对齐
- 批处理是为了减少 HTTP 往返：16 条/次在延迟与内存之间比较平衡

### 3.6 检索：归一化 + 点积 = 余弦

```python
def normalize(v):  return [x / ||v|| for x in v]      # 入库与查询都归一化
def pack(v):       return array.array("f", normalize(v)).tobytes()

# numpy 路径
matrix = np.frombuffer(b"".join(blobs), dtype="float32").reshape(N, dim)   # N×dim
scores = matrix @ np.asarray(query, "float32")                             # N 个余弦值
top    = scores.argsort()[::-1][:limit]
```

数学依据：

```
cos(a,b) = (a·b) / (|a|·|b|)
若 |a| = |b| = 1  →  cos(a,b) = a·b
```

所以"归一化一次、检索只做点积"是标准做法。纯 Python 回退就是 `sum(a*b for ...)` 逐行算。

**缓存**：`_load()` 用 `(project_path, model, chunk_count, max_id)` 作为缓存键。
带 `max_id` 是为了捕捉"块数不变但内容被替换"（`replace_file` 先删后插，id 会涨）。
缓存让连续多次检索只读一次数据库；任何写操作（`replace_file/prune/clear`）都会把缓存置空。

**检索流程**（`CodeIndexService.search`）：

```
query → embed → 取 limit*3 条 → path_filter 过滤 → 截到 limit
```

多取 3 倍是为了 `path_filter` 过滤后仍有足够结果。

### 3.7 增量构建

```
build(full=False)
├─ 取项目内可索引文件列表（iter_source_files）
├─ 与 code_files 里的 sha1 对比 → pending（新增/修改）
├─ prune(现有文件列表) → 删除已消失文件的记录
├─ for 每个 pending 文件：
│    chunk_file() → embedder.embed(chunks) → store.replace_file()
│    progress(i, total, path)      # → 通知 index/progress
└─ 返回 stats(files/chunks/dim/model/scanned/changed/embeddedChunks)
```

**换模型自动重建**：

```python
if full or (stored["chunks"] and stored["model"] and stored["model"] != model):
    self.store.clear(root)      # 不同模型的向量不可比较，必须整库重来
```

**并发保护**：`asyncio.Lock` 保证同一时刻只有一次 build（用户可能连续说两次"重建索引"）。
注意它在 `__init__` 里**不能**直接创建——Python 3.9 下 `asyncio.Lock()` 需要 event loop，
所以声明为 `Optional[asyncio.Lock] = None`，在 `build()` 里懒创建。

**为什么先 prune 再嵌入**：删文件不需要算向量，先清理能少算一批；也避免中途失败留下"文件表有、块表无"的脏数据。

### 3.8 工具与 RPC

| 工具 | 参数 | 行为 |
|---|---|---|
| `semantic_search` | `query`（必填）、`limit` 1~20（默认 6）、`path_filter` | 命中返回 `### path:symbol (行 a-b, 相似度 0.xx)` + 代码块（单块截 1200 字符） |
| `build_index` | `full` | 返回文件数/块数/模型/扫描数/本次更新数 |

索引为空时 `semantic_search` 不报错，而是回一句"（语义索引为空，先调用 build_index 建立索引）"，
让模型自己决定下一步 —— 这是**错误即提示**的典型做法，比抛异常更好。

RPC：

| 方法 | 类型 | 说明 |
|---|---|---|
| `index/status` | 请求（同步） | 返回 `chunks/files/dim/model/updatedAt/numpy/building/root` |
| `index/build` | 请求（后台 task） | 构建完成后回响应；过程中发 `index/progress` 通知 |
| `inline/complete` | 请求（后台 task） | 回 `{"text": "..."}`，失败也回空串 |

### 3.9 局限与演进方向

| 局限 | 说明 | 可行演进 |
|---|---|---|
| 暴力检索 | 10 万块以上变慢 | 引入 HNSW（hnswlib）或量化 IVF，仍可存 SQLite 旁边 |
| 无调用关系 | 不知道"谁调用了这个函数" | 用 tree-sitter 抽 call graph，与向量结果做混合排序 |
| 无 rerank | 只用 embedding 相似度 | 检索后用 cross-encoder 二次排序（成本更高，适合"精确找实现"） |
| 切块按语法 | 跨文件语义（如 DTO + 转换器）被拆散 | 增加"同目录聚合块"或按 import 关系加权 |
| 单查询 | 不扩展同义词 | 查询改写（用 LLM 生成 2~3 个变体再合并去重） |

---

## 4. 构建与兼容

### 4.1 Kotlin + Java 混合编译

行内补全 API 是 **Kotlin + 协程**风格（`suspend`、`Flow`、`kotlin.time.Duration`），
用 Java 实现会非常别扭，所以 Provider 用 Kotlin 写、客户端用 Java 写，两者互相调用。

Maven 默认只认 Java，需要显式加 Kotlin 编译：

```xml
<plugin>
  <groupId>org.jetbrains.kotlin</groupId>
  <artifactId>kotlin-maven-plugin</artifactId>
  <version>${kotlin.version}</version>   <!-- 必须与平台内置 Kotlin 一致，当前 2.3.20-RC2 -->
  <executions>
    <execution>
      <id>compile-kotlin</id>
      <phase>process-sources</phase>     <!-- 早于 maven-compiler-plugin 的 compile -->
      <goals><goal>compile</goal></goals>
    </execution>
  </executions>
  <configuration>
    <jvmTarget>21</jvmTarget>
    <sourceDirs>
      <sourceDir>${project.basedir}/src/main/kotlin</sourceDir>
      <sourceDir>${project.basedir}/src/main/java</sourceDir>  <!-- 让 Kotlin 能引用 Java 类型 -->
    </sourceDirs>
  </configuration>
</plugin>
```

生命周期顺序：

```
process-sources → [kotlin:compile] 产出 .class + META-INF/*.kotlin_module
compile         → [javac] 编译 Java，classpath 里已能看到 Kotlin 产物
```

依赖策略：

| 依赖 | scope | 原因 |
|---|---|---|
| `ide-codeinsight-inline` | provided | IDE 运行时自带，打进插件会重复类 |
| `kotlin-stdlib` | provided | 同上 |
| `kotlinx-coroutines-core-jvm`（`1.10.2-intellij-1`） | provided | 用 IntelliJ 内部裁剪版，避免与 IDE 自带协程冲突 |

> 教训：`kotlin.version` 与平台不一致时，可能出现"编译通过但运行时 NoSuchMethodError"，
> 所以这里跟平台对齐而不是随便取最新版。

### 4.2 为什么 `since-build` 是 252

- 233 / 251 的 `ide-codeinsight-inline` 在公共仓库与本地仓库都拿不到可用产物
- 252（2025.2）起 API 稳定：`DebouncedInlineCompletionProvider` + `InlineCompletionSingleSuggestion.build`
- 若必须兼容更老版本，只能发"LTS 包"：删掉扩展点、Kotlin Provider 与该依赖（见 `COMPATIBILITY.md`）

---

## 5. 调试手册

### 5.1 跑单测

```bash
cd core
python -m unittest discover -s tests          # 98 个用例
python -m unittest tests.test_completion -v   # 只看 FIM 相关
python -m unittest tests.test_codeindex -v    # 只看索引相关
```

`tests/test_completion.py` 覆盖：URL 推导、围栏清理、后缀重复截断、前缀回显、空输入。
`tests/test_codeindex.py` 用 `FakeEmbedder`（固定向量）覆盖：切块、store 读写、余弦排序、增量与换模型重建。
**测试不依赖真实模型**，这是能进 CI 的关键。

### 5.2 手工驱动 Core（最直观的学习方式）

Core 只说 JSON Lines，可以直接用管道喂：

```bash
# 1) 启动（stderr 打日志，stdout 只走协议）
python -m agent_core --project-id demo --data-dir /tmp/agent-demo

# 2) 这是一行请求：查索引状态
{"id":"1","method":"index/status"}
# ← {"id":"1","ok":true,"result":{"chunks":0,"files":0,...,"numpy":true}}

# 3) 改设置（通知，无 id）：指向本地 Ollama + 指定 embedding 模型
{"method":"settings/update","params":{"provider":"ollama","base_url":"http://localhost:11434/api/chat","model":"qwen2.5-coder:7b","embedding_model":"nomic-embed-text"}}

# 4) 建索引（后台跑，过程中会收到 index/progress 通知）
{"id":"2","method":"index/build","params":{"full":false}}

# 5) 语义检索
{"id":"3","method":"semantic_search"}      # 注意：工具是由模型发起的，手工测试请用 agent 对话或直接调 service

# 6) 行内补全
{"id":"4","method":"inline/complete","params":{"prefix":"def add(a, b):\n    return","suffix":"","language":"python"}}
# ← {"id":"4","ok":true,"result":{"text":" a + b"}}
```

> 提示：`chat/start` 需要先配好模型；工具调用链可以用 `core/scripts/smoke_*.py` 里的假 LLM 服务器观察。

### 5.3 观察点

| 想知道 | 看哪 |
|---|---|
| 补全为什么不出现 | Core stderr 里 `inline completion failed` / `completions 404` 的 debug 日志（需要 `--log-level DEBUG`） |
| 索引建了多少 | `index/status` 的 `chunks/files/dim/model/building` |
| 是否走 numpy | `index/status` 的 `numpy` 字段；false 说明运行时缺 numpy，走纯 Python 回退 |
| 检索为什么不命中 | 先看 `dim` 是否与当前 embedding 模型一致；再看文件是否在白名单/被忽略目录 |

### 5.4 常见错误

| 现象 | 原因 | 处理 |
|---|---|---|
| `未配置 Embedding 模型` | 设置页没填或 settings 未下发 | 填 `nomic-embed-text` 后重开设置页 |
| `Ollama embedding 失败(404)` | 模型没拉取 | `ollama pull nomic-embed-text` |
| `未绑定项目根目录` | Core 还没收到带 `project_root` 的 `contextSnapshot` | 先在面板发一条消息 |
| Kotlin 编译报 `Unresolved reference: InlineCompletionFontUtils` | 依赖未拉到或版本不对 | 只用 `ide-codeinsight-inline` 252+，不要写 `INSTANCE` |

---

## 6. 代码地图

| 关注点 | 文件 |
|---|---|
| 扩展点注册、版本门槛 | [plugin.xml](../src/main/resources/META-INF/plugin.xml) |
| 行内补全入口（Kotlin） | [AgentInlineCompletionProvider.kt](../src/main/kotlin/com/workreport/agent/inline/AgentInlineCompletionProvider.kt) |
| 行内补全客户端（Java） | [InlineCompletionClient.java](../src/main/java/com/workreport/agent/inline/InlineCompletionClient.java) |
| 共享 Core 会话 | [AgentCoreService.java](../src/main/java/com/workreport/agent/rpc/AgentCoreService.java) |
| FIM 请求与 sanitize | [completion.py](../core/src/agent_core/llm/completion.py) |
| RPC 分发（后台 task） | [server.py](../core/src/agent_core/rpc/server.py) |
| 切块 | [chunker.py](../core/src/agent_core/codeindex/chunker.py) |
| Embedding | [embeddings.py](../core/src/agent_core/codeindex/embeddings.py) |
| 向量存储与检索 | [store.py](../core/src/agent_core/codeindex/store.py) |
| 增量构建 + 工具 | [service.py](../core/src/agent_core/codeindex/service.py) |
| 配置下发字段 | [protocol.py](../core/src/agent_core/protocol.py)（`ChatConfig.embedding_model`） |
| Maven Kotlin 配置 | [pom.xml](../pom.xml) |
| 单测 | [test_completion.py](../core/tests/test_completion.py)、[test_codeindex.py](../core/tests/test_codeindex.py) |

---

## 7. 学习路径建议

1. **先跑通**：`python -m unittest discover -s tests`，看 98 个用例都在测什么
2. **读协议**：`core/src/agent_core/rpc/server.py` 的 `_dispatch` → 理解请求/响应/通知三分法
3. **跟一条补全**：从 `AgentInlineCompletionProvider.kt` 开始，一路读到 `completion.py`，重点看 `sanitize()`
4. **跟一次建库**：`CodeIndexService.build()` → `chunker.py` → `embeddings.py` → `store.py`
5. **动手改**：给 `INDEXED_EXTENSIONS` 加一个扩展名、或把 `WINDOW_LINES` 改小，再跑测试看影响
6. **再抽象**：对照 §3.2 的表格，思考什么规模下你会换 HNSW，以及需要付出什么工程代价

> 一句话总结：**行内补全的难点不在模型，而在"任何情况下都不能打扰用户"；
> 语义索引的难点不在向量库，而在"切块方式决定检索上限"。**
