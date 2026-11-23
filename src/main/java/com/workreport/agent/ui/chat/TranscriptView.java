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

    // The model's thinking process, streamed on its own channel. Renderers without a foldable
    // thinking row simply ignore it; the answer text never contains it.
    // 模型的思考过程，走独立通道；没有思考行的渲染器直接忽略，正文不含它。
    default void appendReasoningToken(String delta) {
    }

    void addToolLine(String text);

    // Tool / sub-agent progress with explicit state. `running` keeps the step group busy,
    // `failed` marks the group as failed; the one-argument form means "finished successfully".
    // 带状态的工具/子代理进度行：running 表示还在跑，failed 表示失败；单参版本等价于“已完成且成功”。
    default void addToolLine(String text, boolean running, boolean failed) {
        addToolLine(text);
    }

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
