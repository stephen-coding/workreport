package com.workreport.agent;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

// Shell UI strings (ROADMAP §7.5). Base bundle is Chinese, `_en` is used when the IDE runs in English.
// Core prompts and tool descriptions stay in Chinese on purpose: translating them would churn prompts.
// Shell 界面文案（ROADMAP §7.5）。基线是中文，IDE 切英文时自动使用 `_en`。
// Core 的提示词与工具描述刻意不翻译：改动它们会破坏 prompt 的稳定性。
public final class AgentBundle extends DynamicBundle {

    private static final String BUNDLE = "messages.AgentBundle";
    private static final AgentBundle INSTANCE = new AgentBundle();

    private AgentBundle() {
        super(BUNDLE);
    }

    public static String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
                                 Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
