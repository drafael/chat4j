package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.CacheRootHandle;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.util.Collections.emptyList;

@Slf4j
public class ProviderModelCache {

    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";
    private static final String CODEX_REMOTE_MODELS_MARKER = "# remote-models-v1";
    private static final String SCOPE_MARKER_PREFIX = "# scope-v1 ";
    private static final int MAX_CACHE_BYTES = 8 * 1024 * 1024;

    private final CacheRootHandle root;

    public ProviderModelCache(@NonNull StoragePaths storagePaths) {
        this(CacheRootHandle.from(storagePaths));
    }

    public ProviderModelCache(@NonNull CacheRootHandle root) {
        this.root = root;
    }

    public Optional<CacheSnapshot> readCacheEntry(String providerName) {
        Validate.notBlank(providerName, "providerName should not be blank");
        Optional<Path> cacheFile = root.directChild("%s.txt".formatted(sanitize(providerName)));
        if (cacheFile.isEmpty() || !root.isSafeRegularFile(cacheFile.get())) {
            return Optional.empty();
        }
        Path file = cacheFile.get();

        try {
            List<String> lines = readBoundedLines(file);
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
        } catch (IOException | IllegalStateException | SecurityException e) {
            log.warn("Failed to read model cache for provider {}: {}", providerName, ExceptionUtils.getMessage(e));
            return Optional.empty();
        }
    }

    public boolean writeCache(
            String providerName,
            @NonNull Instant fetchedAt,
            String scope,
            @NonNull List<String> models
    ) {
        Validate.notBlank(providerName, "providerName should not be blank");
        try {
            Optional<Path> cacheDir = root.availableRoot();
            Optional<Path> file = root.directChild("%s.txt".formatted(sanitize(providerName)));
            if (cacheDir.isEmpty() || file.isEmpty()) {
                return false;
            }
            Path target = file.orElseThrow();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !root.isSafeRegularFile(target)) {
                log.warn("Model cache target is unsafe for provider {}", providerName);
                return false;
            }
            List<String> lines = new ArrayList<>();
            lines.add(fetchedAt.toString());
            lines.add("%s%s".formatted(SCOPE_MARKER_PREFIX, encodeScope(scope)));
            if (CODEX_PROVIDER_NAME.equals(providerName)) {
                lines.add(CODEX_REMOTE_MODELS_MARKER);
            }
            lines.addAll(sanitizeModels(providerName, models));
            byte[] payload = encodeBounded(lines);
            Path temporaryFile = Files.createTempFile(
                    cacheDir.orElseThrow(),
                    "model-%s-".formatted(sanitize(providerName)),
                    ".tmp"
            );
            try {
                Files.write(temporaryFile, payload);
                moveIntoPlace(temporaryFile, target);
                return true;
            } catch (IOException | SecurityException e) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException | SecurityException ex) {
                    e.addSuppressed(ex);
                }
                throw e;
            }
        } catch (IOException | SecurityException e) {
            log.warn("Failed to write model cache for provider {}: {}", providerName, ExceptionUtils.getMessage(e));
            return false;
        }
    }

    public boolean deleteCache(String providerName) {
        Validate.notBlank(providerName, "providerName should not be blank");
        try {
            Optional<Path> file = root.directChild("%s.txt".formatted(sanitize(providerName)));
            if (file.isEmpty()) {
                return false;
            }
            Path target = file.orElseThrow();
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            if (!root.isSafeRegularFile(target)) {
                log.warn("Model cache target is unsafe for provider {}", providerName);
                return false;
            }
            Files.delete(target);
            return true;
        } catch (IOException | SecurityException e) {
            log.warn("Failed to delete model cache for provider {}: {}", providerName, ExceptionUtils.getMessage(e));
            return false;
        }
    }

    private static byte[] encodeBounded(List<String> lines) throws IOException {
        String content = "%s%s".formatted(String.join(System.lineSeparator(), lines), System.lineSeparator());
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(content));
            if (encoded.remaining() > MAX_CACHE_BYTES) {
                throw new IOException("Model cache exceeds 8 MiB");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IOException("Model cache is not valid UTF-8", e);
        }
    }

    private static List<String> readBoundedLines(Path file) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_CACHE_BYTES + 1);
            if (bytes.length > MAX_CACHE_BYTES) {
                throw new IOException("Model cache exceeds 8 MiB");
            }
        }
        try {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return content.lines().toList();
        } catch (CharacterCodingException e) {
            throw new IOException("Model cache is not valid UTF-8", e);
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

    private static String encodeScope(String scope) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(StringUtils.defaultString(scope)));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String decodeScope(String encodedScope) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encodedScope);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw new IllegalStateException("Invalid model cache scope metadata", e);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public record CacheSnapshot(@NonNull Instant fetchedAt, @NonNull List<String> models, String scope) {
        public CacheSnapshot {
            models = List.copyOf(models);
        }
    }
}
