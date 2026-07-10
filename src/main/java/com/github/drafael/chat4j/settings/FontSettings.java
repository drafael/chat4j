package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public final class FontSettings {

    public static final String APP_FONT_FAMILY_KEY = "chat4j.ui.font.app.family";
    public static final String APP_FONT_SIZE_KEY = "chat4j.ui.font.app.size";
    public static final String CODE_FONT_FAMILY_KEY = "chat4j.ui.font.code.family";
    public static final String DEFAULT_APP_FONT = "System Default";
    public static final String DEFAULT_CODE_FONT = "Monospaced";

    private final SettingsRepository settingsRepo;

    public FontSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String appFontFamily(String defaultValue) {
        return readString(APP_FONT_FAMILY_KEY, defaultValue);
    }

    public String appFontSize(String defaultValue) {
        return readString(APP_FONT_SIZE_KEY, defaultValue);
    }

    public String codeFontFamily(String defaultValue) {
        return readString(CODE_FONT_FAMILY_KEY, defaultValue);
    }

    public void persistAppFontSelection(String family, int size) {
        settingsRepo.put(APP_FONT_FAMILY_KEY, family);
        settingsRepo.put(APP_FONT_SIZE_KEY, String.valueOf(size));
    }

    public void persistCodeFontFamily(String family) {
        settingsRepo.put(CODE_FONT_FAMILY_KEY, family);
    }

    private String readString(String key, String defaultValue) {
        try {
            return settingsRepo.get(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
