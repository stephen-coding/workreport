package com.workreport.agent.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.workreport.agent.rpc.JsonRpcConnection;
import com.workreport.plugin.settings.ReportSettingsState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

// Routes tool/invoke notifications to the concrete tool implementations and
// answers the Core with a tool/result request.
// 将 tool/invoke 通知路由到具体工具实现，并把结果以 tool/result 请求回传 Core。
public final class ToolDispatcher {

    private static final Logger LOG = Logger.getInstance(ToolDispatcher.class);

    // Tools that mutate files; they take turns across parallel sessions.
    // 会改文件的工具；并行会话之间需要排队。
    private static final Set<String> WRITE_TOOLS =
            Set.of("write_file", "edit_file", "delete_file", "revert_file");

    private final ProjectWriteGate writeGate;
    private final FileTools fileTools;
    private final SearchTools searchTools;
    private final GitTools gitTools;
    private final EditTools editTools;
    private final CommandTools commandTools;
    private final DiagnosticsTools diagnosticsTools;

    public ToolDispatcher(Project project) {
        this.writeGate = ProjectWriteGate.getInstance(project);
        this.fileTools = new FileTools(project);
        this.searchTools = new SearchTools(project, fileTools);
        this.gitTools = new GitTools(project);
        this.editTools = new EditTools(project, fileTools);
        this.commandTools = new CommandTools(project, fileTools);
        this.diagnosticsTools = new DiagnosticsTools(project, fileTools);
    }

    public void onToolInvoke(JsonObject params, JsonRpcConnection connection) {
        onToolInvoke(params, connection, "会话", null);
    }

    // sessionLabel identifies the owning chat tab, notice reports queueing to that tab.
    // sessionLabel 标识发起会话（用于排队提示），notice 把排队信息写回该会话的对话区。
    public void onToolInvoke(JsonObject params, JsonRpcConnection connection,
                             String sessionLabel, Consumer<String> notice) {
        String toolCallId = params.has("toolCallId") ? params.get("toolCallId").getAsString() : "";
        String toolName = params.has("toolName") ? params.get("toolName").getAsString() : "";
        String permission = params.has("permission") ? params.get("permission").getAsString() : "auto";
        JsonObject args = params.has("params") && params.get("params").isJsonObject()
                ? params.getAsJsonObject("params")
                : new JsonObject();

        // Tool execution is blocking -> run on the pooled thread, never on EDT.
        // 工具执行是阻塞操作 -> 放到后台线程池，绝不在 EDT 上执行。
        // Auto mode (panel checkbox) skips the diff confirmation for file mutations;
        // terminal commands always follow the permission the Core computed for the
        // call, so a dangerous command can never run on auto-accept alone.
        // 面板勾选「自动执行」时文件写操作不再弹 diff 确认；
        // 终端命令始终按 Core 本次下发的权限处理，不会因自动接受而免确认。
        boolean autoAccept = ReportSettingsState.getInstance().autoAcceptEdits;
        boolean writeOp = WRITE_TOOLS.contains(toolName);
        boolean needsApproval = !"auto".equals(permission) && !(writeOp && autoAccept);
        String label = sessionLabel == null || sessionLabel.isBlank() ? "会话" : sessionLabel;
        // Idea log side of the tool round trip: the Core writes the other half, so a call that
        // never returns can be traced to the side that dropped it.
        // 工具往返的 IDE 侧日志：Core 记另一半，调用没回来时能判断断在哪一侧。
        LOG.info("tool/invoke " + toolName + " id=" + toolCallId + " permission=" + permission
                + " approval=" + needsApproval + " session=" + label);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (writeOp) {
                String waitedFor = writeGate.acquire(label);
                if (waitedFor != null && notice != null) {
                    // Same English-log style as the rest of the tool timeline.
                    // 与工具时间线其余部分一致：按英文日志风格写。
                    notice.accept("queued · waiting for session " + waitedFor + " · " + toolName);
                }
            }
            long startedAt = System.currentTimeMillis();
            ToolResult result;
            try {
                result = dispatch(toolName, args, needsApproval);
            } catch (Exception e) {
                LOG.warn("tool " + toolName + " failed", e);
                result = ToolResult.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                if (writeOp) {
                    writeGate.release();
                }
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            if (result.ok()) {
                LOG.info("tool/result " + toolName + " id=" + toolCallId + " ok in " + elapsed + "ms");
            } else {
                LOG.warn("tool/result " + toolName + " id=" + toolCallId + " failed in "
                        + elapsed + "ms: " + result.error());
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("toolCallId", toolCallId);
            payload.put("ok", result.ok());
            payload.put("result", result.content());
            payload.put("error", result.error());
            try {
                connection.request("tool/result", payload);
            } catch (RuntimeException e) {
                // A dead connection silently turned every tool call into a Core-side timeout.
                // 连接已断时结果回不去，Core 只会超时：至少把它写进日志。
                LOG.warn("cannot send tool/result for " + toolName + " (" + toolCallId + ")", e);
            }
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
            case "run_command":
                return commandTools.runCommand(args, needsApproval);
            case "diagnostics":
                return diagnosticsTools.diagnostics(args);
            default:
                LOG.warn("unknown tool requested by core: " + toolName);
                return ToolResult.fail("未知工具: " + toolName);
        }
    }
}
