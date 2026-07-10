package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.settings.FontSettings;
import com.github.drafael.chat4j.settings.ThemeSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationBootstrapTest {

    @Test
    @DisplayName("Saved appearance application aborts later font reads when theme read fails")
    void applySavedAppearance_whenThemeReadFails_doesNotApplySavedFonts() {
        var settingsRepo = new ThemeReadFailingSettingsRepo();
        var subject = new ApplicationBootstrap(new EnvironmentBootstrapper());

        subject.applySavedAppearance(settingsRepo);

        assertThat(settingsRepo.readKeys).contains(ThemeSettings.THEME_ACCENT_KEY, ThemeSettings.THEME_NAME_KEY);
        assertThat(settingsRepo.readKeys)
                .doesNotContain(
                        FontSettings.APP_FONT_FAMILY_KEY,
                        FontSettings.APP_FONT_SIZE_KEY,
                        FontSettings.CODE_FONT_FAMILY_KEY
                );
    }

    private static class ThemeReadFailingSettingsRepo extends SettingsRepository {

        private final List<String> readKeys = new ArrayList<>();

        private ThemeReadFailingSettingsRepo() {
            super(Path.of("unused-application-bootstrap.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            readKeys.add(key);
            if (ThemeSettings.THEME_NAME_KEY.equals(key)) {
                throw new IllegalStateException("forced theme read failure");
            }
            return defaultValue;
        }
    }
}
