package com.workreport.agent.ui.picker;

import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiIcons;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Shared list popup for history and memory: one search box, one row renderer, one keyboard model.
// Rows are painted rather than built from labels, so a 44 px row keeps its hover actions, chip and
// warning line without a single child component.
// 历史与记忆共用的列表弹窗：一个搜索框、一套行渲染、一套键盘操作。
// 行是自绘的：44 px 行高内放得下 hover 动作、类别 chip 与黄色提示行，不创建任何子组件。
public final class PickerPopup extends JPanel {

    // One row. `payload` carries whatever the caller needs when the row is picked.
    // 单行数据；payload 是调用方在选中该行后要用的对象。
    public static final class Row {
        public final String title;
        public final String subtitle;
        public final String chip;
        public final String warning;
        public final String tooltip;
        public final Object payload;
        public final List<RowAction> actions;

        public Row(String title, String subtitle, String chip, String warning, String tooltip,
                   Object payload, List<RowAction> actions) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.chip = chip == null ? "" : chip;
            this.warning = warning == null ? "" : warning;
            this.tooltip = tooltip;
            this.payload = payload;
            this.actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    // Hover action on a row (open / delete / confirm / ignore).
    // 行上的 hover 动作（打开 / 删除 / 确认 / 忽略）。
    public static final class RowAction {
        public final String label;
        public final boolean danger;
        public final Consumer<Row> handler;

        public RowAction(String label, boolean danger, Consumer<Row> handler) {
            this.label = label;
            this.danger = danger;
            this.handler = handler;
        }
    }

    private final int width;
    private final JBTextField search = new JBTextField();
    private final RowsPanel rows = new RowsPanel();
    private final JLabel header = new JLabel();
    private final JLabel emptyLabel = new JLabel();
    private final JLabel footer = new JLabel();
    private final String emptyText;

    private Consumer<Row> pickHandler;
    private Consumer<String> searchHandler;
    private JBPopup popup;
    private JBScrollPane scroll;
    private Timer debounce;

    public PickerPopup(int width, String searchPlaceholder, String emptyText, String footerHint) {
        super(new BorderLayout(0, UiMetrics.xs()));
        this.width = JBUI.scale(width);
        this.emptyText = emptyText == null ? "" : emptyText;
        setOpaque(false);
        setBorder(new EmptyBorder(UiMetrics.m(), UiMetrics.m(), UiMetrics.m(), UiMetrics.m()));

        add(buildHead(searchPlaceholder), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(footerHint), BorderLayout.SOUTH);
        installKeys();
    }

    // ---------------------------------------------------------------- building

    private JComponent buildHead(String searchPlaceholder) {
        JPanel head = new JPanel(new BorderLayout(0, UiMetrics.xs()));
        head.setOpaque(false);

        JPanel searchRow = new JPanel(new BorderLayout(UiMetrics.xs(), 0));
        searchRow.setOpaque(false);
        searchRow.add(new SearchGlyph(), BorderLayout.WEST);
        search.getEmptyText().setText(searchPlaceholder == null ? "" : searchPlaceholder);
        search.setBorder(new EmptyBorder(UiMetrics.xs(), UiMetrics.xs(), UiMetrics.xs(), UiMetrics.xs()));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                schedule();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                schedule();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                schedule();
            }
        });
        searchRow.add(search, BorderLayout.CENTER);
        head.add(searchRow, BorderLayout.NORTH);

        header.setForeground(UiTheme.muted());
        header.setFont(UiTheme.small());
        head.add(header, BorderLayout.SOUTH);
        return head;
    }

    private JComponent buildCenter() {
        emptyLabel.setForeground(UiTheme.muted());
        emptyLabel.setFont(UiTheme.small());
        emptyLabel.setBorder(new EmptyBorder(UiMetrics.m(), UiMetrics.s(), UiMetrics.m(), UiMetrics.s()));
        emptyLabel.setVisible(false);

        JBScrollPane scroll = new JBScrollPane(rows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(44));

        // The empty-state text shares the centre slot with the list; only one is visible.
        // 空状态文案与列表共用中间位置，任何时候只显示其中一个。
        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(emptyLabel, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        this.scroll = scroll;
        return center;
    }

    private JComponent buildFooter(String footerHint) {
        footer.setForeground(UiTheme.muted());
        footer.setFont(UiTheme.small());
        footer.setText(footerHint == null ? "" : footerHint);
        footer.setBorder(new EmptyBorder(UiMetrics.xs(), UiMetrics.xs(), 0, UiMetrics.xs()));
        return footer;
    }

    // ---------------------------------------------------------------- public API

    public void setPickHandler(Consumer<Row> handler) {
        pickHandler = handler;
    }

    // Debounced query callback: one call 300 ms after typing settles.
    // 搜索回调带 300 ms 防抖：连续敲键只发一次。
    public void setSearchHandler(Consumer<String> handler) {
        searchHandler = handler;
    }

    public void setHeader(String text) {
        header.setText(text == null ? "" : text);
    }

    public void setRows(List<Row> values) {
        rows.setRows(values == null ? List.of() : values);
        boolean empty = values == null || values.isEmpty();
        emptyLabel.setText(empty ? emptyText : "");
        emptyLabel.setVisible(empty);
        rows.setVisible(!empty);
        if (scroll != null) {
            scroll.setVisible(!empty);
        }
        revalidate();
        repaint();
    }

    // Wrap this component into a popup and remember it, so Esc can cancel.
    // 包成弹窗并记住实例，Esc 才能取消。
    public JBPopup createPopup() {
        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(this, search)
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(false)
                .createPopup();
        return popup;
    }

    public void focusSearch() {
        search.requestFocusInWindow();
    }

    public String query() {
        return search.getText().trim();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        int height = Math.min(JBUI.scale(460), Math.max(JBUI.scale(140), size.height));
        return new Dimension(width, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    // ---------------------------------------------------------------- behaviour

    private void schedule() {
        String query = query();
        if (searchHandler == null) {
            return;
        }
        if (debounce != null) {
            debounce.stop();
        }
        debounce = new Timer(300, e -> searchHandler.accept(query));
        debounce.setRepeats(false);
        debounce.start();
    }

    // ↑↓ move the selection, Enter picks it, Esc closes.
    // ↑↓ 移动选中行，Enter 选中，Esc 关闭。
    private void installKeys() {
        InputMap keys = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actions = getActionMap();
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "picker-down");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "picker-up");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "picker-pick");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "picker-close");
        actions.put("picker-down", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                rows.move(1);
            }
        });
        actions.put("picker-up", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                rows.move(-1);
            }
        });
        actions.put("picker-pick", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Row row = rows.selectedRow();
                if (row != null && pickHandler != null) {
                    pickHandler.accept(row);
                }
            }
        });
        actions.put("picker-close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (popup != null) {
                    popup.cancel();
                }
            }
        });
    }

    // ---------------------------------------------------------------- rows

    private final class SearchGlyph extends JComponent {
        SearchGlyph() {
            setPreferredSize(new Dimension(JBUI.scale(16), JBUI.scale(16)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Icon icon = UiIcons.search();
            icon.paintIcon(this, g2, 0, Math.max(0, (getHeight() - icon.getIconHeight()) / 2));
            g2.dispose();
        }
    }

    // The painted list. Row rectangles are recomputed whenever width or data changes, and the
    // same rectangles drive hit testing, so nothing can drift out of sync.
    // 自绘列表：宽度或数据变化时重算行矩形，命中测试复用同一批矩形，不会错位。
    private final class RowsPanel extends JComponent {

        private final List<Row> data = new ArrayList<>();
        private final List<Rectangle> bounds = new ArrayList<>();
        private final List<List<Rectangle>> actionBounds = new ArrayList<>();
        private int hoverIndex = -1;
        private int hoverAction = -1;
        private int selected = -1;
        private int layoutWidth = -1;

        RowsPanel() {
            setOpaque(false);
            setFont(UiTheme.font());
            ToolTipManager.sharedInstance().registerComponent(this);
            installMouse();
        }

        void setRows(List<Row> values) {
            data.clear();
            data.addAll(values);
            selected = data.isEmpty() ? -1 : 0;
            hoverIndex = -1;
            hoverAction = -1;
            layoutRows();
            revalidate();
            repaint();
        }

        void move(int delta) {
            if (data.isEmpty()) {
                return;
            }
            selected = Math.floorMod(selected < 0 ? 0 : selected + delta, data.size());
            scrollRectToVisible(bounds.get(selected));
            repaint();
        }

        Row selectedRow() {
            return selected >= 0 && selected < data.size() ? data.get(selected) : null;
        }

        // ------------------------------------------------------------ layout

        private int rowHeight(Row row) {
            return JBUI.scale(row.warning.isEmpty() ? 44 : 60);
        }

        private void layoutRows() {
            bounds.clear();
            actionBounds.clear();
            layoutWidth = getWidth();
            int y = 0;
            for (Row row : data) {
                int height = rowHeight(row);
                bounds.add(new Rectangle(0, y, Math.max(1, getWidth()), height));
                List<Rectangle> actions = new ArrayList<>();
                int right = Math.max(1, getWidth()) - UiMetrics.m();
                FontMetrics metrics = UiMetrics.metrics(UiTheme.small());
                for (RowAction action : row.actions) {
                    int width = metrics.stringWidth(action.label) + UiMetrics.l();
                    actions.add(new Rectangle(right - width, y + height - JBUI.scale(26),
                            width, JBUI.scale(20)));
                    right -= width + UiMetrics.xs();
                }
                actionBounds.add(actions);
                y += height;
            }
        }

        private void ensureLayout() {
            if (getWidth() != layoutWidth) {
                layoutRows();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            int height = 0;
            for (Row row : data) {
                height += rowHeight(row);
            }
            return new Dimension(width - UiMetrics.m() * 2, Math.max(JBUI.scale(20), height));
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
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
                paintRow(g2, i);
            }
            g2.dispose();
        }

        private void paintRow(Graphics2D g2, int index) {
            Row row = data.get(index);
            Rectangle rect = bounds.get(index);
            boolean hot = index == hoverIndex;
            if (hot || index == selected) {
                g2.setColor(hot ? UiTheme.HOVER : UiTheme.SUBTLE);
                g2.fill(new RoundRectangle2D.Float(rect.x, rect.y, rect.width, rect.height,
                        UiMetrics.radiusChip(), UiMetrics.radiusChip()));
            }

            int left = rect.x + UiMetrics.m();
            int top = rect.y + UiMetrics.s();

            Font titleFont = UiTheme.bold();
            FontMetrics titleMetrics = UiMetrics.metrics(titleFont);
            g2.setFont(titleFont);
            int textWidth = rect.width - (left - rect.x) - UiMetrics.m();
            g2.setColor(UiTheme.text());
            g2.drawString(ellipsize(row.title, titleMetrics, textWidth), left, top + titleMetrics.getAscent());

            Font smallFont = UiTheme.small();
            FontMetrics smallMetrics = UiMetrics.metrics(smallFont);
            g2.setFont(smallFont);
            if (!row.subtitle.isEmpty()) {
                g2.setColor(UiTheme.muted());
                int baseline = top + titleMetrics.getHeight() + smallMetrics.getAscent() - JBUI.scale(2);
                g2.drawString(ellipsize(row.subtitle, smallMetrics, textWidth), left, baseline);
            }
            if (!row.warning.isEmpty()) {
                g2.setColor(UiTheme.WARN);
                int baseline = top + titleMetrics.getHeight() + smallMetrics.getHeight()
                        + smallMetrics.getAscent() - JBUI.scale(4);
                g2.drawString(ellipsize(row.warning, smallMetrics, textWidth), left, baseline);
            }

            if (!row.chip.isEmpty()) {
                int padX = UiMetrics.s();
                int chipWidth = smallMetrics.stringWidth(row.chip) + padX * 2;
                int chipHeight = smallMetrics.getHeight() + JBUI.scale(2);
                int chipX = rect.x + rect.width - UiMetrics.m() - chipWidth;
                int chipY = top;
                g2.setColor(UiTheme.ACCENT_SOFT);
                g2.fill(new RoundRectangle2D.Float(chipX, chipY, chipWidth, chipHeight,
                        UiMetrics.radiusChip(), UiMetrics.radiusChip()));
                g2.setColor(UiTheme.ACCENT);
                g2.drawString(row.chip, chipX + padX, chipY + (chipHeight - smallMetrics.getHeight()) / 2
                        + smallMetrics.getAscent());
            }

            if (hot && !row.actions.isEmpty()) {
                paintActions(g2, index);
            }
        }

        private void paintActions(Graphics2D g2, int index) {
            Row row = data.get(index);
            List<Rectangle> rects = actionBounds.get(index);
            Font font = UiTheme.small();
            FontMetrics metrics = UiMetrics.metrics(font);
            g2.setFont(font);
            for (int i = 0; i < rects.size() && i < row.actions.size(); i++) {
                RowAction action = row.actions.get(i);
                Rectangle bounds = rects.get(i);
                boolean hot = i == hoverAction;
                g2.setColor(hot
                        ? (action.danger ? UiTheme.ERROR_SOFT : UiTheme.SUBTLE)
                        : UiTheme.RAISED);
                g2.fill(new RoundRectangle2D.Float(bounds.x, bounds.y, bounds.width, bounds.height,
                        UiMetrics.radiusChip(), UiMetrics.radiusChip()));
                g2.setColor(action.danger ? UiTheme.ERROR : UiTheme.text());
                g2.drawString(action.label, bounds.x + UiMetrics.s(),
                        bounds.y + (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent());
            }
        }

        private String ellipsize(String value, FontMetrics metrics, int available) {
            if (value == null) {
                return "";
            }
            if (available <= 0 || metrics.stringWidth(value) <= available) {
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
                    int index = indexAt(e.getPoint());
                    int action = actionAt(index, e.getPoint());
                    boolean changed = index != hoverIndex || action != hoverAction;
                    hoverIndex = index;
                    hoverAction = action;
                    setToolTipText(index >= 0 ? data.get(index).tooltip : null);
                    setCursor(index >= 0
                            ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            : Cursor.getDefaultCursor());
                    if (changed) {
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverIndex = -1;
                    hoverAction = -1;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int index = indexAt(e.getPoint());
                    if (index < 0 || !SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    Row row = data.get(index);
                    int action = actionAt(index, e.getPoint());
                    if (action >= 0 && action < row.actions.size()) {
                        row.actions.get(action).handler.accept(row);
                        return;
                    }
                    if (pickHandler != null) {
                        pickHandler.accept(row);
                    }
                }
            };
            addMouseListener(adapter);
            addMouseMotionListener(adapter);
        }

        private int indexAt(Point point) {
            for (int i = 0; i < bounds.size(); i++) {
                if (bounds.get(i).contains(point)) {
                    return i;
                }
            }
            return -1;
        }

        private int actionAt(int index, Point point) {
            if (index < 0 || index >= actionBounds.size()) {
                return -1;
            }
            List<Rectangle> rects = actionBounds.get(index);
            for (int i = 0; i < rects.size(); i++) {
                if (rects.get(i).contains(point)) {
                    return i;
                }
            }
            return -1;
        }
    }

    // Keyboard hint shown under the list.
    // 列表下方的键盘提示。
    public static String defaultFooter() {
        return AgentBundle.message("ui.picker.footer");
    }
}
