package com.github.drafael.chat4j.persistence.settings;

import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("findByPrefix returns matching settings rows in key order")
    void findByPrefix_whenKeysSharePrefix_returnsMatchingRows() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));
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
    void modelFavoritesService_whenRestarted_restoresPersistedFavorites() {
        Path settingsFile = tempDir.resolve("chat4j.properties");
        var settingsRepo = new SettingsRepository(settingsFile);
        var writer = new ModelFavoritesService(settingsRepo);
        writer.setFavorite("Anthropic", "claude-sonnet-4-6", true);

        var subject = new ModelFavoritesService(new SettingsRepository(settingsFile));
        subject.primeFromSettings();

        assertThat(subject.isFavorite("Anthropic", "claude-sonnet-4-6")).isTrue();
    }

    @Test
    @DisplayName("File failures use settings storage exceptions")
    void put_whenSettingsPathIsDirectory_throwsSettingsStorageException() {
        var subject = new SettingsRepository(tempDir);

        assertThatThrownBy(() -> subject.put("chat4j.ui.theme.name", "GitHub"))
                .isInstanceOf(SettingsStorageException.class)
                .hasMessageContaining("settings file");
    }

    @Test
    @DisplayName("Batch updates persist correlated values atomically")
    void updateBatch_whenMultipleValuesProvided_persistsAllValues() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));

        subject.updateBatch(batch -> {
            batch.put("chat4j.stt.provider", "groq");
            batch.put("chat4j.stt.groq.model.id", "whisper-large-v3-turbo");
            batch.put("chat4j.stt.groq.model.label", "Whisper Large v3 Turbo");
        });

        assertThat(subject.get("chat4j.stt.provider")).contains("groq");
        assertThat(subject.get("chat4j.stt.groq.model.id")).contains("whisper-large-v3-turbo");
        assertThat(subject.get("chat4j.stt.groq.model.label")).contains("Whisper Large v3 Turbo");
    }

    @Test
    @DisplayName("Batch updates reject blank keys")
    void updateBatch_whenKeyBlank_rejectsOperation() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));

        assertThatThrownBy(() -> subject.updateBatch(batch -> batch.put(" ", "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
