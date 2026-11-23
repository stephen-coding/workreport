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
import java.util.List;
import java.util.function.Consumer;

// In-place confirmation for a Core-initiated sensitive call (an MCP tool call).
// The modal dialog it replaces hid the conversation and could only be answered with the mouse;
// this card stays inside the transcript, so the context that led to the call is still visible.
// The team pre-approval checkpoint reuses the card with its own title/copy and a taller body,
// because it carries a plan plus the first diff rather than one JSON argument block.
// 就地确认 Core 发起的敏感操作（MCP 工具调用）。
// 卡片留在对话区里，调用前的上下文不被遮挡，Enter 允许 / Esc 拒绝，键盘即可答复。
// 团队预审检查点复用本卡片，只替换标题/说明并把正文放高：它要展示计划与首个 diff。
public final class ToolCallCard extends ChatRowView {

    private final String server;
    private final String tool;
    private final String args;
    private final String titleKey;
    private final String descKey;
    private final String argsLabelKey;

    // Arguments are shown as a short pre: enough to recognise the call, never a wall of JSON.
    // 参数只展示前几行：够认出这次调用了什么，又不至于把对话区铺满 JSON。
    private final int maxArgLines;

    private final int padX = UiMetrics.scale(12);
    private final int padY = UiMetrics.scale(10);
    private final int gap = UiMetrics.scale(6);
    private final int bar = UiMetrics.scale(3);
    private final int buttonHeight = UiMetrics.scale(22);

    private final Rectangle allowArea = new Rectangle();
    private final Rectangle rejectArea = new Rectangle();
    private final Rectangle argsArea = new Rectangle();

    private List<String> descLines = List.of();
    private List<String> argLines = List.of();
    private int layoutWidth = -1;

    private Consumer<Boolean> decisionHandler;
    // null = still waiting for the user, otherwise the answer that was already sent.
    // null 表示还没答复，否则是已经发出的答案。
    private Boolean decision;
    private int hoverButton;

    public ToolCallCard(String server, String tool, String args) {
        this(server, tool, args, "ui.confirm.title", "ui.confirm.desc",
                "ui.confirm.args", 4, "ui.confirm.tip");
    }

    // Team pre-approval: plan + files + first diff, then one "allow" that unlocks the phase.
    // 团队预审：计划 + 文件 + 首个 diff，允许后本阶段的写操作不再逐次确认。
    public static ToolCallCard preapprove(String team, String detail) {
        return new ToolCallCard(team, "", detail, "ui.preapprove.title", "ui.preapprove.desc",
                "ui.preapprove.args", 14, "ui.preapprove.tip");
    }

    private ToolCallCard(String server, String tool, String args, String titleKey, String descKey,
                         String argsLabelKey, int maxArgLines, String tipKey) {
        super(1d, false);
        this.server = server == null || server.isBlank() ? "MCP" : server;
        this.tool = tool == null ? "" : tool;
        this.args = args == null || args.isBlank() ? "{}" : args;
        this.titleKey = titleKey;
        this.descKey = descKey;
        this.argsLabelKey = argsLabelKey;
        this.maxArgLines = maxArgLines;
        setOpaque(false);
        setFocusable(true);
        setToolTipText(AgentBundle.message(tipKey));
        installKeys();
        installMouse();
    }

    // Called once when the user answers; `true` means allow, `false` means deny.
    // 用户答复时回调一次：true 允许，false 拒绝。
    public void setDecisionHandler(Consumer<Boolean> handler) {
        decisionHandler = handler;
    }

    public boolean isDecided() {
        return decision != null;
    }

    // Answer programmatically (session closed, tab switched away): never leaves the Core waiting.
    // 以代码方式答复（会话关闭、标签切走）：不让 Core 一直等这个确认。
    public void decide(boolean approved) {
        if (decision != null) {
            return;
        }
        decision = approved;
        contentChanged();
        if (decisionHandler != null) {
            decisionHandler.accept(approved);
        }
    }

    // Grab the keyboard as soon as the card lands, so Enter/Esc work without a click first.
    // 卡片出现的瞬间拿到焦点，Enter/Esc 不必先点一下。
    @Override
    public void addNotify() {
        super.addNotify();
        if (decision == null) {
            SwingUtilities.invokeLater(this::requestFocusInWindow);
        }
    }

    @Override
    public void removeNotify() {
        // Leaving the tree without an answer must not strand the Core's pending confirmation.
        // 离开组件树时若还没答复，必须给出结论，不能让 Core 一直等。
        if (decision == null) {
            decide(false);
        }
        super.removeNotify();
    }

    // ---------------------------------------------------------------- layout

    private int innerWidth(int width) {
        return Math.max(60, width - padX * 2);
    }

    private void ensureLayout(int width) {
        if (width == layoutWidth && !descLines.isEmpty()) {
            return;
        }
        layoutWidth = width;
        int inner = innerWidth(width);
        descLines = wrap(AgentBundle.message(descKey, server, tool),
                UiMetrics.metrics(UiTheme.font()), inner);
        argLines = clipArgs(wrap(args, UiMetrics.metrics(UiTheme.mono()), inner - UiMetrics.scale(12)));
    }

    private List<String> clipArgs(List<String> raw) {
        if (raw.size() <= maxArgLines) {
            return raw;
        }
        List<String> clipped = new ArrayList<>(raw.subList(0, maxArgLines));
        clipped.set(maxArgLines - 1, clipped.get(maxArgLines - 1) + " …");
        return clipped;
    }

    private int argsHeight() {
        return argLines.size() * UiMetrics.metrics(UiTheme.mono()).getHeight() + UiMetrics.scale(10);
    }

    @Override
    protected int contentWidth(int maxWidth) {
        return maxWidth;
    }

    @Override
    protected int computeHeight(int width) {
        ensureLayout(width);
        FontMetrics titleMetrics = UiMetrics.metrics(UiTheme.bold());
        FontMetrics smallMetrics = UiMetrics.metrics(UiTheme.small());
        int descHeight = descLines.size() * UiMetrics.lineHeight(UiTheme.font());
        int labelHeight = smallMetrics.getHeight() + UiMetrics.scale(2);
        int height = padY + titleMetrics.getHeight() + gap + descHeight + gap + labelHeight
                + argsHeight() + gap + buttonHeight + padY;

        // Buttons sit bottom-right; the key hint occupies the left of the same line.
        // 按钮固定在右下角，同一行的左边放键盘提示。
        int allowWidth = smallMetrics.stringWidth(AgentBundle.message("ui.confirm.allow")) + UiMetrics.scale(24);
        int rejectWidth = smallMetrics.stringWidth(AgentBundle.message("ui.confirm.reject")) + UiMetrics.scale(20);
        int rowTop = height - padY - buttonHeight;
        allowArea.setBounds(width - padX - allowWidth, rowTop, allowWidth, buttonHeight);
        rejectArea.setBounds(allowArea.x - UiMetrics.xs() - rejectWidth, rowTop, rejectWidth, buttonHeight);
        argsArea.setBounds(padX, rowTop - gap - argsHeight(), innerWidth(width), argsHeight());
        return height;
    }

    // Greedy character wrap: works for CJK text and for one-line JSON alike.
    // 逐字符贪心折行：中文与一行 JSON 都适用。
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

        // Left status bar: warning while waiting, result colour once answered.
        // 左侧状态条：等待答复时为警告色，答复后改用结果色。
        Shape oldClip = g2.getClip();
        g2.clip(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        g2.setColor(decision == null ? UiTheme.WARN : (decision ? UiTheme.OK : UiTheme.ERROR));
        g2.fillRect(0, 0, bar, height);
        g2.setClip(oldClip);

        int y = padY;
        Font titleFont = UiTheme.bold();
        FontMetrics titleMetrics = UiMetrics.metrics(titleFont);
        g2.setFont(titleFont);
        g2.setColor(UiTheme.text());
        g2.drawString(AgentBundle.message(titleKey), padX, y + titleMetrics.getAscent());
        y += titleMetrics.getHeight() + gap;

        Font bodyFont = UiTheme.font();
        FontMetrics bodyMetrics = UiMetrics.metrics(bodyFont);
        g2.setFont(bodyFont);
        g2.setColor(UiTheme.muted());
        for (String line : descLines) {
            g2.drawString(line, padX, y + bodyMetrics.getAscent());
            y += bodyMetrics.getHeight();
        }
        y += gap;

        Font smallFont = UiTheme.small();
        g2.setFont(smallFont);
        g2.setColor(UiTheme.muted());
        g2.drawString(AgentBundle.message(argsLabelKey), padX, y + UiMetrics.metrics(smallFont).getAscent());

        paintArgs(g2);
        paintFooter(g2, width);
    }

    private void paintArgs(Graphics2D g2) {
        int arc = UiMetrics.radiusChip();
        g2.setColor(UiTheme.SUBTLE);
        g2.fill(new RoundRectangle2D.Float(argsArea.x, argsArea.y, argsArea.width, argsArea.height, arc, arc));

        Font mono = UiTheme.mono();
        FontMetrics metrics = UiMetrics.metrics(mono);
        g2.setFont(mono);
        g2.setColor(UiTheme.text());
        int baseline = argsArea.y + UiMetrics.scale(5) + metrics.getAscent();
        for (String line : argLines) {
            g2.drawString(line, argsArea.x + UiMetrics.scale(6), baseline);
            baseline += metrics.getHeight();
        }
    }

    private void paintFooter(Graphics2D g2, int width) {
        Font font = UiTheme.small();
        FontMetrics metrics = UiMetrics.metrics(font);
        g2.setFont(font);

        if (decision != null) {
            // Answered: swap the buttons for the verdict so the card keeps a record.
            // 已答复：按钮换成结论文字，卡片上留下记录。
            String text = AgentBundle.message(decision ? "ui.confirm.allowed" : "ui.confirm.rejected");
            g2.setColor(decision ? UiTheme.OK : UiTheme.ERROR);
            int baseline = allowArea.y + (buttonHeight - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(text, width - padX - metrics.stringWidth(text), baseline);
            return;
        }

        g2.setColor(UiTheme.muted());
        int hintBaseline = rejectArea.y + (buttonHeight - metrics.getHeight()) / 2 + metrics.getAscent();
        g2.drawString(AgentBundle.message("ui.confirm.keys"), padX, hintBaseline);

        paintButton(g2, rejectArea, AgentBundle.message("ui.confirm.reject"), false, hoverButton == 2);
        paintButton(g2, allowArea, AgentBundle.message("ui.confirm.allow"), true, hoverButton == 1);
    }

    private void paintButton(Graphics2D g2, Rectangle area, String label, boolean primary, boolean hovered) {
        int arc = UiMetrics.radiusButton();
        if (primary) {
            g2.setColor(UiTheme.ACCENT);
            g2.fill(new RoundRectangle2D.Float(area.x, area.y, area.width, area.height, arc, arc));
            if (hovered) {
                // Lighten instead of switching colour: the accent stays the same hue.
                // 悬停时提亮而不是换色，保持同一色相。
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

    private void installKeys() {
        InputMap keys = getInputMap(WHEN_FOCUSED);
        ActionMap actions = getActionMap();
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "confirm-allow");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "confirm-deny");
        actions.put("confirm-allow", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                decide(true);
            }
        });
        actions.put("confirm-deny", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                decide(false);
            }
        });
    }

    private void installMouse() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int next = decision == null
                        ? (allowArea.contains(e.getPoint()) ? 1 : rejectArea.contains(e.getPoint()) ? 2 : 0)
                        : 0;
                setCursor(next == 0 ? Cursor.getDefaultCursor()
                        : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                if (next != hoverButton) {
                    hoverButton = next;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverButton != 0) {
                    hoverButton = 0;
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (decision != null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (allowArea.contains(e.getPoint())) {
                    decide(true);
                } else if (rejectArea.contains(e.getPoint())) {
                    decide(false);
                }
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }
}
