package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralSettingsDefaultModeApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates default assistant mode state and returns applied mode")
    void apply_whenCalled_updatesStateAndReturnsMode() {
        var subject = new GeneralSettingsDefaultModeApplyCoordinator();
        var assistantMarkdownDefaultMode = new AtomicReference<AssistantRenderMode>();

        AssistantRenderMode applied = subject.apply(
                AssistantRenderMode.MARKDOWN,
                assistantMarkdownDefaultMode::set
        );

        assertThat(applied).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(assistantMarkdownDefaultMode.get()).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new GeneralSettingsDefaultModeApplyCoordinator();

        assertThatThrownBy(() -> subject.apply(null, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("defaultAssistantRenderMode");

        assertThatThrownBy(() -> subject.apply(AssistantRenderMode.PREVIEW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setAssistantMarkdownDefaultMode");
    }
}
