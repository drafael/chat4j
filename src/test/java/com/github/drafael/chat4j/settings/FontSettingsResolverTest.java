package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FontSettingsResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve menu selection uses defaults when settings are missing")
    void resolveMenuSelection_whenSettingsMissing_usesDefaults() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("font-settings-defaults");
        var subject = new FontSettingsResolver(settingsRepo);

        FontSettingsResolver.FontMenuSelection selection = subject.resolveMenuSelection(
                Set.of(FontSettings.DEFAULT_APP_FONT),
                Set.of(AppearancePanel.defaultAppFontSize()),
                Set.of(FontSettings.DEFAULT_CODE_FONT)
        );

        assertThat(selection.appFontFamily()).isEqualTo(FontSettings.DEFAULT_APP_FONT);
        assertThat(selection.appFontSize()).isEqualTo(AppearancePanel.defaultAppFontSize());
        assertThat(selection.codeFontFamily()).isEqualTo(FontSettings.DEFAULT_CODE_FONT);
    }

    @Test
    @DisplayName("Resolve menu selection falls back for unavailable font families")
    void resolveMenuSelection_whenConfiguredFamiliesAreUnavailable_fallsBackToDefaults() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("font-settings-family-fallback");
        settingsRepo.put(FontSettings.APP_FONT_FAMILY_KEY, "Unavailable UI Font");
        settingsRepo.put(FontSettings.CODE_FONT_FAMILY_KEY, "Unavailable Code Font");
        settingsRepo.put(FontSettings.APP_FONT_SIZE_KEY, "18");

        var subject = new FontSettingsResolver(settingsRepo);

        FontSettingsResolver.FontMenuSelection selection = subject.resolveMenuSelection(
                Set.of(FontSettings.DEFAULT_APP_FONT, "Inter"),
                Set.of(14, 16, 18),
                Set.of(FontSettings.DEFAULT_CODE_FONT, "JetBrains Mono")
        );

        assertThat(selection.appFontFamily()).isEqualTo(FontSettings.DEFAULT_APP_FONT);
        assertThat(selection.appFontSize()).isEqualTo(18);
        assertThat(selection.codeFontFamily()).isEqualTo(FontSettings.DEFAULT_CODE_FONT);
    }

    @Test
    @DisplayName("Resolve app font size falls back to default when value is invalid")
    void resolveAppFontSizeSetting_whenValueIsInvalid_returnsDefaultNormalizedSize() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("font-settings-size-invalid");
        settingsRepo.put(FontSettings.APP_FONT_SIZE_KEY, "not-a-number");

        var subject = new FontSettingsResolver(settingsRepo);

        int resolvedSize = subject.resolveAppFontSizeSetting();

        assertThat(resolvedSize).isEqualTo(AppearancePanel.normalizeAppFontSize(AppearancePanel.defaultAppFontSize()));
    }

    @Test
    @DisplayName("Resolve methods return defaults when repository access fails")
    void resolveMethods_whenRepositoryFails_returnDefaults() {
        SettingsRepository failingRepo = new ThrowingSettingsRepo();
        var subject = new FontSettingsResolver(failingRepo);

        assertThat(subject.resolveAppFontFamilySetting()).isEqualTo(FontSettings.DEFAULT_APP_FONT);
        assertThat(subject.resolveCodeFontFamilySetting()).isEqualTo(FontSettings.DEFAULT_CODE_FONT);
        assertThat(subject.resolveAppFontSizeSetting())
                .isEqualTo(AppearancePanel.normalizeAppFontSize(AppearancePanel.defaultAppFontSize()));
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-font-settings-resolver.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
