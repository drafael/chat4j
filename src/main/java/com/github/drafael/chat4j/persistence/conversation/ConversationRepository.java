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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;

public class ConversationRepository {

    private static final int DELETE_BATCH_SIZE = 400;

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

    public void createConversation(CreateConversationCommand command) throws SQLException {
        inTransaction(connection -> {
            try {
                insertConversation(connection, command);
                insertEntry(connection, command.conversationId(), command.firstEntry());
            } catch (SQLException e) {
                if (!conversationCreateMatches(connection, command)) {
                    throw e;
                }
            }
            return emptyList();
        });
    }

    public boolean isCanonicalCreate(CreateConversationCommand command) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return conversationCreateMatches(connection, command);
        }
    }

    public boolean isCanonicalEntry(UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Optional<ConversationHistoryEntry> existing = findEntry(connection, entry.messageId());
            return existing.isPresent() && canonicalEntryMatches(connection, conversationId, entry, existing.get());
        }
    }

    public void appendMessage(UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        inTransaction(connection -> {
            Optional<ConversationHistoryEntry> existing = findEntry(connection, entry.messageId());
            if (existing.isPresent()) {
                requireCanonicalEntry(connection, conversationId, entry, existing.get());
                return emptyList();
            }
            int nextOrdinal = nextOrdinal(connection, conversationId);
            if (entry.ordinal() != nextOrdinal) {
                throw new SQLException("Expected message ordinal %d but received %d for conversation %s"
                        .formatted(nextOrdinal, entry.ordinal(), conversationId));
            }
            insertEntry(connection, conversationId, entry);
            touchConversation(connection, conversationId);
            return emptyList();
        });
    }

    public boolean isCanonicalEdit(UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        return isCanonicalEntry(conversationId, entry);
    }

    public boolean isCanonicalEditAndTruncate(UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        return isCanonicalEntry(conversationId, entry)
                && isCanonicalTruncate(conversationId, entry.messageId(), entry.ordinal());
    }

    public boolean isCanonicalTruncate(UUID conversationId, UUID retainedMessageId, int retainedOrdinal) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!retainedIdentityMatches(connection, conversationId, retainedMessageId, retainedOrdinal)) {
                return false;
            }
            return hasNoSuffix(connection, conversationId, retainedOrdinal);
        }
    }

    public void updateMessage(UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        executeAttachmentMutation(connection -> updateRetainedEntry(connection, conversationId, entry, false));
    }

    public void updateMessageAndDeleteSuffix(UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        executeAttachmentMutation(connection -> updateRetainedEntry(connection, conversationId, entry, true));
    }

    public void truncateAfter(UUID conversationId, UUID retainedMessageId, int retainedOrdinal) throws SQLException {
        executeAttachmentMutation(connection -> {
            requireRetainedIdentity(connection, conversationId, retainedMessageId, retainedOrdinal);
            Set<UUID> candidateAttachmentIds = attachmentStore.findAttachmentIdsForSuffix(
                    connection,
                    conversationId,
                    retainedOrdinal
            );
            int deletedMessages = deleteSuffix(connection, conversationId, retainedOrdinal);
            if (deletedMessages == 0) {
                return emptyList();
            }
            touchConversation(connection, conversationId);
            return attachmentStore.deleteOrphanAttachmentRows(connection, candidateAttachmentIds);
        });
    }

    public boolean isClear(UUID conversationId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!conversationExists(connection, conversationId)) {
                return false;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM messages WHERE conversation_id = ?"
            )) {
                sqlDialect.bindUuid(ps, 1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    return !rs.next();
                }
            }
        }
    }

    public void clearMessages(UUID conversationId) throws SQLException {
        executeAttachmentMutation(connection -> {
            requireConversation(connection, conversationId);
            Set<UUID> candidateAttachmentIds = attachmentStore.findAttachmentIdsForConversations(
                    connection,
                    List.of(conversationId)
            );
            int deletedMessages;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM messages WHERE conversation_id = ?")) {
                sqlDialect.bindUuid(ps, 1, conversationId);
                deletedMessages = ps.executeUpdate();
            }
            if (deletedMessages == 0) {
                return emptyList();
            }
            touchConversation(connection, conversationId);
            return attachmentStore.deleteOrphanAttachmentRows(connection, candidateAttachmentIds);
        });
    }

    public void appendFirstAfterClear(FirstAfterClearCommand command) throws SQLException {
        inTransaction(connection -> {
            Optional<ConversationHistoryEntry> existing = findEntry(connection, command.entry().messageId());
            if (existing.isPresent()) {
                requireCanonicalEntry(connection, command.conversationId(), command.entry(), existing.get());
                if (!conversationTitleMatches(connection, command.conversationId(), command.title())) {
                    throw new SQLException("Conflicting first post-clear state for conversation %s"
                            .formatted(command.conversationId()));
                }
                return emptyList();
            }
            requireConversation(connection, command.conversationId());
            if (nextOrdinal(connection, command.conversationId()) != 1 || command.entry().ordinal() != 1) {
                throw new SQLException("Conversation is not clear: %s".formatted(command.conversationId()));
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE conversations SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
            )) {
                ps.setString(1, command.title());
                sqlDialect.bindUuid(ps, 2, command.conversationId());
                requireOneRow(ps.executeUpdate(), "first post-clear retitle", command.conversationId());
            }
            insertEntry(connection, command.conversationId(), command.entry());
            return emptyList();
        });
    }

    public boolean isCanonicalFirstAfterClear(FirstAfterClearCommand command) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            Optional<ConversationHistoryEntry> existing = findEntry(connection, command.entry().messageId());
            return existing.isPresent()
                    && canonicalEntryMatches(connection, command.conversationId(), command.entry(), existing.get())
                    && conversationTitleMatches(connection, command.conversationId(), command.title());
        }
    }

    public boolean hasTitle(UUID id, String title) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return conversationTitleMatches(connection, id, title);
        }
    }

    public boolean hasFavorite(UUID id, boolean favorite) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT is_favorite FROM conversations WHERE id = ?"
             )) {
            sqlDialect.bindUuid(ps, 1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                boolean storedFavorite = rs.getBoolean(1);
                return !rs.wasNull() && storedFavorite == favorite;
            }
        }
    }

    public void updateTitle(UUID id, String title) throws SQLException {
        updateConversation(id, "UPDATE conversations SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", ps -> ps.setString(1, title));
    }

    public void setFavorite(UUID id, boolean favorite) throws SQLException {
        updateConversation(id, "UPDATE conversations SET is_favorite = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", ps -> ps.setBoolean(1, favorite));
    }

    public boolean hasAgentSettings(UUID id, boolean agentModeEnabled, Path agentProjectRoot) throws SQLException {
        Path normalizedRoot = agentProjectRoot == null ? null : agentProjectRoot.toAbsolutePath().normalize();
        boolean effectiveEnabled = agentModeEnabled && normalizedRoot != null;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT agent_mode_enabled, agent_project_root FROM conversations WHERE id = ?"
             )) {
            sqlDialect.bindUuid(ps, 1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                boolean storedEnabled = rs.getBoolean("agent_mode_enabled");
                return !rs.wasNull()
                        && storedEnabled == effectiveEnabled
                        && Objects.equals(
                        rs.getString("agent_project_root"),
                        normalizedRoot == null ? null : normalizedRoot.toString()
                );
            }
        }
    }

    public boolean hasReasoningLevel(UUID id, ReasoningLevel reasoningLevel) throws SQLException {
        ReasoningLevel normalized = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT reasoning_level FROM conversations WHERE id = ?"
             )) {
            sqlDialect.bindUuid(ps, 1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && Objects.equals(rs.getString(1), normalized.toSettingValue());
            }
        }
    }

    public boolean hasWebSearchSettings(UUID id, boolean enabled, String optionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT web_search_enabled, web_search_option FROM conversations WHERE id = ?"
            )) {
                sqlDialect.bindUuid(ps, 1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    boolean storedEnabled = rs.getBoolean("web_search_enabled");
                    return !rs.wasNull()
                            && storedEnabled == enabled
                            && Objects.equals(rs.getString("web_search_option"), StringUtils.trimToNull(optionId));
                }
            }
        }
    }

    public void updateAgentSettings(UUID id, boolean agentModeEnabled, Path agentProjectRoot) throws SQLException {
        Path normalizedRoot = agentProjectRoot == null ? null : agentProjectRoot.toAbsolutePath().normalize();
        boolean effectiveEnabled = agentModeEnabled && normalizedRoot != null;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE conversations SET agent_mode_enabled = ?, agent_project_root = ? WHERE id = ?"
             )) {
            ps.setBoolean(1, effectiveEnabled);
            ps.setString(2, normalizedRoot == null ? null : normalizedRoot.toString());
            sqlDialect.bindUuid(ps, 3, id);
            requireOneRow(ps.executeUpdate(), "agent settings", id);
        }
    }

    public void updateReasoningLevel(UUID id, ReasoningLevel reasoningLevel) throws SQLException {
        ReasoningLevel normalized = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        updateConversation(id, "UPDATE conversations SET reasoning_level = ? WHERE id = ?", ps -> ps.setString(1, normalized.toSettingValue()));
    }

    public void updateWebSearchSettings(UUID id, boolean enabled, String optionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE conversations SET web_search_enabled = ?, web_search_option = ? WHERE id = ?"
            )) {
                ps.setBoolean(1, enabled);
                ps.setString(2, StringUtils.trimToNull(optionId));
                sqlDialect.bindUuid(ps, 3, id);
                requireOneRow(ps.executeUpdate(), "web search settings", id);
            }
        }
    }

    public boolean conversationsAbsent(List<UUID> ids) throws SQLException {
        List<UUID> exactIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (exactIds.isEmpty()) {
            return true;
        }
        try (Connection connection = dataSource.getConnection()) {
            for (int start = 0; start < exactIds.size(); start += DELETE_BATCH_SIZE) {
                List<UUID> batch = exactIds.subList(start, Math.min(start + DELETE_BATCH_SIZE, exactIds.size()));
                String placeholders = String.join(",", batch.stream().map(ignored -> "?").toList());
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT 1 FROM conversations WHERE id IN (%s)".formatted(placeholders)
                )) {
                    for (int index = 0; index < batch.size(); index++) {
                        sqlDialect.bindUuid(ps, index + 1, batch.get(index));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    public void deleteConversations(List<UUID> ids) throws SQLException {
        Set<UUID> exactIds = ids.stream()
                .filter(Objects::nonNull)
                .collect(toCollection(LinkedHashSet::new));
        if (exactIds.isEmpty()) {
            return;
        }
        executeAttachmentMutation(connection -> {
            List<UUID> values = List.copyOf(exactIds);
            Set<UUID> candidateAttachmentIds = attachmentStore.findAttachmentIdsForConversations(connection, values);
            for (int start = 0; start < values.size(); start += DELETE_BATCH_SIZE) {
                List<UUID> batch = values.subList(start, Math.min(start + DELETE_BATCH_SIZE, values.size()));
                String placeholders = String.join(",", batch.stream().map(ignored -> "?").toList());
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM conversations WHERE id IN (%s)".formatted(placeholders)
                )) {
                    for (int index = 0; index < batch.size(); index++) {
                        sqlDialect.bindUuid(ps, index + 1, batch.get(index));
                    }
                    ps.executeUpdate();
                }
            }
            return attachmentStore.deleteOrphanAttachmentRows(connection, candidateAttachmentIds);
        });
    }

    public Optional<LoadedConversation> loadConversation(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    SELECT c.id AS c_id, c.title, c.provider, c.model, c.is_favorite, c.reasoning_level,
                           c.agent_mode_enabled, c.agent_project_root, c.web_search_enabled, c.web_search_option,
                           c.created_at AS c_created_at, c.updated_at AS c_updated_at,
                           m.id AS m_id, m.role, m.content, m.content_json, m.meta_json, m.created_at AS m_created_at,
                           m.ordinal
                    FROM conversations c
                    LEFT JOIN messages m ON m.conversation_id = c.id
                    WHERE c.id = ?
                    ORDER BY m.ordinal
                    """
            )) {
                sqlDialect.bindUuid(ps, 1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    ConversationRecord conversation = readConversation(rs);
                    List<MessageRecord> messages = new ArrayList<>();
                    do {
                        UUID messageId = sqlDialect.readUuid(rs, "m_id");
                        if (messageId != null) {
                            Instant createdAt = Instant.ofEpochMilli(rs.getLong("m_created_at"));
                            int ordinal = rs.getInt("ordinal");
                            Message message = messageJsonCodec.deserializeMessage(
                                    rs.getString("role"), rs.getString("content"), rs.getString("content_json"),
                                    rs.getString("meta_json"), createdAt);
                            messages.add(new MessageRecord(messageId, ordinal, message));
                        }
                    } while (rs.next());
                    return Optional.of(new LoadedConversation(conversation, messages));
                }
            }
        }
    }

    public Map<String, List<ConversationRecord>> findAllGroupedByDate() throws SQLException {
        Map<String, List<ConversationRecord>> grouped = new LinkedHashMap<>();
        grouped.put("Favorites", new ArrayList<>());
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id AS c_id, title, provider, model, is_favorite, reasoning_level, agent_mode_enabled, agent_project_root, web_search_enabled, web_search_option, created_at AS c_created_at, updated_at AS c_updated_at FROM conversations ORDER BY updated_at DESC"
            ); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ConversationRecord record = readConversation(rs);
                    if (record.isFavorite()) {
                        grouped.get("Favorites").add(record);
                    } else {
                        grouped.computeIfAbsent(dateGroup(record.updatedAt()), ignored -> new ArrayList<>()).add(record);
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
                     SELECT c.id, c.title, c.provider, c.model, c.updated_at, NULL AS snippet
                     FROM conversations c WHERE LOWER(c.title) LIKE ?
                     UNION ALL
                     SELECT c.id, c.title, c.provider, c.model, c.updated_at, %s AS snippet
                     FROM messages m JOIN conversations c ON c.id = m.conversation_id
                     WHERE LOWER(m.content) LIKE ? ORDER BY updated_at DESC
                     """.formatted(sqlDialect.substringExpression("m.content", 1, 120))
             )) {
            ps.setString(1, like);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                Set<UUID> seen = new HashSet<>();
                while (rs.next() && results.size() < 20) {
                    UUID id = sqlDialect.readUuid(rs, "id");
                    if (seen.add(id)) {
                        results.add(new SearchResult(id, rs.getString("title"), rs.getString("provider"), rs.getString("model"), rs.getString("snippet")));
                    }
                }
            }
        }
        return results;
    }

    private List<Path> updateRetainedEntry(
            Connection connection,
            UUID conversationId,
            ConversationHistoryEntry entry,
            boolean deleteSuffix
    ) throws SQLException {
        Optional<ConversationHistoryEntry> existing = findEntry(connection, entry.messageId());
        if (existing.isEmpty() || !entryIdentityMatches(connection, conversationId, existing.get())) {
            throw new SQLException("Message identity does not belong to conversation: %s".formatted(entry.messageId()));
        }

        boolean messagePayloadChanged = !canonicalMessagePayloadMatches(entry, existing.get());
        boolean attachmentLinksChanged = !attachmentStore.attachmentLinksMatch(
                connection,
                entry.messageId(),
                entry.message().parts()
        );
        boolean payloadChanged = messagePayloadChanged || attachmentLinksChanged;
        Set<UUID> candidateAttachmentIds = new LinkedHashSet<>();
        if (attachmentLinksChanged) {
            candidateAttachmentIds.addAll(attachmentStore.findAttachmentIdsForMessage(connection, entry.messageId()));
            replaceAttachmentLinks(connection, entry);
        }
        if (messagePayloadChanged) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE messages SET role = ?, content = ?, content_json = ?, meta_json = ?, created_at = ? WHERE id = ? AND conversation_id = ? AND ordinal = ?"
            )) {
                bindEntryPayload(ps, entry, 1);
                sqlDialect.bindUuid(ps, 6, entry.messageId());
                sqlDialect.bindUuid(ps, 7, conversationId);
                ps.setInt(8, entry.ordinal());
                requireOneRow(ps.executeUpdate(), "message edit", conversationId);
            }
        }

        int deletedMessages = 0;
        if (deleteSuffix) {
            candidateAttachmentIds.addAll(attachmentStore.findAttachmentIdsForSuffix(
                    connection,
                    conversationId,
                    entry.ordinal()
            ));
            deletedMessages = deleteSuffix(connection, conversationId, entry.ordinal());
        }
        if (!payloadChanged && deletedMessages == 0) {
            return emptyList();
        }
        touchConversation(connection, conversationId);
        return attachmentStore.deleteOrphanAttachmentRows(connection, candidateAttachmentIds);
    }

    private void replaceAttachmentLinks(Connection connection, ConversationHistoryEntry entry) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM message_attachments WHERE message_id = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, entry.messageId());
            ps.executeUpdate();
        }
        attachmentStore.persistAttachmentLinks(connection, entry.messageId(), entry.message().parts());
    }

    private void insertConversation(Connection connection, CreateConversationCommand command) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO conversations (
                    id, title, provider, model, reasoning_level, agent_mode_enabled,
                    agent_project_root, web_search_enabled, web_search_option
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            sqlDialect.bindUuid(ps, 1, command.conversationId());
            ps.setString(2, command.title());
            ps.setString(3, command.provider());
            ps.setString(4, command.model());
            ps.setString(5, command.reasoningLevel().toSettingValue());
            Path root = command.agentProjectRoot() == null ? null : command.agentProjectRoot().toAbsolutePath().normalize();
            ps.setBoolean(6, command.agentModeEnabled() && root != null);
            ps.setString(7, root == null ? null : root.toString());
            ps.setBoolean(8, command.webSearchEnabled());
            ps.setString(9, StringUtils.trimToNull(command.webSearchOptionId()));
            ps.executeUpdate();
        }
    }

    private void insertEntry(Connection connection, UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO messages (id, conversation_id, role, content, content_json, meta_json, created_at, ordinal) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )) {
            sqlDialect.bindUuid(ps, 1, entry.messageId());
            sqlDialect.bindUuid(ps, 2, conversationId);
            bindEntryPayload(ps, entry, 3);
            ps.setInt(8, entry.ordinal());
            ps.executeUpdate();
        }
        attachmentStore.persistAttachmentLinks(connection, entry.messageId(), entry.message().parts());
    }

    private void bindEntryPayload(PreparedStatement ps, ConversationHistoryEntry entry, int start) throws SQLException {
        Message message = entry.message();
        ps.setString(start, message.role().name());
        ps.setString(start + 1, message.content());
        ps.setString(start + 2, messageJsonCodec.serializeParts(message.parts()));
        ps.setString(start + 3, messageJsonCodec.serializeMeta(message.meta()));
        ps.setLong(start + 4, message.timestamp().toEpochMilli());
    }

    private Optional<ConversationHistoryEntry> findEntry(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, role, content, content_json, meta_json, created_at, ordinal FROM messages WHERE id = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
                Message message = messageJsonCodec.deserializeMessage(rs.getString("role"), rs.getString("content"),
                        rs.getString("content_json"), rs.getString("meta_json"), createdAt);
                return Optional.of(new ConversationHistoryEntry(sqlDialect.readUuid(rs, "id"), rs.getInt("ordinal"), message));
            }
        }
    }

    private void requireCanonicalEntry(
            Connection connection,
            UUID conversationId,
            ConversationHistoryEntry expected,
            ConversationHistoryEntry actual
    ) throws SQLException {
        requireEntryIdentity(connection, conversationId, actual);
        if (!canonicalEntryPayloadMatches(connection, expected, actual)) {
            throw new SQLException("Conflicting message identity %s for conversation %s".formatted(expected.messageId(), conversationId));
        }
    }

    private boolean canonicalEntryMatches(
            Connection connection,
            UUID conversationId,
            ConversationHistoryEntry expected,
            ConversationHistoryEntry actual
    ) throws SQLException {
        return entryIdentityMatches(connection, conversationId, actual)
                && canonicalEntryPayloadMatches(connection, expected, actual);
    }

    private boolean canonicalEntryPayloadMatches(
            Connection connection,
            ConversationHistoryEntry expected,
            ConversationHistoryEntry actual
    ) throws SQLException {
        return canonicalMessagePayloadMatches(expected, actual)
                && storedMessagePayloadMatches(connection, expected)
                && attachmentStore.attachmentLinksMatch(
                        connection,
                        expected.messageId(),
                        expected.message().parts()
                );
    }

    private boolean canonicalMessagePayloadMatches(
            ConversationHistoryEntry expected,
            ConversationHistoryEntry actual
    ) {
        return expected.ordinal() == actual.ordinal()
                && expected.message().timestamp().toEpochMilli() == actual.message().timestamp().toEpochMilli()
                && expected.message().role() == actual.message().role()
                && Objects.equals(
                        messageJsonCodec.serializeParts(expected.message().parts()),
                        messageJsonCodec.serializeParts(actual.message().parts())
                )
                && Objects.equals(
                        messageJsonCodec.serializeMeta(expected.message().meta()),
                        messageJsonCodec.serializeMeta(actual.message().meta())
                );
    }

    private boolean storedMessagePayloadMatches(Connection connection, ConversationHistoryEntry expected) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT role, content, content_json, meta_json, created_at, ordinal FROM messages WHERE id = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, expected.messageId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                Message message = expected.message();
                return Objects.equals(rs.getString("role"), message.role().name())
                        && Objects.equals(rs.getString("content"), message.content())
                        && Objects.equals(rs.getString("content_json"), messageJsonCodec.serializeParts(message.parts()))
                        && Objects.equals(rs.getString("meta_json"), messageJsonCodec.serializeMeta(message.meta()))
                        && rs.getLong("created_at") == message.timestamp().toEpochMilli()
                        && rs.getInt("ordinal") == expected.ordinal();
            }
        }
    }

    private boolean conversationCreateMatches(Connection connection, CreateConversationCommand command) throws SQLException {
        if (!conversationExists(connection, command.conversationId())) {
            return false;
        }
        Optional<ConversationHistoryEntry> entry = findEntry(connection, command.firstEntry().messageId());
        return entry.isPresent()
                && canonicalEntryMatches(connection, command.conversationId(), command.firstEntry(), entry.get())
                && conversationMetadataMatches(connection, command);
    }

    private boolean conversationTitleMatches(Connection connection, UUID conversationId, String title) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT title FROM conversations WHERE id = ?")) {
            sqlDialect.bindUuid(ps, 1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && Objects.equals(rs.getString("title"), title);
            }
        }
    }

    private boolean conversationMetadataMatches(Connection connection, CreateConversationCommand command) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT title, provider, model, reasoning_level, agent_mode_enabled, agent_project_root,
                       web_search_enabled, web_search_option
                FROM conversations
                WHERE id = ?
                """
        )) {
            sqlDialect.bindUuid(ps, 1, command.conversationId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                Path root = command.agentProjectRoot() == null
                        ? null
                        : command.agentProjectRoot().toAbsolutePath().normalize();
                boolean agentEnabled = command.agentModeEnabled() && root != null;
                boolean storedAgentEnabled = rs.getBoolean("agent_mode_enabled");
                boolean storedAgentEnabledPresent = !rs.wasNull();
                boolean storedWebSearchEnabled = rs.getBoolean("web_search_enabled");
                boolean storedWebSearchEnabledPresent = !rs.wasNull();
                return Objects.equals(rs.getString("title"), command.title())
                        && Objects.equals(rs.getString("provider"), command.provider())
                        && Objects.equals(rs.getString("model"), command.model())
                        && Objects.equals(rs.getString("reasoning_level"), command.reasoningLevel().toSettingValue())
                        && storedAgentEnabledPresent
                        && storedAgentEnabled == agentEnabled
                        && Objects.equals(rs.getString("agent_project_root"), root == null ? null : root.toString())
                        && storedWebSearchEnabledPresent
                        && storedWebSearchEnabled == command.webSearchEnabled()
                        && Objects.equals(rs.getString("web_search_option"), StringUtils.trimToNull(command.webSearchOptionId()));
            }
        }
    }

    private void requireEntryIdentity(Connection connection, UUID conversationId, ConversationHistoryEntry entry) throws SQLException {
        if (!entryIdentityMatches(connection, conversationId, entry)) {
            throw new SQLException("Message identity does not belong to conversation: %s".formatted(entry.messageId()));
        }
    }

    private boolean entryIdentityMatches(
            Connection connection,
            UUID conversationId,
            ConversationHistoryEntry entry
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM messages WHERE id = ? AND conversation_id = ? AND ordinal = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, entry.messageId());
            sqlDialect.bindUuid(ps, 2, conversationId);
            ps.setInt(3, entry.ordinal());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void requireRetainedIdentity(Connection connection, UUID conversationId, UUID messageId, int ordinal) throws SQLException {
        if (!retainedIdentityMatches(connection, conversationId, messageId, ordinal)) {
            throw new SQLException("Retained message identity mismatch: %s".formatted(messageId));
        }
    }

    private boolean retainedIdentityMatches(
            Connection connection,
            UUID conversationId,
            UUID messageId,
            int ordinal
    ) throws SQLException {
        Optional<ConversationHistoryEntry> entry = findEntry(connection, messageId);
        return entry.isPresent()
                && entry.get().ordinal() == ordinal
                && entryIdentityMatches(connection, conversationId, entry.get());
    }

    private boolean hasNoSuffix(Connection connection, UUID conversationId, int retainedOrdinal) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM messages WHERE conversation_id = ? AND ordinal > ?"
        )) {
            sqlDialect.bindUuid(ps, 1, conversationId);
            ps.setInt(2, retainedOrdinal);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private int deleteSuffix(Connection connection, UUID conversationId, int retainedOrdinal) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM messages WHERE conversation_id = ? AND ordinal > ?"
        )) {
            sqlDialect.bindUuid(ps, 1, conversationId);
            ps.setInt(2, retainedOrdinal);
            return ps.executeUpdate();
        }
    }

    private int nextOrdinal(Connection connection, UUID conversationId) throws SQLException {
        requireConversation(connection, conversationId);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM messages WHERE conversation_id = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void touchConversation(Connection connection, UUID conversationId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, conversationId);
            requireOneRow(ps.executeUpdate(), "conversation timestamp", conversationId);
        }
    }

    private void requireConversation(Connection connection, UUID id) throws SQLException {
        if (!conversationExists(connection, id)) {
            throw new SQLException("Conversation not found: %s".formatted(id));
        }
    }

    private boolean conversationExists(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM conversations WHERE id = ?")) {
            sqlDialect.bindUuid(ps, 1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private ConversationRecord readConversation(ResultSet rs) throws SQLException {
        return new ConversationRecord(
                sqlDialect.readUuid(rs, "c_id"), rs.getString("title"), rs.getString("provider"), rs.getString("model"),
                rs.getBoolean("is_favorite"), rs.getString("reasoning_level"), rs.getBoolean("agent_mode_enabled"),
                rs.getString("agent_project_root"), rs.getBoolean("web_search_enabled"), rs.getString("web_search_option"),
                rs.getTimestamp("c_created_at").toLocalDateTime(), rs.getTimestamp("c_updated_at").toLocalDateTime());
    }

    private void updateConversation(UUID id, String sql, StatementBinder binder) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            sqlDialect.bindUuid(ps, 2, id);
            requireOneRow(ps.executeUpdate(), "conversation update", id);
        }
    }

    private void requireOneRow(int affected, String operation, UUID id) throws SQLException {
        if (affected != 1) {
            throw new SQLException("%s affected %d rows for conversation %s".formatted(operation, affected, id));
        }
    }

    public void cleanupUnreferencedManagedAttachmentFiles() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            attachmentStore.cleanupUnreferencedManagedFiles(connection);
        }
    }

    private void executeAttachmentMutation(TransactionOperation<List<Path>> operation) throws SQLException {
        List<Path> cleanupCandidates = new ArrayList<>();
        try {
            List<Path> orphanFiles = inTransaction(connection -> {
                List<Path> result = operation.execute(connection);
                cleanupCandidates.addAll(result);
                return result;
            });
            attachmentStore.deleteAttachmentFiles(orphanFiles);
        } catch (SQLException | RuntimeException e) {
            cleanupAfterUncertainCommit(cleanupCandidates, e);
            throw e;
        }
    }

    private void cleanupAfterUncertainCommit(List<Path> candidates, Throwable failure) {
        if (candidates.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            List<Path> orphanFiles = attachmentStore.findUnreferencedAttachmentFiles(connection, candidates);
            attachmentStore.deleteAttachmentFiles(orphanFiles);
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private <T> T inTransaction(TransactionOperation<T> operation) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackSafely(connection, e);
                throw e;
            }
        }
    }

    private void rollbackSafely(Connection connection, Throwable error) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            error.addSuppressed(e);
        }
    }


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

    @FunctionalInterface
    private interface TransactionOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    public record CreateConversationCommand(
            UUID conversationId,
            String title,
            String provider,
            String model,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            boolean webSearchEnabled,
            String webSearchOptionId,
            ConversationHistoryEntry firstEntry
    ) {
        public CreateConversationCommand {
            Objects.requireNonNull(conversationId, "conversationId can't be null");
            reasoningLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
            Objects.requireNonNull(firstEntry, "firstEntry can't be null");
            if (firstEntry.ordinal() != 1) {
                throw new IllegalArgumentException("First entry ordinal must be one");
            }
        }
    }

    public record FirstAfterClearCommand(UUID conversationId, String title, ConversationHistoryEntry entry) {
        public FirstAfterClearCommand {
            Objects.requireNonNull(conversationId, "conversationId can't be null");
            Objects.requireNonNull(entry, "entry can't be null");
            if (entry.ordinal() != 1) {
                throw new IllegalArgumentException("First post-clear entry ordinal must be one");
            }
        }
    }

    public record LoadedConversation(ConversationRecord conversation, List<MessageRecord> messages) {
        public LoadedConversation {
            messages = List.copyOf(messages);
        }
    }

    public record SearchResult(UUID id, String title, String provider, String model, String snippet) {
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
            this(id, title, provider, model, isFavorite, reasoningLevel, agentModeEnabled, agentProjectRoot,
                    false, null, createdAt, updatedAt);
        }
    }

    public record MessageRecord(UUID id, int ordinal, Message message) {
        public ConversationHistoryEntry historyEntry() {
            return new ConversationHistoryEntry(id, ordinal, message);
        }
    }
}
