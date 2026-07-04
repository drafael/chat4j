package com.github.drafael.chat4j.persistence.settings;

import com.github.drafael.chat4j.persistence.db.StoragePaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

public class SettingsRepository {

    private final Path settingsFile;
    private final Object lock = new Object();

    public SettingsRepository(@NonNull StoragePaths storagePaths) {
        this(storagePaths.settingsFile());
    }

    public SettingsRepository(@NonNull Path settingsFile) {
        this.settingsFile = settingsFile;
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
            Properties properties = loadProperties();
            Map<String, String> entries = new LinkedHashMap<>();
            properties.stringPropertyNames().stream()
                    .sorted()
                    .filter(key -> key.startsWith(safePrefix))
                    .forEach(key -> entries.put(key, properties.getProperty(key)));
            return entries;
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

            try (OutputStream output = Files.newOutputStream(tempFile)) {
                properties.store(output, "Chat4J settings");
            }

            moveAtomicallyOrReplace(tempFile);
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
