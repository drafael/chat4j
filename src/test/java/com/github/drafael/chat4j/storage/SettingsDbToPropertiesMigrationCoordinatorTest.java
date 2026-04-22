package com.github.drafael.chat4j.storage;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsDbToPropertiesMigrationCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Migration maps legacy DB settings keys to canonical properties namespace")
    void migrateIfNeeded_whenLegacySettingsPresent_mapsAndPersistsCanonicalKeys() throws Exception {
        var dataSource = createDataSource("settings-migration-known");
        createSettingsTable(dataSource);

        String encodedProvider = URLEncoder.encode("GitHub Copilot", StandardCharsets.UTF_8);
        insertLegacySetting(dataSource, "theme", "GitHub Dark");
        insertLegacySetting(dataSource, "send.key", "Ctrl+Enter");
        insertLegacySetting(dataSource, SettingsKeys.CHAT_RENDER_MODE, SettingsKeys.CHAT_RENDER_MODE_MARKDOWN);
        insertLegacySetting(dataSource, "chat.markdown.conv.legacy-key", "markdown");
        insertLegacySetting(dataSource, "provider.GitHub Copilot.baseUrl", "https://api.githubcopilot.com");
        insertLegacySetting(dataSource, "model.favorite.%s::gpt-5.4-mini".formatted(encodedProvider), "true");

        var settingsRepo = new SettingsRepo(tempDir.resolve("chat4j.properties"));
        var subject = new SettingsDbToPropertiesMigrationCoordinator(dataSource, settingsRepo);

        subject.migrateIfNeeded();

        assertThat(settingsRepo.get(SettingsKeys.THEME_NAME)).contains("GitHub Dark");
        assertThat(settingsRepo.get(SettingsKeys.CHAT_SEND_KEY)).contains("Ctrl+Enter");
        assertThat(settingsRepo.get(SettingsKeys.CHAT_RENDER_MODE))
                .contains(SettingsKeys.CHAT_RENDER_MODE_MARKDOWN);
        assertThat(settingsRepo.findByPrefix("chat4j.legacy.")).isEmpty();
        assertThat(settingsRepo.get(SettingsKeys.providerBaseUrlKey("GitHub Copilot"))).contains("https://api.githubcopilot.com");
        assertThat(settingsRepo.get(SettingsKeys.modelFavoritePrefixForProvider("GitHub Copilot") + "gpt-5.4-mini"))
                .contains("true");
        assertThat(settingsRepo.get(SettingsKeys.SETTINGS_DB_TO_PROPERTIES_MIGRATION_MARKER)).contains("v1");
    }

    @Test
    @DisplayName("Migration preserves existing canonical properties values")
    void migrateIfNeeded_whenCanonicalSettingAlreadyExists_doesNotOverrideValue() throws Exception {
        var dataSource = createDataSource("settings-migration-existing");
        createSettingsTable(dataSource);
        insertLegacySetting(dataSource, "theme", "Solarized Dark");

        var settingsRepo = new SettingsRepo(tempDir.resolve("existing.properties"));
        settingsRepo.put(SettingsKeys.THEME_NAME, "GitHub");

        var subject = new SettingsDbToPropertiesMigrationCoordinator(dataSource, settingsRepo);
        subject.migrateIfNeeded();

        assertThat(settingsRepo.get(SettingsKeys.THEME_NAME)).contains("GitHub");
        assertThat(settingsRepo.get(SettingsKeys.SETTINGS_DB_TO_PROPERTIES_MIGRATION_MARKER)).contains("v1");
    }

    @Test
    @DisplayName("Migration marks no-op when legacy settings table does not exist")
    void migrateIfNeeded_whenLegacySettingsTableMissing_marksNoopMigration() throws Exception {
        var dataSource = createDataSource("settings-migration-no-table");
        var settingsRepo = new SettingsRepo(tempDir.resolve("no-table.properties"));

        var subject = new SettingsDbToPropertiesMigrationCoordinator(dataSource, settingsRepo);
        subject.migrateIfNeeded();

        assertThat(settingsRepo.get(SettingsKeys.SETTINGS_DB_TO_PROPERTIES_MIGRATION_MARKER))
                .contains("v1-no-legacy-table");
    }

    private DataSource createDataSource(String dbName) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(dbName));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createSettingsTable(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE settings (\"key\" VARCHAR(255) PRIMARY KEY, \"value\" VARCHAR(1000))"
             )
        ) {
            statement.execute();
        }
    }

    private void insertLegacySetting(DataSource dataSource, String key, String value) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO settings (\"key\", \"value\") VALUES (?, ?)"
             )
        ) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }
}
