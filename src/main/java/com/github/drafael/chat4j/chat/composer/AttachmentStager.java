package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

public class AttachmentStager {

    private static final DateTimeFormatter DAY_DIR_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final StoragePaths storagePaths;

    public AttachmentStager() {
        this(StoragePaths.defaultPaths());
    }

    public AttachmentStager(StoragePaths storagePaths) {
        this.storagePaths = storagePaths;
    }

    public AttachmentRef stage(ComposerAttachment attachment) throws IOException {
        UUID id = UUID.randomUUID();
        Path targetPath = targetPath(id, attachment.displayName());
        Files.copy(attachment.path(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        return attachmentRef(id, targetPath, attachment.displayName(), attachment.mimeType());
    }

    private Path targetPath(UUID id, String displayName) throws IOException {
        Path targetDirectory = attachmentsDirectory();
        Files.createDirectories(targetDirectory);
        String safeName = sanitizeFileName(displayName);
        return targetDirectory.resolve("%s-%s".formatted(id, safeName));
    }

    private AttachmentRef attachmentRef(UUID id, Path targetPath, String displayName, String mimeType) throws IOException {
        String sha256 = sha256Hex(targetPath);
        long size = Files.size(targetPath);

        return new AttachmentRef(
                id,
                targetPath.toString(),
                displayName,
                mimeType,
                size,
                sha256
        );
    }

    private Path attachmentsDirectory() {
        String dayFolder = LocalDate.now().format(DAY_DIR_FORMAT);
        return storagePaths.attachmentsDirectory().resolve(dayFolder);
    }

    private String sanitizeFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "attachment";
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
