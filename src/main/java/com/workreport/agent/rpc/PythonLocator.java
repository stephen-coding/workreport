package com.workreport.agent.rpc;

import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

// Locates the Python interpreter and the Agent Core home directory.
// 探测 Python 解释器与 Agent Core 目录。
public final class PythonLocator {

    private static final Logger LOG = Logger.getInstance(PythonLocator.class);

    private PythonLocator() {
    }

    // Resolution order: explicit setting -> AGENT_PYTHON env -> PATH candidates
    // 解析顺序：显式配置 -> AGENT_PYTHON 环境变量 -> PATH 候选
    public static String locatePython(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String env = System.getenv("AGENT_PYTHON");
        if (env != null && !env.isBlank()) {
            return env;
        }
        for (String candidate : new String[]{"python", "python3"}) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // Resolution order: explicit setting -> AGENT_CORE_HOME env -> walk up from cwd
    // 解析顺序：显式配置 -> AGENT_CORE_HOME 环境变量 -> 从工作目录向上查找
    public static Path locateCoreHome(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Paths.get(configured).toAbsolutePath();
            if (isCoreHome(path)) {
                return path;
            }
        }
        String env = System.getenv("AGENT_CORE_HOME");
        if (env != null && !env.isBlank()) {
            Path path = Paths.get(env).toAbsolutePath();
            if (isCoreHome(path)) {
                return path;
            }
        }
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("core");
            if (isCoreHome(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isCoreHome(Path path) {
        return path != null && Files.isDirectory(path.resolve("src").resolve("agent_core"));
    }

    // Resolution order: AGENT_CORE_EXE env -> core/dist, dist next to cwd or parents
    // 解析顺序：AGENT_CORE_EXE 环境变量 -> core/dist 或工作目录向上查找 dist
    public static Path locateCoreExe() {
        String env = System.getenv("AGENT_CORE_EXE");
        if (env != null && !env.isBlank()) {
            Path path = Paths.get(env).toAbsolutePath();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        String name = isWindows() ? "agent-core.exe" : "agent-core";
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("core").resolve("dist").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path direct = dir.resolve("dist").resolve(name);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isExecutable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            LOG.debug("python candidate not available: " + command, e);
            return false;
        }
    }
}
