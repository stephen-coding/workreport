package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiTheme;

import java.awt.*;

// Horizontal rule between sections.
// 小节之间的分隔线。
public final class DividerView extends BlockView {

    public DividerView() {
        setOpaque(false);
    }

    @Override
    protected int computeHeight(int width) {
        return 13;
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        g2.setColor(UiTheme.BORDER);
        int y = height / 2;
        g2.drawLine(0, y, width, y);
    }
}
