package com.github.drafael.chat4j.storage;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public class SettingsDbToPropertiesMigrationCoordinator {

    private static final Logger LOG = Logger.getLogger(SettingsDbToPropertiesMigrationCoordinator.class.getName());
    private static final String LEGACY_SETTINGS_TABLE = "settings";

    private final DataSource dataSource;
    private final SettingsRepo settingsRepo;

    public SettingsDbToPropertiesMigrationCoordinator(@NonNull DataSource dataSource, @NonNull SettingsRepo settingsRepo) {
        this.dataSource = dataSource;
        this.settingsRepo = settingsRepo;
    }

    public void migrateIfNeeded() {
        try {
            if (settingsRepo.get(SettingsKeys.SETTINGS_DB_TO_PROPERTIES_MIGRATION_MARKER).isPresent()) {
                return;
            }

            if (!legacySettingsTableExists()) {
                settingsRepo.put(SettingsKeys.SETTINGS_DB_TO_PROPERTIES_MIGRATION_MARKER, "v1-no-legacy-table");
                return;
            }

            Map<String, String> legacySettings = readLegacySettings();
            int migratedCount = 0;
            int skippedCount = 0;

            for (Map.Entry<String, String> entry : legacySettings.entrySet()) {
                Optional<String> targetKey = mapLegacyKey(entry.getKey());
                if (targetKey.isEmpty()) {
                    skippedCount++;
                    continue;
                }

                if (settingsRepo.get(targetKey.get()).isPresent()) {
                    skippedCount++;
                    continue;
                }

                settingsRepo.put(targetKey.get(), entry.getValue());
                migratedCount++;
            }

            settingsRepo.put(SettingsKeys.SETTINGS_DB_TO_PROPERTIES_MIGRATION_MARKER, "v1");
            LOG.info("Migrated %d DB settings to properties (%d skipped: already present)"
                    .formatted(migratedCount, skippedCount));
        } catch (Exception e) {
            LOG.warning(() -> "Settings DB->properties migration failed: %s".formatted(e.getMessage()));
        }
    }

    private boolean legacySettingsTableExists() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData().getTables(null, "PUBLIC", null, new String[]{"TABLE"})
        ) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (LEGACY_SETTINGS_TABLE.equalsIgnoreCase(tableName)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Map<String, String> readLegacySettings() throws SQLException {
        Map<String, String> settings = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT \"key\", \"value\" FROM settings ORDER BY \"key\"");
             ResultSet rows = statement.executeQuery()
        ) {
            while (rows.next()) {
                settings.put(rows.getString(1), rows.getString(2));
            }
        }
        return settings;
    }

    private Optional<String> mapLegacyKey(String legacyKey) {
        String key = StringUtils.defaultString(legacyKey).trim();

        if (key.equals("theme")) {
            return Optional.of(SettingsKeys.THEME_NAME);
        }
        if (key.equals("accentColor")) {
            return Optional.of(SettingsKeys.THEME_ACCENT);
        }
        if (key.equals("app.font")) {
            return Optional.of(SettingsKeys.APP_FONT_FAMILY);
        }
        if (key.equals("app.font.size")) {
            return Optional.of(SettingsKeys.APP_FONT_SIZE);
        }
        if (key.equals("code.font")) {
            return Optional.of(SettingsKeys.CODE_FONT_FAMILY);
        }
        if (key.equals("window.x")) {
            return Optional.of(SettingsKeys.WINDOW_X);
        }
        if (key.equals("window.y")) {
            return Optional.of(SettingsKeys.WINDOW_Y);
        }
        if (key.equals("window.width")) {
            return Optional.of(SettingsKeys.WINDOW_WIDTH);
        }
        if (key.equals("window.height")) {
            return Optional.of(SettingsKeys.WINDOW_HEIGHT);
        }
        if (key.equals("menu.bar.enabled")) {
            return Optional.of(SettingsKeys.MENU_BAR_ENABLED);
        }
        if (key.equals("send.key")) {
            return Optional.of(SettingsKeys.CHAT_SEND_KEY);
        }
        if (key.equals("auto.scroll")) {
            return Optional.of(SettingsKeys.CHAT_AUTO_SCROLL);
        }
        if (key.equals(SettingsKeys.CHAT_RENDER_MODE)) {
            return Optional.of(SettingsKeys.CHAT_RENDER_MODE);
        }

        Optional<String> providerKey = mapLegacyProviderKey(key);
        if (providerKey.isPresent()) {
            return providerKey;
        }

        Optional<String> favoriteKey = mapLegacyFavoriteKey(key);
        if (favoriteKey.isPresent()) {
            return favoriteKey;
        }

        return Optional.empty();
    }

    private Optional<String> mapLegacyProviderKey(String legacyKey) {
        String providerPrefix = "provider.";
        if (!legacyKey.startsWith(providerPrefix)) {
            return Optional.empty();
        }

        if (legacyKey.endsWith(".enabled")) {
            String providerName = legacyKey.substring(providerPrefix.length(), legacyKey.length() - ".enabled".length());
            return Optional.of(SettingsKeys.providerEnabledKey(providerName));
        }

        if (legacyKey.endsWith(".baseUrl")) {
            String providerName = legacyKey.substring(providerPrefix.length(), legacyKey.length() - ".baseUrl".length());
            return Optional.of(SettingsKeys.providerBaseUrlKey(providerName));
        }

        return Optional.empty();
    }

    private Optional<String> mapLegacyFavoriteKey(String legacyKey) {
        String prefix = "model.favorite.";
        if (!legacyKey.startsWith(prefix)) {
            return Optional.empty();
        }

        String payload = legacyKey.substring(prefix.length());
        String[] parts = payload.split(SettingsKeys.MODEL_FAVORITE_DELIMITER, 2);
        if (parts.length != 2) {
            return Optional.empty();
        }

        String providerName = decode(parts[0]);
        String encodedModel = parts[1];
        String mapped = SettingsKeys.modelFavoritePrefixForProvider(providerName) + encodedModel;
        return Optional.of(mapped);
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
