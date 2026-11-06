package com.workreport.agent.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;

// Scrolling transcript of chat bubbles: user messages on the right, agent answers on the
// left, tool/notice events as slim status lines in between.
// 对话流：用户消息靠右气泡、助手回答靠左渲染 Markdown、工具与提示作为细状态行插入。
public final class ChatTranscript extends JPanel {

    private static final Color USER_BUBBLE = new JBColor(new Color(0x3574F0), new Color(0x2F5FC4));
    private static final Color USER_TEXT = Color.WHITE;
    private static final Color ERROR_BUBBLE = new JBColor(new Color(0xFDECEC), new Color(0x3A2626));
    private static final Color ERROR_TEXT = new JBColor(new Color(0xC0392B), new Color(0xF08A8A));
    private static final Color HINT_TEXT = new JBColor(new Color(0x808080), new Color(0xA0A0A0));
    private static final Color USER_ATTACH_TEXT = new JBColor(new Color(0xDCE7FF), new Color(0xDCE7FF));

    private static final double USER_RATIO = 0.72;
    private static final double AGENT_RATIO = 0.88;
    private static final int ROW_GAP = 10;

    private final JPanel list = new JPanel();
    private final JBScrollPane scrollPane;
    private final Timer flushTimer;

    private MessageRow currentRow;
    private JEditorPane currentEditor;
    private final StringBuilder pending = new StringBuilder();
    private boolean dirty;

    public ChatTranscript() {
        super(new BorderLayout());
        setOpaque(false);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setBorder(new EmptyBorder(10, 8, 12, 8));

        scrollPane = new JBScrollPane(new ScrollHolder(list));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        add(scrollPane, BorderLayout.CENTER);

        // Coalesce streaming tokens: re-rendering the Markdown on every token would stutter.
        // 合并流式 token：每个 token 都重排 Markdown 会卡顿，统一按 70ms 节流刷新。
        flushTimer = new Timer(70, e -> flushNow());
        flushTimer.setRepeats(false);
    }

    // ---------------------------------------------------------------- public API

    public void addHint(String text) {
        runOnEdt(() -> {
            list.add(new StatusRow(text, HINT_TEXT, false));
            relayoutAndScroll(true);
        });
    }

    public void addUserMessage(String text) {
        addUserMessage(text, java.util.List.of());
    }

    public void addUserMessage(String text, java.util.List<String> attachmentNames) {
        runOnEdt(() -> {
            finishAssistantInternal();
            JComponent body = createUserBody(text, attachmentNames);
            RoundedBubble bubble = new RoundedBubble(USER_BUBBLE, body, new EmptyBorder(8, 12, 8, 12));
            list.add(new MessageRow(bubble, true, USER_RATIO));
            relayoutAndScroll(true);
        });
    }

    // Message body: the typed text plus a muted line listing what was attached.
    // 气泡内容：输入的正文 + 一行浅色附件清单。
    private static JComponent createUserBody(String text, java.util.List<String> attachmentNames) {
        boolean hasText = text != null && !text.isEmpty();
        if (attachmentNames == null || attachmentNames.isEmpty()) {
            return createPlainText(hasText ? text : "", USER_TEXT);
        }
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        if (hasText) {
            wrapper.add(createPlainText(text, USER_TEXT));
        }
        // Wrapping text area instead of a JLabel: a long attachment list must stay inside
        // the bubble width instead of being clipped.
        // 用可换行的文本域而非 JLabel，否则附件清单一长就会被气泡边界裁掉。
        JTextArea files = createPlainText("附件：" + String.join("、", attachmentNames), USER_ATTACH_TEXT);
        Font hintFont = baseFont();
        files.setFont(hintFont.deriveFont(hintFont.getSize2D() - 1f));
        files.setBorder(new EmptyBorder(hasText ? 5 : 0, 0, 0, 0));
        wrapper.add(files);
        return wrapper;
    }

    public void appendAssistantToken(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        runOnEdt(() -> {
            ensureAssistantRow();
            pending.append(delta);
            dirty = true;
            if (!flushTimer.isRunning()) {
                flushTimer.start();
            }
        });
    }

    public void addToolLine(String text) {
        runOnEdt(() -> {
            finishAssistantInternal();
            list.add(new StatusRow(text, HINT_TEXT, true));
            relayoutAndScroll(true);
        });
    }

    public void addNotice(String text) {
        runOnEdt(() -> {
            finishAssistantInternal();
            list.add(new StatusRow("[提示] " + text, HINT_TEXT, true));
            relayoutAndScroll(true);
        });
    }

    public void addErrorMessage(String text) {
        runOnEdt(() -> {
            finishAssistantInternal();
            JTextArea body = createPlainText(text, ERROR_TEXT);
            list.add(new MessageRow(new RoundedBubble(ERROR_BUBBLE, body,
                    new EmptyBorder(8, 12, 8, 12)), false, AGENT_RATIO));
            relayoutAndScroll(true);
        });
    }

    // Close the current answer bubble so later tokens start a fresh one.
    // 结束当前回答气泡；后续 token 会开启新的气泡（工具行不会打断已有内容）。
    public void finishAssistant() {
        runOnEdt(this::finishAssistantInternal);
    }

    public void clear() {
        runOnEdt(() -> {
            finishAssistantInternal();
            list.removeAll();
            list.revalidate();
            list.repaint();
        });
    }

    public void dispose() {
        flushTimer.stop();
    }

    // ---------------------------------------------------------------- internals

    private void ensureAssistantRow() {
        if (currentRow != null) {
            return;
        }
        JEditorPane editor = createEditor(UIUtil.getLabelForeground());
        currentEditor = editor;
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(2, 2, 2, 2));
        wrapper.add(editor, BorderLayout.CENTER);
        currentRow = new MessageRow(wrapper, false, AGENT_RATIO);
        list.add(currentRow);
    }

    private void flushNow() {
        if (currentEditor == null || pending.length() == 0) {
            dirty = false;
            return;
        }
        if (!dirty) {
            return;
        }
        dirty = false;
        boolean pinned = isPinnedToBottom();
        currentEditor.setText(MarkdownRenderer.toHtml(pending.toString()));
        currentRow.contentChanged();
        currentRow.revalidate();
        list.revalidate();
        list.repaint();
        if (pinned) {
            scrollToBottom();
        }
    }

    private void finishAssistantInternal() {
        if (flushTimer.isRunning()) {
            flushTimer.stop();
        }
        if (currentRow != null) {
            if (pending.length() > 0) {
                currentEditor.setText(MarkdownRenderer.toHtml(pending.toString()));
                currentRow.contentChanged();
            } else {
                // Nothing streamed into this bubble (the turn ended on a tool call).
                // 这一轮没有实际正文（例如以工具调用结束），把空气泡撤掉。
                list.remove(currentRow);
                list.revalidate();
                list.repaint();
            }
        }
        currentRow = null;
        currentEditor = null;
        pending.setLength(0);
        dirty = false;
    }

    private JEditorPane createEditor(Color foreground) {
        JEditorPane editor = new JEditorPane("text/html", "");
        editor.setEditable(false);
        editor.setOpaque(false);
        editor.setBorder(BorderFactory.createEmptyBorder());
        editor.setForeground(foreground);
        editor.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        editor.setFont(baseFont());
        editor.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                BrowserUtil.browse(event.getURL().toString());
            }
        });
        return editor;
    }

    private void relayoutAndScroll(boolean force) {
        list.revalidate();
        list.repaint();
        if (force || isPinnedToBottom()) {
            scrollToBottom();
        }
    }

    private boolean isPinnedToBottom() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 48;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // Single source of truth for text metrics so bubbles, hints and Markdown agree.
    // 统一字体来源，避免气泡、提示行与 Markdown 三处字号不一致。
    private static Font baseFont() {
        Font font = UIUtil.getLabelFont();
        return font != null ? font : new JLabel().getFont();
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    // Plain-text bubble body: JTextArea wraps by itself, stays selectable and needs no escaping.
    // 纯文本气泡：JTextArea 自动换行、可选中复制，不需要 HTML 转义。
    private static JTextArea createPlainText(String text, Color foreground) {
        JTextArea area = new JTextArea(normalizeNewlines(text));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setForeground(foreground);
        area.setFont(baseFont());
        area.setMargin(new Insets(0, 0, 0, 0));
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setCaretPosition(0);
        return area;
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ---------------------------------------------------------------- building blocks

    // Transcript viewport host. Tracking the viewport width keeps the transcript exactly as
    // wide as the tool window: no horizontal scrollbar, and every row can measure itself from
    // a stable parent width.
    // 视图容器：宽度始终跟随视口，因而不出现横向滚动条，行宽测量也有稳定参照。
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
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(24, visibleRect.height - 24);
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

    private static final class MessageRow extends JPanel {
        private final JComponent content;
        private final boolean alignRight;
        private final double ratio;

        // Measuring mutates child sizes, so the result is cached per available width and only
        // recomputed when the bubble text or the window width actually changed.
        // 测量会改动子组件尺寸，因此按可用宽度缓存结果，仅在换行宽度或内容变化时重算。
        private int measuredRowWidth = -1;
        private int measuredWidth;
        private int measuredHeight;

        MessageRow(JComponent content, boolean alignRight, double ratio) {
            this.content = content;
            this.alignRight = alignRight;
            this.ratio = ratio;
            setOpaque(false);
            setLayout(null);
            setBorder(new EmptyBorder(0, 0, ROW_GAP, 0));
            add(content);
        }

        // Streaming tokens change the text without changing the row width.
        void contentChanged() {
            measuredRowWidth = -1;
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public Dimension getPreferredSize() {
            int width = availableWidth();
            measure(width);
            return new Dimension(width, measuredHeight + ROW_GAP);
        }

        @Override
        public void doLayout() {
            int rowWidth = getWidth();
            measure(rowWidth);
            int x = alignRight ? Math.max(0, rowWidth - measuredWidth) : 0;
            content.setBounds(x, 0, measuredWidth, measuredHeight);
        }

        private int availableWidth() {
            Container parent = getParent();
            if (parent != null && parent.getWidth() > 0) {
                return parent.getWidth();
            }
            // Before the first layout the parent has no width yet; fall back to the viewport
            // extent so the very first measured bubble already fits the window.
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport != null && viewport.getExtentSize().width > 0) {
                return viewport.getExtentSize().width;
            }
            return 560;
        }

        // JEditorPane only reports a correct height once it knows its width, so the bubble
        // is sized first and measured afterwards.
        // JEditorPane 必须先知宽度才能算对高度：先按上限设宽，再测量并回收。
        private void measure(int rowWidth) {
            if (rowWidth == measuredRowWidth) {
                return;
            }
            int maxWidth = Math.max(180, (int) (rowWidth * ratio));
            sizeContent(maxWidth);
            Dimension pref = content.getPreferredSize();
            int width = Math.min(Math.max(pref.width, 120), maxWidth);
            int height = pref.height;
            if (width < maxWidth) {
                sizeContent(width);
                height = content.getPreferredSize().height;
            }
            measuredRowWidth = rowWidth;
            measuredWidth = width;
            measuredHeight = height;
        }

        // The bubble is a BorderLayout wrapper, so the width has to reach the inner text
        // components before their preferred height means anything: otherwise wrapped text
        // reports a single-line height and gets clipped.
        // 先宽度后高度：把可用宽度逐层传给气泡内的文本组件，否则换行后的高度算不对会被裁切。
        private void sizeContent(int width) {
            content.setSize(width, Short.MAX_VALUE);
            sizeChildren(content, width, 0);
        }

        private static void sizeChildren(Component parent, int width, int depth) {
            if (depth > 3 || !(parent instanceof Container container)) {
                return;
            }
            Insets insets = container.getInsets();
            int inner = Math.max(40, width - insets.left - insets.right);
            for (Component child : container.getComponents()) {
                child.setSize(inner, Short.MAX_VALUE);
                sizeChildren(child, inner, depth + 1);
            }
        }
    }

    private static final class RoundedBubble extends JPanel {
        private final Color fill;

        RoundedBubble(Color fill, JComponent content, EmptyBorder padding) {
            super(new BorderLayout());
            this.fill = fill;
            setOpaque(false);
            setBorder(padding);
            add(content, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class StatusRow extends JPanel {
        private final JTextArea area;
        private int measuredWidth = -1;
        private int measuredHeight;

        StatusRow(String text, Color color, boolean indent) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(new EmptyBorder(2, indent ? 8 : 2, 2, 8));
            area = new JTextArea(normalizeNewlines(text));
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setEditable(false);
            area.setOpaque(false);
            area.setForeground(color);
            Font hintFont = baseFont();
            area.setFont(hintFont.deriveFont(hintFont.getSize2D() - 1f));
            area.setMargin(new Insets(0, 0, 0, 0));
            area.setBorder(BorderFactory.createEmptyBorder());
            add(area, BorderLayout.CENTER);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public Dimension getPreferredSize() {
            Container parent = getParent();
            int width = parent != null && parent.getWidth() > 0 ? parent.getWidth() : 560;
            if (width != measuredWidth) {
                area.setSize(Math.max(120, width - 24), Short.MAX_VALUE);
                measuredHeight = area.getPreferredSize().height + 6;
                measuredWidth = width;
            }
            return new Dimension(width, measuredHeight);
        }
    }
}
