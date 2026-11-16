package com.workreport.agent.ui.chat;

import com.workreport.agent.ui.design.UiMetrics;
import com.workreport.agent.ui.design.UiTheme;
import com.workreport.agent.ui.markdown.InlineSpan;
import com.workreport.agent.ui.markdown.MarkdownBlock;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

// Table block with a self-drawn grid: header on the subtle surface, every cell wrapped by its own
// text layout, row height taken from the tallest cell, columns sharing the width evenly.
// 自绘网格表格：表头铺浅底，单元格各自折行，行高取最高单元格，列宽均分。
public final class TableView extends BlockView {

    private final List<String> header;
    private final List<List<String>> rows;

    private final List<List<InlineLayout>> headerLayouts = new ArrayList<>();
    private final List<List<InlineLayout>> rowLayouts = new ArrayList<>();
    private int columns;
    private int columnWidth;
    private int headerHeight;
    private final List<Integer> rowTops = new ArrayList<>();

    public TableView(MarkdownBlock.Table table) {
        this.header = table.header().isEmpty() ? List.of() : table.header().get(0);
        this.rows = table.rows();
        setOpaque(false);
        TextCopy.install(this, () -> plain(table));
    }

    private int cellPad() {
        return UiMetrics.scale(6);
    }

    @Override
    protected int computeHeight(int width) {
        columns = Math.max(1, header.size());
        for (List<String> row : rows) {
            columns = Math.max(columns, row.size());
        }
        columnWidth = Math.max(UiMetrics.scale(48), width / columns);

        headerLayouts.clear();
        rowLayouts.clear();
        rowTops.clear();

        headerLayouts.add(layoutsFor(header, true));
        headerHeight = rowHeight(headerLayouts.get(0));

        int y = headerHeight;
        for (List<String> row : rows) {
            List<InlineLayout> layouts = layoutsFor(row, false);
            int height = rowHeight(layouts);
            rowLayouts.add(layouts);
            rowTops.add(y);
            y += height;
        }
        return Math.max(1, y);
    }

    private List<InlineLayout> layoutsFor(List<String> cells, boolean bold) {
        List<InlineLayout> layouts = new ArrayList<>(columns);
        int textWidth = Math.max(24, columnWidth - cellPad() * 2);
        for (int i = 0; i < columns; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            InlineSpan span = bold ? InlineSpan.plain(cell).withBold() : InlineSpan.plain(cell);
            InlineLayout layout = new InlineLayout(List.of(span), UiTheme.font(), false);
            layout.measure(textWidth);
            layouts.add(layout);
        }
        return layouts;
    }

    private int rowHeight(List<InlineLayout> layouts) {
        int height = 0;
        for (InlineLayout layout : layouts) {
            height = Math.max(height, layout.height());
        }
        return height + cellPad() * 2;
    }

    @Override
    protected void paintBlock(Graphics2D g2, int width, int height) {
        int radius = UiMetrics.radiusChip();
        g2.setColor(UiTheme.SUBTLE);
        g2.fillRoundRect(0, 0, width, headerHeight, radius, radius);
        g2.fillRect(0, headerHeight / 2, width, Math.max(0, height - headerHeight / 2));

        // Column separators: one hairline per boundary, drawn once for the whole table.
        // 列分隔线：每个边界一条细线，整表只画一遍。
        g2.setColor(UiTheme.BORDER);
        for (int column = 1; column < columns; column++) {
            int x = column * columnWidth;
            g2.drawLine(x, 0, x, height);
        }
        g2.drawLine(0, headerHeight, width, headerHeight);
        for (int i = 1; i < rowTops.size(); i++) {
            int y = rowTops.get(i);
            g2.drawLine(0, y, width, y);
        }

        for (int column = 0; column < headerLayouts.get(0).size(); column++) {
            headerLayouts.get(0).get(column).paint(g2, column * columnWidth + cellPad(), cellPad());
        }
        for (int row = 0; row < rowLayouts.size(); row++) {
            int top = rowTops.get(row);
            List<InlineLayout> layouts = rowLayouts.get(row);
            for (int column = 0; column < layouts.size(); column++) {
                layouts.get(column).paint(g2, column * columnWidth + cellPad(), top + cellPad());
            }
        }
    }
}
