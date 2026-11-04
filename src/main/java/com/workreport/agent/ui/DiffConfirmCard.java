package com.workreport.agent.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;

// Modal diff confirmation shown before any file is modified by the agent.
// 文件被修改前展示的模态 diff 确认框。
public final class DiffConfirmCard {

    private static final int MAX_DIFF_LINES = 800;

    private DiffConfirmCard() {
    }

    // Blocking on purpose: called from a pooled thread, shows the dialog on the EDT.
    // 有意阻塞：在后台线程调用，切到 EDT 展示对话框。
    public static boolean confirm(Project project, Path path, String before, String after) {
        boolean[] accepted = {false};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            String title = "确认修改: " + path.getFileName();
            DialogWrapper dialog = new DialogWrapper(project, false) {
                private final JBTextArea area = new JBTextArea(renderDiff(before, after));

                {
                    init();
                    setTitle(title);
                }

                @Override
                protected @Nullable JComponent createCenterPanel() {
                    area.setEditable(false);
                    area.setLineWrap(false);
                    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    JBScrollPane scroll = new JBScrollPane(area);
                    scroll.setPreferredSize(new Dimension(900, 520));
                    return scroll;
                }
            };
            accepted[0] = dialog.showAndGet();
        });
        return accepted[0];
    }

    // Compact unified diff: common prefix/suffix are trimmed, the rest is marked.
    // 紧凑 unified diff：去掉公共前后缀，中间部分按行标记增删。
    static String renderDiff(String before, String after) {
        if (before.equals(after)) {
            return "（内容无变化）";
        }
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);

        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length
                && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) {
            suffix++;
        }

        StringBuilder sb = new StringBuilder();
        int context = 3;
        int contextStart = Math.max(0, prefix - context);
        for (int i = contextStart; i < prefix; i++) {
            sb.append("  ").append(oldLines[i]).append('\n');
        }
        for (int i = prefix; i < oldLines.length - suffix; i++) {
            sb.append("- ").append(oldLines[i]).append('\n');
        }
        for (int i = prefix; i < newLines.length - suffix; i++) {
            sb.append("+ ").append(newLines[i]).append('\n');
        }
        int contextEnd = Math.min(oldLines.length, oldLines.length - suffix + context);
        for (int i = oldLines.length - suffix; i < contextEnd; i++) {
            sb.append("  ").append(oldLines[i]).append('\n');
        }

        String text = sb.toString();
        if (before.isEmpty()) {
            text = "+（新文件）\n" + text;
        } else if (after.isEmpty()) {
            text = "-（删除文件）\n" + text;
        }

        String[] rendered = text.split("\n", -1);
        if (rendered.length > MAX_DIFF_LINES) {
            StringBuilder truncated = new StringBuilder();
            for (int i = 0; i < MAX_DIFF_LINES; i++) {
                truncated.append(rendered[i]).append('\n');
            }
            truncated.append("...（diff 过长，已截断，共 ").append(rendered.length).append(" 行）");
            return truncated.toString();
        }
        return text;
    }
}
