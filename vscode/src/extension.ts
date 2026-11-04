// VS Code entry point: register the chat view, commands and config sync.
// VS Code 入口：注册聊天视图、命令与配置同步。

import * as vscode from "vscode";
import { ChatPanel } from "./panel";
import { CoreSession } from "./session";

export function activate(context: vscode.ExtensionContext): void {
  const panel = new ChatPanel(context.extensionUri);
  const session = new CoreSession(context, panel);

  context.subscriptions.push(
    vscode.window.registerWebviewViewProvider(ChatPanel.viewId, panel, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
    vscode.commands.registerCommand("agentCoder.openChat", () =>
      vscode.commands.executeCommand("agentCoder.chatView.focus")),
    vscode.commands.registerCommand("agentCoder.generateDailyReport", () => {
      void vscode.commands.executeCommand("agentCoder.chatView.focus");
      void session.send("请生成今天的日报，先采集今日 Git 提交再组织内容");
    }),
    vscode.commands.registerCommand("agentCoder.restartCore", () => session.restart()),
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration("agentCoder")) {
        session.pushSettings();
      }
    }),
    { dispose: () => session.dispose() },
  );

  void session.ensureStarted().catch((error: unknown) => {
    const message = error instanceof Error ? error.message : String(error);
    panel.appendSystem(`[错误] ${message}`);
    panel.setStatus("启动失败");
    void vscode.window.showErrorMessage(`Agent Coder: ${message}`);
  });
}

export function deactivate(): void {
  // The session is disposed through context.subscriptions.
}
