package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralSettingsResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve returns default values when settings are missing")
    void resolve_whenSettingsMissing_returnsDefaults() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-settings-defaults");
        var modeCoordinator = new RenderModeSettings(settingsRepo);
        var subject = new GeneralSettingsResolver(settingsRepo, modeCoordinator);

        GeneralSettingsResolver.GeneralSettings settings = subject.resolve(true);

        assertThat(settings.sendOnEnter()).isTrue();
        assertThat(settings.autoScrollEnabled()).isTrue();
        assertThat(settings.defaultRenderMode()).isEqualTo(RenderMode.PREVIEW);
        assertThat(settings.menuBarEnabled()).isTrue();
    }

    @Test
    @DisplayName("Resolve returns configured values when settings exist")
    void resolve_whenSettingsConfigured_returnsConfiguredValues() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-settings-configured");
        settingsRepo.put("chat4j.chat.input.sendKey", "Ctrl+Enter");
        settingsRepo.put("chat4j.chat.behavior.autoScroll", "false");
        settingsRepo.put("chat4j.chat.render.mode", RenderMode.MARKDOWN.settingValue());
        settingsRepo.put("chat4j.ui.menuBar.enabled", "false");

        var modeCoordinator = new RenderModeSettings(settingsRepo);
        var subject = new GeneralSettingsResolver(settingsRepo, modeCoordinator);

        GeneralSettingsResolver.GeneralSettings settings = subject.resolve(true);

        assertThat(settings.sendOnEnter()).isFalse();
        assertThat(settings.autoScrollEnabled()).isFalse();
        assertThat(settings.defaultRenderMode()).isEqualTo(RenderMode.MARKDOWN);
        assertThat(settings.menuBarEnabled()).isFalse();
    }

    @Test
    @DisplayName("Resolve falls back to safe defaults when repository access fails")
    void resolve_whenRepositoryFails_returnsSafeDefaults() {
        SettingsRepository failingRepo = new ThrowingSettingsRepo();
        var modeCoordinator = new RenderModeSettings(failingRepo);
        var subject = new GeneralSettingsResolver(failingRepo, modeCoordinator);

        GeneralSettingsResolver.GeneralSettings settings = subject.resolve(false);

        assertThat(settings.sendOnEnter()).isTrue();
        assertThat(settings.autoScrollEnabled()).isTrue();
        assertThat(settings.defaultRenderMode()).isEqualTo(RenderMode.PREVIEW);
        assertThat(settings.menuBarEnabled()).isFalse();
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-general-settings-resolver.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
