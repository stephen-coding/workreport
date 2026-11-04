package com.workreport.agent.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.workreport.agent.ui.AgentChatPanel;
import org.jetbrains.annotations.NotNull;

// Legacy report entry point: the classic UI now just asks the agent for a daily
// report, and the built-in daily-report skill drives the git collection.
// 旧报告入口：不再自己调 LLM，而是向 Agent 发一条"生成日报"指令，
// 由内置 daily-report Skill 驱动 git 采集与润色。
public final class GenerateReportAction extends AnAction {

    private static final String TOOL_WINDOW_ID = "AgentCoder";
    private static final String PRESET = "生成今天的日报";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (window != null) {
            window.show();
        }
        AgentChatPanel panel = AgentChatPanel.find(project);
        if (panel == null) {
            // The tool window is created lazily; activate it so the panel exists.
            if (window != null) {
                window.activate(null);
            }
            panel = AgentChatPanel.find(project);
        }
        if (panel != null) {
            panel.sendPresetMessage(PRESET);
        }
    }
}
