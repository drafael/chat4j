package com.github.drafael.chat4j.persistence.settings;

import com.github.drafael.chat4j.persistence.CacheRootHandle;
import com.github.drafael.chat4j.persistence.StoragePaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import static java.util.Collections.unmodifiableMap;
import static java.util.stream.StreamSupport.stream;

public class SettingsRepository {

    private static final ConcurrentMap<Path, Object> PATH_LOCKS = new ConcurrentHashMap<>();

    private final Path settingsFile;
    private final Path settingsFileIdentity;
    private final Object lock;

    public SettingsRepository(@NonNull StoragePaths storagePaths) {
        this(storagePaths.settingsFile());
    }

    public SettingsRepository(@NonNull Path settingsFile) {
        this.settingsFile = settingsFile;
        this.settingsFileIdentity = CacheRootHandle.canonicalPath(settingsFile);
        this.lock = PATH_LOCKS.computeIfAbsent(settingsFileIdentity, ignored -> new Object());
    }

    public Optional<String> get(String key) {
        Validate.notBlank(key, "key should not be blank");

        synchronized (lock) {
            Properties properties = loadProperties();
            return Optional.ofNullable(properties.getProperty(key));
        }
    }

    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    public void put(String key, String value) {
        Validate.notBlank(key, "key should not be blank");

        synchronized (lock) {
            Properties properties = loadProperties();
            properties.setProperty(key, StringUtils.defaultString(value));
            storeProperties(properties);
        }
    }

    public void remove(String key) {
        Validate.notBlank(key, "key should not be blank");

        synchronized (lock) {
            Properties properties = loadProperties();
            properties.remove(key);
            storeProperties(properties);
        }
    }

    public Map<String, String> findByPrefix(String prefix) {
        String safePrefix = StringUtils.defaultString(prefix);

        synchronized (lock) {
            return valuesByPrefix(loadProperties(), safePrefix);
        }
    }

    public Map<String, String> findByPrefix(String prefix, int maxEntries) {
        Validate.isTrue(maxEntries >= 0, "maxEntries should not be negative");
        String safePrefix = StringUtils.defaultString(prefix);

        synchronized (lock) {
            return boundedValuesByPrefix(loadProperties(), safePrefix, maxEntries);
        }
    }

    /**
     * Returns one coherent image of the requested settings keys. Missing keys are omitted.
     */
    public Map<String, String> getAll(@NonNull Iterable<String> keys) {
        synchronized (lock) {
            Properties properties = loadProperties();
            Map<String, String> values = new LinkedHashMap<>();
            stream(keys.spliterator(), false)
                    .filter(StringUtils::isNotBlank)
                    .filter(properties::containsKey)
                    .forEach(key -> values.put(key, properties.getProperty(key)));
            return unmodifiableMap(values);
        }
    }

    /**
     * Runs a prefix inspection and mutation against one settings image and persists at most once.
     */
    public void updatePrefixBatch(
            String prefix,
            int maxEntries,
            @NonNull Consumer<PrefixBatchUpdate> updates
    ) {
        Validate.isTrue(maxEntries >= 0, "maxEntries should not be negative");
        String safePrefix = StringUtils.defaultString(prefix);

        synchronized (lock) {
            Properties properties = loadProperties();
            BatchUpdate batchUpdate = new BatchUpdate(properties);
            Map<String, String> values = boundedValuesByPrefix(properties, safePrefix, maxEntries);
            updates.accept(new PrefixBatchUpdate(values, batchUpdate));
            if (batchUpdate.changed()) {
                storeProperties(properties);
            }
        }
    }

    public void updateBatch(@NonNull Consumer<BatchUpdate> updates) {
        synchronized (lock) {
            Properties properties = loadProperties();
            BatchUpdate batchUpdate = new BatchUpdate(properties);
            updates.accept(batchUpdate);
            if (batchUpdate.changed()) {
                storeProperties(properties);
            }
        }
    }

    public boolean updateBatchIf(
            @NonNull BooleanSupplier condition,
            @NonNull Consumer<BatchUpdate> updates
    ) {
        synchronized (lock) {
            Properties properties = loadProperties();
            if (!condition.getAsBoolean()) {
                return false;
            }
            BatchUpdate batchUpdate = new BatchUpdate(properties);
            updates.accept(batchUpdate);
            if (batchUpdate.changed()) {
                storeProperties(properties);
            }
            return true;
        }
    }

    public Path settingsFileIdentity() {
        return settingsFileIdentity;
    }

    private static Map<String, String> valuesByPrefix(Properties properties, String prefix) {
        Map<String, String> entries = new LinkedHashMap<>();
        properties.stringPropertyNames().stream()
                .sorted()
                .filter(key -> key.startsWith(prefix))
                .forEach(key -> entries.put(key, properties.getProperty(key)));
        return unmodifiableMap(entries);
    }

    private static Map<String, String> boundedValuesByPrefix(Properties properties, String prefix, int maxEntries) {
        List<String> keys = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .sorted()
                .limit((long) maxEntries + 1)
                .toList();
        if (keys.size() > maxEntries) {
            throw new IllegalStateException("Settings prefix contains too many entries");
        }
        Map<String, String> entries = new LinkedHashMap<>();
        keys.forEach(key -> entries.put(key, properties.getProperty(key)));
        return unmodifiableMap(entries);
    }

    public record PrefixBatchUpdate(@NonNull Map<String, String> values, @NonNull BatchUpdate updates) {
        public PrefixBatchUpdate {
            values = unmodifiableMap(new LinkedHashMap<>(values));
        }

        @Override
        public String toString() {
            return "PrefixBatchUpdate[valueCount=%d]".formatted(values.size());
        }
    }

    public static final class BatchUpdate {
        private final Properties properties;
        private boolean changed;

        private BatchUpdate(Properties properties) {
            this.properties = properties;
        }

        public void put(String key, String value) {
            Validate.notBlank(key, "key should not be blank");
            String safeValue = StringUtils.defaultString(value);
            if (!safeValue.equals(properties.getProperty(key))) {
                properties.setProperty(key, safeValue);
                changed = true;
            }
        }

        public void remove(String key) {
            Validate.notBlank(key, "key should not be blank");
            if (properties.containsKey(key)) {
                properties.remove(key);
                changed = true;
            }
        }

        private boolean changed() {
            return changed;
        }
    }

    private Properties loadProperties() {
        Properties properties = new Properties();
        if (!Files.exists(settingsFile)) {
            return properties;
        }

        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            return properties;
        } catch (NoSuchFileException e) {
            return properties;
        } catch (IOException e) {
            throw new SettingsStorageException("Failed to read settings file: %s".formatted(settingsFile), e);
        }
    }

    private void storeProperties(Properties properties) {
        Path parent = settingsFile.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path tempFile = parent == null
                    ? Files.createTempFile("chat4j-settings", ".tmp")
                    : Files.createTempFile(parent, settingsFile.getFileName().toString(), ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(tempFile)) {
                    properties.store(output, "Chat4J settings");
                }
                moveAtomicallyOrReplace(tempFile);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ex) {
                    e.addSuppressed(ex);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new SettingsStorageException("Failed to persist settings file: %s".formatted(settingsFile), e);
        }
    }

    private void moveAtomicallyOrReplace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempFile, settingsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
