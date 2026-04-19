package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.Validate;

import java.util.Set;

public class FontSettingsResolver {

    private final SettingsRepo settingsRepo;

    public FontSettingsResolver(SettingsRepo settingsRepo) {
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
    }

    public String resolveAppFontFamilySetting() {
        return readString(AppearancePanel.KEY_APP_FONT, AppearancePanel.DEFAULT_APP_FONT);
    }

    public String resolveCodeFontFamilySetting() {
        return readString(AppearancePanel.KEY_CODE_FONT, AppearancePanel.DEFAULT_CODE_FONT);
    }

    public int resolveAppFontSizeSetting() {
        int defaultSize = AppearancePanel.defaultAppFontSize();
        String value = readString(AppearancePanel.KEY_APP_FONT_SIZE, String.valueOf(defaultSize));
        try {
            return AppearancePanel.normalizeAppFontSize(Integer.parseInt(value));
        } catch (Exception e) {
            return AppearancePanel.normalizeAppFontSize(defaultSize);
        }
    }

    public FontMenuSelection resolveMenuSelection(
            Set<String> availableAppFontFamilies,
            Set<Integer> availableAppFontSizes,
            Set<String> availableCodeFontFamilies
    ) {
        Validate.notNull(availableAppFontFamilies, "availableAppFontFamilies must not be null");
        Validate.notNull(availableAppFontSizes, "availableAppFontSizes must not be null");
        Validate.notNull(availableCodeFontFamilies, "availableCodeFontFamilies must not be null");

        String appFontFamily = resolveAppFontFamilySetting();
        if (!availableAppFontFamilies.contains(appFontFamily)) {
            appFontFamily = AppearancePanel.DEFAULT_APP_FONT;
        }

        int appFontSize = resolveAppFontSizeSetting();
        if (!availableAppFontSizes.contains(appFontSize)) {
            appFontSize = AppearancePanel.normalizeAppFontSize(appFontSize);
        }

        String codeFontFamily = resolveCodeFontFamilySetting();
        if (!availableCodeFontFamilies.contains(codeFontFamily)) {
            codeFontFamily = AppearancePanel.DEFAULT_CODE_FONT;
        }

        return new FontMenuSelection(appFontFamily, appFontSize, codeFontFamily);
    }

    private String readString(String key, String defaultValue) {
        try {
            return settingsRepo.get(key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public record FontMenuSelection(String appFontFamily, int appFontSize, String codeFontFamily) {
    }
}
