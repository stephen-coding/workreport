package com.workreport.agent.ui.chat;

import java.util.List;

// The surface a chat panel talks to: append messages, stream tokens, clear, dispose.
// `ChatTranscript` keeps this contract stable so the session panel never learns which renderer is
// behind it (new block renderer by default, the old HTML one behind -Dagent.ui.classic=true).
// 聊天面板面向的接口：追加消息、流式写入、清空、释放。
// `ChatTranscript` 保持不变，会话面板因此无需知道背后用的是块渲染还是旧的 HTML 渲染。
public interface TranscriptView {

    void addHint(String text);

    void addUserMessage(String text, List<String> attachmentNames);

    void appendAssistantToken(String delta);

    void addToolLine(String text);

    void addNotice(String text);

    void addErrorMessage(String text);

    void finishAssistant();

    // Mount an interactive row (the MCP confirmation card). Renderers without a component host
    // refuse it by returning false, so the caller can fall back to a modal dialog.
    // 挂一张可交互的行（MCP 确认卡片）。没有组件容器的渲染器返回 false，调用方据此退回弹窗。
    default boolean addCard(ChatRowView row) {
        return false;
    }

    void clear();

    void dispose();
}
