package com.github.drafael.chat4j.storage;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.WeakHashMap;

public class SettingsRepo {

    private static final Map<DataSource, Path> SETTINGS_FILE_BY_DATASOURCE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Path settingsFile;
    private final Object lock = new Object();

    public SettingsRepo(@NonNull StoragePaths storagePaths) {
        this(storagePaths.settingsFile());
    }

    public SettingsRepo(@NonNull DataSource dataSource) {
        this(resolveSettingsFile(dataSource));
    }

    public SettingsRepo(@NonNull Path settingsFile) {
        this.settingsFile = settingsFile;
    }

    public Optional<String> get(String key) throws SQLException {
        Validate.notBlank(key, "key should not be blank");

        synchronized (lock) {
            Properties properties = loadProperties();
            return Optional.ofNullable(properties.getProperty(key));
        }
    }

    public String get(String key, String defaultValue) throws SQLException {
        return get(key).orElse(defaultValue);
    }

    public void put(String key, String value) throws SQLException {
        Validate.notBlank(key, "key should not be blank");

        synchronized (lock) {
            Properties properties = loadProperties();
            properties.setProperty(key, StringUtils.defaultString(value));
            storeProperties(properties);
        }
    }

    public void remove(String key) throws SQLException {
        Validate.notBlank(key, "key should not be blank");

        synchronized (lock) {
            Properties properties = loadProperties();
            properties.remove(key);
            storeProperties(properties);
        }
    }

    public Map<String, String> findByPrefix(String prefix) throws SQLException {
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

    private Properties loadProperties() throws SQLException {
        Properties properties = new Properties();
        if (!Files.exists(settingsFile)) {
            return properties;
        }

        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new SQLException("Failed to read settings file: %s".formatted(settingsFile), e);
        }
    }

    private void storeProperties(Properties properties) throws SQLException {
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
            throw new SQLException("Failed to persist settings file: %s".formatted(settingsFile), e);
        }
    }

    private void moveAtomicallyOrReplace(Path tempFile) throws IOException {
        try {
            Files.move(tempFile, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tempFile, settingsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path resolveSettingsFile(@NonNull DataSource dataSource) {
        return SETTINGS_FILE_BY_DATASOURCE.computeIfAbsent(dataSource, unused -> {
            try {
                return Files.createTempFile("chat4j-settings-", ".properties");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create temporary settings file", e);
            }
        });
    }
}
