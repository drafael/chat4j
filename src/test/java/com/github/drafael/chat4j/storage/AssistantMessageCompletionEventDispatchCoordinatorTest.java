package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantMessageCompletionEventDispatchCoordinatorTest {

    @Test
    @DisplayName("Handle resolves event fields and delegates to dispatch action")
    void handle_whenEventPresent_delegatesResolvedFields() {
        var capturedEventConversationId = new AtomicReference<UUID>();
        var capturedCurrentConversationId = new AtomicReference<UUID>();
        var capturedEventMessage = new AtomicReference<Message>();

        var subject = new AssistantMessageCompletionEventDispatchCoordinator((
                eventConversationId,
                eventMessage,
                currentConversationId,
                completionHandler,
                failureHandler
        ) -> {
            capturedEventConversationId.set(eventConversationId);
            capturedEventMessage.set(eventMessage);
            capturedCurrentConversationId.set(currentConversationId);
            return true;
        });

        UUID eventConversationId = UUID.randomUUID();
        UUID currentConversationId = UUID.randomUUID();
        Message eventMessage = Message.assistant("done");

        boolean handled = subject.handle(
                new ChatPanel.AssistantMessageEvent(eventConversationId, eventMessage),
                currentConversationId,
                (conversationId, message, activeConversationId) -> true,
                (conversationId, error) -> {
                }
        );

        assertThat(handled).isTrue();
        assertThat(capturedEventConversationId.get()).isEqualTo(eventConversationId);
        assertThat(capturedCurrentConversationId.get()).isEqualTo(currentConversationId);
        assertThat(capturedEventMessage.get()).isEqualTo(eventMessage);
    }

    @Test
    @DisplayName("Handle passes null event fields when event is absent")
    void handle_whenEventMissing_passesNullEventFields() {
        var capturedEventConversationId = new AtomicReference<UUID>();
        var capturedEventMessage = new AtomicReference<Message>();

        var subject = new AssistantMessageCompletionEventDispatchCoordinator((
                eventConversationId,
                eventMessage,
                currentConversationId,
                completionHandler,
                failureHandler
        ) -> {
            capturedEventConversationId.set(eventConversationId);
            capturedEventMessage.set(eventMessage);
            return false;
        });

        boolean handled = subject.handle(
                null,
                UUID.randomUUID(),
                (conversationId, message, activeConversationId) -> true,
                (conversationId, error) -> {
                }
        );

        assertThat(handled).isFalse();
        assertThat(capturedEventConversationId.get()).isNull();
        assertThat(capturedEventMessage.get()).isNull();
    }

    @Test
    @DisplayName("Handle validates required callbacks and constructor dependency")
    void handle_whenArgumentMissing_throwsException() {
        var subject = new AssistantMessageCompletionEventDispatchCoordinator((
                eventConversationId,
                eventMessage,
                currentConversationId,
                completionHandler,
                failureHandler
        ) -> true);

        assertThatThrownBy(() -> subject.handle(
                null,
                UUID.randomUUID(),
                null,
                (conversationId, error) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("completionHandler");

        assertThatThrownBy(() -> subject.handle(
                null,
                UUID.randomUUID(),
                (conversationId, message, activeConversationId) -> true,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("failureHandler");

        assertThatThrownBy(() -> new AssistantMessageCompletionEventDispatchCoordinator(
                (AssistantMessageCompletionEventDispatchCoordinator.DispatchAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dispatchAction");
    }
}
