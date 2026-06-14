package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.persistence.db.H2SqlDialect;
import com.github.drafael.chat4j.persistence.db.SqlDialect;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.commons.lang3.StringUtils;

public class ConversationRepository {

    private final DataSource dataSource;
    private final SqlDialect sqlDialect;
    private final ConversationMessageJsonCodec messageJsonCodec;
    private final ConversationAttachmentStore attachmentStore;

    public ConversationRepository(DataSource dataSource) {
        this(dataSource, null, new H2SqlDialect());
    }

    public ConversationRepository(DataSource dataSource, Path attachmentRoot) {
        this(dataSource, attachmentRoot, new H2SqlDialect());
    }

    public ConversationRepository(DataSource dataSource, Path attachmentRoot, SqlDialect sqlDialect) {
        this.dataSource = dataSource;
        this.sqlDialect = sqlDialect;
        this.messageJsonCodec = new ConversationMessageJsonCodec();
        this.attachmentStore = new ConversationAttachmentStore(attachmentRoot, sqlDialect);
    }

    public UUID createConversation(String title, String provider, String model) throws SQLException {
        UUID id = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO conversations (id, title, provider, model) VALUES (?, ?, ?, ?)"
             )
        ) {
            sqlDialect.bindUuid(ps, 1, id);
            ps.setString(2, title);
            ps.setString(3, provider);
            ps.setString(4, model);
            ps.executeUpdate();
        }

        return id;
    }

    public void updateTitle(UUID id, String title) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE conversations SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
             )
        ) {
            ps.setString(1, title);
            sqlDialect.bindUuid(ps, 2, id);
            ps.executeUpdate();
        }
    }

    public void toggleFavorite(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE conversations SET is_favorite = %s, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
                             .formatted(sqlDialect.booleanToggleExpression("is_favorite"))
             )
        ) {
            sqlDialect.bindUuid(ps, 1, id);
            ps.executeUpdate();
        }
    }

    public void updateAgentSettings(UUID id, boolean agentModeEnabled, Path agentProjectRoot) throws SQLException {
        Path normalizedRoot = agentProjectRoot == null ? null : agentProjectRoot.toAbsolutePath().normalize();
        boolean effectiveAgentModeEnabled = agentModeEnabled && normalizedRoot != null;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE conversations SET agent_mode_enabled = ?, agent_project_root = ? WHERE id = ?"
             )
        ) {
            ps.setBoolean(1, effectiveAgentModeEnabled);
            ps.setString(2, normalizedRoot == null ? null : normalizedRoot.toString());
            sqlDialect.bindUuid(ps, 3, id);
            ps.executeUpdate();
        }
    }

    public void updateReasoningLevel(UUID id, ReasoningLevel reasoningLevel) throws SQLException {
        ReasoningLevel normalizedLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE conversations SET reasoning_level = ? WHERE id = ?"
             )
        ) {
            ps.setString(1, normalizedLevel.toSettingValue());
            sqlDialect.bindUuid(ps, 2, id);
            ps.executeUpdate();
        }
    }

    public void updateWebSearchSettings(UUID id, boolean enabled, String optionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ensureConversationWebSearchColumns(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE conversations SET web_search_enabled = ?, web_search_option = ? WHERE id = ?"
            )) {
                ps.setBoolean(1, enabled);
                ps.setString(2, StringUtils.trimToNull(optionId));
                sqlDialect.bindUuid(ps, 3, id);
                ps.executeUpdate();
            }
        }
    }

    public void deleteConversation(UUID id) throws SQLException {
        List<Path> orphanAttachmentFiles = deleteRowsAndCollectOrphanAttachmentFiles(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM conversations WHERE id = ?")) {
                sqlDialect.bindUuid(ps, 1, id);
                ps.executeUpdate();
            }
        });
        attachmentStore.deleteAttachmentFiles(orphanAttachmentFiles);
    }

    public void deleteMessages(UUID conversationId) throws SQLException {
        List<Path> orphanAttachmentFiles = deleteRowsAndCollectOrphanAttachmentFiles(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM messages WHERE conversation_id = ?")) {
                sqlDialect.bindUuid(ps, 1, conversationId);
                ps.executeUpdate();
            }
        });
        attachmentStore.deleteAttachmentFiles(orphanAttachmentFiles);
    }

    public void deleteConversations(List<UUID> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Path> orphanAttachmentFiles = deleteRowsAndCollectOrphanAttachmentFiles(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM conversations WHERE id IN (%s)".formatted(placeholders)
            )) {
                for (int i = 0; i < ids.size(); i++) {
                    sqlDialect.bindUuid(ps, i + 1, ids.get(i));
                }
                ps.executeUpdate();
            }
        });
        attachmentStore.deleteAttachmentFiles(orphanAttachmentFiles);
    }

    public void deleteAllConversations() throws SQLException {
        List<Path> orphanAttachmentFiles = deleteRowsAndCollectOrphanAttachmentFiles(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM conversations")) {
                ps.executeUpdate();
            }
        });
        attachmentStore.deleteAttachmentFiles(orphanAttachmentFiles);
    }

    public void addMessage(UUID conversationId, Message message) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID messageId = UUID.randomUUID();

            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO messages (id, conversation_id, role, content, content_json, meta_json) VALUES (?, ?, ?, ?, ?, ?)"
                )) {
                    sqlDialect.bindUuid(ps, 1, messageId);
                    sqlDialect.bindUuid(ps, 2, conversationId);
                    ps.setString(3, message.role().name());
                    ps.setString(4, message.content());
                    ps.setString(5, messageJsonCodec.serializeParts(message.parts()));
                    ps.setString(6, messageJsonCodec.serializeMeta(message.meta()));
                    ps.executeUpdate();
                }

                attachmentStore.persistAttachmentLinks(connection, messageId, message.parts());

                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = ?"
                )) {
                    sqlDialect.bindUuid(ps, 1, conversationId);
                    ps.executeUpdate();
                }

                connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackSafely(connection, e);
                throw e;
            }
        }
    }

    public void addMessage(UUID conversationId, String role, String content) throws SQLException {
        addMessage(conversationId, new Message(messageJsonCodec.parseRole(role), content, Instant.now()));
    }

    public Optional<ConversationRecord> findById(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ensureConversationWebSearchColumns(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, title, provider, model, is_favorite, reasoning_level, agent_mode_enabled, agent_project_root, web_search_enabled, web_search_option, created_at, updated_at FROM conversations WHERE id = ?"
            )) {
                sqlDialect.bindUuid(ps, 1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new ConversationRecord(
                                sqlDialect.readUuid(rs, "id"),
                                rs.getString("title"),
                                rs.getString("provider"),
                                rs.getString("model"),
                                rs.getBoolean("is_favorite"),
                                rs.getString("reasoning_level"),
                                rs.getBoolean("agent_mode_enabled"),
                                rs.getString("agent_project_root"),
                                rs.getBoolean("web_search_enabled"),
                                rs.getString("web_search_option"),
                                rs.getTimestamp("created_at").toLocalDateTime(),
                                rs.getTimestamp("updated_at").toLocalDateTime()
                        ));
                    }
                }
            }
        }

        return Optional.empty();
    }

    public List<MessageRecord> getMessages(UUID conversationId) throws SQLException {
        List<MessageRecord> messages = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, role, content, content_json, meta_json, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at"
             )
        ) {
            sqlDialect.bindUuid(ps, 1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                    messages.add(new MessageRecord(
                        sqlDialect.readUuid(rs, "id"),
                        messageJsonCodec.deserializeMessage(
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("content_json"),
                            rs.getString("meta_json"),
                            createdAt
                        ),
                        createdAt
                    ));
                }
            }
        }

        return messages;
    }

    public Map<String, List<ConversationRecord>> findAllGroupedByDate() throws SQLException {
        Map<String, List<ConversationRecord>> grouped = new LinkedHashMap<>();
        grouped.put("Favorites", new ArrayList<>());

        try (Connection connection = dataSource.getConnection()) {
            ensureConversationWebSearchColumns(connection);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, title, provider, model, is_favorite, reasoning_level, agent_mode_enabled, agent_project_root, web_search_enabled, web_search_option, created_at, updated_at FROM conversations ORDER BY updated_at DESC"
            );
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ConversationRecord rec = new ConversationRecord(
                            sqlDialect.readUuid(rs, "id"),
                            rs.getString("title"),
                            rs.getString("provider"),
                            rs.getString("model"),
                            rs.getBoolean("is_favorite"),
                            rs.getString("reasoning_level"),
                            rs.getBoolean("agent_mode_enabled"),
                            rs.getString("agent_project_root"),
                            rs.getBoolean("web_search_enabled"),
                            rs.getString("web_search_option"),
                            rs.getTimestamp("created_at").toLocalDateTime(),
                            rs.getTimestamp("updated_at").toLocalDateTime());

                    if (rec.isFavorite()) {
                        grouped.get("Favorites").add(rec);
                    } else {
                        String group = dateGroup(rec.updatedAt());
                        grouped.computeIfAbsent(group, k -> new ArrayList<>()).add(rec);
                    }
                }
            }
        }

        if (grouped.get("Favorites").isEmpty()) {
            grouped.remove("Favorites");
        }

        return grouped;
    }

    public List<SearchResult> search(String query) throws SQLException {
        List<SearchResult> results = new ArrayList<>();
        String like = "%%%s%%".formatted(query.toLowerCase());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     """
                     SELECT c.id, c.title, c.provider, c.model, c.updated_at,
                            NULL AS snippet FROM conversations c
                     WHERE LOWER(c.title) LIKE ?
                     UNION ALL
                     SELECT c.id, c.title, c.provider, c.model, c.updated_at,
                            %s AS snippet FROM messages m
                     JOIN conversations c ON c.id = m.conversation_id
                     WHERE LOWER(m.content) LIKE ?
                     ORDER BY updated_at DESC
                     """.formatted(sqlDialect.substringExpression("m.content", 1, 120))
             )
        ) {
            ps.setString(1, like);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                Set<UUID> seen = new HashSet<>();
                while (rs.next()) {
                    UUID id = sqlDialect.readUuid(rs, "id");
                    if (seen.add(id)) {
                        results.add(new SearchResult(
                            id,
                            rs.getString("title"),
                            rs.getString("provider"),
                            rs.getString("model"),
                            rs.getString("snippet")
                        ));
                    }
                    if (results.size() >= 20) {
                        break;
                    }
                }
            }
        }
        return results;
    }

    private void rollbackSafely(Connection connection, Throwable error) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            error.addSuppressed(e);
        }
    }

    private List<Path> deleteRowsAndCollectOrphanAttachmentFiles(SqlOperation operation) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                operation.execute(connection);
                List<Path> orphanAttachmentFiles = attachmentStore.deleteOrphanAttachmentRows(connection);
                connection.commit();
                return orphanAttachmentFiles;
            } catch (SQLException | RuntimeException e) {
                rollbackSafely(connection, e);
                throw e;
            }
        }
    }

    private void ensureConversationWebSearchColumns(Connection connection) throws SQLException {
        sqlDialect.ensureConversationWebSearchColumns(connection);
    }

    @FunctionalInterface
    private interface SqlOperation {
        void execute(Connection connection) throws SQLException;
    }

    public record SearchResult(
        UUID id,
        String title,
        String provider,
        String model,
        String snippet
    ) {}

    private String dateGroup(LocalDateTime dateTime) {
        LocalDate date = dateTime.toLocalDate();
        LocalDate today = LocalDate.now();

        if (date.equals(today)) {
            return "Today";
        }
        if (date.equals(today.minusDays(1))) {
            return "Yesterday";
        }
        if (date.isAfter(today.minusDays(7))) {
            return "This Week";
        }
        if (date.isAfter(today.minusDays(30))) {
            return "This Month";
        }
        return "Older";
    }

    public record ConversationRecord(
        UUID id,
        String title,
        String provider,
        String model,
        boolean isFavorite,
        String reasoningLevel,
        boolean agentModeEnabled,
        String agentProjectRoot,
        boolean webSearchEnabled,
        String webSearchOption,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        public ConversationRecord(
                UUID id,
                String title,
                String provider,
                String model,
                boolean isFavorite,
                String reasoningLevel,
                boolean agentModeEnabled,
                String agentProjectRoot,
                LocalDateTime createdAt,
                LocalDateTime updatedAt
        ) {
            this(
                    id,
                    title,
                    provider,
                    model,
                    isFavorite,
                    reasoningLevel,
                    agentModeEnabled,
                    agentProjectRoot,
                    false,
                    null,
                    createdAt,
                    updatedAt
            );
        }
    }

    public record MessageRecord(
        UUID id,
        Message message,
        LocalDateTime createdAt
    ) {}
}
