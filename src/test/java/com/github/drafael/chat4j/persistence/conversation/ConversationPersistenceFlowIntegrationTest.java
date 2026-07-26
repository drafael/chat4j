package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationPersistenceFlowIntegrationTest {

    @Test
    @DisplayName("Stable conversation entries survive coordinator save and repository load")
    void saveAndLoad_whenEntriesPersist_preservesIdentityOrderAndTimestamp() throws Exception {
        JdbcDataSource dataSource = dataSource("save-load");
        var repository = new ConversationRepository(dataSource);
        var subject = new ConversationPersistenceCoordinator(repository);
        UUID conversationId = UUID.randomUUID();
        Instant userTimestamp = Instant.parse("2026-07-23T12:34:56.789123Z");
        var userEntry = new ConversationHistoryEntry(
                UUID.randomUUID(),
                1,
                new Message(Role.USER, "hello", userTimestamp)
        );
        var assistantEntry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));

        subject.submitNewConversation(createCommand(conversationId, userEntry)).join();
        subject.submitAssistant(conversationId, assistantEntry).join();
        subject.fenceRevision(conversationId).join();

        ConversationRepository.LoadedConversation loaded = repository.loadConversation(conversationId).orElseThrow();
        assertThat(loaded.messages()).extracting(ConversationRepository.MessageRecord::id)
                .containsExactly(userEntry.messageId(), assistantEntry.messageId());
        assertThat(loaded.messages()).extracting(ConversationRepository.MessageRecord::ordinal)
                .containsExactly(1, 2);
        assertThat(loaded.messages().getFirst().message().timestamp())
                .isEqualTo(Instant.ofEpochMilli(userTimestamp.toEpochMilli()));
        subject.close();
    }

    @Test
    @DisplayName("Final sealing retries failed assistant entry behind accepted writes")
    void sealWithFinal_whenAssistantWriteFailed_persistsRecoveryBeforeTerminalCompletion() throws Exception {
        JdbcDataSource dataSource = dataSource("shutdown-recovery");
        var failFirstAssistant = new AtomicBoolean(true);
        ConversationRepository repository = new ConversationRepository(dataSource) {
            @Override
            public void appendMessage(UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
                if (entry.message().role() == Role.ASSISTANT
                        && failFirstAssistant.compareAndSet(true, false)
                ) {
                    throw new SQLException("forced assistant failure");
                }
                super.appendMessage(conversationId, entry);
            }
        };
        var subject = new ConversationPersistenceCoordinator(repository);
        UUID conversationId = UUID.randomUUID();
        var userEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("hello"));
        var assistantEntry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        subject.submitNewConversation(createCommand(conversationId, userEntry)).join();

        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistantEntry).join())
                .hasRootCauseMessage("forced assistant failure");
        subject.sealWithFinal().join();

        assertThat(repository.loadConversation(conversationId).orElseThrow().messages())
                .extracting(ConversationRepository.MessageRecord::id)
                .containsExactly(userEntry.messageId(), assistantEntry.messageId());
    }

    private ConversationRepository.CreateConversationCommand createCommand(
            UUID conversationId,
            ConversationHistoryEntry firstEntry
    ) {
        return new ConversationRepository.CreateConversationCommand(
                conversationId,
                "Conversation",
                "OpenAI",
                "gpt-4o",
                ReasoningLevel.OFF,
                false,
                null,
                false,
                null,
                firstEntry
        );
    }

    private JdbcDataSource dataSource(String name) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .load()
                .migrate();
        return dataSource;
    }
}
