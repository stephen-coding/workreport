package com.workreport.plugin.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

// Settings page — Tools -> Agent Coding Assistant.
// Holds the switchable model list (one API key per profile), MCP servers and Core paths.
// 设置页面 — Tools -> Agent Coding Assistant：
// 管理可切换的模型列表（每个模型一把 API Key）、MCP 服务与 Core 路径。
public final class ReportSettingsConfigurable implements Configurable {

    private static final String KEY_MASK = "******";

    private JPanel mainPanel;
    private JBTable profileTable;
    private DefaultTableModel profileModel;
    private JBTextField authorFilterField;
    private JBTextArea mcpConfigField;
    private JBTextField coreExeField;
    private JBTextField coreHomeField;
    private JBTextField pythonField;
    private JCheckBox inlineCompletionBox;
    private JBTextField embeddingModelField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Agent Coding Assistant";
    }

    @Override
    public @Nullable JComponent createComponent() {
        profileModel = new DefaultTableModel(
                new Object[]{"名称", "类型（ollama / openai_compat）", "端点", "模型", "API Key"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        profileTable = new JBTable(profileModel);
        profileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileTable.getEmptyText().setText("至少添加一个模型，例如：本地 Ollama / 云端 DeepSeek");
        profileTable.setRowHeight(24);
        profileTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        profileTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        profileTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        profileTable.getColumnModel().getColumn(3).setPreferredWidth(140);
        profileTable.getColumnModel().getColumn(4).setPreferredWidth(160);

        JButton addButton = new JButton("添加模型");
        JButton removeButton = new JButton("删除选中");
        addButton.addActionListener(e -> profileModel.addRow(
                new Object[]{"新模型", "ollama", "http://localhost:11434/api/chat", "", ""}));
        removeButton.addActionListener(e -> {
            int row = profileTable.getSelectedRow();
            if (row >= 0) {
                profileModel.removeRow(row);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(addButton);
        buttons.add(removeButton);

        JPanel tablePanel = new JPanel(new BorderLayout(0, 4));
        tablePanel.add(new JBScrollPane(profileTable), BorderLayout.CENTER);
        tablePanel.add(buttons, BorderLayout.SOUTH);

        authorFilterField = new JBTextField(40);
        mcpConfigField = new JBTextArea(6, 60);
        mcpConfigField.setLineWrap(true);
        mcpConfigField.getEmptyText().setText(
                "[{\"name\": \"filesystem\", \"command\": \"npx\", \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\"]}]");
        coreExeField = new JBTextField(40);
        coreHomeField = new JBTextField(40);
        pythonField = new JBTextField(40);
        inlineCompletionBox = new JCheckBox("编辑器内行内补全（Ghost text：Tab 接受 / Esc 取消）");
        embeddingModelField = new JBTextField(30);
        embeddingModelField.getEmptyText().setText("nomic-embed-text（本地 Ollama 推荐）或 text-embedding-3-small");
        coreExeField.getEmptyText().setText("留空自动探测：插件内置 agent-core → AGENT_CORE_EXE → core/dist");
        coreHomeField.getEmptyText().setText("回退到 Python 模式时的 core 目录，例如 D:/learn/workreport-intellij-plugin/core");
        pythonField.getEmptyText().setText("回退到 Python 模式时的解释器，例如 python；留空用 AGENT_PYTHON / PATH");

        JBLabel keyHint = new JBLabel(
                "API Key 按模型名加密存储在系统密钥链；表格里显示 " + KEY_MASK + " 表示已保存，留空或保持原样即不修改");
        keyHint.setForeground(Color.GRAY);
        keyHint.setFont(keyHint.getFont().deriveFont(Font.ITALIC, 11f));

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("模型列表（面板下拉框可随时切换）:"))
                .addComponent(tablePanel)
                .addComponent(keyHint)
                .addComponent(new JBLabel("（类型 ollama = 本地 Ollama；openai_compat = 任意 OpenAI 兼容云端服务）"))
                .addSeparator()
                .addLabeledComponent(new JBLabel("Git 作者过滤:"), authorFilterField, 1, false)
                .addComponent(new JBLabel("（留空表示包含所有作者的提交记录）"))
                .addSeparator()
                .addComponent(new JBLabel("MCP 服务（JSON）:"))
                .addComponent(new JBScrollPane(mcpConfigField))
                .addComponent(new JBLabel("（支持数组或 name 映射；项目内 .agent/mcp.json 优先）"))
                .addSeparator()
                .addComponent(new JBLabel("编辑器增强:"))
                .addComponent(inlineCompletionBox)
                .addLabeledComponent(new JBLabel("Embedding 模型:"), embeddingModelField, 1, false)
                .addComponent(new JBLabel("（语义索引使用当前端点的 embedding 接口；切换模型后首次"
                        + "检索会提示重建索引）"))
                .addSeparator()
                .addComponent(new JBLabel("Agent Core 运行环境（一般不需要改）:"))
                .addLabeledComponent(new JBLabel("Core 可执行文件:"), coreExeField, 1, false)
                .addLabeledComponent(new JBLabel("Core 目录:"), coreHomeField, 1, false)
                .addLabeledComponent(new JBLabel("Python 解释器:"), pythonField, 1, false)
                .addComponent(new JBLabel("（插件 zip 已内置 Core，只有开发调试才需要填这三项）"))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        return !collectProfiles().equals(ModelProfiles.parse(state.modelProfilesJson))
                || hasNewApiKey()
                || !authorFilterField.getText().trim().equals(state.authorFilter)
                || !mcpConfigField.getText().trim().equals(state.mcpServersJson)
                || !coreExeField.getText().trim().equals(state.coreExePath)
                || !coreHomeField.getText().trim().equals(state.coreHomePath)
                || !pythonField.getText().trim().equals(state.pythonPath)
                || inlineCompletionBox.isSelected() != state.inlineCompletionEnabled
                || !embeddingModelField.getText().trim().equals(state.embeddingModel);
    }

    @Override
    public void apply() throws ConfigurationException {
        ReportSettingsState state = ReportSettingsState.getInstance();

        List<ModelProfile> profiles = collectProfiles();
        if (profiles.isEmpty()) {
            throw new ConfigurationException("至少需要配置一个模型（名称、类型、端点、模型名都不能为空）");
        }
        for (int row = 0; row < profileModel.getRowCount(); row++) {
            String key = cell(row, 4);
            if (!key.isEmpty() && !KEY_MASK.equals(key)) {
                ModelProfiles.storeApiKey(cell(row, 0), key);
            }
        }

        state.modelProfilesJson = ModelProfiles.toJson(profiles);
        // Keep the active profile pointing at an existing entry.
        String active = state.activeProfile;
        boolean stillThere = false;
        for (ModelProfile profile : profiles) {
            if (profile.name.equals(active)) {
                stillThere = true;
                break;
            }
        }
        if (!stillThere) {
            state.activeProfile = profiles.get(0).name;
        }

        // Mirror the active profile into the legacy single-model fields.
        ModelProfile current = ModelProfiles.active(state);
        state.apiEndpoint = current.baseUrl;
        state.modelName = current.model;
        state.useOllamaMode = "ollama".equals(current.provider);
        state.resetPrivacyIfEndpointChanged(current.baseUrl);

        state.authorFilter = authorFilterField.getText().trim();
        state.mcpServersJson = mcpConfigField.getText().trim();
        state.coreExePath = coreExeField.getText().trim();
        state.coreHomePath = coreHomeField.getText().trim();
        state.pythonPath = pythonField.getText().trim();
        state.inlineCompletionEnabled = inlineCompletionBox.isSelected();
        state.embeddingModel = embeddingModelField.getText().trim().isEmpty()
                ? "nomic-embed-text" : embeddingModelField.getText().trim();
    }

    @Override
    public void reset() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        profileModel.setRowCount(0);
        for (ModelProfile profile : ModelProfiles.resolve(state)) {
            String savedKey = ModelProfiles.apiKey(profile.name);
            profileModel.addRow(new Object[]{
                    profile.name,
                    profile.provider,
                    profile.baseUrl,
                    profile.model,
                    savedKey != null && !savedKey.isEmpty() ? KEY_MASK : ""});
        }
        authorFilterField.setText(state.authorFilter);
        mcpConfigField.setText(state.mcpServersJson);
        coreExeField.setText(state.coreExePath);
        coreHomeField.setText(state.coreHomePath);
        pythonField.setText(state.pythonPath);
        inlineCompletionBox.setSelected(state.inlineCompletionEnabled);
        embeddingModelField.setText(state.embeddingModel);
    }

    private List<ModelProfile> collectProfiles() {
        List<ModelProfile> profiles = new ArrayList<>();
        for (int row = 0; row < profileModel.getRowCount(); row++) {
            ModelProfile profile = new ModelProfile(
                    cell(row, 0), normalizeProvider(cell(row, 1)), cell(row, 2), cell(row, 3));
            if (profile.isValid()) {
                profiles.add(profile);
            }
        }
        return profiles;
    }

    private boolean hasNewApiKey() {
        for (int row = 0; row < profileModel.getRowCount(); row++) {
            String key = cell(row, 4);
            if (!key.isEmpty() && !KEY_MASK.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private String cell(int row, int column) {
        Object value = profileModel.getValueAt(row, column);
        return value == null ? "" : value.toString().trim();
    }

    private static String normalizeProvider(String provider) {
        String lower = provider.toLowerCase();
        return lower.contains("openai") ? "openai_compat" : "ollama";
    }

    // Backward-compatible accessor used by the chat panel for the legacy single-model path.
    // 兼容旧调用：读取当前模型的 API Key。
    public static String retrieveApiKey() {
        return ModelProfiles.apiKey(ModelProfiles.activeName(ReportSettingsState.getInstance()));
    }
}
