// Map VS Code settings to the Core ``settings/update`` payload.
// 将 VS Code 配置映射为 Core 的 settings/update 载荷。

import * as vscode from "vscode";

// Endpoint shape wins over the typed provider, mirroring agent_core.llm.detect_provider().
// 端点形状优先于「类型」：OpenAI 形状的网关必须带上 Key，与 Core 的判定保持一致。
function detectProvider(configured: string, baseUrl: string): string {
  const provider = (configured || "").trim().toLowerCase();
  const base = (baseUrl || "").trim().toLowerCase().replace(/\/+$/, "");
  if (!base) {
    return provider || "ollama";
  }
  if (/\/api\/(chat|generate|embed|embeddings|tags)$/.test(base)) {
    return "ollama";
  }
  if (base.includes("/chat/completions") || base.endsWith("/v1")) {
    return "openai_compat";
  }
  return provider === "openai_compat" ? "openai_compat" : "ollama";
}

export function collectSettings(): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("agentCoder");
  const configured = config.get<string>("provider", "ollama");
  const baseUrl = config.get<string>("baseUrl", "http://localhost:11434");
  const settings: Record<string, unknown> = {
    provider: detectProvider(configured, baseUrl),
    base_url: baseUrl,
    model: config.get<string>("model", "qwen2.5:7b"),
    max_iterations: config.get<number>("maxIterations", 15),
    tool_concurrency: config.get<number>("toolConcurrency", 4),
    subagent_concurrency: config.get<number>("subagentConcurrency", 3),
  };

  const apiKey = config.get<string>("apiKey", "") || process.env.OPENAI_API_KEY || "";
  if (apiKey) {
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
