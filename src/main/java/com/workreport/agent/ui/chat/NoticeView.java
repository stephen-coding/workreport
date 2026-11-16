package com.workreport.agent.ui.chat;

import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

// Single slim status line: a 3 px tone bar on the left, a soft surface behind it and the text
// wrapped next to it. Hints, notices and errors all share this shape.
// 细状态行：左侧 3 px 状态条 + 浅底 + 文本换行。提示、通知、错误共用同一形状。
public class NoticeView extends ChatRowView {

    // Tone drives the bar and text colour only; the geometry stays identical.
    // 语气只影响状态条与文字颜色，几何形状保持一致。
    public enum Tone {
        HINT,
        INFO,
        OK,
        ERROR
    }

    private final Tone tone;
    private final InlineLayout layout;
    private final String plain;

    private final int bar = UiMetrics.scale(3);
    private final int indent = UiMetrics.scale(10);
    private final int padX = UiMetrics.scale(10);
    private final int padY = UiMetrics.scale(6);

    private final Rectangle copyArea = new Rectangle();
    private boolean hovered;
    private boolean copied;
    private Timer flash;

    public NoticeView(String text, Tone tone) {
        super(1d, false);
        this.tone = tone;
        this.plain = text == null ? "" : text;
        this.layout = new InlineLayout(List.of(InlineSpan.plain(plain)), UiTheme.small(), true);
        setOpaque(false);
        TextCopy.install(this, () -> plain);
        installMouse();
    }

    private Color textColor() {
        return switch (tone) {
            case ERROR -> UiTheme.ERROR;
            case OK -> UiTheme.OK;
            case INFO -> UiTheme.ACCENT;
            case HINT -> UiTheme.muted();
        };
    }

    private Color barColor() {
        return switch (tone) {
            case ERROR -> UiTheme.ERROR;
            case OK -> UiTheme.OK;
            case INFO, HINT -> UiTheme.withAlpha(UiTheme.ACCENT, 120);
        };
    }

    private String actionLabel() {
        return AgentBundle.message(copied ? "ui.message.copied" : "ui.message.copy");
    }

    @Override
    public void removeNotify() {
        if (flash != null) {
            flash.stop();
        }
        super.removeNotify();
    }

    @Override
    protected int contentWidth(int maxWidth) {
        int textWidth = Math.max(40, maxWidth - indent - padX * 2);
        layout.measure(textWidth);
        return Math.min(textWidth, layout.maxAdvance()) + indent + padX * 2;
    }

    @Override
    protected int computeHeight(int width) {
        int textWidth = Math.max(40, width - indent - padX * 2);
        int height = layout.measure(textWidth) + padY * 2;
        FontMetrics metrics = UiMetrics.metrics(UiTheme.small());
        String label = actionLabel();
        int actionWidth = metrics.stringWidth(label) + UiMetrics.scale(10);
        copyArea.setBounds(width - padX - actionWidth, (height - metrics.getHeight()) / 2,
                actionWidth, metrics.getHeight());
        return Math.max(height, padY * 2 + metrics.getHeight());
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        int arc = UiMetrics.radiusChip();
        g2.setColor(tone == Tone.ERROR ? UiTheme.ERROR_SOFT : UiTheme.withAlpha(UiTheme.SUBTLE, 200));
        g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));

        g2.setColor(barColor());
        // Re-round the left edge so the bar does not square off the chip corner.
        // 重绘左侧圆角，避免状态条把 chip 的圆角切成直角。
        Shape oldClip = g2.getClip();
        g2.clip(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        g2.fill(new Rectangle2D.Float(0, 0, bar, height));
        g2.setClip(oldClip);

        layout.paint(g2, indent + padX / 2, padY);

        if (hovered || copied) {
            Font font = UiTheme.small();
            FontMetrics metrics = UiMetrics.metrics(font);
            g2.setFont(font);
            g2.setColor(copied ? UiTheme.OK : textColor());
            String label = actionLabel();
            int baseline = copyArea.y + (copyArea.height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(label, copyArea.x + (copyArea.width - metrics.stringWidth(label)) / 2, baseline);
        }
    }

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
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
                if (!copyArea.contains(e.getPoint())) {
                    return;
                }
                TextCopy.copyToClipboard(plain);
                copied = true;
                repaint();
                if (flash != null) {
                    flash.stop();
                }
                flash = new Timer(1200, event -> {
                    copied = false;
                    repaint();
                });
                flash.setRepeats(false);
                flash.start();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(copyArea.contains(e.getPoint())
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }
}
