package com.workreport.agent.ui.design;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

// Theme-aware button used everywhere in the panel: flat by default, fades on hover, draws its own
// focus ring and never falls back to the platform's 2010-era default button chrome.
// 面板通用按钮：默认扁平，悬停淡入底色，自绘焦点环，不使用平台默认按钮外观。
public final class UiButton extends JComponent {

    public enum Kind {
        // Text-only, transparent until hovered.
        GHOST,
        // Accent-filled call to action.
        SOLID,
        // Square icon-only button.
        ICON,
        // Bordered, background-less button.
        OUTLINE
    }

    private final Kind kind;
    private final List<ActionListener> listeners = new ArrayList<>();

    private String text;
    private Icon icon;
    private boolean hovered;
    private boolean pressed;
    private boolean selected;
    private boolean circular;
    private float hover;
    private int padX = -1;
    private int padY = -1;

    public UiButton(@NotNull String text, @NotNull Kind kind) {
        this(text, null, kind);
    }

    public UiButton(@Nullable String text, @Nullable Icon icon, @NotNull Kind kind) {
        this.text = text == null ? "" : text;
        this.icon = icon;
        this.kind = kind;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(UiTheme.font());
        installMouse();
        installKeys();
    }

    public void addActionListener(ActionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void setText(@Nullable String value) {
        String next = value == null ? "" : value;
        if (!next.equals(text)) {
            text = next;
            revalidate();
            repaint();
        }
    }

    public String getText() {
        return text;
    }

    public void setIcon(@Nullable Icon value) {
        icon = value;
        revalidate();
        repaint();
    }

    // Toggle state: selected buttons keep the accent tint instead of the hover wash, which is
    // what the tab strip and the "auto run" chip need.
    // 选中态：底色固定为强调色淡底、文字转强调色，供标签条与「自动执行」开关使用。
    public void setSelected(boolean value) {
        if (selected == value) {
            return;
        }
        selected = value;
        repaint();
    }

    public boolean isSelected() {
        return selected;
    }

    // Round button (arc equals the height); used by the 28 px send button.
    // 圆形按钮（圆角取高度），用于 28 px 发送按钮。
    public void setCircular(boolean value) {
        if (circular == value) {
            return;
        }
        circular = value;
        revalidate();
        repaint();
    }

    public boolean isCircular() {
        return circular;
    }

    private float arc(int width, int height) {
        if (circular) {
            return Math.min(width, height);
        }
        return UiMetrics.radiusButton();
    }

    // Horizontal / vertical padding override (scaled); -1 keeps the per-kind default.
    // 内边距覆盖（按缩放）；-1 表示沿用该形态的默认值。
    public void setPadding(int x, int y) {
        padX = x;
        padY = y;
        revalidate();
    }

    public void doClick() {
        if (!isEnabled()) {
            return;
        }
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, getText());
        for (ActionListener listener : List.copyOf(listeners)) {
            listener.actionPerformed(event);
        }
    }

    @Override
    public void setEnabled(boolean value) {
        super.setEnabled(value);
        setCursor(value
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        repaint();
    }

    // ---------------------------------------------------------------- sizing

    private int horizontalPadding() {
        if (padX >= 0) {
            return JBUI.scale(padX);
        }
        return switch (kind) {
            case ICON -> JBUI.scale(4);
            case SOLID -> JBUI.scale(14);
            case OUTLINE -> JBUI.scale(10);
            case GHOST -> JBUI.scale(7);
        };
    }

    private int verticalPadding() {
        if (padY >= 0) {
            return JBUI.scale(padY);
        }
        return switch (kind) {
            case ICON -> JBUI.scale(4);
            case SOLID -> JBUI.scale(5);
            default -> JBUI.scale(3);
        };
    }

    private Dimension contentSize() {
        FontMetrics metrics = UiMetrics.metrics(getFont());
        int textWidth = text.isEmpty() ? 0 : metrics.stringWidth(text);
        int textHeight = metrics.getHeight();
        int iconWidth = icon == null ? 0 : icon.getIconWidth() + (text.isEmpty() ? 0 : JBUI.scale(5));
        int iconHeight = icon == null ? 0 : icon.getIconHeight();
        return new Dimension(textWidth + iconWidth, Math.max(textHeight, iconHeight));
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension content = contentSize();
        return new Dimension(content.width + horizontalPadding() * 2,
                content.height + verticalPadding() * 2);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();
        float alpha = isEnabled() ? 1f : 0.4f;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        float arc = arc(width, height);
        Color background = switch (kind) {
            case SOLID -> UiTheme.ACCENT;
            case OUTLINE -> selected ? UiTheme.ACCENT_SOFT : null;
            default -> selected
                    ? UiTheme.withAlpha(UiTheme.HOVER, 255)
                    : (hover > 0.01f ? UiTheme.withAlpha(UiTheme.HOVER, Math.round(255 * hover)) : null);
        };
        if (background != null) {
            g2.setColor(background);
            g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        }
        if (selected && kind != Kind.SOLID) {
            // Accent outline marks the toggle without filling it with a heavy block of colour.
            // 选中态用强调色描边：既能看出开关打开，又不会糊一块重色。
            g2.setColor(UiTheme.withAlpha(UiTheme.ACCENT, 120));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));
        }
        if (kind == Kind.OUTLINE) {
            g2.setColor(UiTheme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));
        }
        if (pressed && kind != Kind.SOLID) {
            g2.setColor(UiTheme.withAlpha(UiTheme.text(), 18));
            g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        }

        Color foreground = kind == Kind.SOLID ? Color.WHITE
                : (selected ? UiTheme.ACCENT : UiTheme.text());
        if (!isEnabled()) {
            foreground = kind == Kind.SOLID ? Color.WHITE : UiTheme.muted();
        }
        paintContent(g2, foreground, width, height);

        if (isFocusOwner()) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
            g2.setColor(UiTheme.accentSoft(90));
            g2.setStroke(new BasicStroke(JBUI.scale(2f)));
            g2.draw(new RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, arc, arc));
        }
        g2.dispose();
    }

    private void paintContent(Graphics2D g2, Color foreground, int width, int height) {
        Dimension content = contentSize();
        int x = (width - content.width) / 2;
        int iconOffset = 0;
        if (icon != null) {
            int iconY = (height - icon.getIconHeight()) / 2;
            icon.paintIcon(this, g2, x, iconY);
            iconOffset = icon.getIconWidth() + (text.isEmpty() ? 0 : JBUI.scale(5));
        }
        if (!text.isEmpty()) {
            g2.setFont(getFont());
            g2.setColor(foreground);
            FontMetrics metrics = UiMetrics.metrics(getFont());
            int baseline = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(text, x + iconOffset, baseline);
        }
    }

    // ---------------------------------------------------------------- interaction

    private void installMouse() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                fadeHover(1f);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                pressed = false;
                fadeHover(0f);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && isEnabled()) {
                    pressed = true;
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                boolean wasPressed = pressed;
                pressed = false;
                repaint();
                if (wasPressed && isEnabled() && contains(e.getPoint())) {
                    doClick();
                }
            }
        });
    }

    private void fadeHover(float target) {
        if (kind == Kind.SOLID) {
            repaint();
            return;
        }
        float from = hover;
        UiAnim.fade(from, target, 120, value -> {
            hover = value;
            repaint();
        });
    }

    private void installKeys() {
        InputMap keys = getInputMap(WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "ui-button");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ui-button");
        getActionMap().put("ui-button", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doClick();
            }
        });
    }

    public boolean isHoverActive() {
        return hovered;
    }
}
