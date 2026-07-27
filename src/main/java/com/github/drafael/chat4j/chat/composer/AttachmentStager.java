package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

public class AttachmentStager {

    private static final DateTimeFormatter DAY_DIR_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final StoragePaths storagePaths;

    public AttachmentStager(StoragePaths storagePaths) {
        this.storagePaths = storagePaths;
    }

    public AttachmentRef stage(ComposerAttachment attachment) throws IOException {
        UUID id = UUID.randomUUID();
        Path finalPath = targetPath(id);
        Path partialPath = finalPath.resolveSibling("%s.part".formatted(id));
        boolean partialOwned = false;
        boolean finalOwned = false;
        try {
            try (SeekableByteChannel input = Files.newByteChannel(attachment.path(), StandardOpenOption.READ);
                 OutputStream output = Files.newOutputStream(
                         partialPath,
                         StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE
                 )) {
                partialOwned = true;
                copyBounded(input, output, attachment.path());
            }
            Files.move(partialPath, finalPath);
            partialOwned = false;
            finalOwned = true;
            return attachmentRef(id, finalPath, attachment.displayName(), attachment.mimeType());
        } catch (IOException | RuntimeException e) {
            deleteOwned(partialOwned ? partialPath : null, e);
            deleteOwned(finalOwned ? finalPath : null, e);
            throw e;
        }
    }

    public void discard(AttachmentRef attachmentRef) {
        if (attachmentRef == null) {
            return;
        }
        managedLeaf(attachmentRef.storagePath()).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best-effort cleanup; repository orphan recovery remains authoritative after persistence.
            }
        });
    }

    public Optional<Path> managedPath(String storagePath) {
        if (StringUtils.isBlank(storagePath)) {
            return Optional.empty();
        }
        try {
            Path attachmentRoot = normalizeExisting(storagePaths.attachmentsDirectory());
            Path attachmentPath = normalizeExisting(Path.of(storagePath));
            return attachmentPath.startsWith(attachmentRoot)
                    ? Optional.of(attachmentPath)
                    : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> managedLeaf(String storagePath) {
        if (StringUtils.isBlank(storagePath)) {
            return Optional.empty();
        }
        try {
            Path configuredRoot = storagePaths.attachmentsDirectory().toAbsolutePath().normalize();
            Path attachmentRoot = configuredRoot.toRealPath();
            Path attachmentPath = Path.of(storagePath).toAbsolutePath().normalize();
            Path lexicalRoot = attachmentPath.startsWith(configuredRoot)
                    ? configuredRoot
                    : attachmentPath.startsWith(attachmentRoot) ? attachmentRoot : null;
            Path parent = attachmentPath.getParent();
            if (lexicalRoot == null
                    || parent == null
                    || containsSymbolicLink(lexicalRoot, parent)) {
                return Optional.empty();
            }
            Path resolvedParent = parent.toRealPath();
            return resolvedParent.startsWith(attachmentRoot)
                    ? Optional.of(resolvedParent.resolve(attachmentPath.getFileName()))
                    : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void copyBounded(SeekableByteChannel input, OutputStream output, Path source) throws IOException {
        if (input.size() > AttachmentSelectionPolicy.MAX_ATTACHMENT_BYTES) {
            throw attachmentTooLarge(source);
        }
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long copied = 0;
        while (copied < AttachmentSelectionPolicy.MAX_ATTACHMENT_BYTES) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), AttachmentSelectionPolicy.MAX_ATTACHMENT_BYTES - copied));
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            output.write(buffer.array(), 0, read);
            copied += read;
        }
        if (input.size() > copied) {
            throw attachmentTooLarge(source);
        }
    }

    private IOException attachmentTooLarge(Path source) {
        return new IOException("File is too large (max 20MB): %s".formatted(source.getFileName()));
    }

    private Path normalizeExisting(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        return Files.exists(normalized) ? normalized.toRealPath() : normalized;
    }

    private boolean containsSymbolicLink(Path root, Path directory) {
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private Path targetPath(UUID id) throws IOException {
        return attachmentsDirectory().resolve(id.toString());
    }

    private AttachmentRef attachmentRef(UUID id, Path targetPath, String displayName, String mimeType) throws IOException {
        Path canonicalTarget = targetPath.toRealPath();
        String sha256 = sha256Hex(canonicalTarget);
        long size = Files.size(canonicalTarget);

        return new AttachmentRef(
                id,
                canonicalTarget.toString(),
                displayName,
                mimeType,
                size,
                sha256
        );
    }

    private Path attachmentsDirectory() throws IOException {
        Path configuredRoot = storagePaths.attachmentsDirectory().toAbsolutePath().normalize();
        Files.createDirectories(configuredRoot);
        Path attachmentRoot = configuredRoot.toRealPath();
        Path configuredDayDirectory = configuredRoot.resolve(LocalDate.now().format(DAY_DIR_FORMAT));
        Files.createDirectories(configuredDayDirectory);
        if (Files.isSymbolicLink(configuredDayDirectory)) {
            throw new IOException("Managed attachment directory must not be a symbolic link.");
        }
        Path dayDirectory = configuredDayDirectory.toRealPath();
        if (!attachmentRoot.equals(dayDirectory.getParent())) {
            throw new IOException("Managed attachment directory escaped its configured root.");
        }
        return dayDirectory;
    }

    private void deleteOwned(Path path, Exception failure) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private String sha256Hex(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }
}
