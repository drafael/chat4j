package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.persistence.db.SqlDialect;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

@Slf4j
class ConversationAttachmentStore {

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

    List<Path> deleteOrphanAttachmentRows(Connection connection) throws SQLException {
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

    void deleteAttachmentFiles(List<Path> paths) {
        paths.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::deleteAttachmentFile);
    }

    private void persistAttachmentLink(Connection connection, UUID messageId, ContentPart part, int partIndex) {
        AttachmentRef attachmentRef = extractAttachmentRef(part);
        if (attachmentRef == null || attachmentRef.id() == null || StringUtils.isBlank(attachmentRef.storagePath())) {
            return;
        }

        try (PreparedStatement ps = connection.prepareStatement(sqlDialect.attachmentUpsertSql())) {
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
}
