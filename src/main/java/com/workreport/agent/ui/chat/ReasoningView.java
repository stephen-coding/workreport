package com.workreport.agent.ui.chat;

import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiIcons;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The model's thinking process (``delta.reasoning_content`` / Ollama ``message.thinking``),
// folded into one row right above the answer it belongs to.
// While the model is still thinking the body is expanded, so the process is readable as it
// arrives; the first content token closes it and folds it into a single "Thinking · 12s · 3,204
// chars" header that stays clickable. The full text is kept - folding only hides it, never trims.
// 模型的思考过程（``reasoning_content`` / Ollama ``thinking``），折成正文上方的一行。
// 思考期间正文展开、随到随看；正文一开始就收起为「思考过程 · 12s · 3204 字」一行，点开仍是全文。
// 折叠只是隐藏，不做任何截断。
public final class ReasoningView extends ChatRowView {

    private final List<InlineLayout> lines = new ArrayList<>();
    private final List<String> paragraphs = new ArrayList<>();

    private final int pad = UiMetrics.scale(8);
    private final int lineGap = UiMetrics.scale(2);
    private final Rectangle headerArea = new Rectangle();

    private String text = "";
    private long startedAt;
    private long elapsedMs;
    private boolean running;
    private boolean expanded = true;
    // Once the user clicked the header, folding is their decision and finish() leaves it alone.
    // 用户点过标题之后，收起与否由用户决定，finish() 不再自动折叠。
    private boolean touched;
    private boolean hovered;

    public ReasoningView() {
        super(1d, false);
        setOpaque(false);
        TextCopy.install(this, () -> text);
        installMouse();
    }

    // Streaming append: the host passes the whole reasoning text accumulated so far.
    // 流式写入：宿主传入累计到目前的思考全文。
    public void setText(String value) {
        String next = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        if (next.equals(text)) {
            return;
        }
        if (startedAt == 0) {
            startedAt = System.currentTimeMillis();
        }
        running = true;
        text = next;
        paragraphs.clear();
        for (String line : next.split("\n", -1)) {
            paragraphs.add(line.isBlank() ? " " : line);
        }
        lines.clear();
        contentChanged();
    }

    // Freeze the clock and fold the body: the answer is about to start below this row.
    // 冻结计时并收起正文：答案马上要出现在这一行下面。
    public void finish() {
        if (startedAt > 0) {
            elapsedMs = System.currentTimeMillis() - startedAt;
            startedAt = 0;
        }
        running = false;
        if (!touched) {
            expanded = false;
        }
        lines.clear();
        contentChanged();
    }

    public boolean isEmpty() {
        return text.isBlank();
    }

    public String plainText() {
        return text;
    }

    // ---------------------------------------------------------------- measuring

    private int headerHeight() {
        return UiMetrics.metrics(UiTheme.small()).getHeight() + pad * 2;
    }

    private long elapsed() {
        return startedAt > 0 ? System.currentTimeMillis() - startedAt : elapsedMs;
    }

    private String headerLabel() {
        StringBuilder label = new StringBuilder(AgentBundle.message("ui.reasoning.title"));
        long millis = elapsed();
        if (millis > 0) {
            label.append(" · ").append(String.format(Locale.ROOT, "%.1fs", millis / 1000.0));
        }
        if (!text.isEmpty()) {
            label.append(" · ").append(AgentBundle.message("ui.reasoning.chars", text.length()));
        }
        return label.toString();
    }

    @Override
    protected int contentWidth(int maxWidth) {
        return maxWidth;
    }

    @Override
    protected int computeHeight(int width) {
        int header = headerHeight();
        headerArea.setBounds(0, 0, width, header);
        lines.clear();
        if (!expanded || paragraphs.isEmpty()) {
            return header;
        }
        int textWidth = Math.max(40, width - pad * 2);
        int height = header + pad;
        for (String paragraph : paragraphs) {
            InlineLayout layout = new InlineLayout(List.of(InlineSpan.plain(paragraph)),
                    UiTheme.small(), false);
            layout.setColorOverride(UiTheme::muted);
            layout.measure(textWidth);
            lines.add(layout);
            height += layout.height() + lineGap;
        }
        return Math.max(header, height - lineGap);
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        if (expanded || hovered) {
            int arc = UiMetrics.radiusChip();
            g2.setColor(UiTheme.withAlpha(UiTheme.SUBTLE, 160));
            g2.fill(new RoundRectangle2D.Float(0, 0, width, headerArea.height, arc, arc));
        }

        int chevron = UiMetrics.scale(12);
        Rectangle box = new Rectangle(pad, (headerArea.height - chevron) / 2, chevron, chevron);
        UiIcons.paintChevron(g2, box, UiTheme.muted(), expanded);

        Font font = UiTheme.small();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        g2.setColor(running ? UiTheme.text() : UiTheme.muted());
        int baseline = (headerArea.height - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(headerLabel(), pad + chevron + pad, baseline);

        if (!expanded) {
            return;
        }
        int y = headerArea.height + pad;
        for (InlineLayout layout : lines) {
            layout.paint(g2, pad, y);
            y += layout.height() + lineGap;
        }
    }

    // ---------------------------------------------------------------- interaction

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!headerArea.contains(e.getPoint())) {
                    return;
                }
                touched = true;
                expanded = !expanded;
                lines.clear();
                contentChanged();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(headerArea.contains(e.getPoint())
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }
}