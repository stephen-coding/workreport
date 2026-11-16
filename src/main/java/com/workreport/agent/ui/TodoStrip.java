package com.workreport.agent.ui;

import com.intellij.util.ui.JBUI;
import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiIcons;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.util.ArrayList;
import java.util.List;

// Collapsible checklist above the transcript, fed by the Core's chat/todos event.
// The list is owned by the Core (todo_write); this strip only renders it and folds
// itself away once every item is finished, so the transcript gets the room back.
// 对话区上方的任务清单：内容由 Core 的 todo_write 维护，这里只负责渲染；
// 全部完成后自动折叠成一行，把空间还给对话。
public final class TodoStrip extends JComponent {

    // One row; the Shell never edits it, it just shows what the Core sent.
    // 单行数据：Shell 只展示，不修改。
    public static final class Item {
        public final String content;
        public final String status;

        public Item(String content, String status) {
            this.content = content;
            this.status = status;
        }
    }

    // Same vocabulary as agent/todos.py, so both sides read alike.
    // 与 agent/todos.py 一致的状态取值。
    private static final String COMPLETED = "completed";
    private static final String IN_PROGRESS = "in_progress";
    private static final String CANCELLED = "cancelled";

    private final List<Item> items = new ArrayList<>();
    private boolean collapsed;
    private int doneCount;
    private boolean hoverHeader;

    public TodoStrip() {
        setOpaque(false);
        setFont(UiTheme.font());
        setBorder(new EmptyBorder(UiMetrics.xs(), UiMetrics.l(), JBUI.scale(2), UiMetrics.l()));
        setToolTipText(AgentBundle.message("todos.tip"));
        setVisible(false);
        installMouse();
    }

    // Replace the whole list; an empty one hides the strip entirely.
    // 整表覆盖；清单为空时整条隐藏。
    public void setItems(List<Item> values) {
        items.clear();
        doneCount = 0;
        if (values != null) {
            items.addAll(values);
            for (Item item : values) {
                if (isDone(item.status)) {
                    doneCount++;
                }
            }
        }
        if (items.isEmpty()) {
            setVisible(false);
            return;
        }
        setVisible(true);
        // Nothing left to do: fold it away without a second click.
        // 已无待办项时直接折叠，不需要再点一次。
        collapsed = doneCount >= items.size();
        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    private void setCollapsed(boolean value) {
        if (collapsed == value) {
            return;
        }
        collapsed = value;
        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    // ---------------------------------------------------------------- sizing

    private int headerHeight() {
        return UiMetrics.metrics(UiTheme.small()).getHeight() + JBUI.scale(4);
    }

    private int rowHeight() {
        return UiMetrics.metrics(getFont()).getHeight() + JBUI.scale(3);
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : JBUI.scale(220);
        int height = headerHeight() + (collapsed ? 0 : items.size() * rowHeight());
        Insets insets = getInsets();
        return new Dimension(width, height + insets.top + insets.bottom);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int left = getInsets().left;
        int top = getInsets().top;

        // Header: chevron + "N/M" counters. The chevron replaces the ▸/▾ characters, which
        // rendered differently on every platform font.
        // 头部：折角 + 计数；用自绘折角替换 ▸/▾ 字符（各平台字形不一致）。
        int chevron = JBUI.scale(10);
        UiIcons.paintChevron(g2, new Rectangle(left, top + (headerHeight() - chevron) / 2,
                chevron, chevron), hoverHeader ? UiTheme.text() : UiTheme.muted(), !collapsed);

        Font headerFont = UiTheme.small();
        FontMetrics headerMetrics = UiMetrics.metrics(headerFont);
        g2.setFont(headerFont);
        g2.setColor(hoverHeader ? UiTheme.text() : UiTheme.muted());
        String suffix = collapsed && doneCount >= items.size()
                ? AgentBundle.message("todos.done") : "";
        String header = AgentBundle.message("todos.header", "", doneCount, items.size(), suffix);
        int textX = left + chevron + UiMetrics.s();
        g2.drawString(header, textX, top + (headerHeight() - headerMetrics.getHeight()) / 2
                + headerMetrics.getAscent());

        if (collapsed) {
            g2.dispose();
            return;
        }

        int y = top + headerHeight();
        for (Item item : items) {
            paintRow(g2, item, left, y);
            y += rowHeight();
        }
        g2.dispose();
    }

    private void paintRow(Graphics2D g2, Item item, int left, int top) {
        int size = JBUI.scale(11);
        int iconX = left + JBUI.scale(3);
        Rectangle box = new Rectangle(iconX, top + (rowHeight() - size) / 2, size, size);
        paintMark(g2, item.status, box);

        Font font = getFont();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        int textX = iconX + size + UiMetrics.s();
        int available = getWidth() - getInsets().right - textX;
        String text = ellipsize(item.content, metrics, available);
        int baseline = top + (rowHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        boolean done = isDone(item.status);
        g2.setColor(done ? UiTheme.muted() : UiTheme.text());
        g2.drawString(text, textX, baseline);
        if (done && metrics.stringWidth(text) > 0) {
            int width = metrics.stringWidth(text);
            int strikeY = baseline - metrics.getAscent() / 2;
            g2.setColor(UiTheme.withAlpha(UiTheme.muted(), 140));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(textX, strikeY, textX + width, strikeY);
        }
    }

    // Status marks are drawn: ring for pending, 3/4 arc for running, tick for done, cross for
    // cancelled. No font glyph is involved, so light/dark themes look identical.
    // 状态全部自绘：待办画圆环、进行中画 3/4 圆弧、完成画勾、取消画叉，不依赖任何字体字形。
    private void paintMark(Graphics2D g2, String status, Rectangle box) {
        if (COMPLETED.equals(status)) {
            UiIcons.paintCheck(g2, box, UiTheme.OK);
            return;
        }
        if (CANCELLED.equals(status)) {
            UiIcons.paintClose(g2, box, UiTheme.muted());
            return;
        }
        if (IN_PROGRESS.equals(status)) {
            g2.setStroke(new BasicStroke(Math.max(1.3f, JBUI.scale(1.6f)), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g2.setColor(UiTheme.accentSoft(70));
            g2.draw(new Arc2D.Float(box.x, box.y, box.width, box.height, 0, 360, Arc2D.OPEN));
            g2.setColor(UiTheme.ACCENT);
            g2.draw(new Arc2D.Float(box.x, box.y, box.width, box.height, 90, 270, Arc2D.OPEN));
            return;
        }
        g2.setStroke(new BasicStroke(Math.max(1.2f, JBUI.scale(1.4f))));
        g2.setColor(UiTheme.muted());
        g2.draw(new Arc2D.Float(box.x, box.y, box.width, box.height, 0, 360, Arc2D.OPEN));
    }

    private String ellipsize(String value, FontMetrics metrics, int available) {
        if (value == null) {
            return "";
        }
        if (available <= 0 || metrics.stringWidth(value) <= available) {
            return value;
        }
        String ellipsis = "…";
        for (int end = value.length() - 1; end > 0; end--) {
            String candidate = value.substring(0, end);
            if (metrics.stringWidth(candidate) + metrics.stringWidth(ellipsis) <= available) {
                return candidate + ellipsis;
            }
        }
        return ellipsis;
    }

    // ---------------------------------------------------------------- interaction

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getY() - getInsets().top <= headerHeight()) {
                    setCollapsed(!collapsed);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                boolean inHeader = e.getY() - getInsets().top <= headerHeight();
                setCursor(inHeader
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
                if (inHeader != hoverHeader) {
                    hoverHeader = inHeader;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverHeader) {
                    hoverHeader = false;
                    repaint();
                }
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private static boolean isDone(String status) {
        return COMPLETED.equals(status) || CANCELLED.equals(status);
    }
}
