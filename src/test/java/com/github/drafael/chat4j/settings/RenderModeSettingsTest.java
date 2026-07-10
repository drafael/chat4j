package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderModeSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve default mode falls back to preview when setting is missing")
    void resolveDefaultMode_whenSettingMissing_returnsPreview() {
        SettingsRepository settingsRepo = settingsRepo("render-mode-default-missing");
        var subject = new RenderModeSettings(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Resolve default mode returns markdown when markdown setting value is stored")
    void resolveDefaultMode_whenStoredSettingValue_returnsMarkdown() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-default-markdown-setting-value");
        settingsRepo.put("chat4j.chat.render.mode", RenderMode.MARKDOWN.settingValue());

        var subject = new RenderModeSettings(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve default mode accepts legacy enum-name values")
    void resolveDefaultMode_whenStoredLegacyEnumName_returnsMarkdown() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-default-markdown-legacy-name");
        settingsRepo.put("chat4j.chat.render.mode", "MARKDOWN");

        var subject = new RenderModeSettings(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve default mode falls back to preview when value is invalid")
    void resolveDefaultMode_whenStoredValueInvalid_returnsPreview() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-default-invalid");
        settingsRepo.put("chat4j.chat.render.mode", "invalid");

        var subject = new RenderModeSettings(settingsRepo);

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Resolve default mode falls back to preview when repository read fails")
    void resolveDefaultMode_whenRepositoryFails_returnsPreview() {
        var subject = new RenderModeSettings(new ThrowingSettingsRepo(true));

        assertThat(subject.resolveDefaultMode()).isEqualTo(RenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Persist default mode stores global render mode setting value")
    void persistDefaultMode_whenCalled_persistsModeSettingValue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-persist");

        var subject = new RenderModeSettings(settingsRepo);
        subject.persistDefaultMode(RenderMode.MARKDOWN);

        assertThat(settingsRepo.get("chat4j.chat.render.mode"))
                .contains(RenderMode.MARKDOWN.settingValue());
    }

    @Test
    @DisplayName("Persist default mode ignores null modes")
    void persistDefaultMode_whenModeNull_doesNothing() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("render-mode-persist-null");

        var subject = new RenderModeSettings(settingsRepo);
        subject.persistDefaultMode(null);

        assertThat(settingsRepo.get("chat4j.chat.render.mode")).isEmpty();
    }

    @Test
    @DisplayName("Persist default mode write failures propagate")
    void persistDefaultMode_whenRepositoryFails_propagatesFailure() {
        var subject = new RenderModeSettings(new ThrowingSettingsRepo(false));

        assertThatThrownBy(() -> subject.persistDefaultMode(RenderMode.MARKDOWN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private final boolean failReads;

        private ThrowingSettingsRepo(boolean failReads) {
            super(Path.of("unused-render-mode-settings.properties"));
            this.failReads = failReads;
        }

        @Override
        public Optional<String> get(String key) {
            if (failReads) {
                throw new IllegalStateException("forced failure");
            }
            return Optional.empty();
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }
    }
}
