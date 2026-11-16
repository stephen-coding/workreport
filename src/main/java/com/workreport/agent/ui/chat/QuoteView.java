package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import java.awt.*;
import java.util.List;

// Quote: a 3 px accent bar on the left and muted text on the right.
// 引用块：左侧 3 px 强调竖条 + 浅色正文。
public final class QuoteView extends BlockView {

    private final InlineLayout layout;
    private final int bar = UiMetrics.scale(3);
    private final int indent = UiMetrics.scale(12);
    private final int padY = UiMetrics.scale(4);

    public QuoteView(List<InlineSpan> spans) {
        layout = new InlineLayout(spans, UiTheme.font(), true);
        setOpaque(false);
        TextCopy.install(this, layout::plainText);
        installLinks(layout, indent, padY);
        setCaretTarget(layout, indent, padY);
    }

    @Override
    protected int computeHeight(int width) {
        return layout.measure(width - indent) + padY * 2;
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        g2.setColor(UiTheme.withAlpha(UiTheme.ACCENT, 102));
        g2.fillRoundRect(0, 0, bar, height, bar, bar);
        layout.paint(g2, indent, padY);
        paintCaret(g2);
    }
}
