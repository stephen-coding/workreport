package com.workreport.agent.ui;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.UIUtil;
import com.workreport.agent.rpc.CoreProcess;
import com.workreport.agent.rpc.JsonRpcConnection;
import com.workreport.agent.rpc.PythonLocator;
import com.workreport.agent.tools.ToolDispatcher;
import com.workreport.plugin.settings.ReportSettingsConfigurable;
import com.workreport.plugin.settings.ReportSettingsState;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

// Agent chat panel — M1: streaming chat over the Core RPC channel.
// Agent 聊天面板 — M1：通过 Core RPC 通道进行流式对话。
public final class AgentChatPanel extends SimpleToolWindowPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentChatPanel.class);

    private final Project project;
    private final String sessionId;
    private final ToolDispatcher toolDispatcher;

    private final JBTextArea chatArea = new JBTextArea(10, 60);
    private final JBTextField inputField = new JBTextField();
    private final JButton sendButton = new JButton("发送");
    private final JButton stopButton = new JButton("停止");
    private final JBLabel statusLabel = new JBLabel("正在启动 Agent Core...");

    private final Object coreLock = new Object();
    private CoreProcess coreProcess;
    private JsonRpcConnection connection;
    private volatile String currentTaskId;
    private volatile boolean disposed;

    public AgentChatPanel(Project project) {
        super(true, true);
        this.project = project;
        this.sessionId = project.getLocationHash();
        this.toolDispatcher = new ToolDispatcher(project);
        initUI();
        startCoreAsync();
    }

    private void initUI() {
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        Font defaultFont = UIManager.getFont("TextArea.font");
        if (defaultFont != null) {
            chatArea.setFont(defaultFont.deriveFont(13f));
        }
        chatArea.setText("Agent 编码助手（M1）。输入问题开始对话。\n");

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        mainPanel.setBackground(UIUtil.getPanelBackground());

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setOpaque(false);
        statusLabel.setBorder(new EmptyBorder(0, 2, 0, 0));
        statusPanel.add(statusLabel, BorderLayout.WEST);
        mainPanel.add(statusPanel, BorderLayout.NORTH);

        mainPanel.add(new JBScrollPane(chatArea), BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
        inputPanel.setOpaque(false);
        inputField.getEmptyText().setText("向 Agent 描述你的任务，例如：帮我解释当前文件的登录流程");
        inputPanel.add(inputField, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttonPanel.setOpaque(false);
        stopButton.setEnabled(false);
        buttonPanel.add(stopButton);
        buttonPanel.add(sendButton);
        inputPanel.add(buttonPanel, BorderLayout.EAST);

        mainPanel.add(inputPanel, BorderLayout.SOUTH);
        setContent(mainPanel);

        sendButton.addActionListener(e -> onSend());
        stopButton.addActionListener(e -> onStop());
        inputField.addActionListener(e -> onSend());
    }

    // ------------------------------------------------------------- lifecycle

    private void startCoreAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ensureStarted();
            } catch (Exception e) {
                LOG.warn("failed to start agent core", e);
                setStatus("启动失败");
                appendText("\n[系统] Agent Core 启动失败：" + e.getMessage() + "\n");
            }
        });
    }

    private void ensureStarted() throws IOException {
        synchronized (coreLock) {
            if (disposed) {
                return;
            }
            if (connection != null && connection.isAlive()) {
                return;
            }
            String python = PythonLocator.locatePython(null);
            if (python == null) {
                throw new IOException("未找到 Python（需 3.9+）。请安装 Python，或设置 AGENT_PYTHON 环境变量");
            }
            Path coreHome = PythonLocator.locateCoreHome(null);
            if (coreHome == null) {
                throw new IOException("未找到 core 目录。请设置 AGENT_CORE_HOME 环境变量指向仓库的 core 目录");
            }
            Path dataDir = Paths.get(System.getProperty("user.home"), ".agent", "data", sessionId);
            coreProcess = new CoreProcess();
            // Prefer the packaged Core executable; fall back to python -m agent_core.
            // 优先使用打包好的 Core 可执行文件，否则回退到 python -m agent_core。
            Path coreExe = PythonLocator.locateCoreExe();
            if (coreExe != null) {
                coreProcess.startExecutable(coreExe, sessionId, dataDir);
            } else {
                coreProcess.start(python, coreHome, sessionId, dataDir);
            }
            connection = new JsonRpcConnection(coreProcess);
            connection.addNotificationHandler(this::onCoreMessage);
            connection.start();
            sendIdeHello(connection);
            sendSettings(connection);
        }
    }

    private void sendIdeHello(JsonRpcConnection conn) {
        Map<String, Object> hello = new HashMap<>();
        hello.put("protocolVersion", "1.0");
        hello.put("ide", "intellij");
        conn.notify("ide/hello", hello);
    }

    // Push current plugin settings to the Core (provider/model/endpoint).
    // 将插件当前设置下发给 Core（提供商/模型/端点）。
    private void sendSettings(JsonRpcConnection conn) {
        ReportSettingsState state = ReportSettingsState.getInstance();
        Map<String, Object> settings = new HashMap<>();
        boolean ollama = state.useOllamaMode;
        settings.put("provider", ollama ? "ollama" : "openai_compat");
        settings.put("base_url", state.apiEndpoint);
        settings.put("model", state.modelName);
        String apiKey = ReportSettingsConfigurable.retrieveApiKey();
        if (!ollama && apiKey != null && !apiKey.isEmpty()) {
            settings.put("api_key", apiKey);
        }
        Object mcpServers = parseMcpServers(state.mcpServersJson);
        if (mcpServers != null) {
            settings.put("mcp_servers", mcpServers);
        }
        conn.notify("settings/update", settings);
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

    // ------------------------------------------------------------- messaging

    // Locate the live panel behind the AgentCoder tool window (used by actions).
    // 找到 AgentCoder 工具窗口背后的面板实例（供 action 使用）。
    public static AgentChatPanel find(Project project) {
        com.intellij.openapi.wm.ToolWindow window =
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("AgentCoder");
        if (window == null) {
            return null;
        }
        com.intellij.ui.content.Content content = window.getContentManager().getContent(0);
        if (content == null || !(content.getComponent() instanceof AgentChatPanel)) {
            return null;
        }
        return (AgentChatPanel) content.getComponent();
    }

    // Pre-fill the input and send immediately (e.g. the daily-report action).
    // 预填输入框并直接发送（例如日报 action）。
    public void sendPresetMessage(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (currentTaskId != null) {
                appendText("\n[系统] 当前任务未结束，请稍后再试。\n");
                return;
            }
            inputField.setText(text);
            onSend();
        });
    }

    private void onSend() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || currentTaskId != null) {
            return;
        }
        inputField.setText("");
        appendText("\n你：" + text + "\n助手：");
        setBusy(true);
        setStatus("思考中...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ensureStarted();
                JsonRpcConnection conn = connection;
                if (conn == null) {
                    throw new IOException("Agent Core 未就绪");
                }
                Map<String, Object> params = new HashMap<>();
                params.put("sessionId", sessionId);
                params.put("message", text);
                params.put("contextSnapshot", buildContextSnapshot());
                conn.request("chat/start", params).thenAccept(response -> {
                    boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
                    if (ok) {
                        currentTaskId = response.getAsJsonObject("result").get("taskId").getAsString();
                    } else {
                        onChatErrorJson(response);
                    }
                }).exceptionally(ex -> {
                    appendText("\n[错误] " + ex.getMessage() + "\n");
                    finishTask();
                    return null;
                });
            } catch (Exception e) {
                LOG.warn("send failed", e);
                appendText("\n[错误] " + e.getMessage() + "\n");
                finishTask();
            }
        });
    }

    private void onStop() {
        JsonRpcConnection conn = connection;
        if (conn == null) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        String taskId = currentTaskId;
        if (taskId != null) {
            params.put("taskId", taskId);
        }
        conn.notify("chat/cancel", params);
    }

    private Map<String, Object> buildContextSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        String basePath = project.getBasePath();
        if (basePath != null) {
            snapshot.put("project_root", basePath);
        }
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) {
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file != null) {
                snapshot.put("current_file", file.getPath());
            }
        }
        return snapshot;
    }

    private void onCoreMessage(JsonObject message) {
        if (!message.has("method")) {
            return;
        }
        String method = message.get("method").getAsString();
        JsonObject params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params")
                : new JsonObject();

        switch (method) {
            case "core/hello":
                setStatus("就绪");
                break;
            case "chat/token": {
                String delta = params.has("delta") ? params.get("delta").getAsString() : "";
                if (!delta.isEmpty()) {
                    appendText(delta);
                }
                break;
            }
            case "tool/invoke": {
                JsonRpcConnection conn = connection;
                if (conn != null) {
                    toolDispatcher.onToolInvoke(params, conn);
                }
                break;
            }
            case "chat/toolStart": {
                String toolName = params.has("toolName") ? params.get("toolName").getAsString() : "?";
                appendText("\n  [工具] " + toolName + " 执行中...");
                break;
            }
            case "chat/toolEnd": {
                boolean ok = params.has("ok") && params.get("ok").getAsBoolean();
                String summary = params.has("summary") ? params.get("summary").getAsString() : "";
                if (summary.length() > 120) {
                    summary = summary.substring(0, 120) + "...";
                }
                appendText("  [工具] " + (ok ? "完成" : "失败")
                        + (summary.isEmpty() ? "" : "：" + summary) + "\n");
                break;
            }
            case "chat/subagent": {
                String kind = params.has("kind") ? params.get("kind").getAsString() : "";
                String subagentId = params.has("subagentId") ? params.get("subagentId").getAsString() : "?";
                if ("start".equals(kind)) {
                    String goal = params.has("goal") ? params.get("goal").getAsString() : "";
                    appendText("\n  [子代理] " + subagentId + " 开始：" + goal);
                } else if ("end".equals(kind)) {
                    boolean ok = params.has("ok") && params.get("ok").getAsBoolean();
                    String summary = params.has("summary") ? params.get("summary").getAsString() : "";
                    if (summary.length() > 120) {
                        summary = summary.substring(0, 120) + "...";
                    }
                    appendText("  [子代理] " + subagentId + (ok ? " 完成" : " 失败")
                            + (summary.isEmpty() ? "" : "：" + summary) + "\n");
                }
                break;
            }
            case "agent/confirm":
                handleConfirm(params);
                break;
            case "chat/finish":
                appendText("\n");
                finishTask();
                setStatus("就绪");
                break;
            case "chat/error": {
                JsonObject error = params.has("error") && params.get("error").isJsonObject()
                        ? params.getAsJsonObject("error")
                        : new JsonObject();
                String code = error.has("code") ? error.get("code").getAsString() : "UNKNOWN";
                String text = error.has("message") ? error.get("message").getAsString() : "";
                appendText("\n[错误:" + code + "] " + text + "\n");
                finishTask();
                setStatus("出错");
                break;
            }
            default:
                // agent/ping and future events are ignored in M1
                break;
        }
    }

    // Ask the user to approve a Core-initiated sensitive action (e.g. an MCP tool call).
    // 向用户确认 Core 发起的敏感操作（例如 MCP 工具调用），结果通过 agent/confirmResult 回传。
    private void handleConfirm(JsonObject params) {
        String confirmId = params.has("confirmId") ? params.get("confirmId").getAsString() : "";
        if (confirmId.isEmpty()) {
            return;
        }
        String server = params.has("server") ? params.get("server").getAsString() : "MCP";
        String tool = params.has("tool") ? params.get("tool").getAsString() : "";
        String args = params.has("args") && params.get("args").isJsonObject()
                ? params.getAsJsonObject("args").toString() : "{}";
        if (args.length() > 400) {
            args = args.substring(0, 400) + "...";
        }
        String detail = args;
        appendText("\n  [确认] MCP 服务 " + server + " 请求调用 " + tool + "...");

        ApplicationManager.getApplication().invokeLater(() -> {
            int answer = Messages.showYesNoDialog(project,
                    "MCP 服务 \"" + server + "\" 请求调用工具 \"" + tool + "\"\n\n参数：" + detail,
                    "确认 MCP 工具调用", "允许", "拒绝", Messages.getQuestionIcon());
            Map<String, Object> result = new HashMap<>();
            result.put("confirmId", confirmId);
            result.put("approved", answer == Messages.YES);
            JsonRpcConnection conn = connection;
            if (conn != null) {
                conn.request("agent/confirmResult", result).exceptionally(ex -> {
                    LOG.warn("failed to send confirm result", ex);
                    return null;
                });
            }
        });
    }

    private void onChatErrorJson(JsonObject response) {
        JsonObject error = response.has("error") && response.get("error").isJsonObject()
                ? response.getAsJsonObject("error")
                : new JsonObject();
        String text = error.has("message") ? error.get("message").getAsString() : "请求失败";
        appendText("\n[错误] " + text + "\n");
        finishTask();
        setStatus("出错");
    }

    private void finishTask() {
        currentTaskId = null;
        setBusy(false);
    }

    // ------------------------------------------------------------- UI helpers

    private void appendText(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            chatArea.append(text);
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    private void setStatus(String text) {
        ApplicationManager.getApplication().invokeLater(() -> statusLabel.setText(text));
    }

    private void setBusy(boolean busy) {
        ApplicationManager.getApplication().invokeLater(() -> {
            sendButton.setEnabled(!busy);
            stopButton.setEnabled(busy);
        });
    }

    @Override
    public void dispose() {
        disposed = true;
        synchronized (coreLock) {
            if (connection != null) {
                connection.dispose();
                connection = null;
            }
            coreProcess = null;
        }
    }
}
