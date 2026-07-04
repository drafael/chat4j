package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeSettingsResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve selected theme returns default when setting is missing")
    void resolveSelectedTheme_whenSettingMissing_returnsDefaultTheme() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("theme-settings-default");
        var subject = new ThemeSettingsResolver(settingsRepo);

        String selectedTheme = subject.resolveSelectedTheme(ThemeSettingsResolver.DEFAULT_THEME);

        assertThat(selectedTheme).isEqualTo(ThemeSettingsResolver.DEFAULT_THEME);
    }

    @Test
    @DisplayName("Resolve selected theme returns stored value when configured")
    void resolveSelectedTheme_whenStoredValueExists_returnsStoredTheme() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("theme-settings-stored");
        settingsRepo.put(SettingsKeys.THEME_NAME, "Solarized Dark");

        var subject = new ThemeSettingsResolver(settingsRepo);

        String selectedTheme = subject.resolveSelectedTheme(ThemeSettingsResolver.DEFAULT_THEME);

        assertThat(selectedTheme).isEqualTo("Solarized Dark");
    }

    @Test
    @DisplayName("Resolve selected theme falls back to default when settings access fails")
    void resolveSelectedTheme_whenSettingsAccessFails_returnsDefaultTheme() {
        var subject = new ThemeSettingsResolver(new ThrowingSettingsRepo());

        String selectedTheme = subject.resolveSelectedTheme(ThemeSettingsResolver.DEFAULT_THEME);

        assertThat(selectedTheme).isEqualTo(ThemeSettingsResolver.DEFAULT_THEME);
    }

    @Test
    @DisplayName("Persist selected theme stores theme setting")
    void persistSelectedTheme_whenCalled_persistsThemeSetting() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("theme-settings-persist");
        var subject = new ThemeSettingsResolver(settingsRepo);

        subject.persistSelectedTheme("GitHub Dark");

        assertThat(settingsRepo.get(SettingsKeys.THEME_NAME)).contains("GitHub Dark");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-theme-settings-resolver.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
