package com.workreport.agent.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.UIUtil;
import com.workreport.agent.AgentBundle;
import com.workreport.agent.rpc.AgentCoreService;
import com.workreport.agent.rpc.JsonRpcConnection;
import com.workreport.plugin.settings.ReportSettingsState;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private final JBLabel statusLabel = new JBLabel(AgentBundle.message("panel.starting"));
    private final JBLabel busyLabel = new JBLabel("");
    private final JPanel tabsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel cards = new JPanel(new CardLayout());

    private final Map<String, ChatSessionPanel> sessions = new LinkedHashMap<>();
    // taskId -> session, filled when chat/start answers and cleared when the task ends.
    // taskId -> 会话，在 chat/start 返回时登记，任务结束时解除。
    private final Map<String, ChatSessionPanel> byTask = new ConcurrentHashMap<>();

    private final ChatSessionPanel.Owner sessionOwner = new SessionOwner();
    private ChatSessionPanel active;
    private JBPopup historyPopup;
    private JPanel historyRows;
    private JBLabel historyHeader;
    private JBTextField historySearch;
    private Timer historySearchTimer;
    // Full list kept next to the popup so clearing the search box restores it without a round trip.
    // 弹窗旁边留着完整列表：清空搜索框时不必再请求一次。
    private List<HistoryEntry> historyEntries = new ArrayList<>();

    private JBPopup memoryPopup;
    private JPanel memoryRows;
    private JBLabel memoryHeader;
    private List<PendingMemory> memoryItems = new ArrayList<>();
    // Numbers behind the default "会话 N" titles. They are released on close, so the
    // strip reuses the smallest free number instead of counting up forever.
    // 「会话 N」里的编号：关闭后归还，复用最小空号，不会一直累加。
    private final Set<Integer> usedNumbers = new HashSet<>();
    private final Map<String, Integer> sessionNumbers = new HashMap<>();

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

        // Opaque on purpose: after a tab is removed the strip must erase the pixels it
        // used to occupy, otherwise the empty slot stays visible as a leftover frame.
        // 故意设为不透明：删掉标签后必须擦掉它占过的像素，否则会留下一个空框。
        tabsBar.setOpaque(true);
        tabsBar.setBackground(UIUtil.getPanelBackground());
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
            setStatus(AgentBundle.message("session.limit", MAX_SESSIONS));
            return;
        }
        int number = nextSessionNumber();
        // A unique suffix per tab (not a counter): a new tab must never inherit the
        // history of an old conversation that happens to reuse the same index.
        // 每个标签用唯一后缀而不是计数器：新标签不能意外继承旧会话的历史。
        String sessionId = coreService.sessionId() + "#"
                + UUID.randomUUID().toString().substring(0, 8);
        ChatSessionPanel session = new ChatSessionPanel(project, sessionId, "会话 " + number, sessionOwner);
        sessions.put(sessionId, session);
        sessionNumbers.put(sessionId, number);
        cards.add(session, sessionId);
        selectSession(session);
    }

    // Smallest number not used by any open tab (1, 2, 3 …).
    // 取当前没被占用的最小正整数。
    private int nextSessionNumber() {
        int number = 1;
        while (usedNumbers.contains(number)) {
            number++;
        }
        usedNumbers.add(number);
        return number;
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
        // Remember the slot first: the tab that follows should move into it, so the
        // strip closes the gap instead of keeping an empty frame.
        // 先记住位置：关闭后由后面的标签补上，标签条收紧而不是留一个空框。
        List<String> order = new ArrayList<>(sessions.keySet());
        int slot = order.indexOf(session.sessionId());

        session.dispose();
        sessions.remove(session.sessionId());
        cards.remove(session);
        byTask.values().remove(session);
        // Hand the number back so the next "会话 N" reuses it (history tabs have none).
        // 归还编号，下个新标签优先用最小空号；历史标签没有编号。
        Integer released = sessionNumbers.remove(session.sessionId());
        if (released != null) {
            usedNumbers.remove(released);
        }

        if (active == session) {
            String next = null;
            if (slot >= 0) {
                if (slot < order.size() - 1) {
                    next = order.get(slot + 1);
                } else if (slot > 0) {
                    next = order.get(slot - 1);
                }
            }
            active = next != null ? sessions.get(next) : null;
        }
        if (active == null) {
            openSession();
        } else {
            selectSession(active);
        }
        cards.revalidate();
        cards.repaint();
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
        add.setToolTipText(AgentBundle.message("tab.new.tip"));
        add.setMargin(new Insets(2, 8, 2, 8));
        add.setFocusable(false);
        add.addActionListener(e -> openSession());
        tabsBar.add(add);

        JButton history = new JButton(AgentBundle.message("tab.history"));
        history.setToolTipText(AgentBundle.message("tab.history.tip"));
        history.setMargin(new Insets(2, 8, 2, 8));
        history.setFocusable(false);
        history.addActionListener(e -> showHistory(history));
        tabsBar.add(history);

        JButton memory = new JButton(AgentBundle.message("tab.memory"));
        memory.setToolTipText(AgentBundle.message("tab.memory.tip"));
        memory.setMargin(new Insets(2, 8, 2, 8));
        memory.setFocusable(false);
        memory.addActionListener(e -> showMemories(memory));
        tabsBar.add(memory);

        tabsBar.revalidate();
        // The strip may lose a whole wrapped row; the parent has to re-measure the height
        // as well, otherwise the freed space stays behind as a blank band.
        // 标签条可能少一整行，父容器也要重新量高度，否则空出来的位置会留成一条空白。
        if (tabsBar.getParent() != null) {
            tabsBar.getParent().revalidate();
        }
        tabsBar.repaint();
    }

    private void refreshBusyLabel() {
        int running = 0;
        for (ChatSessionPanel session : sessions.values()) {
            if (session.isBusy()) {
                running++;
            }
        }
        busyLabel.setText(running <= 1
                ? (running == 1 ? AgentBundle.message("busy.one") : "")
                : AgentBundle.message("busy.many", running));
    }

    // ------------------------------------------------------------- history

    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int MAX_HISTORY_ROWS = 30;

    // One conversation from the Core's session store, decoded from session/list.
    // Search hits (session/search) reuse it and carry a rendered snippet instead of a message count.
    // Core 会话存储里的一条历史会话（来自 session/list）。
    // 搜索命中（session/search）复用同一类型，只把消息条数换成渲染好的片段。
    private static final class HistoryEntry {
        private final String id;
        private final String title;
        private final String preview;
        private final long updatedAt;
        private final int messageCount;
        private final String snippetHtml;

        HistoryEntry(JsonObject json) {
            this(json, null);
        }

        private HistoryEntry(JsonObject json, String snippetHtml) {
            this.id = string(json, "id");
            this.title = string(json, "title");
            this.preview = string(json, "preview");
            this.updatedAt = json.has("updatedAt") && !json.get("updatedAt").isJsonNull()
                    ? json.get("updatedAt").getAsLong() : 0L;
            this.messageCount = json.has("messageCount") ? json.get("messageCount").getAsInt() : 0;
            this.snippetHtml = snippetHtml;
        }

        static HistoryEntry fromSearch(JsonObject json) {
            return new HistoryEntry(json, highlightSnippet(string(json, "snippet")));
        }

        String displayTitle() {
            return title.isBlank() ? AgentBundle.message("history.untitled") : title;
        }

        String tooltip() {
            if (snippetHtml != null && !snippetHtml.isEmpty()) {
                return stripTags(snippetHtml);
            }
            return preview.isEmpty() ? displayTitle() : preview;
        }

        boolean hasSnippet() {
            return snippetHtml != null && !snippetHtml.isEmpty();
        }
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static Map<String, Object> params(String key, String value) {
        Map<String, Object> params = new HashMap<>();
        params.put(key, value);
        return params;
    }

    // session/search wraps hits in <mark>; Swing labels want <b> and everything else escaped.
    // session/search 用 <mark> 标命中片段，Swing 标签要 <b>，其余字符必须转义。
    private static String highlightSnippet(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<html>");
        int index = 0;
        while (index < raw.length()) {
            int open = raw.indexOf("<mark>", index);
            int close = open < 0 ? -1 : raw.indexOf("</mark>", open);
            if (open < 0 || close < 0) {
                out.append(escapeHtml(raw.substring(index)));
                break;
            }
            out.append(escapeHtml(raw.substring(index, open)));
            out.append("<b>").append(escapeHtml(raw.substring(open + 6, close))).append("</b>");
            index = close + 7;
        }
        return out.append("</html>").toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", " ").replace("\n", " ");
    }

    private static String stripTags(String html) {
        return html.replace("<html>", "").replace("</html>", "")
                .replace("<b>", "").replace("</b>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    // One request/response round trip against the Core, unwrapped to its result object.
    // 向 Core 发一次请求并把 result 取出来；Core 返回 error 时抛异常。
    private JsonObject callCore(String method, Map<String, Object> payload) throws Exception {
        JsonRpcConnection conn = coreService.connection();
        JsonObject response = conn.request(method, payload == null ? new HashMap<>() : payload)
                .get(20, TimeUnit.SECONDS);
        if (response.has("error") && response.get("error").isJsonObject()) {
            throw new IllegalStateException(string(response.getAsJsonObject("error"), "message"));
        }
        return response.has("result") && response.get("result").isJsonObject()
                ? response.getAsJsonObject("result") : new JsonObject();
    }

    // Ask the Core for the stored conversations, then pop the list under the button.
    // 向 Core 要历史会话列表，再在按钮下方弹出。
    private void showHistory(Component anchor) {
        cancelMemoryPopup();
        setStatus(AgentBundle.message("history.loading"));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<HistoryEntry> entries = new ArrayList<>();
            try {
                JsonObject result = callCore("session/list", new HashMap<>());
                JsonArray array = result.has("sessions") && result.get("sessions").isJsonArray()
                        ? result.getAsJsonArray("sessions") : new JsonArray();
                for (JsonElement element : array) {
                    if (element.isJsonObject()) {
                        entries.add(new HistoryEntry(element.getAsJsonObject()));
                    }
                }
            } catch (Exception e) {
                LOG.warn("failed to list sessions", e);
                setStatus(AgentBundle.message("history.loadFailed", e.getMessage()));
                return;
            }
            setStatus(AgentBundle.message("status.ready"));
            ApplicationManager.getApplication().invokeLater(() -> showHistoryPopup(anchor, entries));
        });
    }

    private void showHistoryPopup(Component anchor, List<HistoryEntry> entries) {
        cancelHistoryPopup();
        historyEntries = new ArrayList<>(entries);

        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setBorder(new EmptyBorder(6, 4, 4, 4));

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        historySearch = new JBTextField();
        historySearch.getEmptyText().setText(AgentBundle.message("history.search.placeholder"));
        historySearch.setToolTipText(AgentBundle.message("history.search.tip"));
        historySearch.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                scheduleHistorySearch();
            }
        });
        head.add(historySearch, BorderLayout.NORTH);
        historyHeader = new JBLabel(historyHeaderText(historyEntries, ""));
        historyHeader.setForeground(UIUtil.getContextHelpForeground());
        historyHeader.setBorder(new EmptyBorder(0, 6, 0, 0));
        head.add(historyHeader, BorderLayout.SOUTH);
        content.add(head, BorderLayout.NORTH);

        historyRows = new JPanel();
        historyRows.setLayout(new BoxLayout(historyRows, BoxLayout.Y_AXIS));
        historyRows.setOpaque(false);
        fillHistoryRows(historyEntries, "");

        JBScrollPane scroll = new JBScrollPane(historyRows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(400,
                Math.min(400, Math.max(80, historyEntries.size() * 54 + 20))));
        content.add(scroll, BorderLayout.CENTER);

        historyPopup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(false)
                .setTitle("历史会话")
                .createPopup();
        historyPopup.showUnderneathOf(anchor);
        historySearch.requestFocusInWindow();
    }

    private static String historyHeaderText(List<HistoryEntry> entries, String query) {
        if (!query.isEmpty()) {
            return entries.isEmpty()
                    ? AgentBundle.message("history.noHits", query)
                    : AgentBundle.message("history.hits", query, entries.size());
        }
        return entries.isEmpty()
                ? AgentBundle.message("history.header.empty")
                : AgentBundle.message("history.header");
    }

    // Fill the row container; query is only used to pick the empty-state wording.
    // 填充行容器；query 只决定空状态文案。
    private void fillHistoryRows(List<HistoryEntry> entries, String query) {
        if (historyRows == null) {
            return;
        }
        historyRows.removeAll();
        if (entries.isEmpty()) {
            historyRows.add(new JBLabel(query.isEmpty()
                    ? AgentBundle.message("history.empty")
                    : AgentBundle.message("history.empty.search")));
        } else {
            int shown = 0;
            for (HistoryEntry entry : entries) {
                if (shown >= MAX_HISTORY_ROWS) {
                    break;
                }
                historyRows.add(new HistoryRow(entry));
                shown++;
            }
        }
        historyRows.revalidate();
        historyRows.repaint();
    }

    // Coalesce keystrokes: one request 300 ms after typing stops.
    // 连续敲键只发一次请求：停下来 300 ms 后再搜。
    private void scheduleHistorySearch() {
        if (historySearchTimer != null) {
            historySearchTimer.stop();
        }
        historySearchTimer = new Timer(300, e -> runHistorySearch(
                historySearch == null ? "" : historySearch.getText().trim()));
        historySearchTimer.setRepeats(false);
        historySearchTimer.start();
    }

    private void runHistorySearch(String query) {
        if (query.isEmpty()) {
            fillHistoryRows(historyEntries, "");
            if (historyHeader != null) {
                historyHeader.setText(historyHeaderText(historyEntries, ""));
            }
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<HistoryEntry> hits = new ArrayList<>();
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("query", query);
                payload.put("limit", MAX_HISTORY_ROWS);
                JsonObject result = callCore("session/search", payload);
                JsonArray array = result.has("results") && result.get("results").isJsonArray()
                        ? result.getAsJsonArray("results") : new JsonArray();
                for (JsonElement element : array) {
                    if (element.isJsonObject()) {
                        hits.add(HistoryEntry.fromSearch(element.getAsJsonObject()));
                    }
                }
            } catch (Exception e) {
                LOG.warn("history search failed", e);
                setStatus(AgentBundle.message("history.searchFailed", e.getMessage()));
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (historyPopup == null || !historyPopup.isVisible()
                        || historySearch == null || !query.equals(historySearch.getText().trim())) {
                    return;
                }
                if (historyHeader != null) {
                    historyHeader.setText(historyHeaderText(hits, query));
                }
                fillHistoryRows(hits, query);
            });
        });
    }

    private void cancelHistoryPopup() {
        if (historySearchTimer != null) {
            historySearchTimer.stop();
        }
        if (historyPopup != null && historyPopup.isVisible()) {
            historyPopup.cancel();
        }
    }

    // ------------------------------------------------------------- memory

    private static final int MAX_MEMORY_ROWS = 20;

    // One review candidate from .agent/memories/_pending (memory/pending).
    // 待确认的记忆候选（memory/pending 返回的一条）。
    private static final class PendingMemory {
        private final String id;
        private final String title;
        private final String content;
        private final String category;
        private final String source;

        PendingMemory(JsonObject json) {
            this.id = string(json, "id");
            this.title = string(json, "title");
            this.content = string(json, "content");
            this.category = string(json, "category");
            this.source = string(json, "source");
        }

        String displayTitle() {
            return title.isBlank() ? content : title;
        }

        // "correction" means the user fixed an earlier statement; anything else is a preference.
        // correction 表示纠正了之前的说法，其余都是偏好类候选。
        String sourceLabel() {
            return "correction".equals(source) ? "纠正" : "偏好";
        }
    }

    // Load the candidates, then pop the list under the button.
    // 先拉候选，再在按钮下方弹出列表。
    private void showMemories(Component anchor) {
        cancelHistoryPopup();
        setStatus(AgentBundle.message("memory.loading"));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<PendingMemory> items = new ArrayList<>();
            try {
                JsonObject result = callCore("memory/pending", new HashMap<>());
                JsonArray array = result.has("items") && result.get("items").isJsonArray()
                        ? result.getAsJsonArray("items") : new JsonArray();
                for (JsonElement element : array) {
                    if (element.isJsonObject()) {
                        items.add(new PendingMemory(element.getAsJsonObject()));
                    }
                }
            } catch (Exception e) {
                LOG.warn("failed to load pending memories", e);
                setStatus(AgentBundle.message("memory.loadFailed", e.getMessage()));
                return;
            }
            setStatus(AgentBundle.message("status.ready"));
            ApplicationManager.getApplication().invokeLater(() -> showMemoryPopup(anchor, items));
        });
    }

    private void showMemoryPopup(Component anchor, List<PendingMemory> items) {
        cancelMemoryPopup();
        memoryItems = new ArrayList<>(items);

        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setBorder(new EmptyBorder(6, 4, 4, 4));
        memoryHeader = new JBLabel(memoryHeaderText(memoryItems));
        memoryHeader.setForeground(UIUtil.getContextHelpForeground());
        memoryHeader.setBorder(new EmptyBorder(0, 6, 4, 0));
        content.add(memoryHeader, BorderLayout.NORTH);

        memoryRows = new JPanel();
        memoryRows.setLayout(new BoxLayout(memoryRows, BoxLayout.Y_AXIS));
        memoryRows.setOpaque(false);
        fillMemoryRows();

        JBScrollPane scroll = new JBScrollPane(memoryRows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(420,
                Math.min(420, Math.max(110, memoryItems.size() * 92 + 30))));
        content.add(scroll, BorderLayout.CENTER);

        memoryPopup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(false)
                .setTitle("记忆候选")
                .createPopup();
        memoryPopup.showUnderneathOf(anchor);
    }

    private static String memoryHeaderText(List<PendingMemory> items) {
        return items.isEmpty()
                ? AgentBundle.message("memory.header.empty")
                : AgentBundle.message("memory.header", items.size());
    }

    private void fillMemoryRows() {
        if (memoryRows == null) {
            return;
        }
        memoryRows.removeAll();
        if (memoryItems.isEmpty()) {
            memoryRows.add(new JBLabel(AgentBundle.message("memory.empty")));
        } else {
            int shown = 0;
            for (PendingMemory item : memoryItems) {
                if (shown >= MAX_MEMORY_ROWS) {
                    break;
                }
                memoryRows.add(new MemoryRow(item));
                shown++;
            }
        }
        memoryRows.revalidate();
        memoryRows.repaint();
    }

    // approve=true → memory/promote (optionally with edited text), false → memory/discard.
    // approve=true 走 memory/promote（可带改过的文字），false 走 memory/discard。
    private void resolveMemory(PendingMemory item, boolean approve, String content) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String message;
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("id", item.id);
                if (approve) {
                    if (content != null) {
                        payload.put("content", content);
                    }
                    JsonObject result = callCore("memory/promote", payload);
                    String title = string(result, "title");
                    JsonObject duplicate = result.has("duplicate") && result.get("duplicate").isJsonObject()
                            ? result.getAsJsonObject("duplicate") : null;
                    String label = title.isBlank() ? item.displayTitle() : title;
                    message = duplicate == null
                            ? AgentBundle.message("memory.remembered", label)
                            : AgentBundle.message("memory.remembered.duplicate", label,
                                    string(duplicate, "id"));
                } else {
                    callCore("memory/discard", payload);
                    message = AgentBundle.message("memory.ignored", item.displayTitle());
                }
            } catch (Exception e) {
                LOG.warn("memory operation failed", e);
                setStatus(AgentBundle.message("memory.opFailed", e.getMessage()));
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (memoryItems.remove(item)) {
                    fillMemoryRows();
                    if (memoryHeader != null) {
                        memoryHeader.setText(memoryHeaderText(memoryItems));
                    }
                }
            });
            setStatus(message);
        });
    }

    private void cancelMemoryPopup() {
        if (memoryPopup != null && memoryPopup.isVisible()) {
            memoryPopup.cancel();
        }
    }

    // One candidate: title, the sentence, its origin, then 确认 / 编辑 / 忽略.
    // 一条候选：标题、原文、来源与三个操作。
    private final class MemoryRow extends JPanel {

        MemoryRow(PendingMemory item) {
            super(new BorderLayout(6, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(6, 8, 6, 6));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
            setToolTipText(item.content);

            Font base = UIUtil.getLabelFont() != null ? UIUtil.getLabelFont() : new JLabel().getFont();
            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            JLabel title = new JLabel(item.displayTitle());
            title.setFont(base.deriveFont(Font.BOLD));
            JLabel content = new JLabel(item.content);
            content.setFont(base.deriveFont(base.getSize2D() - 1f));
            JLabel meta = new JLabel(item.sourceLabel() + " · " + item.category);
            meta.setFont(base.deriveFont(base.getSize2D() - 1f));
            meta.setForeground(UIUtil.getContextHelpForeground());
            text.add(title);
            text.add(content);
            text.add(meta);
            add(text, BorderLayout.CENTER);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            actions.setOpaque(false);
            JButton confirm = new JButton(AgentBundle.message("memory.confirm"));
            confirm.setToolTipText(AgentBundle.message("memory.confirm.tip"));
            confirm.setMargin(new Insets(2, 8, 2, 8));
            confirm.setFocusable(false);
            confirm.addActionListener(e -> resolveMemory(item, true, null));
            JButton edit = new JButton(AgentBundle.message("memory.edit"));
            edit.setToolTipText(AgentBundle.message("memory.edit.tip"));
            edit.setMargin(new Insets(2, 8, 2, 8));
            edit.setFocusable(false);
            edit.addActionListener(e -> {
                String edited = Messages.showMultilineInputDialog(project,
                        AgentBundle.message("memory.edit.message"),
                        AgentBundle.message("memory.edit.title"), item.content, null, null);
                if (edited != null && !edited.trim().isEmpty()) {
                    resolveMemory(item, true, edited.trim());
                }
            });
            JButton ignore = new JButton(AgentBundle.message("memory.ignore"));
            ignore.setToolTipText(AgentBundle.message("memory.ignore.tip"));
            ignore.setMargin(new Insets(2, 8, 2, 8));
            ignore.setFocusable(false);
            ignore.addActionListener(e -> resolveMemory(item, false, null));
            actions.add(confirm);
            actions.add(edit);
            actions.add(ignore);
            add(actions, BorderLayout.EAST);
        }
    }

    // Reopen a stored conversation as a tab, replaying its bubbles first.
    // 把历史会话打开成标签，先把旧气泡回放出来。
    private void openHistory(HistoryEntry entry) {
        cancelHistoryPopup();
        ChatSessionPanel existing = sessions.get(entry.id);
        if (existing != null) {
            selectSession(existing);
            return;
        }
        if (sessions.size() >= MAX_SESSIONS) {
            setStatus(AgentBundle.message("session.limit", MAX_SESSIONS));
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                JsonRpcConnection conn = coreService.connection();
                JsonObject response = conn.request("session/get", params("sessionId", entry.id))
                        .get(20, TimeUnit.SECONDS);
                JsonObject result = response.has("result") && response.get("result").isJsonObject()
                        ? response.getAsJsonObject("result") : new JsonObject();
                String title = string(result, "title");
                List<ChatSessionPanel.HistoryMessage> messages = parseHistoryMessages(result);
                List<TodoStrip.Item> todos = ChatSessionPanel.parseTodos(result);
                ApplicationManager.getApplication().invokeLater(
                        () -> addHistoryTab(entry, title, messages, todos));
            } catch (Exception e) {
                LOG.warn("failed to load session " + entry.id, e);
                setStatus(AgentBundle.message("history.openFailed", e.getMessage()));
            }
        });
    }

    private void addHistoryTab(HistoryEntry entry, String title,
                               List<ChatSessionPanel.HistoryMessage> messages,
                               List<TodoStrip.Item> todos) {
        if (sessions.containsKey(entry.id)) {
            selectSession(sessions.get(entry.id));
            return;
        }
        ChatSessionPanel session = new ChatSessionPanel(project, entry.id,
                entry.displayTitle(), sessionOwner);
        session.applyHistory(title.isBlank() ? entry.displayTitle() : title, messages, todos);
        sessions.put(entry.id, session);
        cards.add(session, entry.id);
        selectSession(session);
        setStatus(AgentBundle.message("history.opened", session.displayTitle()));
    }

    private void deleteHistory(HistoryEntry entry) {
        cancelHistoryPopup();
        int answer = Messages.showYesNoDialog(project,
                AgentBundle.message("history.delete.message", entry.displayTitle()),
                AgentBundle.message("history.delete.title"),
                AgentBundle.message("common.delete"), AgentBundle.message("common.cancel"),
                Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long freed;
            try {
                JsonRpcConnection conn = coreService.connection();
                JsonObject response = conn.request("session/delete", params("sessionId", entry.id))
                        .get(20, TimeUnit.SECONDS);
                JsonObject result = response.has("result") && response.get("result").isJsonObject()
                        ? response.getAsJsonObject("result") : new JsonObject();
                freed = result.has("freedBytes") && !result.get("freedBytes").isJsonNull()
                        ? result.get("freedBytes").getAsLong() : 0L;
            } catch (Exception e) {
                LOG.warn("failed to delete session " + entry.id, e);
                setStatus(AgentBundle.message("history.deleteFailed", e.getMessage()));
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                ChatSessionPanel open = sessions.get(entry.id);
                if (open != null) {
                    closeSession(open);
                }
                // Say how much disk the delete actually reclaimed.
                // 告诉用户这次删除真正释放了多少磁盘。
                setStatus(freed > 0
                        ? AgentBundle.message("history.deletedWithFreed", entry.displayTitle(), formatSize(freed))
                        : AgentBundle.message("history.deleted", entry.displayTitle()));
            });
        });
    }

    // Human-readable byte count for the status line ("640 B" / "12.3 KB" / "1.4 MB").
    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static List<ChatSessionPanel.HistoryMessage> parseHistoryMessages(JsonObject result) {
        List<ChatSessionPanel.HistoryMessage> messages = new ArrayList<>();
        if (!result.has("messages") || !result.get("messages").isJsonArray()) {
            return messages;
        }
        for (JsonElement element : result.getAsJsonArray("messages")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            String text = string(message, "content");
            if (text.isBlank()) {
                continue;
            }
            messages.add(new ChatSessionPanel.HistoryMessage(
                    "user".equals(string(message, "role")), text));
        }
        return messages;
    }

    private static String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        return HISTORY_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    // One history entry: title + time on the left, a delete cross on the right.
    // 单条历史记录：左边标题与时间，右边删除叉。
    private final class HistoryRow extends JPanel {

        private boolean hover;

        HistoryRow(HistoryEntry entry) {
            super(new BorderLayout(6, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(5, 8, 5, 6));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
            setToolTipText(entry.tooltip());

            Font base = UIUtil.getLabelFont() != null ? UIUtil.getLabelFont() : new JLabel().getFont();
            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            JLabel title = new JLabel(entry.displayTitle());
            title.setFont(base.deriveFont(Font.BOLD));
            text.add(title);
            if (entry.hasSnippet()) {
                // Search hit: the snippet already boldens the matched words.
                // 搜索命中：片段里已经把匹配词加粗了。
                JLabel snippet = new JLabel(entry.snippetHtml);
                snippet.setFont(base.deriveFont(base.getSize2D() - 1f));
                snippet.setForeground(UIUtil.getContextHelpForeground());
                text.add(snippet);
            } else {
                JLabel meta = new JLabel(formatTime(entry.updatedAt) + " · " + entry.messageCount + " 条消息");
                meta.setFont(base.deriveFont(base.getSize2D() - 1f));
                meta.setForeground(UIUtil.getContextHelpForeground());
                text.add(meta);
            }
            add(text, BorderLayout.CENTER);

            JLabel remove = new JLabel("×");
            remove.setToolTipText(AgentBundle.message("history.delete.tip"));
            remove.setForeground(UIUtil.getContextHelpForeground());
            remove.setBorder(new EmptyBorder(0, 4, 0, 2));
            remove.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    e.consume();
                    deleteHistory(entry);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    remove.setForeground(JBColor.RED);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    remove.setForeground(UIUtil.getContextHelpForeground());
                }
            });
            add(remove, BorderLayout.EAST);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openHistory(entry);
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

        @Override
        protected void paintComponent(Graphics g) {
            if (hover) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new JBColor(new Color(0xEAF1FB), new Color(0x39404A)));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    // ------------------------------------------------------------- core wiring

    private void startCoreAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                coreService.connection();
            } catch (Exception e) {
                LOG.warn("failed to start agent core", e);
                setStatus(AgentBundle.message("status.startFailed"));
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
                setStatus(AgentBundle.message("status.ready"));
            } else if ("memory/pendingChanged".equals(method)) {
                int count = params.has("count") ? params.get("count").getAsInt() : 0;
                if (count > 0) {
                    setStatus(AgentBundle.message("status.newMemories", count));
                }
            } else if ("session/indexProgress".equals(method)) {
                // One-time migration; say so instead of leaving a stale "就绪".
                // 首次建索引是后台迁移，提一下进度，避免一直显示旧的「就绪」。
                if (!params.has("finished") || !params.get("finished").getAsBoolean()) {
                    setStatus(AgentBundle.message("status.indexing"));
                } else {
                    setStatus(AgentBundle.message("status.ready"));
                }
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
                    setStatus(AgentBundle.message("core.runningRebuild"));
                    return;
                }
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                coreService.restart();
                setStatus(AgentBundle.message("core.rebuildNext"));
            });
            return;
        }
        // Apply model / endpoint / MCP right away; running sessions keep their history.
        // 立即下发模型/端点/MCP，已打开的会话仍保留上下文。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            coreService.pushSettings();
            setStatus(AgentBundle.message("core.pushed"));
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
            titleLabel.setToolTipText(session.isBusy()
                    ? AgentBundle.message("tab.running.tip")
                    : AgentBundle.message("tab.switch.tip"));
            // Labels swallow mouse events, so the title forwards clicks to the tab.
            // JLabel 会吞掉鼠标事件，因此标题要把点击转发给标签。
            titleLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectSession(session);
                }
            });

            JLabel close = new JLabel("×");
            close.setToolTipText(AgentBundle.message("tab.close.tip"));
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
