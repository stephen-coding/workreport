package com.workreport.plugin.privacy;

import com.intellij.openapi.ui.Messages;
import com.workreport.plugin.settings.ReportSettingsState;

// Privacy confirmation dialog utility — red warning, must pop up on first polish use
// 隐私确认弹窗工具类 — 红色警告，首次润色必弹
public final class PrivacyNoticeUtil {

    private PrivacyNoticeUtil() {
    }

    /**
     * Show privacy warning dialog and return whether user confirmed.
     * Confirmation is persisted to avoid repeated prompts.
     * <p>
     * 显示隐私警告弹窗，返回用户是否确认。
     * 确认后会持久化状态，避免重复弹出。
     *
     * @param endpoint current API endpoint address / 当前配置的 API 端点地址
     * @return true = user confirmed / 用户确认，false = user cancelled / 用户取消
     */
    public static boolean showPrivacyWarning(String endpoint) {
        // Key logic: Build HTML content with red warning text
        // 关键逻辑：构造含红色警告的 HTML 内容
        String htmlMessage = """
                <html><body style="width:420px;">
                <p style="color:red;font-size:14px;font-weight:bold;">
                &#9888;&#65039; 警告：润色功能将发送您的报告内容至外部服务！
                </p>
                <hr/>
                <p>您的报告文本将被发送至以下地址进行 AI 润色：</p>
                <p style="font-weight:bold;color:#336699;">%s</p>
                <ul>
                <li>如果您使用的是 <b>Ollama（本地模式）</b>，数据不会离开您的计算机。</li>
                <li>如果您使用的是 <b>云端 API</b>，数据将通过网络发送至第三方服务。</li>
                </ul>
                <p style="color:red;">请确认您了解并接受上述数据传输行为。</p>
                </body></html>
                """.formatted(escapeHtml(endpoint));

        int result = Messages.showDialog(
                htmlMessage,
                "隐私与数据传输提示",
                new String[]{"我已了解并同意", "取消"},
                1, // Default selection: "Cancel" / 默认选中"取消"
                Messages.getWarningIcon()
        );

        // Key logic: Persist confirmation state after user accepts
        // 关键逻辑：用户确认后持久化状态
        if (result == 0) {
            ReportSettingsState.getInstance().privacyAccepted = true;
            return true;
        }
        return false;
    }

    /**
     * Check if privacy notice needs to be shown (show dialog if not yet confirmed).
     * 检查是否需要显示隐私提示（未确认则弹窗）。
     *
     * @return true = confirmed and can proceed / 已确认可以继续，false = user declined / 用户拒绝
     */
    public static boolean ensurePrivacyAccepted() {
        ReportSettingsState settings = ReportSettingsState.getInstance();
        if (settings.privacyAccepted) {
            return true;
        }
        return showPrivacyWarning(settings.apiEndpoint);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
