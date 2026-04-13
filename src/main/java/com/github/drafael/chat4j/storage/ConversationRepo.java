package com.github.drafael.chat4j.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ContentParts;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;

import javax.sql.DataSource;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

public class ConversationRepo {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public ConversationRepo(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public UUID createConversation(String title, String provider, String model) throws SQLException {
        UUID id = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO conversations (id, title, provider, model) VALUES (?, ?, ?, ?)"
             )
        ) {
            ps.setObject(1, id);
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
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    public void toggleFavorite(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE conversations SET is_favorite = NOT is_favorite, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
             )
        ) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    public void deleteConversation(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM conversations WHERE id = ?"
             )
        ) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    public void addMessage(UUID conversationId, Message message) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            UUID messageId = UUID.randomUUID();

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO messages (id, conversation_id, role, content, content_json, meta_json) VALUES (?, ?, ?, ?, ?, ?)"
            )
            ) {
                ps.setObject(1, messageId);
                ps.setObject(2, conversationId);
                ps.setString(3, message.role().name());
                ps.setString(4, message.content());
                ps.setString(5, serializeParts(message.parts()));
                ps.setString(6, serializeMeta(message.meta()));
                ps.executeUpdate();
            }

            persistAttachmentLinks(connection, messageId, message.parts());

            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = ?"
            )
            ) {
                ps.setObject(1, conversationId);
                ps.executeUpdate();
            }
        }
    }

    public void addMessage(UUID conversationId, String role, String content) throws SQLException {
        addMessage(conversationId, new Message(parseRole(role), content, Instant.now()));
    }

    public Optional<ConversationRecord> findById(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, title, provider, model, is_favorite, created_at, updated_at FROM conversations WHERE id = ?"
             )
        ) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ConversationRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("provider"),
                        rs.getString("model"),
                        rs.getBoolean("is_favorite"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()
                    ));
                }
            }
        }

        return Optional.empty();
    }

    public List<MessageRecord> getMessages(UUID conversationId) throws SQLException {
        List<MessageRecord> messages = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, role, content, content_json, meta_json, created_at "
                             + "FROM messages WHERE conversation_id = ? ORDER BY created_at"
             )
        ) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                    messages.add(new MessageRecord(
                        rs.getObject("id", UUID.class),
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

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, title, provider, model, is_favorite, created_at, updated_at "
                             + "FROM conversations ORDER BY updated_at DESC"
             );
             ResultSet rs = ps.executeQuery()
        ) {
            while (rs.next()) {
                ConversationRecord rec = new ConversationRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("provider"),
                    rs.getString("model"),
                    rs.getBoolean("is_favorite"),
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

        if (grouped.get("Favorites").isEmpty()) {
            grouped.remove("Favorites");
        }

        return grouped;
    }

    public List<SearchResult> search(String query) throws SQLException {
        List<SearchResult> results = new ArrayList<>();
        String like = "%" + query.toLowerCase() + "%";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT c.id, c.title, c.provider, c.model, c.updated_at, "
                     + "NULL AS snippet FROM conversations c "
                     + "WHERE LOWER(c.title) LIKE ? "
                     + "UNION ALL "
                     + "SELECT c.id, c.title, c.provider, c.model, c.updated_at, "
                     + "SUBSTRING(m.content, 1, 120) AS snippet FROM messages m "
                     + "JOIN conversations c ON c.id = m.conversation_id "
                     + "WHERE LOWER(m.content) LIKE ? "
                     + "ORDER BY updated_at DESC"
             )
        ) {
            ps.setString(1, like);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                Set<UUID> seen = new HashSet<>();
                while (rs.next()) {
                    UUID id = rs.getObject("id", UUID.class);
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

    private void persistAttachmentLinks(Connection connection, UUID messageId, List<ContentPart> parts) throws SQLException {
        if (parts == null || parts.isEmpty()) {
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
        if (attachmentRef == null || attachmentRef.id() == null || attachmentRef.storagePath().isBlank()) {
            return;
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) "
                        + "KEY (id) VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            ps.setObject(1, attachmentRef.id());
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
            ps.setObject(1, messageId);
            ps.setObject(2, attachmentRef.id());
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
                .filter(part -> part != null)
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
        return node.toString();
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
        if (contentJson == null || contentJson.isBlank()) {
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
        if (metaJson == null || metaJson.isBlank()) {
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
                    node.path("error").asText("")
            );
        } catch (Exception e) {
            return MessageMeta.empty();
        }
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
        if (value == null || value.isBlank()) {
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
        if (role == null || role.isBlank()) {
            return Role.USER;
        }

        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            return Role.USER;
        }
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record MessageRecord(
        UUID id,
        Message message,
        LocalDateTime createdAt
    ) {}
}
