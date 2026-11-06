package com.workreport.agent.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import org.jetbrains.annotations.NotNull;

// Tools menu entry that jumps straight to the plugin settings page
// (model list / MCP servers / Embedding model) instead of hunting inside Settings.
// Tools 菜单入口：一键打开插件设置页（模型列表 / MCP / Embedding），
// 不必再进 Settings 里逐层翻找，同时让 Ctrl+Shift+A 也能搜到"模型设置"。
public final class OpenSettingsAction extends AnAction {

    private static final String SETTINGS_ID = "com.workreport.settings";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // The configurable is application-level, so it also works without an open project.
        ShowSettingsUtil.getInstance().showSettingsDialog(event.getProject(), SETTINGS_ID);
    }
}
