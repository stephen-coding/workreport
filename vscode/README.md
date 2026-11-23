# Agent Coder for VS Code

VS Code Shell for the shared **Agent Core**. It speaks exactly the same JSON
Lines RPC protocol as the IntelliJ plugin, so editing, tools, memory, skills,
MCP servers and subagents all run in the Core while this extension only performs
IDE-side actions (file IO, git CLI, diffs, terminals).

Subagents are **read-only roles** owned by the Core: a role may pin its own model,
declare an output contract (`text` / `bullets` / `json`) and a wall-clock timeout,
and the Core injects the role catalog into the main agent's prompt — adding a
`SUBAGENT.md` needs no shell change. Progress lines show role, model and duration,
e.g. `[子代理] bug-hunter-1·qwen2.5-7b 完成（12.4s）`.

Inline (ghost text) completion is on by default: 300 ms after you stop typing the
caret shows the Core's fill-in-the-middle answer; `Tab` accepts, `Esc` drops it.
Nothing is requested for files over 512 KB, binary documents, or when the Core is
not running, so typing never blocks.

## Requirements

- Python 3.9+ (or a packaged `agent-core` executable, see below)
- The repository `core/` directory, or `agentCoder.coreHome` pointing at it
- An LLM endpoint: Ollama (`agentCoder.provider = ollama`) or any OpenAI-compatible API

## Run in development

```bash
cd vscode
npm install
npm run compile
npm run check:settings   # both shells must push the same settings/update fields
# press F5 in VS Code with this folder open, or:
npm run smoke        # boots the real Core and runs one chat round
```

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `agentCoder.pythonPath` | auto | Python interpreter (or `AGENT_PYTHON`) |
| `agentCoder.coreHome` | auto | Repository `core/` directory (or `AGENT_CORE_HOME`) |
| `agentCoder.provider` | `ollama` | `ollama` or `openai_compat` |
| `agentCoder.baseUrl` | `http://localhost:11434` | Ollama or OpenAI-compatible endpoint |
| `agentCoder.model` | `qwen2.5:7b` | Model name |
| `agentCoder.embeddingModel` | empty | Embedding model for the semantic index |
| `agentCoder.apiKey` | empty | **Migrated to SecretStorage on first start**; the encrypted value wins, then `OPENAI_API_KEY` |
| `agentCoder.maxIterations` | 40 | Tool loop budget per task (5~300) |
| `agentCoder.contextWindow` | 32000 | Model context window in tokens (4000~200000); history budget is 75% of it |
| `agentCoder.temperature` | 0.2 | Sampling temperature |
| `agentCoder.maxTokens` | 4096 | Max output per reply |
| `agentCoder.toolConcurrency` | 4 | Parallel tool calls |
| `agentCoder.subagentConcurrency` | 3 | Parallel read-only subagents (role-based dispatch) |
| `agentCoder.autoAcceptEdits` | false | Skip the diff confirmation |
| `agentCoder.commandAllowlist` | `[]` | Command prefixes that run without confirmation; empty keeps the Core's built-in list (dangerous commands are always refused) |
| `agentCoder.inlineCompletion.enabled` | true | Ghost-text completion after a 300 ms pause |
| `agentCoder.mcpServers` | `[]` | MCP servers (array or name map) |
| `agentCoder.activeTeam` | empty | Enabled Agent team; empty keeps teams off (mirrors IntelliJ's `settings/update.active_team`) |

Project-level `.agent/mcp.json` wins over `agentCoder.mcpServers`, matching the
IntelliJ shell.

## Commands

- `Agent: 打开对话面板` — focus the chat view
- `Agent: 生成日报` — run the built-in `daily-report` skill
- `Agent: 重启 Agent Core` — restart the Core process

## Chat panel

- `历史` lists stored conversations (time, first question, message count); click a
  row to replay it and keep chatting, `×` deletes the file after a confirmation
  that also reports the disk space freed.
- `新会话` starts a conversation whose id carries the current timestamp, so the
  previous one stays in history.
- A multi-step task shows the Core-owned checklist above the transcript
  (`☐ / ◐ / ☑`); it folds away once everything is finished and is replayed when
  you reopen the conversation.
- `团队` selects the Agent team (same roster as IntelliJ's `team/list`); while a
  turn is running the selector is disabled so it cannot race the Core's snapshot.
  Switching writes `agentCoder.activeTeam` and pushes the full settings payload.
- `交付物` opens the team blackboard (`team/artifacts`); click a row to expand the
  body inline. A new artifact also prints one `[交付物] [impl] …` line in the
  transcript.
- The team pre-review card arrives as `agent/confirm` with `kind="preapprove"`;
  it shows the plan, the touched files and the first diff, and `允许` turns this
  phase's routine writes automatic (deletes and dangerous commands still stop).
  The webview declares `team` in `ide/hello`, so an older shell simply keeps
  per-write confirmations.

## Capability matrix (after 2.7.0)

| Capability | IntelliJ | VS Code |
|---|---|---|
| Streaming chat / tools / memory / skills / MCP / subagents | yes | yes |
| Inline completion | yes | **yes** |
| Semantic index settings | yes | **yes** |
| Multiple chat tabs | yes (up to 8) | single conversation (accepted difference, see FAQ) |
| History of conversations | yes | **yes** |
| Secret storage | PasswordSafe | **SecretStorage** |
| Agent teams (picker / pre-review / blackboard) | yes | **yes** (3.3.0) |
| Reasoning / thinking stream (`chat/reasoning`) | yes (folded row above the answer) | **no** — the notification is additive and silently ignored; `chat/token` still carries the answer |

## Packaged Core

Build once with `powershell -File scripts/build_core.ps1`, then either:

- set `AGENT_CORE_EXE` to the executable, or
- place it at `core/dist/agent-core(.exe)` — both shells detect it automatically.

No Python installation is needed in that case.
