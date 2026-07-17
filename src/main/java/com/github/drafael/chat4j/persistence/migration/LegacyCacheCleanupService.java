package com.github.drafael.chat4j.persistence.migration;

import com.github.drafael.chat4j.persistence.StoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.exception.ExceptionUtils;

/** Deletes only recognized top-level legacy cache files; legacy content is never read. */
@Slf4j
public final class LegacyCacheCleanupService {

    private static final String COPILOT_FILE = "github-copilot-model-metadata.json";
    private static final int ENTRY_LIMIT = 100_000;

    private final Path legacyRoot;

    public LegacyCacheCleanupService(StoragePaths storagePaths) {
        this(storagePaths.legacyModelsCacheDirectory());
    }

    public LegacyCacheCleanupService(Path legacyRoot) {
        this.legacyRoot = Validate.notNull(legacyRoot, "legacyRoot should not be null").toAbsolutePath().normalize();
    }

    public void cleanup() {
        try {
            if (Files.notExists(legacyRoot, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes rootAttributes = readAttributes(legacyRoot);
            if (!isDirectory(rootAttributes)) {
                log.warn("Legacy cache root is not a directory; preserving it");
                return;
            }
            if (rootAttributes.fileKey() == null) {
                log.warn("Legacy cache root identity is unavailable; preserving it");
                return;
            }

            List<Path> entries;
            try (Stream<Path> children = Files.list(legacyRoot)) {
                entries = children.limit(ENTRY_LIMIT + 1L).toList();
            }
            if (entries.size() > ENTRY_LIMIT) {
                log.warn("Legacy cache cleanup skipped because the entry limit was exceeded");
                return;
            }

            entries.stream()
                    .map(this::recognizedRegularFile)
                    .flatMap(Optional::stream)
                    .forEach(entry -> deleteSafely(entry, rootAttributes.fileKey()));
            removeIfEmpty(rootAttributes.fileKey());
        } catch (IOException | SecurityException e) {
            log.warn("Legacy cache cleanup skipped: {}", ExceptionUtils.getMessage(e));
        }
    }

    private Optional<RecognizedEntry> recognizedRegularFile(Path entry) {
        try {
            BasicFileAttributes attributes = readAttributes(entry);
            String name = entry.getFileName().toString();
            boolean recognized = legacyRoot.equals(entry.toAbsolutePath().normalize().getParent())
                    && attributes.isRegularFile()
                    && !attributes.isSymbolicLink()
                    && (name.endsWith(".txt") || COPILOT_FILE.equals(name));
            return recognized ? Optional.of(new RecognizedEntry(entry, attributes.fileKey())) : Optional.empty();
        } catch (IOException | SecurityException e) {
            log.warn("Legacy cache entry inspection failed; preserving entry: {}", ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
    }

    private void deleteSafely(RecognizedEntry entry, Object rootFileKey) {
        try {
            if (!sameRoot(rootFileKey)) {
                log.warn("Legacy cache root changed during cleanup; preserving remaining entries");
                return;
            }
            Optional<RecognizedEntry> current = recognizedRegularFile(entry.path());
            if (current.isEmpty() || !sameIdentity(entry.fileKey(), current.orElseThrow().fileKey())) {
                log.warn("Legacy cache entry changed during cleanup; preserving it");
                return;
            }
            Files.delete(entry.path());
        } catch (IOException | SecurityException e) {
            log.warn("Legacy cache entry deletion failed: {}", ExceptionUtils.getMessage(e));
        }
    }

    private void removeIfEmpty(Object rootFileKey) {
        if (!sameRoot(rootFileKey)) {
            log.warn("Legacy cache root changed during cleanup; preserving it");
            return;
        }
        try {
            boolean empty;
            try (Stream<Path> children = Files.list(legacyRoot)) {
                empty = children.findAny().isEmpty();
            }
            if (empty && sameRoot(rootFileKey)) {
                Files.delete(legacyRoot);
            }
        } catch (IOException | SecurityException e) {
            log.warn("Legacy cache root removal failed: {}", ExceptionUtils.getMessage(e));
        }
    }

    private boolean sameRoot(Object expectedFileKey) {
        try {
            BasicFileAttributes attributes = readAttributes(legacyRoot);
            return isDirectory(attributes) && sameIdentity(expectedFileKey, attributes.fileKey());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static boolean sameIdentity(Object expectedFileKey, Object actualFileKey) {
        return expectedFileKey != null && expectedFileKey.equals(actualFileKey);
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isSymbolicLink();
    }

    private record RecognizedEntry(Path path, Object fileKey) {
    }
}
