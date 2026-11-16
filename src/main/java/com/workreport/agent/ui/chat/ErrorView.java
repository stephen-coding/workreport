package com.workreport.agent.ui.chat;

// Error line: the same slim shape as a notice, in the error tone. It keeps the copy action so a
// failed turn can be pasted into an issue without retyping the message.
// 错误行：与提示行同形，采用错误语气，并保留复制动作，方便把失败信息贴进 issue。
public final class ErrorView extends NoticeView {

    public ErrorView(String text) {
        super(text, Tone.ERROR);
    }
}
