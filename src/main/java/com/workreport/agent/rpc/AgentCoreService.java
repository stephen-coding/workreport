package com.workreport.agent.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.workreport.plugin.settings.ModelProfile;
import com.workreport.plugin.settings.ModelProfiles;
import com.workreport.plugin.settings.ReportSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

// Project-scoped owner of the Agent Core process and its RPC connection.
// The chat panel and the editor ghost-text provider share this one process.
// 项目级 Agent Core 会话：聊天面板与编辑器补全共用同一个 Core 进程与 RPC 连接。
@Service(Service.Level.PROJECT)
public final class AgentCoreService implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentCoreService.class);

    private final Project project;
    private final Object lock = new Object();

    private CoreProcess coreProcess;
    private JsonRpcConnection connection;
    private volatile Consumer<JsonObject> messageHandler;
    private volatile boolean disposed;

    public AgentCoreService(Project project) {
        this.project = project;
    }

    public static AgentCoreService getInstance(@NotNull Project project) {
        return project.getService(AgentCoreService.class);
    }

    public @NotNull String sessionId() {
        return project.getLocationHash();
    }

    public @NotNull Path dataDir() {
        return Paths.get(System.getProperty("user.home"), ".agent", "data", sessionId());
    }

    // Register the single notification sink (the chat panel); null detaches it.
    // 注册通知接收者（聊天面板）；传 null 表示解绑。
    public void setMessageHandler(@Nullable Consumer<JsonObject> handler) {
        this.messageHandler = handler;
    }

    public @Nullable JsonRpcConnection connectionOrNull() {
        JsonRpcConnection current = connection;
        return current != null && current.isAlive() ? current : null;
    }

    // Start the Core on first use: packaged executable first, Python as fallback.
    // 首次使用时启动 Core：优先打包的可执行文件，找不到才回退 Python 模式。
    public @NotNull JsonRpcConnection connection() throws IOException {
        synchronized (lock) {
            if (disposed) {
                throw new IOException("Agent Core 已释放");
            }
            if (connection != null && connection.isAlive()) {
                return connection;
            }
            ReportSettingsState state = ReportSettingsState.getInstance();
            Path coreExe = PythonLocator.locateCoreExe(state.coreExePath);
            coreProcess = new CoreProcess();
            if (coreExe != null) {
                coreProcess.startExecutable(coreExe, sessionId(), dataDir());
            } else {
                String python = PythonLocator.locatePython(state.pythonPath);
                if (python == null) {
                    throw new IOException("未找到 Agent Core：插件未内置可执行文件，且未找到 Python 3.9+。"
                            + "请在设置页填写 Core 可执行文件，或设置 AGENT_CORE_EXE / AGENT_PYTHON 环境变量");
                }
                Path coreHome = PythonLocator.locateCoreHome(state.coreHomePath);
                if (coreHome == null) {
                    throw new IOException("未找到 core 目录。请设置 AGENT_CORE_EXE 或 AGENT_CORE_HOME 环境变量");
                }
                coreProcess.start(python, coreHome, sessionId(), dataDir());
            }
            connection = new JsonRpcConnection(coreProcess);
            connection.addNotificationHandler(this::dispatch);
            connection.start();
            sendIdeHello(connection);
            pushSettings(connection);
            return connection;
        }
    }

    // Drop the current process so the next request picks up edited settings.
    // 丢弃当前进程，使下一次请求重建 Core 并应用新的配置。
    public void restart() {
        synchronized (lock) {
            if (connection != null) {
                connection.dispose();
                connection = null;
            }
            coreProcess = null;
        }
    }

    public void pushSettings() {
        JsonRpcConnection current = connectionOrNull();
        if (current != null) {
            pushSettings(current);
        }
    }

    // Push the active model profile to the Core (provider/model/endpoint/key + MCP + embedding).
    // 将当前选中的模型配置下发给 Core（提供商/模型/端点/Key + MCP + embedding 模型）。
    public void pushSettings(@NotNull JsonRpcConnection conn) {
        ReportSettingsState state = ReportSettingsState.getInstance();
        ModelProfile profile = ModelProfiles.active(state);
        Map<String, Object> settings = new HashMap<>();
        boolean ollama = "ollama".equals(profile.provider);
        settings.put("provider", ollama ? "ollama" : "openai_compat");
        settings.put("base_url", profile.baseUrl);
        settings.put("model", profile.model);
        settings.put("embedding_model", state.embeddingModel);
        String apiKey = ModelProfiles.apiKey(profile.name);
        if (!ollama && apiKey != null && !apiKey.isEmpty()) {
            settings.put("api_key", apiKey);
        }
        Object mcpServers = parseMcpServers(state.mcpServersJson);
        if (mcpServers != null) {
            settings.put("mcp_servers", mcpServers);
        }
        conn.notify("settings/update", settings);
    }

    private void dispatch(@NotNull JsonObject message) {
        Consumer<JsonObject> handler = messageHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.accept(message);
        } catch (Exception e) {
            LOG.warn("agent core message handler failed", e);
        }
    }

    private void sendIdeHello(@NotNull JsonRpcConnection conn) {
        Map<String, Object> hello = new HashMap<>();
        hello.put("protocolVersion", "1.0");
        hello.put("ide", "intellij");
        conn.notify("ide/hello", hello);
    }

    // Parse the MCP server JSON typed in the settings page (array or name map).
    // 解析设置页填写的 MCP 服务 JSON（数组或 name 映射两种形态）。
    private static Object parseMcpServers(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonArray() || element.isJsonObject()) {
                return element;
            }
        } catch (RuntimeException e) {
            LOG.warn("ignored invalid MCP config JSON", e);
        }
        return null;
    }

    @Override
    public void dispose() {
        disposed = true;
        messageHandler = null;
        restart();
    }
}
