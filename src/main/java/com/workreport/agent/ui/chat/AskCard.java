package com.workreport.agent.ui.chat;

import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

// In-place question from the agent (the ``ask_user`` tool).
// The agent pauses its turn until this card is answered, so the card has to be complete:
// options, an optional "other" line the user types into, and a skip path that tells the
// model to continue with assumptions instead of waiting for an answer that never comes.
// Agent 就地向用户提问（``ask_user`` 工具）。
// 提问期间 Core 的这一轮停在等待状态，所以卡片必须自己闭环：选项、可填写的「其它」，
// 以及一条「跳过」——跳过会告诉模型带着假设继续，而不是干等一个不会来的回答。
public final class AskCard extends ChatRowView {

    private static final int PAD_X = UiMetrics.scale(12);
    private static final int PAD_Y = UiMetrics.scale(10);
    private static final int GAP = UiMetrics.scale(6);
    private static final int BAR = UiMetrics.scale(3);
    private static final int BUTTON_H = UiMetrics.scale(22);
    private static final int ROW_H = UiMetrics.scale(24);
    private static final int MARK = UiMetrics.scale(12);
    private static final int INDENT = UiMetrics.scale(18);
    private static final int FIELD_H = UiMetrics.scale(24);
    private static final int MAX_QUESTIONS = 3;

    // One question with everything needed to lay it out at the current width.
    // 单个问题及其在当前宽度下的排版结果。
    private static final class Q {
        final String header;
        final String text;
        final List<String> labels;
        final List<String> descriptions;
        final boolean multi;
        final boolean allowCustom;

        final Set<Integer> picked = new LinkedHashSet<>();
        boolean custom;

        List<String> lines = List.of();
        List<List<String>> optionLines = new ArrayList<>();
        final List<Rectangle> optionRects = new ArrayList<>();
        final Rectangle customRect = new Rectangle();
        final Rectangle fieldRect = new Rectangle();
        JTextField field;
        int titleY;
        int linesY;

        Q(String header, String text, List<String> labels, List<String> descriptions,
          boolean multi, boolean allowCustom) {
            this.header = header;
            this.text = text;
            this.labels = labels;
            this.descriptions = descriptions;
            this.multi = multi;
            this.allowCustom = allowCustom;
        }
    }

    // What the user answered; ``skipped`` is the "carry on with assumptions" path.
    // 用户的回答；``skipped`` 表示「跳过，按假设继续」。
    public static final class Answer {
        public final List<List<String>> picked = new ArrayList<>();
        public final List<String> custom = new ArrayList<>();
        public boolean skipped;
    }

    private final List<Q> questions = new ArrayList<>();

    private final Rectangle skipArea = new Rectangle();
    private final Rectangle submitArea = new Rectangle();

    private int layoutWidth = -1;
    private int hovered = -1;
    private boolean answered;
    private boolean withdrawn;

    private Consumer<Answer> answerHandler;

    public AskCard() {
        super(1d, false);
        setOpaque(false);
        setFocusable(true);
        setToolTipText(AgentBundle.message("ui.ask.tip"));
        installKeys();
        installMouse();
    }

    // ---------------------------------------------------------------- content

    // Append one question; call ``finishQuestions`` once every one is added.
    // 追加一个问题；全部加完后调用 ``finishQuestions``。
    public AskCard question(String header, String text, List<String> labels,
                            List<String> descriptions, boolean multi, boolean allowCustom) {
        if (questions.size() >= MAX_QUESTIONS || text == null || text.isBlank()) {
            return this;
        }
        questions.add(new Q(header == null ? "" : header, text,
                labels == null ? List.of() : labels,
                descriptions == null ? List.of() : descriptions,
                multi, allowCustom));
        return this;
    }

    public AskCard finishQuestions() {
        contentChanged();
        return this;
    }

    public void setAnswerHandler(Consumer<Answer> handler) {
        answerHandler = handler;
    }

    public boolean isAnswered() {
        return answered;
    }

    public boolean isEmpty() {
        return questions.isEmpty();
    }

    // ---------------------------------------------------------------- answering

    // Send the collected answer; safe to call once (later calls are ignored).
    // 提交当前选择；只生效一次。
    public void submit() {
        if (answered || questions.isEmpty()) {
            return;
        }
        Answer answer = new Answer();
        for (Q q : questions) {
            List<String> picked = new ArrayList<>();
            for (Integer index : q.picked) {
                if (index >= 0 && index < q.labels.size()) {
                    picked.add(q.labels.get(index));
                }
            }
            answer.picked.add(picked);
            answer.custom.add(q.custom && q.field != null ? q.field.getText().trim() : "");
        }
        answered = true;
        contentChanged();
        if (answerHandler != null) {
            answerHandler.accept(answer);
        }
    }

    // "Carry on with assumptions": the model gets an explicit note instead of silence.
    // 「按假设继续」：让模型收到一条明确说明，而不是无声超时。
    public void skip() {
        if (answered) {
            return;
        }
        Answer answer = new Answer();
        answer.skipped = true;
        answered = true;
        contentChanged();
        if (answerHandler != null) {
            answerHandler.accept(answer);
        }
    }

    // Core cancelled the wait (task cancelled or timed out): keep the card, mark it dead.
    // Core 取消了等待（任务被取消或超时）：卡片留下，只标记为已取消。
    public void withdraw() {
        if (answered) {
            return;
        }
        answered = true;
        withdrawn = true;
        contentChanged();
    }

    // Grab the keyboard when the card lands, unless the user is typing in the composer.
    // 卡片出现时拿焦点，但用户正在输入框里打字时不抢。
    @Override
    public void addNotify() {
        super.addNotify();
        if (!answered) {
            SwingUtilities.invokeLater(this::requestFocusInWindow);
        }
    }

    @Override
    public void removeNotify() {
        // Leaving the tree without an answer (session closed) must not strand the Core.
        // 离开组件树时仍未答复（会话被关掉），不能让 Core 一直等。
        if (!answered) {
            skip();
        }
        super.removeNotify();
    }

    // ---------------------------------------------------------------- layout

    private int innerWidth(int width) {
        return Math.max(80, width - PAD_X * 2);
    }

    private void ensureLayout(int width) {
        if (width == layoutWidth && !questions.isEmpty() && !questions.get(0).lines.isEmpty()) {
            return;
        }
        layoutWidth = width;
        FontMetrics body = UiMetrics.metrics(UiTheme.font());
        FontMetrics small = UiMetrics.metrics(UiTheme.small());
        FontMetrics bold = UiMetrics.metrics(UiTheme.bold());
        int inner = innerWidth(width);
        int y = PAD_Y;

        y += bold.getHeight() + GAP;
        for (Q q : questions) {
            q.lines = wrap(q.text, body, inner - INDENT);
            q.titleY = y;
            y += q.lines.size() * UiMetrics.lineHeight(UiTheme.font()) + GAP;

            q.optionLines.clear();
            q.optionRects.clear();
            for (int i = 0; i < q.labels.size(); i++) {
                String description = i < q.descriptions.size() ? q.descriptions.get(i) : "";
                String label = description.isEmpty()
                        ? q.labels.get(i) : q.labels.get(i) + "  —  " + description;
                List<String> lines = wrap(label, small, inner - INDENT - MARK - GAP);
                q.optionLines.add(lines);
                int height = Math.max(ROW_H, lines.size() * small.getHeight() + UiMetrics.scale(8));
                q.optionRects.add(new Rectangle(PAD_X + INDENT, y, inner - INDENT, height));
                y += height;
            }
            if (q.allowCustom) {
                q.customRect.setBounds(PAD_X + INDENT, y, inner - INDENT, ROW_H);
                y += ROW_H;
            } else {
                q.customRect.setBounds(0, 0, 0, 0);
            }
            if (q.custom) {
                q.fieldRect.setBounds(PAD_X + INDENT, y + UiMetrics.xs(),
                        inner - INDENT, FIELD_H);
                y += FIELD_H + UiMetrics.xs() + GAP;
            } else {
                q.fieldRect.setBounds(0, 0, 0, 0);
            }
            y += GAP;
        }

        int rowTop = y;
        int submitWidth = small.stringWidth(AgentBundle.message("ui.ask.submit")) + UiMetrics.scale(24);
        int skipWidth = small.stringWidth(AgentBundle.message("ui.ask.skip")) + UiMetrics.scale(20);
        submitArea.setBounds(width - PAD_X - submitWidth, rowTop, submitWidth, BUTTON_H);
        skipArea.setBounds(submitArea.x - UiMetrics.xs() - skipWidth, rowTop, skipWidth, BUTTON_H);
    }

    @Override
    protected int computeHeight(int width) {
        ensureLayout(width);
        return skipArea.y + BUTTON_H + PAD_Y;
    }

    // Greedy character wrap: works for CJK and for one long latin option alike.
    // 逐字符贪心折行：中文与一个很长的英文选项都适用。
    private static List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(line.toString());
                line.setLength(0);
                continue;
            }
            line.append(c);
            if (line.length() > 1 && metrics.stringWidth(line.toString()) > maxWidth) {
                line.setLength(line.length() - 1);
                lines.add(line.toString());
                line.setLength(0);
                line.append(c);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        ensureLayout(width);
        for (Q q : questions) {
            if (q.field != null && q.custom) {
                q.field.setBounds(q.fieldRect);
            }
        }
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        ensureLayout(width);
        int arc = UiMetrics.radiusCard();
        g2.setColor(UiTheme.RAISED);
        g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        g2.setColor(UiTheme.BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));

        Shape oldClip = g2.getClip();
        g2.clip(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        g2.setColor(answered ? (withdrawn ? UiTheme.muted() : UiTheme.OK) : UiTheme.WARN);
        g2.fillRect(0, 0, BAR, height);
        g2.setClip(oldClip);

        Font titleFont = UiTheme.bold();
        FontMetrics titleMetrics = UiMetrics.metrics(titleFont);
        g2.setFont(titleFont);
        g2.setColor(UiTheme.text());
        g2.drawString(AgentBundle.message("ui.ask.title"), PAD_X, PAD_Y + titleMetrics.getAscent());

        for (int qi = 0; qi < questions.size(); qi++) {
            paintQuestion(g2, questions.get(qi), qi);
        }
        paintFooter(g2, width);
    }

    private void paintQuestion(Graphics2D g2, Q q, int questionIndex) {
        int textX = PAD_X + INDENT;
        FontMetrics small = UiMetrics.metrics(UiTheme.small());

        // Header chip: a short label so a three-question card stays scannable.
        // 短标签 chip：一次问三个问题时也能一眼分清。
        String chip = q.header.isEmpty() ? "" : q.header;
        if (!chip.isEmpty()) {
            int chipWidth = small.stringWidth(chip) + UiMetrics.scale(10);
            int chipHeight = small.getHeight();
            g2.setColor(UiTheme.SUBTLE);
            g2.fill(new RoundRectangle2D.Float(PAD_X, q.titleY, chipWidth, chipHeight,
                    UiMetrics.radiusChip(), UiMetrics.radiusChip()));
            g2.setFont(UiTheme.small());
            g2.setColor(UiTheme.muted());
            g2.drawString(chip, PAD_X + UiMetrics.scale(5),
                    q.titleY + (chipHeight - small.getHeight()) / 2 + small.getAscent());
        }

        FontMetrics body = UiMetrics.metrics(UiTheme.font());
        g2.setFont(UiTheme.font());
        g2.setColor(answered && !withdrawn ? UiTheme.muted() : UiTheme.text());
        int y = q.linesY;
        for (String line : q.lines) {
            g2.drawString(line, textX, y + body.getAscent());
            y += UiMetrics.lineHeight(UiTheme.font());
        }

        for (int i = 0; i < q.optionRects.size(); i++) {
            paintOption(g2, q, questionIndex, i);
        }
        if (q.allowCustom) {
            paintOption(g2, q, questionIndex, -1);
        }
    }

    private void paintOption(Graphics2D g2, Q q, int questionIndex, int index) {
        Rectangle area = index < 0 ? q.customRect : q.optionRects.get(index);
        boolean picked = index >= 0 ? q.picked.contains(index) : q.custom;
        int id = index < 0 ? 100 * (questionIndex + 1) - 1 : 100 * (questionIndex + 1) + index;
        boolean hot = !answered && hovered == id;
        FontMetrics small = UiMetrics.metrics(UiTheme.small());

        if (hot) {
            g2.setColor(UiTheme.HOVER);
            g2.fill(new RoundRectangle2D.Float(area.x - UiMetrics.xs(), area.y,
                    area.width + UiMetrics.xs() * 2, area.height,
                    UiMetrics.radiusChip(), UiMetrics.radiusChip()));
        }

        int markY = area.y + (area.height - MARK) / 2;
        int markX = area.x;
        g2.setStroke(new BasicStroke(UiMetrics.scale(1) + 0.5f));
        if (q.multi) {
            g2.setColor(picked ? UiTheme.ACCENT : UiTheme.BORDER);
            if (picked) {
                g2.fill(new RoundRectangle2D.Float(markX, markY, MARK, MARK,
                        UiMetrics.scale(3), UiMetrics.scale(3)));
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(Math.max(1.4f, UiMetrics.scale(1) + 0.6f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(markX + MARK / 4, markY + MARK / 2, markX + MARK / 2, markY + MARK * 3 / 4);
                g2.drawLine(markX + MARK / 2, markY + MARK * 3 / 4, markX + MARK * 4 / 5, markY + MARK / 4);
            } else {
                g2.draw(new RoundRectangle2D.Float(markX + 0.5f, markY + 0.5f, MARK - 1f, MARK - 1f,
                        UiMetrics.scale(3), UiMetrics.scale(3)));
            }
        } else {
            g2.setColor(picked ? UiTheme.ACCENT : UiTheme.BORDER);
            g2.drawOval(markX, markY, MARK, MARK);
            if (picked) {
                int dot = MARK / 2;
                g2.fillOval(markX + (MARK - dot) / 2, markY + (MARK - dot) / 2, dot, dot);
            }
        }

        g2.setFont(UiTheme.small());
        g2.setColor(answered && !withdrawn ? UiTheme.muted() : UiTheme.text());
        int textX = area.x + MARK + GAP;
        int baseline = area.y + UiMetrics.scale(4) + small.getAscent();
        if (index < 0) {
            g2.drawString(AgentBundle.message("ui.ask.custom"), textX, baseline);
        } else {
            List<String> lines = q.optionLines.get(index);
            for (String line : lines) {
                g2.drawString(line, textX, baseline);
                baseline += small.getHeight();
            }
        }
    }

    private void paintFooter(Graphics2D g2, int width) {
        Font font = UiTheme.small();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        if (answered) {
            String text = AgentBundle.message(withdrawn ? "ui.ask.withdrawn" : "ui.ask.answered");
            g2.setColor(withdrawn ? UiTheme.muted() : UiTheme.OK);
            int baseline = skipArea.y + (BUTTON_H - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(text, width - PAD_X - metrics.stringWidth(text), baseline);
            return;
        }
        g2.setColor(UiTheme.muted());
        int hintBaseline = skipArea.y + (BUTTON_H - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(AgentBundle.message("ui.ask.keys"), PAD_X, hintBaseline);
        paintButton(g2, skipArea, AgentBundle.message("ui.ask.skip"), false, hovered == -2);
        paintButton(g2, submitArea, AgentBundle.message("ui.ask.submit"), true, hovered == -3);
    }

    private void paintButton(Graphics2D g2, Rectangle area, String label, boolean primary,
                             boolean hovered) {
        int arc = UiMetrics.radiusButton();
        if (primary) {
            g2.setColor(UiTheme.ACCENT);
            g2.fill(new RoundRectangle2D.Float(area.x, area.y, area.width, area.height, arc, arc));
            if (hovered) {
                g2.setColor(new Color(255, 255, 255, 46));
                g2.fill(new RoundRectangle2D.Float(area.x, area.y, area.width, area.height, arc, arc));
            }
        } else {
            g2.setColor(hovered ? UiTheme.HOVER : UiTheme.SUBTLE);
            g2.fill(new RoundRectangle2D.Float(area.x, area.y, area.width, area.height, arc, arc));
            g2.setColor(UiTheme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(area.x + 0.5f, area.y + 0.5f,
                    area.width - 1f, area.height - 1f, arc, arc));
        }
        Font font = UiTheme.small();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);
        g2.setColor(primary ? Color.WHITE : UiTheme.text());
        int baseline = area.y + (area.height - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(label, area.x + (area.width - metrics.stringWidth(label)) / 2, baseline);
    }

    // ---------------------------------------------------------------- interaction

    private int hit(MouseEvent event) {
        Point point = event.getPoint();
        if (answered) {
            return -4;
        }
        if (submitArea.contains(point)) {
            return -3;
        }
        if (skipArea.contains(point)) {
            return -2;
        }
        for (int qi = 0; qi < questions.size(); qi++) {
            Q q = questions.get(qi);
            for (int oi = 0; oi < q.optionRects.size(); oi++) {
                if (q.optionRects.get(oi).contains(point)) {
                    return 100 * (qi + 1) + oi;
                }
            }
            if (q.allowCustom && q.customRect.contains(point)) {
                return 100 * (qi + 1) - 1;
            }
        }
        return -4;
    }

    private void installKeys() {
        InputMap keys = getInputMap(WHEN_FOCUSED);
        ActionMap actions = getActionMap();
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ask-submit");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ask-skip");
        actions.put("ask-submit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                submit();
            }
        });
        actions.put("ask-skip", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                skip();
            }
        });
    }

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int next = hit(e);
                setCursor(next == -4 ? Cursor.getDefaultCursor()
                        : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                if (next != hovered) {
                    hovered = next;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hovered != -4) {
                    hovered = -4;
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (answered || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Point point = e.getPoint();
                if (submitArea.contains(point)) {
                    submit();
                    return;
                }
                if (skipArea.contains(point)) {
                    skip();
                    return;
                }
                for (Q q : questions) {
                    for (int oi = 0; oi < q.optionRects.size(); oi++) {
                        if (q.optionRects.get(oi).contains(point)) {
                            toggle(q, oi);
                            return;
                        }
                    }
                    if (q.allowCustom && q.customRect.contains(point)) {
                        toggleCustom(q);
                        return;
                    }
                }
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    // Single-select replaces the choice; multi-select toggles one box.
    // 单选替换选择；多选开关单个选项。
    private void toggle(Q q, int index) {
        if (q.picked.contains(index)) {
            q.picked.remove(index);
        } else {
            if (!q.multi) {
                q.picked.clear();
            }
            q.picked.add(index);
        }
        // Choosing an option in a single-select question drops the custom answer.
        // 单选题重新选了选项，就把「其它」的自定义答案撤掉。
        if (!q.multi && q.custom) {
            toggleCustom(q);
            return;
        }
        contentChanged();
    }

    // The "other" row wires up a real text field, so IME / paste / selection all work.
    // 「其它」行挂一个真实输入框：输入法、粘贴、选中都按系统行为走。
    private void toggleCustom(Q q) {
        q.custom = !q.custom;
        if (q.custom) {
            if (!q.multi) {
                q.picked.clear();
            }
            if (q.field == null) {
                q.field = new JTextField();
                q.field.putClientProperty("JTextField.placeholderText",
                        AgentBundle.message("ui.ask.custom.hint"));
                q.field.setFont(UiTheme.font());
                q.field.addActionListener(e -> submit());
                q.field.getInputMap(JComponent.WHEN_FOCUSED)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ask-skip");
                q.field.getActionMap().put("ask-skip", new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        skip();
                    }
                });
            }
            add(q.field);
        } else if (q.field != null) {
            remove(q.field);
        }
        contentChanged();
        revalidate();
        if (q.custom && q.field != null) {
            SwingUtilities.invokeLater(q.field::requestFocusInWindow);
        }
    }
}
