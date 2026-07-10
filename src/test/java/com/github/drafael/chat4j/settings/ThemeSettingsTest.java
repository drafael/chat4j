package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Theme settings own backward-compatible keys and default theme")
    void constants_whenRead_matchPersistedKeysAndDefaultTheme() {
        assertThat(ThemeSettings.THEME_NAME_KEY).isEqualTo("chat4j.ui.theme.name");
        assertThat(ThemeSettings.THEME_ACCENT_KEY).isEqualTo("chat4j.ui.theme.accent");
        assertThat(ThemeSettings.DEFAULT_THEME).isEqualTo("Material Lighter");
    }

    @Test
    @DisplayName("Theme settings returns stored selected theme")
    void selectedTheme_whenStored_returnsStoredValue() {
        SettingsRepository settingsRepo = settingsRepo("theme-settings-stored");
        settingsRepo.put(ThemeSettings.THEME_NAME_KEY, "GitHub Dark");
        var subject = new ThemeSettings(settingsRepo);

        assertThat(subject.selectedTheme(ThemeSettings.DEFAULT_THEME)).isEqualTo("GitHub Dark");
    }

    @Test
    @DisplayName("Theme settings falls back when repository access fails")
    void selectedTheme_whenRepositoryFails_returnsCallerDefault() {
        var subject = new ThemeSettings(new ThrowingSettingsRepo());

        assertThat(subject.selectedTheme("Fallback Theme")).isEqualTo("Fallback Theme");
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-theme-settings.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
