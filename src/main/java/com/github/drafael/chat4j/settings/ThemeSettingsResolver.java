package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.Validate;

import java.sql.SQLException;

public class ThemeSettingsResolver {

    public static final String DEFAULT_THEME = "GitHub";
    private static final String KEY_THEME = "theme";

    private final SettingsRepo settingsRepo;

    public ThemeSettingsResolver(SettingsRepo settingsRepo) {
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
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
