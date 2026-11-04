// Map VS Code settings to the Core ``settings/update`` payload.
// 将 VS Code 配置映射为 Core 的 settings/update 载荷。

import * as vscode from "vscode";

export function collectSettings(): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("agentCoder");
  const provider = config.get<string>("provider", "ollama");
  const settings: Record<string, unknown> = {
    provider,
    base_url: config.get<string>("baseUrl", "http://localhost:11434"),
    model: config.get<string>("model", "qwen2.5:7b"),
    max_iterations: config.get<number>("maxIterations", 15),
    tool_concurrency: config.get<number>("toolConcurrency", 4),
    subagent_concurrency: config.get<number>("subagentConcurrency", 3),
  };

  const apiKey = config.get<string>("apiKey", "") || process.env.OPENAI_API_KEY || "";
  if (provider !== "ollama" && apiKey) {
    settings.api_key = apiKey;
  }

  const mcpServers = config.get<unknown[]>("mcpServers", []);
  if (Array.isArray(mcpServers) && mcpServers.length > 0) {
    settings.mcp_servers = mcpServers;
  }
  return settings;
}

export function autoAcceptEdits(): boolean {
  return vscode.workspace.getConfiguration("agentCoder").get<boolean>("autoAcceptEdits", false);
}
