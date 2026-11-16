package com.workreport.agent.ui;

import com.intellij.util.ui.UIUtil;
import com.workreport.agent.AgentBundle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

// Collapsible checklist above the transcript, fed by the Core's chat/todos event.
// The list is owned by the Core (todo_write); this strip only renders it and folds
// itself away once every item is finished, so the transcript gets the room back.
// 对话区上方的任务清单：内容由 Core 的 todo_write 维护，这里只负责渲染；
// 全部完成后自动折叠成一行，把空间还给对话。
public final class TodoStrip extends JPanel {

    // One row; the Shell never edits it, it just shows what the Core sent.
    // 单行数据：Shell 只展示，不修改。
    public static final class Item {
        public final String content;
        public final String status;

        public Item(String content, String status) {
            this.content = content;
            this.status = status;
        }
    }

    // Same vocabulary as agent/todos.py, so both sides read alike.
    // 与 agent/todos.py 一致的状态符号。
    private static final String PENDING = "☐";
    private static final String ACTIVE = "◐";
    private static final String DONE = "☑";
    private static final String CANCELLED = "☒";

    private final JLabel header = new JLabel();
    private final JPanel rows = new JPanel();
    private boolean collapsed;
    private int doneCount;
    private int totalCount;

    public TodoStrip() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(4, 10, 2, 10));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText(AgentBundle.message("todos.tip"));
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setCollapsed(!collapsed);
            }
        });
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        add(header, BorderLayout.NORTH);
        add(rows, BorderLayout.CENTER);
        setVisible(false);
    }

    // Replace the whole list; an empty one hides the strip entirely.
    // 整表覆盖；清单为空时整条隐藏。
    public void setItems(List<Item> items) {
        rows.removeAll();
        totalCount = items == null ? 0 : items.size();
        doneCount = 0;
        if (items == null || items.isEmpty()) {
            setVisible(false);
            return;
        }
        Font base = UIUtil.getLabelFont() != null ? UIUtil.getLabelFont() : new JLabel().getFont();
        for (Item item : items) {
            boolean done = isDone(item.status);
            if (done) {
                doneCount++;
            }
            JLabel row = new JLabel(mark(item.status) + " " + item.content);
            row.setFont(done ? base : base.deriveFont(Font.BOLD));
            if (done) {
                row.setForeground(UIUtil.getContextHelpForeground());
            }
            row.setBorder(new EmptyBorder(1, 0, 1, 0));
            rows.add(row);
        }
        setVisible(true);
        // Nothing left to do: fold it away without a second click.
        // 已无待办项时直接折叠，不需要再点一次。
        setCollapsed(doneCount >= totalCount);
        refreshHeader();
        revalidate();
        repaint();
    }

    private void setCollapsed(boolean value) {
        collapsed = value;
        rows.setVisible(!value);
        refreshHeader();
        revalidate();
        repaint();
    }

    private void refreshHeader() {
        String caret = collapsed ? "▸ " : "▾ ";
        String suffix = collapsed && doneCount >= totalCount ? AgentBundle.message("todos.done") : "";
        header.setText(AgentBundle.message("todos.header", caret, doneCount, totalCount, suffix));
    }

    private static boolean isDone(String status) {
        return "completed".equals(status) || "cancelled".equals(status);
    }

    private static String mark(String status) {
        if ("completed".equals(status)) {
            return DONE;
        }
        if ("in_progress".equals(status)) {
            return ACTIVE;
        }
        if ("cancelled".equals(status)) {
            return CANCELLED;
        }
        return PENDING;
    }
}
