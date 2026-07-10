package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FontSettingsPersisterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Persist app font selection stores family and size settings")
    void persistAppFontSelection_whenCalled_persistsFamilyAndSize() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("font-persister-app");
        var subject = new FontSettingsPersister(settingsRepo);

        subject.persistAppFontSelection("Inter", 16);

        assertThat(settingsRepo.get(FontSettings.APP_FONT_FAMILY_KEY)).contains("Inter");
        assertThat(settingsRepo.get(FontSettings.APP_FONT_SIZE_KEY)).contains("16");
    }

    @Test
    @DisplayName("Persist code font family stores code font setting")
    void persistCodeFontFamily_whenCalled_persistsCodeFontFamily() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("font-persister-code");
        var subject = new FontSettingsPersister(settingsRepo);

        subject.persistCodeFontFamily("JetBrains Mono");

        assertThat(settingsRepo.get(FontSettings.CODE_FONT_FAMILY_KEY)).contains("JetBrains Mono");
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }
}
