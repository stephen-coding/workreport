// Diff confirmation for edit tools: show a native diff editor, then ask to apply.
// 编辑工具的 diff 确认：用原生 diff 编辑器展示，再询问是否应用。

import { mkdirSync, writeFileSync } from "fs";
import { join } from "path";
import { tmpdir } from "os";
import * as vscode from "vscode";
import { autoAcceptEdits } from "./settings";

let tempCounter = 0;

function writeTemp(side: "before" | "after", label: string, content: string): vscode.Uri {
  const dir = join(tmpdir(), "agent-coder-diffs");
  mkdirSync(dir, { recursive: true });
  const safeLabel = label.replace(/[\\/:*?"<>|]/g, "_").slice(-60);
  const file = join(dir, `${++tempCounter}-${safeLabel}.${side}`);
  writeFileSync(file, content, "utf-8");
  return vscode.Uri.file(file);
}

export async function confirmEdit(label: string, before: string, after: string): Promise<boolean> {
  if (autoAcceptEdits()) {
    return true;
  }
  const beforeUri = writeTemp("before", label, before);
  const afterUri = writeTemp("after", label, after);
  await vscode.commands.executeCommand("vscode.diff", beforeUri, afterUri, `Agent 修改预览: ${label}`);
  const choice = await vscode.window.showWarningMessage(
    `Agent 想要修改 ${label}（左侧为原内容，右侧为修改后）。`,
    { modal: true },
    "应用",
    "拒绝",
  );
  return choice === "应用";
}

export async function confirmAction(message: string): Promise<boolean> {
  const choice = await vscode.window.showWarningMessage(message, { modal: true }, "允许", "拒绝");
  return choice === "允许";
}
