package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RenderModeSettingsCoordinatorTest {

    @Test
    @DisplayName("Resolve default mode falls back to preview when setting is missing")
    void resolveDefaultMode_whenSettingMissing_returnsPreview() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("render-mode-default-missing");
        var subject = new RenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Resolve default mode returns markdown when markdown setting value is stored")
    void resolveDefaultMode_whenStoredSettingValue_returnsMarkdown() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("render-mode-default-markdown-setting-value");
        settingsRepo.put(SettingsKeys.CHAT_RENDER_MODE, RenderMode.MARKDOWN.settingValue());

        var subject = new RenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve default mode accepts legacy enum-name values")
    void resolveDefaultMode_whenStoredLegacyEnumName_returnsMarkdown() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("render-mode-default-markdown-legacy-name");
        settingsRepo.put(SettingsKeys.CHAT_RENDER_MODE, "MARKDOWN");

        var subject = new RenderModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Persist default mode stores global render mode setting value")
    void persistDefaultMode_whenCalled_persistsModeSettingValue() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("render-mode-persist");

        var subject = new RenderModeSettingsCoordinator(settingsRepo);
        subject.persistDefaultMode(RenderMode.MARKDOWN);

        assertThat(settingsRepo.get(SettingsKeys.CHAT_RENDER_MODE))
                .contains(RenderMode.MARKDOWN.settingValue());
    }

    private SettingsRepo settingsRepo(String testName) {
        return new SettingsRepo(Path.of("target", "%s.properties".formatted(testName)));
    }
}
