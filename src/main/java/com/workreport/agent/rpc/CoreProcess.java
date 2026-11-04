package com.workreport.agent.rpc;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Manages the Agent Core child process (python -m agent_core).
// 管理 Agent Core 子进程（python -m agent_core）。
public final class CoreProcess implements Disposable {

    private static final Logger LOG = Logger.getInstance(CoreProcess.class);
    private static final int MAX_STDERR_LINES = 50;

    private final Object writeLock = new Object();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private Process process;
    private BufferedWriter stdin;

    public void start(@NotNull String pythonExecutable,
                      @NotNull Path coreHome,
                      @NotNull String projectId,
                      @NotNull Path dataDir) throws IOException {
        Path srcDir = coreHome.resolve("src");
        List<String> command = List.of(
                pythonExecutable, "-m", "agent_core",
                "--project-id", projectId,
                "--data-dir", dataDir.toString());
        launch(command, coreHome, Map.of("PYTHONPATH", srcDir.toString()));
    }

    // Launch a packaged Core executable (PyInstaller build) when present.
    // 存在打包好的 Core 可执行文件（PyInstaller 产物）时直接启动它。
    public void startExecutable(@NotNull Path executable,
                                @NotNull String projectId,
                                @NotNull Path dataDir) throws IOException {
        List<String> command = List.of(
                executable.toString(),
                "--project-id", projectId,
                "--data-dir", dataDir.toString());
        Path workingDir = executable.getParent() != null ? executable.getParent() : Paths.get(".");
        launch(command, workingDir, Map.of());
    }

    private void launch(List<String> command, Path workingDir,
                        Map<String, String> extraEnv) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().putAll(extraEnv);

        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startStderrPump();
        LOG.info("agent core started: " + String.join(" ", command));
    }

    // Drain stderr into the IDE log; keep a short tail for error hints.
    // 将 stderr 排入 IDE 日志，并保留末尾若干行用于错误提示。
    private void startStderrPump() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.info("[core] " + line);
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        while (stderrTail.size() > MAX_STDERR_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            } catch (IOException e) {
                LOG.debug("core stderr stream closed", e);
            }
        }, "agent-core-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    public InputStream getStdout() {
        return process.getInputStream();
    }

    public void writeLine(@NotNull String line) {
        synchronized (writeLock) {
            if (stdin == null) {
                return;
            }
            try {
                stdin.write(line);
                stdin.write("\n");
                stdin.flush();
            } catch (IOException e) {
                LOG.warn("failed to write to agent core", e);
            }
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public String stderrTail() {
        synchronized (stderrTail) {
            return String.join("\n", stderrTail);
        }
    }

    @Override
    public void dispose() {
        if (process == null) {
            return;
        }
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
