package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import java.awt.*;
import java.util.List;

// A paragraph: wrapped spans, no decoration. Blocks are separated by the layout gap.
// 普通段落：只做折行，不做装饰；块间距由布局统一给出。
public final class ParagraphView extends BlockView {

    private final InlineLayout layout;

    public ParagraphView(List<InlineSpan> spans) {
        layout = new InlineLayout(spans, UiTheme.font(), false);
        setOpaque(false);
        TextCopy.install(this, layout::plainText);
        installLinks(layout, 0, 0);
        setCaretTarget(layout, 0, 0);
    }

    @Override
    protected int computeHeight(int width) {
        return layout.measure(width);
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        layout.paint(g2, 0, 0);
        paintCaret(g2);
    }
}
