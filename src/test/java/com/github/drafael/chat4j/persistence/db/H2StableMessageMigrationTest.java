package com.github.drafael.chat4j.persistence.db;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class H2StableMessageMigrationTest {

    @Test
    @DisplayName("Stable message migration normalizes roles and interprets legacy timestamps in the database zone")
    void migrate_whenLegacyMessageUsesLowercaseRole_preservesRoleAndInstantMeaning() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stable-message-v7;DB_CLOSE_DELAY=-1;TIME ZONE=Europe/Helsinki");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        migrate(dataSource, "6");
        LocalDateTime legacyTimestamp = LocalDateTime.of(1970, 1, 1, 0, 0, 0, 999_600_000);
        long expectedEpochMillis = legacyTimestamp
                .atZone(ZoneId.of("Europe/Helsinki"))
                .toInstant()
                .toEpochMilli();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID earlierCanonicalId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sharedAttachmentId = UUID.randomUUID();
        UUID uniqueAttachmentId = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            try (var conversation = connection.prepareStatement(
                    "INSERT INTO conversations (id, title, provider, model) VALUES (?, ?, ?, ?)"
            )) {
                conversation.setObject(1, conversationId);
                conversation.setString(2, "Legacy");
                conversation.setString(3, "OpenAI");
                conversation.setString(4, "gpt-4.1");
                conversation.executeUpdate();
            }
            try (var message = connection.prepareStatement(
                    "INSERT INTO messages (id, conversation_id, role, content, created_at) VALUES (?, ?, ?, ?, ?)"
            )) {
                message.setObject(1, messageId);
                message.setObject(2, conversationId);
                message.setString(3, "assistant");
                message.setString(4, "answer");
                message.setObject(5, legacyTimestamp);
                message.executeUpdate();
                message.setObject(1, earlierCanonicalId);
                message.setString(3, "user");
                message.setString(4, "question");
                message.executeUpdate();
            }
            try (var attachment = connection.prepareStatement(
                    "INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                insertAttachment(attachment, sharedAttachmentId, "stored/shared", "shared.txt");
                insertAttachment(attachment, uniqueAttachmentId, "stored/unique", "unique.txt");
            }
            try (var link = connection.prepareStatement(
                    "INSERT INTO message_attachments (message_id, attachment_id, part_index) VALUES (?, ?, ?)"
            )) {
                insertLink(link, earlierCanonicalId, sharedAttachmentId, 0);
                insertLink(link, messageId, sharedAttachmentId, 0);
                insertLink(link, messageId, uniqueAttachmentId, 1);
            }
        }

        migrate(dataSource, null);

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT role, created_at, ordinal FROM messages WHERE id = ?"
             )) {
            statement.setObject(1, messageId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("role")).isEqualTo("ASSISTANT");
                assertThat(result.getLong("created_at")).isEqualTo(expectedEpochMillis);
                assertThat(result.getInt("ordinal")).isEqualTo(2);
            }

            try (var ordered = connection.prepareStatement(
                    "SELECT id, ordinal FROM messages WHERE conversation_id = ? ORDER BY ordinal"
            )) {
                ordered.setObject(1, conversationId);
                try (var result = ordered.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("id", UUID.class)).isEqualTo(earlierCanonicalId);
                    assertThat(result.getInt("ordinal")).isEqualTo(1);
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("id", UUID.class)).isEqualTo(messageId);
                    assertThat(result.getInt("ordinal")).isEqualTo(2);
                }
            }

            try (var links = connection.prepareStatement(
                    "SELECT message_id, attachment_id, part_index FROM message_attachments ORDER BY message_id, part_index"
            ); var result = links.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("message_id", UUID.class)).isEqualTo(earlierCanonicalId);
                assertThat(result.getObject("attachment_id", UUID.class)).isEqualTo(sharedAttachmentId);
                assertThat(result.getInt("part_index")).isZero();
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("message_id", UUID.class)).isEqualTo(messageId);
                assertThat(result.getObject("attachment_id", UUID.class)).isEqualTo(sharedAttachmentId);
                assertThat(result.getInt("part_index")).isZero();
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("message_id", UUID.class)).isEqualTo(messageId);
                assertThat(result.getObject("attachment_id", UUID.class)).isEqualTo(uniqueAttachmentId);
                assertThat(result.getInt("part_index")).isEqualTo(1);
                assertThat(result.next()).isFalse();
            }

            assertThatThrownBy(() -> insertMessage(connection, conversationId, UUID.randomUUID(), 0))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMessage(connection, conversationId, UUID.randomUUID(), 2))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("Stable message migration reports invalid legacy identity prerequisites")
    void migrate_whenLegacyConversationIdentityIsMissing_failsAtNamedValidationBoundary() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:stable-message-v7-invalid;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        migrate(dataSource, "6");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO messages (id, conversation_id, role, content, created_at) VALUES (?, NULL, ?, ?, ?)"
             )) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "USER");
            statement.setString(3, "invalid");
            statement.setObject(4, LocalDateTime.now());
            statement.executeUpdate();
        }

        assertThatThrownBy(() -> migrate(dataSource, null))
                .hasStackTraceContaining("CHK_V7_STABLE_MESSAGE_PREREQUISITES");
    }

    private void insertAttachment(
            java.sql.PreparedStatement statement,
            UUID attachmentId,
            String storagePath,
            String originalName
    ) throws SQLException {
        statement.setObject(1, attachmentId);
        statement.setString(2, storagePath);
        statement.setString(3, originalName);
        statement.setString(4, "text/plain");
        statement.setLong(5, 4L);
        statement.setString(6, attachmentId.toString());
        statement.executeUpdate();
    }

    private void insertLink(
            java.sql.PreparedStatement statement,
            UUID messageId,
            UUID attachmentId,
            int partIndex
    ) throws SQLException {
        statement.setObject(1, messageId);
        statement.setObject(2, attachmentId);
        statement.setInt(3, partIndex);
        statement.executeUpdate();
    }

    private void insertMessage(
            java.sql.Connection connection,
            UUID conversationId,
            UUID messageId,
            int ordinal
    ) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO messages (id, conversation_id, role, content, created_at, ordinal) VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            statement.setObject(1, messageId);
            statement.setObject(2, conversationId);
            statement.setString(3, "USER");
            statement.setString(4, "conflict");
            statement.setLong(5, 1L);
            statement.setInt(6, ordinal);
            statement.executeUpdate();
        }
    }

    private void migrate(JdbcDataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"));
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
