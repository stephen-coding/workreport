package com.workreport.agent.inline;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.workreport.agent.rpc.AgentCoreService;
import com.workreport.agent.rpc.JsonRpcConnection;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Thin client for Core's `inline/complete` (fill-in-the-middle) request.
// Core 的 `inline/complete`（FIM 补全）请求封装，供编辑器 ghost text 调用。
public final class InlineCompletionClient {

    private static final Logger LOG = Logger.getInstance(InlineCompletionClient.class);
    private static final long TIMEOUT_MS = 1500;

    private InlineCompletionClient() {
    }

    // Blocking call — must run on a background thread. Returns "" when the
    // Core is not available, the model has no FIM endpoint, or we time out.
    // 阻塞调用，必须在后台线程执行；失败/超时一律返回空字符串（编辑器不弹错误）。
    public static String complete(Project project, String path, String language,
                                  String prefix, String suffix) {
        JsonRpcConnection conn;
        try {
            conn = AgentCoreService.getInstance(project).connection();
        } catch (Exception e) {
            LOG.debug("agent core unavailable for inline completion: " + e.getMessage());
            return "";
        }
        Map<String, Object> params = new HashMap<>();
        params.put("prefix", prefix);
        params.put("suffix", suffix);
        params.put("language", language == null ? "" : language);
        params.put("path", path == null ? "" : path);
        try {
            JsonObject response = conn.request("inline/complete", params)
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            boolean ok = response.has("ok") && response.get("ok").getAsBoolean();
            if (!ok || !response.has("result") || !response.get("result").isJsonObject()) {
                return "";
            }
            JsonObject result = response.getAsJsonObject("result");
            return result.has("text") ? result.get("text").getAsString() : "";
        } catch (Exception e) {
            LOG.debug("inline completion request failed: " + e.getMessage());
            return "";
        }
    }
}
