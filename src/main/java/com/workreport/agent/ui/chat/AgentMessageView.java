package com.workreport.agent.ui.chat;

import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.IncrementalParser;
import com.workreport.agent.ui.markdown.MarkdownBlock;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

// Left-aligned agent answer card: raised surface, hairline border, a role chip plus meta line and
// hover-revealed actions, with the Markdown body mounted as block views underneath.
// 靠左的助手回答卡片：抬高底色 + 细描边，头部是角色 chip 与元信息，悬停浮出动作，
// 正文按 Markdown 块挂载在下方。
public final class AgentMessageView extends ChatRowView {

    private static final int BLINK_MS = 500;

    private final IncrementalParser parser = new IncrementalParser();
    private final BlockPanel body = new BlockPanel();

    private final int padX = UiMetrics.scale(12);
    private final int padY = UiMetrics.scale(10);
    private final int bodyGap = UiMetrics.scale(8);

    private final Rectangle copyArea = new Rectangle();
    private final Rectangle chipArea = new Rectangle();

    private String meta = "";
    private boolean streaming;
    private boolean caretOn;
    private boolean hovered;
    private boolean copied;
    private Timer blink;
    private Timer copyFlash;

    public AgentMessageView() {
        super(1d, false);
        setOpaque(false);
        setLayout(null);
        add(body);
        TextCopy.install(this, body::plainText);
        installMouse();
    }

    // Streaming text arrives as a whole document; the incremental parser keeps the work proportional
    // to what actually changed.
    // 流式文本按整篇文档喂入，增量解析器把开销压到“只重解析变化的尾部”。
    public void setMarkdown(String text) {
        List<MarkdownBlock> blocks = parser.feed(text);
        body.setBlocks(blocks);
        body.setCaretVisible(caretOn);
        contentChanged();
    }

    public void setMeta(String value) {
        String next = value == null ? "" : value;
        if (!next.equals(meta)) {
            meta = next;
            contentChanged();
        }
    }

    public boolean isEmpty() {
        return body.plainText().isBlank();
    }

    public String plainText() {
        return body.plainText();
    }

    // The blink timer only runs while a message is actually streaming.
    // 只在真正流式输出期间才启动闪烁定时器。
    public void setStreaming(boolean value) {
        if (streaming == value) {
            return;
        }
        streaming = value;
        if (value) {
            caretOn = true;
            body.setCaretVisible(true);
            if (blink == null) {
                blink = new Timer(BLINK_MS, event -> {
                    caretOn = !caretOn;
                    body.setCaretVisible(caretOn);
                });
            }
            blink.start();
        } else {
            if (blink != null) {
                blink.stop();
            }
            caretOn = false;
            body.setCaretVisible(false);
        }
    }

    @Override
    public void removeNotify() {
        if (blink != null) {
            blink.stop();
        }
        if (copyFlash != null) {
            copyFlash.stop();
        }
        super.removeNotify();
    }

    // ---------------------------------------------------------------- measuring

    private int headerHeight() {
        FontMetrics small = UiMetrics.metrics(UiTheme.small());
        return Math.max(small.getHeight(), UiMetrics.metrics(UiTheme.bold()).getHeight()) + UiMetrics.scale(2);
    }

    private int innerWidth(int width) {
        return Math.max(40, width - padX * 2);
    }

    private int bodyHeight(int width) {
        return body.heightFor(innerWidth(width));
    }

    @Override
    protected int contentWidth(int maxWidth) {
        return maxWidth;
    }

    @Override
    protected int computeHeight(int width) {
        int header = headerHeight();
        int bodyTop = padY + header + bodyGap;
        int height = Math.max(bodyTop + bodyHeight(width) + padY,
                bodyTop + UiMetrics.lineHeight(UiTheme.font()) + padY);
        layoutHeader(width, header);
        return height;
    }

    // Positions the chip and the hover action; both are painted, not components.
    // 计算头部 chip 与悬停动作的位置（二者都是绘制而非组件）。
    private void layoutHeader(int width, int header) {
        Font chipFont = UiTheme.small().deriveFont(Font.BOLD);
        FontMetrics chipMetrics = UiMetrics.metrics(chipFont);
        String role = AgentBundle.message("ui.chat.agent");
        int chipWidth = chipMetrics.stringWidth(role) + UiMetrics.scale(12);
        chipArea.setBounds(padX, padY, chipWidth, header);

        FontMetrics actionMetrics = UiMetrics.metrics(UiTheme.small());
        String label = actionLabel();
        int actionWidth = actionMetrics.stringWidth(label) + UiMetrics.scale(14);
        copyArea.setBounds(width - padX - actionWidth, padY, actionWidth, header);
    }

    private String actionLabel() {
        return AgentBundle.message(copied ? "ui.message.copied" : "ui.message.copy");
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        int header = headerHeight();
        int bodyTop = padY + header + bodyGap;
        body.setBounds(padX, bodyTop, innerWidth(width), bodyHeight(width));
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        int arc = UiMetrics.radiusCard();
        g2.setColor(UiTheme.RAISED);
        g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        g2.setColor(UiTheme.BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));

        paintRoleChip(g2);
        paintMeta(g2);
        if (hovered || copied) {
            paintAction(g2);
        }
    }

    private void paintRoleChip(Graphics2D g2) {
        int arc = UiMetrics.radiusChip();
        g2.setColor(UiTheme.accentSoft(56));
        g2.fill(new RoundRectangle2D.Float(chipArea.x, chipArea.y, chipArea.width, chipArea.height, arc, arc));
        Font font = UiTheme.small().deriveFont(Font.BOLD);
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        g2.setColor(UiTheme.ACCENT);
        int baseline = chipArea.y + (chipArea.height - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(AgentBundle.message("ui.chat.agent"),
                chipArea.x + UiMetrics.scale(6), baseline);
    }

    private void paintMeta(Graphics2D g2) {
        if (meta.isEmpty()) {
            return;
        }
        Font font = UiTheme.small();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        g2.setColor(UiTheme.muted());
        int x = chipArea.x + chipArea.width + UiMetrics.scale(8);
        int baseline = chipArea.y + (chipArea.height - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(meta, x, baseline);
    }

    private void paintAction(Graphics2D g2) {
        Font font = UiTheme.small();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        g2.setColor(copied ? UiTheme.OK : UiTheme.ACCENT);
        String label = actionLabel();
        int baseline = copyArea.y + (copyArea.height - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(label, copyArea.x + (copyArea.width - metrics.stringWidth(label)) / 2, baseline);
    }

    // ---------------------------------------------------------------- interaction

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
                if (!copyArea.contains(e.getPoint()) || body.plainText().isEmpty()) {
                    return;
                }
                TextCopy.copyToClipboard(body.plainText());
                flashCopied();
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

    private void flashCopied() {
        copied = true;
        repaint();
        if (copyFlash != null) {
            copyFlash.stop();
        }
        copyFlash = new Timer(1200, event -> {
            copied = false;
            repaint();
        });
        copyFlash.setRepeats(false);
        copyFlash.start();
    }
}
