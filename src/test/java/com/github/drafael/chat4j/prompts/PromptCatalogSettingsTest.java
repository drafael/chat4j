package com.github.drafael.chat4j.prompts;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptCatalogSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Prompt catalog settings return empty when catalog is missing")
    void loadCatalogJson_whenMissing_returnsEmpty() {
        var subject = new PromptCatalogSettings(settingsRepo("prompt-catalog-missing"));

        assertThat(subject.loadCatalogJson()).isEmpty();
    }

    @Test
    @DisplayName("Prompt catalog settings save and load raw JSON")
    void saveCatalogJson_whenCalled_persistsRawJson() {
        var settingsRepo = settingsRepo("prompt-catalog-save");
        var subject = new PromptCatalogSettings(settingsRepo);

        subject.saveCatalogJson("[{\"name\":\"Example\"}]");

        assertThat(subject.loadCatalogJson()).contains("[{\"name\":\"Example\"}]");
        assertThat(settingsRepo.get("chat4j.prompts.catalog")).contains("[{\"name\":\"Example\"}]");
    }

    @Test
    @DisplayName("Prompt catalog settings write failures propagate")
    void saveCatalogJson_whenRepositoryFails_propagatesFailure() {
        var subject = new PromptCatalogSettings(new ThrowingSettingsRepo());

        assertThatThrownBy(() -> subject.saveCatalogJson("[]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-prompt-catalog-settings.properties"));
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }
    }
}
