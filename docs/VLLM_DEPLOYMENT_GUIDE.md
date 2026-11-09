# vLLM 详解与 27B 模型部署指南

> 适用对象：需要在内网/本机部署 Qwen 系 27B 量级模型，并让 Agent Coding Assistant 具备**工具调用**能力的开发者
> 对应插件版本：**2.4.8**
> 相关文档：[USER_GUIDE §11.4](../USER_GUIDE.md)（报错排查）、[README §1](../README.md)（插件侧配置）

---

## 0. 一句话结论

**vLLM 是"把模型权重变成 HTTP 服务"的推理引擎**。它跑在有算力的 Linux 服务器上，对外暴露 OpenAI 兼容接口；插件只负责发请求，模型能不能"动手改代码"，取决于 vLLM 启动时有没有打开工具调用开关。

```
你的 IDEA  ──HTTP──▶  New API 网关  ──HTTP──▶  vLLM  ──▶  GPU / NPU 上的模型
（插件）               （鉴权/计费）            （推理引擎）
```

---

## 1. vLLM 到底是什么

### 1.1 它解决的三件事

| 问题 | 没有 vLLM 时 | vLLM 的做法 |
|---|---|---|
| **显存怎么管** | 每个请求预分配一大块连续显存，浪费严重 | **PagedAttention**：把 KV Cache 切成固定大小"页"，按需分配，碎片率接近 0 |
| **并发怎么提** | 一次只能算一个请求，GPU 大量空转 | **Continuous Batching**：请求一进来就插进当前批次，不等整个 batch 结束 |
| **接口怎么统一** | 各家模型各有各的调用方式 | 内置 **OpenAI 兼容 HTTP Server**（`/v1/chat/completions`、`/v1/completions`、`/v1/embeddings`） |

第三点是本插件能跟它对上的原因：**插件说的是 OpenAI 协议**，vLLM 说的也是。

### 1.2 与同类工具的定位

| 工具 | 定位 | 平台 | 适合谁 |
|---|---|---|---|
| **Ollama** | 个人本机一键跑模型 | Win/Mac/Linux | 自己笔记本上试效果 |
| **vLLM** | 生产级推理服务 | 主要 Linux + NVIDIA/AMD/昇腾 | 多人共用、要吞吐、要 OpenAI 协议 |
| **llama.cpp** | 极致轻量、CPU/量化 | 全平台 | 无独显、嵌入式 |
| **MindIE** | 华为昇腾官方推理引擎 | 昇腾 + Linux | 纯昇腾环境、不折腾 vLLM |
| **TGI** | HuggingFace 的推理服务 | Linux + GPU | HF 生态用户 |

一句话：**Ollama 是"能跑"，vLLM 是"能扛"**。

### 1.3 内部结构（请求经过什么）

```
POST /v1/chat/completions
        │
        ▼
┌─────────────────────────────────────┐
│ API Server（FastAPI）               │  ← 鉴权、参数校验、聊天模板、工具解析
│   · chat template 渲染              │     工具调用开关就在这里生效
│   · tool call parser（解析模型输出） │
└──────────────┬──────────────────────┘
               ▼
┌─────────────────────────────────────┐
│ Scheduler（调度器）                  │  ← Continuous Batching、抢占、KV 页分配
└──────────────┬──────────────────────┘
               ▼
┌─────────────────────────────────────┐
│ Worker / Executor                    │  ← 张量并行、模型前向、采样
└──────────────┬──────────────────────┘
               ▼
        GPU / NPU 显存（模型权重 + KV Cache）
```

理解这张图能解释两件事：

1. **工具调用是 API Server 层的事**——和底层是 CUDA 还是 CANN **无关**，纯 CPU 后端也能开
2. **显存不够时报错发生在 Scheduler/Worker 层**（KV 页分不出来），表现为 `No available memory for the cache blocks`

### 1.4 四个核心概念

**PagedAttention**
把 KV Cache 当成操作系统内存页来管理：不要求连续，按页分配。论文数据是显存浪费从 60%~80% 降到 4% 以内，因此同样显存能塞进更多并发。

**Continuous Batching（连续批处理）**
传统批处理要等一整批算完才接新请求；vLLM 以"迭代（token 步）"为调度粒度，某个请求生成完立刻退出、新请求立刻补位。吞吐提升通常 5–20 倍。

**Prefix Caching**
系统提示词、工具定义（几十个工具的 JSON schema 往往上千 token）每次请求都一样。开启后这部分 KV 直接复用，首 token 延迟显著下降。**对 Agent 场景收益极大**，建议默认打开。

**Tensor Parallel（张量并行，TP）**
单卡放不下就把每层权重横切到多张卡上，每步做一次 all-reduce。`--tensor-parallel-size N` 就是 N 卡协同提供**一个**服务实例（不是 N 个副本）。

### 1.5 三个常见误解

| 误解 | 事实 |
|---|---|
| "vLLM 是一个模型" | 它是**引擎**，模型（权重）是它的输入。同一个 vLLM 可以服务任意 HF 格式模型 |
| "只有英伟达能跑" | 官方主支持 NVIDIA/AMD，昇腾有官方推荐的 `vllm-ascend` 插件；TPU/Gaudi/XPU 也有 out-of-tree 支持（见 §3） |
| "vLLM 自带工具调用" | 它自带**解析能力**，但必须用 `--enable-auto-tool-choice --tool-call-parser` 显式打开，默认关闭 |

---

## 2. 在你这条链路里处于什么位置

以本项目实际配置为例（`workReportSettings.xml`）：

```
端点 http://172.29.1.22:3000/v1      ← 这是 New API 网关，端口 3000 是它的默认端口
模型 qwen-3.8-27b                    ← 网关里的模型名，需与 vLLM 的 --served-model-name 一致
类型 openai_compat
```

```
IDEA 插件
   │  ① 发 OpenAI 协议请求，带 Authorization: Bearer <Key>
   ▼
New API 网关 :3000
   │  ② 校验 Key → 报 new_api_error / Invalid token
   │  ③ 按"渠道"配置转发到后端
   ▼
vLLM :8000（通常只监听 127.0.0.1）
   │  ④ 校验 tools/tool_choice → 报 "auto" tool choice requires ...
   │  ⑤ 渲染 chat template → 调模型 → 解析 tool call
   ▼
GPU / NPU
```

### 报错归属对照表

| 报错特征 | 归属层 | 该找谁 |
|---|---|---|
| `Invalid token (new_api_error)` | New API 网关 | 网关后台的「令牌」页面 |
| `"auto" tool choice requires --enable-auto-tool-choice` | **vLLM** | 部署 vLLM 的人（加启动参数） |
| `Invalid URL (POST .../api/chat)` | 插件端点填写 | 插件 2.2.1+ 会自动纠正 |
| `does not support tools` | 模型/chat 模板 | 换模型或换 parser |
| `CUDA out of memory` / `No available memory for the cache blocks` | vLLM 显存 | 降 `--max-model-len`、加 TP、量化 |
| 连接超时 / 无响应 | 网络 | 确认在同一个内网 / VPN |

---

## 3. 硬件与平台支持

| 平台 | 支持方式 | 生产可用性 | 备注 |
|---|---|---|---|
| **NVIDIA GPU** | 官方 CUDA 轮子 | ★★★★★ | 功能最全、更新最快 |
| **AMD GPU** | 官方 ROCm 6.3+ | ★★★★ | MI 系列 |
| **华为昇腾 NPU** | `vllm-ascend` 插件（社区维护，vLLM 官方推荐路径） | ★★★ | 版本锁得极死，见 §4.2 |
| Google TPU / Intel Gaudi / Intel XPU / IBM Spyre / Apple Silicon | out-of-tree 插件 | ★★ | 功能按需实现 |
| **Windows 原生** | ❌ 不支持 | — | 必须 WSL2 或 Docker（且需 NVIDIA Container Toolkit） |

> **官方文档**：<https://docs.vllm.ai/en/latest/>

---

## 4. 安装

### 4.1 NVIDIA 路径（推荐）

前置：Linux、NVIDIA 驱动 ≥ 对应 CUDA 版本、Python 3.9–3.12。

```bash
# 建议用独立环境，避免污染系统 Python
python3 -m venv /opt/vllm-venv
source /opt/vllm-venv/bin/activate

pip install -U pip
pip install vllm

# 验证
python -c "import vllm; print(vllm.__version__)"
```

> 国内网络慢时加镜像：`pip install vllm -i https://pypi.tuna.tsinghua.edu.cn/simple`
> 驱动/CUDA 不匹配是最常见的装不上原因，报 `undefined symbol` 基本都是它。

### 4.2 昇腾路径（vllm-ascend）

**版本必须严格对齐**，这是昇腾上最大的坑：

```
Python 3.12
CANN 9.2.1
PyTorch 2.10.0
torch_npu 2.10.0.post7
vllm 与 vllm-ascend 版本号完全一致（如 0.30.0 ↔ 0.30.0）
```

```bash
source /usr/local/Ascend/ascend-toolkit/set_env.sh   # 加载 CANN 环境

pip install torch==2.10.0 torch_npu==2.10.0.post7
pip install vllm==0.30.0
pip install vllm-ascend==0.30.0

# 验证 NPU 可见
npu-smi info
python -c "import torch, torch_npu; print(torch.npu.is_available())"
```

> 昇腾支持仓库：<https://github.com/vllm-project/vllm-ascend>
> 如果团队已有 MindIE 在用，除非需要 vLLM 的 OpenAI 协议与工具调用，否则不必为了 vLLM 重搭环境。

### 4.3 Docker 路径

```bash
# 需要 nvidia-container-toolkit
docker run --rm --gpus all --ipc=host \
  -p 8000:8000 \
  -v /models:/models \
  vllm/vllm-openai:latest \
  --model /models/qwen-3.8-27b \
  --served-model-name qwen-3.8-27b \
  --enable-auto-tool-choice --tool-call-parser hermes
```

注意：`vllm/vllm-openai` 镜像的 ENTRYPOINT 已经是 `vllm serve`，**不要再写 `serve`**。

---

## 5. 27B 模型的启动命令（核心章节）

### 5.1 先算显存，再选命令

27B 参数量的粗略显存需求（不含 KV Cache）：

| 精度 | 权重 | 加上 KV/激活后最小可用 | 典型卡配置 |
|---|---|---|---|
| BF16 / FP16 | ≈ 54 GB | ≈ 64 GB | 1×80GB，或 2×48GB |
| FP8（Hopper/Ada 在线量化） | ≈ 27 GB | ≈ 36 GB | 1×48GB，或 1×40GB |
| INT8 / W8A8 | ≈ 27 GB | ≈ 36 GB | 同上 |
| INT4（AWQ / GPTQ） | ≈ 14 GB | ≈ 22 GB | 1×24GB（4090，需降上下文） |

KV Cache 预算经验值：`--max-model-len 32768` + 4 并发，Qwen 系 GQA 结构约需 8–12 GB；上下文越长、并发越高，占用线性上涨。

> 显存不够时 vLLM 会在启动阶段报 `No available memory for the cache blocks`，按 §7 处理。
> 昇腾上先跑 `npu-smi info` 看**单卡实际可用显存**，再对表。

### 5.2 单卡 80GB：最小可用命令

```bash
vllm serve /models/qwen-3.8-27b \
  --served-model-name qwen-3.8-27b \
  --host 0.0.0.0 \
  --port 8000
```

这样就能聊了，但**不能用工具**——插件的 Agent 能力全部失效。生产环境请用下面的完整命令。

### 5.3 生产推荐：单卡 80GB + 工具调用

```bash
vllm serve /models/qwen-3.8-27b \
  --served-model-name qwen-3.8-27b \
  --host 0.0.0.0 \
  --port 8000 \
  --dtype bfloat16 \
  --gpu-memory-utilization 0.90 \
  --max-model-len 32768 \
  --max-num-seqs 16 \
  --enable-prefix-caching \
  --enable-auto-tool-choice \
  --tool-call-parser hermes
```

**这一条就是让 `[提示] 端点未启用函数调用` 消失的命令。** 两个 `--tool-*` 参数必须同时给。

### 5.4 多卡张量并行（单卡放不下）

```bash
# 2 卡：每卡约 27 GB 权重，适合 2×48GB / 2×40GB
vllm serve /models/qwen-3.8-27b \
  --served-model-name qwen-3.8-27b \
  --host 0.0.0.0 --port 8000 \
  --tensor-parallel-size 2 \
  --gpu-memory-utilization 0.90 \
  --max-model-len 32768 \
  --enable-prefix-caching \
  --enable-auto-tool-choice \
  --tool-call-parser hermes
```

多机多卡再加 `--pipeline-parallel-size`，或直接用 Ray 后端。TP 要求卡数能整除注意力头数（通常 2/4/8 都行）。

### 5.5 量化版（省显存）

```bash
# 权重本身是 AWQ/GPTQ 量化格式时
vllm serve /models/qwen-3.8-27b-awq \
  --served-model-name qwen-3.8-27b \
  --quantization awq \
  --host 0.0.0.0 --port 8000 \
  --max-model-len 16384 \
  --enable-auto-tool-choice --tool-call-parser hermes
```

```bash
# BF16 权重在线转 FP8（需要 Hopper/Ada 架构，即 H100/H200/L40S/4090）
vllm serve /models/qwen-3.8-27b \
  --quantization fp8 \
  ...
```

量化会带来一点精度损失，Agent 场景建议先验证工具调用是否还稳定（用小任务试）。

### 5.6 昇腾版

```bash
source /usr/local/Ascend/ascend-toolkit/set_env.sh

vllm serve /models/qwen-3.8-27b \
  --served-model-name qwen-3.8-27b \
  --host 0.0.0.0 --port 8000 \
  --tensor-parallel-size 2 \
  --max-model-len 32768 \
  --enable-auto-tool-choice \
  --tool-call-parser hermes
```

昇腾注意事项：

- parser 以 `hermes` 最稳；`glm45` / `glm47` 这类新 parser 依赖较新版本，昇腾侧可能还没有
- `--quantization` 支持范围比 CUDA 窄，不确定就先跑 BF16
- 版本对不上时典型报错是 `import vllm_ascend` 失败或 `EngineCore failed to start`

### 5.7 docker compose

```yaml
services:
  vllm:
    image: vllm/vllm-openai:latest
    runtime: nvidia
    ipc: host
    ports:
      - "8000:8000"
    volumes:
      - /models:/models
    command:
      - --model
      - /models/qwen-3.8-27b
      - --served-model-name
      - qwen-3.8-27b
      - --max-model-len
      - "32768"
      - --enable-prefix-caching
      - --enable-auto-tool-choice
      - --tool-call-parser
      - hermes
    restart: unless-stopped
```

### 5.8 systemd 常驻（生产推荐）

`/etc/systemd/system/vllm-qwen27b.service`：

```ini
[Unit]
Description=vLLM Qwen 27B (OpenAI compatible)
After=network-online.target

[Service]
Type=simple
User=vllm
Environment="PATH=/opt/vllm-venv/bin:/usr/local/bin:/usr/bin:/bin"
ExecStart=/opt/vllm-venv/bin/vllm serve /models/qwen-3.8-27b \
  --served-model-name qwen-3.8-27b \
  --host 0.0.0.0 --port 8000 \
  --gpu-memory-utilization 0.90 \
  --max-model-len 32768 \
  --enable-prefix-caching \
  --enable-auto-tool-choice \
  --tool-call-parser hermes
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now vllm-qwen27b
sudo systemctl status vllm-qwen27b
journalctl -u vllm-qwen27b -f
```

临时验证（不落服务）：`nohup vllm serve ... > /var/log/vllm.log 2>&1 &` 或 `tmux` 里跑。

### 5.9 参数逐个说明

| 参数 | 作用 | 建议值 |
|---|---|---|
| `--model` | 模型路径或 HF 名 | 本地目录更稳定 |
| `--served-model-name` | 对外暴露的模型名，**必须与插件/网关里的模型名一致** | `qwen-3.8-27b` |
| `--host` / `--port` | 监听地址/端口 | 经网关转发用 `127.0.0.1`；直连用 `0.0.0.0` |
| `--tensor-parallel-size` | 张量并行卡数 | 单卡放不下时用 |
| `--gpu-memory-utilization` | 显存占用上限比例 | 独占卡 `0.9`；共享卡 `0.7~0.8` |
| `--max-model-len` | 单请求最大上下文 | 按需，Agent 场景 ≥ 32K 更舒服 |
| `--max-num-seqs` | 最大并发序列数 | 默认足够，人多再调 |
| `--enable-prefix-caching` | 复用系统提示词/工具定义的 KV | **开启**，Agent 场景收益明显 |
| `--enable-auto-tool-choice` | 允许模型自主选工具 | **必须开**，否则插件 400 |
| `--tool-call-parser` | 解析模型输出的工具调用 | Qwen2.5/Qwen3 → `hermes`；Qwen3-Coder → `qwen3_coder` |
| `--reasoning-parser` | 把"思考过程"与"正式回答"分开 | Qwen3 → `qwen3`（可选） |
| `--chat-template` | 自定义聊天模板 | 一般不用，模型自带 |
| `--quantization` | 量化方式 | `fp8` / `awq` / `gptq` |
| `--api-key` | 服务端自带鉴权 | 经 New API 时不需要 |
| `--trust-remote-code` | 允许加载模型自定义代码 | 私有模型可能需要 |

> **关于 `--reasoning-parser qwen3`**：Qwen3 默认开思考模式。开启该参数后，"思考"内容走 `reasoning_content` 字段，正式回答走 `content`。
> 本插件只渲染 `content`，因此你会看到"先静默几秒，然后出答案"——这是正常现象，不是卡死。

### 5.10 没有服务器权限怎么办

**路线 A：转给部署方（最快）**

> 麻烦在 vLLM 启动命令里加两个参数并重启：
> `--enable-auto-tool-choice --tool-call-parser hermes`
> 模型 qwen-3.8-27b 属 Qwen 系，parser 用 hermes；两个参数必须一起加。
> 影响面：仅影响 OpenAI 接口层对 tool call 的解析，不影响现有调用方。

**路线 B：另起实例，不动线上**

在另一个端口（如 8001）用带参数的命令起第二个实例，然后在 New API 后台加一个新渠道指向它，
插件里切到该渠道即可，其他人不受影响。

**路线 C：本机验证插件功能**

装 Ollama + `ollama pull qwen2.5:7b`，端点填 `http://localhost:11434`，开箱支持工具调用，
五分钟就能看到 Agent 真正读写文件的效果，与线上环境完全隔离。

---

## 6. 三步验证部署

### ① 服务存活

```bash
curl http://127.0.0.1:8000/v1/models
```

应返回 `{"object":"list","data":[{"id":"qwen-3.8-27b",...}]}`。

### ② 工具调用开关是否真开了（关键）

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen-3.8-27b",
    "messages": [{"role":"user","content":"hi"}],
    "tools": [{"type":"function","function":{
      "name":"noop","description":"noop",
      "parameters":{"type":"object","properties":{}}}}],
    "tool_choice": "auto"
  }'
```

- **200** → 开关已生效
- **400 + `"auto" tool choice requires ...`** → 参数没生效（改错文件 / 服务没真重启 / 改的是另一个实例）

### ③ 插件联调

1. 设置页端点填 `http://<host>:8000/v1`（绕过网关时）或网关地址 `http://172.29.1.22:3000/v1`
2. 模型名填 `--served-model-name` 的值（经网关时填网关渠道里的模型名）
3. 类型选 **OpenAI 兼容**（2.2.2 起填错也会按端点自动纠正）
4. **重启 IDE**——降级状态有 5 分钟假释（v2.3.1 起），重启可立即恢复
5. 发「列出当前目录的文件」，看到 `[工具] list_dir 执行中...` 即全链路打通

---

## 7. 常见报错对照表

| 报错 | 根因 | 处理 |
|---|---|---|
| `"auto" tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set` | 没开工具调用 | 启动命令补两个参数并重启（§5.3） |
| 回答里出现裸的 `<tool_call>` / `<function=...>` 文本，文件没被改 | vLLM 没解析出 `tool_calls`：请求没带工具定义（降级期内），或 `--tool-call-parser` 与模型不匹配 | **v2.4.2 起正文里的标记会被解析成真正的工具调用并执行，同时从气泡里屏蔽**，不再只是显示一段 XML；但结构化 `tool_calls` 仍是首选。仍建议补 `--enable-auto-tool-choice --tool-call-parser`：Qwen2.5/Qwen3 用 `hermes`、Qwen3-Coder 用 `qwen3_coder`，重启 vLLM 后重启 IDE |
| `No user query found in messages.`（400，跑长任务时出现） | 请求里一条 `user` 消息都没有：vLLM 要求至少一条 | 插件 v2.4.3 起裁剪不再丢提问；旧版本遇到时报错后再发一条消息即可绕过（新提问是最后一条，不会被裁） |
| `... does not support tools` | 模型或 chat 模板不支持工具 | 换 `hermes` parser，或换支持工具调用的模型 |
| `ValueError: Unknown tool parser` | parser 名在当前版本不存在 | 升级 vLLM，或退回 `hermes` |
| `The model's max seq len (...) is larger than max_model_len` | `--max-model-len` 设小了 | 调大或让模型自动取 |
| `maximum context length is N tokens` | 请求超长 | 调大 `--max-model-len`，或让 Agent 少读大文件 |
| `CUDA out of memory` | 显存不足 | 降 `--gpu-memory-utilization`、加 TP、量化、降上下文 |
| `No available memory for the cache blocks` | KV Cache 分不到空间 | 降 `--max-model-len` 或 `--max-num-seqs` |
| `EngineCore failed to start` | 驱动/CUDA/版本不匹配 | 对齐驱动与 vLLM 版本；昇腾对 CANN/torch_npu |
| `Invalid token (new_api_error)` | New API 网关 Key 无效 | 网关后台「令牌」检查状态/额度/分组 |
| `Invalid URL (POST /v1/chat/completions/api/chat)` | 端点形状与类型冲突 | 插件 2.2.1+ 自动纠正；或把端点写成 `.../v1` |
| 请求超时、无响应 | 不在同一内网 | 确认 VPN / 网络；`ping`、`Test-NetConnection` 验证 |

---

## 8. 性能调优速查

| 目标 | 手段 |
|---|---|
| 首 token 更快 | 开 `--enable-prefix-caching`；缩短系统提示词与工具定义 |
| 吞吐更高 | 提高 `--max-num-seqs`；开连续批处理（默认）；用 FP8 |
| 单请求更快 | 减小 `--max-model-len`；避免超长上下文 |
| 显存更省 | 量化（INT8/INT4）；降 `--gpu-memory-utilization`；减上下文 |
| 多卡扩展 | `--tensor-parallel-size`；跨机用 `--pipeline-parallel-size` |
| 稳定性 | systemd 托管 + `Restart=always`；日志落盘便于回溯 |

---

## 9. 插件侧怎么填（对照表）

| 插件设置项 | 值 | 说明 |
|---|---|---|
| 端点 | `http://<host>:8000/v1` | 直连 vLLM；若走网关则填网关地址 |
| 模型名 | 与 `--served-model-name` 完全一致 | 网关场景填渠道里的模型名 |
| 类型 | `openai_compat` | 2.2.2 起按端点自动纠正，填错也能用 |
| API Key | 直连时留空（除非 vLLM 设了 `--api-key`）；经网关时必填 | Key 存在系统密钥链，按模型名分开存 |

**本项目 2.2.2 / 2.4.2 的三层保护**：

1. **协议自动判定**：按端点形状（`/api/...` vs `/v1/...`）选 Ollama 或 OpenAI 协议，不会再拼出 `.../chat/completions/api/chat`
2. **工具调用降级**：端点拒绝 `tools` 时，去掉工具重试一次，面板打一行 `[提示]`，对话继续
3. **文本工具调用兜底**（v2.4.2 起）：降级期不是纯聊天——模型把 `<tool_call><function=...>` 写进正文时，Core 会把它解析成真正的工具调用并执行，标记同时从流式输出里屏蔽，气泡里不再出现 XML

> 降级状态只保持 5 分钟（v2.3.1 起）：服务端补上参数后，下一次发送会自动重新带上工具；
> 想立即生效就**重启 IDE**（v2.3.2 起改设置不会再重建 Core）。

---

## 10. 命令模板速查

```bash
# 单卡 80GB，含工具调用（最常用）
vllm serve /models/qwen-3.8-27b --served-model-name qwen-3.8-27b \
  --host 0.0.0.0 --port 8000 --gpu-memory-utilization 0.90 \
  --max-model-len 32768 --enable-prefix-caching \
  --enable-auto-tool-choice --tool-call-parser hermes

# 双卡张量并行
vllm serve /models/qwen-3.8-27b --served-model-name qwen-3.8-27b \
  --tensor-parallel-size 2 --gpu-memory-utilization 0.90 \
  --max-model-len 32768 --enable-prefix-caching \
  --enable-auto-tool-choice --tool-call-parser hermes

# Qwen3 + 思考内容分离
vllm serve /models/qwen-3.8-27b --served-model-name qwen-3.8-27b \
  --max-model-len 32768 --enable-prefix-caching \
  --enable-auto-tool-choice --tool-call-parser hermes \
  --reasoning-parser qwen3

# 只聊天，不要工具（排查用）
vllm serve /models/qwen-3.8-27b --served-model-name qwen-3.8-27b --port 8000
```

---

## 附：参考资料

- vLLM 官方文档：<https://docs.vllm.ai/en/latest/>
- 工具调用（Tool Calling）：<https://docs.vllm.ai/en/latest/features/tool_calling/>
- Qwen 官方 vLLM 部署说明：<https://qwen.readthedocs.io/en/latest/deployment/vllm.html>
- 昇腾插件 vllm-ascend：<https://github.com/vllm-project/vllm-ascend>
