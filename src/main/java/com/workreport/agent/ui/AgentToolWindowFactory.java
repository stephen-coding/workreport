package com.workreport.agent.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

// Tool window factory — mounts the agent chat panel in the IDE bottom bar.
// 工具窗口工厂 — 在 IDE 底部挂载 Agent 聊天面板。
public final class AgentToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AgentChatPanel panel = new AgentChatPanel(project);
        Disposer.register(project, panel);
        Content content = ContentFactory.getInstance().createContent(panel, "Agent", false);
        toolWindow.getContentManager().addContent(content);
    }
}
