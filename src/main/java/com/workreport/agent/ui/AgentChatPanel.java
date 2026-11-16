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
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.picker.PickerPopup;
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

    private final SessionTabsBar tabsBar = new SessionTabsBar();
    private final JPanel cards = new JPanel(new CardLayout());

    private final Map<String, ChatSessionPanel> sessions = new LinkedHashMap<>();
    // taskId -> session, filled when chat/start answers and cleared when the task ends.
    // taskId -> 会话，在 chat/start 返回时登记，任务结束时解除。
    private final Map<String, ChatSessionPanel> byTask = new ConcurrentHashMap<>();

    private final ChatSessionPanel.Owner sessionOwner = new SessionOwner();
    private ChatSessionPanel active;
    private JBPopup historyPopup;
    private PickerPopup historyPicker;
    // Full list kept next to the popup so clearing the search box restores it without a round trip.
    // 弹窗旁边留着完整列表：清空搜索框时不必再请求一次。
    private List<HistoryEntry> historyEntries = new ArrayList<>();

    private JBPopup memoryPopup;
    private PickerPopup memoryPicker;
    private List<PendingMemory> memoryItems = new ArrayList<>();
    // Inline yellow hints per candidate ("similar to memory #x"), keyed by candidate id.
    // 按候选 id 存的行内黄色提示（「与已有记忆 #x 相似」），不弹对话框。
    private final Map<String, String> memoryWarnings = new HashMap<>();
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
        startCoreAsync();
    }

    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, UiMetrics.s()));
        mainPanel.setBorder(new EmptyBorder(UiMetrics.m(), UiMetrics.m(), UiMetrics.s(), UiMetrics.m()));
        mainPanel.setBackground(UIUtil.getPanelBackground());

        // The strip is the only thing left on top: the status text moved into each session,
        // right above the composer, so the transcript starts higher.
        // 顶部只剩标签条：状态文本已下移到每个会话的输入区上方，对话区因此更靠上。
        tabsBar.setListener(new SessionTabsBar.Listener() {
            @Override
            public void onSelect(int index) {
                selectSession(sessionAt(index));
            }

            @Override
            public void onClose(int index) {
                closeSession(sessionAt(index));
            }

            @Override
            public void onNewSession() {
                openSession();
            }

            @Override
            public void onHistory(Component anchor) {
                showHistory(anchor);
            }

            @Override
            public void onMemory(Component anchor) {
                showMemories(anchor);
            }

            @Override
            public void onSettings() {
                openSettings();
            }
        });

        mainPanel.add(tabsBar, BorderLayout.NORTH);
        mainPanel.add(cards, BorderLayout.CENTER);
        setContent(mainPanel);
    }

    // Tab index -> session, in insertion order (the same order the strip was filled with).
    // 标签下标 -> 会话，按插入顺序（与填充标签条的顺序一致）。
    private ChatSessionPanel sessionAt(int index) {
        List<ChatSessionPanel> ordered = new ArrayList<>(sessions.values());
        return index >= 0 && index < ordered.size() ? ordered.get(index) : null;
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
    }

    // Feed the strip with the current titles, running flags and selection.
    // 把当前标题、运行状态与选中项交给标签条，由它自绘。
    private void rebuildTabs() {
        List<ChatSessionPanel> ordered = new ArrayList<>(sessions.values());
        List<SessionTabsBar.Tab> tabs = new ArrayList<>(ordered.size());
        int selected = 0;
        for (int i = 0; i < ordered.size(); i++) {
            ChatSessionPanel session = ordered.get(i);
            tabs.add(new SessionTabsBar.Tab(session.displayTitle(), session.isBusy()));
            if (session == active) {
                selected = i;
            }
        }
        tabsBar.setTabs(tabs, selected);
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

        // One shared picker for search, hover actions and keyboard navigation.
        // 搜索、hover 动作与键盘导航全部复用同一个列表弹窗。
        PickerPopup picker = new PickerPopup(400,
                AgentBundle.message("history.search.placeholder"),
                AgentBundle.message("history.empty"),
                PickerPopup.defaultFooter());
        historyPicker = picker;
        picker.setHeader(historyHeaderText(historyEntries, ""));
        picker.setPickHandler(row -> {
            if (row.payload instanceof HistoryEntry entry) {
                openHistory(entry);
            }
        });
        picker.setSearchHandler(this::runHistorySearch);
        picker.setRows(historyRows(historyEntries));

        historyPopup = picker.createPopup();
        historyPopup.showUnderneathOf(anchor);
        picker.focusSearch();
    }

    private List<PickerPopup.Row> historyRows(List<HistoryEntry> entries) {
        List<PickerPopup.Row> rows = new ArrayList<>();
        int shown = 0;
        for (HistoryEntry entry : entries) {
            if (shown >= MAX_HISTORY_ROWS) {
                break;
            }
            // Search hits show the matched snippet, the plain list shows time and size.
            // 搜索命中显示匹配片段，普通列表显示时间与条数。
            String subtitle = entry.hasSnippet()
                    ? stripTags(entry.snippetHtml)
                    : formatTime(entry.updatedAt) + " · " + entry.messageCount + " 条消息";
            rows.add(new PickerPopup.Row(entry.displayTitle(), subtitle, "", "",
                    entry.tooltip(), entry,
                    List.of(new PickerPopup.RowAction(AgentBundle.message("common.delete"), true,
                            row -> {
                                if (row.payload instanceof HistoryEntry target) {
                                    deleteHistory(target);
                                }
                            }))));
            shown++;
        }
        return rows;
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

    private void runHistorySearch(String query) {
        PickerPopup picker = historyPicker;
        if (picker == null) {
            return;
        }
        if (query.isEmpty()) {
            picker.setHeader(historyHeaderText(historyEntries, ""));
            picker.setRows(historyRows(historyEntries));
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
                if (historyPicker != picker || historyPopup == null || !historyPopup.isVisible()
                        || !query.equals(picker.query())) {
                    return;
                }
                picker.setHeader(historyHeaderText(hits, query));
                picker.setRows(historyRows(hits));
            });
        });
    }

    private void cancelHistoryPopup() {
        if (historyPopup != null && historyPopup.isVisible()) {
            historyPopup.cancel();
        }
        historyPicker = null;
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
        memoryWarnings.clear();
        tabsBar.setPendingMemories(memoryItems.size());

        // Same picker as history: search on top, one painted row per candidate.
        // Filtering is local (the candidates already sit in memory), so typing costs nothing.
        // 与历史共用同一个弹窗：顶部搜索、每条候选一行自绘；候选已在内存里，筛选完全本地完成。
        PickerPopup picker = new PickerPopup(420,
                AgentBundle.message("ui.picker.memory.search"),
                AgentBundle.message("memory.empty"),
                PickerPopup.defaultFooter());
        memoryPicker = picker;
        picker.setPickHandler(row -> {
            if (row.payload instanceof PendingMemory item) {
                resolveMemory(item, true, null);
            }
        });
        picker.setSearchHandler(query -> refreshMemoryRows());
        picker.setRows(memoryRows());
        picker.setHeader(memoryHeaderText(memoryItems, ""));

        memoryPopup = picker.createPopup();
        memoryPopup.showUnderneathOf(anchor);
        picker.focusSearch();
    }

    private static String memoryHeaderText(List<PendingMemory> items, String query) {
        if (!query.isEmpty()) {
            return AgentBundle.message("ui.picker.memory.hits", query);
        }
        return items.isEmpty()
                ? AgentBundle.message("memory.header.empty")
                : AgentBundle.message("memory.header", items.size());
    }

    private List<PickerPopup.Row> memoryRows() {
        List<PickerPopup.Row> rows = new ArrayList<>();
        String query = memoryPicker == null ? "" : memoryPicker.query().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (PendingMemory item : memoryItems) {
            if (shown >= MAX_MEMORY_ROWS) {
                break;
            }
            if (!query.isEmpty() && !matchesMemory(item, query)) {
                continue;
            }
            // Confirm sits rightmost (the primary action), ignore leftmost (destructive one).
            // 确认放在最右（主操作），忽略放在最左（破坏性操作）。
            rows.add(new PickerPopup.Row(item.displayTitle(), item.content,
                    item.sourceLabel() + " · " + item.category,
                    memoryWarnings.getOrDefault(item.id, ""),
                    item.content, item,
                    List.of(
                            new PickerPopup.RowAction(AgentBundle.message("memory.confirm"), false,
                                    row -> {
                                        if (row.payload instanceof PendingMemory target) {
                                            resolveMemory(target, true, null);
                                        }
                                    }),
                            new PickerPopup.RowAction(AgentBundle.message("memory.edit"), false,
                                    row -> {
                                        if (row.payload instanceof PendingMemory target) {
                                            editMemory(target);
                                        }
                                    }),
                            new PickerPopup.RowAction(AgentBundle.message("memory.ignore"), true,
                                    row -> {
                                        if (row.payload instanceof PendingMemory target) {
                                            resolveMemory(target, false, null);
                                        }
                                    }))));
            shown++;
        }
        return rows;
    }

    private static boolean matchesMemory(PendingMemory item, String query) {
        return item.title.toLowerCase(Locale.ROOT).contains(query)
                || item.content.toLowerCase(Locale.ROOT).contains(query)
                || item.category.toLowerCase(Locale.ROOT).contains(query);
    }

    // Rebuild rows after an approve/ignore, keeping the search box and header in sync.
    // 确认/忽略后重建行，同时刷新表头与搜索状态。
    private void refreshMemoryRows() {
        PickerPopup picker = memoryPicker;
        if (picker == null) {
            return;
        }
        picker.setHeader(memoryHeaderText(memoryItems, picker.query()));
        picker.setRows(memoryRows());
    }

    // Editing still needs a text box; everything else stays inline in the row.
    // 改写文字仍然需要输入框，其余操作全部留在行内完成。
    private void editMemory(PendingMemory item) {
        String edited = Messages.showMultilineInputDialog(project,
                AgentBundle.message("memory.edit.message"),
                AgentBundle.message("memory.edit.title"), item.content, null, null);
        if (edited != null && !edited.trim().isEmpty()) {
            resolveMemory(item, true, edited.trim());
        }
    }

    // approve=true → memory/promote (optionally with edited text), false → memory/discard.
    // approve=true 走 memory/promote（可带改过的文字），false 走 memory/discard。
    private void resolveMemory(PendingMemory item, boolean approve, String content) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String message;
            JsonObject duplicate = null;
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("id", item.id);
                if (approve) {
                    if (content != null) {
                        payload.put("content", content);
                    }
                    JsonObject result = callCore("memory/promote", payload);
                    String title = string(result, "title");
                    duplicate = result.has("duplicate") && result.get("duplicate").isJsonObject()
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
            String duplicateId = duplicate == null ? null : string(duplicate, "id");
            ApplicationManager.getApplication().invokeLater(() -> {
                if (duplicateId != null) {
                    // Keep the row and show the duplicate hint inline in yellow.
                    // 保留该行，把「与已有记忆相似」的提示直接画在行内（黄色小字）。
                    memoryWarnings.put(item.id, AgentBundle.message("ui.picker.memory.duplicate", duplicateId));
                } else if (memoryItems.remove(item)) {
                    memoryWarnings.remove(item.id);
                }
                tabsBar.setPendingMemories(memoryItems.size());
                refreshMemoryRows();
            });
            setStatus(message);
        });
    }

    private void cancelMemoryPopup() {
        if (memoryPopup != null && memoryPopup.isVisible()) {
            memoryPopup.cancel();
        }
        memoryPopup = null;
        memoryPicker = null;
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
                // The badge on the memory button carries the number; no status line needed.
                // 待确认数改为记忆按钮上的角标，不再占用状态行。
                ApplicationManager.getApplication().invokeLater(
                        () -> tabsBar.setPendingMemories(count));
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
        ApplicationManager.getApplication().invokeLater(() -> {
            ChatSessionPanel target = active;
            if (target != null) {
                target.setStatus(text);
            }
        });
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
        }

        @Override
        public void onTaskCleared(ChatSessionPanel session) {
            byTask.values().remove(session);
            rebuildTabs();
        }

        @Override
        public void onBusyChanged(ChatSessionPanel session) {
            rebuildTabs();
        }

        @Override
        public void onTitleChanged(ChatSessionPanel session) {
            rebuildTabs();
        }

        @Override
        public void openSettings() {
            AgentChatPanel.this.openSettings();
        }
    }
}
