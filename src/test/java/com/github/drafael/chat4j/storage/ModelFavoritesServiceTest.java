package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFavoritesServiceTest {

    @Test
    @DisplayName("Favorites are reloaded from settings keys during service priming")
    void primeFromSettings_whenFavoritesWerePersisted_loadsFavoritesIntoMemory() throws SQLException {
        var settingsRepo = new InMemorySettingsRepo();
        var writer = new ModelFavoritesService(settingsRepo);
        writer.setFavorite("OpenAI", "gpt-4.1", true);
        writer.setFavorite("Ollama", "qwen3:14b", true);

        var subject = new ModelFavoritesService(settingsRepo);
        subject.primeFromSettings();

        assertThat(subject.isFavorite("OpenAI", "gpt-4.1")).isTrue();
        assertThat(subject.isFavorite("Ollama", "qwen3:14b")).isTrue();
    }

    @Test
    @DisplayName("Toggling a favorite persists add and remove operations")
    void toggleFavorite_whenCalledTwice_addsThenRemovesFavorite() throws SQLException {
        var settingsRepo = new InMemorySettingsRepo();
        var subject = new ModelFavoritesService(settingsRepo);

        boolean firstToggle = subject.toggleFavorite("Ollama", "qwen3:14b");
        boolean secondToggle = subject.toggleFavorite("Ollama", "qwen3:14b");

        assertThat(firstToggle).isTrue();
        assertThat(secondToggle).isFalse();
        assertThat(subject.isFavorite("Ollama", "qwen3:14b")).isFalse();
        assertThat(settingsRepo.countByPrefix(SettingsKeys.MODEL_FAVORITE_PREFIX)).isZero();
    }

    @Test
    @DisplayName("Favorites are tracked by provider and model pair")
    void isFavorite_whenSameModelExistsInDifferentProviders_tracksEntriesIndependently() throws SQLException {
        var settingsRepo = new InMemorySettingsRepo();
        var subject = new ModelFavoritesService(settingsRepo);

        subject.setFavorite("OpenAI", "gpt-4o", true);

        assertThat(subject.isFavorite("OpenAI", "gpt-4o")).isTrue();
        assertThat(subject.isFavorite("OpenRouter", "gpt-4o")).isFalse();
    }

    private static class InMemorySettingsRepo extends SettingsRepo {

        private final Map<String, String> entries = new LinkedHashMap<>();

        private InMemorySettingsRepo() {
            super(Path.of("target", "test-model-favorites-in-memory.properties"));
        }

        @Override
        public void put(String key, String value) {
            entries.put(key, value);
        }

        @Override
        public void remove(String key) {
            entries.remove(key);
        }

        @Override
        public Map<String, String> findByPrefix(String prefix) {
            return entries.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (existing, replacement) -> existing,
                            LinkedHashMap::new
                    ));
        }

        private int countByPrefix(String prefix) {
            return (int) entries.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .count();
        }
    }
}
