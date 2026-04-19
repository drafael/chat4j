package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantMessageCompletionCoordinatorTest {

    @Test
    @DisplayName("Persist returns ignored result when conversation id is missing")
    void persist_whenConversationIdMissing_returnsIgnoredResult() throws Exception {
        AtomicInteger persistCalls = new AtomicInteger();
        var subject = new AssistantMessageCompletionCoordinator((conversationId, message) -> persistCalls.incrementAndGet());

        AssistantMessageCompletionCoordinator.PersistResult result = subject.persist(
                null,
                Message.assistant("done"),
                UUID.randomUUID()
        );

        assertThat(result.handled()).isFalse();
        assertThat(result.conversationIdToSelect()).isNull();
        assertThat(persistCalls.get()).isZero();
    }

    @Test
    @DisplayName("Persist returns ignored result when message is missing")
    void persist_whenMessageMissing_returnsIgnoredResult() throws Exception {
        AtomicInteger persistCalls = new AtomicInteger();
        var subject = new AssistantMessageCompletionCoordinator((conversationId, message) -> persistCalls.incrementAndGet());

        AssistantMessageCompletionCoordinator.PersistResult result = subject.persist(
                UUID.randomUUID(),
                null,
                UUID.randomUUID()
        );

        assertThat(result.handled()).isFalse();
        assertThat(result.conversationIdToSelect()).isNull();
        assertThat(persistCalls.get()).isZero();
    }

    @Test
    @DisplayName("Persist handles valid event and requests sidebar reselection for active conversation")
    void persist_whenConversationMatchesActive_returnsHandledWithConversationToSelect() throws Exception {
        AtomicInteger persistCalls = new AtomicInteger();
        AtomicReference<UUID> persistedConversationId = new AtomicReference<>();
        AtomicReference<Message> persistedMessage = new AtomicReference<>();
        var subject = new AssistantMessageCompletionCoordinator((conversationId, message) -> {
            persistCalls.incrementAndGet();
            persistedConversationId.set(conversationId);
            persistedMessage.set(message);
        });

        UUID conversationId = UUID.randomUUID();
        Message assistantMessage = Message.assistant("done");

        AssistantMessageCompletionCoordinator.PersistResult result = subject.persist(
                conversationId,
                assistantMessage,
                conversationId
        );

        assertThat(result.handled()).isTrue();
        assertThat(result.conversationIdToSelect()).isEqualTo(conversationId);
        assertThat(persistCalls.get()).isEqualTo(1);
        assertThat(persistedConversationId.get()).isEqualTo(conversationId);
        assertThat(persistedMessage.get()).isEqualTo(assistantMessage);
    }

    @Test
    @DisplayName("Persist handles valid event and skips reselection for different active conversation")
    void persist_whenConversationDiffersFromActive_returnsHandledWithoutConversationToSelect() throws Exception {
        AtomicInteger persistCalls = new AtomicInteger();
        var subject = new AssistantMessageCompletionCoordinator((conversationId, message) -> persistCalls.incrementAndGet());

        AssistantMessageCompletionCoordinator.PersistResult result = subject.persist(
                UUID.randomUUID(),
                Message.assistant("done"),
                UUID.randomUUID()
        );

        assertThat(result.handled()).isTrue();
        assertThat(result.conversationIdToSelect()).isNull();
        assertThat(persistCalls.get()).isEqualTo(1);
    }
}
