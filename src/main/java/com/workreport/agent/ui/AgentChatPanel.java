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
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.UIUtil;
import com.workreport.agent.rpc.AgentCoreService;
import com.workreport.agent.rpc.JsonRpcConnection;
import com.workreport.agent.tools.ToolDispatcher;
import com.workreport.plugin.settings.ModelProfile;
import com.workreport.plugin.settings.ModelProfiles;
import com.workreport.plugin.settings.ReportSettingsState;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
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
    private final JButton settingsButton = new JButton("设置");
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JCheckBox autoCheckBox = new JCheckBox("自动执行");
    private final JBLabel statusLabel = new JBLabel("正在启动 Agent Core...");
    private boolean updatingModelCombo;

    private final AgentCoreService coreService;
    private volatile String currentTaskId;

    public AgentChatPanel(Project project) {
        super(true, true);
        this.project = project;
        this.sessionId = project.getLocationHash();
        this.toolDispatcher = new ToolDispatcher(project);
        this.coreService = AgentCoreService.getInstance(project);
        coreService.setMessageHandler(this::onCoreMessage);
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
        chatArea.setText("Agent 编码助手已就绪。\n"
                + "首次使用请点右下角「设置」配置模型（Ollama 或 OpenAI 兼容），然后直接描述任务。\n");

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        mainPanel.setBackground(UIUtil.getPanelBackground());

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setOpaque(false);
        statusLabel.setBorder(new EmptyBorder(0, 2, 0, 0));
        statusPanel.add(statusLabel, BorderLayout.WEST);
        // Quick model switcher + auto-apply toggle (Qoder-style).
        // 快速模型切换 + 自动执行开关（类 Qoder 体验）。
        JPanel quickPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        quickPanel.setOpaque(false);
        modelCombo.setToolTipText("切换模型（在设置页维护列表）");
        autoCheckBox.setToolTipText("勾选后写文件不再弹 diff 确认，直接落盘（可用 revert_file 回退）");
        quickPanel.add(new JBLabel("模型:"));
        quickPanel.add(modelCombo);
        quickPanel.add(autoCheckBox);
        statusPanel.add(quickPanel, BorderLayout.EAST);
        mainPanel.add(statusPanel, BorderLayout.NORTH);

        mainPanel.add(new JBScrollPane(chatArea), BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
        inputPanel.setOpaque(false);
        inputField.getEmptyText().setText("向 Agent 描述你的任务，例如：帮我解释当前文件的登录流程");
        inputPanel.add(inputField, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttonPanel.setOpaque(false);
        stopButton.setEnabled(false);
        buttonPanel.add(settingsButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(sendButton);
        inputPanel.add(buttonPanel, BorderLayout.EAST);

        mainPanel.add(inputPanel, BorderLayout.SOUTH);
        setContent(mainPanel);

        sendButton.addActionListener(e -> onSend());
        stopButton.addActionListener(e -> onStop());
        settingsButton.addActionListener(e -> openSettings());
        modelCombo.addActionListener(e -> onModelSelected());
        autoCheckBox.addActionListener(e -> onAutoAcceptToggled());
        inputField.addActionListener(e -> onSend());

        refreshModelCombo();
        autoCheckBox.setSelected(ReportSettingsState.getInstance().autoAcceptEdits);
    }

    // Fill the combo with the configured profiles, keeping the active one selected.
    // 用已配置的模型填充下拉框，并选中当前生效的那个。
    private void refreshModelCombo() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        updatingModelCombo = true;
        try {
            modelCombo.removeAllItems();
            for (ModelProfile profile : ModelProfiles.resolve(state)) {
                modelCombo.addItem(profile.name);
            }
            modelCombo.setSelectedItem(ModelProfiles.activeName(state));
        } finally {
            updatingModelCombo = false;
        }
    }

    private void onModelSelected() {
        if (updatingModelCombo) {
            return;
        }
        Object selected = modelCombo.getSelectedItem();
        if (selected == null) {
            return;
        }
        ReportSettingsState state = ReportSettingsState.getInstance();
        state.activeProfile = selected.toString();
        ModelProfile profile = ModelProfiles.active(state);
        setStatus("已切换模型：" + profile.name + "（" + profile.model + "）");
        coreService.pushSettings();
    }

    private void onAutoAcceptToggled() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        state.autoAcceptEdits = autoCheckBox.isSelected();
        setStatus(state.autoAcceptEdits ? "自动执行已开启：写文件不再确认" : "自动执行已关闭：写文件需确认");
    }

    // Open the plugin settings page; drop the Core afterwards so path/model edits take effect.
    // 打开插件设置页；关闭后重启 Core，使路径/模型修改立即生效。
    private void openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "com.workreport.settings");
        // Model list may have changed while the dialog was open.
        // 设置页可能改过模型列表，刷新下拉框。
        refreshModelCombo();
        autoCheckBox.setSelected(ReportSettingsState.getInstance().autoAcceptEdits);
        if (currentTaskId != null) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            coreService.restart();
            setStatus("配置已更新，下条消息重建 Core");
        });
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
        coreService.connection();
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
                JsonRpcConnection conn = coreService.connectionOrNull();
                if (conn == null) {
                    throw new IOException("Agent Core 未就绪");
                }
                // Re-push settings so model/MCP edits apply without restarting the Core.
                // 重新下发设置，使模型/MCP 修改无需重启 Core 即可生效。
                coreService.pushSettings(conn);
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
        JsonRpcConnection conn = coreService.connectionOrNull();
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
                JsonRpcConnection conn = coreService.connectionOrNull();
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
            JsonRpcConnection conn = coreService.connectionOrNull();
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
        coreService.setMessageHandler(null);
    }
}
