package com.workreport.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.workreport.agent.ui.CommandConfirmCard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

// run_command: execute one shell command inside the project and return its output.
// The Core decides whether a prompt is needed (allowlist) or the call is refused;
// this class only asks for confirmation when told to and then runs the process.
// run_command：在项目内执行一条 shell 命令并回传输出。
// 是否需要确认由 Core 判定（白名单/危险命令），这里只负责弹窗与执行。
public final class CommandTools {

    private static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_TIMEOUT_SEC = 600;
    // Keep the tail: failures (stack traces, "BUILD FAILURE") sit at the end.
    // 保留尾部：失败堆栈与 BUILD FAILURE 都在输出末尾。
    private static final int MAX_OUTPUT_CHARS = 8000;
    private static final int HEAD_CHARS = 3200;
    private static final int MAX_CAPTURE_BYTES = 2 * 1024 * 1024;

    private final Project project;
    private final FileTools fileTools;

    public CommandTools(Project project, FileTools fileTools) {
        this.project = project;
        this.fileTools = fileTools;
    }

    public ToolResult runCommand(JsonObject args, boolean needsApproval) {
        if (!args.has("command")) {
            return ToolResult.fail("缺少参数 command");
        }
        String command = args.get("command").getAsString().trim();
        if (command.isEmpty()) {
            return ToolResult.fail("command 不能为空");
        }
        Path cwd = fileTools.projectRoot();
        if (cwd == null) {
            return ToolResult.fail("项目根目录不可用");
        }
        if (args.has("cwd") && !args.get("cwd").getAsString().isBlank()) {
            Path resolved = fileTools.resolve(args.get("cwd").getAsString());
            if (resolved == null) {
                return ToolResult.fail("工作目录无效或越出项目根目录");
            }
            if (!Files.isDirectory(resolved)) {
                return ToolResult.fail("工作目录不存在: " + args.get("cwd").getAsString());
            }
            cwd = resolved;
        }
        int timeout = timeoutSeconds(args);
        if (needsApproval && !CommandConfirmCard.confirm(project, command, cwd)) {
            return ToolResult.fail("用户拒绝：未执行命令 " + command);
        }
        return execute(command, cwd, timeout);
    }

    private int timeoutSeconds(JsonObject args) {
        if (!args.has("timeout_sec")) {
            return DEFAULT_TIMEOUT_SEC;
        }
        try {
            int value = args.get("timeout_sec").getAsInt();
            return Math.max(1, Math.min(value, MAX_TIMEOUT_SEC));
        } catch (RuntimeException e) {
            return DEFAULT_TIMEOUT_SEC;
        }
    }

    private ToolResult execute(String command, Path cwd, int timeoutSec) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(shellLaunch(command));
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            return ToolResult.fail("启动失败: " + e.getMessage());
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(process.getInputStream(), captured),
                "agent-command-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolResult.fail("执行被中断");
        }
        if (!finished) {
            process.destroyForcibly();
            join(reader);
            String partial = render(captured.toByteArray());
            return ToolResult.ok("已终止（超时 " + timeoutSec + " 秒）"
                    + (partial.isEmpty() ? "，无输出" : "\n" + truncate(partial)));
        }
        join(reader);
        int exitCode = process.exitValue();
        String output = render(captured.toByteArray());
        StringBuilder sb = new StringBuilder();
        sb.append(output.isEmpty() ? "（无输出）" : truncate(output));
        sb.append("\n退出码: ").append(exitCode);
        if (exitCode != 0) {
            sb.append("（命令未成功，请根据上面的输出定位原因）");
        }
        return ToolResult.ok(sb.toString());
    }

    // Windows goes through cmd (with UTF-8 code page so Chinese output is readable);
    // Unix through /bin/sh. The command string is passed verbatim - no rewriting.
    // Windows 走 cmd（先切 UTF-8 代码页，中文输出才不会乱码），Unix 走 /bin/sh；
    // 命令字符串原样传入，不做转义改写。
    private List<String> shellLaunch(String command) {
        if (isWindows()) {
            return Arrays.asList("cmd.exe", "/d", "/s", "/c", "chcp 65001>nul & " + command);
        }
        return Arrays.asList("/bin/sh", "-c", command);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void drain(InputStream stream, ByteArrayOutputStream sink) {
        byte[] buffer = new byte[8192];
        try (InputStream input = stream) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (sink.size() >= MAX_CAPTURE_BYTES) {
                    continue;  // keep reading so the child never blocks on a full pipe
                }
                sink.write(buffer, 0, Math.min(read, MAX_CAPTURE_BYTES - sink.size()));
            }
        } catch (IOException ignored) {
            // The process was killed: whatever was captured stays useful.
        }
    }

    private void join(Thread reader) {
        try {
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String render(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        return FileText.decode(bytes).text().stripTrailing();
    }

    private String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        int tailChars = MAX_OUTPUT_CHARS - HEAD_CHARS;
        String head = output.substring(0, HEAD_CHARS);
        String tail = output.substring(output.length() - tailChars);
        String omitted = output.substring(HEAD_CHARS, output.length() - tailChars);
        int omittedLines = FileText.splitLines(omitted).size();
        int totalLines = FileText.splitLines(output).size();
        return head + "\n...（中间省略 " + omittedLines + " 行，共 " + totalLines + " 行）...\n" + tail;
    }
}
