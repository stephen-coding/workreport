package com.workreport.agent.ui;

import com.intellij.util.ui.JBUI;
import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiAnim;
import com.workreport.agent.ui.design.UiButton;
import com.workreport.agent.ui.design.UiIcons;
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

// Tab strip of the agent tool window: one pill per session plus the tool group on the right.
// Everything that used to be a row of default JButtons is painted here, so the strip follows the
// IDE theme, keeps a constant 30 px height and never reflows into a second line.
// Agent 工具窗口的标签条：每个会话一个 pill，右侧是工具按钮组。
// 过去一排默认 JButton 全部改为自绘：跟随主题、固定 30 px 高、不会折成第二行。
public final class SessionTabsBar extends JPanel {

    // Callbacks carry the tab index; the owner maps it back to its own session list.
    // 回调只带下标，由容器映射回自己的会话列表。
    public interface Listener {
        void onSelect(int index);

        void onClose(int index);

        void onNewSession();

        void onHistory(Component anchor);

        void onMemory(Component anchor);

        void onSettings();
    }

    // One tab as the bar needs it: a title and whether its task is still running.
    // 标签条只关心两件事：标题，以及任务是否还在跑。
    public static final class Tab {
        public final String title;
        public final boolean running;

        public Tab(String title, boolean running) {
            this.title = title;
            this.running = running;
        }
    }

    private static final int HEIGHT = 30;

    private final TabsArea tabs = new TabsArea();
    private final UiButton historyButton;
    private final UiButton memoryButton;
    private final UiButton settingsButton;
    private final UiButton addButton;

    private Listener listener;
    private int pendingMemories;

    public SessionTabsBar() {
        super(new BorderLayout());
        // Transparent: the strip only paints pills, so the parent background (and therefore the
        // live IDE theme) shows through without caching a colour at construction time.
        // 透明：标签条只画 pill，父容器底色（即实时主题色）透出来，不会在构造时把颜色写死。
        setOpaque(false);
        setPreferredSize(new Dimension(10, JBUI.scale(HEIGHT)));
        setMinimumSize(new Dimension(10, JBUI.scale(HEIGHT)));

        // Local aliases first: a lambda may not read a blank final field of the same class.
        // 先用局部别名：lambda 里不能读尚未赋值的 final 字段。
        UiButton history = iconButton("history", "tab.history.tip");
        history.addActionListener(e -> {
            if (listener != null) {
                listener.onHistory(history);
            }
        });
        historyButton = history;

        UiButton memory = iconButton("memory", "tab.memory.tip");
        memory.addActionListener(e -> {
            if (listener != null) {
                listener.onMemory(memory);
            }
        });
        memory.setIcon(new BadgeIcon(UiIcons.icon("memory")));
        memoryButton = memory;

        UiButton settings = iconButton("settings", "composer.settings");
        settings.addActionListener(e -> {
            if (listener != null) {
                listener.onSettings();
            }
        });
        settingsButton = settings;

        UiButton add = iconButton("add", "tab.new.tip");
        add.addActionListener(e -> {
            if (listener != null) {
                listener.onNewSession();
            }
        });
        addButton = add;

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0));
        tools.setOpaque(false);
        tools.add(history);
        tools.add(memory);
        tools.add(settings);
        tools.add(add);

        add(tabs, BorderLayout.CENTER);
        add(tools, BorderLayout.EAST);

        installShortcuts();
    }

    private static UiButton iconButton(String iconName, String tipKey) {
        UiButton button = new UiButton(null, UiIcons.icon(iconName), UiButton.Kind.ICON);
        button.setToolTipText(AgentBundle.message(tipKey));
        button.setPadding(5, 5);
        return button;
    }

    public void setListener(Listener value) {
        listener = value;
    }

    // Replace the tab list; the strip owns no session state of its own.
    // 整表替换标签；标签条本身不持有任何会话状态。
    public void setTabs(List<Tab> values, int selected) {
        tabs.setTabs(values == null ? List.of() : values, selected);
    }

    // Number shown on the memory button; 0 hides the badge.
    // 记忆按钮上的待确认数量，0 表示不显示角标。
    public void setPendingMemories(int count) {
        if (pendingMemories == count) {
            return;
        }
        pendingMemories = count;
        memoryButton.setIcon(new BadgeIcon(UiIcons.icon("memory"), count));
        memoryButton.repaint();
    }

    public int tabCount() {
        return tabs.tabs.size();
    }

    // Ctrl+Tab / Ctrl+Shift+Tab cycle, Ctrl+1..8 jump straight to a tab.
    // Ctrl+Tab 与 Ctrl+Shift+Tab 循环切换，Ctrl+1..8 直接跳到对应标签。
    private void installShortcuts() {
        InputMap keys = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = getActionMap();
        for (int i = 1; i <= 8; i++) {
            final int target = i - 1;
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + i, KeyEvent.CTRL_DOWN_MASK),
                    "agent-tab-" + i);
            actions.put("agent-tab-" + i, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (target < tabs.tabs.size() && listener != null) {
                        listener.onSelect(target);
                    }
                }
            });
        }
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.CTRL_DOWN_MASK), "agent-tab-next");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "agent-tab-prev");
        actions.put("agent-tab-next", step(1));
        actions.put("agent-tab-prev", step(-1));
    }

    private AbstractAction step(int delta) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int count = tabs.tabs.size();
                if (count == 0 || listener == null) {
                    return;
                }
                int next = Math.floorMod(tabs.selected + delta, count);
                listener.onSelect(next);
            }
        };
    }

    // ---------------------------------------------------------------- tabs area

    // Painted strip. No child components: every tab, the running dot and the close cross are
    // drawing operations, which keeps switching cheap and avoids label re-layout.
    // 整条自绘：标签、运行圆点、关闭叉都是画出来的，切换时没有组件重排。
    private final class TabsArea extends JComponent {

        private final List<Tab> tabs = new ArrayList<>();
        private final List<Rectangle> bounds = new ArrayList<>();
        private int selected;
        private int hoverIndex = -1;
        private int closeIndex = -1;
        private int firstVisible;
        private Rectangle overflowBounds;
        private boolean overflowHover;
        private int layoutWidth = -1;
        private float selectionFade = 1f;
        private float pulse;
        private float[] hoverFade = new float[0];
        private Timer pulseTimer;

        TabsArea() {
            setOpaque(false);
            setFont(UiTheme.font());
            ToolTipManager.sharedInstance().registerComponent(this);
            installMouse();
        }

        void setTabs(List<Tab> values, int selectedIndex) {
            tabs.clear();
            tabs.addAll(values);
            selected = Math.max(0, values.isEmpty() ? 0 : Math.min(selectedIndex, values.size() - 1));
            hoverIndex = -1;
            closeIndex = -1;
            hoverFade = new float[values.size()];
            selectionFade = 0f;
            UiAnim.animate(140, progress -> {
                selectionFade = progress;
                repaint();
            });
            syncPulse();
            layoutTabs();
            repaint();
        }

        private boolean anyRunning() {
            for (Tab tab : tabs) {
                if (tab.running) {
                    return true;
                }
            }
            return false;
        }

        // The breathing dots share one timer; it stops as soon as no tab is running.
        // 呼吸点共用一个定时器，没有运行中的会话就停掉。
        private void syncPulse() {
            boolean running = anyRunning();
            if (running && pulseTimer == null) {
                pulseTimer = new Timer(40, e -> {
                    if (!anyRunning()) {
                        syncPulse();
                        return;
                    }
                    pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 190.0));
                    repaint();
                });
                pulseTimer.setCoalesce(true);
            }
            if (running) {
                if (!pulseTimer.isRunning()) {
                    pulseTimer.start();
                }
            } else if (pulseTimer != null) {
                pulseTimer.stop();
            }
        }

        // ------------------------------------------------------------ layout

        private int gap() {
            return JBUI.scale(4);
        }

        private int minTabWidth() {
            return JBUI.scale(58);
        }

        private int maxTabWidth() {
            return JBUI.scale(180);
        }

        private int overflowWidth() {
            return JBUI.scale(22);
        }

        // Widths are recomputed on every resize; assigning them here keeps hit testing and painting
        // in sync without a second layout pass.
        // 每次尺寸变化都重算宽度，命中测试与绘制共用同一批矩形，避免二次布局。
        private void layoutTabs() {
            bounds.clear();
            int width = getWidth();
            layoutWidth = width;
            int height = getHeight();
            if (width <= 0 || tabs.isEmpty()) {
                firstVisible = 0;
                overflowBounds = null;
                return;
            }
            FontMetrics metrics = UiMetrics.metrics(getFont());
            int gap = gap();
            int count = tabs.size();
            int per = Math.min(maxTabWidth(), Math.max(minTabWidth(), width / count - gap));
            int fit = count;
            int avail = width;
            while (fit > 0 && fit * (per + gap) > avail) {
                per = Math.max(minTabWidth(), avail / fit - gap);
                if (fit * (per + gap) <= avail) {
                    break;
                }
                fit--;
            }
            boolean overflow = fit < count;
            if (overflow) {
                overflowBounds = new Rectangle(width - overflowWidth(), 0, overflowWidth(), height);
                avail = width - overflowWidth();
                fit = 0;
                while ((fit + 1) * (per + gap) <= avail) {
                    fit++;
                }
                fit = Math.min(fit, count);
            } else {
                overflowBounds = null;
            }
            firstVisible = Math.max(0, Math.min(selected - fit + 1, count - fit));
            int x = JBUI.scale(2);
            int y = JBUI.scale(2);
            int tabHeight = Math.max(JBUI.scale(16), height - JBUI.scale(4));
            for (int i = 0; i < fit; i++) {
                int index = firstVisible + i;
                bounds.add(new Rectangle(x, y, per, tabHeight));
                x += per + gap;
            }
            if (overflowBounds != null) {
                overflowBounds.y = y;
                overflowBounds.height = tabHeight;
            }
        }

        @Override
        public void doLayout() {
            layoutTabs();
        }

        // The parent only sets bounds, so a width change is picked up on the next paint.
        // 父容器只设置 bounds，宽度变化在下一次绘制时重新分配标签宽度。
        private void ensureLayout() {
            if (getWidth() != layoutWidth || (bounds.isEmpty() && !tabs.isEmpty())) {
                layoutTabs();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(120), JBUI.scale(HEIGHT));
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(JBUI.scale(60), JBUI.scale(HEIGHT));
        }

        // ------------------------------------------------------------ painting

        @Override
        protected void paintComponent(Graphics g) {
            ensureLayout();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int i = 0; i < bounds.size(); i++) {
                paintTab(g2, firstVisible + i, bounds.get(i), i);
            }
            if (overflowBounds != null) {
                paintOverflow(g2);
            }
            g2.dispose();
        }

        private void paintTab(Graphics2D g2, int index, Rectangle bounds, int slot) {
            Tab tab = tabs.get(index);
            boolean isSelected = index == selected;
            boolean isHover = index == hoverIndex;
            float fade = slot < hoverFade.length ? hoverFade[slot] : 0f;
            int arc = UiMetrics.radiusChip();

            float alpha = isSelected ? selectionFade : fade;
            if (alpha > 0.01f) {
                Color fill = isSelected ? UiTheme.ACCENT_SOFT : UiTheme.withAlpha(UiTheme.HOVER,
                        Math.round(255 * Math.min(1f, alpha)));
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Float(bounds.x, bounds.y, bounds.width, bounds.height,
                        arc, arc));
            }
            if (isSelected) {
                // 2 px accent indicator pinned to the bottom edge of the pill.
                // 选中标签底部 2 px 强调色指示条。
                int indicatorWidth = Math.round(bounds.width * selectionFade);
                g2.setColor(UiTheme.ACCENT);
                g2.fill(new RoundRectangle2D.Float(
                        bounds.x + (bounds.width - indicatorWidth) / 2f,
                        bounds.y + bounds.height - JBUI.scale(2f),
                        indicatorWidth, JBUI.scale(2f), JBUI.scale(2f), JBUI.scale(2f)));
            }

            Font font = isSelected ? UiTheme.bold() : UiTheme.font();
            FontMetrics metrics = UiMetrics.metrics(font);
            g2.setFont(font);

            int left = bounds.x + JBUI.scale(9);
            if (tab.running) {
                int dot = JBUI.scale(6);
                int dotY = bounds.y + (bounds.height - dot) / 2;
                int dotAlpha = Math.round(255 * (0.35f + 0.65f * pulse));
                g2.setColor(UiTheme.withAlpha(UiTheme.ACCENT, dotAlpha));
                g2.fillOval(left, dotY, dot, dot);
                left += dot + JBUI.scale(5);
            }

            int closeSize = JBUI.scale(14);
            int closeRight = bounds.x + bounds.width - JBUI.scale(6) - closeSize;
            int textWidth = Math.max(JBUI.scale(10),
                    closeRight - JBUI.scale(4) - left);
            String title = tab.title == null || tab.title.isBlank() ? "—" : tab.title;
            String shown = ellipsize(title, metrics, textWidth);
            g2.setColor(isSelected ? UiTheme.text() : UiTheme.muted());
            int baseline = bounds.y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(shown, left, baseline);

            float closeAlpha = (isHover || index == closeIndex) ? 1f : (isSelected ? 0.55f : 0f);
            if (closeAlpha > 0.01f) {
                Shape old = g2.getClip();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, closeAlpha));
                Rectangle close = closeBounds(bounds);
                if (index == closeIndex) {
                    g2.setColor(UiTheme.withAlpha(UiTheme.HOVER, 255));
                    g2.fill(new RoundRectangle2D.Float(close.x - JBUI.scale(1),
                            close.y - JBUI.scale(1), close.width + JBUI.scale(2),
                            close.height + JBUI.scale(2), arc, arc));
                }
                UiIcons.paintClose(g2, close, index == closeIndex ? UiTheme.ERROR : UiTheme.muted());
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
                g2.setClip(old);
            }
        }

        private Rectangle closeBounds(Rectangle tabBounds) {
            int size = JBUI.scale(14);
            return new Rectangle(tabBounds.x + tabBounds.width - JBUI.scale(6) - size,
                    tabBounds.y + (tabBounds.height - size) / 2, size, size);
        }

        private void paintOverflow(Graphics2D g2) {
            boolean hot = overflowHover;
            g2.setColor(hot ? UiTheme.HOVER : UiTheme.SUBTLE);
            g2.fill(new RoundRectangle2D.Float(overflowBounds.x, overflowBounds.y,
                    overflowBounds.width, overflowBounds.height,
                    UiMetrics.radiusChip(), UiMetrics.radiusChip()));
            FontMetrics metrics = UiMetrics.metrics(UiTheme.bold());
            g2.setFont(UiTheme.bold());
            g2.setColor(UiTheme.muted());
            String glyph = "»";
            g2.drawString(glyph, overflowBounds.x + (overflowBounds.width - metrics.stringWidth(glyph)) / 2,
                    overflowBounds.y + (overflowBounds.height - metrics.getHeight()) / 2
                            + metrics.getAscent());
        }

        private String ellipsize(String value, FontMetrics metrics, int available) {
            if (metrics.stringWidth(value) <= available) {
                return value;
            }
            String ellipsis = "…";
            for (int end = value.length() - 1; end > 0; end--) {
                String candidate = value.substring(0, end);
                if (metrics.stringWidth(candidate) + metrics.stringWidth(ellipsis) <= available) {
                    return candidate + ellipsis;
                }
            }
            return ellipsis;
        }

        // ------------------------------------------------------------ interaction

        private void installMouse() {
            MouseAdapter adapter = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    updateHover(e.getPoint());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverIndex = -1;
                    closeIndex = -1;
                    overflowHover = false;
                    for (int i = 0; i < hoverFade.length; i++) {
                        hoverFade[i] = 0f;
                    }
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (tabs.isEmpty()) {
                        return;
                    }
                    if (overflowBounds != null && overflowBounds.contains(e.getPoint())) {
                        showOverflowMenu(e.getPoint());
                        return;
                    }
                    int slot = slotAt(e.getPoint());
                    if (slot < 0) {
                        return;
                    }
                    int index = firstVisible + slot;
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        if (listener != null) {
                            listener.onClose(index);
                        }
                        return;
                    }
                    if (!SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    if (closeBounds(bounds.get(slot)).contains(e.getPoint())) {
                        if (listener != null) {
                            listener.onClose(index);
                        }
                        return;
                    }
                    if (listener != null) {
                        listener.onSelect(index);
                    }
                }
            };
            addMouseListener(adapter);
            addMouseMotionListener(adapter);
        }

        private void updateHover(Point point) {
            boolean nextOverflow = overflowBounds != null && overflowBounds.contains(point);
            if (nextOverflow != overflowHover) {
                overflowHover = nextOverflow;
                repaint();
            }
            if (nextOverflow) {
                hoverIndex = -1;
                closeIndex = -1;
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                return;
            }
            int slot = slotAt(point);
            int nextHover = slot < 0 ? -1 : firstVisible + slot;
            // Tooltip explains the running dot and the middle-click shortcut.
            // 提示文案说明运行圆点含义与中键关闭。
            if (nextHover >= 0 && nextHover < tabs.size()) {
                setToolTipText(tabs.get(nextHover).running
                        ? AgentBundle.message("tab.running.tip")
                        : AgentBundle.message("tab.switch.tip"));
            } else {
                setToolTipText(null);
            }
            int nextClose = -1;
            if (slot >= 0 && closeBounds(bounds.get(slot)).contains(point)) {
                nextClose = firstVisible + slot;
            }
            boolean changed = nextHover != hoverIndex || nextClose != closeIndex;
            int previousHover = hoverIndex;
            hoverIndex = nextHover;
            closeIndex = nextClose;
            setCursor(nextHover >= 0
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
            // Hover washes fade per slot so moving across the strip does not flicker.
            // 悬停底色按槽位淡入，鼠标扫过时不会闪。
            if (slot >= 0 && slot < hoverFade.length && hoverFade[slot] < 1f) {
                final int target = slot;
                UiAnim.fade(hoverFade[target], 1f, 120, value -> {
                    if (target < hoverFade.length) {
                        hoverFade[target] = value;
                        repaint();
                    }
                });
            }
            if (previousHover >= 0 && previousHover != nextHover) {
                int previousSlot = previousHover - firstVisible;
                if (previousSlot >= 0 && previousSlot < hoverFade.length) {
                    hoverFade[previousSlot] = 0f;
                }
            }
            if (changed) {
                repaint();
            }
        }

        private int slotAt(Point point) {
            for (int i = 0; i < bounds.size(); i++) {
                if (bounds.get(i).contains(point)) {
                    return i;
                }
            }
            return -1;
        }

        // Tabs that do not fit are reachable through the » menu instead of a second row.
        // 放不下的标签收进 » 菜单，而不是折成第二行。
        private void showOverflowMenu(Point point) {
            if (listener == null) {
                return;
            }
            JPopupMenu menu = new JPopupMenu();
            for (int i = 0; i < tabs.size(); i++) {
                final int index = i;
                JMenuItem item = new JMenuItem(tabs.get(i).title);
                item.setEnabled(i != selected);
                item.addActionListener(e -> listener.onSelect(index));
                menu.add(item);
            }
            menu.show(this, point.x, point.y);
        }

        @Override
        public void removeNotify() {
            if (pulseTimer != null) {
                pulseTimer.stop();
            }
            super.removeNotify();
        }
    }

    // Icon with an optional count badge, used by the memory button.
    // 带数量角标的图标，供记忆按钮使用。
    private static final class BadgeIcon implements Icon {

        private final Icon base;
        private final int count;

        BadgeIcon(Icon base) {
            this(base, 0);
        }

        BadgeIcon(Icon base, int count) {
            this.base = base;
            this.count = count;
        }

        @Override
        public int getIconWidth() {
            return base.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return base.getIconHeight();
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            base.paintIcon(c, g, x, y);
            if (count <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            String text = count > 9 ? "9+" : String.valueOf(count);
            Font font = UiTheme.small().deriveFont(Font.BOLD, UiTheme.small().getSize2D() - 1f);
            g2.setFont(font);
            FontMetrics metrics = g2.getFontMetrics(font);
            int diameter = Math.max(JBUI.scale(10), metrics.stringWidth(text) + JBUI.scale(4));
            int bx = x + getIconWidth() - diameter + JBUI.scale(2);
            int by = y - JBUI.scale(2);
            g2.setColor(UiTheme.ACCENT);
            g2.fillOval(bx, by, diameter, diameter);
            g2.setColor(Color.WHITE);
            g2.drawString(text, bx + (diameter - metrics.stringWidth(text)) / 2f,
                    by + (diameter - metrics.getHeight()) / 2f + metrics.getAscent());
            g2.dispose();
        }
    }

    // Convenience for callers that only need to know which session a click landed on.
    // 便于调用方把菜单动作映射回会话的辅助入口。
    public void updateTabs(List<Tab> values, int selected, int pendingMemories) {
        setPendingMemories(pendingMemories);
        setTabs(values, selected);
    }
}
