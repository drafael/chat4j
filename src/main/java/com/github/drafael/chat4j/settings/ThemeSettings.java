package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public final class ThemeSettings {

    public static final String THEME_NAME_KEY = "chat4j.ui.theme.name";
    public static final String THEME_ACCENT_KEY = "chat4j.ui.theme.accent";
    public static final String DEFAULT_THEME = "Material Lighter";

    private final SettingsRepository settingsRepo;

    public ThemeSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String selectedTheme(String defaultTheme) {
        try {
            return settingsRepo.get(THEME_NAME_KEY, defaultTheme);
        } catch (Exception e) {
            return defaultTheme;
        }
    }

    public void persistSelectedTheme(String themeName) {
        settingsRepo.put(THEME_NAME_KEY, themeName);
    }
}
