package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class ThemeSettingsResolver {

    public static final String DEFAULT_THEME = ThemeSettings.DEFAULT_THEME;

    private final SettingsRepository settingsRepo;

    public ThemeSettingsResolver(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String resolveSelectedTheme(String defaultTheme) {
        Validate.notBlank(defaultTheme, "defaultTheme must not be blank");

        return themeSettings().selectedTheme(defaultTheme);
    }

    public void persistSelectedTheme(String themeName) {
        Validate.notBlank(themeName, "themeName must not be blank");
        themeSettings().persistSelectedTheme(themeName);
    }

    private ThemeSettings themeSettings() {
        return new ThemeSettings(settingsRepo);
    }
}
