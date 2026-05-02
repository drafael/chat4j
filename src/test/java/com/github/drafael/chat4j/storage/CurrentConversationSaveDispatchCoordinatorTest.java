package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentConversationSaveDispatchCoordinatorTest {

    @Test
    @DisplayName("Save delegates to save action and UI apply action")
    void save_whenSuccessful_delegatesToSaveAndUiApply() {
        var subject = new CurrentConversationSaveDispatchCoordinator();
        UUID conversationId = UUID.randomUUID();
        var history = List.of(Message.user("hello"));
        var capturedConversationId = new AtomicReference<UUID>();
        var capturedSelectedModel = new AtomicReference<String>();
        var capturedReasoningLevel = new AtomicReference<ReasoningLevel>();
        var appliedSaveResult = new AtomicReference<CurrentConversationSaveCoordinator.SaveResult>();

        subject.save(
                conversationId,
                AssistantRenderMode.PREVIEW,
                history,
                "OpenAI > gpt-4.1",
                AssistantRenderMode.MARKDOWN,
                ReasoningLevel.HIGH,
                true,
                Path.of("/tmp/demo"),
                (currentConversationId,
                 pendingUnsavedConversationRenderMode,
                 loadedHistory,
                 selectedModelKey,
                 currentAssistantRenderMode,
                 reasoningLevel,
                 agentModeEnabled,
                 agentProjectRoot) -> {
                    capturedConversationId.set(currentConversationId);
                    capturedSelectedModel.set(selectedModelKey);
                    capturedReasoningLevel.set(reasoningLevel);
                    return new CurrentConversationSaveCoordinator.SaveResult(
                            true,
                            conversationId,
                            null,
                            false
                    );
                },
                appliedSaveResult::set,
                error -> {
                }
        );

        assertThat(capturedConversationId.get()).isEqualTo(conversationId);
        assertThat(capturedSelectedModel.get()).isEqualTo("OpenAI > gpt-4.1");
        assertThat(capturedReasoningLevel.get()).isEqualTo(ReasoningLevel.HIGH);
        assertThat(appliedSaveResult.get()).isNotNull();
        assertThat(appliedSaveResult.get().saved()).isTrue();
    }

    @Test
    @DisplayName("Save reports failure callback and skips UI apply when save action throws")
    void save_whenSaveThrows_reportsFailureAndSkipsUiApply() {
        var subject = new CurrentConversationSaveDispatchCoordinator();
        var failures = new ArrayList<String>();
        var appliedSaveResult = new AtomicReference<CurrentConversationSaveCoordinator.SaveResult>();

        subject.save(
                UUID.randomUUID(),
                null,
                emptyList(),
                null,
                AssistantRenderMode.PREVIEW,
                ReasoningLevel.OFF,
                false,
                null,
                (currentConversationId,
                 pendingUnsavedConversationRenderMode,
                 history,
                 selectedModelKey,
                 currentAssistantRenderMode,
                 reasoningLevel,
                 agentModeEnabled,
                 agentProjectRoot) -> {
                    throw new IllegalStateException("boom");
                },
                appliedSaveResult::set,
                error -> failures.add(error.getMessage())
        );

        assertThat(appliedSaveResult).hasValue(null);
        assertThat(failures).containsExactly("boom");
    }

    @Test
    @DisplayName("Save validates required arguments")
    void save_whenArgumentMissing_throwsException() {
        var subject = new CurrentConversationSaveDispatchCoordinator();

        assertThatThrownBy(() -> subject.save(
                UUID.randomUUID(),
                null,
                null,
                null,
                AssistantRenderMode.PREVIEW,
                ReasoningLevel.OFF,
                false,
                null,
                (currentConversationId,
                 pendingUnsavedConversationRenderMode,
                 history,
                 selectedModelKey,
                 currentAssistantRenderMode,
                 reasoningLevel,
                 agentModeEnabled,
                 agentProjectRoot) -> null,
                saveResult -> {
                },
                error -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("history is marked non-null");

        assertThatThrownBy(() -> subject.save(
                UUID.randomUUID(),
                null,
                emptyList(),
                null,
                AssistantRenderMode.PREVIEW,
                ReasoningLevel.OFF,
                false,
                null,
                null,
                saveResult -> {
                },
                error -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveAction is marked non-null");
    }
}
