package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralSettingsApplyCoordinatorTest {

    @Test
    @DisplayName("Apply resolves general settings and computes mode-to-apply")
    void apply_whenCalled_resolvesSettingsAndComputesModeToApply() {
        var capturedIsMacOs = new AtomicReference<Boolean>();
        var capturedCurrentConversationId = new AtomicReference<UUID>();
        var capturedConversationRenderMode = new AtomicReference<AssistantRenderMode>();
        var capturedPendingUnsavedMode = new AtomicReference<AssistantRenderMode>();
        var capturedDefaultMode = new AtomicReference<AssistantRenderMode>();

        var expectedSettings = new GeneralSettingsResolver.GeneralSettings(
                true,
                false,
                AssistantRenderMode.MARKDOWN,
                true
        );

        var subject = new GeneralSettingsApplyCoordinator(
                isMacOs -> {
                    capturedIsMacOs.set(isMacOs);
                    return expectedSettings;
                },
                (currentConversationId, conversationRenderMode, pendingUnsavedConversationRenderMode, defaultMode) -> {
                    capturedCurrentConversationId.set(currentConversationId);
                    capturedConversationRenderMode.set(conversationRenderMode);
                    capturedPendingUnsavedMode.set(pendingUnsavedConversationRenderMode);
                    capturedDefaultMode.set(defaultMode);
                    return AssistantRenderMode.PREVIEW;
                }
        );

        UUID conversationId = UUID.randomUUID();
        GeneralSettingsApplyCoordinator.ApplyResult result = subject.apply(
                true,
                conversationId,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW
        );

        assertThat(result.sendOnEnter()).isTrue();
        assertThat(result.autoScrollEnabled()).isFalse();
        assertThat(result.defaultAssistantRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(result.modeToApply()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(result.menuBarEnabled()).isTrue();

        assertThat(capturedIsMacOs.get()).isTrue();
        assertThat(capturedCurrentConversationId.get()).isEqualTo(conversationId);
        assertThat(capturedConversationRenderMode.get()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(capturedPendingUnsavedMode.get()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(capturedDefaultMode.get()).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Constructor validates required dependencies")
    void constructor_whenDependencyMissing_throwsException() {
        assertThatThrownBy(() -> new GeneralSettingsApplyCoordinator(null, (a, b, c, d) -> d))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsResolver must not be null");

        assertThatThrownBy(() -> new GeneralSettingsApplyCoordinator(isMacOs -> null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modeResolver must not be null");
    }
}
