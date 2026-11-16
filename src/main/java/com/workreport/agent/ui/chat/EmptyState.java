package com.workreport.agent.ui.chat;

import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiIcons;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// First-run panel: product mark, a title and four suggestion cards that drop a ready-made prompt
// into the composer. It replaces the paragraph of instructions that used to greet every new tab.
// 首次打开的空态：产品图标 + 标题 + 四张建议卡，点一下就填好提示词。
// 取代过去每个新标签都要读的一段说明文字。
public final class EmptyState extends MeasuredComponent {

    private static final String[][] SUGGESTIONS = {
            {"ui.empty.suggest.code", "ui.empty.suggest.code.prompt"},
            {"ui.empty.suggest.bug", "ui.empty.suggest.bug.prompt"},
            {"ui.empty.suggest.test", "ui.empty.suggest.test.prompt"},
            {"ui.empty.suggest.report", "ui.empty.suggest.report.prompt"}
    };

    private static final int MAX_CONTENT = 440;

    private final List<Rectangle> cards = new ArrayList<>();
    private final List<InlineLayout> cardTitles = new ArrayList<>();
    private final List<InlineLayout> cardPrompts = new ArrayList<>();
    private final InlineLayout title;
    private final InlineLayout subtitle;
    private InlineLayout hint;

    private Consumer<String> onPick;
    private int hoverIndex = -1;
    private int contentWidth;
    private int iconTop;
    private int titleTop;
    private int subtitleTop;
    private int hintTop = -1;

    public EmptyState() {
        setOpaque(false);
        title = new InlineLayout(List.of(InlineSpan.plain(AgentBundle.message("ui.empty.title")).withBold()),
                UiTheme.h2(), false);
        subtitle = new InlineLayout(List.of(InlineSpan.plain(AgentBundle.message("ui.empty.subtitle"))),
                UiTheme.small(), true);
        for (String[] suggestion : SUGGESTIONS) {
            cardTitles.add(new InlineLayout(List.of(InlineSpan.plain(AgentBundle.message(suggestion[0])).withBold()),
                    UiTheme.font(), false));
            cardPrompts.add(new InlineLayout(List.of(InlineSpan.plain(AgentBundle.message(suggestion[1]))),
                    UiTheme.small(), true));
        }
        installMouse();
    }

    public void setOnPick(Consumer<String> consumer) {
        onPick = consumer;
    }

    // A single muted line under the cards, used for the one hint worth keeping.
    // 卡片下方的一行浅色提示，只保留真正有用的那一句。
    public void setHint(String text) {
        hint = text == null || text.isBlank()
                ? null
                : new InlineLayout(List.of(InlineSpan.plain(text)), UiTheme.small(), true);
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------- measuring

    private int gap() {
        return UiMetrics.scale(10);
    }

    private int cardWidth(int width) {
        return (contentWidth - gap()) / 2;
    }

    private int cardHeight() {
        return UiMetrics.scale(58);
    }

    @Override
    protected int computeHeight(int width) {
        contentWidth = Math.min(MAX_CONTENT, Math.max(UiMetrics.scale(240), width));
        int left = (width - contentWidth) / 2;

        iconTop = UiMetrics.scale(28);
        titleTop = iconTop + UiMetrics.scale(48) + UiMetrics.scale(10);
        subtitleTop = titleTop + title.measure(contentWidth) + UiMetrics.scale(4);
        int y = subtitleTop + subtitle.measure(contentWidth) + UiMetrics.scale(18);

        cards.clear();
        int cardW = cardWidth(width);
        for (int i = 0; i < SUGGESTIONS.length; i++) {
            int column = i % 2;
            int row = i / 2;
            int x = left + column * (cardW + gap());
            int cardY = y + row * (cardHeight() + gap());
            cards.add(new Rectangle(x, cardY, cardW, cardHeight()));
            int textWidth = Math.max(40, cardW - UiMetrics.scale(20));
            cardTitles.get(i).measure(textWidth);
            cardPrompts.get(i).measure(textWidth);
        }
        y += 2 * cardHeight() + gap();

        hintTop = -1;
        if (hint != null) {
            hintTop = y + UiMetrics.scale(16);
            y = hintTop + hint.measure(contentWidth);
        }
        return y + UiMetrics.scale(24);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        int left = (width - contentWidth) / 2;

        Icon icon = UiIcons.scaled("agent", UiMetrics.scale(40));
        icon.paintIcon(this, g2, left + (contentWidth - icon.getIconWidth()) / 2, iconTop);

        title.paint(g2, left + (contentWidth - title.maxAdvance()) / 2, titleTop);
        subtitle.paint(g2, left + (contentWidth - subtitle.maxAdvance()) / 2, subtitleTop);

        for (int i = 0; i < cards.size(); i++) {
            paintCard(g2, i);
        }
        if (hint != null && hintTop >= 0) {
            hint.paint(g2, left + (contentWidth - hint.maxAdvance()) / 2, hintTop);
        }
    }

    private void paintCard(Graphics2D g2, int index) {
        Rectangle bounds = cards.get(index);
        boolean hot = index == hoverIndex;
        int arc = UiMetrics.radiusCard();
        g2.setColor(hot ? UiTheme.HOVER : UiTheme.SUBTLE);
        g2.fill(new RoundRectangle2D.Float(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc));
        g2.setColor(hot ? UiTheme.ACCENT : UiTheme.BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(bounds.x + 0.5f, bounds.y + 0.5f,
                bounds.width - 1f, bounds.height - 1f, arc, arc));

        int textX = bounds.x + UiMetrics.scale(10);
        int textY = bounds.y + UiMetrics.scale(9);
        cardTitles.get(index).paint(g2, textX, textY);
        int promptTop = textY + cardTitles.get(index).height() + UiMetrics.scale(3);
        int maxPromptHeight = bounds.y + bounds.height - UiMetrics.scale(9) - promptTop;
        Shape oldClip = g2.getClip();
        g2.clipRect(textX, promptTop, bounds.width - UiMetrics.scale(20), Math.max(1, maxPromptHeight));
        cardPrompts.get(index).paint(g2, textX, promptTop);
        g2.setClip(oldClip);
    }

    // ---------------------------------------------------------------- interaction

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = indexAt(e.getPoint());
                if (index != hoverIndex) {
                    hoverIndex = index;
                    repaint();
                }
                setCursor(index >= 0
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverIndex != -1) {
                    hoverIndex = -1;
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int index = indexAt(e.getPoint());
                if (index < 0 || onPick == null) {
                    return;
                }
                onPick.accept(AgentBundle.message(SUGGESTIONS[index][1]));
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private int indexAt(Point point) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).contains(point)) {
                return i;
            }
        }
        return -1;
    }
}
