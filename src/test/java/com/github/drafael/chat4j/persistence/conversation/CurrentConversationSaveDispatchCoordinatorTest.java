package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentConversationSaveDispatchCoordinatorTest {

    private final CurrentConversationSaveDispatchCoordinator subject = new CurrentConversationSaveDispatchCoordinator();

    @Test
    @DisplayName("Save delegates to save action and UI apply action")
    void save_whenSuccessful_appliesResult() {
        UUID conversationId = UUID.randomUUID();
        var appliedResult = new AtomicReference<CurrentConversationSaveCoordinator.SaveResult>();

        subject.save(
                conversationId,
                List.of(Message.user("hello")),
                "OpenAI:gpt-4o",
                ReasoningLevel.OFF,
                false,
                null,
                (id, history, selectedModelKey, reasoningLevel, agentModeEnabled, agentProjectRoot) ->
                        new CurrentConversationSaveCoordinator.SaveResult(true, id, false),
                appliedResult::set,
                error -> {}
        );

        assertThat(appliedResult.get().conversationId()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("Save forwards failures")
    void save_whenSaveActionFails_forwardsFailure() {
        var failure = new AtomicReference<Exception>();

        subject.save(
                UUID.randomUUID(),
                List.of(),
                null,
                ReasoningLevel.OFF,
                false,
                null,
                (id, history, selectedModelKey, reasoningLevel, agentModeEnabled, agentProjectRoot) -> {
                    throw new IllegalStateException("boom");
                },
                result -> {},
                failure::set
        );

        assertThat(failure.get()).hasMessage("boom");
    }

    @Test
    @DisplayName("Save validates history")
    void save_whenHistoryMissing_throwsException() {
        assertThatThrownBy(() -> subject.save(UUID.randomUUID(), null, null, ReasoningLevel.OFF, false, null, (id, history, selectedModelKey, reasoningLevel, agentModeEnabled, agentProjectRoot) -> null, result -> {}, error -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("history");
    }
}
