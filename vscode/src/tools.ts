// Shell-side tool implementations for tool/invoke (files, search, git, edits).
// tool/invoke 的 Shell 侧工具实现（文件、搜索、Git、编辑）。

import { execFile } from "child_process";
import { promises as fs } from "fs";
import { basename, dirname, isAbsolute, relative, resolve, sep } from "path";
import { promisify } from "util";
import * as vscode from "vscode";
import { RpcConnection } from "./rpc";
import { confirmAction, confirmEdit } from "./diff";

const execFileAsync = promisify(execFile);
const MAX_FILE_BYTES = 512 * 1024;
const MAX_LIST_ENTRIES = 300;
const MAX_SEARCH_RESULTS = 50;
const MAX_DIFF_CHARS = 20000;
const SKIP_DIRS = new Set([".git", "node_modules", "target", "dist", "out", "build", ".idea"]);

interface ToolRequest {
  toolCallId?: string;
  toolName?: string;
  params?: Record<string, unknown>;
}

export class ToolRunner {
  private readonly snapshots = new Map<string, string>();

  constructor(private readonly projectRoot: string) {}

  async onToolInvoke(request: ToolRequest, connection: RpcConnection): Promise<void> {
    const toolCallId = request.toolCallId ?? "";
    const toolName = request.toolName ?? "";
    const args = request.params ?? {};
    let content: string;
    try {
      content = await this.run(toolName, args);
      connection.notify("tool/result", { toolCallId, ok: true, result: content });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      connection.notify("tool/result", { toolCallId, ok: false, error: message });
    }
  }

  private async run(toolName: string, args: Record<string, unknown>): Promise<string> {
    switch (toolName) {
      case "read_file":
        return this.readFile(args);
      case "list_dir":
        return this.listDir(args);
      case "search_code":
        return this.searchCode(args);
      case "git_log":
        return this.gitLog(args);
      case "git_diff":
        return this.gitDiff(args);
      case "write_file":
        return this.writeFile(args);
      case "edit_file":
        return this.editFile(args);
      case "delete_file":
        return this.deleteFile(args);
      case "revert_file":
        return this.revertFile(args);
      default:
        throw new Error(`未知工具: ${toolName}`);
    }
  }

  // ---------------------------------------------------------------- helpers

  private resolvePath(raw: unknown): string {
    const text = typeof raw === "string" ? raw.trim() : "";
    if (!text) {
      throw new Error("缺少参数 path");
    }
    const root = resolve(this.projectRoot);
    const candidate = resolve(isAbsolute(text) ? text : resolve(root, text));
    if (candidate !== root && !candidate.startsWith(root + sep)) {
      throw new Error("路径无效或越出项目根目录");
    }
    return candidate;
  }

  private relativeLabel(path: string): string {
    return relative(resolve(this.projectRoot), path).split(sep).join("/") || basename(path);
  }

  private async readText(path: string): Promise<string> {
    const stat = await fs.stat(path);
    if (stat.size > MAX_FILE_BYTES) {
      throw new Error(`文件过大（${stat.size} 字节），请用 start_line/end_line 指定范围`);
    }
    return fs.readFile(path, "utf-8");
  }

  private numberArg(args: Record<string, unknown>, key: string): number | undefined {
    const value = args[key];
    const parsed = typeof value === "number" ? value : Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }

  private stringArg(args: Record<string, unknown>, key: string): string {
    const value = args[key];
    return typeof value === "string" ? value : "";
  }

  private async git(argsList: string[]): Promise<string> {
    try {
      const { stdout } = await execFileAsync("git", argsList, {
        cwd: this.projectRoot,
        maxBuffer: 8 * 1024 * 1024,
      });
      return stdout;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(`git ${argsList[0]} 失败: ${message}`);
    }
  }

  private async persist(path: string, content: string): Promise<void> {
    await fs.mkdir(dirname(path), { recursive: true });
    await fs.writeFile(path, content, "utf-8");
    const document = vscode.workspace.textDocuments.find(
      (doc) => doc.uri.fsPath === path,
    );
    if (document?.isDirty) {
      await document.save();
    }
  }

  // Persist unsaved editor buffers so the agent never overwrites user edits.
  // 先把未保存的编辑器内容落盘，避免覆盖用户正在编辑的内容。
  private async flushDocuments(): Promise<void> {
    const dirty = vscode.workspace.textDocuments.filter((doc) => doc.isDirty);
    await Promise.all(dirty.map((doc) => doc.save()));
  }

  // ------------------------------------------------------------------ tools

  private async readFile(args: Record<string, unknown>): Promise<string> {
    const path = this.resolvePath(args.path);
    const text = await this.readText(path);
    if (!text) {
      return "（空文件）";
    }
    const lines = text.split(/\r?\n/);
    if (lines.length > 0 && lines[lines.length - 1] === "") {
      lines.pop();
    }
    const start = Math.max(1, this.numberArg(args, "start_line") ?? 1);
    const end = Math.min(lines.length, this.numberArg(args, "end_line") ?? lines.length);
    if (start > lines.length || start > end) {
      return `（无匹配行；文件共 ${lines.length} 行）`;
    }
    const output: string[] = [];
    for (let index = start; index <= end; index += 1) {
      output.push(`${index}→${lines[index - 1]}`);
    }
    return output.join("\n");
  }

  private async listDir(args: Record<string, unknown>): Promise<string> {
    const dir = args.path ? this.resolvePath(args.path) : resolve(this.projectRoot);
    const entries = await fs.readdir(dir, { withFileTypes: true });
    const names = entries
      .sort((left, right) => left.name.localeCompare(right.name))
      .slice(0, MAX_LIST_ENTRIES)
      .map((entry) => (entry.isDirectory() ? `${entry.name}/` : entry.name));
    return names.length > 0 ? names.join("\n") : "（空目录）";
  }

  private async searchCode(args: Record<string, unknown>): Promise<string> {
    const pattern = this.stringArg(args, "pattern");
    if (!pattern) {
      throw new Error("缺少参数 pattern");
    }
    let expression: RegExp;
    try {
      expression = new RegExp(pattern, "u");
    } catch (error) {
      throw new Error(`正则表达式无效: ${String(error)}`);
    }
    const root = args.path ? this.resolvePath(args.path) : resolve(this.projectRoot);
    const limit = Math.min(200, this.numberArg(args, "max_results") ?? MAX_SEARCH_RESULTS);
    const results: string[] = [];

    const walk = async (directory: string): Promise<void> => {
      if (results.length >= limit) {
        return;
      }
      const entries = await fs.readdir(directory, { withFileTypes: true });
      for (const entry of entries) {
        if (results.length >= limit) {
          return;
        }
        if (entry.name.startsWith(".") && entry.isDirectory()) {
          continue;
        }
        const fullPath = resolve(directory, entry.name);
        if (entry.isDirectory()) {
          if (!SKIP_DIRS.has(entry.name)) {
            await walk(fullPath);
          }
          continue;
        }
        if (!entry.isFile()) {
          continue;
        }
        let text: string;
        try {
          const stat = await fs.stat(fullPath);
          if (stat.size > MAX_FILE_BYTES) {
            continue;
          }
          text = await fs.readFile(fullPath, "utf-8");
        } catch {
          continue;
        }
        if (text.includes("\u0000")) {
          continue;
        }
        const lines = text.split(/\r?\n/);
        for (let index = 0; index < lines.length; index += 1) {
          if (expression.test(lines[index])) {
            results.push(`${this.relativeLabel(fullPath)}:${index + 1}: ${lines[index].trim().slice(0, 200)}`);
            if (results.length >= limit) {
              return;
            }
          }
        }
      }
    };

    await walk(root);
    return results.length > 0 ? results.join("\n") : "（无匹配结果）";
  }

  private async gitLog(args: Record<string, unknown>): Promise<string> {
    const start = this.stringArg(args, "start_date");
    const end = this.stringArg(args, "end_date");
    if (!start || !end) {
      throw new Error("缺少参数 start_date / end_date（yyyy-MM-dd）");
    }
    if (end < start) {
      throw new Error("end_date 早于 start_date");
    }
    const nextDay = new Date(`${end}T00:00:00Z`);
    nextDay.setUTCDate(nextDay.getUTCDate() + 1);
    const until = nextDay.toISOString().slice(0, 10);
    const output = await this.git([
      "log",
      `--since=${start} 00:00:00`,
      `--until=${until} 00:00:00`,
      "--date=short",
      "--pretty=format:%h|%an|%ad|%s",
    ]);
    if (!output.trim()) {
      return "（该时间范围内没有提交记录）";
    }
    return output
      .trim()
      .split(/\r?\n/)
      .map((line) => {
        const [hash, author, date, ...rest] = line.split("|");
        return `${hash}  ${date}  ${author}\n    ${rest.join("|")}`;
      })
      .join("\n");
  }

  private async gitDiff(args: Record<string, unknown>): Promise<string> {
    const path = this.stringArg(args, "path");
    const output = await this.git(path ? ["diff", "--", path] : ["diff"]);
    if (!output.trim()) {
      return "（无未提交变更）";
    }
    return output.length > MAX_DIFF_CHARS
      ? `${output.slice(0, MAX_DIFF_CHARS)}\n...(已截断)`
      : output;
  }

  private async writeFile(args: Record<string, unknown>): Promise<string> {
    const path = this.resolvePath(args.path);
    const content = this.stringArg(args, "content");
    await this.flushDocuments();
    const before = await fs.readFile(path, "utf-8").catch(() => "");
    if (!(await confirmEdit(this.relativeLabel(path), before, content))) {
      throw new Error(`用户拒绝：未写入 ${this.relativeLabel(path)}`);
    }
    this.snapshots.set(path, before);
    await this.persist(path, content);
    return `已写入 ${this.relativeLabel(path)}（${content.length} 字符）`;
  }

  private async editFile(args: Record<string, unknown>): Promise<string> {
    const path = this.resolvePath(args.path);
    const oldText = this.stringArg(args, "old_string");
    const newText = this.stringArg(args, "new_string");
    if (!oldText) {
      throw new Error("old_string 不能为空");
    }
    await this.flushDocuments();
    const before = await fs.readFile(path, "utf-8").catch(() => {
      throw new Error(`文件不存在: ${this.relativeLabel(path)}`);
    });
    const first = before.indexOf(oldText);
    if (first < 0) {
      throw new Error("未找到 old_string，请先用 read_file 确认原文（注意缩进与换行）");
    }
    const replaceAll = args.replace_all === true;
    if (!replaceAll && before.indexOf(oldText, first + 1) >= 0) {
      throw new Error("old_string 匹配到多处，请提供更长的上下文或设置 replace_all=true");
    }
    const after = replaceAll
      ? before.split(oldText).join(newText)
      : before.slice(0, first) + newText + before.slice(first + oldText.length);

    if (!(await confirmEdit(this.relativeLabel(path), before, after))) {
      throw new Error(`用户拒绝：未修改 ${this.relativeLabel(path)}`);
    }
    this.snapshots.set(path, before);
    await this.persist(path, after);
    return `已修改 ${this.relativeLabel(path)}${replaceAll ? "（替换全部匹配）" : "（1 处）"}`;
  }

  private async deleteFile(args: Record<string, unknown>): Promise<string> {
    const path = this.resolvePath(args.path);
    await this.flushDocuments();
    const before = await fs.readFile(path, "utf-8").catch(() => {
      throw new Error(`文件不存在: ${this.relativeLabel(path)}`);
    });
    if (!(await confirmAction(`Agent 想要删除 ${this.relativeLabel(path)}，是否允许？`))) {
      throw new Error(`用户拒绝：未删除 ${this.relativeLabel(path)}`);
    }
    this.snapshots.set(path, before);
    await fs.unlink(path);
    return `已删除 ${this.relativeLabel(path)}（可用 revert_file 恢复）`;
  }

  private async revertFile(args: Record<string, unknown>): Promise<string> {
    const path = this.resolvePath(args.path);
    const previous = this.snapshots.get(path);
    if (previous === undefined) {
      throw new Error(`没有可回退的快照: ${this.relativeLabel(path)}`);
    }
    this.snapshots.delete(path);
    await this.persist(path, previous);
    return `已回退 ${this.relativeLabel(path)} 到上一次修改前`;
  }
}
