// Inline (ghost text) completion backed by the Core's FIM endpoint.
// 行内补全：复用 Core 的 inline/complete（FIM），参数与 IntelliJ 侧保持一致。

import * as vscode from "vscode";
import { RpcConnection } from "./rpc";

const DEBOUNCE_MS = 300;
const TIMEOUT_MS = 1500;
const MAX_FILE_BYTES = 512 * 1024;
const MAX_PREFIX_CHARS = 4000;
const MAX_SUFFIX_CHARS = 1200;

export class InlineCompletionProvider implements vscode.InlineCompletionItemProvider, vscode.Disposable {
  private readonly registration: vscode.Disposable;

  constructor(private readonly connection: () => RpcConnection | undefined) {
    this.registration = vscode.languages.registerInlineCompletionItemProvider({ pattern: "**" }, this);
  }

  // Never throws and never shows UI: any failure (no Core, timeout, empty answer)
  // simply means "no suggestion", so typing stays responsive.
  // 不报错也不弹窗：无 Core、超时或空结果一律视为“没有补全”。
  async provideInlineCompletionItems(
    document: vscode.TextDocument,
    position: vscode.Position,
    context: vscode.InlineCompletionContext,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlineCompletionItem[] | undefined> {
    const config = vscode.workspace.getConfiguration("agentCoder");
    if (!config.get<boolean>("inlineCompletion.enabled", true)) {
      return undefined;
    }
    if (document.uri.scheme !== "file" || document.uri.fsPath.length === 0) {
      return undefined;
    }
    // One pass over the text decides both size and binary:
    // a binary file is large or carries NUL bytes, and neither should reach the model.
    // 一次取文本同时判断大小与二进制：两者都不该被送到模型。
    const text = document.getText();
    if (text.length > MAX_FILE_BYTES || text.includes("\u0000")) {
      return undefined;
    }
    const connection = this.connection();
    if (!connection) {
      return undefined;
    }

    const offset = document.offsetAt(position);
    const prefix = text.slice(Math.max(0, offset - MAX_PREFIX_CHARS), offset);
    if (!prefix.trim()) {
      return undefined;
    }
    const suffix = text.slice(offset, offset + MAX_SUFFIX_CHARS);

    // Automatic triggers wait out the debounce and drop stale requests; an explicit
    // invocation (Ctrl+Space) should answer immediately.
    // 自动触发先等 300ms 防抖并丢弃过期请求；手动触发立即响应。
    if (context.triggerKind === vscode.InlineCompletionTriggerKind.Automatic) {
      if (!(await sleep(DEBOUNCE_MS, token))) {
        return undefined;
      }
    }
    if (token.isCancellationRequested) {
      return undefined;
    }

    const response = await withTimeout(connection.request("inline/complete", {
      prefix,
      suffix,
      language: document.languageId,
      path: document.uri.fsPath,
    }), TIMEOUT_MS);
    // A newer keystroke may have arrived while the model was thinking.
    // 模型思考期间可能又有新输入，过期结果直接丢弃。
    if (!response || token.isCancellationRequested) {
      return undefined;
    }
    const answer = response.result?.text;
    if (typeof answer !== "string" || !answer.trim()) {
      return undefined;
    }
    return [new vscode.InlineCompletionItem(answer, new vscode.Range(position, position))];
  }

  dispose(): void {
    this.registration.dispose();
  }
}

// Resolves false when the token fires before the delay elapses.
function sleep(ms: number, token: vscode.CancellationToken): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    if (token.isCancellationRequested) {
      resolve(false);
      return;
    }
    const timer = setTimeout(() => {
      subscription.dispose();
      resolve(!token.isCancellationRequested);
    }, ms);
    const subscription = token.onCancellationRequested(() => {
      clearTimeout(timer);
      resolve(false);
    });
  });
}

// Swallows rejections as well: a timeout and a dead connection both mean "".
function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | undefined> {
  return new Promise<T | undefined>((resolve) => {
    const timer = setTimeout(() => resolve(undefined), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      () => {
        clearTimeout(timer);
        resolve(undefined);
      },
    );
  });
}
