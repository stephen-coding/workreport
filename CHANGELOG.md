# Changelog

Mirror of `plugin.xml` `<change-notes>`: every released version gets one section here, and the
Marketplace entry is copied from it. Core, Shell and VS Code Shell share the numbered release.

## 3.0.0

产品化收口（ROADMAP §7）。

- **记忆沉淀**：对话里出现「以后都…」「不要…」这类偏好，或「不是…而是…」这类纠正时，
  Core 用规则抽取候选，落到 `<项目>/.agent/memories/_pending/`，不进 `memory_overview`。
  面板「记忆」里可确认 / 编辑 / 忽略（`memory/pending`、`memory/promote`、`memory/discard`），
  确认时对已有记忆做相似度提示。
- **会话全文检索**：`sessions.db` 中的 `sessions_text` 镜像 + FTS5（trigram，不支持时回退
  `LIKE`），保存会话时增量索引，首次启动后台全量建索引并上报 `session/indexProgress`。
  `session/search` 返回带 `<mark>` 的片段，历史弹窗顶部搜索框 300 ms 防抖即时搜索。
- **本地用量统计**：`~/.agent/logs/usage.jsonl` 每任务一行，超过 10 MB 轮转为
  `usage-YYYYMM.jsonl`；`usage/report` 按需聚合近 7/30 天，不上报、不做用户标识。
- **性能基线**：`core/scripts/bench_core.py` 记录冷启动、空闲 RSS、单轮延迟、千条会话列表；
  千条列表从 211 ms 降到 105~110 ms（并行读取 + 单连接批量建索引）。
- **i18n**：Shell 按钮、状态行、设置页标签走 `AgentBundle`（`properties` / `_en.properties`），
  IDE 切英文即为英文；Core 提示词与工具描述保持中文。
- Marketplace `change-notes` + 版本号收口（`pom.xml`、`plugin.xml` 同步 3.0.0）。

## 2.7.0

- VS Code：行内补全（`inline.ts`）、设置项补齐与 `settings/update` 一致性断言、
  API Key 迁移到 SecretStorage、历史会话面板。
- 能力矩阵核对表归档到 `vscode/README.md`。

## 2.6.0

- `run_command` 工具：白名单直接执行、其余逐次确认、`rm -rf` / `git push` / `format` 等
  危险命令直接拒绝；两端实现与单测。
- `diagnostics` 工具、`todo_write` 工具 + `chat/todos` 事件 + 会话落盘 + 两端清单 UI。

## 2.5.0

- 上下文预算（`contextWindow`，按 75% 折算历史预算）；用量聚合与 `usageTotal`；
  传输层重试退避；日志落盘与轮转；设置页四项与双端下发对齐。

## 2.4.10

- CI 覆盖 `develop` / `master`；About 文案；`agent_core.__version__` 支持环境变量注入；
  `--version` 与 `core/hello.coreVersion` 一致。
