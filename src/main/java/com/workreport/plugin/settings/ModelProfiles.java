package com.workreport.plugin.settings;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;

// Parses the switchable model list and owns per-profile API key storage.
// 解析可切换的模型列表，并负责「每个模型一把 API Key」的安全存储。
public final class ModelProfiles {

    private static final Logger LOG = Logger.getInstance(ModelProfiles.class);
    private static final Gson GSON = new Gson();
    private static final String CREDENTIAL_SERVICE_NAME = "WorkReportPlugin";
    private static final String LEGACY_API_KEY = "apiKey";
    private static final String PROFILE_PREFIX = "apiKey:";

    public static final String DEFAULT_NAME = "默认";

    private ModelProfiles() {
    }

    public static List<ModelProfile> parse(String json) {
        List<ModelProfile> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            ModelProfile[] parsed = GSON.fromJson(json, ModelProfile[].class);
            if (parsed != null) {
                for (ModelProfile profile : parsed) {
                    if (profile != null && profile.isValid()) {
                        result.add(profile);
                    }
                }
            }
        } catch (JsonSyntaxException e) {
            LOG.warn("invalid model profiles JSON, ignored", e);
        }
        return result;
    }

    public static String toJson(List<ModelProfile> profiles) {
        return GSON.toJson(profiles);
    }

    // Seed one profile from the legacy single-model fields when nothing has been configured yet.
    // 尚未配置多模型时，用旧的单模型字段生成一条默认配置。
    public static List<ModelProfile> resolve(ReportSettingsState state) {
        List<ModelProfile> profiles = parse(state.modelProfilesJson);
        if (!profiles.isEmpty()) {
            return profiles;
        }
        ModelProfile fallback = new ModelProfile(
                DEFAULT_NAME,
                state.useOllamaMode ? "ollama" : "openai_compat",
                state.apiEndpoint,
                state.modelName);
        if (fallback.isValid()) {
            profiles.add(fallback);
        }
        return profiles;
    }

    // The profile the panel should use right now.
    // 面板当前应使用的模型配置。
    public static ModelProfile active(ReportSettingsState state) {
        List<ModelProfile> profiles = resolve(state);
        if (profiles.isEmpty()) {
            return new ModelProfile(DEFAULT_NAME, "ollama", "http://localhost:11434/api/chat", "qwen2.5:7b");
        }
        for (ModelProfile profile : profiles) {
            if (profile.name.equals(state.activeProfile)) {
                return profile;
            }
        }
        return profiles.get(0);
    }

    public static String activeName(ReportSettingsState state) {
        return active(state).name;
    }

    public static String apiKey(String profileName) {
        if (profileName == null || profileName.isBlank() || DEFAULT_NAME.equals(profileName)) {
            String legacy = apiKeyValue(LEGACY_API_KEY);
            if (legacy != null && !legacy.isEmpty()) {
                return legacy;
            }
        }
        return apiKeyValue(PROFILE_PREFIX + profileName);
    }

    public static void storeApiKey(String profileName, String value) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        String key = DEFAULT_NAME.equals(profileName) ? LEGACY_API_KEY : PROFILE_PREFIX + profileName;
        PasswordSafe.getInstance().set(attributes(key), new Credentials(key, value));
    }

    private static String apiKeyValue(String key) {
        Credentials credentials = PasswordSafe.getInstance().get(attributes(key));
        return credentials != null ? credentials.getPasswordAsString() : null;
    }

    private static CredentialAttributes attributes(String key) {
        return new CredentialAttributes(
                CredentialAttributesKt.generateServiceName(CREDENTIAL_SERVICE_NAME, key));
    }
}
