package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPersistenceCoordinatorTest {

    @Test
    @DisplayName("Persist history loads repository count once and writes only missing messages")
    void persistConversationHistory_whenRepositoryHasExistingMessages_persistsOnlyMissingMessages() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new FakeConversationRepo(conversationId, 1);
        var counter = new PersistedMessageCounter();
        var subject = new ConversationPersistenceCoordinator(repo, counter);

        List<Message> history = List.of(
                Message.user("one"),
                Message.user("two"),
                Message.assistant("three")
        );

        int persistedCount = subject.persistConversationHistory(conversationId, history);

        assertThat(persistedCount).isEqualTo(3);
        assertThat(repo.getMessagesCalls.get()).isEqualTo(1);
        assertThat(repo.addedMessages).containsExactly(history.get(1), history.get(2));
    }

    @Test
    @DisplayName("Creating conversation primes counter and avoids initial repository count lookup")
    void createConversation_whenCalled_primesCounterForSubsequentHistoryPersistence() throws Exception {
        UUID createdConversationId = UUID.randomUUID();
        var repo = new FakeConversationRepo(createdConversationId, 5);
        var counter = new PersistedMessageCounter();
        var subject = new ConversationPersistenceCoordinator(repo, counter);

        UUID conversationId = subject.createConversation("title", "OpenAI", "gpt-4.1");
        int persistedCount = subject.persistConversationHistory(conversationId, List.of(
                Message.user("one"),
                Message.assistant("two")
        ));

        assertThat(conversationId).isEqualTo(createdConversationId);
        assertThat(persistedCount).isEqualTo(2);
        assertThat(repo.getMessagesCalls.get()).isZero();
        assertThat(repo.addedMessages).hasSize(2);
    }

    @Test
    @DisplayName("Persisting assistant message increments known persisted count")
    void persistAssistantMessage_whenConversationWasLoaded_incrementsPersistedCounter() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new FakeConversationRepo(conversationId, 0);
        var counter = new PersistedMessageCounter();
        var subject = new ConversationPersistenceCoordinator(repo, counter);

        subject.markConversationLoaded(conversationId, 2);
        subject.persistAssistantMessage(conversationId, Message.assistant("hello"));

        int persistedCount = counter.resolve(conversationId, id -> 99);
        assertThat(persistedCount).isEqualTo(3);
        assertThat(repo.addedMessages).hasSize(1);
        assertThat(repo.addedMessages.getFirst().role()).isEqualTo(Role.ASSISTANT);
        assertThat(repo.addedMessages.getFirst().content()).isEqualTo("hello");
    }

    private static class FakeConversationRepo extends ConversationRepo {

        private final UUID createdConversationId;
        private final int existingMessageCount;
        private final AtomicInteger getMessagesCalls = new AtomicInteger();
        private final List<Message> addedMessages = new ArrayList<>();

        private FakeConversationRepo(UUID createdConversationId, int existingMessageCount) {
            super(null);
            this.createdConversationId = createdConversationId;
            this.existingMessageCount = existingMessageCount;
        }

        @Override
        public UUID createConversation(String title, String provider, String model) {
            return createdConversationId;
        }

        @Override
        public List<MessageRecord> getMessages(UUID conversationId) {
            getMessagesCalls.incrementAndGet();
            return IntStream.range(0, existingMessageCount)
                    .mapToObj(index -> new MessageRecord(
                            UUID.randomUUID(),
                            Message.user("existing-%d".formatted(index)),
                            LocalDateTime.now()
                    ))
                    .toList();
        }

        @Override
        public void addMessage(UUID conversationId, Message message) {
            addedMessages.add(message);
        }

        @Override
        public Optional<ConversationRecord> findById(UUID id) {
            return Optional.empty();
        }
    }
}
