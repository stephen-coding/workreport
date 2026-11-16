package com.workreport.agent.ui;

import com.google.gson.JsonElement;
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
import com.workreport.agent.AgentBundle;
import com.workreport.agent.rpc.AgentCoreService;
import com.workreport.agent.rpc.JsonRpcConnection;
import com.workreport.agent.tools.ToolDispatcher;
import com.workreport.agent.ui.chat.AskCard;
import com.workreport.agent.ui.chat.ToolCallCard;
import com.workreport.agent.ui.design.UiMetrics;
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

    // Hooks back to the tab container (session list, tab strip, settings dialog).
    // 回传给标签容器的钩子（会话列表、标签条、设置窗口）。
    // The status line lives in this panel, so the container no longer needs a setStatus hook.
    // 状态行已经下移到本面板，容器不再需要 setStatus 钩子。
    public interface Owner {
        void onTaskBound(ChatSessionPanel session, String taskId);

        void onTaskCleared(ChatSessionPanel session);

        void onBusyChanged(ChatSessionPanel session);

        void onTitleChanged(ChatSessionPanel session);

        void openSettings();
    }

    private static final Logger LOG = Logger.getInstance(ChatSessionPanel.class);

    private final Project project;
    private final String sessionId;
    private final String defaultTitle;
    private final Owner owner;
    private final AgentCoreService coreService;
    private final ToolDispatcher toolDispatcher;

    private final ChatTranscript transcript = new ChatTranscript();
    private final TodoStrip todoStrip = new TodoStrip();
    private final StatusRow statusRow = new StatusRow();
    private final OnePixelSplitter conversationSplit;
    private final ChatComposer composer;

    private volatile String currentTaskId;
    // Confirmation cards still waiting for an answer, keyed by confirmId.
    // 还在等用户答复的确认卡片，键是 confirmId。
    private final Map<String, ToolCallCard> pendingConfirms = new HashMap<>();
    // Questions the Core is currently blocked on, keyed by askId.
    // Core 正在等待回答的提问，按 askId 索引。
    private final Map<String, AskCard> pendingAsks = new HashMap<>();
    private boolean composerSized;
    private String autoTitle;
    private String modelLabel = "";
    private long turnStartMs;
    private long turnIn;
    private long turnOut;

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
        // One short line under the empty-state cards; the long explanation that used to greet
        // every new tab is gone, the suggestion cards carry it now.
        // 空态卡片下方只留一行提示；过去那段长说明由建议卡取代。
        transcript.addHint(AgentBundle.message("ui.session.hint"));
        // Clicking a suggestion card drops its prompt into the composer instead of sending it.
        // 点建议卡只是把提示词填进输入框，不直接发送。
        transcript.setSuggestionSink(composer::setText);

        // Transcript above, composer below; the divider is draggable so the input can grow.
        // 上方对话、下方输入区；中间分隔条可拖动，用来放大输入区。
        transcript.setMinimumSize(new Dimension(160, 120));
        composer.setMinimumSize(new Dimension(160, 100));
        // The checklist sits above the transcript, inside the top half, so the divider
        // still only resizes transcript vs composer.
        // 清单放在对话区上方（仍在上半区），分隔条依旧只调整对话区与输入区。
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setMinimumSize(new Dimension(160, 120));
        top.add(todoStrip, BorderLayout.NORTH);
        top.add(transcript, BorderLayout.CENTER);

        // The status line moved out of the tool-window header and sits right above the input.
        // 状态行从工具窗口顶部下移到输入区上方。
        JPanel bottom = new JPanel(new BorderLayout(0, UiMetrics.xs()));
        bottom.setOpaque(false);
        bottom.setMinimumSize(new Dimension(160, 110));
        bottom.add(statusRow, BorderLayout.NORTH);
        bottom.add(composer, BorderLayout.CENTER);

        conversationSplit.setFirstComponent(top);
        conversationSplit.setSecondComponent(bottom);
        conversationSplit.setHonorComponentsMinimumSize(true);
        add(conversationSplit, BorderLayout.CENTER);

        setStatus(AgentBundle.message("status.ready"));
        refreshModelCombo();
        composer.setAutoAccept(ReportSettingsState.getInstance().autoAcceptEdits);
    }

    // Status text and the meta chip are owned by this panel now.
    // 状态文本与右侧 chip 改由本面板维护。
    public void setStatus(String text) {
        statusRow.setText(text);
    }

    // Right-hand chip: model name, this turn's tokens and its wall-clock time.
    // 右侧 chip：模型名 + 本轮用量 + 耗时。
    private void refreshMeta() {
        StringBuilder meta = new StringBuilder(modelLabel);
        if (turnIn > 0 || turnOut > 0) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(formatTokens(turnIn)).append('/').append(formatTokens(turnOut));
        }
        if (turnStartMs > 0) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            long elapsed = System.currentTimeMillis() - turnStartMs;
            meta.append(String.format(java.util.Locale.ROOT, "%.1fs", elapsed / 1000.0));
        }
        statusRow.setMeta(meta.toString());
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
    public void applyHistory(String title, List<HistoryMessage> messages,
                             List<TodoStrip.Item> todos) {
        String flat = shorten(title);
        if (!flat.isEmpty()) {
            autoTitle = flat;
            owner.onTitleChanged(this);
        }
        // The checklist is part of the session, so replay it with the transcript.
        // 清单属于会话的一部分，和聊天一起回放。
        todoStrip.setItems(todos);
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
        String active = ModelProfiles.activeName(state);
        composer.setModels(names, active);
        composer.setAutoAccept(state.autoAcceptEdits);
        modelLabel = active == null ? "" : active;
        refreshMeta();
    }

    private void switchModel(String name) {
        ReportSettingsState state = ReportSettingsState.getInstance();
        state.activeProfile = name;
        ModelProfile profile = ModelProfiles.active(state);
        modelLabel = profile.name;
        refreshMeta();
        setStatus(AgentBundle.message("session.modelSwitched", profile.name, profile.model));
        coreService.pushSettings();
    }

    private void setAutoAccept(boolean value) {
        ReportSettingsState state = ReportSettingsState.getInstance();
        state.autoAcceptEdits = value;
        setStatus(value ? AgentBundle.message("session.autoOn") : AgentBundle.message("session.autoOff"));
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
        // Start the clock and reset the counters for the meta chip of this turn.
        // 开始本轮计时并清零用量，供右侧 chip 展示。
        turnStartMs = System.currentTimeMillis();
        turnIn = 0;
        turnOut = 0;
        refreshMeta();
        setBusy(true);
        setStatus(AgentBundle.message("status.thinking"));

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
                // A role may run on its own model, so show it next to the task id.
                // 角色可能跑在独立模型上，提示里带上模型名便于核实。
                String model = params.has("model") ? params.get("model").getAsString() : "";
                String label = model.isEmpty() ? subagentId : subagentId + "·" + model;
                if ("start".equals(kind)) {
                    String goal = params.has("goal") ? params.get("goal").getAsString() : "";
                    transcript.addToolLine("[子代理] " + label + " 开始：" + goal);
                } else if ("end".equals(kind)) {
                    boolean ok = params.has("ok") && params.get("ok").getAsBoolean();
                    long durationMs = params.has("durationMs") ? params.get("durationMs").getAsLong() : 0L;
                    String cost = durationMs > 0 ? String.format("（%.1fs）", durationMs / 1000.0) : "";
                    String summary = params.has("summary") ? params.get("summary").getAsString() : "";
                    if (summary.length() > 120) {
                        summary = summary.substring(0, 120) + "...";
                    }
                    transcript.addToolLine("[子代理] " + label + (ok ? " 完成" : " 失败") + cost
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
            case "chat/todos":
                todoStrip.setItems(parseTodos(params));
                break;
            case "agent/confirm":
                handleConfirm(params);
                break;
            case "ask/user":
                handleAsk(params);
                break;
            case "ask/withdraw": {
                String askId = params.has("askId") ? params.get("askId").getAsString() : "";
                AskCard card = pendingAsks.remove(askId);
                if (card != null) {
                    card.withdraw();
                }
                break;
            }
            case "chat/finish":
                transcript.finishAssistant();
                appendUsage(params);
                finishTask();
                setStatus(AgentBundle.message("status.ready"));
                break;
            case "chat/error": {
                JsonObject error = params.has("error") && params.get("error").isJsonObject()
                        ? params.getAsJsonObject("error")
                        : new JsonObject();
                String code = error.has("code") ? error.get("code").getAsString() : "UNKNOWN";
                String text = error.has("message") ? error.get("message").getAsString() : "";
                transcript.addErrorMessage("[错误:" + code + "] " + text);
                finishTask();
                setStatus(AgentBundle.message("status.error"));
                break;
            }
            default:
                break;
        }
    }

    // Extract a todo list from a chat/todos payload or a session/get result;
    // both carry the same {"todos": [{"content", "status"}]} shape.
    // 从 chat/todos 负载或 session/get 结果里取清单，两者字段一致。
    public static List<TodoStrip.Item> parseTodos(JsonObject container) {
        List<TodoStrip.Item> items = new ArrayList<>();
        if (container == null || !container.has("todos") || !container.get("todos").isJsonArray()) {
            return items;
        }
        for (JsonElement element : container.getAsJsonArray("todos")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject todo = element.getAsJsonObject();
            String content = todo.has("content") && !todo.get("content").isJsonNull()
                    ? todo.get("content").getAsString() : "";
            if (content.isBlank()) {
                continue;
            }
            String status = todo.has("status") && !todo.get("status").isJsonNull()
                    ? todo.get("status").getAsString() : "pending";
            items.add(new TodoStrip.Item(content, status));
        }
        return items;
    }

    // Ask the user to approve a Core-initiated sensitive action (e.g. an MCP tool call).
    // The card lives in the transcript, so the context that led to the call stays visible and
    // Enter/Esc answer it; the modal dialog is only the fallback for when this session is not on
    // screen (another tab is active, or the tool window is collapsed).
    // 向用户确认 Core 发起的敏感操作（例如 MCP 工具调用）。
    // 卡片留在对话区里，调用前的上下文不被遮挡，Enter/Esc 即可答复；
    // 只有本会话不在屏幕上（切到了别的标签、工具窗口收起）时才退回模态对话框。
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

        ApplicationManager.getApplication().invokeLater(() -> {
            if (transcript.isShowing()) {
                ToolCallCard card = new ToolCallCard(server, tool, detail);
                card.setDecisionHandler(approved -> {
                    pendingConfirms.remove(confirmId);
                    sendConfirmResult(confirmId, approved);
                    setStatus(AgentBundle.message("status.thinking"));
                });
                if (transcript.addCard(card)) {
                    pendingConfirms.put(confirmId, card);
                    setStatus(AgentBundle.message("ui.confirm.status"));
                    return;
                }
            }
            askConfirmInDialog(confirmId, server, tool, detail);
        });
    }

    // Fallback path: a dialog is the only option when no transcript is on screen.
    // 保底路径：对话区不在屏幕上时只能用弹框。
    private void askConfirmInDialog(String confirmId, String server, String tool, String detail) {
        transcript.addToolLine("[确认] MCP 服务 " + server + " 请求调用 " + tool + "...");
        int answer = Messages.showYesNoDialog(project,
                "会话「" + displayTitle() + "」\n\nMCP 服务 \"" + server + "\" 请求调用工具 \"" + tool
                        + "\"\n\n参数：" + detail,
                "确认 MCP 工具调用", "允许", "拒绝", Messages.getQuestionIcon());
        sendConfirmResult(confirmId, answer == Messages.YES);
    }

    private void sendConfirmResult(String confirmId, boolean approved) {
        Map<String, Object> result = new HashMap<>();
        result.put("confirmId", confirmId);
        result.put("approved", approved);
        JsonRpcConnection conn = coreService.connectionOrNull();
        if (conn != null) {
            conn.request("agent/confirmResult", result).exceptionally(ex -> {
                LOG.warn("failed to send confirm result", ex);
                return null;
            });
        }
    }

    // The agent asked a question (``ask_user``) and is blocked until we answer.
    // Same routing as a confirmation: an in-place card first, a dialog only when this
    // session is not on screen. Skipping is explicit, so the model continues with
    // assumptions instead of waiting for an answer that never comes.
    // Agent 提问（``ask_user``）并停在这一轮等我们回答。
    // 与确认走同一套路由：优先对话区内的卡片，本会话不可见时才用对话框兵底；
    // 「跳过」是显式动作，模型会带着假设继续，不会干等。
    private void handleAsk(JsonObject params) {
        String askId = params.has("askId") ? params.get("askId").getAsString() : "";
        if (askId.isEmpty() || !params.has("questions") || !params.get("questions").isJsonArray()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            AskCard card = buildAskCard(params);
            if (card.isEmpty()) {
                AskCard.Answer skipped = new AskCard.Answer();
                skipped.skipped = true;
                sendAskResult(askId, skipped);
                return;
            }
            card.setAnswerHandler(answer -> {
                pendingAsks.remove(askId);
                sendAskResult(askId, answer);
                setStatus(AgentBundle.message("status.thinking"));
            });
            if (transcript.isShowing() && transcript.addCard(card)) {
                pendingAsks.put(askId, card);
                setStatus(AgentBundle.message("ui.ask.status"));
                return;
            }
            askInDialog(askId, params.getAsJsonArray("questions"));
        });
    }

    private static AskCard buildAskCard(JsonObject params) {
        AskCard card = new AskCard();
        for (JsonElement element : params.getAsJsonArray("questions")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject question = element.getAsJsonObject();
            String text = textOf(question, "question");
            List<String> labels = new ArrayList<>();
            List<String> descriptions = new ArrayList<>();
            if (question.has("options") && question.get("options").isJsonArray()) {
                for (JsonElement optionElement : question.getAsJsonArray("options")) {
                    if (!optionElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject option = optionElement.getAsJsonObject();
                    String label = textOf(option, "label");
                    if (label.isEmpty()) {
                        continue;
                    }
                    labels.add(label);
                    descriptions.add(textOf(option, "description"));
                }
            }
            boolean multi = question.has("multiSelect") && question.get("multiSelect").getAsBoolean();
            boolean allowCustom = !question.has("allowCustom")
                    || question.get("allowCustom").getAsBoolean();
            card.question(textOf(question, "header"), text, labels, descriptions, multi, allowCustom);
        }
        return card.finishQuestions();
    }

    private static String textOf(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        return object.get(field).getAsString().trim();
    }

    // Fallback path: one dialog per question. Multi-select degrades to single choice.
    // 兵底路径：逐题弹框；多选在对话框里降为单选。
    private void askInDialog(String askId, Iterable<JsonElement> questions) {
        AskCard.Answer answer = new AskCard.Answer();
        for (JsonElement element : questions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject question = element.getAsJsonObject();
            String text = textOf(question, "question");
            String title = textOf(question, "header");
            if (title.isEmpty()) {
                title = AgentBundle.message("ui.ask.dialog.title");
            }
            List<String> labels = new ArrayList<>();
            if (question.has("options") && question.get("options").isJsonArray()) {
                for (JsonElement optionElement : question.getAsJsonArray("options")) {
                    if (optionElement.isJsonObject()) {
                        String label = textOf(optionElement.getAsJsonObject(), "label");
                        if (!label.isEmpty()) {
                            labels.add(label);
                        }
                    }
                }
            }
            boolean allowCustom = !question.has("allowCustom")
                    || question.get("allowCustom").getAsBoolean();
            List<String> picked = new ArrayList<>();
            String custom = "";
            if (labels.isEmpty()) {
                custom = Messages.showInputDialog(project, text, title,
                        Messages.getQuestionIcon());
                if (custom == null) {
                    answer.skipped = true;
                    break;
                }
            } else {
                List<String> choices = new ArrayList<>(labels);
                if (allowCustom) {
                    choices.add(AgentBundle.message("ui.ask.dialog.custom"));
                }
                int index = Messages.showChooseDialog(project,
                        "会话「" + displayTitle() + "」\n\n" + text, title,
                        Messages.getQuestionIcon(), choices.toArray(new String[0]), choices.get(0));
                if (index < 0) {
                    answer.skipped = true;
                    break;
                }
                if (allowCustom && index == labels.size()) {
                    String typed = Messages.showInputDialog(project,
                            AgentBundle.message("ui.ask.dialog.input"), title,
                            Messages.getQuestionIcon());
                    if (typed == null) {
                        answer.skipped = true;
                        break;
                    }
                    custom = typed.trim();
                } else {
                    picked.add(labels.get(index));
                }
            }
            answer.picked.add(picked);
            answer.custom.add(custom);
        }
        sendAskResult(askId, answer);
    }

    private void sendAskResult(String askId, AskCard.Answer answer) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < answer.picked.size(); i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("index", i);
            item.put("picked", answer.picked.get(i));
            item.put("custom", answer.custom.get(i));
            items.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("askId", askId);
        result.put("skipped", answer.skipped);
        result.put("answers", items);
        JsonRpcConnection conn = coreService.connectionOrNull();
        if (conn != null) {
            conn.request("ask/userAnswer", result).exceptionally(ex -> {
                LOG.warn("failed to send ask answer", ex);
                return null;
            });
        }
    }

    private void onChatErrorJson(JsonObject response) {
        JsonObject error = response.has("error") && response.get("error").isJsonObject()
                ? response.getAsJsonObject("error")
                : new JsonObject();
        String text = error.has("message") ? error.get("message").getAsString() : "请求失败";
        transcript.addErrorMessage("[错误] " + text);
        finishTask();
        setStatus(AgentBundle.message("status.error"));
    }

    private void finishTask() {
        currentTaskId = null;
        setBusy(false);
        refreshMeta();
        owner.onTaskCleared(this);
    }

    // Show the turn's summed token usage; hidden when the endpoint reports none.
    // 展示本轮累计用量；端点不报用量时不显示。
    private void appendUsage(JsonObject params) {
        if (!params.has("usageTotal") || !params.get("usageTotal").isJsonObject()) {
            return;
        }
        JsonObject usage = params.getAsJsonObject("usageTotal");
        long in = usage.has("in") && !usage.get("in").isJsonNull() ? usage.get("in").getAsLong() : 0;
        long out = usage.has("out") && !usage.get("out").isJsonNull() ? usage.get("out").getAsLong() : 0;
        turnIn = in;
        turnOut = out;
        refreshMeta();
        if (in == 0 && out == 0) {
            return;
        }
        transcript.addNotice("本轮用量：" + formatTokens(in) + " 输入 / "
                + formatTokens(out) + " 输出");
    }

    private static String formatTokens(long value) {
        if (value < 1000) {
            return String.valueOf(value);
        }
        return String.format(java.util.Locale.ROOT, "%.1fk", value / 1000.0);
    }

    // ------------------------------------------------------------------ ui helpers

    private void setBusy(boolean busy) {
        ApplicationManager.getApplication().invokeLater(() -> {
            composer.setBusy(busy);
            statusRow.setBusy(busy);
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
        // Never leave a Core confirmation unanswered when the tab goes away.
        // 标签关闭时不能把 Core 的确认请求悬空:统一按拒绝回传。
        for (ToolCallCard card : new ArrayList<>(pendingConfirms.values())) {
            card.decide(false);
        }
        pendingConfirms.clear();
        // A question is never left hanging either: skipping lets the Core finish the turn.
        // 提问也不能悬空：按「跳过」回传，Core 才能把这轮跑完。
        for (AskCard card : new ArrayList<>(pendingAsks.values())) {
            card.skip();
        }
        pendingAsks.clear();
        transcript.dispose();
    }
}
