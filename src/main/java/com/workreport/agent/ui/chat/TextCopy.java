package com.workreport.agent.ui.chat;

import javax.swing.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.function.Supplier;

// Copy plumbing shared by every custom-painted block.
// Self-drawn text is invisible to the platform's selection model, so each block exposes its plain
// text through a TransferHandler and the message header adds an explicit "copy" action.
// 自绘文本不在平台的选择模型里，因此每个块通过 TransferHandler 暴露纯文本，
// 消息头再提供显式「复制」动作作为兜底。
public final class TextCopy {

    private TextCopy() {
    }

    public static TransferHandler handler(Supplier<String> text) {
        return new TransferHandler() {
            @Override
            public int getSourceActions(JComponent component) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent component) {
                String value = text == null ? null : text.get();
                return new StringSelection(value == null ? "" : value);
            }
        };
    }

    public static void install(JComponent component, Supplier<String> text) {
        component.setTransferHandler(handler(text));
    }

    // Put text on the system clipboard, reporting failures in the status bar instead of throwing.
    // 把文本写入系统剪贴板；失败时不抛异常。
    public static void copyToClipboard(String text) {
        if (text == null) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (RuntimeException ignored) {
            // Clipboard may be locked by another process; nothing we can do from here.
            // 剪贴板可能被其它进程占用，这里不再处理。
        }
    }
}
