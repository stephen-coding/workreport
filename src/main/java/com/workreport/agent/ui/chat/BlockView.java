package com.workreport.agent.ui.chat;

import com.intellij.ide.BrowserUtil;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;
import com.workreport.agent.ui.markdown.MarkdownBlock;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

// Base class for a single Markdown block view: sizing comes from MeasuredComponent, this class
// adds the link handling shared by every text-ish block.
// 单个 Markdown 块视图的基类：尺寸由 MeasuredComponent 负责，这里只补上各文本块共用的链接处理。
public abstract class BlockView extends MeasuredComponent {

    // Only the tail block of a streaming message owns the caret, and only while the blink phase
    // is on; both are set by the hosting BlockPanel.
    // 只有流式消息的尾块会画光标，且仅在闪烁的“亮”相位；状态由宿主 BlockPanel 设置。
    private InlineLayout caretTarget;
    private int caretX;
    private int caretY;
    private boolean caretVisible;

    protected final void setCaretTarget(InlineLayout layout, int x, int y) {
        caretTarget = layout;
        caretX = x;
        caretY = y;
    }

    public void setCaretVisible(boolean visible) {
        if (caretVisible == visible) {
            return;
        }
        caretVisible = visible;
        repaint();
    }

    protected final void paintCaret(Graphics2D g2) {
        if (caretVisible && caretTarget != null) {
            caretTarget.paintCaret(g2, caretX, caretY, UiTheme.ACCENT);
        }
    }

    protected final void installLinks(InlineLayout layout, int insetX, int insetY) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean link = layout.linkAt(e.getX() - insetX, e.getY() - insetY) != null;
                setCursor(link
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                String href = layout.linkAt(e.getX() - insetX, e.getY() - insetY);
                if (href == null || href.isEmpty()) {
                    return;
                }
                try {
                    BrowserUtil.browse(href);
                } catch (RuntimeException ignored) {
                    // Malformed links must not break the panel.
                    // 畸形链接不能把面板带崩。
                }
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    protected static String plain(List<InlineSpan> spans) {
        StringBuilder builder = new StringBuilder();
        if (spans != null) {
            for (InlineSpan span : spans) {
                builder.append(span.text);
            }
        }
        return builder.toString();
    }

    // Plain-text projection of a block, used by the message-level copy action.
    // 块的纯文本投影，供消息级「复制」使用。
    public static String plain(MarkdownBlock block) {
        if (block instanceof MarkdownBlock.Heading heading) {
            return plain(heading.spans());
        }
        if (block instanceof MarkdownBlock.Paragraph paragraph) {
            return plain(paragraph.spans());
        }
        if (block instanceof MarkdownBlock.Quote quote) {
            return plain(quote.spans());
        }
        if (block instanceof MarkdownBlock.CodeBlock code) {
            return code.code();
        }
        if (block instanceof MarkdownBlock.ListBlock list) {
            StringBuilder builder = new StringBuilder();
            for (MarkdownBlock.ListItem item : list.items()) {
                builder.append("  ".repeat(item.level()))
                        .append(item.marker()).append(' ').append(plain(item.spans())).append('\n');
            }
            return builder.toString();
        }
        if (block instanceof MarkdownBlock.Table table) {
            StringBuilder builder = new StringBuilder();
            for (List<String> row : table.header()) {
                builder.append(String.join(" | ", row)).append('\n');
            }
            for (List<String> row : table.rows()) {
                builder.append(String.join(" | ", row)).append('\n');
            }
            return builder.toString();
        }
        return "";
    }
}
