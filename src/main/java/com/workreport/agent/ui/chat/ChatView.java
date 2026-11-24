package com.workreport.agent.ui.chat;

import com.intellij.openapi.diagnostic.Logger;
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
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private static final int SMOOTH_SCROLL_MS = 160;

    private static final Logger LOG = Logger.getInstance(ChatView.class);

    private final JPanel content = new JPanel();
    private final JBScrollPane scrollPane;
    private final Overlay overlay;
    private final BackToBottom backToBottom = new BackToBottom();
    private final Timer flushTimer;

    private final List<ChatRowView> rows = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();
    // Tokens arrive on the RPC reader thread. They are parked here and drained by a single EDT
    // task, so a fast stream posts one task per frame instead of one task per token.
    // token 来自 RPC 读线程：先缓在这里，由单个 EDT 任务取走；高速流因此每帧一个任务，而不是每个 token 一个。
    private final ConcurrentLinkedQueue<String> pendingTokens = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> pendingReasoning = new ConcurrentLinkedQueue<>();
    // Written from the RPC thread and cleared on the EDT, so both flags are volatile.
    // 一个在读线程写、另一个在 EDT 清，两个标志声明为 volatile。
    private volatile boolean drainScheduled;
    private volatile boolean scrollScheduled;
    private volatile boolean reasoningDrainScheduled;

    private int hiddenFrom;
    private MoreView moreView;
    private EmptyState emptyState;
    private String pendingHint;

    private AgentMessageView current;
    private StepGroupView steps;
    private boolean dirty;

    // Thinking process of the current model round; folded away as soon as the answer starts.
    // 本轮模型的思考过程；正文一开始就折叠收起。
    private ReasoningView reasoning;
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private boolean reasoningDirty;

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
        if (!SwingUtilities.isEventDispatchThread()) {
            pendingTokens.add(delta);
            if (!drainScheduled) {
                drainScheduled = true;
                SwingUtilities.invokeLater(this::drainTokens);
            }
            return;
        }
        appendTokenOnEdt(delta);
    }

    // Hand every parked token to the current row in one go.
    // 把缓存的 token 一次性交给当前行。
    private void drainTokens() {
        drainScheduled = false;
        StringBuilder batch = new StringBuilder();
        String token;
        while ((token = pendingTokens.poll()) != null) {
            batch.append(token);
        }
        if (batch.length() > 0) {
            appendTokenOnEdt(batch.toString());
        }
    }

    private void appendTokenOnEdt(String delta) {
        ensureAssistantRow();
        buffer.append(delta);
        dirty = true;
        if (!flushTimer.isRunning()) {
            flushTimer.start();
        }
    }

    // ---------------------------------------------------------------- reasoning

    // The thinking process takes the same batching path as tokens: one EDT task per drain, and a
    // shared 40 ms flush, so a fast reasoner cannot flood the queue.
    // 思考过程与正文同一套合批机制：一次 drain 一个 EDT 任务，共用 40ms 刷新。
    @Override
    public void appendReasoningToken(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            pendingReasoning.add(delta);
            if (!reasoningDrainScheduled) {
                reasoningDrainScheduled = true;
                SwingUtilities.invokeLater(this::drainReasoning);
            }
            return;
        }
        appendReasoningOnEdt(delta);
    }

    private void drainReasoning() {
        reasoningDrainScheduled = false;
        StringBuilder batch = new StringBuilder();
        String token;
        while ((token = pendingReasoning.poll()) != null) {
            batch.append(token);
        }
        if (batch.length() > 0) {
            appendReasoningOnEdt(batch.toString());
        }
    }

    private void appendReasoningOnEdt(String delta) {
        ensureReasoningRow();
        reasoningBuffer.append(delta);
        reasoningDirty = true;
        if (!flushTimer.isRunning()) {
            flushTimer.start();
        }
    }

    private void ensureReasoningRow() {
        if (reasoning != null) {
            return;
        }
        // A new thinking phase closes the previous tool group before opening its own row.
        // 新一段思考开始前先结束上一组工具步骤。
        breakSteps();
        ReasoningView row = new ReasoningView();
        appendRow(row);
        reasoning = row;
    }

    // Land the parked thinking text, freeze its clock and fold it away.
    // 把缓存的思考文本落盘、冻结计时并收起。
    private void finishReasoningInternal() {
        drainReasoning();
        if (reasoning != null) {
            reasoning.setText(reasoningBuffer.toString());
            reasoning.finish();
        }
        reasoning = null;
        reasoningBuffer.setLength(0);
        reasoningDirty = false;
    }

    @Override
    public void addToolLine(String text) {
        addToolLine(text, false, false);
    }

    @Override
    public void addToolLine(String text, boolean running, boolean failed) {
        runOnEdt(() -> {
            finishAssistantInternal();
            if (text == null || text.isEmpty()) {
                return;
            }
            if (steps == null || steps.getParent() != content) {
                steps = new StepGroupView();
                appendRow(steps);
            }
            steps.add(text, failed);
            steps.setRunning(running);
            followTail();
        });
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
            pendingTokens.clear();
            pendingReasoning.clear();
            drainScheduled = false;
            scrollScheduled = false;
            reasoningDrainScheduled = false;
            current = null;
            steps = null;
            reasoning = null;
            buffer.setLength(0);
            reasoningBuffer.setLength(0);
            dirty = false;
            reasoningDirty = false;
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
        // The answer always sits below its thinking: fold the reasoning row first.
        // 答案始终在思考之后：先把思考行收起。
        finishReasoningInternal();
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
        boolean pinned = isPinnedToBottom();
        boolean changed = false;
        if (reasoningDirty && reasoning != null) {
            reasoningDirty = false;
            reasoning.setText(reasoningBuffer.toString());
            changed = true;
        }
        if (current != null && dirty) {
            dirty = false;
            current.setMarkdown(buffer.toString());
            changed = true;
        }
        if (!changed) {
            return;
        }
        content.revalidate();
        content.repaint();
        if (pinned) {
            scrollToBottom(false);
        }
        updatePill();
    }

    private void finishAssistantInternal() {
        // Parked tokens belong to the row we are about to close: land them first, otherwise they
        // would show up under whatever comes next.
        // 缓存里的 token 属于即将收尾的这一行，先落盘，否则会显示到后面那一条下面。
        drainTokens();
        // Thinking has nowhere to go once the answer is closed; fold it here as well.
        // 正文收尾后思考行也没必要再长，一并收起。
        finishReasoningInternal();
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
        // Several flushes can land in one frame; one scroll request is enough.
        // 一帧内可能来了多次刷新，一次滚动请求就够了。
        if (!smooth) {
            if (scrollScheduled) {
                return;
            }
            scrollScheduled = true;
        }
        SwingUtilities.invokeLater(() -> {
            if (!smooth) {
                scrollScheduled = false;
            }
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            int end = bottomOf(bar);
            if (end <= bar.getValue()) {
                LOG.debug("scrollToBottom skipped: already at " + end);
                return;
            }
            LOG.debug("scrollToBottom " + bar.getValue() + " -> " + end
                    + (smooth ? " (smooth)" : ""));
            if (!smooth) {
                bar.setValue(end);
                return;
            }
            int from = bar.getValue();
            // The list can keep growing while the tween runs, so the end is re-read on every
            // frame and the last frame always lands on the newest bottom.
            // 动画期间内容可能还在增长：每帧重新读取终点，最后一帧一定落在最新的底部。
            UiAnim.animate(SMOOTH_SCROLL_MS, progress -> {
                bar.setValue(Math.round(from + (bottomOf(bar) - from) * progress));
            });
        });
    }

    // Largest legal value for this bar: the maximum is the content height, so the bottom is the
    // maximum minus the visible extent.
    // 滚动条能取到的最大值：maximum 是内容高度，底部即 maximum 减去可视范围。
    private static int bottomOf(JScrollBar bar) {
        return Math.max(bar.getMinimum(), bar.getMaximum() - bar.getVisibleAmount());
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

        // The pill deliberately overlaps the scroll pane, so the opaque-child repaint shortcut
        // has to stay off; otherwise the area under the pill may be skipped when it repaints.
        // 胶囊与滚动面板刻意重叠，因此关闭“不透明子组件可跳过”的重绘优化。
        @Override
        public boolean isOptimizedDrawingEnabled() {
            return false;
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

    private final class BackToBottom extends JButton {

        BackToBottom() {
            setVisible(false);
            // A real button instead of a JComponent with a mouse listener: press/release pairs,
            // keyboard activation and hover rollover all come from the button model, which is
            // what a hand-rolled mouseClicked never guaranteed.
            // 用真正的 JButton 而不是自带鼠标监听的 JComponent：按下/释放配对、键盘触发、
            // 悬停状态都由按钮模型维护，自绘 JComponent 的 mouseClicked 并不能保证触发。
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(event -> {
                LOG.debug("back-to-bottom clicked");
                // Lay out whatever changed since the last pass before measuring: reading the
                // bottom of a stale layout is how a click ends up scrolling nowhere.
                // 先强制布局再量底部：用过期布局量出的底部就是“滚到原地”。
                scrollPane.validate();
                scrollToBottom(true);
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
            boolean hovered = getModel().isRollover();
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
