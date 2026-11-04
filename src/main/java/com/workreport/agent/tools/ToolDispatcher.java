package com.workreport.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.workreport.agent.rpc.JsonRpcConnection;

import java.util.HashMap;
import java.util.Map;

// Routes tool/invoke notifications to the concrete tool implementations and
// answers the Core with a tool/result request.
// 将 tool/invoke 通知路由到具体工具实现，并把结果以 tool/result 请求回传 Core。
public final class ToolDispatcher {

    private static final Logger LOG = Logger.getInstance(ToolDispatcher.class);

    private final FileTools fileTools;
    private final SearchTools searchTools;
    private final GitTools gitTools;
    private final EditTools editTools;

    public ToolDispatcher(Project project) {
        this.fileTools = new FileTools(project);
        this.searchTools = new SearchTools(project, fileTools);
        this.gitTools = new GitTools(project);
        this.editTools = new EditTools(project, fileTools);
    }

    public void onToolInvoke(JsonObject params, JsonRpcConnection connection) {
        String toolCallId = params.has("toolCallId") ? params.get("toolCallId").getAsString() : "";
        String toolName = params.has("toolName") ? params.get("toolName").getAsString() : "";
        String permission = params.has("permission") ? params.get("permission").getAsString() : "auto";
        JsonObject args = params.has("params") && params.get("params").isJsonObject()
                ? params.getAsJsonObject("params")
                : new JsonObject();

        // Tool execution is blocking -> run on the pooled thread, never on EDT.
        // 工具执行是阻塞操作 -> 放到后台线程池，绝不在 EDT 上执行。
        boolean needsApproval = !"auto".equals(permission);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ToolResult result;
            try {
                result = dispatch(toolName, args, needsApproval);
            } catch (Exception e) {
                LOG.warn("tool " + toolName + " failed", e);
                result = ToolResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("toolCallId", toolCallId);
            payload.put("ok", result.ok());
            payload.put("result", result.content());
            payload.put("error", result.error());
            connection.request("tool/result", payload);
        });
    }

    private ToolResult dispatch(String toolName, JsonObject args, boolean needsApproval) {
        switch (toolName) {
            case "read_file":
                return fileTools.readFile(args);
            case "list_dir":
                return fileTools.listDir(args);
            case "search_code":
                return searchTools.searchCode(args);
            case "git_log":
                return gitTools.log(args);
            case "git_diff":
                return gitTools.diff(args);
            case "write_file":
                return editTools.writeFile(args, needsApproval);
            case "edit_file":
                return editTools.editFile(args, needsApproval);
            case "delete_file":
                return editTools.deleteFile(args, needsApproval);
            case "revert_file":
                return editTools.revertFile(args);
            default:
                return ToolResult.fail("未知工具: " + toolName);
        }
    }
}
