// Locate the Python interpreter and the Agent Core directory.
// 探测 Python 解释器与 Agent Core 目录。

import { existsSync } from "fs";
import { join, resolve } from "path";
import * as vscode from "vscode";

const CORE_MARKER = join("src", "agent_core", "__main__.py");

function firstExisting(candidates: string[]): string | undefined {
  return candidates.find((candidate) => candidate && existsSync(candidate));
}

export function locatePython(): string | undefined {
  const configured = vscode.workspace.getConfiguration("agentCoder").get<string>("pythonPath");
  if (configured && configured.trim()) {
    return configured.trim();
  }
  const fromEnv = process.env.AGENT_PYTHON;
  if (fromEnv && fromEnv.trim()) {
    return fromEnv.trim();
  }
  // On Windows the launcher is usually "python"; elsewhere prefer python3.
  const candidates = process.platform === "win32" ? ["python", "python3"] : ["python3", "python"];
  return candidates[0];
}

export function locateCoreHome(context: vscode.ExtensionContext): string | undefined {
  const configured = vscode.workspace.getConfiguration("agentCoder").get<string>("coreHome");
  const candidates: string[] = [];
  if (configured && configured.trim()) {
    candidates.push(resolve(configured.trim()));
  }
  const fromEnv = process.env.AGENT_CORE_HOME;
  if (fromEnv && fromEnv.trim()) {
    candidates.push(resolve(fromEnv.trim()));
  }
  const extensionRoot = context.extensionUri.fsPath;
  // vscode/ lives next to core/ inside the repository, and one level down when packaged.
  candidates.push(resolve(extensionRoot, "..", "core"));
  candidates.push(resolve(extensionRoot, "..", "..", "core"));
  candidates.push(resolve(extensionRoot, "core"));
  for (const workspace of vscode.workspace.workspaceFolders ?? []) {
    candidates.push(resolve(workspace.uri.fsPath, "core"));
  }
  for (const candidate of candidates) {
    if (existsSync(join(candidate, CORE_MARKER))) {
      return candidate;
    }
  }
  return firstExisting(candidates);
}

// A PyInstaller build of the Core, when shipped with the extension or the repo.
// 打包好的 Core 可执行文件（随扩展或仓库分发时优先使用）。
export function locateCoreExe(context: vscode.ExtensionContext): string | undefined {
  const fromEnv = process.env.AGENT_CORE_EXE;
  if (fromEnv && fromEnv.trim() && existsSync(fromEnv.trim())) {
    return fromEnv.trim();
  }
  const name = process.platform === "win32" ? "agent-core.exe" : "agent-core";
  const extensionRoot = context.extensionUri.fsPath;
  const candidates = [
    join(extensionRoot, "bin", name),
    resolve(extensionRoot, "..", "core", "dist", name),
    resolve(extensionRoot, "..", "..", "core", "dist", name),
  ];
  const workspace = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (workspace) {
    candidates.push(resolve(workspace, "core", "dist", name));
  }
  return firstExisting(candidates);
}
