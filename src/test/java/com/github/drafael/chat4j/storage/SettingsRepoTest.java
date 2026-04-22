package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsRepoTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("findByPrefix returns matching settings rows in key order")
    void findByPrefix_whenKeysSharePrefix_returnsMatchingRows() throws SQLException {
        var subject = new SettingsRepo(tempDir.resolve("chat4j.properties"));
        subject.put("chat4j.models.favorite.ollama::qwen3%3A14b", "true");
        subject.put("chat4j.models.favorite.openai::gpt-4.1", "true");
        subject.put("chat4j.ui.theme.name", "GitHub");

        Map<String, String> rows = subject.findByPrefix("chat4j.models.favorite.");

        assertThat(rows.keySet()).containsExactly(
                "chat4j.models.favorite.ollama::qwen3%3A14b",
                "chat4j.models.favorite.openai::gpt-4.1"
        );
        assertThat(rows.values()).containsOnly("true");
    }

    @Test
    @DisplayName("Model favorites persisted in settings are restored after service restart")
    void modelFavoritesService_whenRestarted_restoresPersistedFavorites() throws SQLException {
        Path settingsFile = tempDir.resolve("chat4j.properties");
        var settingsRepo = new SettingsRepo(settingsFile);
        var writer = new ModelFavoritesService(settingsRepo);
        writer.setFavorite("Anthropic", "claude-sonnet-4-6", true);

        var subject = new ModelFavoritesService(new SettingsRepo(settingsFile));
        subject.primeFromSettings();

        assertThat(subject.isFavorite("Anthropic", "claude-sonnet-4-6")).isTrue();
    }
}
