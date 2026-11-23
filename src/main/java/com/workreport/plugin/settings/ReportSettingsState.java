package com.workreport.plugin.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

// Persistent settings state — API Key is NOT stored here (encrypted via PasswordSafe)
// 持久化配置状态 — API Key 不在此存储（通过 PasswordSafe 加密存储）
@State(name = "WorkReportSettings", storages = @Storage("workReportSettings.xml"))
@Service(Service.Level.APP)
public final class ReportSettingsState implements PersistentStateComponent<ReportSettingsState> {

    // Report type: daily, weekly, quarterly, yearly
    // 报告类型：日报、周报、季报、年报
    public String reportType = "weekly";

    // Default to Ollama local mode
    // 默认使用 Ollama 本地模式
    public String apiEndpoint = "http://localhost:11434/api/chat";
    public String modelName = "qwen2.5:7b";
    public boolean useOllamaMode = true;

    // Privacy confirmation state — must confirm before first polish use
    // 隐私确认状态 — 首次使用润色必须确认
    public boolean privacyAccepted = false;

    // Git log author filter (empty = all authors)
    // Git 日志作者过滤（留空表示所有作者）
    public String authorFilter = "";

    // MCP servers as JSON (array or name map); project .agent/mcp.json wins
    // MCP 服务 JSON（数组或 name 映射）；项目内 .agent/mcp.json 优先
    public String mcpServersJson = "";

    // Optional Core overrides; blank = auto-detect (bundled -> AGENT_CORE_EXE -> core/dist)
    // 可选的 Core 路径覆盖；留空表示自动探测（插件内置 -> 环境变量 -> core/dist）
    public String coreExePath = "";
    public String coreHomePath = "";
    public String pythonPath = "";

    // Switchable model list: [{"name","provider","baseUrl","model"}] + the active one
    // 可切换的模型列表 + 当前选中的模型
    public String modelProfilesJson = "";
    public String activeProfile = "";

    // Auto mode: apply file edits without the diff confirmation prompt
    // 自动执行：写文件不再弹 diff 确认
    public boolean autoAcceptEdits = false;

    // Active agent team by name; empty means teams are off (the 3.3.0 default).
    // Shell 只负责记住用户选的那个团队；名册与成员能力全在 Core 侧。
    // 当前启用的 Agent 团队（按名称）；空表示不启用（3.3.0 的默认值）。
    public String activeTeam = "";

    // Editor inline completion (ghost text, fill-in-the-middle)
    // 编辑器行内补全（Ghost text，FIM）
    public boolean inlineCompletionEnabled = true;

    // Embedding model used by the semantic code index
    // 语义索引使用的 Embedding 模型
    public String embeddingModel = "nomic-embed-text";

    // How many LLM<->tool rounds one message may take before the agent stops.
    // Long audits (read every doc chapter, edit many files) need more than the old 15.
    // 单条消息最多跑多少轮「模型 + 工具」：复杂审计/批量改文件远超旧的 15 轮。
    public int maxIterations = 40;

    // Model context window in tokens; the Core derives its history budget from it
    // (75%, clamped to 4000~200000).
    // 模型上下文窗口（tokens）：Core 据此按 75% 计算历史裁剪预算。
    public int contextWindow = 32000;

    // Concurrency and sampling knobs pushed to the Core so both shells behave alike.
    // 并发与采样参数：随设置下发给 Core，保证两端行为一致。
    public int toolConcurrency = 4;
    public int subagentConcurrency = 3;
    public double temperature = 0.2;
    public int maxTokens = 4096;

    // Commands the agent may run without asking (one prefix per line).
    // Leave blank to use the Core's built-in list (mvn / npm / pytest / git status ...).
    // 免确认的命令前缀（每行一个）；留空使用 Core 内置白名单。
    public String commandAllowlist = "";

    public static ReportSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(ReportSettingsState.class);
    }

    @Override
    public ReportSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull ReportSettingsState state) {
        this.reportType = state.reportType != null ? state.reportType : "weekly";
        this.apiEndpoint = state.apiEndpoint;
        this.modelName = state.modelName;
        this.useOllamaMode = state.useOllamaMode;
        this.privacyAccepted = state.privacyAccepted;
        this.authorFilter = state.authorFilter;
        this.mcpServersJson = state.mcpServersJson != null ? state.mcpServersJson : "";
        this.coreExePath = state.coreExePath != null ? state.coreExePath : "";
        this.coreHomePath = state.coreHomePath != null ? state.coreHomePath : "";
        this.pythonPath = state.pythonPath != null ? state.pythonPath : "";
        this.modelProfilesJson = state.modelProfilesJson != null ? state.modelProfilesJson : "";
        this.activeProfile = state.activeProfile != null ? state.activeProfile : "";
        this.autoAcceptEdits = state.autoAcceptEdits;
        this.activeTeam = state.activeTeam != null ? state.activeTeam : "";
        this.inlineCompletionEnabled = state.inlineCompletionEnabled;
        this.embeddingModel = state.embeddingModel != null ? state.embeddingModel : "nomic-embed-text";
        // Older XML files have no value here (deserializes as 0) — keep the default.
        // 旧配置文件里没有这一项（读出来是 0），保留默认值。
        this.maxIterations = state.maxIterations > 0 ? state.maxIterations : 40;
        this.contextWindow = state.contextWindow > 0 ? state.contextWindow : 32000;
        this.toolConcurrency = state.toolConcurrency > 0 ? state.toolConcurrency : 4;
        this.subagentConcurrency = state.subagentConcurrency > 0 ? state.subagentConcurrency : 3;
        this.temperature = state.temperature > 0 ? state.temperature : 0.2;
        this.maxTokens = state.maxTokens > 0 ? state.maxTokens : 4096;
        this.commandAllowlist = state.commandAllowlist != null ? state.commandAllowlist : "";
    }

    // Reset privacy confirmation when API endpoint changes (require re-confirmation)
    // 当 API 端点变更时，重置隐私确认（要求用户重新确认）
    public void resetPrivacyIfEndpointChanged(String newEndpoint) {
        if (!apiEndpoint.equals(newEndpoint)) {
            privacyAccepted = false;
        }
    }

    // Check if API is configured (non-empty endpoint)
    // 检查 API 是否已配置（非空端点即可）
    public boolean isApiConfigured() {
        return apiEndpoint != null && !apiEndpoint.isBlank();
    }
}
