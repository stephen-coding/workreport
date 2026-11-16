package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.markdown.MarkdownBlock;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

// Hosts the block views of one Markdown document.
// Updating the document reuses every leading block that did not change, so streaming only ever
// rebuilds the tail: that is the whole point of moving off the HTML editor.
// 一个 Markdown 文档的块视图宿主。
// 更新时复用未变化的前缀块，流式输出只重建尾部——这是脱离 HTML 编辑器的核心收益。
public final class BlockPanel extends JPanel {

    private static final int GAP = 8;

    private final BlockListLayout layout = new BlockListLayout(UiMetrics.scale(GAP));
    // Mirrors the children one-to-one, so "the same block" survives arbitrary re-parses.
    // 与子组件一一对应，便于判断哪些块其实没变。
    private final List<MarkdownBlock> mounted = new ArrayList<>();

    private BlockView caretSource;

    public BlockPanel() {
        super(null);
        setLayout(layout);
        setOpaque(false);
    }

    public void setBlocks(List<MarkdownBlock> blocks) {
        List<MarkdownBlock> next = blocks == null ? List.of() : blocks;
        int common = 0;
        while (common < mounted.size() && common < next.size()
                && mounted.get(common).equals(next.get(common))) {
            common++;
        }
        if (common == mounted.size() && common == next.size()) {
            return;
        }
        for (int i = mounted.size() - 1; i >= common; i--) {
            remove(i);
            mounted.remove(i);
        }
        for (int i = common; i < next.size(); i++) {
            MarkdownBlock block = next.get(i);
            BlockView view = create(block);
            mounted.add(block);
            add(view);
        }
        caretSource = mounted.isEmpty() ? null : tailView();
        revalidate();
        repaint();
    }

    private BlockView tailView() {
        Component[] children = getComponents();
        for (int i = children.length - 1; i >= 0; i--) {
            if (children[i] instanceof BlockView view) {
                return view;
            }
        }
        return null;
    }

    // Height at a width, straight from the layout: no child resizing, no preferred-size recursion.
    // 指定宽度下的总高，直接问布局：不重置子组件尺寸，也不做首选尺寸递归。
    public int heightFor(int width) {
        return layout.totalHeight(this, Math.max(1, width));
    }

    // Blinking caret on the tail block; the caller owns the blink timing.
    // 尾块的闪烁光标，闪烁节奏由调用方控制。
    public void setCaretVisible(boolean visible) {
        if (caretSource == null) {
            caretSource = tailView();
        }
        if (caretSource != null) {
            caretSource.setCaretVisible(visible);
        }
    }

    public String plainText() {
        StringBuilder builder = new StringBuilder();
        for (MarkdownBlock block : mounted) {
            String text = BlockView.plain(block);
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private static BlockView create(MarkdownBlock block) {
        if (block instanceof MarkdownBlock.Heading heading) {
            return new HeadingView(heading.level(), heading.spans());
        }
        if (block instanceof MarkdownBlock.Paragraph paragraph) {
            return new ParagraphView(paragraph.spans());
        }
        if (block instanceof MarkdownBlock.ListBlock list) {
            return new ListView(list);
        }
        if (block instanceof MarkdownBlock.CodeBlock code) {
            return new CodeBlockView(code);
        }
        if (block instanceof MarkdownBlock.Quote quote) {
            return new QuoteView(quote.spans());
        }
        if (block instanceof MarkdownBlock.Table table) {
            return new TableView(table);
        }
        return new DividerView();
    }
}
