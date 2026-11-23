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

// The key lives outside the settings (SecretStorage / env, see secrets.ts), so it
// is passed in rather than read here.
// Key 不在设置里读（见 secrets.ts），由调用方传入。
export function collectSettings(apiKey: string): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("agentCoder");
  const configured = config.get<string>("provider", "ollama");
  const baseUrl = config.get<string>("baseUrl", "http://localhost:11434");
  const settings: Record<string, unknown> = {
    provider: detectProvider(configured, baseUrl),
    base_url: baseUrl,
    model: config.get<string>("model", "qwen2.5:7b"),
    // Same field set as the IntelliJ pushSettings payload (§4.5).
    // 与 IntelliJ pushSettings 下发同一套字段（§4.5）。
    embedding_model: config.get<string>("embeddingModel", "") ?? "",
    max_iterations: config.get<number>("maxIterations", 40),
    context_window: config.get<number>("contextWindow", 32000),
    temperature: config.get<number>("temperature", 0.2),
    max_tokens: config.get<number>("maxTokens", 4096),
    tool_concurrency: config.get<number>("toolConcurrency", 4),
    subagent_concurrency: config.get<number>("subagentConcurrency", 3),
    // Team switch: empty string means "no team", the 3.3.0 default.
    // 团队开关：空串表示不启用，即 3.3.0 的默认值。
    active_team: config.get<string>("activeTeam", "") ?? "",
  };

  if (apiKey) {
    settings.api_key = apiKey;
  }

  const mcpServers = config.get<unknown[]>("mcpServers", []);
  if (Array.isArray(mcpServers) && mcpServers.length > 0) {
    settings.mcp_servers = mcpServers;
  }

  // Non-empty overrides the Core's built-in list; empty keeps the defaults.
  // 非空列表覆盖 Core 内置白名单，空列表继续用默认值。
  const allowlist = config.get<string[]>("commandAllowlist", []);
  if (Array.isArray(allowlist)) {
    settings.command_allowlist = allowlist
      .map((entry) => String(entry).trim())
      .filter((entry) => entry.length > 0);
  }
  return settings;
}

export function autoAcceptEdits(): boolean {
  return vscode.workspace.getConfiguration("agentCoder").get<boolean>("autoAcceptEdits", false);
}
