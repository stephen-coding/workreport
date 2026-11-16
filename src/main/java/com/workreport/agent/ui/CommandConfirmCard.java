package com.workreport.agent.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;

// Modal confirmation shown before running a non-allowlisted terminal command.
// 非白名单命令执行前的模态确认框。
public final class CommandConfirmCard {

    private CommandConfirmCard() {
    }

    // Blocking on purpose: called from a pooled thread, shows the dialog on the EDT.
    // 有意阻塞：在后台线程调用，切到 EDT 展示对话框。
    public static boolean confirm(Project project, String command, Path cwd) {
        boolean[] accepted = {false};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            JBLabel hint = new JBLabel("Agent 请求执行以下命令；允许后才会运行。");
            hint.setForeground(Color.GRAY);
            hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
            DialogWrapper dialog = new DialogWrapper(project, false) {
                private final JBTextArea area = new JBTextArea(command);

                {
                    init();
                    setTitle("确认执行命令");
                    setOKButtonText("执行");
                    setCancelButtonText("跳过");
                }

                @Override
                protected @Nullable JComponent createCenterPanel() {
                    area.setEditable(false);
                    area.setLineWrap(true);
                    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    JBScrollPane scroll = new JBScrollPane(area);
                    scroll.setPreferredSize(new Dimension(720, 160));
                    JPanel panel = new JPanel(new BorderLayout(0, 8));
                    panel.add(new JBLabel("工作目录: " + cwd), BorderLayout.NORTH);
                    panel.add(scroll, BorderLayout.CENTER);
                    panel.add(hint, BorderLayout.SOUTH);
                    return panel;
                }
            };
            accepted[0] = dialog.showAndGet();
        });
        return accepted[0];
    }
}
