package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationRepositoryTest {

    @Test
    @DisplayName("Message insert is rolled back when attachment persistence fails")
    void addMessage_whenAttachmentPersistenceFails_rollsBackInsertedMessage() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-rollback");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);

        Message message = Message.user(List.of(
                new TextPart("hello"),
                new FilePart(new AttachmentRef(
                        UUID.randomUUID(),
                        "x".repeat(1300),
                        "oversized-path.txt",
                        "text/plain",
                        10,
                        "sha"
                ))
        ));

        assertThatThrownBy(() -> appendMessage(subject, conversationId, message))
                .isInstanceOf(SQLException.class);

        assertThat(countRows(dataSource, "messages")).isZero();
        assertThat(countRows(dataSource, "attachments")).isZero();
        assertThat(countRows(dataSource, "message_attachments")).isZero();
    }

    @Test
    @DisplayName("Message insert rejects attachment parts without durable identity")
    void appendMessage_whenAttachmentReferenceIsIncomplete_rollsBackMessage() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-invalid-attachment");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        var subject = new ConversationRepository(dataSource);
        Message message = Message.user(List.of(new FilePart(new AttachmentRef(
                null,
                "/tmp/incomplete.txt",
                "incomplete.txt",
                "text/plain",
                1,
                "sha"
        ))));

        assertThatThrownBy(() -> subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), 1, message)
        ))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("require an ID and storage path");

        assertThat(countRows(dataSource, "messages")).isZero();
        assertThat(countRows(dataSource, "attachments")).isZero();
    }

    @Test
    @DisplayName("Startup attachment cleanup removes unlinked files and retains durable files")
    void cleanupUnreferencedManagedAttachmentFiles_whenFilesAreUnlinked_removesOnlyOrphans(
            @TempDir Path tempDir
    ) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-startup-attachment-cleanup");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path attachmentRoot = Files.createDirectories(tempDir.resolve("attachments"));
        Path dayDirectory = Files.createDirectories(attachmentRoot.resolve("20260725"));
        Path linkedFile = Files.writeString(dayDirectory.resolve(UUID.randomUUID().toString()), "linked");
        Path orphanFile = Files.writeString(dayDirectory.resolve(UUID.randomUUID().toString()), "orphan");
        Path freshUnlinkedFile = Files.writeString(dayDirectory.resolve(UUID.randomUUID().toString()), "fresh");
        Files.setLastModifiedTime(orphanFile, FileTime.from(Instant.now().minusSeconds(172_800)));
        var subject = new ConversationRepository(dataSource, attachmentRoot);
        Message message = Message.user(List.of(new FilePart(new AttachmentRef(
                UUID.randomUUID(),
                linkedFile.toString(),
                "linked.txt",
                "text/plain",
                Files.size(linkedFile),
                "sha"
        ))));
        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), 1, message)
        );

        subject.cleanupUnreferencedManagedAttachmentFiles();

        assertThat(linkedFile).exists();
        assertThat(orphanFile).doesNotExist();
        assertThat(freshUnlinkedFile).exists();
    }

    @Test
    @DisplayName("Message insert rejects attachment paths outside the configured managed root")
    void appendMessage_whenAttachmentIsOutsideManagedRoot_rollsBackMessage(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-outside-attachment-root");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path attachmentRoot = Files.createDirectories(tempDir.resolve("attachments"));
        Path outsideFile = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        var subject = new ConversationRepository(dataSource, attachmentRoot);
        Message message = Message.user(List.of(new FilePart(new AttachmentRef(
                UUID.randomUUID(),
                outsideFile.toString(),
                "outside.txt",
                "text/plain",
                Files.size(outsideFile),
                "sha"
        ))));

        assertThatThrownBy(() -> appendMessage(subject, conversationId, message))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("managed root");

        assertThat(countRows(dataSource, "messages")).isZero();
        assertThat(countRows(dataSource, "attachments")).isZero();
    }

    @Test
    @DisplayName("Successful message insert persists message and attachment links")
    void addMessage_whenAttachmentPersistenceSucceeds_persistsMessageAndAttachments() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-success");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);

        Message message = Message.user(List.of(
                new TextPart("hello"),
                new FilePart(new AttachmentRef(
                        UUID.randomUUID(),
                        "/tmp/demo.txt",
                        "demo.txt",
                        "text/plain",
                        10,
                        "sha"
                ))
        ));

        appendMessage(subject, conversationId, message);

        assertThat(countRows(dataSource, "messages")).isEqualTo(1);
        assertThat(countRows(dataSource, "attachments")).isEqualTo(1);
        assertThat(countRows(dataSource, "message_attachments")).isEqualTo(1);
    }

    @Test
    @DisplayName("Canonical attachment paths make stable message retries idempotent")
    void appendMessage_whenAttachmentPathSpellingDiffers_acceptsCanonicalRetry(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-canonical-attachment-path");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path file = createAttachmentFile(tempDir, "canonical.txt");
        UUID attachmentId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var firstRef = new AttachmentRef(
                attachmentId,
                file.getParent().resolve(".").resolve(file.getFileName()).toString(),
                "canonical.txt",
                "text/plain",
                Files.size(file),
                "sha"
        );
        var retryRef = new AttachmentRef(
                attachmentId,
                file.toRealPath().toString(),
                "canonical.txt",
                "text/plain",
                Files.size(file),
                "sha"
        );
        var subject = new ConversationRepository(dataSource, tempDir);
        Message firstMessage = Message.user(List.of(new FilePart(firstRef)));
        Message retryMessage = new Message(
                firstMessage.role(),
                List.of(new FilePart(retryRef)),
                firstMessage.timestamp(),
                firstMessage.meta()
        );

        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(messageId, 1, firstMessage)
        );
        Message loadedMessage = subject.loadConversation(conversationId).orElseThrow().messages().getFirst().message();
        var codec = new ConversationMessageJsonCodec();
        assertThat(codec.serializeParts(loadedMessage.parts())).isEqualTo(codec.serializeParts(retryMessage.parts()));
        assertThat(codec.serializeMeta(loadedMessage.meta())).isEqualTo(codec.serializeMeta(retryMessage.meta()));
        assertThat(loadedMessage.timestamp().toEpochMilli()).isEqualTo(retryMessage.timestamp().toEpochMilli());
        assertThat(subject.isCanonicalEntry(
                conversationId,
                new ConversationHistoryEntry(messageId, 1, retryMessage)
        )).isTrue();
        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(messageId, 1, retryMessage)
        );

        assertThat(countRows(dataSource, "messages")).isEqualTo(1);
        assertThat(countRows(dataSource, "attachments")).isEqualTo(1);
        assertThat(countRows(dataSource, "message_attachments")).isEqualTo(1);
    }

    @Test
    @DisplayName("Canonical retry rejects a stale searchable content column")
    void isCanonicalEntry_whenStoredContentDisagreesWithJson_returnsFalse() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-stale-search-content");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("canonical content"));
        var subject = new ConversationRepository(dataSource);
        subject.appendMessage(conversationId, entry);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE messages SET content = ? WHERE id = ?")) {
            statement.setString(1, "stale search content");
            statement.setObject(2, entry.messageId());
            statement.executeUpdate();
        }

        assertThat(subject.isCanonicalEntry(conversationId, entry)).isFalse();
        assertThatThrownBy(() -> subject.appendMessage(conversationId, entry))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Conflicting message identity");
    }

    @Test
    @DisplayName("Retrying a stable message ID against another conversation is rejected")
    void appendMessage_whenMessageIdBelongsToAnotherConversation_rejectsConflict() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-cross-conversation-message-id");
        createSchema(dataSource);
        UUID firstConversationId = insertConversation(dataSource);
        UUID secondConversationId = insertConversation(dataSource);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("hello"));
        var subject = new ConversationRepository(dataSource);
        subject.appendMessage(firstConversationId, entry);

        assertThatThrownBy(() -> subject.appendMessage(secondConversationId, entry))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("does not belong to conversation");
        assertThat(countRows(dataSource, "messages")).isEqualTo(1);
    }

    @Test
    @DisplayName("Retrying conversation creation with conflicting metadata is rejected")
    void createConversation_whenStableIdentityHasConflictingMetadata_rejectsConflict() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-create-metadata-conflict");
        createSchema(dataSource);
        UUID conversationId = UUID.randomUUID();
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("hello"));
        var subject = new ConversationRepository(dataSource);
        var original = new ConversationRepository.CreateConversationCommand(
                conversationId,
                "Original",
                "OpenAI",
                "gpt-4.1",
                ReasoningLevel.OFF,
                false,
                null,
                false,
                null,
                entry
        );
        subject.createConversation(original);
        var conflicting = new ConversationRepository.CreateConversationCommand(
                conversationId,
                "Changed",
                "OpenAI",
                "gpt-4.1",
                ReasoningLevel.OFF,
                false,
                null,
                false,
                null,
                entry
        );

        assertThatThrownBy(() -> subject.createConversation(conflicting))
                .isInstanceOf(SQLException.class);
        assertThat(countRows(dataSource, "conversations")).isEqualTo(1);
        assertThat(countRows(dataSource, "messages")).isEqualTo(1);
    }

    @Test
    @DisplayName("Nullable conversation booleans do not satisfy canonical create metadata")
    void isCanonicalCreate_whenStoredBooleansAreNull_rejectsDesiredFalseValues() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-null-create-booleans");
        createSchema(dataSource);
        UUID conversationId = UUID.randomUUID();
        var command = new ConversationRepository.CreateConversationCommand(
                conversationId,
                "Original",
                "OpenAI",
                "gpt-4.1",
                ReasoningLevel.OFF,
                false,
                null,
                false,
                null,
                new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("hello"))
        );
        var subject = new ConversationRepository(dataSource);
        subject.createConversation(command);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE conversations SET agent_mode_enabled = NULL, web_search_enabled = NULL WHERE id = ?"
             )) {
            statement.setObject(1, conversationId);
            statement.executeUpdate();
        }

        assertThat(subject.isCanonicalCreate(command)).isFalse();
    }

    @Test
    @DisplayName("Retrying canonical first-after-clear remains idempotent after a suffix exists")
    void appendFirstAfterClear_whenCanonicalSuffixExists_preservesConversation() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-first-after-clear-suffix");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        var firstEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("first"));
        var command = new ConversationRepository.FirstAfterClearCommand(conversationId, "First", firstEntry);
        var subject = new ConversationRepository(dataSource);
        subject.appendFirstAfterClear(command);
        assertThat(subject.loadConversation(conversationId).orElseThrow().conversation().title()).isEqualTo("First");
        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"))
        );

        subject.appendFirstAfterClear(command);

        assertThat(subject.loadConversation(conversationId).orElseThrow().messages()).hasSize(2);
    }

    @Test
    @DisplayName("Explicit edit replaces durable attachment links and removes the old orphan")
    void updateMessage_whenAttachmentLinksChange_replacesLinksTransactionally(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-edit-attachment-links");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path firstFile = createAttachmentFile(tempDir, "first.txt");
        Path secondFile = createAttachmentFile(tempDir, "second.txt");
        UUID messageId = UUID.randomUUID();
        var original = new ConversationHistoryEntry(
                messageId,
                1,
                Message.user(List.of(new TextPart("before"), new FilePart(attachmentRef(firstFile))))
        );
        var subject = new ConversationRepository(dataSource, tempDir);
        subject.appendMessage(conversationId, original);
        var replacement = new ConversationHistoryEntry(
                messageId,
                1,
                Message.user(List.of(new TextPart("after"), new FilePart(attachmentRef(secondFile))))
        );

        subject.updateMessage(conversationId, replacement);

        ConversationRepository.MessageRecord persisted = subject.loadConversation(conversationId).orElseThrow().messages().getFirst();
        assertThat(persisted.message().content()).startsWith("after");
        assertThat(countRows(dataSource, "attachments")).isEqualTo(1);
        assertThat(countRows(dataSource, "message_attachments")).isEqualTo(1);
        assertThat(firstFile).doesNotExist();
        assertThat(secondFile).exists();
    }

    @Test
    @DisplayName("Failed attachment-link replacement rolls back the original message and link")
    void updateMessage_whenReplacementAttachmentConflicts_rollsBackOriginalLink(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-edit-attachment-rollback");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path originalFile = createAttachmentFile(tempDir, "original.txt");
        Path conflictingFile = createAttachmentFile(tempDir, "conflicting.txt");
        AttachmentRef originalRef = attachmentRef(originalFile);
        UUID conflictingId = UUID.randomUUID();
        var existingMetadata = new AttachmentRef(
                conflictingId,
                conflictingFile.toRealPath().toString(),
                "existing.txt",
                "text/plain",
                Files.size(conflictingFile),
                "existing-sha"
        );
        var conflictingMetadata = new AttachmentRef(
                conflictingId,
                conflictingFile.toRealPath().toString(),
                "changed.txt",
                "text/plain",
                Files.size(conflictingFile),
                "changed-sha"
        );
        var subject = new ConversationRepository(dataSource, tempDir);
        UUID originalMessageId = UUID.randomUUID();
        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(
                        originalMessageId,
                        1,
                        Message.user(List.of(new TextPart("before"), new FilePart(originalRef)))
                )
        );
        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(
                        UUID.randomUUID(),
                        2,
                        Message.assistant(List.of(new TextPart("holder"), new FilePart(existingMetadata)))
                )
        );
        var replacement = new ConversationHistoryEntry(
                originalMessageId,
                1,
                Message.user(List.of(new TextPart("after"), new FilePart(conflictingMetadata)))
        );

        assertThatThrownBy(() -> subject.updateMessage(conversationId, replacement))
                .isInstanceOf(SQLException.class);

        ConversationRepository.MessageRecord persisted = subject.loadConversation(conversationId)
                .orElseThrow()
                .messages()
                .getFirst();
        assertThat(persisted.message().content()).startsWith("before");
        assertThat(countRows(dataSource, "attachments")).isEqualTo(2);
        assertThat(countRows(dataSource, "message_attachments")).isEqualTo(2);
        assertThat(originalFile).exists();
        assertThat(conflictingFile).exists();
    }

    @Test
    @DisplayName("Nullable boolean metadata does not satisfy an explicit false postcondition")
    void metadataPostconditions_whenStoredBooleansAreNull_rejectFalseDesiredValues() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-null-booleans");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE conversations SET is_favorite = NULL, agent_mode_enabled = NULL, "
                             + "web_search_enabled = NULL WHERE id = ?"
             )) {
            statement.setObject(1, conversationId);
            statement.executeUpdate();
        }
        var subject = new ConversationRepository(dataSource);

        assertThat(subject.hasFavorite(conversationId, false)).isFalse();
        assertThat(subject.hasAgentSettings(conversationId, false, null)).isFalse();
        assertThat(subject.hasWebSearchSettings(conversationId, false, null)).isFalse();
    }

    @Test
    @DisplayName("A nullable stored attachment size does not match an explicit zero-byte reference")
    void appendMessage_whenStoredAttachmentSizeIsNull_rejectsZeroByteReference(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-null-attachment-size");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path emptyFile = Files.createFile(tempDir.resolve("empty.txt"));
        UUID attachmentId = UUID.randomUUID();
        var attachmentRef = new AttachmentRef(
                attachmentId,
                emptyFile.toRealPath().toString(),
                "empty.txt",
                "text/plain",
                0,
                "empty-sha"
        );
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) "
                             + "VALUES (?, ?, ?, ?, NULL, ?)"
             )) {
            statement.setObject(1, attachmentId);
            statement.setString(2, attachmentRef.storagePath());
            statement.setString(3, attachmentRef.originalName());
            statement.setString(4, attachmentRef.mimeType());
            statement.setString(5, attachmentRef.sha256());
            statement.executeUpdate();
        }
        var subject = new ConversationRepository(dataSource, tempDir);
        var entry = new ConversationHistoryEntry(
                UUID.randomUUID(),
                1,
                Message.user(List.of(new TextPart("empty"), new FilePart(attachmentRef)))
        );

        assertThatThrownBy(() -> subject.appendMessage(conversationId, entry))
                .isInstanceOf(SQLException.class);
        assertThat(countRows(dataSource, "message_attachments")).isZero();
    }

    @Test
    @DisplayName("Clearing one attachment identity preserves a file linked through a legacy path alias")
    void clearMessages_whenAnotherAttachmentUsesCanonicalPathAlias_preservesSharedFile(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-shared-attachment-path");
        createSchema(dataSource);
        UUID clearedConversationId = insertConversation(dataSource);
        UUID retainedConversationId = insertConversation(dataSource);
        Path sharedFile = createAttachmentFile(tempDir, "shared-path.txt");
        AttachmentRef firstRef = attachmentRef(sharedFile);
        AttachmentRef secondRef = new AttachmentRef(
                UUID.randomUUID(),
                firstRef.storagePath(),
                firstRef.originalName(),
                firstRef.mimeType(),
                firstRef.sizeBytes(),
                firstRef.sha256()
        );
        var subject = new ConversationRepository(dataSource, tempDir);
        subject.appendMessage(
                clearedConversationId,
                new ConversationHistoryEntry(
                        UUID.randomUUID(),
                        1,
                        Message.user(List.of(new FilePart(firstRef)))
                )
        );
        subject.appendMessage(
                retainedConversationId,
                new ConversationHistoryEntry(
                        UUID.randomUUID(),
                        1,
                        Message.user(List.of(new FilePart(secondRef)))
                )
        );
        Path aliasDirectory = Files.createDirectories(sharedFile.getParent().resolve("alias"));
        String aliasPath = aliasDirectory.resolve("..").resolve(sharedFile.getFileName()).toString();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE attachments SET storage_path = ? WHERE id = ?"
             )) {
            statement.setString(1, aliasPath);
            statement.setObject(2, secondRef.id());
            statement.executeUpdate();
        }

        subject.clearMessages(clearedConversationId);

        assertThat(sharedFile).exists();
        assertThat(countRows(dataSource, "attachments")).isEqualTo(1);
        assertThat(countRows(dataSource, "message_attachments")).isEqualTo(1);
    }

    @Test
    @DisplayName("Clearing a conversation preserves unrelated pre-existing orphan attachments")
    void clearMessages_whenUnrelatedOrphanExists_deletesOnlyResultingOrphan(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-exact-orphan-cleanup");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path linkedFile = createAttachmentFile(tempDir, "linked.txt");
        Path unrelatedFile = createAttachmentFile(tempDir, "unrelated.txt");
        AttachmentRef linkedRef = attachmentRef(linkedFile);
        AttachmentRef unrelatedRef = attachmentRef(unrelatedFile);
        var subject = new ConversationRepository(dataSource, tempDir);
        subject.appendMessage(
                conversationId,
                new ConversationHistoryEntry(
                        UUID.randomUUID(),
                        1,
                        Message.user(List.of(new TextPart("linked"), new FilePart(linkedRef)))
                )
        );
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?)"
             )) {
            statement.setObject(1, unrelatedRef.id());
            statement.setString(2, unrelatedRef.storagePath());
            statement.setString(3, unrelatedRef.originalName());
            statement.setString(4, unrelatedRef.mimeType());
            statement.setLong(5, unrelatedRef.sizeBytes());
            statement.setString(6, unrelatedRef.sha256());
            statement.executeUpdate();
        }

        subject.clearMessages(conversationId);

        assertThat(countRows(dataSource, "attachments")).isEqualTo(1);
        assertThat(linkedFile).doesNotExist();
        assertThat(unrelatedFile).exists();
    }

    @Test
    @DisplayName("Satisfied clear and truncate retries do not change conversation recency")
    void contentMutation_whenPostconditionAlreadySatisfied_preservesUpdatedAt() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-idempotent-recency");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        var subject = new ConversationRepository(dataSource);
        LocalDateTime marker = LocalDateTime.of(2001, 2, 3, 4, 5, 6);
        setUpdatedAt(dataSource, conversationId, marker);

        subject.clearMessages(conversationId);

        assertThat(readUpdatedAt(dataSource, conversationId)).isEqualTo(marker);
        var retained = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("retained"));
        subject.appendMessage(conversationId, retained);
        setUpdatedAt(dataSource, conversationId, marker);

        subject.truncateAfter(conversationId, retained.messageId(), retained.ordinal());

        assertThat(readUpdatedAt(dataSource, conversationId)).isEqualTo(marker);
    }

    @Test
    @DisplayName("Assistant thinking metadata is serialized into message meta JSON")
    void addMessage_whenAssistantContainsThinking_persistsAssistantThinkingMeta() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-thinking-meta");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);

        Message message = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("answer")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "Thinking trace")
        );

        appendMessage(subject, conversationId, message);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT meta_json FROM messages LIMIT 1");
             ResultSet rs = statement.executeQuery()
        ) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("\"assistantThinking\":\"Thinking trace\"");
        }
    }

    @Test
    @DisplayName("Assistant agent tool activity metadata is serialized and restored")
    void addMessage_whenAssistantContainsAgentToolActivities_persistsAndRestoresMeta() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-agent-tool-meta");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);
        List<AgentToolActivityMeta> toolActivities = List.of(
                new AgentToolActivityMeta("read-note", "read", "SUCCEEDED", "path=note.txt", ""),
                new AgentToolActivityMeta("grep-error", "grep", "FAILED", "path=., query=todo", "no matches")
        );
        Message message = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("answer")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "", "", toolActivities)
        );

        appendMessage(subject, conversationId, message);

        Message restored = subject.loadConversation(conversationId).orElseThrow().messages().getFirst().message();
        assertThat(restored.meta().agentToolActivities()).containsExactlyElementsOf(toolActivities);
    }

    @Test
    @DisplayName("Deleting messages keeps the conversation row")
    void deleteMessages_whenConversationHasMessages_removesMessagesOnly() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-delete-messages");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);
        appendMessage(subject, conversationId, Message.user("first"));
        appendMessage(subject, conversationId, Message.assistant("second"));

        subject.clearMessages(conversationId);

        assertThat(countRows(dataSource, "conversations")).isEqualTo(1);
        assertThat(countRows(dataSource, "messages")).isZero();
        assertThat(subject.loadConversation(conversationId).map(ConversationRepository.LoadedConversation::conversation)).isPresent();
    }

    @Test
    @DisplayName("Deleting messages removes unreferenced attachment files")
    void deleteMessages_whenMessagesHaveAttachments_deletesAttachmentFiles(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-delete-message-attachments");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path attachmentFile = createAttachmentFile(tempDir, "message.txt");

        ConversationRepository subject = new ConversationRepository(dataSource, tempDir);
        appendMessage(subject, conversationId, Message.user(List.of(
                new TextPart("attached"),
                new FilePart(attachmentRef(attachmentFile))
        )));

        subject.clearMessages(conversationId);

        assertThat(countRows(dataSource, "messages")).isZero();
        assertThat(countRows(dataSource, "attachments")).isZero();
        assertThat(countRows(dataSource, "message_attachments")).isZero();
        assertThat(attachmentFile).doesNotExist();
    }

    @Test
    @DisplayName("Deleting conversations removes unreferenced attachment files")
    void deleteConversation_whenConversationHasAttachments_deletesAttachmentFiles(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-delete-conversation-attachments");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path attachmentFile = createAttachmentFile(tempDir, "conversation.txt");

        ConversationRepository subject = new ConversationRepository(dataSource, tempDir);
        appendMessage(subject, conversationId, Message.user(List.of(new FilePart(attachmentRef(attachmentFile)))));

        subject.deleteConversations(List.of(conversationId));

        assertThat(countRows(dataSource, "conversations")).isZero();
        assertThat(countRows(dataSource, "attachments")).isZero();
        assertThat(countRows(dataSource, "message_attachments")).isZero();
        assertThat(attachmentFile).doesNotExist();
    }

    @Test
    @DisplayName("Deleting messages keeps attachment files still referenced by remaining messages")
    void deleteMessages_whenAttachmentIsStillReferenced_keepsFile(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-delete-keeps-referenced-attachments");
        createSchema(dataSource);
        UUID firstConversationId = insertConversation(dataSource);
        UUID secondConversationId = insertConversation(dataSource);
        Path sharedAttachment = createAttachmentFile(tempDir, "shared.txt");
        AttachmentRef attachmentRef = attachmentRef(sharedAttachment);

        ConversationRepository subject = new ConversationRepository(dataSource, tempDir);
        appendMessage(subject, firstConversationId, Message.user(List.of(new FilePart(attachmentRef))));
        appendMessage(subject, secondConversationId, Message.user(List.of(new FilePart(attachmentRef))));

        subject.clearMessages(firstConversationId);

        assertThat(countRows(dataSource, "attachments")).isEqualTo(1);
        assertThat(sharedAttachment).exists();
    }

    @Test
    @DisplayName("Updating reasoning level persists per-conversation reasoning mode")
    void updateReasoningLevel_whenConversationExists_persistsValue() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-reasoning-level");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);
        subject.updateReasoningLevel(conversationId, ReasoningLevel.EXTRA_HIGH);

        ConversationRepository.ConversationRecord conversation = subject.loadConversation(conversationId).map(ConversationRepository.LoadedConversation::conversation).orElseThrow();
        assertThat(conversation.reasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH.toSettingValue());

        subject.updateReasoningLevel(conversationId, null);
        ConversationRepository.ConversationRecord updated = subject.loadConversation(conversationId).map(ConversationRepository.LoadedConversation::conversation).orElseThrow();
        assertThat(updated.reasoningLevel()).isEqualTo(ReasoningLevel.OFF.toSettingValue());
    }

    @Test
    @DisplayName("Updating agent settings persists per-conversation mode and root")
    void updateAgentSettings_whenConversationExists_persistsValues() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-agent-settings");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);
        subject.updateAgentSettings(conversationId, true, Path.of("/tmp/workspace"));

        ConversationRepository.ConversationRecord conversation = subject.loadConversation(conversationId).map(ConversationRepository.LoadedConversation::conversation).orElseThrow();
        assertThat(conversation.agentModeEnabled()).isTrue();
        assertThat(conversation.agentProjectRoot()).isEqualTo(Path.of("/tmp/workspace").toAbsolutePath().normalize().toString());

        subject.updateAgentSettings(conversationId, true, null);
        ConversationRepository.ConversationRecord updated = subject.loadConversation(conversationId).map(ConversationRepository.LoadedConversation::conversation).orElseThrow();
        assertThat(updated.agentModeEnabled()).isFalse();
        assertThat(updated.agentProjectRoot()).isNull();
    }

    private static Path createAttachmentFile(Path attachmentRoot, String fileName) throws Exception {
        Path attachmentFile = attachmentRoot.resolve("20260515").resolve(fileName);
        Files.createDirectories(attachmentFile.getParent());
        Files.writeString(attachmentFile, "attachment");
        return attachmentFile;
    }

    private static void appendMessage(
            ConversationRepository repository,
            UUID conversationId,
            Message message
    ) throws Exception {
        int ordinal = repository.loadConversation(conversationId).orElseThrow().messages().size() + 1;
        repository.appendMessage(
                conversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), ordinal, message)
        );
    }

    private static AttachmentRef attachmentRef(Path attachmentFile) throws Exception {
        return new AttachmentRef(
                UUID.randomUUID(),
                attachmentFile.toRealPath().toString(),
                attachmentFile.getFileName().toString(),
                "text/plain",
                Files.size(attachmentFile),
                "sha"
        );
    }

    private static DataSource createDataSource(String dbName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(dbName));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createSchema(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, """
                    CREATE TABLE IF NOT EXISTS conversations (
                        id UUID PRIMARY KEY,
                        title VARCHAR(255),
                        provider VARCHAR(50),
                        model VARCHAR(100),
                        is_favorite BOOLEAN DEFAULT FALSE,
                        reasoning_level VARCHAR(20) DEFAULT 'off',
                        agent_mode_enabled BOOLEAN DEFAULT FALSE,
                        agent_project_root VARCHAR(1024),
                        web_search_enabled BOOLEAN DEFAULT FALSE,
                        web_search_option VARCHAR(80),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            execute(connection, """
                    CREATE TABLE IF NOT EXISTS messages (
                        id UUID NOT NULL PRIMARY KEY,
                        conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                        role VARCHAR(10) NOT NULL,
                        content CLOB,
                        content_json CLOB,
                        meta_json CLOB,
                        created_at BIGINT NOT NULL,
                        ordinal INT NOT NULL CHECK (ordinal > 0),
                        UNIQUE (conversation_id, ordinal)
                    )
                    """);

            execute(connection, """
                    CREATE TABLE IF NOT EXISTS attachments (
                        id UUID PRIMARY KEY,
                        storage_path VARCHAR(1024) NOT NULL,
                        original_name VARCHAR(255),
                        mime_type VARCHAR(120),
                        size_bytes BIGINT DEFAULT 0,
                        sha256 VARCHAR(64),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            execute(connection, """
                    CREATE TABLE IF NOT EXISTS message_attachments (
                        message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                        attachment_id UUID NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
                        part_index INT NOT NULL,
                        PRIMARY KEY (message_id, part_index)
                    )
                    """);
        }
    }

    private static UUID insertConversation(DataSource dataSource) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO conversations (id, title, provider, model) VALUES (?, ?, ?, ?)"
             )
        ) {
            statement.setObject(1, id);
            statement.setString(2, "demo");
            statement.setString(3, "OpenAI");
            statement.setString(4, "gpt-4.1");
            statement.executeUpdate();
        }

        return id;
    }

    private static void setUpdatedAt(DataSource dataSource, UUID conversationId, LocalDateTime updatedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE conversations SET updated_at = ? WHERE id = ?"
             )) {
            statement.setTimestamp(1, Timestamp.valueOf(updatedAt));
            statement.setObject(2, conversationId);
            statement.executeUpdate();
        }
    }

    private static LocalDateTime readUpdatedAt(DataSource dataSource, UUID conversationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT updated_at FROM conversations WHERE id = ?"
             )) {
            statement.setObject(1, conversationId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toLocalDateTime();
            }
        }
    }

    private static long countRows(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM %s".formatted(tableName));
             ResultSet rs = statement.executeQuery()
        ) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }
}
