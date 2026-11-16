package com.workreport.agent.ui;

import com.workreport.agent.ui.chat.ChatRowView;
import com.workreport.agent.ui.chat.ChatView;
import com.workreport.agent.ui.chat.ClassicTranscript;
import com.workreport.agent.ui.chat.TranscriptView;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

// Facade over the transcript renderer.
// The session panel keeps calling this class exactly as before; which renderer runs behind it is
// decided once, here: the block renderer by default, the 3.0 HTML one behind -Dagent.ui.classic=true.
// 对话区门面：会话面板的调用方式完全不变，具体用哪套渲染在这里决定——
// 默认块渲染，`-Dagent.ui.classic=true` 时退回 3.0 的 HTML 渲染。
public final class ChatTranscript extends JPanel {

    private final TranscriptView view;

    public ChatTranscript() {
        super(new BorderLayout());
        setOpaque(false);
        view = classicMode() ? new ClassicTranscript() : new ChatView();
        add((Component) view, BorderLayout.CENTER);
    }

    private static boolean classicMode() {
        if (Boolean.getBoolean("agent.ui.classic")) {
            return true;
        }
        String value = System.getProperty("agent.ui.renderer", "");
        return "classic".equalsIgnoreCase(value);
    }

    // The block renderer can pre-fill the composer from its suggestion cards; the classic one
    // has no such surface and silently ignores the sink.
    // 块渲染的空态建议卡可以回填输入框；经典渲染没有这个结构，忽略即可。
    public void setSuggestionSink(Consumer<String> sink) {
        if (view instanceof ChatView chatView) {
            chatView.setSuggestionSink(sink);
        }
    }

    // ---------------------------------------------------------------- public API

    public void addHint(String text) {
        view.addHint(text);
    }

    public void addUserMessage(String text) {
        addUserMessage(text, List.of());
    }

    public void addUserMessage(String text, List<String> attachmentNames) {
        view.addUserMessage(text, attachmentNames);
    }

    public void appendAssistantToken(String delta) {
        view.appendAssistantToken(delta);
    }

    public void addToolLine(String text) {
        view.addToolLine(text);
    }

    public void addNotice(String text) {
        view.addNotice(text);
    }

    public void addErrorMessage(String text) {
        view.addErrorMessage(text);
    }

    public void finishAssistant() {
        view.finishAssistant();
    }

    // Mount an interactive row (MCP confirmation card); false means this renderer cannot host one
    // and the caller must fall back to a dialog.
    // 挂一张可交互的行（MCP 确认卡片）；返回 false 表示当前渲染器不支持，调用方需退回弹框。
    public boolean addCard(ChatRowView row) {
        return view.addCard(row);
    }

    public void clear() {
        view.clear();
    }

    public void dispose() {
        view.dispose();
    }
}
