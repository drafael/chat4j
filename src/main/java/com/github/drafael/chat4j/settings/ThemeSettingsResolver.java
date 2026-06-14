package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.sql.SQLException;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class ThemeSettingsResolver {

    public static final String DEFAULT_THEME = "Material Lighter";
    private static final String KEY_THEME = SettingsKeys.THEME_NAME;

    private final SettingsRepository settingsRepo;

    public ThemeSettingsResolver(@NonNull SettingsRepository settingsRepo) {
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
