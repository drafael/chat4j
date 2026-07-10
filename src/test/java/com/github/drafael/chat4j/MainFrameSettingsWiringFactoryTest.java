package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.settings.FontMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontPreviewApplier;
import com.github.drafael.chat4j.settings.FontSelectionNormalizer;
import com.github.drafael.chat4j.settings.GeneralSettingsUiApplyCoordinator;
import com.github.drafael.chat4j.settings.RenderModeChangeUiApplyCoordinator;
import com.github.drafael.chat4j.settings.RenderModeSelectionResolver;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionSynchronizer;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameSettingsWiringFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Create builds non-null settings wiring graph")
    void create_whenCalled_buildsSettingsWiring() throws Exception {
        var subject = new MainFrameSettingsWiringFactory();
        var settingsRepo = settingsRepo("mainframe-settings-wiring");

        MainFrameSettingsWiringFactory.SettingsWiring wiring = subject.create(
                settingsRepo,
                new RenderModeSelectionResolver(),
                new RenderModeChangeUiApplyCoordinator(),
                new GeneralSettingsUiApplyCoordinator(),
                new FontSelectionNormalizer(),
                new FontPreviewApplier(),
                new FontMenuSelectionSynchronizer(),
                new FontMenuSelectionApplyCoordinator(),
                new ThemeMenuSelectionSynchronizer(),
                new ThemeMenuSelectionApplyCoordinator()
        );

        assertThat(wiring.renderModeSettings()).isNotNull();
        assertThat(wiring.renderModeChangeCoordinator()).isNotNull();
        assertThat(wiring.renderModeChangeDispatchCoordinator()).isNotNull();
        assertThat(wiring.generalSettingsResolver()).isNotNull();
        assertThat(wiring.generalSettingsApplyCoordinator()).isNotNull();
        assertThat(wiring.generalSettingsApplyDispatchCoordinator()).isNotNull();
        assertThat(wiring.fontSettingsResolver()).isNotNull();
        assertThat(wiring.fontSettingsPersister()).isNotNull();
        assertThat(wiring.fontMenuApplyCoordinator()).isNotNull();
        assertThat(wiring.fontMenuSelectionRefreshCoordinator()).isNotNull();
        assertThat(wiring.fontMenuSelectionDispatchCoordinator()).isNotNull();
        assertThat(wiring.fontMenuSelectionFlowCoordinator()).isNotNull();
        assertThat(wiring.appFontSizeStepResolver()).isNotNull();
        assertThat(wiring.appFontSizeAdjustCoordinator()).isNotNull();
        assertThat(wiring.themeSettingsResolver()).isNotNull();
        assertThat(wiring.themeMenuApplyCoordinator()).isNotNull();
        assertThat(wiring.themeMenuSelectionRefreshCoordinator()).isNotNull();
        assertThat(wiring.themeMenuSelectionDispatchCoordinator()).isNotNull();
        assertThat(wiring.themeMenuSelectionFlowCoordinator()).isNotNull();
    }

    @Test
    @DisplayName("Create validates required dependencies")
    void create_whenRequiredDependencyMissing_throwsException() {
        var subject = new MainFrameSettingsWiringFactory();

        assertThatThrownBy(() -> subject.create(
                null,
                new RenderModeSelectionResolver(),
                new RenderModeChangeUiApplyCoordinator(),
                new GeneralSettingsUiApplyCoordinator(),
                new FontSelectionNormalizer(),
                new FontPreviewApplier(),
                new FontMenuSelectionSynchronizer(),
                new FontMenuSelectionApplyCoordinator(),
                new ThemeMenuSelectionSynchronizer(),
                new ThemeMenuSelectionApplyCoordinator()
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsRepo");
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }
}
