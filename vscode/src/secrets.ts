// API key storage: SecretStorage first, environment second.
// 密钥存储：优先 context.secrets（加密），其次环境变量；设置里的明文会被迁移并清空。

import * as vscode from "vscode";

export const API_KEY_SECRET = "agentCoder.apiKey";

export interface ApiKeyState {
  key: string;
  // True when a plaintext value was moved into SecretStorage during this call.
  // 本次调用是否把明文迁进了加密存储。
  migrated: boolean;
}

export async function ensureApiKey(context: vscode.ExtensionContext): Promise<ApiKeyState> {
  const config = vscode.workspace.getConfiguration("agentCoder");
  const stored = ((await context.secrets.get(API_KEY_SECRET)) ?? "").trim();
  const plain = (config.get<string>("apiKey", "") ?? "").trim();

  if (stored) {
    // Secrets win; a leftover plaintext value must not shadow the encrypted one.
    // 以加密存储为准，残留的明文一并清掉。
    if (plain) {
      await clearPlaintextKey(config);
    }
    return { key: stored, migrated: false };
  }
  if (plain) {
    await context.secrets.store(API_KEY_SECRET, plain);
    await clearPlaintextKey(config);
    return { key: plain, migrated: true };
  }
  return { key: (process.env.OPENAI_API_KEY ?? "").trim(), migrated: false };
}

// Remove the setting from whichever layer actually holds it, so the panel stops
// showing the key without leaving an empty override behind.
// 从真正存在该值的配置层移除，设置面板不再显示明文，也不留空覆盖项。
async function clearPlaintextKey(config: vscode.WorkspaceConfiguration): Promise<void> {
  const info = config.inspect<string>("apiKey");
  const target = info?.workspaceFolderValue !== undefined
    ? vscode.ConfigurationTarget.WorkspaceFolder
    : info?.workspaceValue !== undefined
      ? vscode.ConfigurationTarget.Workspace
      : vscode.ConfigurationTarget.Global;
  await config.update("apiKey", undefined, target);
}
