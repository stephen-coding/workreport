package com.workreport.agent.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.workreport.agent.AgentBundle;
import com.workreport.agent.ui.design.UiAnim;
import com.workreport.agent.ui.design.UiButton;
import com.workreport.agent.ui.design.UiIcons;
import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Bottom composer: multi-line input, attachment chips and the action row.
// The whole block can be made taller by dragging the splitter divider above it; the input
// itself also grows with the text (up to a cap) and collapses again after sending.
// 底部输入区：多行输入框 + 附件条 + 操作栏。
// 拖动上方分隔条可整体加高；输入内容变多时输入框自动长高（有上限），发送后自动收起。
public final class ChatComposer extends JPanel {

    // Callbacks to the owning panel; keeps this component free of RPC knowledge.
    // 回调给上层面板，输入区本身不关心 RPC 细节。
    public interface Listener {
        void onSend(String text, List<Path> attachments);

        void onStop();

        void onOpenSettings();

        void onModelChanged(String modelName);

        void onAutoAcceptChanged(boolean autoAccept);
    }

    // Height request sent to the owner; force=true means "collapse me now" (after sending).
    // 向上层提出高度诉求；force=true 表示发送后强制收回基础高度。
    public interface HeightSink {
        void onDesiredHeight(int desired, boolean force);
    }

    private static final int MIN_ROWS = 4;
    private static final int MAX_ROWS = 14;

    private final Project project;
    private final Listener listener;
    private final HeightSink heightSink;

    private final InputArea input = new InputArea();
    private final JBScrollPane inputScroll;
    private final JPanel attachmentStrip = new JPanel(new WrapLayout());
    private final List<Path> attachments = new ArrayList<>();

    private final UiButton attachButton;
    private final UiButton settingsButton;
    private final UiButton stopButton;
    private final UiButton sendButton;
    private final UiButton modelButton;
    private final UiButton autoButton;
    private final ComposerBox box;
    private final JPanel toolbar;

    private List<String> models = List.of();
    private String activeModel = "";
    private boolean busy;
    private boolean collapseAfterSend;
    private int lastNotifiedHeight;

    public ChatComposer(Project project, Listener listener, HeightSink heightSink) {
        super(new BorderLayout(0, UiMetrics.s()));
        this.project = project;
        this.listener = listener;
        this.heightSink = heightSink;

        setOpaque(false);
        setBorder(new EmptyBorder(UiMetrics.s(), 0, JBUI.scale(2), 0));

        attachmentStrip.setOpaque(false);
        attachmentStrip.setVisible(false);

        inputScroll = new JBScrollPane(input);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));

        attachButton = new UiButton(null, UiIcons.icon("attach"), UiButton.Kind.ICON);
        attachButton.setToolTipText(AgentBundle.message("composer.attach.tip"));
        attachButton.setPadding(5, 5);
        attachButton.addActionListener(e -> showAttachMenu());

        settingsButton = new UiButton(null, UiIcons.settings(), UiButton.Kind.ICON);
        settingsButton.setToolTipText(AgentBundle.message("composer.settings"));
        settingsButton.setPadding(5, 5);
        settingsButton.addActionListener(e -> listener.onOpenSettings());

        stopButton = new UiButton(AgentBundle.message("composer.stop"), UiButton.Kind.GHOST);
        stopButton.setToolTipText(AgentBundle.message("ui.composer.stop.tip"));
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> listener.onStop());

        sendButton = new UiButton(null, new ArrowIcon(JBUI.scale(14)), UiButton.Kind.SOLID);
        sendButton.setCircular(true);
        sendButton.setPadding(7, 7);
        sendButton.setToolTipText(AgentBundle.message("ui.composer.send.tip"));
        sendButton.addActionListener(e -> triggerSend());

        modelButton = new UiButton("", new ChevronIcon(JBUI.scale(9)), UiButton.Kind.GHOST);
        modelButton.setPadding(5, 3);
        modelButton.setToolTipText(AgentBundle.message("composer.model.tip"));
        modelButton.addActionListener(e -> showModelMenu());

        autoButton = new UiButton(AgentBundle.message("composer.auto"), UiButton.Kind.OUTLINE);
        autoButton.setPadding(7, 3);
        autoButton.setToolTipText(AgentBundle.message("composer.auto.tip"));
        autoButton.addActionListener(e -> {
            boolean next = !autoButton.isSelected();
            autoButton.setSelected(next);
            listener.onAutoAcceptChanged(next);
        });

        toolbar = buildToolbar();
        box = new ComposerBox(inputScroll);

        add(attachmentStrip, BorderLayout.NORTH);
        add(box, BorderLayout.CENTER);
        add(toolbar, BorderLayout.SOUTH);

        installInputBindings();
        installDropSupport();
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshHeight();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshHeight();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshHeight();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refreshHeight();
            }
        });
        refreshHeight();
    }

    // ------------------------------------------------------------------ public API

    // Model list changed: keep the compact button showing the active name.
    // 模型列表变化：紧凑按钮显示当前生效的模型名。
    public void setModels(List<String> names, String active) {
        models = names == null ? List.of() : List.copyOf(names);
        activeModel = active == null ? "" : active;
        modelButton.setText(activeModel.isEmpty()
                ? AgentBundle.message("ui.composer.model.pick")
                : activeModel);
        modelButton.setEnabled(!models.isEmpty());
        modelButton.revalidate();
        modelButton.repaint();
    }

    public void setAutoAccept(boolean value) {
        autoButton.setSelected(value);
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        sendButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        box.repaint();
    }

    public void setText(String text) {
        input.setText(text);
        input.requestFocusInWindow();
    }

    public void focusInput() {
        input.requestFocusInWindow();
    }

    // Send whatever is in the composer (used by the daily-report action).
    // 发送当前输入内容（供日报 action 复用）。
    public void triggerSend() {
        if (busy) {
            return;
        }
        String text = input.getText().trim();
        if (text.isEmpty() && attachments.isEmpty()) {
            return;
        }
        listener.onSend(text, List.copyOf(attachments));
    }

    // Clear text and attachments, then collapse back to the base height.
    // 清空输入与附件，并收回到基础高度。
    public void reset() {
        collapseAfterSend = true;
        input.setText("");
        attachments.clear();
        refreshAttachments();
        refreshHeight();
        input.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ attachments

    private void showAttachMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem pick = new JMenuItem(AgentBundle.message("composer.pickFile"));
        pick.addActionListener(e -> pickFiles());
        JMenuItem current = new JMenuItem(AgentBundle.message("composer.currentFile"));
        current.addActionListener(e -> addCurrentEditorFile());
        menu.add(pick);
        menu.add(current);
        menu.show(attachButton, 0, attachButton.getHeight());
    }

    private void pickFiles() {
        if (attachments.size() >= ChatAttachment.MAX_FILES) {
            return;
        }
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory
                .createMultipleFilesNoJarsDescriptor()
                .withTitle(AgentBundle.message("composer.add.title"))
                .withDescription(AgentBundle.message("composer.add.description"));
        VirtualFile[] files = FileChooserFactory.getInstance()
                .createFileChooser(descriptor, project, null)
                .choose(project);
        for (VirtualFile file : files) {
            addAttachment(Path.of(file.getPath()));
        }
    }

    private void addCurrentEditorFile() {
        VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
        if (files.length == 0) {
            return;
        }
        addAttachment(Path.of(files[0].getPath()));
    }

    // Files can also be dropped straight onto the input area (QQ/WeChat style).
    // 文件也可以直接拖进输入区（QQ/微信习惯）。
    private void installDropSupport() {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    @SuppressWarnings("unchecked")
                    List<File> dropped = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : dropped) {
                        addAttachment(file.toPath());
                    }
                    return !dropped.isEmpty();
                } catch (Exception e) {
                    return false;
                }
            }
        };
        input.setTransferHandler(handler);
        setTransferHandler(handler);
    }

    private void addAttachment(Path path) {
        if (attachments.size() >= ChatAttachment.MAX_FILES) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (attachments.contains(normalized)) {
            return;
        }
        attachments.add(normalized);
        refreshAttachments();
    }

    private void refreshAttachments() {
        attachmentStrip.removeAll();
        for (Path path : attachments) {
            attachmentStrip.add(new ChipView(path, () -> {
                attachments.remove(path);
                refreshAttachments();
            }));
        }
        attachmentStrip.setVisible(!attachments.isEmpty());
        attachmentStrip.revalidate();
        attachmentStrip.repaint();
        refreshHeight();
    }

    // ------------------------------------------------------------------ layout helpers

    private static String shortName(Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        return name.length() <= 28 ? name : name.substring(0, 25) + "...";
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(UiMetrics.s(), 0));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(UiMetrics.xs(), 0, 0, 0));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiMetrics.xs(), 0));
        left.setOpaque(false);
        left.add(attachButton);
        left.add(modelButton);
        left.add(autoButton);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiMetrics.xs(), 0));
        right.setOpaque(false);
        right.add(settingsButton);
        right.add(stopButton);
        right.add(sendButton);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // Compact model picker: a list popup under the button, no default combo chrome.
    // 紧凑模型选择：按钮下方弹出列表，不使用默认下拉框外观。
    private void showModelMenu() {
        if (models.isEmpty()) {
            return;
        }
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(new ArrayList<>(models))
                .setTitle(AgentBundle.message("composer.model"))
                .setSelectedValue(activeModel, true)
                .setItemChosenCallback(name -> {
                    if (name == null || name.equals(activeModel)) {
                        return;
                    }
                    activeModel = name;
                    modelButton.setText(name);
                    listener.onModelChanged(name);
                })
                .createPopup()
                .showUnderneathOf(modelButton);
    }

    // Enter sends, Shift+Enter breaks the line (Ctrl+Enter also sends).
    // Enter 发送、Shift+Enter 换行，Ctrl+Enter 同样发送。
    private void installInputBindings() {
        InputMap keys = input.getInputMap(JComponent.WHEN_FOCUSED);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chat-send");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "chat-send");
        // A plain JTextArea has no Shift+Enter binding at all, so the key used to do nothing:
        // no newline, no send. Point it back at the editor's own line break.
        // 原生 JTextArea 根本没绑 Shift+Enter，按下去既不发也不换行，这里接回编辑器自带的换行。
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                DefaultEditorKit.insertBreakAction);
        input.getActionMap().put("chat-send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (busy) {
                    // Nothing can go out while the agent is running, so fall back to a newline
                    // instead of swallowing the key.
                    // 任务在跑时发不出去，把回车让给换行，避免按键没反应。
                    input.replaceSelection("\n");
                    return;
                }
                triggerSend();
            }
        });
    }

    private int lineHeight() {
        FontMetrics metrics = input.getFontMetrics(input.getFont());
        return metrics == null ? JBUI.scale(16) : metrics.getHeight();
    }

    private int chromeHeight() {
        Insets outer = getInsets();
        int strip = attachmentStrip.isVisible() ? attachmentStrip.getPreferredSize().height + UiMetrics.s() : 0;
        return outer.top + outer.bottom + strip + toolbar.getPreferredSize().height + UiMetrics.s()
                + JBUI.scale(18);
    }

    // Grow the input with its content and tell the owner how tall the composer wants to be.
    // 随内容长高输入区，并把期望高度告诉上层（由上层调整分隔条位置）。
    private void refreshHeight() {
        int width = input.getWidth();
        if (width <= 0) {
            width = Math.max(JBUI.scale(160), getWidth() - JBUI.scale(46));
        }
        input.setSize(width, Short.MAX_VALUE);
        int content = input.getPreferredSize().height;
        int min = lineHeight() * MIN_ROWS;
        int max = lineHeight() * MAX_ROWS;
        int visible = Math.max(min, Math.min(max, content));
        inputScroll.setPreferredSize(new Dimension(JBUI.scale(10), visible + JBUI.scale(4)));
        revalidate();
        boolean force = collapseAfterSend;
        collapseAfterSend = false;
        int desired = chromeHeight() + visible;
        // Only ask for room when the content actually grew (or after sending): asking on every
        // resize would fight the user dragging the divider.
        // 只在内容变多（或发送后）才提诉求；每次 resize 都提会和用户拖动分隔条打架。
        if (heightSink != null && (force || desired > lastNotifiedHeight + 4)) {
            lastNotifiedHeight = desired;
            heightSink.onDesiredHeight(desired, force);
        }
    }

    private static Font baseFont() {
        Font font = UIUtil.getLabelFont();
        return font != null ? font : new JLabel().getFont();
    }

    // ------------------------------------------------------------------ inner pieces

    // Floating card behind the text area: raised surface, 1 px border and a 2 px focus ring.
    // 输入框底板：抬高底色 + 1 px 描边，聚焦时加 2 px 焦点环。
    private final class ComposerBox extends JPanel {

        private float focus;

        ComposerBox(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(new EmptyBorder(UiMetrics.m(), UiMetrics.l(), UiMetrics.m(), UiMetrics.l()));
            add(content, BorderLayout.CENTER);
            input.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    animateFocus(1f);
                }

                @Override
                public void focusLost(FocusEvent e) {
                    animateFocus(0f);
                }
            });
        }

        private void animateFocus(float target) {
            UiAnim.fade(focus, target, 120, value -> {
                focus = value;
                repaint();
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = UiMetrics.radiusInput();
            int width = getWidth();
            int height = getHeight();
            g2.setColor(UiTheme.RAISED);
            g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
            g2.setColor(UiTheme.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));
            if (focus > 0.01f) {
                g2.setColor(UiTheme.withAlpha(UiTheme.ACCENT, Math.round(90 * focus)));
                g2.setStroke(new BasicStroke(JBUI.scale(2f)));
                g2.draw(new RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, arc, arc));
            }
            paintKeyHint(g2);
            g2.dispose();
            super.paintComponent(g);
        }

        // Bottom-right shortcut reminder, only while the box is empty.
        // 右下角的快捷键提示，只在空输入时显示。
        private void paintKeyHint(Graphics2D g2) {
            if (busy || !input.getText().isEmpty()) {
                return;
            }
            Font font = UiTheme.small();
            FontMetrics metrics = UiMetrics.metrics(font);
            String hint = AgentBundle.message("ui.composer.keys");
            int x = getWidth() - getInsets().right - metrics.stringWidth(hint);
            int y = getHeight() - getInsets().bottom + metrics.getAscent() + JBUI.scale(1);
            if (x < getInsets().left || y > getHeight()) {
                return;
            }
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(font);
            g2.setColor(UiTheme.muted());
            g2.drawString(hint, x, y);
        }
    }

    // FlowLayout never wraps in preferredLayoutSize (only in layoutContainer), so the strip
    // would report a single row of height and clip the second row of chips.
    // FlowLayout 计算偏好尺寸时假设单行（只有布局时才换行），会导致 chip 第二行被裁切，这里按宽度算行数。
    private static final class WrapLayout extends FlowLayout {
        WrapLayout() {
            super(FlowLayout.LEFT, UiMetrics.xs(), JBUI.scale(4));
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            synchronized (target.getTreeLock()) {
                int maxWidth = target.getWidth();
                if (maxWidth <= 0 && target.getParent() != null) {
                    maxWidth = target.getParent().getWidth();
                }
                Insets insets = target.getInsets();
                int limit = maxWidth - insets.left - insets.right;
                int hgap = getHgap();
                int vgap = getVgap();
                int rowWidth = 0;
                int rowHeight = 0;
                int totalHeight = 0;
                for (Component child : target.getComponents()) {
                    if (!child.isVisible()) {
                        continue;
                    }
                    Dimension size = child.getPreferredSize();
                    if (rowWidth > 0 && limit > 0 && rowWidth + hgap + size.width > limit) {
                        totalHeight += rowHeight + vgap;
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    rowWidth += (rowWidth == 0 ? 0 : hgap) + size.width;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                totalHeight += rowHeight;
                int width = maxWidth > 0 ? maxWidth : rowWidth;
                return new Dimension(width, totalHeight + insets.top + insets.bottom);
            }
        }
    }

    // Attachment chip: paperclip, elided file name and a close cross that only shows on hover.
    // 附件 chip：回形针 + 省略的文件名，关闭叉只在悬停时出现。
    private final class ChipView extends JComponent {

        private final Path path;
        private final Runnable onRemove;
        private final String label;
        private boolean hover;
        private boolean overClose;

        ChipView(Path path, Runnable onRemove) {
            this.path = path;
            this.onRemove = onRemove;
            this.label = shortName(path);
            setOpaque(false);
            setFont(UiTheme.small());
            setToolTipText(path.toString());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    overClose = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && closeBounds().contains(e.getPoint())) {
                        onRemove.run();
                    }
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    boolean next = closeBounds().contains(e.getPoint());
                    if (next != overClose) {
                        overClose = next;
                        repaint();
                    }
                }
            });
        }

        private Rectangle closeBounds() {
            int size = JBUI.scale(12);
            return new Rectangle(getWidth() - size - JBUI.scale(4),
                    (getHeight() - size) / 2, size, size);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = UiMetrics.metrics(getFont());
            int width = UiMetrics.m() + JBUI.scale(10) + JBUI.scale(5) + metrics.stringWidth(label)
                    + JBUI.scale(6) + JBUI.scale(12) + UiMetrics.xs();
            return new Dimension(width, Math.max(JBUI.scale(20), metrics.getHeight() + JBUI.scale(6)));
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int arc = UiMetrics.radiusChip();
            int width = getWidth();
            int height = getHeight();
            g2.setColor(hover ? UiTheme.HOVER : UiTheme.SUBTLE);
            g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));

            int left = UiMetrics.m();
            int glyph = JBUI.scale(10);
            UiIcons.paintPaperclip(g2, new Rectangle(left, (height - glyph) / 2, glyph, glyph),
                    UiTheme.muted());
            left += glyph + JBUI.scale(5);

            FontMetrics metrics = UiMetrics.metrics(getFont());
            g2.setFont(getFont());
            g2.setColor(UiTheme.text());
            int baseline = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(label, left, baseline);

            if (hover) {
                UiIcons.paintClose(g2, closeBounds(), overClose ? UiTheme.ERROR : UiTheme.muted());
            }
            g2.dispose();
        }
    }

    // Wrapping text area with a placeholder, since Swing has no built-in empty text for it.
    // 带占位提示的多行输入框：Swing 原生 JTextArea 没有空文本提示，这里自绘。
    private static final class InputArea extends JTextArea {

        private static final String HINT = AgentBundle.message("composer.hint");

        InputArea() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            setMargin(new Insets(0, 0, 0, 0));
            setFont(baseFont());
            setForeground(UIUtil.getLabelForeground());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getDocument().getLength() > 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            FontMetrics metrics = g2.getFontMetrics();
            int available = getWidth() - getInsets().left - getInsets().right;
            // Narrow tool windows cannot fit the full hint: fall back to shorter variants.
            // 工具窗口太窄时完整提示放不下，逐级降级为短文案。
            String hint = HINT;
            if (metrics.stringWidth(hint) > available) {
                hint = AgentBundle.message("composer.hint.short");
            }
            if (metrics.stringWidth(hint) > available) {
                hint = AgentBundle.message("composer.hint.tiny");
            }
            g2.setColor(UiTheme.muted());
            Insets insets = getInsets();
            g2.drawString(hint, insets.left, insets.top + metrics.getAscent());
            g2.dispose();
        }
    }

    // Up arrow inside the round send button.
    // 圆形发送按钮里的向上箭头。
    private static final class ArrowIcon implements Icon {

        private final int size;

        ArrowIcon(int size) {
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            UiIcons.paintUpArrow(g2, new Rectangle(0, 0, size, size), Color.WHITE);
            g2.dispose();
        }
    }

    // Down chevron that marks the compact model picker.
    // 紧凑模型选择按钮上的下拉折角。
    private static final class ChevronIcon implements Icon {

        private final int size;

        ChevronIcon(int size) {
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            UiIcons.paintChevron(g2, new Rectangle(0, 0, size, size), UiTheme.muted(), true);
            g2.dispose();
        }
    }
}
