package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralSettingsApplyDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply delegates to settings apply and UI apply actions")
    void apply_whenCalled_delegatesToSettingsAndUiApply() {
        UUID conversationId = UUID.randomUUID();
        var calls = new ArrayList<String>();
        var capturedApplyResult = new AtomicReference<GeneralSettingsApplyCoordinator.ApplyResult>();

        var applyResult = new GeneralSettingsApplyCoordinator.ApplyResult(
                true,
                false,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW,
                true
        );

        var subject = new GeneralSettingsApplyDispatchCoordinator(
                (isMacOS, currentConversationId, currentConversationRenderMode, pendingUnsavedConversationRenderMode) -> {
                    calls.add("settings-apply:%s:%s".formatted(isMacOS, currentConversationId));
                    return applyResult;
                },
                (resolvedApplyResult,
                 sendOnEnterApplier,
                 autoScrollApplier,
                 renderModeApplier,
                 menuBarSettingApplier) -> {
                    calls.add("ui-apply");
                    capturedApplyResult.set(resolvedApplyResult);
                    sendOnEnterApplier.apply(resolvedApplyResult.sendOnEnter());
                    autoScrollApplier.apply(resolvedApplyResult.autoScrollEnabled());
                    renderModeApplier.apply(resolvedApplyResult.modeToApply(), true);
                    menuBarSettingApplier.apply(resolvedApplyResult.menuBarEnabled());
                    return resolvedApplyResult.defaultAssistantRenderMode();
                }
        );

        var uiCalls = new ArrayList<String>();
        AssistantRenderMode defaultMode = subject.apply(
                true,
                conversationId,
                AssistantRenderMode.PREVIEW,
                AssistantRenderMode.MARKDOWN,
                sendOnEnter -> uiCalls.add("send-on-enter:%s".formatted(sendOnEnter)),
                autoScroll -> uiCalls.add("auto-scroll:%s".formatted(autoScroll)),
                (mode, userInitiated) -> uiCalls.add("render-mode:%s:%s".formatted(mode, userInitiated)),
                menuBarEnabled -> uiCalls.add("menu-bar:%s".formatted(menuBarEnabled))
        );

        assertThat(defaultMode).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(calls).containsExactly("settings-apply:true:%s".formatted(conversationId), "ui-apply");
        assertThat(capturedApplyResult.get()).isSameAs(applyResult);
        assertThat(uiCalls).containsExactly(
                "send-on-enter:true",
                "auto-scroll:false",
                "render-mode:PREVIEW:true",
                "menu-bar:true"
        );
    }

    @Test
    @DisplayName("Apply validates required arguments and dependencies")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new GeneralSettingsApplyDispatchCoordinator(
                (isMacOS, currentConversationId, currentConversationRenderMode, pendingUnsavedConversationRenderMode) ->
                        new GeneralSettingsApplyCoordinator.ApplyResult(
                                true,
                                true,
                                AssistantRenderMode.MARKDOWN,
                                AssistantRenderMode.PREVIEW,
                                true
                        ),
                (applyResult,
                 sendOnEnterApplier,
                 autoScrollApplier,
                 renderModeApplier,
                 menuBarSettingApplier) -> applyResult.defaultAssistantRenderMode()
        );

        assertThatThrownBy(() -> subject.apply(
                false,
                null,
                null,
                null,
                null,
                autoScroll -> {
                },
                (mode, userInitiated) -> {
                },
                menuBarEnabled -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sendOnEnterApplier must not be null");

        assertThatThrownBy(() -> new GeneralSettingsApplyDispatchCoordinator(
                (GeneralSettingsApplyDispatchCoordinator.SettingsApplyAction) null,
                (applyResult,
                 sendOnEnterApplier,
                 autoScrollApplier,
                 renderModeApplier,
                 menuBarSettingApplier) -> applyResult.defaultAssistantRenderMode()
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsApplyAction must not be null");
    }
}
