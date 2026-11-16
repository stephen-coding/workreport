package com.workreport.agent.ui.chat;

import com.intellij.ui.components.JBScrollPane;
import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiAnim;
import com.workreport.agent.ui.design.UiIcons;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// The conversation surface: a scrolling stack of message rows on top of the block renderer.
// Three things make it feel fast where the old HTML transcript stuttered:
//   1. tokens land in a 40 ms window and only the tail block re-parses (IncrementalParser),
//   2. every row caches its height per width, so a repaint never re-measures the conversation,
//   3. very old rows are unmounted in batches instead of staying in the component tree forever.
// 对话区：在块渲染之上堆叠可滚动的消息行。
// 三点让它比旧的 HTML 对话区顺滑：
//   1. token 按 40ms 合并，且只重解析尾块；
//   2. 每行按宽度缓存高度，重绘不再全量测量；
//   3. 过老的行按批卸载，不再长期留在组件树里。
public final class ChatView extends JPanel implements TranscriptView {

    private static final int FLUSH_MS = 40;
    // Mounted rows: keep the newest ones and fold the rest behind one "show earlier" row.
    // 常驻行数：只保留最近的部分，更早的收进一行“查看更早”。
    private static final int KEEP_ROWS = 120;
    private static final int REVEAL_BATCH = 40;
    private static final int PIN_SLACK = 48;

    private final JPanel content = new JPanel();
    private final JBScrollPane scrollPane;
    private final Overlay overlay;
    private final BackToBottom backToBottom = new BackToBottom();
    private final Timer flushTimer;

    private final List<ChatRowView> rows = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();

    private int hiddenFrom;
    private MoreView moreView;
    private EmptyState emptyState;
    private String pendingHint;

    private AgentMessageView current;
    private StepGroupView steps;
    private boolean dirty;

    private Consumer<String> onSuggestion;

    public ChatView() {
        super(new BorderLayout());
        setOpaque(false);

        content.setLayout(new BlockListLayout(UiMetrics.scale(12)));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(UiMetrics.scale(12), UiMetrics.scale(10),
                UiMetrics.scale(14), UiMetrics.scale(10)));

        scrollPane = new JBScrollPane(new ScrollHolder(content));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(UiMetrics.scale(40));
        scrollPane.getVerticalScrollBar().addAdjustmentListener(event -> updatePill());

        overlay = new Overlay();
        overlay.add(scrollPane);
        overlay.add(backToBottom);
        add(overlay, BorderLayout.CENTER);

        // Single coalescing window for the whole stream: 40 ms, one parse, one repaint.
        // 整条流共用一个合并窗口：40ms 一次解析、一次重绘。
        flushTimer = new Timer(FLUSH_MS, event -> flushNow());
        flushTimer.setRepeats(false);

        mountEmptyState();
    }

    // Lets the surrounding panel pre-fill the composer from a suggestion card.
    // 让外层面板能把建议卡的提示词填进输入框。
    public void setSuggestionSink(Consumer<String> sink) {
        onSuggestion = sink;
    }

    // ---------------------------------------------------------------- public API

    @Override
    public void addHint(String text) {
        runOnEdt(() -> {
            if (rows.isEmpty()) {
                pendingHint = text;
                if (emptyState != null) {
                    emptyState.setHint(text);
                    content.revalidate();
                    content.repaint();
                }
                return;
            }
            appendRow(new NoticeView(text, NoticeView.Tone.HINT));
            followTail();
        });
    }

    @Override
    public void addUserMessage(String text, List<String> attachmentNames) {
        runOnEdt(() -> {
            finishAssistantInternal();
            breakSteps();
            appendRow(new UserMessageView(text, attachmentNames));
            followTail();
        });
    }

    @Override
    public void appendAssistantToken(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        runOnEdt(() -> {
            ensureAssistantRow();
            buffer.append(delta);
            dirty = true;
            if (!flushTimer.isRunning()) {
                flushTimer.start();
            }
        });
    }

    @Override
    public void addToolLine(String text) {
        runOnEdt(() -> {
            finishAssistantInternal();
            if (text == null || text.isEmpty()) {
                return;
            }
            if (steps == null || steps.getParent() != content) {
                steps = new StepGroupView();
                appendRow(steps);
            }
            steps.add(text);
            steps.setRunning(isRunningTool(text));
            followTail();
        });
    }

    // The tool lines the session panel emits carry their own state; only these two shapes mean
    // "still working", everything else is a finished step.
    // 会话面板发出的工具行自带状态，只有这两种形态表示“还在跑”。
    private static boolean isRunningTool(String text) {
        return text.contains("执行中") || text.contains("开始：");
    }

    @Override
    public void addNotice(String text) {
        runOnEdt(() -> {
            finishAssistantInternal();
            breakSteps();
            appendRow(new NoticeView(text, NoticeView.Tone.INFO));
            followTail();
        });
    }

    @Override
    public void addErrorMessage(String text) {
        runOnEdt(() -> {
            finishAssistantInternal();
            breakSteps();
            appendRow(new ErrorView(text));
            followTail();
        });
    }

    @Override
    public void finishAssistant() {
        runOnEdt(this::finishAssistantInternal);
    }

    // Mount an interactive card at the tail (MCP confirmation) and hand it the keyboard.
    // 在末尾挂一张可交互卡片（MCP 确认），并把键盘焦点交给它。
    @Override
    public boolean addCard(ChatRowView row) {
        runOnEdt(() -> {
            finishAssistantInternal();
            breakSteps();
            appendRow(row);
            followTail();
            SwingUtilities.invokeLater(row::requestFocusInWindow);
        });
        return true;
    }

    @Override
    public void clear() {
        runOnEdt(() -> {
            flushTimer.stop();
            current = null;
            steps = null;
            buffer.setLength(0);
            dirty = false;
            rows.clear();
            hiddenFrom = 0;
            moreView = null;
            content.removeAll();
            mountEmptyState();
            content.revalidate();
            content.repaint();
            updatePill();
        });
    }

    @Override
    public void dispose() {
        flushTimer.stop();
    }

    // ---------------------------------------------------------------- streaming

    private void ensureAssistantRow() {
        if (current != null) {
            return;
        }
        breakSteps();
        AgentMessageView row = new AgentMessageView();
        row.setStreaming(true);
        appendRow(row);
        current = row;
    }

    private void breakSteps() {
        if (steps != null) {
            steps.setRunning(false);
        }
        steps = null;
    }

    private void flushNow() {
        if (current == null || !dirty) {
            dirty = false;
            return;
        }
        dirty = false;
        boolean pinned = isPinnedToBottom();
        current.setMarkdown(buffer.toString());
        content.revalidate();
        content.repaint();
        if (pinned) {
            scrollToBottom(false);
        }
        updatePill();
    }

    private void finishAssistantInternal() {
        flushTimer.stop();
        if (current != null) {
            if (buffer.length() == 0) {
                // Nothing streamed into this row (the turn ended on a tool call).
                // 这一轮没有实际正文（例如以工具调用结束），把空行撤掉。
                removeRow(current);
            } else {
                current.setMarkdown(buffer.toString());
                current.setStreaming(false);
                current.contentChanged();
                content.revalidate();
                content.repaint();
            }
        }
        current = null;
        buffer.setLength(0);
        dirty = false;
    }

    // ---------------------------------------------------------------- rows

    private void appendRow(ChatRowView row) {
        rows.add(row);
        if (emptyState != null) {
            content.remove(emptyState);
            emptyState = null;
        }
        trimOldRows();
        content.add(row);
        content.revalidate();
        content.repaint();
    }

    private void removeRow(ChatRowView row) {
        rows.remove(row);
        content.remove(row);
        content.revalidate();
        content.repaint();
    }

    // Unmount the oldest rows in batches; the count stays visible in the "show earlier" row.
    // 按批卸载最老的行，数量显示在“查看更早”那一行里。
    private void trimOldRows() {
        while (rows.size() - hiddenFrom > KEEP_ROWS + REVEAL_BATCH) {
            content.remove(rows.get(hiddenFrom));
            hiddenFrom++;
        }
        syncMoreView();
    }

    private void syncMoreView() {
        if (hiddenFrom <= 0) {
            if (moreView != null) {
                content.remove(moreView);
                moreView = null;
            }
            return;
        }
        if (moreView == null) {
            moreView = new MoreView(this::revealOlder);
            content.add(moreView, 0);
        }
        moreView.setHiddenCount(hiddenFrom);
        if (content.getComponentCount() == 0 || content.getComponent(0) != moreView) {
            content.add(moreView, 0);
        }
    }

    private void revealOlder() {
        hiddenFrom = Math.max(0, hiddenFrom - REVEAL_BATCH);
        content.removeAll();
        if (hiddenFrom > 0) {
            moreView = new MoreView(this::revealOlder);
            moreView.setHiddenCount(hiddenFrom);
            content.add(moreView);
        } else {
            moreView = null;
        }
        for (int i = hiddenFrom; i < rows.size(); i++) {
            content.add(rows.get(i));
        }
        content.revalidate();
        content.repaint();
    }

    private void mountEmptyState() {
        if (emptyState == null) {
            emptyState = new EmptyState();
            emptyState.setOnPick(text -> {
                if (onSuggestion != null) {
                    onSuggestion.accept(text);
                }
            });
        }
        emptyState.setHint(pendingHint);
        content.add(emptyState, 0);
    }

    // ---------------------------------------------------------------- scrolling

    private void followTail() {
        if (isPinnedToBottom()) {
            scrollToBottom(false);
        }
        updatePill();
    }

    private boolean isPinnedToBottom() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - PIN_SLACK;
    }

    private void scrollToBottom(boolean smooth) {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            int target = bar.getMaximum() - bar.getVisibleAmount();
            if (target <= bar.getValue()) {
                return;
            }
            if (!smooth) {
                bar.setValue(target);
                return;
            }
            int from = bar.getValue();
            UiAnim.animate(120, progress -> {
                int value = Math.round(from + (target - from) * progress);
                bar.setValue(value);
            });
        });
    }

    private void updatePill() {
        boolean show = !isPinnedToBottom();
        if (backToBottom.isVisible() != show) {
            backToBottom.setVisible(show);
            backToBottom.revalidate();
            backToBottom.repaint();
        }
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    // ---------------------------------------------------------------- host pieces

    // Keeps the transcript exactly as wide as the viewport: no horizontal scrollbar, and every row
    // measures against a stable width.
    // 宽度始终跟随视口：不出现横向滚动条，行宽测量也有稳定参照。
    private static final class ScrollHolder extends JPanel implements Scrollable {

        ScrollHolder(JComponent view) {
            super(new BorderLayout());
            setOpaque(false);
            add(view, BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return UiMetrics.scale(40);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(UiMetrics.scale(40), visibleRect.height - UiMetrics.scale(40));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // Scroll pane plus the floating "back to bottom" pill drawn on top of it.
    // 滚动容器 + 浮在其上的“回到底部”胶囊。
    private final class Overlay extends JPanel {

        Overlay() {
            super(null);
            setOpaque(false);
        }

        @Override
        public void doLayout() {
            scrollPane.setBounds(0, 0, getWidth(), getHeight());
            Dimension size = backToBottom.getPreferredSize();
            int x = (getWidth() - size.width) / 2;
            int y = Math.max(0, getHeight() - size.height - UiMetrics.scale(14));
            backToBottom.setBounds(x, y, size.width, size.height);
        }
    }

    private final class BackToBottom extends JComponent {

        private boolean hovered;

        BackToBottom() {
            setVisible(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    scrollToBottom(true);
                }
            });
        }

        private String label() {
            return AgentBundle.message("ui.chat.backToBottom");
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = UiMetrics.metrics(UiTheme.small());
            return new Dimension(metrics.stringWidth(label()) + UiMetrics.scale(26),
                    metrics.getHeight() + UiMetrics.scale(10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = getHeight();
            g2.setColor(UiTheme.RAISED);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
            g2.setColor(hovered ? UiTheme.ACCENT : UiTheme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, arc, arc));

            Font font = UiTheme.small();
            FontMetrics metrics = UiMetrics.metrics(font);
            g2.setFont(font);
            g2.setColor(hovered ? UiTheme.ACCENT : UiTheme.text());
            String label = label();
            g2.drawString(label, (getWidth() - metrics.stringWidth(label)) / 2,
                    (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
            g2.dispose();
        }
    }

    // One-line header that folds the unmounted rows.
    // 折叠已卸载行的一行头部。
    private final class MoreView extends ChatRowView {

        private final Runnable onReveal;
        private int hiddenCount;
        private boolean hovered;
        private final int pad = UiMetrics.scale(8);

        MoreView(Runnable onReveal) {
            super(1d, false);
            this.onReveal = onReveal;
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    onReveal.run();
                }
            });
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setHiddenCount(int count) {
            if (hiddenCount == count) {
                return;
            }
            hiddenCount = count;
            revalidate();
            repaint();
        }

        private String label() {
            return AgentBundle.message("ui.chat.showEarlier", hiddenCount);
        }

        @Override
        protected int computeHeight(int width) {
            return UiMetrics.metrics(UiTheme.small()).getHeight() + pad * 2;
        }

        @Override
        protected void paintBlock(Graphics2D g2, int width, int height) {
            int chevron = UiMetrics.scale(12);
            Rectangle box = new Rectangle(UiMetrics.scale(4), (height - chevron) / 2, chevron, chevron);
            UiIcons.paintChevron(g2, box, hovered ? UiTheme.ACCENT : UiTheme.muted(), false);
            Font font = UiTheme.small();
            FontMetrics metrics = UiMetrics.metrics(font);
            g2.setFont(font);
            g2.setColor(hovered ? UiTheme.ACCENT : UiTheme.muted());
            g2.drawString(label(), UiMetrics.scale(4) + chevron + UiMetrics.scale(6),
                    (height - metrics.getHeight()) / 2 + metrics.getAscent());
        }
    }
}
