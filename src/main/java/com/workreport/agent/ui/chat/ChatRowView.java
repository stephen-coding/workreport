package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiMetrics;

// Base class for every transcript row (user bubble, agent card, step group, notice).
// It only answers the layout's two questions: how wide do I want to be, and do I hug the right edge.
// 对话区每一行的基类（用户气泡、助手卡片、步骤组、提示行）。
// 它只回答布局的两个问题：想要多宽、是否贴右边。
public abstract class ChatRowView extends MeasuredComponent {

    private final double ratio;
    private final boolean rightAligned;

    protected ChatRowView(double ratio, boolean rightAligned) {
        this.ratio = ratio <= 0d ? 1d : ratio;
        this.rightAligned = rightAligned;
        setOpaque(false);
    }

    // Upper bound of the row width, as a share of the available width.
    // 行宽上限，按可用宽度的比例计算。
    protected final int maxWidth(int availableWidth) {
        return Math.max(UiMetrics.scale(140), Math.round(availableWidth * (float) ratio));
    }

    // Natural width of the content; rows that paint a full-width card just return the cap.
    // 内容的自然宽度；铺满整行的卡片直接返回上限即可。
    protected int contentWidth(int maxWidth) {
        return maxWidth;
    }

    @Override
    public final int preferredWidth(int availableWidth) {
        int cap = maxWidth(availableWidth);
        int wanted = contentWidth(cap);
        return Math.min(cap, Math.max(UiMetrics.scale(80), wanted));
    }

    @Override
    public final boolean alignRight() {
        return rightAligned;
    }

    // Called by the host when the row's content changed shape (streaming, expand, toggle).
    // 内容形状变化（流式、展开、折叠）时由宿主调用。
    public void contentChanged() {
        invalidateHeight();
        revalidate();
        repaint();
    }
}
