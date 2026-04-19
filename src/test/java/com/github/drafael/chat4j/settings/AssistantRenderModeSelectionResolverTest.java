package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantRenderModeSelectionResolverTest {

    private final AssistantRenderModeSelectionResolver subject = new AssistantRenderModeSelectionResolver();

    @Test
    @DisplayName("Resolve uses conversation mode when a conversation is active")
    void resolve_whenConversationIsActive_usesConversationMode() {
        AssistantRenderMode mode = subject.resolve(
                UUID.fromString("80b8de82-1d63-4f4e-a534-f49cf9a015fb"),
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW,
                AssistantRenderMode.PREVIEW
        );

        assertThat(mode).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve falls back to default mode when active conversation mode is missing")
    void resolve_whenConversationIsActiveAndModeMissing_fallsBackToDefaultMode() {
        AssistantRenderMode mode = subject.resolve(
                UUID.fromString("4fba3668-5768-443d-9367-3f42abd0534f"),
                null,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW
        );

        assertThat(mode).isEqualTo(AssistantRenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Resolve uses pending unsaved mode when no conversation is active")
    void resolve_whenNoConversationAndPendingModeExists_usesPendingMode() {
        AssistantRenderMode mode = subject.resolve(
                null,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW
        );

        assertThat(mode).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve uses default mode when no conversation and no pending mode")
    void resolve_whenNoConversationAndNoPendingMode_usesDefaultMode() {
        AssistantRenderMode mode = subject.resolve(
                null,
                AssistantRenderMode.MARKDOWN,
                null,
                AssistantRenderMode.PREVIEW
        );

        assertThat(mode).isEqualTo(AssistantRenderMode.PREVIEW);
    }
}
