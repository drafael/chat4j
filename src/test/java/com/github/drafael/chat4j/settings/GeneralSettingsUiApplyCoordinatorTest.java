package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralSettingsUiApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates UI settings in order and returns default assistant render mode")
    void apply_whenCalled_updatesUiSettingsAndReturnsDefaultMode() {
        var subject = new GeneralSettingsUiApplyCoordinator();
        var applyResult = new GeneralSettingsApplyCoordinator.ApplyResult(
                true,
                false,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW,
                true
        );
        var calls = new ArrayList<String>();

        AssistantRenderMode returnedDefaultMode = subject.apply(
                applyResult,
                sendOnEnter -> calls.add("send-on-enter:%s".formatted(sendOnEnter)),
                autoScrollEnabled -> calls.add("auto-scroll:%s".formatted(autoScrollEnabled)),
                (mode, userInitiated) -> calls.add("render-mode:%s:%s".formatted(mode, userInitiated)),
                enabled -> calls.add("menu-bar:%s".formatted(enabled))
        );

        assertThat(returnedDefaultMode).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(calls).containsExactly(
                "send-on-enter:true",
                "auto-scroll:false",
                "render-mode:PREVIEW:true",
                "menu-bar:true"
        );
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new GeneralSettingsUiApplyCoordinator();
        var applyResult = new GeneralSettingsApplyCoordinator.ApplyResult(
                true,
                false,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW,
                true
        );

        assertThatThrownBy(() -> subject.apply(
                null,
                sendOnEnter -> {
                },
                autoScrollEnabled -> {
                },
                (mode, userInitiated) -> {
                },
                enabled -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applyResult");

        assertThatThrownBy(() -> subject.apply(
                applyResult,
                null,
                autoScrollEnabled -> {
                },
                (mode, userInitiated) -> {
                },
                enabled -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sendOnEnterApplier");
    }
}
