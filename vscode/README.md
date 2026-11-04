# Agent Coder for VS Code

VS Code Shell for the shared **Agent Core**. It speaks exactly the same JSON
Lines RPC protocol as the IntelliJ plugin, so editing, tools, memory, skills,
MCP servers and subagents all run in the Core while this extension only performs
IDE-side actions (file IO, git CLI, diffs, terminals).

## Requirements

- Python 3.9+ (or a packaged `agent-core` executable, see below)
- The repository `core/` directory, or `agentCoder.coreHome` pointing at it
- An LLM endpoint: Ollama (`agentCoder.provider = ollama`) or any OpenAI-compatible API

## Run in development

```bash
cd vscode
npm install
npm run compile
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
| `agentCoder.apiKey` | empty | Cloud API key (prefer `OPENAI_API_KEY`) |
| `agentCoder.maxIterations` | 15 | Tool loop budget per task |
| `agentCoder.toolConcurrency` | 4 | Parallel tool calls |
| `agentCoder.subagentConcurrency` | 3 | Parallel subagents |
| `agentCoder.autoAcceptEdits` | false | Skip the diff confirmation |
| `agentCoder.mcpServers` | `[]` | MCP servers (array or name map) |

Project-level `.agent/mcp.json` wins over `agentCoder.mcpServers`, matching the
IntelliJ shell.

## Commands

- `Agent: 打开对话面板` — focus the chat view
- `Agent: 生成日报` — run the built-in `daily-report` skill
- `Agent: 重启 Agent Core` — restart the Core process

## Packaged Core

Build once with `powershell -File scripts/build_core.ps1`, then either:

- set `AGENT_CORE_EXE` to the executable, or
- place it at `core/dist/agent-core(.exe)` — both shells detect it automatically.

No Python installation is needed in that case.
