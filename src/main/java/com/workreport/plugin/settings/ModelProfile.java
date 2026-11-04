package com.workreport.plugin.settings;

// One switchable model profile: name is the stable id used for the API key lookup.
// 一条可切换的模型配置：name 作为 API Key 的稳定索引。
public final class ModelProfile {

    public String name = "";
    public String provider = "ollama";   // "ollama" | "openai_compat"
    public String baseUrl = "";
    public String model = "";

    public ModelProfile() {
    }

    public ModelProfile(String name, String provider, String baseUrl, String model) {
        this.name = name;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public boolean isValid() {
        return name != null && !name.isBlank()
                && provider != null && !provider.isBlank()
                && model != null && !model.isBlank();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModelProfile)) {
            return false;
        }
        ModelProfile that = (ModelProfile) other;
        return name.equals(that.name)
                && provider.equals(that.provider)
                && baseUrl.equals(that.baseUrl)
                && model.equals(that.model);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, provider, baseUrl, model);
    }
}
