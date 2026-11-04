package com.workreport.agent.rpc;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

// Locates the Python interpreter and the Agent Core home directory.
// 探测 Python 解释器与 Agent Core 目录。
public final class PythonLocator {

    private static final Logger LOG = Logger.getInstance(PythonLocator.class);
    private static final String PLUGIN_ID = "com.workreport.ai-assistant";

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

    // Resolution order: AGENT_CORE_EXE env -> plugin bundle -> core/dist next to cwd or parents
    // 解析顺序：AGENT_CORE_EXE 环境变量 -> 插件内置产物 -> core/dist 或工作目录向上查找
    public static Path locateCoreExe() {
        String env = System.getenv("AGENT_CORE_EXE");
        if (env != null && !env.isBlank()) {
            Path path = Paths.get(env).toAbsolutePath();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        Path bundled = bundledCoreExe();
        if (bundled != null) {
            return bundled;
        }
        String name = coreExecutableName();
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

    // Packaged Core shipped inside the plugin zip: <plugin>/agent-core/agent-core[.exe]
    // 插件 zip 内置的 Core 可执行文件：<plugin>/agent-core/agent-core[.exe]
    private static Path bundledCoreExe() {
        try {
            IdeaPluginDescriptor descriptor =
                    PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
            if (descriptor == null) {
                return null;
            }
            Path base = descriptor.getPluginPath();
            if (base == null) {
                return null;
            }
            Path candidate = base.resolve("agent-core").resolve(coreExecutableName());
            if (Files.isRegularFile(candidate)) {
                File file = candidate.toFile();
                if (!file.canExecute()) {
                    // zip extraction drops the exec bit on unix
                    // zip 解压会丢失 unix 可执行位
                    file.setExecutable(true);
                }
                return candidate;
            }
        } catch (Throwable t) {
            LOG.debug("bundled core lookup failed", t);
        }
        return null;
    }

    public static String coreExecutableName() {
        return isWindows() ? "agent-core.exe" : "agent-core";
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
