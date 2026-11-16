package com.workreport.agent.ui.chat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;

// Vertical layout for measured rows and blocks.
// Every child caches its own height per width, so laying out the transcript is O(n) cheap checks
// instead of the old "ask every bubble for its preferred size and let it resize its children".
// 行/块的纵向布局：每个子组件按宽度缓存高度，布局时只做便宜的高度读取，
// 取代旧实现里「每行都递归测量并 setSize 子组件」的全量开销。
public final class BlockListLayout implements LayoutManager2 {

    // Anything the layout can position.
    // 可被布局定位的子组件契约。
    public interface Measured {
        // Width the child wants given the available width (rows may cap themselves).
        // 在给定可用宽度下，子组件希望的宽度（消息行可以自我限宽）。
        int preferredWidth(int availableWidth);

        // Height after the child knows its width; must be stable for the same width.
        // 已知宽度后的高度；同一宽度下必须稳定（由实现缓存）。
        int heightFor(int width);

        boolean alignRight();
    }

    private final int gap;

    public BlockListLayout(int gap) {
        this.gap = Math.max(0, gap);
    }

    @Override
    public void addLayoutComponent(Component component, Object constraints) {
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }

    // Nothing global to forget: each child owns its cached height.
    // 没有全局缓存需要清理：高度缓存归各子组件自己。
    @Override
    public void invalidateLayout(Container target) {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        int width = availableWidth(parent);
        Insets insets = parent.getInsets();
        return new Dimension(width + insets.left + insets.right,
                totalHeight(parent, width) + insets.top + insets.bottom);
    }

    // Total height of all children at one width; also used by hosts that measure a sub-panel.
    // 指定宽度下所有子组件的总高；也供需要测量子面板的宿主使用。
    public int totalHeight(Container parent, int width) {
        int height = 0;
        boolean first = true;
        for (Component child : parent.getComponents()) {
            if (!child.isVisible() || !(child instanceof Measured measured)) {
                continue;
            }
            int childWidth = measured.preferredWidth(width);
            height += measured.heightFor(childWidth);
            if (!first) {
                height += gap;
            }
            first = false;
        }
        return height;
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return new Dimension(1, 1);
    }

    @Override
    public Dimension maximumLayoutSize(Container target) {
        return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public float getLayoutAlignmentX(Container target) {
        return 0f;
    }

    @Override
    public float getLayoutAlignmentY(Container target) {
        return 0f;
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets insets = parent.getInsets();
        int available = availableWidth(parent);
        int y = insets.top;
        boolean first = true;
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (!(child instanceof Measured measured)) {
                child.setBounds(insets.left, y, available, Math.max(1, child.getPreferredSize().height));
                continue;
            }
            if (!first) {
                y += gap;
            }
            int childWidth = measured.preferredWidth(available);
            int childHeight = measured.heightFor(childWidth);
            int x = measured.alignRight()
                    ? insets.left + available - childWidth
                    : insets.left;
            child.setBounds(x, y, childWidth, childHeight);
            y += childHeight;
            first = false;
        }
    }

    private static int availableWidth(Container parent) {
        Insets insets = parent.getInsets();
        int width = parent.getWidth() - insets.left - insets.right;
        if (width <= 0) {
            width = 480;
        }
        return width;
    }
}
