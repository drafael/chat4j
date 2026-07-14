package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.util.Collections.emptyList;

@Slf4j
public class ProviderModelCache {

    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";
    private static final String CODEX_REMOTE_MODELS_MARKER = "# remote-models-v1";
    private static final String SCOPE_MARKER_PREFIX = "# scope-v1 ";

    private final Path cacheDir;

    public ProviderModelCache(StoragePaths storagePaths) {
        this.cacheDir = storagePaths.modelsCacheDirectory();
    }

    public Optional<CacheSnapshot> readCacheEntry(String providerName) {
        Path file = cacheDir.resolve("%s.txt".formatted(sanitize(providerName)));
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty()) {
                log.warn("Model cache file is malformed for provider {}", providerName);
                return Optional.empty();
            }

            Instant fetchedAt;
            try {
                fetchedAt = Instant.parse(lines.getFirst());
            } catch (DateTimeParseException e) {
                log.warn("Model cache timestamp is invalid for provider {}: {}", providerName, ExceptionUtils.getMessage(e));
                return Optional.empty();
            }

            int modelsStartIndex = 1;
            String scope = null;
            if (lines.size() > modelsStartIndex && lines.get(modelsStartIndex).startsWith(SCOPE_MARKER_PREFIX)) {
                scope = decodeScope(lines.get(modelsStartIndex).substring(SCOPE_MARKER_PREFIX.length()));
                modelsStartIndex++;
            }

            if (CODEX_PROVIDER_NAME.equals(providerName)) {
                if (lines.size() <= modelsStartIndex || !CODEX_REMOTE_MODELS_MARKER.equals(lines.get(modelsStartIndex))) {
                    if (Instant.EPOCH.equals(fetchedAt) && lines.size() == 1) {
                        return Optional.of(new CacheSnapshot(Instant.EPOCH, emptyList(), null));
                    }
                    log.info("Ignoring legacy OpenAI Codex model cache so local models can be refreshed cleanly");
                    return Optional.empty();
                }
                modelsStartIndex++;
            }

            List<String> models = lines.size() == modelsStartIndex
                    ? emptyList()
                    : sanitizeModels(providerName, lines.subList(modelsStartIndex, lines.size()));
            return Optional.of(new CacheSnapshot(fetchedAt, models, scope));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to read model cache for provider {}: {}", providerName, ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
    }

    public boolean writeCache(String providerName, Instant fetchedAt, String scope, List<String> models) {
        try {
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve("%s.txt".formatted(sanitize(providerName)));
            List<String> lines = new ArrayList<>();
            lines.add(fetchedAt.toString());
            lines.add("%s%s".formatted(SCOPE_MARKER_PREFIX, encodeScope(scope)));
            if (CODEX_PROVIDER_NAME.equals(providerName)) {
                lines.add(CODEX_REMOTE_MODELS_MARKER);
            }
            lines.addAll(sanitizeModels(providerName, models));
            Path temporaryFile = Files.createTempFile(cacheDir, "model-%s-".formatted(sanitize(providerName)), ".tmp");
            try {
                Files.write(temporaryFile, lines);
                moveIntoPlace(temporaryFile, file);
                return true;
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        } catch (IOException e) {
            log.warn("Failed to write model cache for provider {}: {}", providerName, ExceptionUtils.getMessage(e));
            return false;
        }
    }

    public boolean deleteCache(String providerName) {
        try {
            Files.deleteIfExists(cacheDir.resolve("%s.txt".formatted(sanitize(providerName))));
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete model cache for provider {}: {}", providerName, ExceptionUtils.getMessage(e));
            return false;
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException replacementFailure) {
                replacementFailure.addSuppressed(atomicMoveFailure);
                throw replacementFailure;
            }
        }
    }

    private static List<String> sanitizeModels(String providerName, List<String> models) {
        return ModelOrdering.sanitizeAndSortByProvider(providerName, models);
    }

    private static String encodeScope(String scope) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(StringUtils.defaultString(scope).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeScope(String encodedScope) {
        try {
            return new String(Base64.getUrlDecoder().decode(encodedScope), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid model cache scope metadata", e);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public record CacheSnapshot(Instant fetchedAt, List<String> models, String scope) {
    }
}
