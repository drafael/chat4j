package com.github.drafael.chat4j.persistence.migration;

import com.github.drafael.chat4j.persistence.FileIdentity;
import com.github.drafael.chat4j.persistence.StoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

/** Deletes only recognized top-level legacy cache files; legacy content is never read. */
@Slf4j
public final class LegacyCacheCleanupService {

    private static final String COPILOT_FILE = "github-copilot-model-metadata.json";
    private static final int ENTRY_LIMIT = 100_000;

    private final Path legacyRoot;

    public LegacyCacheCleanupService(@NonNull StoragePaths storagePaths) {
        this(storagePaths.legacyModelsCacheDirectory());
    }

    public LegacyCacheCleanupService(@NonNull Path legacyRoot) {
        this.legacyRoot = legacyRoot.toAbsolutePath().normalize();
    }

    public void cleanup() {
        try {
            if (Files.notExists(legacyRoot, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Optional<FileIdentity> rootIdentity = FileIdentity.directory(legacyRoot);
            if (rootIdentity.isEmpty()) {
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
                    .forEach(entry -> deleteSafely(entry, rootIdentity.orElseThrow()));
            removeIfEmpty(rootIdentity.orElseThrow());
        } catch (IOException | SecurityException e) {
            log.warn("Legacy cache cleanup skipped: {}", ExceptionUtils.getMessage(e));
        }
    }

    private Optional<RecognizedEntry> recognizedRegularFile(Path entry) {
        String name = entry.getFileName().toString();
        boolean recognized = legacyRoot.equals(entry.toAbsolutePath().normalize().getParent())
                && (name.endsWith(".txt") || COPILOT_FILE.equals(name));
        return recognized
                ? FileIdentity.regularFile(entry).map(identity -> new RecognizedEntry(entry, identity))
                : Optional.empty();
    }

    private void deleteSafely(RecognizedEntry entry, FileIdentity rootIdentity) {
        try {
            if (!sameRoot(rootIdentity)) {
                log.warn("Legacy cache root changed during cleanup; preserving remaining entries");
                return;
            }
            Optional<RecognizedEntry> current = recognizedRegularFile(entry.path());
            if (current.isEmpty() || !entry.fileIdentity().equals(current.orElseThrow().fileIdentity())) {
                log.warn("Legacy cache entry changed during cleanup; preserving it");
                return;
            }
            Files.delete(entry.path());
        } catch (IOException | SecurityException e) {
            log.warn("Legacy cache entry deletion failed: {}", ExceptionUtils.getMessage(e));
        }
    }

    private void removeIfEmpty(FileIdentity rootIdentity) {
        if (!sameRoot(rootIdentity)) {
            log.warn("Legacy cache root changed during cleanup; preserving it");
            return;
        }
        try {
            boolean empty;
            try (Stream<Path> children = Files.list(legacyRoot)) {
                empty = children.findAny().isEmpty();
            }
            if (empty && sameRoot(rootIdentity)) {
                Files.delete(legacyRoot);
            }
        } catch (IOException | SecurityException e) {
            log.warn("Legacy cache root removal failed: {}", ExceptionUtils.getMessage(e));
        }
    }

    private boolean sameRoot(FileIdentity expectedIdentity) {
        return FileIdentity.directory(legacyRoot).filter(expectedIdentity::equals).isPresent();
    }

    private record RecognizedEntry(Path path, FileIdentity fileIdentity) {
    }
}
