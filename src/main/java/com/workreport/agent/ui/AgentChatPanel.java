package com.workreport.agent.ui;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import com.workreport.agent.rpc.AgentCoreService;
import com.workreport.plugin.settings.ReportSettingsState;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

// Agent chat tool window: a tab strip of parallel chat sessions.
// Each tab owns its own Core session id and task id, so several agents can run at once;
// notifications are routed back to the tab by taskId.
// Agent 聊天工具窗口：一排并行会话标签。
// 每个标签有自己的 Core 会话与任务 id，因此可同时跑多个 Agent；通知按 taskId 路由回标签。
public final class AgentChatPanel extends SimpleToolWindowPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentChatPanel.class);

    // Enough for parallel work without turning the tab strip into a scroll bar.
    // 并行够用，又不至于把标签条挤成滚动条。
    private static final int MAX_SESSIONS = 8;

    private final Project project;
    private final AgentCoreService coreService;

    private final JBLabel statusLabel = new JBLabel("正在启动 Agent Core...");
    private final JBLabel busyLabel = new JBLabel("");
    private final JPanel tabsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel cards = new JPanel(new CardLayout());

    private final Map<String, ChatSessionPanel> sessions = new LinkedHashMap<>();
    // taskId -> session, filled when chat/start answers and cleared when the task ends.
    // taskId -> 会话，在 chat/start 返回时登记，任务结束时解除。
    private final Map<String, ChatSessionPanel> byTask = new ConcurrentHashMap<>();

    private final ChatSessionPanel.Owner sessionOwner = new SessionOwner();
    private ChatSessionPanel active;
    private int counter;

    public AgentChatPanel(Project project) {
        super(true, true);
        this.project = project;
        this.coreService = AgentCoreService.getInstance(project);
        coreService.setMessageHandler(this::onCoreMessage);
        initUI();
        openSession();
        refreshBusyLabel();
        startCoreAsync();
    }

    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 6));
        mainPanel.setBorder(new EmptyBorder(8, 8, 6, 8));
        mainPanel.setBackground(UIUtil.getPanelBackground());

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setOpaque(false);
        statusLabel.setBorder(new EmptyBorder(0, 2, 0, 0));
        statusPanel.add(statusLabel, BorderLayout.WEST);
        busyLabel.setForeground(UIUtil.getContextHelpForeground());
        statusPanel.add(busyLabel, BorderLayout.EAST);

        tabsBar.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(statusPanel, BorderLayout.NORTH);
        head.add(tabsBar, BorderLayout.SOUTH);

        mainPanel.add(head, BorderLayout.NORTH);
        mainPanel.add(cards, BorderLayout.CENTER);
        setContent(mainPanel);
    }

    // ------------------------------------------------------------- sessions

    // Open a fresh session and make it the visible one.
    // 新建一个会话并切换过去。
    private void openSession() {
        if (sessions.size() >= MAX_SESSIONS) {
            setStatus("最多同时开 " + MAX_SESSIONS + " 个会话，请先关闭一个。");
            return;
        }
        counter++;
        // Distinct suffix on the project hash: the Core keeps one history per session id.
        // 在项目哈希后加后缀：Core 按它隔离每个会话的历史。
        String sessionId = coreService.sessionId() + "#" + counter;
        ChatSessionPanel session = new ChatSessionPanel(project, sessionId, "会话 " + counter, sessionOwner);
        sessions.put(sessionId, session);
        cards.add(session, sessionId);
        selectSession(session);
    }

    private void selectSession(ChatSessionPanel session) {
        if (session == null) {
            return;
        }
        active = session;
        ((CardLayout) cards.getLayout()).show(cards, session.sessionId());
        rebuildTabs();
        session.focusInput();
    }

    private void closeSession(ChatSessionPanel session) {
        if (session == null) {
            return;
        }
        session.dispose();
        sessions.remove(session.sessionId());
        cards.remove(session);
        byTask.values().remove(session);
        if (active == session) {
            active = sessions.isEmpty() ? null : sessions.values().iterator().next();
        }
        if (active == null) {
            openSession();
        } else {
            selectSession(active);
        }
        rebuildTabs();
        refreshBusyLabel();
    }

    // Rebuild the strip so every tab shows the fresh title and running marker.
    // 重建标签条，让每个标签显示最新标题与运行标记。
    private void rebuildTabs() {
        tabsBar.removeAll();
        for (ChatSessionPanel session : sessions.values()) {
            tabsBar.add(new SessionTab(session));
        }
        JButton add = new JButton("+");
        add.setToolTipText("新建会话（可以同时开多个并行跑）");
        add.setMargin(new Insets(2, 8, 2, 8));
        add.setFocusable(false);
        add.addActionListener(e -> openSession());
        tabsBar.add(add);
        tabsBar.revalidate();
        tabsBar.repaint();
    }

    private void refreshBusyLabel() {
        int running = 0;
        for (ChatSessionPanel session : sessions.values()) {
            if (session.isBusy()) {
                running++;
            }
        }
        busyLabel.setText(running <= 1 ? (running == 1 ? "1 个任务运行中" : "") : running + " 个任务并行运行中");
    }

    // ------------------------------------------------------------- core wiring

    private void startCoreAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                coreService.connection();
            } catch (Exception e) {
                LOG.warn("failed to start agent core", e);
                setStatus("启动失败");
                if (active != null) {
                    active.onCoreMessage("chat/error", errorParams("[系统] Agent Core 启动失败：" + e.getMessage()));
                }
            }
        });
    }

    private static JsonObject errorParams(String text) {
        JsonObject error = new JsonObject();
        error.addProperty("code", "CORE_START_FAILED");
        error.addProperty("message", text);
        JsonObject params = new JsonObject();
        params.add("error", error);
        return params;
    }

    // Route every Core notification to the session that owns its taskId.
    // 把每条 Core 通知按 taskId 路由给对应会话。
    private void onCoreMessage(JsonObject message) {
        if (!message.has("method")) {
            return;
        }
        String method = message.get("method").getAsString();
        JsonObject params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params")
                : new JsonObject();

        String taskId = params.has("taskId") && !params.get("taskId").isJsonNull()
                ? params.get("taskId").getAsString()
                : "";
        if (taskId.isEmpty()) {
            // Global events (no task yet): core/hello and friends.
            // 全局事件（还没有任务）：例如 core/hello。
            if ("core/hello".equals(method)) {
                setStatus("就绪");
            }
            return;
        }
        ChatSessionPanel target = byTask.get(taskId);
        if (target == null) {
            // Only possible for frames that arrive before chat/start returns (should not happen).
            // 只可能是 chat/start 返回之前就到达的帧（正常情况下不会发生）。
            LOG.debug("unrouted core event " + method + " task=" + taskId);
            return;
        }
        target.onCoreMessage(method, params);
    }

    // Open the plugin settings page. Model / endpoint / MCP edits reach the running Core through
    // settings/update (pushed before every message), so the Core is only rebuilt when one of the
    // three Core location paths changed — rebuilding wipes every session's conversation history.
    // 打开插件设置页。模型/端点/MCP 的修改由 settings/update 实时下发（每条消息前都会推一次），
    // 只有 Core 可执行文件/目录/解释器这三个路径变了才重建 Core——重建会清空所有会话上下文。
    private void openSettings() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        String exePath = state.coreExePath;
        String homePath = state.coreHomePath;
        String pythonPath = state.pythonPath;

        ShowSettingsUtil.getInstance().showSettingsDialog(project, "com.workreport.settings");
        for (ChatSessionPanel session : sessions.values()) {
            session.refreshModelCombo();
        }

        boolean corePathsChanged = !Objects.equals(exePath, state.coreExePath)
                || !Objects.equals(homePath, state.coreHomePath)
                || !Objects.equals(pythonPath, state.pythonPath);
        if (corePathsChanged) {
            for (ChatSessionPanel session : sessions.values()) {
                if (session.isBusy()) {
                    setStatus("Core 路径已修改，当前有任务在跑，等它结束后重建 Core");
                    return;
                }
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                coreService.restart();
                setStatus("Core 路径已更新，下条消息重建 Core（会话上下文会清空）");
            });
            return;
        }
        // Apply model / endpoint / MCP right away; running sessions keep their history.
        // 立即下发模型/端点/MCP，已打开的会话仍保留上下文。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            coreService.pushSettings();
            setStatus("配置已下发，会话上下文保持不变");
        });
    }

    // ------------------------------------------------------------- external API

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

    // Pre-fill the visible session and send immediately (e.g. the daily-report action).
    // 在可见会话里预填并直接发送（例如日报 action）。
    public void sendPresetMessage(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ChatSessionPanel target = active;
            if (target != null) {
                target.sendPresetMessage(text);
            }
        });
    }

    private void setStatus(String text) {
        ApplicationManager.getApplication().invokeLater(() -> statusLabel.setText(text));
    }

    @Override
    public void dispose() {
        coreService.setMessageHandler(null);
        for (ChatSessionPanel session : sessions.values()) {
            session.dispose();
        }
        sessions.clear();
        byTask.clear();
    }

    // ------------------------------------------------------------- pieces

    // Bridges session callbacks back to the container.
    // 把会话的回调转接到容器。
    private final class SessionOwner implements ChatSessionPanel.Owner {
        @Override
        public void onTaskBound(ChatSessionPanel session, String taskId) {
            byTask.put(taskId, session);
            rebuildTabs();
            refreshBusyLabel();
        }

        @Override
        public void onTaskCleared(ChatSessionPanel session) {
            byTask.values().remove(session);
            rebuildTabs();
            refreshBusyLabel();
        }

        @Override
        public void onBusyChanged(ChatSessionPanel session) {
            rebuildTabs();
            refreshBusyLabel();
        }

        @Override
        public void onTitleChanged(ChatSessionPanel session) {
            rebuildTabs();
        }

        @Override
        public void openSettings() {
            AgentChatPanel.this.openSettings();
        }

        @Override
        public void setStatus(String text) {
            AgentChatPanel.this.setStatus(text);
        }
    }

    // One tab: title, running dot and a close cross.
    // 单个标签：标题、运行圆点、关闭叉。
    private final class SessionTab extends JPanel {

        private static final Color SELECTED_BG = new JBColor(new Color(0xE4EEFF), new Color(0x2F3A4A));
        private static final Color HOVER_BG = new JBColor(new Color(0xEEF2F7), new Color(0x343A44));

        private final ChatSessionPanel session;
        private final JLabel titleLabel = new JLabel();
        private boolean hover;

        SessionTab(ChatSessionPanel session) {
            super(new FlowLayout(FlowLayout.LEFT, 5, 0));
            this.session = session;
            setOpaque(false);
            setBorder(new EmptyBorder(5, 10, 5, 6));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            titleLabel.setFont(fontFor(session == active));
            titleLabel.setForeground(UIUtil.getLabelForeground());
            titleLabel.setText((session.isBusy() ? "● " : "") + session.displayTitle());
            titleLabel.setToolTipText(session.isBusy() ? "该会话正在运行任务" : "点击切换会话");
            // Labels swallow mouse events, so the title forwards clicks to the tab.
            // JLabel 会吞掉鼠标事件，因此标题要把点击转发给标签。
            titleLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectSession(session);
                }
            });

            JLabel close = new JLabel("×");
            close.setToolTipText("关闭会话");
            close.setForeground(UIUtil.getContextHelpForeground());
            close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            close.setBorder(new EmptyBorder(0, 2, 0, 0));
            close.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    closeSession(session);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    close.setForeground(JBColor.RED);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    close.setForeground(UIUtil.getContextHelpForeground());
                }
            });

            add(titleLabel);
            add(close);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectSession(session);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        private Font fontFor(boolean selected) {
            Font base = UIUtil.getLabelFont();
            if (base == null) {
                base = new JLabel().getFont();
            }
            return selected ? base.deriveFont(Font.BOLD) : base;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean selected = session == active;
            if (selected) {
                g2.setColor(SELECTED_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.setColor(JBColor.namedColor("Focus.color", new Color(0x4C7DF0)));
                g2.fillRoundRect(0, getHeight() - 2, getWidth() - 1, 2, 2, 2);
            } else if (hover) {
                g2.setColor(HOVER_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
