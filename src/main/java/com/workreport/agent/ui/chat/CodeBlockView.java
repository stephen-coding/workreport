package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.MarkdownBlock;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

// Code block: compact mono body on a subtle surface, a language chip, a copy action, and an
// "expand" row once the snippet is longer than fits. Long lines are clipped, never wrapped:
// wrapped code is worse than a truncated line, and the copy button always has the full text.
// 代码块：浅底紧凑等宽正文 + 语言 chip + 复制按钮；超过 18 行时给出“展开”。
// 长行一律裁切而不是折行——折行的代码更难读，复制按钮拿到的始终是完整原文。
public final class CodeBlockView extends BlockView {

    private static final int MAX_COLLAPSED_LINES = 18;

    private final String language;
    private final String code;
    private final String[] lines;

    private boolean expanded;
    private boolean copied;

    private final Rectangle copyArea = new Rectangle();
    private final Rectangle toggleArea = new Rectangle();
    private int bodyTop;
    private int lineHeight;
    private String copyLabel = "";
    private String toggleLabel = "";

    public CodeBlockView(MarkdownBlock.CodeBlock block) {
        this.language = block.language() == null ? "" : block.language();
        this.code = block.code() == null ? "" : block.code();
        this.lines = this.code.isEmpty() ? new String[]{""} : this.code.split("\n", -1);
        setOpaque(false);
        TextCopy.install(this, () -> code);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (copyArea.contains(e.getPoint())) {
                    TextCopy.copyToClipboard(code);
                    flashCopied();
                } else if (toggleArea.contains(e.getPoint())) {
                    expanded = !expanded;
                    invalidateHeight();
                    revalidate();
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                boolean clickable = copyArea.contains(e.getPoint()) || toggleArea.contains(e.getPoint());
                setCursor(clickable
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean clickable = copyArea.contains(e.getPoint()) || toggleArea.contains(e.getPoint());
                setCursor(clickable
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
    }

    private void flashCopied() {
        copied = true;
        copyLabel = com.workreport.agent.AgentBundle.message("ui.code.copied");
        repaint();
        Timer timer = new Timer(1200, event -> {
            copied = false;
            copyLabel = com.workreport.agent.AgentBundle.message("ui.code.copy");
            repaint();
        });
        timer.setRepeats(false);
        timer.start();
    }

    private int pad() {
        return UiMetrics.scale(10);
    }

    @Override
    protected int computeHeight(int width) {
        Font mono = UiTheme.mono();
        FontMetrics metrics = UiMetrics.metrics(mono);
        lineHeight = metrics.getHeight();
        int headerHeight = Math.max(lineHeight, UiMetrics.metrics(UiTheme.small()).getHeight());

        copyLabel = com.workreport.agent.AgentBundle.message(copied ? "ui.code.copied" : "ui.code.copy");
        int copyWidth = UiMetrics.metrics(UiTheme.small()).stringWidth(copyLabel) + UiMetrics.scale(8);
        copyArea.setBounds(width - pad() - copyWidth, pad(), copyWidth, headerHeight);

        int visibleLines = expanded ? lines.length : Math.min(lines.length, MAX_COLLAPSED_LINES);
        boolean collapsible = lines.length > MAX_COLLAPSED_LINES;
        int toggleHeight = collapsible ? lineHeight : 0;
        int toggleTop = pad() + headerHeight + UiMetrics.scale(4) + visibleLines * lineHeight;
        if (collapsible) {
            toggleLabel = com.workreport.agent.AgentBundle.message(expanded ? "ui.code.collapse" : "ui.code.expand",
                    lines.length);
            toggleArea.setBounds(pad(), toggleTop, width - pad() * 2, toggleHeight);
        } else {
            toggleArea.setBounds(0, 0, 0, 0);
        }
        bodyTop = pad() + headerHeight + UiMetrics.scale(4);
        return bodyTop + visibleLines * lineHeight + toggleHeight + pad();
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        int radius = UiMetrics.radiusCard();
        g2.setColor(UiTheme.SUBTLE);
        g2.fillRoundRect(0, 0, width, height, radius, radius);

        Font mono = UiTheme.mono();
        Font small = UiTheme.small();
        FontMetrics monoMetrics = UiMetrics.metrics(mono);
        FontMetrics smallMetrics = UiMetrics.metrics(small);

        g2.setFont(small);
        g2.setColor(UiTheme.muted());
        String caption = language.isEmpty() ? "code" : language;
        g2.drawString(caption, pad(), pad() + smallMetrics.getAscent());

        g2.setColor(copied ? UiTheme.OK : UiTheme.ACCENT);
        int copyWidth = smallMetrics.stringWidth(copyLabel);
        g2.drawString(copyLabel, copyArea.x + (copyArea.width - copyWidth) / 2,
                copyArea.y + (copyArea.height - smallMetrics.getHeight()) / 2 + smallMetrics.getAscent());

        int visibleLines = expanded ? lines.length : Math.min(lines.length, MAX_COLLAPSED_LINES);
        Shape oldClip = g2.getClip();
        g2.clipRect(pad(), bodyTop, width - pad() * 2, visibleLines * lineHeight);
        g2.setFont(mono);
        g2.setColor(UiTheme.text());
        int available = width - pad() * 2;
        for (int i = 0; i < visibleLines; i++) {
            String line = lines[i];
            String drawn = truncate(line, monoMetrics, available);
            int baseline = bodyTop + i * lineHeight + monoMetrics.getAscent();
            g2.drawString(drawn, pad(), baseline);
        }
        g2.setClip(oldClip);

        if (toggleArea.width > 0) {
            g2.setFont(small);
            g2.setColor(UiTheme.ACCENT);
            g2.drawString(toggleLabel, pad(),
                    toggleArea.y + (toggleArea.height - smallMetrics.getHeight()) / 2 + smallMetrics.getAscent());
        }
    }

    private static String truncate(String line, FontMetrics metrics, int available) {
        if (line.isEmpty() || metrics.stringWidth(line) <= available) {
            return line;
        }
        int low = 0;
        int high = line.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (metrics.stringWidth(line.substring(0, mid)) <= available - metrics.stringWidth("…")) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return String.format(Locale.ROOT, "%s…", line.substring(0, Math.max(0, low)));
    }
}
