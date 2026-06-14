package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.db.StoragePaths;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

public class GeneratedImageAttachmentWriter {

    private static final DateTimeFormatter DAY_DIR_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");

    private final StoragePaths storagePaths;

    public GeneratedImageAttachmentWriter() {
        this(StoragePaths.defaultPaths());
    }

    GeneratedImageAttachmentWriter(StoragePaths storagePaths) {
        this.storagePaths = storagePaths;
    }

    public AttachmentRef write(byte[] bytes, String mimeType) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Generated image was empty.");
        }

        UUID id = UUID.randomUUID();
        String resolvedMimeType = StringUtils.defaultIfBlank(mimeType, "image/png");
        String displayName = generatedImageFileName(id, resolvedMimeType);
        Path targetPath = targetPath(displayName);
        Files.copy(new ByteArrayInputStream(bytes), targetPath, StandardCopyOption.REPLACE_EXISTING);
        return new AttachmentRef(
                id,
                targetPath.toString(),
                displayName,
                resolvedMimeType,
                Files.size(targetPath),
                sha256Hex(targetPath)
        );
    }

    private Path targetPath(String displayName) throws IOException {
        Path targetDirectory = attachmentsDirectory();
        Files.createDirectories(targetDirectory);
        return targetDirectory.resolve(sanitizeFileName(displayName));
    }

    private String generatedImageFileName(UUID id, String mimeType) {
        return "generated-image-%s-%s.%s".formatted(
                LocalTime.now().format(FILE_TIME_FORMAT),
                id.toString().substring(0, 8),
                imageExtension(mimeType)
        );
    }

    private Path attachmentsDirectory() {
        String dayFolder = LocalDate.now().format(DAY_DIR_FORMAT);
        return storagePaths.attachmentsDirectory().resolve(dayFolder);
    }

    private String imageExtension(String mimeType) {
        String normalized = StringUtils.defaultString(mimeType).toLowerCase();
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private String sanitizeFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "generated-image";
        }

        return fileName
                .replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("_+", "_");
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
