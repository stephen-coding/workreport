package com.workreport.agent.ui;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.OnePixelSplitter;
import com.workreport.agent.rpc.AgentCoreService;
import com.workreport.agent.rpc.JsonRpcConnection;
import com.workreport.agent.tools.ToolDispatcher;
import com.workreport.plugin.settings.ModelProfile;
import com.workreport.plugin.settings.ModelProfiles;
import com.workreport.plugin.settings.ReportSettingsState;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// One chat session: transcript + draggable divider + composer.
// Each session owns its own Core sessionId and taskId, so several of them can run at once.
// 单个会话：对话区 + 可拖动分隔条 + 输入区。
// 每个会话在 Core 里有独立的 sessionId 与 taskId，多个会话可以同时运行。
public final class ChatSessionPanel extends JPanel {

    // Hooks back to the tab container (session list, status bar, settings dialog).
    // 回传给标签容器的钩子（会话列表、状态栏、设置窗口）。
    public interface Owner {
        void onTaskBound(ChatSessionPanel session, String taskId);

        void onTaskCleared(ChatSessionPanel session);

        void onBusyChanged(ChatSessionPanel session);

        void onTitleChanged(ChatSessionPanel session);

        void openSettings();

        void setStatus(String text);
    }

    private static final Logger LOG = Logger.getInstance(ChatSessionPanel.class);

    private final Project project;
    private final String sessionId;
    private final String defaultTitle;
    private final Owner owner;
    private final AgentCoreService coreService;
    private final ToolDispatcher toolDispatcher;

    private final ChatTranscript transcript = new ChatTranscript();
    private final OnePixelSplitter conversationSplit;
    private final ChatComposer composer;

    private volatile String currentTaskId;
    private boolean composerSized;
    private String autoTitle;

    public ChatSessionPanel(Project project, String sessionId, String defaultTitle, Owner owner) {
        super(new BorderLayout());
        this.project = project;
        this.sessionId = sessionId;
        this.defaultTitle = defaultTitle;
        this.owner = owner;
        this.coreService = AgentCoreService.getInstance(project);
        this.toolDispatcher = new ToolDispatcher(project);
        this.conversationSplit = new OnePixelSplitter(true, 0.70f);
        this.composer = new ChatComposer(project, new ComposerListener(), this::applyComposerHeight);
        setOpaque(false);
        initUI();
    }

    private void initUI() {
        transcript.addHint("这是一个独立会话：可以同时开多个会话并行跑任务。"
                + "拖动上方分隔条能加高输入区，「附件」或直接拖文件进来可把文件一并发给模型。");

        // Transcript above, composer below; the divider is draggable so the input can grow.
        // 上方对话、下方输入区；中间分隔条可拖动，用来放大输入区。
        transcript.setMinimumSize(new Dimension(160, 120));
        composer.setMinimumSize(new Dimension(160, 110));
        conversationSplit.setFirstComponent(transcript);
        conversationSplit.setSecondComponent(composer);
        conversationSplit.setHonorComponentsMinimumSize(true);
        add(conversationSplit, BorderLayout.CENTER);

        refreshModelCombo();
        composer.setAutoAccept(ReportSettingsState.getInstance().autoAcceptEdits);
    }

    // ------------------------------------------------------------------ identity

    // Unique per tab, so the Core keeps one message history per session.
    // 每个标签唯一，Core 按它隔离各自的对话历史。
    public String sessionId() {
        return sessionId;
    }

    public String displayTitle() {
        return autoTitle != null ? autoTitle : defaultTitle;
    }

    public boolean isBusy() {
        return currentTaskId != null;
    }

    public String taskId() {
        return currentTaskId;
    }

    // Put the caret in this session's input (called when its tab becomes visible).
    // 标签切过来时把焦点放到本会话的输入框。
    public void focusInput() {
        SwingUtilities.invokeLater(composer::focusInput);
    }

    // Take the first words of the first message as the tab title.
    // 用第一条消息的开头作为标签标题。
    private void updateTitleFrom(String text) {
        if (autoTitle != null || text == null) {
            return;
        }
        String flat = shorten(text);
        if (flat.isEmpty()) {
            return;
        }
        autoTitle = flat;
        owner.onTitleChanged(this);
    }

    private static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "";
        }
        return flat.length() <= 14 ? flat : flat.substring(0, 14) + "…";
    }

    // ------------------------------------------------------------------ history

    // One replayed line: the Core's history store only returns user / assistant text.
    // 历史回放的一行：Core 的历史接口只返回用户与助手的正文，不含工具流水。
    public static final class HistoryMessage {
        public final boolean user;
        public final String text;

        public HistoryMessage(boolean user, String text) {
            this.user = user;
            this.text = text;
        }
    }

    // Fill this session with a stored conversation; sending again continues it,
    // because the Core keys the history by this panel's sessionId.
    // 用 Core 里的历史记录填充本会话；之后再发送会接着这段历史继续。
    public void applyHistory(String title, List<HistoryMessage> messages) {
        String flat = shorten(title);
        if (!flat.isEmpty()) {
            autoTitle = flat;
            owner.onTitleChanged(this);
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }
        transcript.clear();
        for (HistoryMessage message : messages) {
            if (message.user) {
                transcript.addUserMessage(message.text);
            } else {
                transcript.appendAssistantToken(message.text);
                transcript.finishAssistant();
            }
        }
        transcript.addHint("以上为历史记录（已保存在本机），可以接着聊。");
    }

    // ------------------------------------------------------------------ models

    // Fill the composer combo with the configured profiles, keeping the active one selected.
    // 用已配置的模型填充输入区的下拉框，并选中当前生效的那个。
    public void refreshModelCombo() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        List<String> names = new ArrayList<>();
        for (ModelProfile profile : ModelProfiles.resolve(state)) {
            names.add(profile.name);
        }
        composer.setModels(names, ModelProfiles.activeName(state));
        composer.setAutoAccept(state.autoAcceptEdits);
    }

    private void switchModel(String name) {
        ReportSettingsState state = ReportSettingsState.getInstance();
        state.activeProfile = name;
        ModelProfile profile = ModelProfiles.active(state);
        owner.setStatus("已切换模型：" + profile.name + "（" + profile.model + "）");
        coreService.pushSettings();
    }

    private void setAutoAccept(boolean value) {
        ReportSettingsState state = ReportSettingsState.getInstance();
        state.autoAcceptEdits = value;
        owner.setStatus(value ? "自动执行已开启：写文件不再确认" : "自动执行已关闭：写文件需确认");
    }

    // ------------------------------------------------------------------ messaging

    // Pre-fill the input and send immediately (e.g. the daily-report action).
    // 预填输入框并直接发送（例如日报 action）。
    public void sendPresetMessage(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (currentTaskId != null) {
                transcript.addNotice("当前会话还有任务在跑，请等它结束或点「停止」。");
                return;
            }
            composer.setText(text);
            composer.triggerSend();
        });
    }

    // Send the typed text together with the attached files to the Core.
    // 把输入文本与附件一并发送给 Core；附件正文拼进 message，路径写入 contextSnapshot。
    private void sendMessage(String text, List<Path> attachments) {
        if (currentTaskId != null) {
            transcript.addNotice("当前会话还有任务在跑，请等它结束或点「停止」。");
            return;
        }
        if (text == null) {
            text = "";
        }
        text = text.trim();
        if (text.isEmpty() && attachments.isEmpty()) {
            return;
        }
        String projectRoot = project.getBasePath();
        List<String> names = new ArrayList<>();
        for (Path path : attachments) {
            names.add(ChatAttachment.displayName(path, projectRoot));
        }
        transcript.addUserMessage(text.isEmpty() ? "（仅附件）" : text, names);
        composer.reset();
        updateTitleFrom(text);
        setBusy(true);
        owner.setStatus("思考中...");

        String userText = text;
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
                params.put("message", ChatAttachment.buildPrompt(userText, attachments, projectRoot));
                params.put("contextSnapshot", buildContextSnapshot(attachments));
                conn.request("chat/start", params).thenAccept(response -> {
                    boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
                    if (ok) {
                        String taskId = response.getAsJsonObject("result").get("taskId").getAsString();
                        currentTaskId = taskId;
                        // Index the task before any token can arrive so the container can route it.
                        // 先登记 taskId，容器后续才能把属于本会话的事件路由过来。
                        owner.onTaskBound(this, taskId);
                    } else {
                        onChatErrorJson(response);
                    }
                }).exceptionally(ex -> {
                    transcript.addErrorMessage("[错误] " + ex.getMessage());
                    finishTask();
                    return null;
                });
            } catch (Exception e) {
                LOG.warn("send failed", e);
                transcript.addErrorMessage("[错误] " + e.getMessage());
                finishTask();
            }
        });
    }

    private void ensureStarted() throws IOException {
        coreService.connection();
    }

    // Cancel only this session's task; other sessions keep running.
    // 只取消本会话的任务，不影响其它会话。
    public void onStop() {
        String taskId = currentTaskId;
        JsonRpcConnection conn = coreService.connectionOrNull();
        if (conn == null || taskId == null) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("taskId", taskId);
        conn.notify("chat/cancel", params);
    }

    private Map<String, Object> buildContextSnapshot(List<Path> attachments) {
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
        if (!attachments.isEmpty()) {
            List<String> paths = new ArrayList<>();
            for (Path path : attachments) {
                paths.add(path.toString());
            }
            snapshot.put("open_files", paths);
        }
        return snapshot;
    }

    // ------------------------------------------------------------------ core events

    // Deliver one Core notification that the container routed to this session.
    // 处理容器按 taskId 路由给本会话的一条 Core 通知。
    void onCoreMessage(String method, JsonObject params) {
        switch (method) {
            case "chat/token": {
                String delta = params.has("delta") ? params.get("delta").getAsString() : "";
                transcript.appendAssistantToken(delta);
                break;
            }
            case "tool/invoke": {
                JsonRpcConnection conn = coreService.connectionOrNull();
                if (conn != null) {
                    // Writes from parallel sessions are serialized inside the dispatcher.
                    // 并行会话的文件写在 dispatcher 内部串行排队。
                    toolDispatcher.onToolInvoke(params, conn, displayTitle(), transcript::addToolLine);
                }
                break;
            }
            case "chat/toolStart": {
                String toolName = params.has("toolName") ? params.get("toolName").getAsString() : "?";
                transcript.addToolLine("[工具] " + toolName + " 执行中...");
                break;
            }
            case "chat/toolEnd": {
                boolean ok = params.has("ok") && params.get("ok").getAsBoolean();
                String summary = params.has("summary") ? params.get("summary").getAsString() : "";
                if (summary.length() > 120) {
                    summary = summary.substring(0, 120) + "...";
                }
                transcript.addToolLine("[工具] " + (ok ? "完成" : "失败")
                        + (summary.isEmpty() ? "" : "：" + summary));
                break;
            }
            case "chat/subagent": {
                String kind = params.has("kind") ? params.get("kind").getAsString() : "";
                String subagentId = params.has("subagentId") ? params.get("subagentId").getAsString() : "?";
                if ("start".equals(kind)) {
                    String goal = params.has("goal") ? params.get("goal").getAsString() : "";
                    transcript.addToolLine("[子代理] " + subagentId + " 开始：" + goal);
                } else if ("end".equals(kind)) {
                    boolean ok = params.has("ok") && params.get("ok").getAsBoolean();
                    String summary = params.has("summary") ? params.get("summary").getAsString() : "";
                    if (summary.length() > 120) {
                        summary = summary.substring(0, 120) + "...";
                    }
                    transcript.addToolLine("[子代理] " + subagentId + (ok ? " 完成" : " 失败")
                            + (summary.isEmpty() ? "" : "：" + summary));
                }
                break;
            }
            case "chat/notice": {
                String text = params.has("text") ? params.get("text").getAsString() : "";
                if (!text.isEmpty()) {
                    transcript.addNotice(text);
                }
                break;
            }
            case "agent/confirm":
                handleConfirm(params);
                break;
            case "chat/finish":
                transcript.finishAssistant();
                finishTask();
                owner.setStatus("就绪");
                break;
            case "chat/error": {
                JsonObject error = params.has("error") && params.get("error").isJsonObject()
                        ? params.getAsJsonObject("error")
                        : new JsonObject();
                String code = error.has("code") ? error.get("code").getAsString() : "UNKNOWN";
                String text = error.has("message") ? error.get("message").getAsString() : "";
                transcript.addErrorMessage("[错误:" + code + "] " + text);
                finishTask();
                owner.setStatus("出错");
                break;
            }
            default:
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
        transcript.addToolLine("[确认] MCP 服务 " + server + " 请求调用 " + tool + "...");

        ApplicationManager.getApplication().invokeLater(() -> {
            int answer = Messages.showYesNoDialog(project,
                    "会话「" + displayTitle() + "」\n\nMCP 服务 \"" + server + "\" 请求调用工具 \"" + tool
                            + "\"\n\n参数：" + detail,
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
        transcript.addErrorMessage("[错误] " + text);
        finishTask();
        owner.setStatus("出错");
    }

    private void finishTask() {
        currentTaskId = null;
        setBusy(false);
        owner.onTaskCleared(this);
    }

    // ------------------------------------------------------------------ ui helpers

    private void setBusy(boolean busy) {
        ApplicationManager.getApplication().invokeLater(() -> {
            composer.setBusy(busy);
            owner.onBusyChanged(this);
        });
    }

    // Resize the splitter for the composer: grow when the text needs more room, collapse after send.
    // 根据输入区的期望高度调整分隔条：内容变多时长高，发送后（force）收回到基础高度。
    // Manual drags are never undone, because only "taller than now" and "forced" requests apply.
    // 手动拖动过的尺寸不会被覆盖，因为只在“需要更高”或“强制收回”时才调整。
    private void applyComposerHeight(int desired, boolean force) {
        int total = conversationSplit.getHeight();
        if (total <= 0) {
            return;
        }
        // First layout: snap the composer to its base height instead of the 30% default.
        // 首次布局：把输入区收到基础高度，而不是分隔条的默认比例。
        boolean first = !composerSized;
        composerSized = true;
        int current = composer.getHeight();
        if (!force && !first && desired <= current + 4) {
            return;
        }
        double share = Math.max(0.12, Math.min(0.80, (double) desired / total));
        float proportion = (float) (1.0 - share);
        // Apply outside the layout pass: resizing the splitter while it lays out loops.
        // 延后到布局之后应用，避免在布局过程中改分隔条比例导致反复重排。
        ApplicationManager.getApplication().invokeLater(() -> conversationSplit.setProportion(proportion));
    }

    // Bridges composer callbacks back to this session.
    // 把输入区的回调转接到本会话。
    private final class ComposerListener implements ChatComposer.Listener {
        @Override
        public void onSend(String text, List<Path> attachments) {
            sendMessage(text, attachments);
        }

        @Override
        public void onStop() {
            ChatSessionPanel.this.onStop();
        }

        @Override
        public void onOpenSettings() {
            owner.openSettings();
        }

        @Override
        public void onModelChanged(String modelName) {
            switchModel(modelName);
        }

        @Override
        public void onAutoAcceptChanged(boolean autoAccept) {
            setAutoAccept(autoAccept);
        }
    }

    // Cancel the running task (if any) and release UI resources when the tab closes.
    // 关闭标签时取消仍在跑的任务并释放界面资源。
    public void dispose() {
        onStop();
        transcript.dispose();
    }
}
