package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FontSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Font settings own backward-compatible keys and defaults")
    void constants_whenRead_matchPersistedKeysAndDefaults() {
        assertThat(FontSettings.APP_FONT_FAMILY_KEY).isEqualTo("chat4j.ui.font.app.family");
        assertThat(FontSettings.APP_FONT_SIZE_KEY).isEqualTo("chat4j.ui.font.app.size");
        assertThat(FontSettings.CODE_FONT_FAMILY_KEY).isEqualTo("chat4j.ui.font.code.family");
        assertThat(FontSettings.DEFAULT_APP_FONT).isEqualTo("System Default");
        assertThat(FontSettings.DEFAULT_CODE_FONT).isEqualTo("Monospaced");
    }

    @Test
    @DisplayName("Font settings read raw values without parsing app font size")
    void readMethods_whenValuesConfigured_returnRawValues() {
        SettingsRepository settingsRepo = settingsRepo("font-settings-raw");
        settingsRepo.put(FontSettings.APP_FONT_FAMILY_KEY, "Inter");
        settingsRepo.put(FontSettings.APP_FONT_SIZE_KEY, "not-a-number");
        settingsRepo.put(FontSettings.CODE_FONT_FAMILY_KEY, "JetBrains Mono");
        var subject = new FontSettings(settingsRepo);

        assertThat(subject.appFontFamily(FontSettings.DEFAULT_APP_FONT)).isEqualTo("Inter");
        assertThat(subject.appFontSize("13")).isEqualTo("not-a-number");
        assertThat(subject.codeFontFamily(FontSettings.DEFAULT_CODE_FONT)).isEqualTo("JetBrains Mono");
    }

    @Test
    @DisplayName("Font settings reads fall back when repository access fails")
    void readMethods_whenRepositoryFails_returnCallerDefaults() {
        var subject = new FontSettings(new ThrowingSettingsRepo());

        assertThat(subject.appFontFamily("app-default")).isEqualTo("app-default");
        assertThat(subject.appFontSize("13")).isEqualTo("13");
        assertThat(subject.codeFontFamily("code-default")).isEqualTo("code-default");
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-font-settings.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
