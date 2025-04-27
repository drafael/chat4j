package com.github.drafael.chat4j.storage;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsRepoTest {

    @Test
    @DisplayName("findByPrefix returns matching settings rows in key order")
    void findByPrefix_whenKeysSharePrefix_returnsMatchingRows() throws SQLException {
        DataSource dataSource = createDataSource("settings-prefix");
        createSettingsTable(dataSource);

        var subject = new SettingsRepo(dataSource);
        subject.put("model.favorite.Ollama::qwen3%3A14b", "true");
        subject.put("model.favorite.OpenAI::gpt-4.1", "true");
        subject.put("theme", "GitHub");

        Map<String, String> rows = subject.findByPrefix("model.favorite.");

        assertThat(rows.keySet()).containsExactly(
                "model.favorite.Ollama::qwen3%3A14b",
                "model.favorite.OpenAI::gpt-4.1"
        );
        assertThat(rows.values()).containsOnly("true");
    }

    @Test
    @DisplayName("Model favorites persisted in settings are restored after service restart")
    void modelFavoritesService_whenRestarted_restoresPersistedFavorites() throws SQLException {
        DataSource dataSource = createDataSource("favorites-reload");
        createSettingsTable(dataSource);

        var settingsRepo = new SettingsRepo(dataSource);
        var writer = new ModelFavoritesService(settingsRepo);
        writer.setFavorite("Anthropic", "claude-sonnet-4-6", true);

        var subject = new ModelFavoritesService(settingsRepo);
        subject.primeFromSettings();

        assertThat(subject.isFavorite("Anthropic", "claude-sonnet-4-6")).isTrue();
    }

    private static DataSource createDataSource(String dbName) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createSettingsTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS settings (\"key\" VARCHAR(100) PRIMARY KEY, \"value\" VARCHAR(500))"
             )
        ) {
            statement.execute();
        }
    }
}
