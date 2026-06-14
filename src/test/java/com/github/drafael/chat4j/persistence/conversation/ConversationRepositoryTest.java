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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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

        assertThatThrownBy(() -> subject.addMessage(conversationId, message))
                .isInstanceOf(SQLException.class);

        assertThat(countRows(dataSource, "messages")).isZero();
        assertThat(countRows(dataSource, "attachments")).isZero();
        assertThat(countRows(dataSource, "message_attachments")).isZero();
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

        subject.addMessage(conversationId, message);

        assertThat(countRows(dataSource, "messages")).isEqualTo(1);
        assertThat(countRows(dataSource, "attachments")).isEqualTo(1);
        assertThat(countRows(dataSource, "message_attachments")).isEqualTo(1);
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

        subject.addMessage(conversationId, message);

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

        subject.addMessage(conversationId, message);

        Message restored = subject.getMessages(conversationId).getFirst().message();
        assertThat(restored.meta().agentToolActivities()).containsExactlyElementsOf(toolActivities);
    }

    @Test
    @DisplayName("Deleting messages keeps the conversation row")
    void deleteMessages_whenConversationHasMessages_removesMessagesOnly() throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-delete-messages");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);

        ConversationRepository subject = new ConversationRepository(dataSource);
        subject.addMessage(conversationId, Message.user("first"));
        subject.addMessage(conversationId, Message.assistant("second"));

        subject.deleteMessages(conversationId);

        assertThat(countRows(dataSource, "conversations")).isEqualTo(1);
        assertThat(countRows(dataSource, "messages")).isZero();
        assertThat(subject.findById(conversationId)).isPresent();
    }

    @Test
    @DisplayName("Deleting messages removes unreferenced attachment files")
    void deleteMessages_whenMessagesHaveAttachments_deletesAttachmentFiles(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = createDataSource("conversation-repo-delete-message-attachments");
        createSchema(dataSource);
        UUID conversationId = insertConversation(dataSource);
        Path attachmentFile = createAttachmentFile(tempDir, "message.txt");

        ConversationRepository subject = new ConversationRepository(dataSource, tempDir);
        subject.addMessage(conversationId, Message.user(List.of(
                new TextPart("attached"),
                new FilePart(attachmentRef(attachmentFile))
        )));

        subject.deleteMessages(conversationId);

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
        subject.addMessage(conversationId, Message.user(List.of(new FilePart(attachmentRef(attachmentFile)))));

        subject.deleteConversation(conversationId);

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
        subject.addMessage(firstConversationId, Message.user(List.of(new FilePart(attachmentRef))));
        subject.addMessage(secondConversationId, Message.user(List.of(new FilePart(attachmentRef))));

        subject.deleteMessages(firstConversationId);

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

        ConversationRepository.ConversationRecord conversation = subject.findById(conversationId).orElseThrow();
        assertThat(conversation.reasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH.toSettingValue());

        subject.updateReasoningLevel(conversationId, null);
        ConversationRepository.ConversationRecord updated = subject.findById(conversationId).orElseThrow();
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

        ConversationRepository.ConversationRecord conversation = subject.findById(conversationId).orElseThrow();
        assertThat(conversation.agentModeEnabled()).isTrue();
        assertThat(conversation.agentProjectRoot()).isEqualTo(Path.of("/tmp/workspace").toAbsolutePath().normalize().toString());

        subject.updateAgentSettings(conversationId, true, null);
        ConversationRepository.ConversationRecord updated = subject.findById(conversationId).orElseThrow();
        assertThat(updated.agentModeEnabled()).isFalse();
        assertThat(updated.agentProjectRoot()).isNull();
    }

    private static Path createAttachmentFile(Path attachmentRoot, String fileName) throws Exception {
        Path attachmentFile = attachmentRoot.resolve("20260515").resolve(fileName);
        Files.createDirectories(attachmentFile.getParent());
        Files.writeString(attachmentFile, "attachment");
        return attachmentFile;
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
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);

            execute(connection, """
                    CREATE TABLE IF NOT EXISTS messages (
                        id UUID PRIMARY KEY,
                        conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
                        role VARCHAR(10),
                        content CLOB,
                        content_json CLOB,
                        meta_json CLOB,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
