package com.workreport.agent.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.UIUtil;

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
import java.awt.event.KeyEvent;
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

    private static final Color CHIP_BG = new JBColor(new Color(0xE9EEF6), new Color(0x3A3F4B));
    private static final Color CHIP_TEXT = new JBColor(new Color(0x2A3550), new Color(0xE4E8F0));

    private final Project project;
    private final Listener listener;
    private final HeightSink heightSink;

    private final InputArea input = new InputArea();
    private final JBScrollPane inputScroll;
    private final JPanel attachmentStrip = new JPanel(new WrapLayout());
    private final List<Path> attachments = new ArrayList<>();

    private final JButton attachButton = new JButton("附件");
    private final JButton settingsButton = new JButton("设置");
    private final JButton stopButton = new JButton("停止");
    private final JButton sendButton = new JButton("发送");
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JCheckBox autoCheckBox = new JCheckBox("自动执行");
    private final JPanel toolbar;

    private boolean updatingModels;
    private boolean collapseAfterSend;
    private int lastNotifiedHeight;

    public ChatComposer(Project project, Listener listener, HeightSink heightSink) {
        super(new BorderLayout(0, 6));
        this.project = project;
        this.listener = listener;
        this.heightSink = heightSink;

        setOpaque(false);
        setBorder(new EmptyBorder(6, 0, 2, 0));

        attachmentStrip.setOpaque(false);
        attachmentStrip.setVisible(false);

        inputScroll = new JBScrollPane(input);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.getVerticalScrollBar().setUnitIncrement(16);

        toolbar = buildToolbar();

        add(attachmentStrip, BorderLayout.NORTH);
        add(new ComposerBox(inputScroll), BorderLayout.CENTER);
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

    public void setModels(List<String> names, String active) {
        updatingModels = true;
        try {
            modelCombo.removeAllItems();
            for (String name : names) {
                modelCombo.addItem(name);
            }
            modelCombo.setSelectedItem(active);
        } finally {
            updatingModels = false;
        }
    }

    public void setAutoAccept(boolean value) {
        autoCheckBox.setSelected(value);
    }

    public void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
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
        if (!sendButton.isEnabled()) {
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
        JMenuItem pick = new JMenuItem("选择文件…");
        pick.addActionListener(e -> pickFiles());
        JMenuItem current = new JMenuItem("添加当前编辑器文件");
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
                .withTitle("添加附件")
                .withDescription("文本文件会随消息内联发送（单文件 40 KB 内），二进制或超大文件只提供路径");
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
            attachmentStrip.add(createChip(path));
        }
        attachmentStrip.setVisible(!attachments.isEmpty());
        attachmentStrip.revalidate();
        attachmentStrip.repaint();
        refreshHeight();
    }

    private JComponent createChip(Path path) {
        Chip chip = new Chip();
        JLabel name = new JLabel(shortName(path));
        name.setToolTipText(path.toString());
        name.setForeground(CHIP_TEXT);
        Font chipFont = baseFont();
        name.setFont(chipFont.deriveFont(chipFont.getSize2D() - 1f));

        JButton remove = new JButton("x");
        remove.setToolTipText("移除附件");
        remove.setFocusable(false);
        remove.setMargin(new Insets(0, 3, 0, 3));
        remove.setBorderPainted(false);
        remove.setContentAreaFilled(false);
        remove.setForeground(CHIP_TEXT);
        remove.addActionListener(e -> {
            attachments.remove(path);
            refreshAttachments();
        });

        chip.add(name);
        chip.add(remove);
        return chip;
    }

    // ------------------------------------------------------------------ layout helpers

    private static String shortName(Path path) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        return name.length() <= 28 ? name : name.substring(0, 25) + "...";
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        attachButton.setToolTipText("添加文件作为上下文（可多选）");
        attachButton.addActionListener(e -> showAttachMenu());
        modelCombo.setToolTipText("切换模型（在设置页维护列表）");
        autoCheckBox.setToolTipText("勾选后写文件不再弹 diff 确认，直接落盘（可用 revert_file 回退）");
        left.add(attachButton);
        left.add(new JBLabel("模型:"));
        left.add(modelCombo);
        left.add(autoCheckBox);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        stopButton.setEnabled(false);
        sendButton.setFont(baseFont().deriveFont(Font.BOLD));
        sendButton.setMargin(new Insets(4, 18, 4, 18));
        right.add(settingsButton);
        right.add(stopButton);
        right.add(sendButton);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        sendButton.addActionListener(e -> triggerSend());
        stopButton.addActionListener(e -> listener.onStop());
        settingsButton.addActionListener(e -> listener.onOpenSettings());
        modelCombo.addActionListener(e -> {
            if (updatingModels) {
                return;
            }
            Object selected = modelCombo.getSelectedItem();
            if (selected != null) {
                listener.onModelChanged(selected.toString());
            }
        });
        autoCheckBox.addActionListener(e -> listener.onAutoAcceptChanged(autoCheckBox.isSelected()));
        return bar;
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
                if (!sendButton.isEnabled()) {
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
        return metrics == null ? 16 : metrics.getHeight();
    }

    private int chromeHeight() {
        Insets outer = getInsets();
        int strip = attachmentStrip.isVisible() ? attachmentStrip.getPreferredSize().height + 6 : 0;
        return outer.top + outer.bottom + strip + toolbar.getPreferredSize().height + 6 + 18;
    }

    // Grow the input with its content and tell the owner how tall the composer wants to be.
    // 随内容长高输入区，并把期望高度告诉上层（由上层调整分隔条位置）。
    private void refreshHeight() {
        int width = input.getWidth();
        if (width <= 0) {
            width = Math.max(160, getWidth() - 46);
        }
        input.setSize(width, Short.MAX_VALUE);
        int content = input.getPreferredSize().height;
        int min = lineHeight() * MIN_ROWS;
        int max = lineHeight() * MAX_ROWS;
        int visible = Math.max(min, Math.min(max, content));
        inputScroll.setPreferredSize(new Dimension(10, visible + 4));
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

    // Rounded input surface that follows the IDE theme.
    // 圆角输入框底板，配色跟随 IDE 主题。
    private static final class ComposerBox extends JPanel {
        ComposerBox(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(new EmptyBorder(10, 12, 10, 12));
            add(content, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1;
            int height = getHeight() - 1;
            g2.setColor(JBColor.namedColor("TextField.background", new Color(0xF7F8FA)));
            g2.fillRoundRect(0, 0, width, height, 12, 12);
            g2.setColor(JBColor.namedColor("Component.borderColor", new Color(0xC9CDD3)));
            g2.drawRoundRect(0, 0, width, height, 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // FlowLayout never wraps in preferredLayoutSize (only in layoutContainer), so the strip
    // would report a single row of height and clip the second row of chips.
    // FlowLayout 计算偏好尺寸时假设单行（只有布局时才换行），会导致 chip 第二行被裁切，这里按宽度算行数。
    private static final class WrapLayout extends FlowLayout {
        WrapLayout() {
            super(FlowLayout.LEFT, 6, 2);
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

    private static final class Chip extends JPanel {
        Chip() {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(2, 8, 2, 4));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(CHIP_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // Wrapping text area with a placeholder, since Swing has no built-in empty text for it.
    // 带占位提示的多行输入框：Swing 原生 JTextArea 没有空文本提示，这里自绘。
    private static final class InputArea extends JTextArea {

        private static final String HINT = "向 Agent 描述任务，Enter 发送，Shift+Enter 换行；「附件」或直接拖文件进来";

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
                hint = "向 Agent 描述任务…";
            }
            if (metrics.stringWidth(hint) > available) {
                hint = "描述任务…";
            }
            g2.setColor(JBColor.namedColor("Component.placeholderForeground",
                    new Color(0x9AA0A6)));
            Insets insets = getInsets();
            g2.drawString(hint, insets.left, insets.top + metrics.getAscent());
            g2.dispose();
        }
    }
}
