package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.MarkdownBlock;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

// Bullet / numbered list with a hanging indent: the marker column is fixed, wrapped lines align
// with the text instead of running under the marker, and each nesting level adds a fixed step.
// 带悬挂缩进的列表：符号列宽度固定，续行与正文左对齐；每级嵌套固定步进。
public final class ListView extends BlockView {

    private static final int STEP = 16;
    private static final int GUTTER = 20;
    private static final int ITEM_GAP = 4;

    private final MarkdownBlock.ListBlock block;
    private final List<InlineLayout> layouts = new ArrayList<>();
    private final List<Integer> tops = new ArrayList<>();

    public ListView(MarkdownBlock.ListBlock block) {
        this.block = block;
        setOpaque(false);
        TextCopy.install(this, () -> plain(block));
    }

    private int step() {
        return UiMetrics.scale(STEP);
    }

    private int gutter() {
        return UiMetrics.scale(GUTTER);
    }

    @Override
    protected int computeHeight(int width) {
        layouts.clear();
        tops.clear();
        int y = 0;
        for (MarkdownBlock.ListItem item : block.items()) {
            InlineLayout layout = new InlineLayout(item.spans(), UiTheme.font(), false);
            int textWidth = width - item.level() * step() - gutter();
            layout.measure(Math.max(24, textWidth));
            layouts.add(layout);
            tops.add(y);
            y += layout.height() + UiMetrics.scale(ITEM_GAP);
        }
        // The caret follows the last item, so a streaming list still shows the write position.
        // 光标跟随最后一项，流式输出列表时也能看到写入位置。
        if (!layouts.isEmpty()) {
            int last = layouts.size() - 1;
            int left = block.items().get(last).level() * step();
            setCaretTarget(layouts.get(last), left + gutter(), tops.get(last));
        }
        return Math.max(1, y - UiMetrics.scale(ITEM_GAP));
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        Font font = UiTheme.font();
        FontMetrics metrics = UiMetrics.metrics(font);
        for (int i = 0; i < layouts.size() && i < block.items().size(); i++) {
            MarkdownBlock.ListItem item = block.items().get(i);
            int left = item.level() * step();
            int top = tops.get(i);
            InlineLayout layout = layouts.get(i);

            g2.setFont(font);
            g2.setColor(UiTheme.muted());
            int markerRight = left + gutter() - UiMetrics.scale(6);
            int baseline = top + metrics.getAscent();
            String marker = item.marker();
            int markerWidth = metrics.stringWidth(marker);
            g2.drawString(marker, markerRight - markerWidth, baseline);

            layout.paint(g2, left + gutter(), top);
        }
        paintCaret(g2);
    }
}
