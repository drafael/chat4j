package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralSettingsResolverTest {

    @Test
    @DisplayName("Resolve returns default values when settings are missing")
    void resolve_whenSettingsMissing_returnsDefaults() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-settings-defaults");
        var modeCoordinator = new RenderModeSettingsCoordinator(settingsRepo);
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
        settingsRepo.put(SettingsKeys.CHAT_SEND_KEY, "Ctrl+Enter");
        settingsRepo.put(SettingsKeys.CHAT_AUTO_SCROLL, "false");
        settingsRepo.put(SettingsKeys.CHAT_RENDER_MODE, SettingsKeys.CHAT_RENDER_MODE_MARKDOWN);
        settingsRepo.put(SettingsKeys.MENU_BAR_ENABLED, "false");

        var modeCoordinator = new RenderModeSettingsCoordinator(settingsRepo);
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
        var modeCoordinator = new RenderModeSettingsCoordinator(failingRepo);
        var subject = new GeneralSettingsResolver(failingRepo, modeCoordinator);

        GeneralSettingsResolver.GeneralSettings settings = subject.resolve(false);

        assertThat(settings.sendOnEnter()).isTrue();
        assertThat(settings.autoScrollEnabled()).isTrue();
        assertThat(settings.defaultRenderMode()).isEqualTo(RenderMode.PREVIEW);
        assertThat(settings.menuBarEnabled()).isFalse();
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(Path.of("target", "%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("target", "test-general-settings-resolver-throwing.properties"));
        }

        @Override
        public String get(String key, String defaultValue) throws SQLException {
            throw new SQLException("forced failure");
        }
    }
}
