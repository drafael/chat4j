package com.github.drafael.chat4j.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

/** Moves files owned by the legacy config-only layout into their scoped storage homes. */
@Slf4j
@RequiredArgsConstructor
public final class StorageLayoutMigrator {

    @NonNull
    private final StoragePaths paths;

    public void migrate() {
        migrateDurable(paths.legacyDatabaseDirectory(), paths.databaseDirectory(), "database");
        migrateDurable(paths.legacyAttachmentsDirectory(), paths.attachmentsDirectory(), "attachments");
        migrateDurable(paths.legacyPromptsFile(), paths.promptsFile(), "prompt catalog");
        migrateDurable(paths.legacySecretsDirectory(), paths.secretsDirectory(), "saved credentials");
        migrateDurable(
                paths.legacyDatabaseCredentialsFile(),
                paths.databaseCredentialsFile(),
                "database credentials"
        );
        migrateDurable(paths.legacyCopilotAuthFile(), paths.copilotAuthFile(), "Copilot authentication");
        migrateDurable(paths.legacyCodexAuthFile(), paths.codexAuthFile(), "Codex authentication");
        migrateDurable(paths.legacySttModelsDirectory(), paths.sttModelsDirectory(), "speech models");

        migrateReplaceable(paths.legacyCacheDirectory(), paths.cacheDirectory(), "cache");
        migrateReplaceable(paths.legacyJcefBundleDirectory(), paths.jcefBundleDirectory(), "JCEF runtime");
        migrateReplaceable(paths.legacySttTempDirectory(), paths.sttTempDirectory(), "speech temporary files");
        migrateLegacyLogs();
    }

    private void migrateDurable(Path source, Path destination, String description) {
        Path normalizedSource = normalize(source);
        Path normalizedDestination = normalize(destination);
        if (normalizedSource.equals(normalizedDestination) || Files.notExists(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            rejectSymbolicLinks(normalizedSource, description);
            if (Files.exists(normalizedDestination, LinkOption.NOFOLLOW_LINKS)) {
                if (!equivalent(normalizedSource, normalizedDestination)) {
                    throw new IllegalStateException(
                            "Legacy %s exists at both %s and %s".formatted(
                                    description,
                                    normalizedSource,
                                    normalizedDestination
                            )
                    );
                }
                deleteTree(normalizedSource);
                return;
            }
            move(normalizedSource, normalizedDestination);
            log.info("Migrated {} storage to {}", description, normalizedDestination);
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException(
                    "Failed to migrate %s from %s to %s".formatted(
                            description,
                            normalizedSource,
                            normalizedDestination
                    ),
                    e
            );
        }
    }

    private void migrateReplaceable(Path source, Path destination, String description) {
        Path normalizedSource = normalize(source);
        Path normalizedDestination = normalize(destination);
        if (normalizedSource.equals(normalizedDestination) || Files.notExists(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            rejectSymbolicLink(normalizedSource, description);
            if (Files.exists(normalizedDestination, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(normalizedSource);
                return;
            }
            move(normalizedSource, normalizedDestination);
            log.info("Migrated {} storage to {}", description, normalizedDestination);
        } catch (Exception e) {
            log.warn("Legacy {} migration skipped: {}", description, ExceptionUtils.getMessage(e));
        }
    }

    private void migrateLegacyLogs() {
        Path source = normalize(paths.legacyLogsDirectory());
        Path logsDirectory = normalize(paths.logsDirectory());
        if (source.equals(logsDirectory) || Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        migrateReplaceable(source, logsDirectory.resolve("legacy-config"), "logs");
    }

    private static void move(Path source, Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.getFileStore(source).equals(Files.getFileStore(parent))) {
            Files.move(source, destination);
            return;
        }
        copyThenMove(source, destination);
    }

    private static void copyThenMove(Path source, Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("Migration destination has no parent: %s".formatted(destination));
        }
        Path staged = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                ? Files.createTempDirectory(parent, ".chat4j-migration-")
                : Files.createTempFile(parent, ".chat4j-migration-", ".tmp");
        try {
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                copyDirectory(source, staged);
            } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } else {
                throw new IOException("Unsupported migration source: %s".formatted(source));
            }
            Files.move(staged, destination);
            deleteTree(source);
        } catch (IOException | RuntimeException e) {
            deleteTreeIfExists(staged, e);
            throw e;
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> entries = Files.walk(source)) {
            List<Path> paths = entries.sorted().toList();
            for (Path path : paths) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) {
                    continue;
                }
                Path target = destination.resolve(relative);
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Refusing to copy symbolic link in migrated storage");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    throw new IOException("Unsupported migration entry: %s".formatted(path));
                }
            }
        }
    }

    private static void deleteTreeIfExists(Path root, Exception failure) {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            deleteTree(root);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static boolean equivalent(Path first, Path second) throws IOException {
        boolean firstDirectory = Files.isDirectory(first, LinkOption.NOFOLLOW_LINKS);
        boolean secondDirectory = Files.isDirectory(second, LinkOption.NOFOLLOW_LINKS);
        if (firstDirectory != secondDirectory) {
            return false;
        }
        if (!firstDirectory) {
            return Files.isRegularFile(first, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(second, LinkOption.NOFOLLOW_LINKS)
                    && Files.mismatch(first, second) == -1L;
        }

        List<Path> firstEntries;
        try (Stream<Path> entries = Files.walk(first)) {
            firstEntries = entries.map(first::relativize).sorted().toList();
        }
        List<Path> secondEntries;
        try (Stream<Path> entries = Files.walk(second)) {
            secondEntries = entries.map(second::relativize).sorted().toList();
        }
        if (!firstEntries.equals(secondEntries)) {
            return false;
        }
        return firstEntries.stream()
                .filter(relative -> !relative.toString().isEmpty())
                .allMatch(relative -> equivalentEntry(first.resolve(relative), second.resolve(relative)));
    }

    private static boolean equivalentEntry(Path first, Path second) {
        try {
            rejectSymbolicLink(first, "legacy storage");
            rejectSymbolicLink(second, "destination storage");
            boolean firstDirectory = Files.isDirectory(first, LinkOption.NOFOLLOW_LINKS);
            boolean secondDirectory = Files.isDirectory(second, LinkOption.NOFOLLOW_LINKS);
            return firstDirectory == secondDirectory
                    && (firstDirectory || Files.mismatch(first, second) == -1L);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static void rejectSymbolicLink(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("%s path is a symbolic link".formatted(description));
        }
    }

    private static void rejectSymbolicLinks(Path root, String description) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            Path symbolicLink = entries.filter(Files::isSymbolicLink).findFirst().orElse(null);
            if (symbolicLink != null) {
                throw new IOException("%s contains a symbolic link: %s".formatted(description, symbolicLink));
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> entries = Files.walk(root)) {
            List<Path> paths = entries.sorted((left, right) -> right.compareTo(left)).toList();
            for (Path path : paths) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Refusing to delete symbolic link in migrated storage");
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
