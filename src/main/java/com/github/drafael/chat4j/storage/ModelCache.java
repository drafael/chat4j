package com.github.drafael.chat4j.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.github.drafael.chat4j.provider.support.ModelOrdering;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.emptyList;

public class ModelCache {

    private final Path cacheDir;

    public ModelCache(StoragePaths storagePaths) {
        this.cacheDir = storagePaths.modelsCacheDirectory();
    }

    public List<String> readCache(String providerName) {
        return readCacheEntry(providerName)
                .map(CacheSnapshot::models)
                .orElseGet(() -> emptyList());
    }

    public Optional<CacheSnapshot> readCacheEntry(String providerName) {
        Path file = cacheDir.resolve("%s.txt".formatted(sanitize(providerName)));
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 2) {
                return Optional.empty();
            }

            Instant fetchedAt;
            try {
                fetchedAt = Instant.parse(lines.getFirst());
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }

            List<String> models = sanitizeModels(providerName, lines.subList(1, lines.size()));
            return Optional.of(new CacheSnapshot(fetchedAt, models));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void writeCache(String providerName, List<String> models) {
        writeCache(providerName, Instant.now(), models);
    }

    public void writeCache(String providerName, Instant fetchedAt, List<String> models) {
        try {
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve("%s.txt".formatted(sanitize(providerName)));
            List<String> lines = new ArrayList<>();
            lines.add(fetchedAt.toString());
            lines.addAll(sanitizeModels(providerName, models));
            Files.write(file, lines);
        } catch (IOException e) {
            // Cache write failure is non-critical
        }
    }

    private static List<String> sanitizeModels(String providerName, List<String> models) {
        return ModelOrdering.sanitizeAndSortByProvider(providerName, models);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public record CacheSnapshot(Instant fetchedAt, List<String> models) {
    }
}
