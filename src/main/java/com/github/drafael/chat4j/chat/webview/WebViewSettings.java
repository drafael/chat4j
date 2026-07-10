package com.github.drafael.chat4j.chat.webview;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public final class WebViewSettings {

    public static final String ENGINE_KEY = "chat4j.chat.webView.engine";

    private final SettingsRepository settingsRepo;

    public WebViewSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String readEngineValue(String defaultValue) {
        try {
            return settingsRepo.get(ENGINE_KEY, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public WebViewEngine resolveEngine(WebViewEngine fallback) {
        return WebViewEngine.fromSettingValue(readEngineValue(""), fallback);
    }

    public void persistEngine(WebViewEngine engine) {
        persistEngineValue(engine.settingValue());
    }

    public void persistEngineValue(String value) {
        settingsRepo.put(ENGINE_KEY, value);
    }
}
