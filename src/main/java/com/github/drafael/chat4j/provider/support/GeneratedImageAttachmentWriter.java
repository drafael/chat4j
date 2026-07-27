package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class GeneratedImageAttachmentWriter {

    private static final DateTimeFormatter DAY_DIR_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");
    private static final int MAX_UUID_ATTEMPTS = 3;
    private static final int MAX_DIMENSION = 16_384;
    private static final long MAX_PIXELS = 40_000_000L;

    private final ProviderAttachmentSupport attachmentSupport;
    private final Supplier<UUID> idSupplier;

    public GeneratedImageAttachmentWriter(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this(attachmentSupport, UUID::randomUUID);
    }

    GeneratedImageAttachmentWriter(
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull Supplier<UUID> idSupplier
    ) {
        this.attachmentSupport = attachmentSupport;
        this.idSupplier = idSupplier;
    }

    public AttachmentRef write(@NonNull byte[] bytes, String mimeType) throws IOException {
        if (bytes.length == 0) {
            throw new IOException("Generated image was empty.");
        }
        if (bytes.length > ProviderAttachmentSupport.MAX_IMAGE_BYTES) {
            throw new IOException("Generated image exceeded the managed image limit.");
        }

        String canonicalMime = canonicalImageMime(mimeType)
                .orElseThrow(() -> new IOException("Generated image MIME type is unsupported."));
        validateImage(bytes, canonicalMime);
        Path targetDirectory;
        try {
            targetDirectory = attachmentsDirectory();
        } catch (IOException e) {
            throw new IOException("Could not prepare managed image storage.");
        }

        for (int attempt = 0; attempt < MAX_UUID_ATTEMPTS; attempt++) {
            UUID id = idSupplier.get();
            try {
                return publish(targetDirectory, id, bytes, canonicalMime);
            } catch (FileAlreadyExistsException ignored) {
                // Retry with a fresh UUID without exposing the collided managed path.
            }
        }
        throw new IOException("Could not reserve a unique managed image name.");
    }

    public void discard(@NonNull AttachmentRef attachmentRef) {
        managedLeaf(attachmentRef.storagePath()).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best-effort cleanup; startup orphan recovery remains authoritative.
            }
        });
    }

    private AttachmentRef publish(Path targetDirectory, UUID id, byte[] bytes, String mimeType) throws IOException {
        Path partialPath = targetDirectory.resolve("%s.part".formatted(id));
        Path finalPath = targetDirectory.resolve(id.toString());
        boolean partialOwned = false;
        boolean finalOwned = false;
        try {
            Files.createFile(partialPath);
            partialOwned = true;
            Files.write(partialPath, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(partialPath, finalPath);
            partialOwned = false;
            finalOwned = true;

            String displayName = generatedImageFileName(id, mimeType);
            AttachmentRef attachmentRef = new AttachmentRef(
                    id,
                    finalPath.toString(),
                    displayName,
                    mimeType,
                    bytes.length,
                    sha256Hex(bytes)
            );
            ProviderAttachmentSupport.ResolvedAttachment resolved = attachmentSupport.resolve(attachmentRef, true)
                    .filter(attachment -> attachment.actualSize() == bytes.length)
                    .filter(attachment -> attachment.kind() == ProviderAttachmentSupport.AttachmentKind.IMAGE)
                    .orElseThrow(() -> new IOException("Published generated image failed managed-root verification."));
            if (!resolved.mimeType().equals(mimeType)) {
                throw new IOException("Published generated image metadata did not match its content type.");
            }
            return new AttachmentRef(
                    id,
                    resolved.canonicalPath().toString(),
                    resolved.safeName(),
                    resolved.mimeType(),
                    resolved.actualSize(),
                    attachmentRef.sha256()
            );
        } catch (IOException | RuntimeException e) {
            if (partialOwned) {
                deleteOwned(partialPath);
            }
            if (finalOwned) {
                deleteOwned(finalPath);
            }
            if (e instanceof FileAlreadyExistsException collision) {
                throw collision;
            }
            throw new IOException("Generated image could not be published.");
        }
    }

    private Optional<Path> managedLeaf(String storagePath) {
        if (StringUtils.isBlank(storagePath)) {
            return Optional.empty();
        }
        try {
            Path attachmentPath = Path.of(storagePath).toAbsolutePath().normalize();
            Path parent = attachmentPath.getParent();
            if (!attachmentPath.startsWith(attachmentSupport.managedRoot())
                    || parent == null
                    || containsSymbolicLink(attachmentSupport.managedRoot(), parent)) {
                return Optional.empty();
            }
            Path resolvedParent = parent.toRealPath();
            return resolvedParent.startsWith(attachmentSupport.managedRoot())
                    ? Optional.of(resolvedParent.resolve(attachmentPath.getFileName()))
                    : Optional.empty();
        } catch (IOException | InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
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

    private Path attachmentsDirectory() throws IOException {
        Path configuredDayDirectory = attachmentSupport.managedRoot()
                .resolve(LocalDate.now().format(DAY_DIR_FORMAT));
        Files.createDirectories(configuredDayDirectory);
        if (Files.isSymbolicLink(configuredDayDirectory)) {
            throw new IOException("Managed image directory must not be a symbolic link.");
        }
        Path dayDirectory = configuredDayDirectory.toRealPath();
        if (!attachmentSupport.managedRoot().equals(dayDirectory.getParent())) {
            throw new IOException("Managed image directory escaped its configured root.");
        }
        return dayDirectory;
    }

    private String generatedImageFileName(UUID id, String mimeType) {
        return "generated-image-%s-%s.%s".formatted(
                LocalTime.now().format(FILE_TIME_FORMAT),
                id.toString().substring(0, 8),
                imageExtension(mimeType)
        );
    }

    private String imageExtension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private Optional<String> canonicalImageMime(String mimeType) {
        String normalized = StringUtils.trimToEmpty(mimeType).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> Optional.of("image/jpeg");
            case "image/png" -> Optional.of("image/png");
            case "image/gif" -> Optional.of("image/gif");
            case "image/webp" -> Optional.of("image/webp");
            default -> Optional.empty();
        };
    }

    private void validateImage(byte[] bytes, String declaredMime) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Generated image could not be inspected.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Generated image format is unsupported.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String detectedMime = detectedMime(reader.getFormatName())
                        .orElseThrow(() -> new IOException("Generated image format is unsupported."));
                if (!detectedMime.equals(declaredMime)) {
                    throw new IOException("Generated image content did not match its declared MIME type.");
                }
                int imageCount = reader.getNumImages(true);
                if (imageCount <= 0) {
                    throw new IOException("Generated image contained no frames.");
                }
                for (int imageIndex = 0; imageIndex < imageCount; imageIndex++) {
                    validateDimensions(reader.getWidth(imageIndex), reader.getHeight(imageIndex));
                    try {
                        reader.read(imageIndex);
                    } catch (IOException | RuntimeException e) {
                        throw new IOException("Generated image data could not be decoded.", e);
                    }
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private void validateDimensions(int width, int height) throws IOException {
        long pixels = (long) width * height;
        if (width <= 0
                || height <= 0
                || width > MAX_DIMENSION
                || height > MAX_DIMENSION
                || pixels > MAX_PIXELS) {
            throw new IOException("Generated image dimensions exceeded the managed image limit.");
        }
    }

    private Optional<String> detectedMime(String formatName) {
        String normalized = StringUtils.trimToEmpty(formatName).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jpeg", "jpg" -> Optional.of("image/jpeg");
            case "png" -> Optional.of("image/png");
            case "gif" -> Optional.of("image/gif");
            case "webp" -> Optional.of("image/webp");
            default -> Optional.empty();
        };
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void deleteOwned(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The invocation never deletes a path it did not reserve; orphan cleanup is best effort.
        }
    }
}
