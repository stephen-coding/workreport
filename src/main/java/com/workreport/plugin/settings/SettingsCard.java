package com.workreport.plugin.settings;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

// Rounded section used by the settings page: bold heading, one muted caption line and a body that
// lays fields out as "label on the left, control on the right".
// The card paints its own surface so a group of fields reads as one block instead of a long column
// of labels separated by grey rules.
// 设置页的圆角分节卡片：粗体标题 + 一行浅色说明 + 主体（左标签、右控件）。
// 底色与描边由卡片自绘，一组字段因此读起来是一块，而不是一长列被灰线切开的标签。
public final class SettingsCard extends JPanel {

    // Label column: wide enough for the longest settings label at 100% and still stable at 200%.
    // 标签列宽：100% 缩放下最长的标签也放得下，200% 缩放同样稳定。
    private static final int LABEL_WIDTH = 108;

    private static final Color FILL = new JBColor(new Color(0xFFFFFF), new Color(0x2B2D30));
    private static final Color LINE = JBColor.namedColor("Borders.color",
            new JBColor(new Color(0xDBDFE6), new Color(0x3A3D41)));

    private final JPanel body = new JPanel();
    private final JLabel captionLabel;

    public SettingsCard(String heading, @Nullable String caption) {
        super(new BorderLayout(0, JBUI.scale(10)));
        setOpaque(false);
        setBorder(new EmptyBorder(JBUI.scale(14), JBUI.scale(16), JBUI.scale(14), JBUI.scale(16)));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.add(titleLabel(heading));
        captionLabel = captionLabel(caption);
        captionLabel.setVisible(caption != null && !caption.isBlank());
        head.add(captionLabel);
        add(head, BorderLayout.NORTH);

        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        add(body, BorderLayout.CENTER);
    }

    // Kept for the (rare) caller that wants to restyle the caption after construction.
    // 供少数调用方在构造后改写说明文字。
    public void setCaption(String text) {
        captionLabel.setText(text == null ? "" : text);
        captionLabel.setVisible(text != null && !text.isBlank());
        revalidate();
        repaint();
    }

    // One "label + control" line; the control takes the remaining width.
    // 一行「标签 + 控件」，控件占满剩余宽度。
    public SettingsCard row(String label, JComponent control) {
        Line line = new Line(new BorderLayout(JBUI.scale(10), 0));
        line.setBorder(new EmptyBorder(0, 0, JBUI.scale(8), 0));
        JLabel name = new JLabel(label);
        name.setPreferredSize(new Dimension(JBUI.scale(LABEL_WIDTH), JBUI.scale(28)));
        line.add(name, BorderLayout.WEST);
        line.add(control, BorderLayout.CENTER);
        addLine(line);
        return this;
    }

    // A full-width block (text areas, list + form pairs) without the label column.
    // The wrapper carries the spacing, so the component keeps its own border.
    // 不带标签列的全宽内容（文本域、列表 + 表单等）；间距由包裹层负责，组件自身描边不受影响。
    public SettingsCard block(JComponent component) {
        addLine(wrap(component));
        return this;
    }

    // Two controls side by side, used for the numeric grid.
    // 并排的两个控件，数字参数用这种网格。
    public SettingsCard pair(String leftLabel, JComponent left, String rightLabel, JComponent right) {
        Line line = new Line(new GridBagLayout());
        line.setBorder(new EmptyBorder(0, 0, JBUI.scale(8), 0));
        GridBagConstraints leftName = new GridBagConstraints();
        leftName.gridx = 0;
        leftName.gridy = 0;
        leftName.anchor = GridBagConstraints.WEST;
        leftName.insets = new Insets(0, 0, 0, JBUI.scale(8));
        line.add(new JLabel(leftLabel), leftName);
        GridBagConstraints leftField = new GridBagConstraints();
        leftField.gridx = 1;
        leftField.gridy = 0;
        leftField.weightx = 1;
        leftField.fill = GridBagConstraints.HORIZONTAL;
        leftField.insets = new Insets(0, 0, 0, JBUI.scale(18));
        line.add(left, leftField);
        GridBagConstraints rightName = new GridBagConstraints();
        rightName.gridx = 2;
        rightName.gridy = 0;
        rightName.anchor = GridBagConstraints.WEST;
        rightName.insets = new Insets(0, 0, 0, JBUI.scale(8));
        line.add(new JLabel(rightLabel), rightName);
        GridBagConstraints rightField = new GridBagConstraints();
        rightField.gridx = 3;
        rightField.gridy = 0;
        rightField.weightx = 1;
        rightField.fill = GridBagConstraints.HORIZONTAL;
        line.add(right, rightField);
        addLine(line);
        return this;
    }

    // Small muted line under a control (validation result, hint).
    // 控件下方的一行浅色说明（校验结果、提示）。
    public SettingsCard note(JComponent component) {
        addLine(wrap(component));
        return this;
    }

    private static JPanel wrap(JComponent component) {
        Line line = new Line(new BorderLayout());
        line.setBorder(new EmptyBorder(0, 0, JBUI.scale(8), 0));
        line.add(component, BorderLayout.CENTER);
        return line;
    }

    private void addLine(JComponent line) {
        line.setAlignmentX(LEFT_ALIGNMENT);
        body.add(line);
    }

    // BoxLayout stretches a child only when its maximum size allows it; a plain JPanel reports
    // maximum == preferred, so fields would stay at their minimum width inside the card.
    // BoxLayout 只在最大尺寸允许时才拉伸子组件；普通 JPanel 的最大尺寸等于偏好尺寸，
    // 字段因此停留在最小宽度，无法铺满卡片。这里放开宽度、保留内容高度。
    private static final class Line extends JPanel {

        Line(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private JLabel titleLabel(String heading) {
        JLabel label = new JLabel(heading);
        Font base = UIUtil.getLabelFont();
        if (base != null) {
            label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1.5f));
        }
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JLabel captionLabel(String caption) {
        JLabel label = new JLabel(caption == null ? "" : caption);
        Font base = UIUtil.getLabelFont();
        if (base != null) {
            label.setFont(base.deriveFont(base.getSize2D() - 1f));
        }
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setBorder(new EmptyBorder(JBUI.scale(3), 0, 0, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = JBUI.scale(10);
        int width = getWidth();
        int height = getHeight();
        g2.setColor(FILL);
        g2.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
        g2.setColor(LINE);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));
        g2.dispose();
        super.paintComponent(g);
    }
}