package com.workreport.plugin.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.workreport.agent.AgentBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Settings page — Tools -> Agent Coding Assistant.
// A stack of rounded cards instead of one long FormBuilder column: models on the left with their
// editor on the right, then agent behaviour, editor integration, Git, MCP, allowlist and Core paths.
// 设置页 — Tools -> Agent Coding Assistant。
// 一叠圆角卡片取代原来一列到底的 FormBuilder：左侧模型列表 + 右侧编辑表单，随后是
// Agent 行为、编辑器集成、Git、MCP、命令白名单与 Core 路径。
public final class ReportSettingsConfigurable implements Configurable {

    // Shown nowhere as a value: the field stays empty and its placeholder says a key is stored.
    // 不再作为字段值出现：字段留空，由占位文字说明“已保存”。
    private static final String KEY_MASK = "******";

    private static final String PROVIDER_OLLAMA = "ollama";
    private static final String PROVIDER_OPENAI = "openai_compat";

    private JPanel mainPanel;
    private JPanel column;

    private DefaultListModel<ModelProfile> profileModel;
    private JBList<ModelProfile> profileList;
    private JBTextField nameField;
    private JComboBox<String> providerBox;
    private JBTextField endpointField;
    private JBTextField modelField;
    private JBPasswordField keyField;
    private JComboBox<String> activeBox;

    private JBTextField authorFilterField;
    private JBTextArea mcpConfigField;
    private JBLabel mcpStatus;
    private JBTextField coreExeField;
    private JBTextField coreHomeField;
    private JBTextField pythonField;
    private JCheckBox inlineCompletionBox;
    private JBTextField embeddingModelField;
    private JBTextField maxIterationsField;
    private JBTextField contextWindowField;
    private JBTextField toolConcurrencyField;
    private JBTextField subagentConcurrencyField;
    private JBTextField temperatureField;
    private JBTextField maxTokensField;
    private JBTextArea commandAllowlistField;

    // Profiles whose API key is already in the credential store (drives the field placeholder).
    // 已经在凭据库里有 Key 的模型（决定字段占位文字）。
    private final Set<String> storedKeys = new HashSet<>();
    // Keys typed in this session, keyed by the profile name they belong to.
    // 本次会话里新输入的 Key，按所属模型名索引。
    private final Map<String, String> typedKeys = new HashMap<>();
    // Models renamed in this session: new name -> old name, so a stored key follows the rename
    // instead of being silently orphaned.
    // 本次会话改过名的模型：新名 -> 旧名，保存时把已存 Key 一并搬过去，不会静默丢掉。
    private final Map<String, String> renamedKeys = new HashMap<>();
    // Names currently shown in the "active model" combo; lets a keystroke that does not change the
    // roster skip the rebuild, which would otherwise reset the user's selection mid-typing.
    // 「当前模型」下拉当前展示的名称；名册未变时跳过重建，避免边改名边把选择重置。
    private List<String> activeNamesCache = List.of();
    // Guards the editor <-> model sync while fields are filled programmatically.
    // 程序化填充字段时，屏蔽编辑器与模型之间的双向同步。
    private boolean loading;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return AgentBundle.message("settings.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        profileModel = new DefaultListModel<>();
        buildModelFields();
        buildOtherFields();

        column = new JPanel(new GridBagLayout());
        column.setOpaque(false);
        addCard(modelsCard());
        addCard(agentCard());
        addCard(editorCard());
        addCard(gitCard());
        addCard(mcpCard());
        addCard(allowlistCard());
        addCard(coreCard());

        JPanel holder = new CardColumn();
        holder.setOpaque(false);
        holder.setBorder(new EmptyBorder(JBUI.scale(12), JBUI.scale(12), JBUI.scale(12), JBUI.scale(12)));
        holder.add(column, BorderLayout.NORTH);

        JBScrollPane scroll = new JBScrollPane(holder);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(24));

        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setOpaque(false);
        mainPanel.add(scroll, BorderLayout.CENTER);

        reset();
        return mainPanel;
    }

    // ------------------------------------------------------------------ cards

    private void addCard(SettingsCard card) {
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = column.getComponentCount();
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, JBUI.scale(10), 0);
        column.add(card, constraints);
    }

    private SettingsCard modelsCard() {
        SettingsCard card = new SettingsCard(text("settings.models.title"), text("settings.models.caption"));

        JPanel split = new JPanel(new BorderLayout(JBUI.scale(14), 0));
        split.setOpaque(false);
        split.add(profileListPanel(), BorderLayout.WEST);
        split.add(profileFormPanel(), BorderLayout.CENTER);
        card.block(split);

        card.note(hint(text("settings.models.keyHint",
                KEY_MASK, text("settings.models.keyStoredHint"))));
        return card;
    }

    private JPanel profileListPanel() {
        JPanel wrap = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        wrap.setOpaque(false);
        wrap.setPreferredSize(new Dimension(JBUI.scale(250), JBUI.scale(206)));

        profileList = new JBList<>(profileModel);
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setCellRenderer(new ProfileRenderer());
        profileList.getEmptyText().setText(text("settings.emptyProfiles"));
        profileList.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                loadEditor();
            }
        });

        JBScrollPane scroll = new JBScrollPane(profileList);
        scroll.setBorder(JBUI.Borders.customLine(
                JBColor.namedColor("Borders.color", JBColor.border()), 1));
        wrap.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        buttons.setOpaque(false);
        JButton add = new JButton(text("settings.addModel"));
        add.addActionListener(event -> addProfile());
        JButton remove = new JButton(text("settings.removeSelected"));
        remove.addActionListener(event -> removeProfile());
        buttons.add(add);
        buttons.add(remove);
        wrap.add(buttons, BorderLayout.SOUTH);
        return wrap;
    }

    private JPanel profileFormPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        int row = 0;
        row = formRow(form, row, text("settings.models.name"), nameField);
        row = formRow(form, row, text("settings.models.provider"), natural(providerBox));
        row = formRow(form, row, text("settings.models.endpoint"), endpointField);
        row = formRow(form, row, text("settings.models.model"), modelField);
        row = formRow(form, row, text("settings.models.key"), keyField);
        formRow(form, row, text("settings.models.active"), natural(activeBox));
        return form;
    }

    // Combo boxes look wrong when stretched across a wide card, so they keep their preferred width.
    // 下拉框拉满整张卡片会很难看，这里让它保持自身宽度。
    private static JComponent natural(JComponent control) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(control, BorderLayout.WEST);
        return wrap;
    }

    private int formRow(JPanel form, int row, String label, JComponent field) {
        JLabel name = new JLabel(label);
        name.setPreferredSize(new Dimension(JBUI.scale(84), JBUI.scale(24)));
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.WEST;
        left.insets = new Insets(0, 0, JBUI.scale(6), JBUI.scale(10));
        form.add(name, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(0, 0, JBUI.scale(6), 0);
        form.add(field, right);
        return row + 1;
    }

    private SettingsCard agentCard() {
        SettingsCard card = new SettingsCard(text("settings.agent.title"), text("settings.agent.caption"));
        card.pair(text("settings.maxIterations"), maxIterationsField,
                text("settings.contextWindow"), contextWindowField);
        card.pair(text("settings.toolConcurrency"), toolConcurrencyField,
                text("settings.subagentConcurrency"), subagentConcurrencyField);
        card.pair(text("settings.temperature"), temperatureField,
                text("settings.maxTokens"), maxTokensField);
        return card;
    }

    private SettingsCard editorCard() {
        SettingsCard card = new SettingsCard(text("settings.editor.title"), text("settings.editor.caption"));
        card.block(inlineCompletionBox);
        card.row(text("settings.embedding"), embeddingModelField);
        return card;
    }

    private SettingsCard gitCard() {
        SettingsCard card = new SettingsCard(text("settings.git.title"), text("settings.git.caption"));
        card.row(text("settings.git.author"), authorFilterField);
        return card;
    }

    private SettingsCard mcpCard() {
        SettingsCard card = new SettingsCard(text("settings.mcp.title"), text("settings.mcp.caption"));
        card.block(scrollFor(mcpConfigField, JBUI.scale(110)));
        card.note(mcpStatus);
        return card;
    }

    private SettingsCard allowlistCard() {
        SettingsCard card = new SettingsCard(text("settings.allowlist.title"),
                text("settings.allowlist.caption"));
        card.block(scrollFor(commandAllowlistField, JBUI.scale(92)));
        return card;
    }

    private SettingsCard coreCard() {
        SettingsCard card = new SettingsCard(text("settings.core.title"), text("settings.core.caption"));
        card.row(text("settings.coreExe"), coreExeField);
        card.row(text("settings.coreHome"), coreHomeField);
        card.row(text("settings.python"), pythonField);
        return card;
    }

    // ------------------------------------------------------------------ field construction

    private void buildModelFields() {
        nameField = new JBTextField(18);
        nameField.getEmptyText().setText(text("settings.models.nameHint"));
        nameField.setToolTipText(text("settings.models.nameTip"));

        providerBox = new JComboBox<>();
        providerBox.addItem(text("settings.models.provider.ollama"));
        providerBox.addItem(text("settings.models.provider.openai"));

        endpointField = new JBTextField(24);
        endpointField.getEmptyText().setText("http://localhost:11434/api/chat");
        endpointField.setToolTipText(text("settings.models.endpointTip"));

        modelField = new JBTextField(18);
        modelField.getEmptyText().setText("qwen2.5:7b / deepseek-chat");
        modelField.setToolTipText(text("settings.models.modelTip"));

        keyField = new JBPasswordField();
        keyField.setToolTipText(text("settings.models.keyTip"));

        activeBox = new JComboBox<>();

        DocumentListener editorSync = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                commitEditor();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                commitEditor();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                commitEditor();
            }
        };
        nameField.getDocument().addDocumentListener(editorSync);
        endpointField.getDocument().addDocumentListener(editorSync);
        modelField.getDocument().addDocumentListener(editorSync);
        keyField.getDocument().addDocumentListener(editorSync);

        ActionListener choiceSync = event -> commitEditor();
        providerBox.addActionListener(choiceSync);
    }

    // Tracks the viewport width so the card column fills the settings window instead of sitting at
    // its minimum width and leaving a wide empty strip on the right.
    // 跟随视口宽度：卡片列铺满设置窗口，而不是停在最小宽度、右边留一条空白。
    private static final class CardColumn extends JPanel implements Scrollable {

        CardColumn() {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(JBUI.scale(16), visibleRect.height - JBUI.scale(16));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private void buildOtherFields() {
        authorFilterField = new JBTextField(24);
        authorFilterField.getEmptyText().setText(text("settings.git.authorHint"));

        mcpConfigField = new JBTextArea(5, 60);
        mcpConfigField.setLineWrap(true);
        mcpConfigField.getEmptyText().setText(
                "[{\"name\": \"filesystem\", \"command\": \"npx\", \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\"]}]");
        mcpStatus = new JBLabel("");
        mcpStatus.setFont(mcpStatus.getFont().deriveFont(mcpStatus.getFont().getSize2D() - 1f));
        mcpConfigField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                validateMcp();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                validateMcp();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                validateMcp();
            }
        });

        coreExeField = new JBTextField(24);
        coreExeField.getEmptyText().setText(text("settings.coreExeHint"));
        coreHomeField = new JBTextField(24);
        coreHomeField.getEmptyText().setText(text("settings.coreHomeHint"));
        pythonField = new JBTextField(24);
        pythonField.getEmptyText().setText(text("settings.pythonHint"));

        inlineCompletionBox = new JCheckBox(text("settings.inlineCompletion"));
        inlineCompletionBox.setOpaque(false);

        embeddingModelField = new JBTextField(24);
        embeddingModelField.getEmptyText().setText("nomic-embed-text / text-embedding-3-small");
        embeddingModelField.setToolTipText(text("settings.embeddingTip"));

        maxIterationsField = numberField("40", text("settings.maxIterationsTip"));
        contextWindowField = numberField("32000", text("settings.contextWindowTip"));
        toolConcurrencyField = numberField("4", text("settings.toolConcurrencyTip"));
        subagentConcurrencyField = numberField("3", text("settings.subagentConcurrencyTip"));
        temperatureField = numberField("0.2", text("settings.temperatureTip"));
        maxTokensField = numberField("4096", text("settings.maxTokensTip"));

        commandAllowlistField = new JBTextArea(4, 60);
        commandAllowlistField.setLineWrap(true);
        commandAllowlistField.getEmptyText().setText(text("settings.allowlistHint"));
        commandAllowlistField.setToolTipText(text("settings.allowlistTip"));
    }

    private static JBTextField numberField(String placeholder, String tip) {
        JBTextField field = new JBTextField(8);
        field.getEmptyText().setText(placeholder);
        field.setToolTipText(tip);
        return field;
    }

    private static JBScrollPane scrollFor(JTextArea area, int height) {
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setPreferredSize(new Dimension(JBUI.scale(320), height));
        return scroll;
    }

    // ------------------------------------------------------------------ editor <-> model sync

    // Push what the fields currently show into the selected list entry.
    // 把字段当前内容写回选中的列表项。
    private void commitEditor() {
        if (loading) {
            return;
        }
        int index = profileList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        ModelProfile profile = profileModel.get(index);
        String previousName = profile.name;
        String active = selectedActive();
        profile.name = nameField.getText().trim();
        profile.provider = providerBox.getSelectedIndex() == 1 ? PROVIDER_OPENAI : PROVIDER_OLLAMA;
        profile.baseUrl = endpointField.getText().trim();
        profile.model = modelField.getText().trim();

        String key = new String(keyField.getPassword()).trim();
        if (!key.isEmpty() && !KEY_MASK.equals(key)) {
            typedKeys.put(profile.name, key);
        }
        profileModel.set(index, profile);
        refreshActiveBox();
        // Renaming the active model must keep it active; otherwise the rebuilt combo falls back to
        // the first entry and Apply would silently switch models.
        // 改名时「当前模型」要跟着走：否则重建下拉后会退回第一项，保存时静默换了模型。
        if (active.equals(previousName) && !profile.name.isEmpty()) {
            activeBox.setSelectedItem(profile.name);
        }
        if (!profile.name.isEmpty()) {
            if (profile.name.equals(previousName)) {
                renamedKeys.remove(profile.name);
            } else {
                renamedKeys.put(profile.name, previousName);
            }
        }
    }

    // Fill the fields from the selected entry.
    // 按选中的条目填充字段。
    private void loadEditor() {
        int index = profileList.getSelectedIndex();
        boolean has = index >= 0;
        nameField.setEnabled(has);
        providerBox.setEnabled(has);
        endpointField.setEnabled(has);
        modelField.setEnabled(has);
        keyField.setEnabled(has);

        String keyOwner = null;
        ModelProfile profile = null;
        if (has) {
            profile = profileModel.get(index);
            keyOwner = profile.name;
        }

        // Show the "N models configured" state even before the first selection.
        // 未选中时字段清空并禁用，避免误以为可以把内容写到空模型上。
        boolean before = loading;
        loading = true;
        if (profile == null) {
            nameField.setText("");
            providerBox.setSelectedIndex(0);
            endpointField.setText("");
            modelField.setText("");
            keyField.setText("");
            keyField.getEmptyText().setText(text("settings.models.keyEmpty"));
        } else {
            nameField.setText(profile.name);
            providerBox.setSelectedIndex(PROVIDER_OPENAI.equals(profile.provider) ? 1 : 0);
            endpointField.setText(profile.baseUrl);
            modelField.setText(profile.model);
            keyField.setText("");
            keyField.getEmptyText().setText(
                    storedKeys.contains(keyOwner) || typedKeys.containsKey(keyOwner)
                            || renamedKeys.containsKey(keyOwner)
                            ? text("settings.models.keyStoredHint")
                            : text("settings.models.keyEmpty"));
        }
        loading = before;
    }

    private void addProfile() {
        commitEditor();
        ModelProfile profile = new ModelProfile(text("settings.models.newName"), PROVIDER_OLLAMA,
                "http://localhost:11434/api/chat", "");
        String name = profile.name;
        int suffix = 2;
        while (nameInUse(name, null)) {
            name = text("settings.models.newName") + " " + suffix;
            suffix++;
        }
        profile.name = name;
        profileModel.addElement(profile);
        profileList.setSelectedIndex(profileModel.size() - 1);
        profileList.ensureIndexIsVisible(profileModel.size() - 1);
        refreshActiveBox();
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private void removeProfile() {
        int index = profileList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        ModelProfile removed = profileModel.get(index);
        typedKeys.remove(removed.name);
        profileModel.remove(index);
        refreshActiveBox();
        if (!profileModel.isEmpty()) {
            profileList.setSelectedIndex(Math.min(index, profileModel.size() - 1));
        } else {
            loadEditor();
        }
    }

    private boolean nameInUse(String name, @Nullable ModelProfile ignore) {
        for (int i = 0; i < profileModel.size(); i++) {
            ModelProfile profile = profileModel.get(i);
            if (profile != ignore && profile.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    // Rebuild the "current model" combo from the roster, keeping the user's pick when it survives.
    // 用名册重建「当前模型」下拉；用户的选择仍然存在时保持不动。
    private void refreshActiveBox() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < profileModel.size(); i++) {
            String name = profileModel.get(i).name;
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        activeBox.setEnabled(!names.isEmpty());
        if (names.equals(activeNamesCache)) {
            return;
        }
        activeNamesCache = names;
        Object previous = activeBox.getSelectedItem();
        boolean before = loading;
        loading = true;
        activeBox.removeAllItems();
        for (String name : names) {
            activeBox.addItem(name);
        }
        if (previous instanceof String pick && names.contains(pick)) {
            activeBox.setSelectedItem(pick);
        }
        loading = before;
    }

    private void validateMcp() {
        String json = mcpConfigField.getText().trim();
        if (json.isEmpty()) {
            mcpStatus.setText(text("settings.mcp.empty"));
            mcpStatus.setForeground(UIUtil.getContextHelpForeground());
            return;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonArray() && !element.isJsonObject()) {
                mcpStatus.setText(text("settings.mcp.invalidShape"));
                mcpStatus.setForeground(UiColors.error());
                return;
            }
            int count = element.isJsonArray()
                    ? element.getAsJsonArray().size()
                    : element.getAsJsonObject().size();
            mcpStatus.setText(text("settings.mcp.valid", count));
            mcpStatus.setForeground(UiColors.ok());
        } catch (RuntimeException exception) {
            mcpStatus.setText(text("settings.mcp.invalid", shortMessage(exception)));
            mcpStatus.setForeground(UiColors.error());
        }
    }

    private static String shortMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }

    // ------------------------------------------------------------------ Configurable

    @Override
    public boolean isModified() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        return !collectProfiles().equals(ModelProfiles.parse(state.modelProfilesJson))
                || !typedKeys.isEmpty()
                || !selectedActive().equals(state.activeProfile)
                || !authorFilterField.getText().trim().equals(state.authorFilter)
                || !mcpConfigField.getText().trim().equals(state.mcpServersJson)
                || !coreExeField.getText().trim().equals(state.coreExePath)
                || !coreHomeField.getText().trim().equals(state.coreHomePath)
                || !pythonField.getText().trim().equals(state.pythonPath)
                || inlineCompletionBox.isSelected() != state.inlineCompletionEnabled
                || !embeddingModelField.getText().trim().equals(state.embeddingModel)
                || !maxIterationsField.getText().trim().equals(String.valueOf(state.maxIterations))
                || !contextWindowField.getText().trim().equals(String.valueOf(state.contextWindow))
                || !toolConcurrencyField.getText().trim().equals(String.valueOf(state.toolConcurrency))
                || !subagentConcurrencyField.getText().trim().equals(String.valueOf(state.subagentConcurrency))
                || !temperatureField.getText().trim().equals(String.valueOf(state.temperature))
                || !maxTokensField.getText().trim().equals(String.valueOf(state.maxTokens))
                || !commandAllowlistField.getText().trim().equals(state.commandAllowlist);
    }

    @Override
    public void apply() throws ConfigurationException {
        commitEditor();
        ReportSettingsState state = ReportSettingsState.getInstance();

        List<ModelProfile> profiles = collectProfiles();
        if (profiles.isEmpty()) {
            throw new ConfigurationException(text("settings.error.noModel"));
        }
        Set<String> names = new HashSet<>();
        for (ModelProfile profile : profiles) {
            if (profile.name.isBlank()) {
                throw new ConfigurationException(text("settings.error.blankName"));
            }
            if (!names.add(profile.name)) {
                throw new ConfigurationException(text("settings.error.duplicateName", profile.name));
            }
            if (profile.baseUrl.isBlank() || profile.model.isBlank()) {
                throw new ConfigurationException(text("settings.error.incomplete", profile.name));
            }
        }

        for (Map.Entry<String, String> entry : renamedKeys.entrySet()) {
            String newName = entry.getKey();
            if (typedKeys.containsKey(newName)) {
                continue;
            }
            String stored = ModelProfiles.apiKey(entry.getValue());
            if (stored != null && !stored.isEmpty()) {
                ModelProfiles.storeApiKey(newName, stored);
            }
        }
        renamedKeys.clear();
        for (Map.Entry<String, String> entry : typedKeys.entrySet()) {
            ModelProfiles.storeApiKey(entry.getKey(), entry.getValue());
        }
        typedKeys.clear();

        state.modelProfilesJson = ModelProfiles.toJson(profiles);
        String active = selectedActive();
        state.activeProfile = names.contains(active) ? active : profiles.get(0).name;

        // Mirror the active profile into the legacy single-model fields.
        ModelProfile current = ModelProfiles.active(state);
        state.apiEndpoint = current.baseUrl;
        state.modelName = current.model;
        state.useOllamaMode = ModelEndpoints.isOllama(current.provider, current.baseUrl);
        state.resetPrivacyIfEndpointChanged(current.baseUrl);

        state.authorFilter = authorFilterField.getText().trim();
        state.mcpServersJson = mcpConfigField.getText().trim();
        state.coreExePath = coreExeField.getText().trim();
        state.coreHomePath = coreHomeField.getText().trim();
        state.pythonPath = pythonField.getText().trim();
        state.inlineCompletionEnabled = inlineCompletionBox.isSelected();
        state.maxIterations = parseMaxIterations();
        state.contextWindow = parseIntField(contextWindowField, 32000, 4000, 200000, text("settings.contextWindow"));
        state.toolConcurrency = parseIntField(toolConcurrencyField, 4, 1, 16, text("settings.toolConcurrency"));
        state.subagentConcurrency = parseIntField(subagentConcurrencyField, 3, 1, 8,
                text("settings.subagentConcurrency"));
        state.temperature = parseTemperature();
        state.maxTokens = parseIntField(maxTokensField, 4096, 256, 32768, text("settings.maxTokens"));
        state.commandAllowlist = commandAllowlistField.getText().trim();
        state.embeddingModel = embeddingModelField.getText().trim().isEmpty()
                ? "nomic-embed-text" : embeddingModelField.getText().trim();
    }

    @Override
    public void reset() {
        ReportSettingsState state = ReportSettingsState.getInstance();
        String active = ModelProfiles.activeName(state);

        loading = true;
        typedKeys.clear();
        renamedKeys.clear();
        storedKeys.clear();
        profileModel.clear();
        for (ModelProfile profile : ModelProfiles.resolve(state)) {
            ModelProfile copy = new ModelProfile(profile.name, profile.provider, profile.baseUrl, profile.model);
            profileModel.addElement(copy);
            String saved = ModelProfiles.apiKey(profile.name);
            if (saved != null && !saved.isEmpty()) {
                storedKeys.add(profile.name);
            }
        }
        refreshActiveBox();
        activeBox.setSelectedItem(active);
        loading = false;

        if (!profileModel.isEmpty()) {
            int index = 0;
            for (int i = 0; i < profileModel.size(); i++) {
                if (profileModel.get(i).name.equals(active)) {
                    index = i;
                    break;
                }
            }
            profileList.setSelectedIndex(index);
        } else {
            loadEditor();
        }

        authorFilterField.setText(state.authorFilter);
        mcpConfigField.setText(state.mcpServersJson);
        validateMcp();
        coreExeField.setText(state.coreExePath);
        coreHomeField.setText(state.coreHomePath);
        pythonField.setText(state.pythonPath);
        inlineCompletionBox.setSelected(state.inlineCompletionEnabled);
        embeddingModelField.setText(state.embeddingModel);
        maxIterationsField.setText(String.valueOf(state.maxIterations));
        contextWindowField.setText(String.valueOf(state.contextWindow));
        toolConcurrencyField.setText(String.valueOf(state.toolConcurrency));
        subagentConcurrencyField.setText(String.valueOf(state.subagentConcurrency));
        temperatureField.setText(String.valueOf(state.temperature));
        maxTokensField.setText(String.valueOf(state.maxTokens));
        commandAllowlistField.setText(state.commandAllowlist);
    }

    // ------------------------------------------------------------------ validation helpers

    // A typo here silently truncates every task, so out-of-range values fail loudly.
    // 轮次上限写错会默默截断任务，因此越界直接报错而不是静默纠正。
    private int parseMaxIterations() throws ConfigurationException {
        String text = maxIterationsField.getText().trim();
        if (text.isEmpty()) {
            return 40;
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(text("settings.error.iterationsNumber"));
        }
        if (value < 5 || value > 300) {
            throw new ConfigurationException(text("settings.error.iterationsRange", value));
        }
        return value;
    }

    private int parseIntField(JBTextField field, int fallback, int min, int max, String label)
            throws ConfigurationException {
        String raw = field.getText().trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(text("settings.error.number", label, min, max));
        }
        if (value < min || value > max) {
            throw new ConfigurationException(text("settings.error.range", label, min, max, value));
        }
        return value;
    }

    private double parseTemperature() throws ConfigurationException {
        String raw = temperatureField.getText().trim();
        if (raw.isEmpty()) {
            return 0.2;
        }
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            throw new ConfigurationException(text("settings.error.temperatureNumber"));
        }
        if (value < 0.1 || value > 1.5) {
            throw new ConfigurationException(text("settings.error.temperatureRange", value));
        }
        return Math.round(value * 100) / 100.0;
    }

    // ------------------------------------------------------------------ data helpers

    private List<ModelProfile> collectProfiles() {
        List<ModelProfile> profiles = new ArrayList<>();
        for (int i = 0; i < profileModel.size(); i++) {
            ModelProfile profile = profileModel.get(i);
            profiles.add(new ModelProfile(profile.name, normalizeProvider(profile.provider),
                    profile.baseUrl, profile.model));
        }
        return profiles;
    }

    private String selectedActive() {
        Object selected = activeBox.getSelectedItem();
        if (selected instanceof String name) {
            return name;
        }
        return profileModel.isEmpty() ? "" : profileModel.get(0).name;
    }

    private static String normalizeProvider(String provider) {
        return provider != null && provider.contains("openai") ? PROVIDER_OPENAI : PROVIDER_OLLAMA;
    }

    private static String text(String key) {
        return AgentBundle.message(key);
    }

    private static String text(String key, Object... args) {
        return args.length == 0 ? AgentBundle.message(key) : AgentBundle.message(key, args);
    }

    private static JBLabel hint(String value) {
        JBLabel label = new JBLabel(value);
        label.setFont(label.getFont().deriveFont(Font.ITALIC, label.getFont().getSize2D() - 1f));
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setBorder(new EmptyBorder(JBUI.scale(2), 0, 0, 0));
        return label;
    }

    // Tint helpers so the MCP validation line matches the theme instead of hard-coding colours.
    // 校验提示的配色跟随主题，不写死颜色。
    private static final class UiColors {

        private UiColors() {
        }

        static Color ok() {
            return JBColor.namedColor("Notifications.successForeground",
                    new JBColor(new Color(0x2E7D32), new Color(0x73B27A)));
        }

        static Color error() {
            return JBColor.namedColor("Notifications.errorForeground",
                    new JBColor(new Color(0xC0392B), new Color(0xF08A8A)));
        }
    }

    // Two-line row: name on top, endpoint/model underneath in a smaller muted font.
    // 两行渲染：上面是名称，下面是端点与模型的小字。
    private static final class ProfileRenderer extends JPanel implements ListCellRenderer<ModelProfile> {

        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        ProfileRenderer() {
            super(new BorderLayout(0, JBUI.scale(1)));
            setOpaque(true);
            setBorder(new EmptyBorder(JBUI.scale(6), JBUI.scale(8), JBUI.scale(6), JBUI.scale(8)));
            add(name, BorderLayout.NORTH);
            add(detail, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ModelProfile> list, ModelProfile value,
                                                      int index, boolean selected, boolean focused) {
            Font base = list.getFont();
            name.setText(value.name.isEmpty() ? text("settings.models.unnamed") : value.name);
            name.setFont(base.deriveFont(Font.BOLD));
            detail.setText(value.model.isEmpty() ? value.baseUrl : value.model + " · " + compact(value.baseUrl));
            detail.setFont(base.deriveFont(base.getSize2D() - 1f));

            Color foreground = selected ? UIUtil.getListSelectionForeground(true) : UIUtil.getLabelForeground();
            name.setForeground(foreground);
            detail.setForeground(selected ? foreground : UIUtil.getContextHelpForeground());
            setBackground(selected ? UIUtil.getListSelectionBackground(true) : list.getBackground());
            return this;
        }

        private static String compact(String url) {
            if (url == null) {
                return "";
            }
            String stripped = url.replaceFirst("^https?://", "");
            return stripped.length() <= 32 ? stripped : stripped.substring(0, 29) + "...";
        }
    }

    // Backward-compatible accessor used by the chat panel for the legacy single-model path.
    // 兼容旧调用：读取当前模型的 API Key。
    public static String retrieveApiKey() {
        return ModelProfiles.apiKey(ModelProfiles.activeName(ReportSettingsState.getInstance()));
    }
}