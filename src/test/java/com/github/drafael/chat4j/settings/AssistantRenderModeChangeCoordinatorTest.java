package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantRenderModeChangeCoordinatorTest {

    @Test
    @DisplayName("Apply ignores null mode and preserves pending unsaved mode")
    void apply_whenModeNull_ignoresAndKeepsPendingUnsavedMode() {
        var persisterCalls = new AtomicInteger();
        var subject = new AssistantRenderModeChangeCoordinator(
                new AssistantRenderModeChangePlanner(),
                (conversationId, mode) -> persisterCalls.incrementAndGet()
        );

        var result = subject.apply(UUID.randomUUID(), null, AssistantRenderMode.MARKDOWN);

        assertThat(result.handled()).isFalse();
        assertThat(result.pendingUnsavedConversationRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(persisterCalls.get()).isZero();
    }

    @Test
    @DisplayName("Apply persists mode when conversation is active")
    void apply_whenConversationActive_persistsModeAndKeepsPendingUnsavedMode() {
        var persistedConversationId = new AtomicReference<UUID>();
        var persistedMode = new AtomicReference<AssistantRenderMode>();
        var subject = new AssistantRenderModeChangeCoordinator(
                new AssistantRenderModeChangePlanner(),
                (conversationId, mode) -> {
                    persistedConversationId.set(conversationId);
                    persistedMode.set(mode);
                }
        );

        UUID currentConversationId = UUID.randomUUID();
        var result = subject.apply(currentConversationId, AssistantRenderMode.PREVIEW, AssistantRenderMode.MARKDOWN);

        assertThat(result.handled()).isTrue();
        assertThat(result.pendingUnsavedConversationRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(persistedConversationId.get()).isEqualTo(currentConversationId);
        assertThat(persistedMode.get()).isEqualTo(AssistantRenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Apply updates pending unsaved mode when no conversation is active")
    void apply_whenNoConversationActive_setsPendingUnsavedMode() {
        var persisterCalls = new AtomicInteger();
        var subject = new AssistantRenderModeChangeCoordinator(
                new AssistantRenderModeChangePlanner(),
                (conversationId, mode) -> persisterCalls.incrementAndGet()
        );

        var result = subject.apply(null, AssistantRenderMode.MARKDOWN, AssistantRenderMode.PREVIEW);

        assertThat(result.handled()).isTrue();
        assertThat(result.pendingUnsavedConversationRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(persisterCalls.get()).isZero();
    }

    @Test
    @DisplayName("Constructor validates required collaborators")
    void constructor_whenDependencyMissing_throwsException() {
        assertThatThrownBy(() -> new AssistantRenderModeChangeCoordinator(
                null,
                (conversationId, mode) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("assistantRenderModeChangePlanner must not be null");

        assertThatThrownBy(() -> new AssistantRenderModeChangeCoordinator(
                new AssistantRenderModeChangePlanner(),
                (AssistantRenderModeChangeCoordinator.ConversationModePersister) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationModePersister must not be null");
    }
}
