package com.workreport.plugin.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.workreport.plugin.settings.ReportSettingsConfigurable;
import com.workreport.plugin.settings.ReportSettingsState;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;

// AI polish service — async LLM API call (supports Ollama local / OpenAI-compatible cloud)
// AI 润色服务 — 异步调用 LLM API（支持 Ollama 本地 / OpenAI 兼容云端）
public final class ReportPolishService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    // Key logic: JDK 21 Duration for timeout configuration
    // 关键逻辑：JDK 21 Duration 设置超时
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    // Get system prompt based on report type
    private String getSystemPrompt(String reportType) {
        return switch (reportType) {
            case "daily" -> getDailyPrompt();
            case "quarterly" -> getQuarterlyPrompt();
            case "yearly" -> getYearlyPrompt();
            default -> getWeeklyPrompt();
        };
    }

    private String getDailyPrompt() {
        return """
                你是一个专业的日报润色助手。请将用户提供的工作内容改写为一份结构清晰、语言专业的中文日报。
                要求：
                1. 保持所有事实信息准确，不要捏造内容
                2. 使用专业的工作汇报语言
                3. 按照"今日完成工作"、"明日计划"、"遇到的问题"三个板块组织
                4. 每个工作项用简洁的一句话描述，突出成果和价值
                5. 保持原文的技术术语不变
                """;
    }

    private String getWeeklyPrompt() {
        return """
                你是一个专业的周报润色助手。请将用户提供的工作内容改写为一份结构清晰、语言专业的中文周报。
                要求：
                1. 保持所有事实信息准确，不要捏造内容
                2. 使用专业的工作汇报语言
                3. 按照"本周工作内容"、"下周计划"、"需要协调的问题"三个板块组织
                4. 每个工作项用简洁的一句话描述，突出成果和价值
                5. 保持原文的技术术语不变
                """;
    }

    private String getQuarterlyPrompt() {
        return """
                你是一个专业的季度报告润色助手。请将用户提供的工作内容改写为一份结构清晰、语言专业的中文季度报告。
                要求：
                1. 保持所有事实信息准确，不要捏造内容
                2. 使用专业的工作汇报语言，注重总结性和概括性
                3. 按照"本季度主要成果"、"项目进展总结"、"下季度规划"、"风险与挑战"四个板块组织
                4. 对工作内容进行归纳提炼，突出关键成果和里程碑
                5. 保持原文的技术术语不变
                """;
    }

    private String getYearlyPrompt() {
        return """
                你是一个专业的年度报告润色助手。请将用户提供的工作内容改写为一份结构清晰、语言专业的中文年度报告。
                要求：
                1. 保持所有事实信息准确，不要捏造内容
                2. 使用专业的工作汇报语言，注重战略性和总结性
                3. 按照"年度工作回顾"、"年度成果总结"、"来年目标"、"个人成长与反思"四个板块组织
                4. 对工作内容进行高度概括，突出年度亮点和重要成就
                5. 保持原文的技术术语不变
                """;
    }

    /**
     * Async polish report text (with progress bar) - non-streaming fallback.
     * 异步润色报告文本（带进度条）- 非流式回退方法。
     */
    public void polishAsync(Project project, String text, String reportType,
                            Consumer<String> onSuccess, Consumer<Exception> onError) {
        polishAsyncStreaming(project, text, reportType, null, onSuccess, onError);
    }

    /**
     * Async polish with streaming output support.
     * 异步润色，支持流式输出。
     *
     * @param project    current project / 当前项目
     * @param text       text to polish / 待润色文本
     * @param reportType report type / 报告类型
     * @param onToken    token callback for streaming (null = non-streaming) / 流式 token 回调
     * @param onSuccess  success callback with full result / 成功回调（完整结果）
     * @param onError    error callback / 失败回调
     */
    public void polishAsyncStreaming(Project project, String text, String reportType,
                                     Consumer<String> onToken,
                                     Consumer<String> onSuccess, Consumer<Exception> onError) {
        String taskTitle = getTaskTitle(reportType);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, taskTitle, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("正在连接 AI 服务...");
                try {
                    if (onToken != null) {
                        // 流式模式
                        StringBuilder fullResult = new StringBuilder();
                        polishSyncStreaming(text, reportType, token -> {
                            fullResult.append(token);
                            // 关键逻辑：回到 EDT 更新 UI，实现打字机效果
                            ApplicationManager.getApplication().invokeLater(() -> onToken.accept(token));
                        });
                        ApplicationManager.getApplication().invokeLater(() -> onSuccess.accept(fullResult.toString()));
                    } else {
                        // 非流式回退
                        String result = polishSync(text, reportType);
                        ApplicationManager.getApplication().invokeLater(() -> onSuccess.accept(result));
                    }
                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() -> onError.accept(e));
                }
            }
        });
    }

    private String getTaskTitle(String reportType) {
        return switch (reportType) {
            case "daily" -> "正在润色日报...";
            case "quarterly" -> "正在润色季度报告...";
            case "yearly" -> "正在润色年度报告...";
            default -> "正在润色周报...";
        };
    }

    // Key logic: Synchronous polish (called on background thread), build different request formats by mode
    // 关键逻辑：同步润色（在后台线程调用），根据模式构造不同请求格式
    private String polishSync(String reportText, String reportType) throws IOException {
        ReportSettingsState settings = ReportSettingsState.getInstance();
        String endpoint = settings.apiEndpoint;
        String model = settings.modelName;
        boolean isOllama = settings.useOllamaMode;

        String escapedContent = escapeJson(reportText);
        String escapedSystemPrompt = escapeJson(getSystemPrompt(reportType));

        // Key logic: Text Blocks format JSON request body
        // 关键逻辑：Text Blocks 格式化 JSON 请求体
        String jsonBody;
        if (isOllama) {
            // Ollama local mode request format
            // Ollama 本地模式请求格式
            jsonBody = """
                    {
                      "model": "%s",
                      "messages": [
                        {"role": "system", "content": "%s"},
                        {"role": "user", "content": "%s"}
                      ],
                      "stream": false
                    }
                    """.formatted(escapeJson(model), escapedSystemPrompt, escapedContent);
        } else {
            // OpenAI-compatible cloud API request format
            // OpenAI 兼容云端 API 请求格式
            jsonBody = """
                    {
                      "model": "%s",
                      "messages": [
                        {"role": "system", "content": "%s"},
                        {"role": "user", "content": "%s"}
                      ],
                      "temperature": 0.7
                    }
                    """.formatted(escapeJson(model), escapedSystemPrompt, escapedContent);
        }

        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .post(body);

        // Key logic: Add Bearer Token auth header for cloud mode
        // 关键逻辑：云端模式添加 Bearer Token 认证头
        if (!isOllama) {
            String apiKey = ReportSettingsConfigurable.retrieveApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
        }

        Request request = requestBuilder.build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API 请求失败，状态码: " + response.code()
                        + "，响应: " + (response.body() != null ? response.body().string() : "无"));
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            return parseResponse(responseBody, isOllama);
        }
    }

    // Key logic: Parse different API response formats
    // 关键逻辑：解析不同格式的 API 响应
    private String parseResponse(String responseBody, boolean isOllama) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

            if (isOllama) {
                // Ollama format: { "message": { "content": "..." } }
                // Ollama 格式：{ "message": { "content": "..." } }
                return root.getAsJsonObject("message")
                           .get("content")
                           .getAsString();
            } else {
                // OpenAI format: { "choices": [{ "message": { "content": "..." } }] }
                // OpenAI 格式：{ "choices": [{ "message": { "content": "..." } }] }
                return root.getAsJsonArray("choices")
                           .get(0).getAsJsonObject()
                           .getAsJsonObject("message")
                           .get("content")
                           .getAsString();
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 AI 响应失败: " + e.getMessage()
                    + "\n原始响应: " + responseBody, e);
        }
    }

    /**
     * Streaming polish - SSE (Server-Sent Events) format.
     * 流式润色 - SSE 格式。
     */
    private void polishSyncStreaming(String reportText, String reportType,
                                     Consumer<String> onToken) throws IOException {
        ReportSettingsState settings = ReportSettingsState.getInstance();
        String endpoint = settings.apiEndpoint;
        String model = settings.modelName;
        boolean isOllama = settings.useOllamaMode;

        String escapedContent = escapeJson(reportText);
        String escapedSystemPrompt = escapeJson(getSystemPrompt(reportType));

        // 流式请求体
        String jsonBody;
        if (isOllama) {
            jsonBody = """
                    {
                      "model": "%s",
                      "messages": [
                        {"role": "system", "content": "%s"},
                        {"role": "user", "content": "%s"}
                      ],
                      "stream": true
                    }
                    """.formatted(escapeJson(model), escapedSystemPrompt, escapedContent);
        } else {
            jsonBody = """
                    {
                      "model": "%s",
                      "messages": [
                        {"role": "system", "content": "%s"},
                        {"role": "user", "content": "%s"}
                      ],
                      "temperature": 0.7,
                      "stream": true
                    }
                    """.formatted(escapeJson(model), escapedSystemPrompt, escapedContent);
        }

        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .post(body);

        if (!isOllama) {
            String apiKey = ReportSettingsConfigurable.retrieveApiKey();
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
        }

        Request request = requestBuilder.build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API 请求失败，状态码: " + response.code());
            }

            // 关键逻辑：逐行读取 SSE 流
            try (BufferedReader reader = new BufferedReader(
                    response.body() != null ? response.body().charStream() : null)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // SSE 格式: data: {...}
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        // 流结束标记
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        // 解析 token
                        String token = extractTokenFromStream(data, isOllama);
                        if (token != null && !token.isEmpty()) {
                            onToken.accept(token);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从 SSE 数据中提取 token 内容
     */
    private String extractTokenFromStream(String data, boolean isOllama) {
        try {
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();
            if (isOllama) {
                // Ollama: { "message": { "content": "..." } }
                return root.getAsJsonObject("message").get("content").getAsString();
            } else {
                // OpenAI: { "choices": [{ "delta": { "content": "..." } }] }
                var choices = root.getAsJsonArray("choices");
                if (choices != null && choices.size() > 0) {
                    var delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta != null && delta.has("content")) {
                        var content = delta.get("content");
                        return content.isJsonNull() ? "" : content.getAsString();
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时返回空，继续处理下一行
        }
        return "";
    }

    // JSON string escape
    // JSON 字符串转义
    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    /**
     * Send error notification to IDE notification bar.
     * 发送错误通知到 IDE 通知栏。
     */
    public static void notifyError(Project project, String message) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("WorkReport Notifications")
                    .createNotification("报告助手错误", message, NotificationType.ERROR)
                    .notify(project);
        } catch (Exception e) {
            // Fallback when notification group is not registered
            // 通知组未注册时的回退方案
            com.intellij.openapi.ui.Messages.showErrorDialog(project, message, "报告助手错误");
        }
    }
}
