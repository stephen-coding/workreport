package com.workreport.plugin.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

// Tool window factory — creates the Work Report panel in the IDE bottom bar
// 工具窗口工厂 — 在 IDE 底部创建工作汇报面板
public final class ReportToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Key logic: Create main panel and add to tool window
        // 关键逻辑：创建主面板并添加到工具窗口
        ReportToolWindowPanel panel = new ReportToolWindowPanel(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(panel, "工作报告", false);
        toolWindow.getContentManager().addContent(content);
    }
}
