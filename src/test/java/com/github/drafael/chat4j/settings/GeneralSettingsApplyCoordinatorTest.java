package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralSettingsApplyCoordinatorTest {

    @Test
    @DisplayName("Apply resolves settings and global render mode")
    void apply_whenCalled_returnsResolvedSettings() {
        var capturedDefaultMode = new AtomicReference<RenderMode>();
        var subject = new GeneralSettingsApplyCoordinator(
                isMacOs -> new GeneralSettingsResolver.GeneralSettings(true, false, RenderMode.MARKDOWN, true),
                defaultMode -> {
                    capturedDefaultMode.set(defaultMode);
                    return defaultMode;
                }
        );

        GeneralSettingsApplyCoordinator.ApplyResult result = subject.apply(true);

        assertThat(result.sendOnEnter()).isTrue();
        assertThat(result.autoScrollEnabled()).isFalse();
        assertThat(result.defaultRenderMode()).isEqualTo(RenderMode.MARKDOWN);
        assertThat(result.modeToApply()).isEqualTo(RenderMode.MARKDOWN);
        assertThat(result.menuBarEnabled()).isTrue();
        assertThat(capturedDefaultMode.get()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Constructor validates required collaborators")
    void constructor_whenRequiredArgumentMissing_throwsException() {
        assertThatThrownBy(() -> new GeneralSettingsApplyCoordinator(null, defaultMode -> defaultMode))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsResolver");
    }
}
