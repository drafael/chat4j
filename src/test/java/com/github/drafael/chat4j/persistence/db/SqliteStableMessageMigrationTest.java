package com.github.drafael.chat4j.persistence.db;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteStableMessageMigrationTest {

    @Test
    @DisplayName("Stable message migration preserves links and installs ordered message constraints")
    void migrate_whenLegacyDatabaseIsPopulated_preservesLinksAndEnforcesStableOrder(@TempDir Path tempDir) throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDir.resolve("stable-message-v7.db"));
        migrate(dataSource, "6");
        UUID conversationId = UUID.randomUUID();
        UUID firstInsertedMessageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID secondInsertedMessageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sharedAttachmentId = UUID.randomUUID();
        UUID uniqueAttachmentId = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO conversations (id, title, provider, model) VALUES ('%s', 'Legacy', 'OpenAI', 'gpt-4.1')"
                    .formatted(conversationId));
            statement.executeUpdate("INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) VALUES ('%s', 'stored/shared', 'shared.txt', 'text/plain', 4, 'shared')"
                    .formatted(sharedAttachmentId));
            statement.executeUpdate("INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) VALUES ('%s', 'stored/unique', 'unique.txt', 'text/plain', 4, 'unique')"
                    .formatted(uniqueAttachmentId));
            statement.executeUpdate("INSERT INTO messages (id, conversation_id, role, content, created_at) VALUES ('%s', '%s', 'unsupported', 'first', '1970-01-01 00:00:01')"
                    .formatted(firstInsertedMessageId, conversationId));
            statement.executeUpdate("INSERT INTO messages (id, conversation_id, role, content, created_at) VALUES ('%s', '%s', 'assistant', 'second', '1970-01-01 00:00:01')"
                    .formatted(secondInsertedMessageId, conversationId));
            statement.executeUpdate("INSERT INTO message_attachments (message_id, attachment_id, part_index) VALUES ('%s', '%s', 0)"
                    .formatted(secondInsertedMessageId, sharedAttachmentId));
            statement.executeUpdate("INSERT INTO message_attachments (message_id, attachment_id, part_index) VALUES ('%s', '%s', 0)"
                    .formatted(firstInsertedMessageId, sharedAttachmentId));
            statement.executeUpdate("INSERT INTO message_attachments (message_id, attachment_id, part_index) VALUES ('%s', '%s', 1)"
                    .formatted(firstInsertedMessageId, uniqueAttachmentId));
        }

        migrate(dataSource, null);

        try (var connection = dataSource.getConnection();
             var messages = connection.prepareStatement(
                     "SELECT id, role, created_at, ordinal FROM messages WHERE conversation_id = ? ORDER BY ordinal"
             )) {
            messages.setString(1, conversationId.toString());
            try (var result = messages.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("id")).isEqualTo(secondInsertedMessageId.toString());
                assertThat(result.getString("role")).isEqualTo("ASSISTANT");
                assertThat(result.getLong("created_at")).isEqualTo(1_000L);
                assertThat(result.getInt("ordinal")).isEqualTo(1);
                assertThat(result.next()).isTrue();
                assertThat(result.getString("id")).isEqualTo(firstInsertedMessageId.toString());
                assertThat(result.getString("role")).isEqualTo("USER");
                assertThat(result.getInt("ordinal")).isEqualTo(2);
                assertThat(result.next()).isFalse();
            }

            try (var links = connection.createStatement().executeQuery(
                    "SELECT message_id, attachment_id, part_index FROM message_attachments ORDER BY message_id, part_index"
            )) {
                assertThat(links.next()).isTrue();
                assertThat(links.getString("message_id")).isEqualTo(secondInsertedMessageId.toString());
                assertThat(links.getString("attachment_id")).isEqualTo(sharedAttachmentId.toString());
                assertThat(links.getInt("part_index")).isZero();
                assertThat(links.next()).isTrue();
                assertThat(links.getString("message_id")).isEqualTo(firstInsertedMessageId.toString());
                assertThat(links.getString("attachment_id")).isEqualTo(sharedAttachmentId.toString());
                assertThat(links.getInt("part_index")).isZero();
                assertThat(links.next()).isTrue();
                assertThat(links.getString("message_id")).isEqualTo(firstInsertedMessageId.toString());
                assertThat(links.getString("attachment_id")).isEqualTo(uniqueAttachmentId.toString());
                assertThat(links.getInt("part_index")).isEqualTo(1);
                assertThat(links.next()).isFalse();
            }

            try (var violations = connection.createStatement().executeQuery("PRAGMA foreign_key_check")) {
                assertThat(violations.next()).isFalse();
            }

            assertThatThrownBy(() -> insertMessage(connection, conversationId, UUID.randomUUID(), 0))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> insertMessage(connection, conversationId, UUID.randomUUID(), 2))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE constraint failed");
            assertThatThrownBy(() -> insertMessageWithRawOrder(
                    connection,
                    conversationId,
                    UUID.randomUUID(),
                    "'not-an-epoch'",
                    "3"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CHECK constraint failed");
            assertThatThrownBy(() -> insertMessageWithRawOrder(
                    connection,
                    conversationId,
                    UUID.randomUUID(),
                    "2000",
                    "3.5"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("CHECK constraint failed");
        }
    }

    @Test
    @DisplayName("Stable message migration reports invalid legacy timestamp prerequisites")
    void migrate_whenLegacyTimestampIsInvalid_failsAtNamedValidationBoundary(@TempDir Path tempDir) throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDir.resolve("stable-message-v7-invalid.db"));
        migrate(dataSource, "6");
        UUID conversationId = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO conversations (id, title) VALUES ('%s', 'Invalid')"
                    .formatted(conversationId));
            statement.executeUpdate("INSERT INTO messages (id, conversation_id, role, content, created_at) VALUES ('%s', '%s', 'USER', 'invalid', 'not-a-timestamp')"
                    .formatted(UUID.randomUUID(), conversationId));
        }

        assertThatThrownBy(() -> migrate(dataSource, null))
                .hasStackTraceContaining("chk_v7_stable_message_prerequisites");
    }

    private SQLiteDataSource dataSource(Path databasePath) {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:%s".formatted(databasePath));
        return dataSource;
    }

    private void migrate(SQLiteDataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/sqlite")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"));
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insertMessageWithRawOrder(
            java.sql.Connection connection,
            UUID conversationId,
            UUID messageId,
            String createdAtSql,
            String ordinalSql
    ) throws SQLException {
        try (var statement = connection.createStatement()) {
            String sql = """
                    INSERT INTO messages (id, conversation_id, role, content, created_at, ordinal)
                    VALUES ('%s', '%s', 'USER', 'invalid type', %s, %s)
                    """.formatted(messageId, conversationId, createdAtSql, ordinalSql);
            statement.executeUpdate(sql);
        }
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
            statement.setString(1, messageId.toString());
            statement.setString(2, conversationId.toString());
            statement.setString(3, "USER");
            statement.setString(4, "conflict");
            statement.setLong(5, 1L);
            statement.setInt(6, ordinal);
            statement.executeUpdate();
        }
    }
}
