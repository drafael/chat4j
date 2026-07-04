package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class RenderModeSettingsCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve default mode falls back to preview when setting is missing")
    void resolveDefaultMode_whenSettingMissing_returnsPreview() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-default-missing");
        var subject = new RenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Resolve default mode returns markdown when markdown setting value is stored")
    void resolveDefaultMode_whenStoredSettingValue_returnsMarkdown() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-default-markdown-setting-value");
        settingsRepo.put(SettingsKeys.CHAT_RENDER_MODE, RenderMode.MARKDOWN.settingValue());

        var subject = new RenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve default mode accepts legacy enum-name values")
    void resolveDefaultMode_whenStoredLegacyEnumName_returnsMarkdown() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-default-markdown-legacy-name");
        settingsRepo.put(SettingsKeys.CHAT_RENDER_MODE, "MARKDOWN");

        var subject = new RenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Persist default mode stores global render mode setting value")
    void persistDefaultMode_whenCalled_persistsModeSettingValue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-persist");

        var subject = new RenderModeSettingsCoordinator(settingsRepo);
        subject.persistDefaultMode(RenderMode.MARKDOWN);

        assertThat(settingsRepo.get(SettingsKeys.CHAT_RENDER_MODE))
                .contains(RenderMode.MARKDOWN.settingValue());
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }
}
