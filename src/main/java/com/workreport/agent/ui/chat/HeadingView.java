package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import java.awt.*;
import java.util.List;

// Heading block: level drives the font and the space above, so a long answer keeps its outline.
// 标题块：层级决定字号与上间距，长回答的层次一眼可辨。
public final class HeadingView extends BlockView {

    private final InlineLayout layout;
    private final int topGap;

    public HeadingView(int level, List<InlineSpan> spans) {
        Font font = switch (Math.max(1, Math.min(6, level))) {
            case 1 -> UiTheme.h1();
            case 2 -> UiTheme.h2();
            default -> UiTheme.h3();
        };
        topGap = UiMetrics.scale(level <= 1 ? 20 : level == 2 ? 16 : 14);
        layout = new InlineLayout(spans, font, false);
        setOpaque(false);
        TextCopy.install(this, layout::plainText);
        installLinks(layout, 0, topGap);
        setCaretTarget(layout, 0, topGap);
    }

    @Override
    protected int computeHeight(int width) {
        return topGap + layout.measure(width);
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        layout.paint(g2, 0, topGap);
        paintCaret(g2);
    }
}
