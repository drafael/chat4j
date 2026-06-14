package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Persist if exists returns false when conversation is deleted before insert")
    void persistMessageIfConversationExists_whenConversationDisappearsDuringInsert_returnsFalse() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new DisappearingConversationRepo(conversationId);
        var counter = new PersistedMessageCounter();
        var subject = new ConversationPersistenceCoordinator(repo, counter);

        boolean persisted = subject.persistMessageIfConversationExists(conversationId, Message.user("hello"));

        assertThat(persisted).isFalse();
        assertThat(repo.addMessageCalls).hasValue(1);
    }

    @Test
    @DisplayName("Persist history stops without throwing when conversation is deleted during insert")
    void persistConversationHistory_whenConversationDisappearsDuringInsert_stopsPersistence() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repo = new DisappearingConversationRepo(conversationId);
        var counter = new PersistedMessageCounter();
        var subject = new ConversationPersistenceCoordinator(repo, counter);

        int persistedCount = subject.persistConversationHistory(conversationId, List.of(Message.user("hello")));

        assertThat(persistedCount).isZero();
        assertThat(repo.addMessageCalls).hasValue(1);
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

    private static class DisappearingConversationRepo extends FakeConversationRepo {

        private final AtomicInteger findCalls = new AtomicInteger();
        private final AtomicInteger addMessageCalls = new AtomicInteger();

        private DisappearingConversationRepo(UUID conversationId) {
            super(conversationId, 0);
        }

        @Override
        public void addMessage(UUID conversationId, Message message) throws SQLException {
            addMessageCalls.incrementAndGet();
            throw new SQLException("referential integrity constraint violation", "23506");
        }

        @Override
        public Optional<ConversationRecord> findById(UUID id) {
            return findCalls.incrementAndGet() == 1 ? super.findById(id) : Optional.empty();
        }
    }

    private static class FakeConversationRepo extends ConversationRepository {

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
        public void addMessage(UUID conversationId, Message message) throws SQLException {
            addedMessages.add(message);
        }

        @Override
        public Optional<ConversationRecord> findById(UUID id) {
            if (!createdConversationId.equals(id)) {
                return Optional.empty();
            }
            LocalDateTime now = LocalDateTime.now();
            return Optional.of(new ConversationRecord(
                    id,
                    "title",
                    "OpenAI",
                    "gpt-4.1",
                    false,
                    "off",
                    false,
                    null,
                    now,
                    now
            ));
        }
    }
}
