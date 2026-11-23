package com.workreport.agent.ui.chat;

import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiIcons;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

// Several consecutive tool / sub-agent lines folded into one collapsible step block.
// Collapsed it is a single line ("3 步 · 完成"); expanded it is a timeline. This replaces the wall
// of grey text lines that used to push the answer off screen.
// 把连续的工具/子代理行合并成一个可折叠的步骤块：折叠时只有一行“3 步 · 完成”，展开是时间线。
// 取代过去刷屏的灰色文本行。
public final class StepGroupView extends ChatRowView {

    private final List<String> items = new ArrayList<>();
    private final List<InlineLayout> layouts = new ArrayList<>();

    private final int pad = UiMetrics.scale(8);
    private final int itemGap = UiMetrics.scale(5);
    private final int dotColumn = UiMetrics.scale(18);

    private final Rectangle headerArea = new Rectangle();

    private boolean expanded;
    private boolean running;
    private boolean hovered;
    private int failedCount;

    public StepGroupView() {
        super(1d, false);
        setOpaque(false);
        TextCopy.install(this, () -> String.join("\n", items));
        installMouse();
    }

    public void add(String line) {
        add(line, false);
    }

    // Failed steps are flagged by the caller: the timeline text is English now, so sniffing for a
    // word inside it would silently stop marking failures.
    // 失败状态由调用方传入：时间线已是英文，靠文本里找词判断失败会静默失灵。
    public void add(String line, boolean failed) {
        if (line == null || line.isEmpty()) {
            return;
        }
        items.add(line);
        if (failed) {
            failedCount++;
        }
        layouts.clear();
        contentChanged();
    }

    // Named `count` on purpose: `size()` is already taken by Component and returns a Dimension.
    // 名字用 count：`size()` 已被 Component 占用且返回 Dimension。
    public int count() {
        return items.size();
    }

    // A step that is still executing keeps the summary line in the "running" wording.
    // 仍在执行的步骤用“执行中”措辞。
    public void setRunning(boolean value) {
        if (running == value) {
            return;
        }
        running = value;
        repaint();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    // ---------------------------------------------------------------- measuring

    private int headerHeight() {
        return UiMetrics.metrics(UiTheme.small()).getHeight() + pad;
    }

    private String summary() {
        String count = AgentBundle.message("ui.steps.count", items.size());
        String status;
        if (running) {
            status = AgentBundle.message("ui.steps.running");
        } else if (failedCount > 0) {
            status = AgentBundle.message("ui.steps.failed");
        } else {
            status = AgentBundle.message("ui.steps.done");
        }
        return count + " · " + status;
    }

    private void ensureLayouts(int width) {
        if (!layouts.isEmpty() || items.isEmpty()) {
            return;
        }
        int textWidth = Math.max(40, width - dotColumn - pad);
        for (String item : items) {
            InlineLayout layout = new InlineLayout(List.of(InlineSpan.plain(item)), UiTheme.small(), false);
            layout.measure(textWidth);
            layouts.add(layout);
        }
    }

    @Override
    protected int computeHeight(int width) {
        int header = headerHeight();
        headerArea.setBounds(0, 0, width, header);
        if (!expanded) {
            layouts.clear();
            return header;
        }
        ensureLayouts(width);
        int height = header + pad / 2;
        for (InlineLayout layout : layouts) {
            height += layout.height() + itemGap;
        }
        return Math.max(height - itemGap, header);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        boolean active = expanded || hovered;
        if (active) {
            int arc = UiMetrics.radiusChip();
            g2.setColor(UiTheme.withAlpha(UiTheme.SUBTLE, 200));
            g2.fill(new RoundRectangle2D.Float(0, 0, width, headerArea.height, arc, arc));
        }

        int chevron = UiMetrics.scale(12);
        Rectangle box = new Rectangle(pad, (headerArea.height - chevron) / 2, chevron, chevron);
        UiIcons.paintChevron(g2, box, UiTheme.muted(), expanded);

        Font font = UiTheme.small();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        g2.setColor(running ? UiTheme.text() : UiTheme.muted());
        int baseline = (headerArea.height - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(summary(), pad + chevron + pad, baseline);

        if (!expanded) {
            return;
        }
        int y = headerArea.height + pad / 2;
        int axis = pad + chevron / 2;
        int dot = UiMetrics.scale(5);
        for (int i = 0; i < layouts.size(); i++) {
            InlineLayout layout = layouts.get(i);
            g2.setColor(UiTheme.withAlpha(UiTheme.muted(), 90));
            g2.fillOval(axis - dot / 2, y + metrics.getAscent() - dot, dot, dot);
            if (i < layouts.size() - 1) {
                g2.setColor(UiTheme.withAlpha(UiTheme.BORDER, 160));
                g2.drawLine(axis, y + metrics.getAscent(), axis, y + layout.height() + itemGap);
            }
            layout.paint(g2, dotColumn, y);
            y += layout.height() + itemGap;
        }
    }

    // ---------------------------------------------------------------- interaction

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!headerArea.contains(e.getPoint())) {
                    return;
                }
                expanded = !expanded;
                contentChanged();
            }

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
            public void mouseMoved(MouseEvent e) {
                setCursor(headerArea.contains(e.getPoint())
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }
}
