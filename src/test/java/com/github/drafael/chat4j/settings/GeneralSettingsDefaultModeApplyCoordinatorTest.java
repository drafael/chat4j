package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralSettingsDefaultModeApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates default render mode state and returns applied mode")
    void apply_whenCalled_updatesStateAndReturnsMode() {
        var subject = new GeneralSettingsDefaultModeApplyCoordinator();
        var defaultRenderMode = new AtomicReference<RenderMode>();

        RenderMode applied = subject.apply(
                RenderMode.MARKDOWN,
                defaultRenderMode::set
        );

        assertThat(applied).isEqualTo(RenderMode.MARKDOWN);
        assertThat(defaultRenderMode.get()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new GeneralSettingsDefaultModeApplyCoordinator();

        assertThatThrownBy(() -> subject.apply(null, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("defaultRenderMode");

        assertThatThrownBy(() -> subject.apply(RenderMode.PREVIEW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setDefaultRenderMode");
    }
}
