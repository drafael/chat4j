package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import java.sql.SQLException;

public class ThemeSettingsResolver {

    public static final String DEFAULT_THEME = "Material Lighter";
    private static final String KEY_THEME = SettingsKeys.THEME_NAME;

    private final SettingsRepo settingsRepo;

    public ThemeSettingsResolver(@NonNull SettingsRepo settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String resolveSelectedTheme(String defaultTheme) {
        Validate.notBlank(defaultTheme, "defaultTheme must not be blank");

        try {
            return settingsRepo.get(KEY_THEME, defaultTheme);
        } catch (Exception e) {
            return defaultTheme;
        }
    }

    public void persistSelectedTheme(String themeName) throws SQLException {
        Validate.notBlank(themeName, "themeName must not be blank");
        settingsRepo.put(KEY_THEME, themeName);
    }
}
