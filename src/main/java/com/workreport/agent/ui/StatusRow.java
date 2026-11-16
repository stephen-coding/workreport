package com.workreport.agent.ui;

import com.intellij.util.ui.JBUI;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;

// Thin status line that lives directly above the composer.
// Left: a spinning accent arc plus the current state text; right: a muted chip with the model and
// the numbers of the turn. It replaces the two stacked rows that used to sit on top of the panel.
// 输入区正上方的状态行：左侧转动的强调色圆环 + 状态文本，右侧是模型与用量的小 chip。
// 取代过去面板顶部堆着的两行结构，把纵向空间让给对话。
public final class StatusRow extends JComponent {

    private static final int HEIGHT = 20;
    private static final int DOT = 12;

    private String text = "";
    private String meta = "";
    private boolean busy;
    private float angle;
    private Timer spinner;

    public StatusRow() {
        setOpaque(false);
        setFont(UiTheme.small());
        setBorder(null);
    }

    public void setText(String value) {
        String next = value == null ? "" : value;
        if (next.equals(text)) {
            return;
        }
        text = next;
        repaint();
    }

    // Right-hand chip: model name, token usage, elapsed time. Empty hides it.
    // 右侧 chip：模型、用量、耗时；为空时整块不画。
    public void setMeta(String value) {
        String next = value == null ? "" : value;
        if (next.equals(meta)) {
            return;
        }
        meta = next;
        repaint();
    }

    // The spinner only ticks while a task is running, so an idle panel costs nothing.
    // 只有任务运行中才转，空闲时不留任何定时器。
    public void setBusy(boolean value) {
        if (busy == value) {
            return;
        }
        busy = value;
        if (busy) {
            if (spinner == null) {
                spinner = new Timer(60, e -> {
                    angle = (angle + 30f) % 360f;
                    repaint();
                });
                spinner.setCoalesce(true);
            }
            spinner.start();
        } else if (spinner != null) {
            spinner.stop();
            angle = 0f;
        }
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(JBUI.scale(120), JBUI.scale(HEIGHT));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, JBUI.scale(HEIGHT));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int height = getHeight();
        int left = 0;
        if (busy) {
            int size = JBUI.scale(DOT);
            int y = (height - size) / 2;
            g2.setStroke(new BasicStroke(Math.max(1.4f, JBUI.scale(2f)), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g2.setColor(UiTheme.accentSoft(70));
            g2.draw(new Arc2D.Float(left, y, size, size, 0, 360, Arc2D.OPEN));
            g2.setColor(UiTheme.ACCENT);
            g2.draw(new Arc2D.Float(left, y, size, size, angle, 100, Arc2D.OPEN));
            left += size + UiMetrics.s();
        }

        Font font = getFont();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);

        int metaWidth = 0;
        if (!meta.isEmpty()) {
            int padX = UiMetrics.s();
            metaWidth = metrics.stringWidth(meta) + padX * 2;
            int chipHeight = Math.min(height - JBUI.scale(2), metrics.getHeight() + JBUI.scale(2));
            int chipY = (height - chipHeight) / 2;
            int chipX = getWidth() - metaWidth;
            g2.setColor(UiTheme.SUBTLE);
            g2.fill(new RoundRectangle2D.Float(chipX, chipY, metaWidth, chipHeight,
                    UiMetrics.radiusChip(), UiMetrics.radiusChip()));
            g2.setColor(UiTheme.muted());
            int baseline = chipY + (chipHeight - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(meta, chipX + padX, baseline);
        }

        if (!text.isEmpty()) {
            int available = getWidth() - left - metaWidth - (metaWidth > 0 ? UiMetrics.s() : 0);
            String shown = text;
            if (metrics.stringWidth(shown) > available && available > 0) {
                shown = ellipsize(shown, metrics, available);
            }
            g2.setColor(UiTheme.muted());
            int baseline = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(shown, left, baseline);
        }
        g2.dispose();
    }

    private static String ellipsize(String value, FontMetrics metrics, int available) {
        String ellipsis = "…";
        int width = metrics.stringWidth(ellipsis);
        for (int end = value.length() - 1; end > 0; end--) {
            String candidate = value.substring(0, end);
            if (metrics.stringWidth(candidate) + width <= available) {
                return candidate + ellipsis;
            }
        }
        return "";
    }

    // Stop the spinner when the row leaves the hierarchy.
    // 从界面移除时停掉定时器。
    @Override
    public void removeNotify() {
        if (spinner != null) {
            spinner.stop();
        }
        super.removeNotify();
    }
}
