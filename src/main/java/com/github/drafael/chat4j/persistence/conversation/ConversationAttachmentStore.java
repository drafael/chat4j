package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.persistence.db.SqlDialect;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

@Slf4j
class ConversationAttachmentStore {

    private static final Duration ORPHAN_CLEANUP_GRACE = Duration.ofDays(1);

    private final Path attachmentRoot;
    private final SqlDialect sqlDialect;

    ConversationAttachmentStore(Path attachmentRoot, SqlDialect sqlDialect) {
        this.attachmentRoot = normalizeAttachmentRoot(attachmentRoot);
        this.sqlDialect = sqlDialect;
    }

    void persistAttachmentLinks(Connection connection, UUID messageId, List<ContentPart> parts) throws SQLException {
        if (ObjectUtils.isEmpty(parts)) {
            return;
        }
        validateAttachmentRefs(parts);

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

    boolean attachmentLinksMatch(Connection connection, UUID messageId, List<ContentPart> parts) throws SQLException {
        if (!attachmentRefsValid(parts)) {
            return false;
        }
        List<IndexedAttachmentRef> expectedRefs = IntStream.range(0, parts.size())
                .filter(partIndex -> isAttachmentPart(parts.get(partIndex)))
                .mapToObj(partIndex -> new IndexedAttachmentRef(partIndex, extractAttachmentRef(parts.get(partIndex))))
                .toList();
        List<AttachmentLink> expected = expectedRefs.stream()
                .map(link -> new AttachmentLink(link.partIndex(), link.attachmentRef().id()))
                .toList();
        List<AttachmentLink> actual = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT attachment_id, part_index FROM message_attachments WHERE message_id = ? ORDER BY part_index"
        )) {
            sqlDialect.bindUuid(ps, 1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID attachmentId = sqlDialect.readUuid(rs, "attachment_id");
                    actual.add(new AttachmentLink(rs.getInt("part_index"), attachmentId));
                }
            }
        }
        boolean linksMatch = expected.size() == actual.size()
                && IntStream.range(0, expected.size()).allMatch(index ->
                expected.get(index).partIndex() == actual.get(index).partIndex()
                        && Objects.equals(
                        expected.get(index).attachmentId(),
                        actual.get(index).attachmentId()
                ));
        if (!linksMatch) {
            return false;
        }
        for (IndexedAttachmentRef expectedRef : expectedRefs) {
            if (!attachmentMatches(connection, expectedRef.attachmentRef())) {
                return false;
            }
        }
        return true;
    }

    Set<UUID> findAttachmentIdsForMessage(Connection connection, UUID messageId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT attachment_id FROM message_attachments WHERE message_id = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, messageId);
            return readAttachmentIds(ps);
        }
    }

    Set<UUID> findAttachmentIdsForSuffix(
            Connection connection,
            UUID conversationId,
            int retainedOrdinal
    ) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT DISTINCT ma.attachment_id
                FROM message_attachments ma
                JOIN messages m ON m.id = ma.message_id
                WHERE m.conversation_id = ? AND m.ordinal > ?
                """
        )) {
            sqlDialect.bindUuid(ps, 1, conversationId);
            ps.setInt(2, retainedOrdinal);
            return readAttachmentIds(ps);
        }
    }

    Set<UUID> findAttachmentIdsForConversations(
            Connection connection,
            Collection<UUID> conversationIds
    ) throws SQLException {
        Set<UUID> attachmentIds = new LinkedHashSet<>();
        List<UUID> ids = conversationIds.stream().filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < ids.size(); start += 400) {
            List<UUID> batch = ids.subList(start, Math.min(start + 400, ids.size()));
            String placeholders = String.join(",", batch.stream().map(ignored -> "?").toList());
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    SELECT DISTINCT ma.attachment_id
                    FROM message_attachments ma
                    JOIN messages m ON m.id = ma.message_id
                    WHERE m.conversation_id IN (%s)
                    """.formatted(placeholders)
            )) {
                for (int index = 0; index < batch.size(); index++) {
                    sqlDialect.bindUuid(ps, index + 1, batch.get(index));
                }
                attachmentIds.addAll(readAttachmentIds(ps));
            }
        }
        return attachmentIds;
    }

    List<Path> deleteOrphanAttachmentRows(
            Connection connection,
            Collection<UUID> candidateAttachmentIds
    ) throws SQLException {
        List<UUID> candidates = candidateAttachmentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return emptyList();
        }

        List<Path> fileCandidates = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += 400) {
            List<UUID> batch = candidates.subList(start, Math.min(start + 400, candidates.size()));
            String placeholders = String.join(",", batch.stream().map(ignored -> "?").toList());
            try (PreparedStatement select = connection.prepareStatement(
                    """
                    SELECT storage_path
                    FROM attachments a
                    WHERE id IN (%s)
                      AND NOT EXISTS (
                          SELECT 1 FROM message_attachments ma WHERE ma.attachment_id = a.id
                      )
                    """.formatted(placeholders)
            )) {
                bindAttachmentIds(select, batch);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        String storagePath = rs.getString("storage_path");
                        Path path = managedAttachmentPath(storagePath);
                        if (path != null) {
                            fileCandidates.add(path);
                        }
                    }
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    """
                    DELETE FROM attachments
                    WHERE id IN (%s)
                      AND NOT EXISTS (
                          SELECT 1 FROM message_attachments ma WHERE ma.attachment_id = attachments.id
                      )
                    """.formatted(placeholders)
            )) {
                bindAttachmentIds(delete, batch);
                delete.executeUpdate();
            }
        }
        if (fileCandidates.isEmpty()) {
            return emptyList();
        }
        Set<Path> retainedPaths = findManagedAttachmentPaths(connection);
        return fileCandidates.stream()
                .filter(path -> !retainedPaths.contains(path))
                .distinct()
                .toList();
    }

    List<Path> findUnreferencedAttachmentFiles(
            Connection connection,
            Collection<Path> candidatePaths
    ) throws SQLException {
        Set<Path> retainedPaths = findManagedAttachmentPaths(connection);
        return candidatePaths.stream()
                .filter(Objects::nonNull)
                .filter(path -> !retainedPaths.contains(normalizeExistingPath(path)))
                .distinct()
                .toList();
    }

    void deleteAttachmentFiles(List<Path> paths) {
        paths.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::deleteAttachmentFile);
    }

    void cleanupUnreferencedManagedFiles(Connection connection) throws SQLException {
        if (attachmentRoot == null || !Files.isDirectory(attachmentRoot)) {
            return;
        }
        List<Path> candidates;
        Instant cutoff = Instant.now().minus(ORPHAN_CLEANUP_GRACE);
        try (var paths = Files.walk(attachmentRoot)) {
            candidates = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> lastModifiedBefore(path, cutoff))
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to scan managed attachments for orphan cleanup", e);
            return;
        }
        deleteAttachmentFiles(findUnreferencedAttachmentFiles(connection, candidates));
    }

    private boolean lastModifiedBefore(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            log.debug("Skipping managed attachment with unreadable modification time: {}", path, e);
            return false;
        }
    }

    private void persistAttachmentLink(Connection connection, UUID messageId, ContentPart part, int partIndex) {
        if (!isAttachmentPart(part)) {
            return;
        }
        AttachmentRef attachmentRef = extractAttachmentRef(part);

        try {
            insertOrVerifyAttachment(connection, attachmentRef);
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

    private void insertOrVerifyAttachment(Connection connection, AttachmentRef attachmentRef) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO attachments (id, storage_path, original_name, mime_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            sqlDialect.bindUuid(ps, 1, attachmentRef.id());
            ps.setString(2, canonicalStoragePath(attachmentRef.storagePath()));
            ps.setString(3, attachmentRef.originalName());
            ps.setString(4, attachmentRef.mimeType());
            ps.setLong(5, attachmentRef.sizeBytes());
            ps.setString(6, attachmentRef.sha256());
            ps.executeUpdate();
            return;
        } catch (SQLException insertFailure) {
            if (attachmentMatches(connection, attachmentRef)) {
                return;
            }
            throw insertFailure;
        }
    }

    private boolean attachmentMatches(Connection connection, AttachmentRef attachmentRef) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT storage_path, original_name, mime_type, size_bytes, sha256 FROM attachments WHERE id = ?"
        )) {
            sqlDialect.bindUuid(ps, 1, attachmentRef.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                long storedSize = rs.getLong("size_bytes");
                boolean sizeMatches = !rs.wasNull() && storedSize == attachmentRef.sizeBytes();
                return Objects.equals(
                            canonicalStoragePath(rs.getString("storage_path")),
                            canonicalStoragePath(attachmentRef.storagePath())
                        )
                        && Objects.equals(rs.getString("original_name"), attachmentRef.originalName())
                        && Objects.equals(rs.getString("mime_type"), attachmentRef.mimeType())
                        && sizeMatches
                        && Objects.equals(rs.getString("sha256"), attachmentRef.sha256());
            }
        }
    }

    private void validateAttachmentRefs(List<ContentPart> parts) throws SQLException {
        if (!attachmentRefsValid(parts)) {
            throw new SQLException("Attachment parts require an ID and storage path under the managed root");
        }
    }

    private boolean attachmentRefsValid(List<ContentPart> parts) {
        if (ObjectUtils.isEmpty(parts)) {
            return true;
        }
        return parts.stream()
                .filter(this::isAttachmentPart)
                .map(this::extractAttachmentRef)
                .allMatch(attachmentRef -> attachmentRef != null
                        && attachmentRef.id() != null
                        && StringUtils.isNotBlank(attachmentRef.storagePath())
                        && (attachmentRoot == null || managedAttachmentPath(attachmentRef.storagePath()) != null));
    }

    private boolean isAttachmentPart(ContentPart part) {
        return part instanceof ImagePart || part instanceof FilePart || part instanceof GeneratedImagePart;
    }

    private AttachmentRef extractAttachmentRef(ContentPart part) {
        if (part instanceof ImagePart imagePart) {
            return imagePart.attachmentRef();
        }

        if (part instanceof FilePart filePart) {
            return filePart.attachmentRef();
        }

        if (part instanceof GeneratedImagePart generatedImagePart) {
            return generatedImagePart.attachmentRef();
        }

        return null;
    }

    private Set<UUID> readAttachmentIds(PreparedStatement statement) throws SQLException {
        Set<UUID> attachmentIds = new LinkedHashSet<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                attachmentIds.add(sqlDialect.readUuid(rs, "attachment_id"));
            }
        }
        return attachmentIds;
    }

    private void bindAttachmentIds(PreparedStatement statement, List<UUID> attachmentIds) throws SQLException {
        for (int index = 0; index < attachmentIds.size(); index++) {
            sqlDialect.bindUuid(statement, index + 1, attachmentIds.get(index));
        }
    }

    private Set<Path> findManagedAttachmentPaths(Connection connection) throws SQLException {
        Set<Path> paths = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT storage_path FROM attachments");
             ResultSet rs = statement.executeQuery()
        ) {
            while (rs.next()) {
                Path path = managedAttachmentPath(rs.getString("storage_path"));
                if (path != null) {
                    paths.add(path);
                }
            }
        }
        return paths;
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

    static String canonicalStoragePath(String storagePath) {
        if (StringUtils.isBlank(storagePath)) {
            return storagePath;
        }
        try {
            return normalizeExistingPath(Path.of(storagePath)).toString();
        } catch (InvalidPathException e) {
            return storagePath;
        }
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

    private record IndexedAttachmentRef(int partIndex, AttachmentRef attachmentRef) {
    }

    private record AttachmentLink(int partIndex, UUID attachmentId) {
    }
}
