package com.workreport.agent.ui.chat;

import javax.swing.*;
import java.awt.*;

// Base class for anything the transcript lays out: a Markdown block or a message row.
// The one thing it guarantees is that measuring is cached per width, so a repaint caused by a
// streaming tail never re-measures the whole conversation.
// 对话区里所有可布局组件的基类（Markdown 块与消息行）。
// 它保证「按宽度缓存测量结果」：流式刷新尾部时，其余行不会重新测量。
public abstract class MeasuredComponent extends JComponent implements BlockListLayout.Measured {

    private int measuredWidth = -1;
    private int measuredHeight;

    @Override
    public final int heightFor(int width) {
        int safeWidth = Math.max(1, width);
        if (safeWidth != measuredWidth) {
            measuredWidth = safeWidth;
            measuredHeight = Math.max(1, computeHeight(safeWidth));
        }
        return measuredHeight;
    }

    @Override
    public int preferredWidth(int availableWidth) {
        return availableWidth;
    }

    @Override
    public boolean alignRight() {
        return false;
    }

    // Drop the cached height; call after the content changed.
    // 内容变化后丢弃高度缓存。
    protected final void invalidateHeight() {
        measuredWidth = -1;
    }

    protected abstract int computeHeight(int width);

    protected abstract void paintBlock(Graphics2D g2, int width, int height);

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        try {
            paintBlock(g2, getWidth(), getHeight());
        } finally {
            g2.dispose();
        }
    }
}
