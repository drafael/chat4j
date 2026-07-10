package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.Set;
import lombok.NonNull;

public class FontSettingsResolver {

    private final SettingsRepository settingsRepo;

    public FontSettingsResolver(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String resolveAppFontFamilySetting() {
        return fontSettings().appFontFamily(FontSettings.DEFAULT_APP_FONT);
    }

    public String resolveCodeFontFamilySetting() {
        return fontSettings().codeFontFamily(FontSettings.DEFAULT_CODE_FONT);
    }

    public int resolveAppFontSizeSetting() {
        int defaultSize = AppearancePanel.defaultAppFontSize();
        String value = fontSettings().appFontSize(String.valueOf(defaultSize));
        try {
            return AppearancePanel.normalizeAppFontSize(Integer.parseInt(value));
        } catch (Exception e) {
            return AppearancePanel.normalizeAppFontSize(defaultSize);
        }
    }

    public FontMenuSelection resolveMenuSelection(
            @NonNull Set<String> availableAppFontFamilies,
            @NonNull Set<Integer> availableAppFontSizes,
            @NonNull Set<String> availableCodeFontFamilies
    ) {

        String appFontFamily = resolveAppFontFamilySetting();
        if (!availableAppFontFamilies.contains(appFontFamily)) {
            appFontFamily = FontSettings.DEFAULT_APP_FONT;
        }

        int appFontSize = resolveAppFontSizeSetting();
        if (!availableAppFontSizes.contains(appFontSize)) {
            appFontSize = AppearancePanel.normalizeAppFontSize(appFontSize);
        }

        String codeFontFamily = resolveCodeFontFamilySetting();
        if (!availableCodeFontFamilies.contains(codeFontFamily)) {
            codeFontFamily = FontSettings.DEFAULT_CODE_FONT;
        }

        return new FontMenuSelection(appFontFamily, appFontSize, codeFontFamily);
    }

    private FontSettings fontSettings() {
        return new FontSettings(settingsRepo);
    }

    public record FontMenuSelection(String appFontFamily, int appFontSize, String codeFontFamily) {
    }
}
