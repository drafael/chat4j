package com.github.drafael.chat4j.storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

public class ConversationRepo {

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

    public void addMessage(UUID conversationId, String role, String content) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)"
            )
            ) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, conversationId);
                ps.setString(3, role);
                ps.setString(4, content);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE id = ?"
            )
            ) {
                ps.setObject(1, conversationId);
                ps.executeUpdate();
            }
        }
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
                     "SELECT id, role, content, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at"
             )
        ) {
            ps.setObject(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messages.add(new MessageRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()
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
                     "SELECT id, title, provider, model, is_favorite, created_at, updated_at " +
                             "FROM conversations ORDER BY updated_at DESC"
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

        // Search in conversation titles
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT c.id, c.title, c.provider, c.model, c.updated_at, " +
                     "NULL AS snippet FROM conversations c " +
                     "WHERE LOWER(c.title) LIKE ? " +
                     "UNION ALL " +
                     "SELECT c.id, c.title, c.provider, c.model, c.updated_at, " +
                     "SUBSTRING(m.content, 1, 120) AS snippet FROM messages m " +
                     "JOIN conversations c ON c.id = m.conversation_id " +
                     "WHERE LOWER(m.content) LIKE ? " +
                     "ORDER BY updated_at DESC"
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
                    if (results.size() >= 20) break;
                }
            }
        }
        return results;
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

        if (date.equals(today)) return "Today";
        if (date.equals(today.minusDays(1))) return "Yesterday";
        if (date.isAfter(today.minusDays(7))) return "This Week";
        if (date.isAfter(today.minusDays(30))) return "This Month";
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
        String role,
        String content,
        LocalDateTime createdAt
    ) {}
}
