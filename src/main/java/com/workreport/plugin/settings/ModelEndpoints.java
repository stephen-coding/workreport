package com.workreport.plugin.settings;

// Endpoint shape decides the wire protocol, mirroring agent_core.llm.detect_provider().
// 端点形状决定协议类型，规则与 Core 的 detect_provider() 保持一致：
// 填错「类型」时以端点为准，避免把 OpenAI 端点拼成 .../chat/completions/api/chat。
public final class ModelEndpoints {

    public static final String OLLAMA = "ollama";
    public static final String OPENAI = "openai_compat";

    private static final String[] OLLAMA_SUFFIXES = {
            "/api/chat", "/api/generate", "/api/embed", "/api/embeddings", "/api/tags"
    };

    private ModelEndpoints() {
    }

    public static String detect(String configured, String baseUrl) {
        String provider = configured == null ? "" : configured.trim().toLowerCase();
        String base = trimTrailingSlash(baseUrl);
        if (base.isEmpty()) {
            return provider.isEmpty() ? OLLAMA : provider;
        }
        if (endsWithAny(base, OLLAMA_SUFFIXES)) {
            return OLLAMA;
        }
        if (base.contains("/chat/completions") || base.endsWith("/v1")) {
            return OPENAI;
        }
        return OPENAI.equals(provider) ? OPENAI : OLLAMA;
    }

    public static boolean isOllama(String configured, String baseUrl) {
        return OLLAMA.equals(detect(configured, baseUrl));
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim().toLowerCase();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean endsWithAny(String value, String[] suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
