package com.github.drafael.chat4j.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ContentParts;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

@Slf4j
public class ConversationRepo {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final Path attachmentRoot;
    private final SqlDialect sqlDialect;

    public ConversationRepo(DataSource dataSource) {
        this(dataSource, null, new H2SqlDialect());
    }

    public ConversationRepo(DataSource dataSource, Path attachmentRoot) {
        this(dataSource, attachmentRoot, new H2SqlDialect());
    }

    public ConversationRepo(DataSource dataSource, Path attachmentRoot, SqlDialect sqlDialect) {
        this.dataSource = dataSource;
        this.attachmentRoot = normalizeAttachmentRoot(attachmentRoot);
        this.sqlDialect = sqlDialect;
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
        deleteAttachmentFiles(orphanAttachmentFiles);
    }

    public void deleteMessages(UUID conversationId) throws SQLException {
        List<Path> orphanAttachmentFiles = deleteRowsAndCollectOrphanAttachmentFiles(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM messages WHERE conversation_id = ?")) {
                sqlDialect.bindUuid(ps, 1, conversationId);
                ps.executeUpdate();
            }
        });
        deleteAttachmentFiles(orphanAttachmentFiles);
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
        deleteAttachmentFiles(orphanAttachmentFiles);
    }

    public void deleteAllConversations() throws SQLException {
        List<Path> orphanAttachmentFiles = deleteRowsAndCollectOrphanAttachmentFiles(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM conversations")) {
                ps.executeUpdate();
            }
        });
        deleteAttachmentFiles(orphanAttachmentFiles);
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
                    ps.setString(5, serializeParts(message.parts()));
                    ps.setString(6, serializeMeta(message.meta()));
                    ps.executeUpdate();
                }

                persistAttachmentLinks(connection, messageId, message.parts());

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
        addMessage(conversationId, new Message(parseRole(role), content, Instant.now()));
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
                        deserializeMessage(
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
                List<Path> orphanAttachmentFiles = deleteOrphanAttachmentRows(connection);
                connection.commit();
                return orphanAttachmentFiles;
            } catch (SQLException | RuntimeException e) {
                rollbackSafely(connection, e);
                throw e;
            }
        }
    }

    private List<Path> deleteOrphanAttachmentRows(Connection connection) throws SQLException {
        List<Path> orphanAttachmentFiles = findOrphanAttachmentFiles(connection);
        if (orphanAttachmentFiles.isEmpty()) {
            return emptyList();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                """
                DELETE FROM attachments
                WHERE NOT EXISTS (
                    SELECT 1 FROM message_attachments ma WHERE ma.attachment_id = attachments.id
                )
                """
        )) {
            ps.executeUpdate();
        }

        return orphanAttachmentFiles;
    }

    private List<Path> findOrphanAttachmentFiles(Connection connection) throws SQLException {
        List<Path> orphanAttachmentFiles = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT storage_path
                FROM attachments a
                WHERE NOT EXISTS (
                    SELECT 1 FROM message_attachments ma WHERE ma.attachment_id = a.id
                )
                """
        );
             ResultSet rs = ps.executeQuery()
        ) {
            while (rs.next()) {
                Path path = managedAttachmentPath(rs.getString("storage_path"));
                if (path != null) {
                    orphanAttachmentFiles.add(path);
                }
            }
        }
        return orphanAttachmentFiles.stream()
                .distinct()
                .toList();
    }

    private void deleteAttachmentFiles(List<Path> paths) {
        paths.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::deleteAttachmentFile);
    }

    private void deleteAttachmentFile(Path path) {
        if (!isManagedAttachmentPath(path)) {
            log.warn("Skipping attachment file outside managed attachment root: {}", path);
            return;
        }

        try {
            Files.deleteIfExists(path);
            pruneEmptyAttachmentDirectories(path.getParent());
        } catch (IOException e) {
            log.warn("Failed to delete attachment file: {}", path, e);
        }
    }

    private Path managedAttachmentPath(String storagePath) {
        if (attachmentRoot == null || StringUtils.isBlank(storagePath)) {
            return null;
        }

        try {
            Path path = normalizeExistingPath(Path.of(storagePath));
            return path.startsWith(attachmentRoot) ? path : null;
        } catch (InvalidPathException e) {
            log.warn("Ignoring invalid attachment storage path: {}", storagePath, e);
            return null;
        }
    }

    private boolean isManagedAttachmentPath(Path path) {
        return attachmentRoot != null && normalizeExistingPath(path).startsWith(attachmentRoot);
    }

    private static Path normalizeAttachmentRoot(Path path) {
        if (path == null) {
            return null;
        }
        return normalizeExistingPath(path);
    }

    private static Path normalizeExistingPath(Path path) {
        Path absolutePath = path.toAbsolutePath().normalize();
        if (!Files.exists(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
            return absolutePath;
        }

        try {
            return absolutePath.toRealPath();
        } catch (IOException e) {
            return absolutePath;
        }
    }

    private void pruneEmptyAttachmentDirectories(Path startDirectory) {
        if (startDirectory == null || attachmentRoot == null) {
            return;
        }

        Path current = startDirectory.toAbsolutePath().normalize();
        while (!current.equals(attachmentRoot) && current.startsWith(attachmentRoot)) {
            try {
                Files.deleteIfExists(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
            if (current == null) {
                return;
            }
        }
    }

    private void ensureConversationWebSearchColumns(Connection connection) throws SQLException {
        sqlDialect.ensureConversationWebSearchColumns(connection);
    }

    private void persistAttachmentLinks(Connection connection, UUID messageId, List<ContentPart> parts) throws SQLException {
        if (ObjectUtils.isEmpty(parts)) {
            return;
        }

        try {
            IntStream.range(0, parts.size())
                    .forEach(partIndex -> persistAttachmentLink(connection, messageId, parts.get(partIndex), partIndex));
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw e;
        }
    }

    private void persistAttachmentLink(Connection connection, UUID messageId, ContentPart part, int partIndex) {
        AttachmentRef attachmentRef = extractAttachmentRef(part);
        if (attachmentRef == null || attachmentRef.id() == null || StringUtils.isBlank(attachmentRef.storagePath())) {
            return;
        }

        try (PreparedStatement ps = connection.prepareStatement(
                sqlDialect.attachmentUpsertSql()
        )) {
            sqlDialect.bindUuid(ps, 1, attachmentRef.id());
            ps.setString(2, attachmentRef.storagePath());
            ps.setString(3, attachmentRef.originalName());
            ps.setString(4, attachmentRef.mimeType());
            ps.setLong(5, attachmentRef.sizeBytes());
            ps.setString(6, attachmentRef.sha256());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO message_attachments (message_id, attachment_id, part_index) VALUES (?, ?, ?)"
        )) {
            sqlDialect.bindUuid(ps, 1, messageId);
            sqlDialect.bindUuid(ps, 2, attachmentRef.id());
            ps.setInt(3, partIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private AttachmentRef extractAttachmentRef(ContentPart part) {
        if (part instanceof ImagePart imagePart) {
            return imagePart.attachmentRef();
        }

        if (part instanceof FilePart filePart) {
            return filePart.attachmentRef();
        }

        return null;
    }

    private String serializeParts(List<ContentPart> parts) {
        ArrayNode root = JSON.createArrayNode();
        if (parts == null) {
            return root.toString();
        }

        parts.stream()
                .filter(Objects::nonNull)
                .map(this::serializePart)
                .forEach(root::add);

        return root.toString();
    }

    private ObjectNode serializePart(ContentPart part) {
        ObjectNode node = JSON.createObjectNode();

        if (part instanceof TextPart textPart) {
            node.put("type", "text");
            node.put("text", textPart.text());
            return node;
        }

        if (part instanceof ImagePart imagePart) {
            node.put("type", "image");
            node.set("attachment", serializeAttachment(imagePart.attachmentRef()));
            if (imagePart.width() != null) {
                node.put("width", imagePart.width());
            }
            if (imagePart.height() != null) {
                node.put("height", imagePart.height());
            }
            return node;
        }

        if (part instanceof FilePart filePart) {
            node.put("type", "file");
            node.set("attachment", serializeAttachment(filePart.attachmentRef()));
            return node;
        }

        node.put("type", "text");
        node.put("text", part.asTextProjection());
        return node;
    }

    private ObjectNode serializeAttachment(AttachmentRef attachmentRef) {
        ObjectNode node = JSON.createObjectNode();
        AttachmentRef value = attachmentRef == null
                ? new AttachmentRef(null, null, null, null, 0L, null)
                : attachmentRef;

        if (value.id() == null) {
            node.putNull("id");
        } else {
            node.put("id", value.id().toString());
        }

        node.put("storagePath", value.storagePath());
        node.put("originalName", value.originalName());
        node.put("mimeType", value.mimeType());
        node.put("sizeBytes", value.sizeBytes());
        node.put("sha256", value.sha256());
        return node;
    }

    private String serializeMeta(MessageMeta meta) {
        MessageMeta value = meta == null ? MessageMeta.empty() : meta;
        ObjectNode node = JSON.createObjectNode();

        ArrayNode activeSkills = JSON.createArrayNode();
        value.activeSkills().forEach(activeSkills::add);
        node.set("activeSkills", activeSkills);

        ArrayNode fallbackNotices = JSON.createArrayNode();
        value.fallbackNotices().forEach(fallbackNotices::add);
        node.set("fallbackNotices", fallbackNotices);

        node.put("cancelled", value.cancelled());
        node.put("error", value.error());
        node.put("assistantThinking", value.assistantThinking());
        node.put("assistantWebSearch", value.assistantWebSearch());

        ArrayNode agentToolActivities = JSON.createArrayNode();
        value.agentToolActivities().stream()
                .map(this::serializeAgentToolActivity)
                .forEach(agentToolActivities::add);
        node.set("agentToolActivities", agentToolActivities);
        return node.toString();
    }

    private ObjectNode serializeAgentToolActivity(AgentToolActivityMeta activity) {
        AgentToolActivityMeta value = activity == null
                ? new AgentToolActivityMeta("", "unknown", "STARTED", "", "")
                : activity;
        ObjectNode node = JSON.createObjectNode();
        node.put("invocationId", value.invocationId());
        node.put("toolName", value.toolName());
        node.put("status", value.status());
        node.put("argumentsSummary", value.argumentsSummary());
        node.put("message", value.message());
        return node;
    }

    private Message deserializeMessage(String role,
                                       String content,
                                       String contentJson,
                                       String metaJson,
                                       LocalDateTime createdAt
    ) {
        List<ContentPart> parts = deserializeParts(contentJson, content);
        MessageMeta meta = deserializeMeta(metaJson);
        Instant timestamp = createdAt.atZone(ZoneId.systemDefault()).toInstant();
        return new Message(parseRole(role), parts, timestamp, meta);
    }

    private List<ContentPart> deserializeParts(String contentJson, String fallbackContent) {
        if (StringUtils.isBlank(contentJson)) {
            return ContentParts.ofText(fallbackContent);
        }

        try {
            JsonNode root = JSON.readTree(contentJson);
            if (!root.isArray()) {
                return ContentParts.ofText(fallbackContent);
            }

            List<ContentPart> parts = new ArrayList<>();
            root.forEach(node -> deserializePart(node).ifPresent(parts::add));
            if (parts.isEmpty()) {
                return ContentParts.ofText(fallbackContent);
            }
            return List.copyOf(parts);
        } catch (Exception e) {
            return ContentParts.ofText(fallbackContent);
        }
    }

    private Optional<ContentPart> deserializePart(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> Optional.of(new TextPart(node.path("text").asText("")));
            case "image" -> Optional.of(new ImagePart(
                    deserializeAttachment(node.path("attachment")),
                    readOptionalInt(node, "width"),
                    readOptionalInt(node, "height")
            ));
            case "file" -> Optional.of(new FilePart(deserializeAttachment(node.path("attachment"))));
            default -> Optional.empty();
        };
    }

    private MessageMeta deserializeMeta(String metaJson) {
        if (StringUtils.isBlank(metaJson)) {
            return MessageMeta.empty();
        }

        try {
            JsonNode node = JSON.readTree(metaJson);
            List<String> activeSkills = new ArrayList<>();
            JsonNode skillsNode = node.path("activeSkills");
            if (skillsNode.isArray()) {
                skillsNode.forEach(skillNode -> {
                    String skill = skillNode.asText("").trim();
                    if (!skill.isBlank()) {
                        activeSkills.add(skill);
                    }
                });
            }

            List<String> fallbackNotices = new ArrayList<>();
            JsonNode noticesNode = node.path("fallbackNotices");
            if (noticesNode.isArray()) {
                noticesNode.forEach(noticeNode -> {
                    String notice = noticeNode.asText("").trim();
                    if (!notice.isBlank()) {
                        fallbackNotices.add(notice);
                    }
                });
            }

            return new MessageMeta(
                    activeSkills,
                    fallbackNotices,
                    node.path("cancelled").asBoolean(false),
                    node.path("error").asText(""),
                    node.path("assistantThinking").asText(""),
                    node.path("assistantWebSearch").asText(""),
                    deserializeAgentToolActivities(node.path("agentToolActivities"))
            );
        } catch (Exception e) {
            return MessageMeta.empty();
        }
    }

    private List<AgentToolActivityMeta> deserializeAgentToolActivities(JsonNode node) {
        if (node == null || !node.isArray()) {
            return emptyList();
        }

        List<AgentToolActivityMeta> activities = new ArrayList<>();
        node.forEach(activityNode -> {
            if (activityNode == null || !activityNode.isObject()) {
                return;
            }

            activities.add(new AgentToolActivityMeta(
                    activityNode.path("invocationId").asText(""),
                    activityNode.path("toolName").asText("unknown"),
                    activityNode.path("status").asText("STARTED"),
                    activityNode.path("argumentsSummary").asText(""),
                    activityNode.path("message").asText("")
            ));
        });
        return List.copyOf(activities);
    }

    private AttachmentRef deserializeAttachment(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new AttachmentRef(null, null, null, null, 0L, null);
        }

        return new AttachmentRef(
                parseUuid(node.path("id").asText(null)),
                node.path("storagePath").asText(""),
                node.path("originalName").asText(""),
                node.path("mimeType").asText(""),
                node.path("sizeBytes").asLong(0L),
                node.path("sha256").asText("")
        );
    }

    private UUID parseUuid(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Integer readOptionalInt(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private Role parseRole(String role) {
        if (StringUtils.isBlank(role)) {
            return Role.USER;
        }

        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            return Role.USER;
        }
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
