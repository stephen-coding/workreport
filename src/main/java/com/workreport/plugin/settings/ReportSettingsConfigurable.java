package com.workreport.plugin.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

// Settings page — Tools -> Agent Coding Assistant, with PasswordSafe encrypted API Key storage
// 设置页面 — Tools -> Agent Coding Assistant，含 PasswordSafe 加密存储 API Key（面板右下角「设置」可直接打开）
public final class ReportSettingsConfigurable implements Configurable {

    private static final String CREDENTIAL_SERVICE_NAME = "WorkReportPlugin";
    private static final String CREDENTIAL_KEY = "apiKey";

    private JPanel mainPanel;
    private JRadioButton ollamaModeRadio;
    private JRadioButton cloudModeRadio;
    private JBTextField endpointField;
    private JBTextField modelNameField;
    private JBPasswordField apiKeyField;
    private JBTextField authorFilterField;
    private JBTextArea mcpConfigField;
    private JBTextField coreExeField;
    private JBTextField coreHomeField;
    private JBTextField pythonField;
    private JPanel cloudSettingsPanel;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Agent Coding Assistant";
    }

    @Override
    public @Nullable JComponent createComponent() {
        // Key logic: API mode toggle (Ollama local / Cloud API)
        // 关键逻辑：API 模式切换（Ollama 本地 / 云端 API）
        ollamaModeRadio = new JRadioButton("Ollama（本地模式）");
        cloudModeRadio = new JRadioButton("云端 API（OpenAI 兼容）");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(ollamaModeRadio);
        modeGroup.add(cloudModeRadio);

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        modePanel.add(ollamaModeRadio);
        modePanel.add(cloudModeRadio);

        endpointField = new JBTextField(40);
        modelNameField = new JBTextField(40);
        apiKeyField = new JBPasswordField();
        authorFilterField = new JBTextField(40);
        coreExeField = new JBTextField(40);
        coreHomeField = new JBTextField(40);
        pythonField = new JBTextField(40);
        coreExeField.getEmptyText().setText("留空自动探测：插件内置 agent-core → AGENT_CORE_EXE → core/dist");
        coreHomeField.getEmptyText().setText("回退到 Python 模式时的 core 目录，例如 D:/learn/workreport-intellij-plugin/core");
        pythonField.getEmptyText().setText("回退到 Python 模式时的解释器，例如 python；留空用 AGENT_PYTHON / PATH");
        mcpConfigField = new JBTextArea(6, 60);
        mcpConfigField.setLineWrap(true);
        mcpConfigField.getEmptyText().setText(
                "[{\"name\": \"filesystem\", \"command\": \"npx\", \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\"]}]");

        // Key logic: Cloud settings area (only enabled in cloud mode)
        // 关键逻辑：云端设置区域（仅云端模式启用）
        JBLabel apiKeyHint = new JBLabel("API Key 通过系统密钥链加密存储，不会以明文保存");
        apiKeyHint.setForeground(Color.GRAY);
        apiKeyHint.setFont(apiKeyHint.getFont().deriveFont(Font.ITALIC, 11f));

        cloudSettingsPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("API Key:"), apiKeyField, 1, false)
                .addComponent(apiKeyHint)
                .getPanel();

        // Mode toggle event binding
        // 模式切换联动
        ollamaModeRadio.addActionListener(e -> onModeChanged());
        cloudModeRadio.addActionListener(e -> onModeChanged());

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("API 模式:"))
                .addComponent(modePanel)
                .addSeparator()
                .addLabeledComponent(new JBLabel("API 端点:"), endpointField, 1, false)
                .addLabeledComponent(new JBLabel("模型名称:"), modelNameField, 1, false)
                .addSeparator()
                .addComponent(new JBLabel("云端 API 设置:"))
                .addComponent(cloudSettingsPanel)
                .addSeparator()
                .addLabeledComponent(new JBLabel("Git 作者过滤:"), authorFilterField, 1, false)
                .addComponent(new JBLabel("（留空表示包含所有作者的提交记录）"))
                .addSeparator()
                .addComponent(new JBLabel("MCP 服务（JSON）:"))
                .addComponent(new JBScrollPane(mcpConfigField))
                .addComponent(new JBLabel("（支持数组或 name 映射；项目内 .agent/mcp.json 优先）"))
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

    // Key logic: Update default endpoint and cloud settings availability on mode change
    // 关键逻辑：模式切换时更新默认端点和云端设置可用性
    private void onModeChanged() {
        boolean isCloud = cloudModeRadio.isSelected();
        setCloudSettingsEnabled(isCloud);
        if (ollamaModeRadio.isSelected() && endpointField.getText().contains("/v1/")) {
            endpointField.setText("http://localhost:11434/api/chat");
        } else if (cloudModeRadio.isSelected() && endpointField.getText().contains("11434")) {
            endpointField.setText("https://api.openai.com/v1/chat/completions");
        }
    }

    private void setCloudSettingsEnabled(boolean enabled) {
        apiKeyField.setEnabled(enabled);
        for (Component comp : cloudSettingsPanel.getComponents()) {
            comp.setEnabled(enabled);
        }
    }

    @Override
    public boolean isModified() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        return !endpointField.getText().equals(state.apiEndpoint)
                || !modelNameField.getText().equals(state.modelName)
                || ollamaModeRadio.isSelected() != state.useOllamaMode
                || !authorFilterField.getText().equals(state.authorFilter)
                || !mcpConfigField.getText().equals(state.mcpServersJson)
                || !coreExeField.getText().trim().equals(state.coreExePath)
                || !coreHomeField.getText().trim().equals(state.coreHomePath)
                || !pythonField.getText().trim().equals(state.pythonPath)
                || apiKeyField.getPassword().length > 0;
    }

    @Override
    public void apply() throws ConfigurationException {
        ReportSettingsState state = ReportSettingsState.getInstance();

        // Key logic: Reset privacy confirmation when endpoint changes
        // 关键逻辑：端点变更时重置隐私确认
        state.resetPrivacyIfEndpointChanged(endpointField.getText());

        state.apiEndpoint = endpointField.getText().trim();
        state.modelName = modelNameField.getText().trim();
        state.useOllamaMode = ollamaModeRadio.isSelected();
        state.authorFilter = authorFilterField.getText().trim();
        state.mcpServersJson = mcpConfigField.getText().trim();
        state.coreExePath = coreExeField.getText().trim();
        state.coreHomePath = coreHomeField.getText().trim();
        state.pythonPath = pythonField.getText().trim();

        // Key logic: API Key encrypted via PasswordSafe (no plaintext)
        // 关键逻辑：API Key 通过 PasswordSafe 加密存储（无明文）
        char[] keyChars = apiKeyField.getPassword();
        if (keyChars.length > 0) {
            String keyValue = new String(keyChars);
            storeApiKey(keyValue);
        }
    }

    @Override
    public void reset() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        endpointField.setText(state.apiEndpoint);
        modelNameField.setText(state.modelName);
        authorFilterField.setText(state.authorFilter);
        mcpConfigField.setText(state.mcpServersJson);
        coreExeField.setText(state.coreExePath);
        coreHomeField.setText(state.coreHomePath);
        pythonField.setText(state.pythonPath);

        if (state.useOllamaMode) {
            ollamaModeRadio.setSelected(true);
        } else {
            cloudModeRadio.setSelected(true);
        }
        onModeChanged();

        // Load existing API Key from PasswordSafe for placeholder display
        // 从 PasswordSafe 读取已有 API Key 以占位显示
        String existingKey = retrieveApiKey();
        if (existingKey != null && !existingKey.isEmpty()) {
            apiKeyField.setText(existingKey);
        } else {
            apiKeyField.setText("");
        }
    }

    // ==================== PasswordSafe encrypted storage ====================
    // ==================== PasswordSafe 加密存储 ====================

    private static CredentialAttributes createCredentialAttributes() {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName(CREDENTIAL_SERVICE_NAME, CREDENTIAL_KEY)
        );
    }

    // Key logic: Store API Key to system keychain
    // 关键逻辑：存储 API Key 到系统密钥链
    private static void storeApiKey(String apiKey) {
        CredentialAttributes attributes = createCredentialAttributes();
        Credentials credentials = new Credentials(CREDENTIAL_KEY, apiKey);
        PasswordSafe.getInstance().set(attributes, credentials);
    }

    // Key logic: Retrieve API Key from system keychain
    // 关键逻辑：从系统密钥链读取 API Key
    public static String retrieveApiKey() {
        CredentialAttributes attributes = createCredentialAttributes();
        Credentials credentials = PasswordSafe.getInstance().get(attributes);
        return credentials != null ? credentials.getPasswordAsString() : null;
    }
}
