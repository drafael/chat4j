package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeSettingsResolverTest {

    @Test
    @DisplayName("Resolve selected theme returns default when setting is missing")
    void resolveSelectedTheme_whenSettingMissing_returnsDefaultTheme() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("theme-settings-default");
        var subject = new ThemeSettingsResolver(settingsRepo);

        String selectedTheme = subject.resolveSelectedTheme(ThemeSettingsResolver.DEFAULT_THEME);

        assertThat(selectedTheme).isEqualTo(ThemeSettingsResolver.DEFAULT_THEME);
    }

    @Test
    @DisplayName("Resolve selected theme returns stored value when configured")
    void resolveSelectedTheme_whenStoredValueExists_returnsStoredTheme() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("theme-settings-stored");
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
        SettingsRepo settingsRepo = settingsRepo("theme-settings-persist");
        var subject = new ThemeSettingsResolver(settingsRepo);

        subject.persistSelectedTheme("GitHub Dark");

        assertThat(settingsRepo.get(SettingsKeys.THEME_NAME)).contains("GitHub Dark");
    }

    private SettingsRepo settingsRepo(String testName) {
        return new SettingsRepo(Path.of("target", "%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepo {

        private ThrowingSettingsRepo() {
            super(Path.of("target", "test-theme-settings-resolver-throwing.properties"));
        }

        @Override
        public String get(String key, String defaultValue) throws SQLException {
            throw new SQLException("forced failure");
        }
    }
}
