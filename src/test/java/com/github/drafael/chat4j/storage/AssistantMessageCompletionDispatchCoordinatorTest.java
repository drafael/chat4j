package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantMessageCompletionDispatchCoordinatorTest {

    @Test
    @DisplayName("Handle delegates to completion handler and returns its result")
    void handle_whenSuccessful_returnsCompletionResult() {
        var subject = new AssistantMessageCompletionDispatchCoordinator();
        UUID eventConversationId = UUID.randomUUID();
        UUID currentConversationId = UUID.randomUUID();
        Message eventMessage = Message.assistant("hello");
        var capturedConversationId = new AtomicReference<UUID>();
        var capturedCurrentConversationId = new AtomicReference<UUID>();
        var capturedMessage = new AtomicReference<Message>();

        boolean handled = subject.handle(
                eventConversationId,
                eventMessage,
                currentConversationId,
                (conversationId, message, activeConversationId) -> {
                    capturedConversationId.set(conversationId);
                    capturedMessage.set(message);
                    capturedCurrentConversationId.set(activeConversationId);
                    return true;
                },
                (conversationId, error) -> {
                }
        );

        assertThat(handled).isTrue();
        assertThat(capturedConversationId.get()).isEqualTo(eventConversationId);
        assertThat(capturedCurrentConversationId.get()).isEqualTo(currentConversationId);
        assertThat(capturedMessage.get()).isEqualTo(eventMessage);
    }

    @Test
    @DisplayName("Handle reports failure callback and returns false when completion throws")
    void handle_whenCompletionThrows_reportsFailureAndReturnsFalse() {
        var subject = new AssistantMessageCompletionDispatchCoordinator();
        UUID eventConversationId = UUID.randomUUID();
        var failures = new ArrayList<String>();

        boolean handled = subject.handle(
                eventConversationId,
                null,
                UUID.randomUUID(),
                (conversationId, message, activeConversationId) -> {
                    throw new IllegalStateException("boom");
                },
                (conversationId, error) -> failures.add(conversationId + ":" + error.getMessage())
        );

        assertThat(handled).isFalse();
        assertThat(failures).containsExactly(eventConversationId + ":boom");
    }

    @Test
    @DisplayName("Handle validates required callbacks")
    void handle_whenCallbackMissing_throwsException() {
        var subject = new AssistantMessageCompletionDispatchCoordinator();

        assertThatThrownBy(() -> subject.handle(
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                null,
                (conversationId, error) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("completionHandler must not be null");

        assertThatThrownBy(() -> subject.handle(
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                (conversationId, message, activeConversationId) -> true,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("failureHandler must not be null");
    }
}
