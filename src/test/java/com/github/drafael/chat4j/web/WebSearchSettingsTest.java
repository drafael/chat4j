package com.github.drafael.chat4j.web;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSearchSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Auto-browse top N defaults to three when missing")
    void autoBrowseTopN_whenSettingMissing_returnsDefault() {
        var subject = new WebSearchSettings(settingsRepo("web-search-default"));

        assertThat(subject.autoBrowseTopN()).isEqualTo(3);
    }

    @Test
    @DisplayName("Auto-browse top N returns persisted value")
    void autoBrowseTopN_whenSettingPersisted_returnsPersistedValue() throws Exception {
        var settingsRepo = settingsRepo("web-search-persisted");
        settingsRepo.put("chat4j.web.autoBrowseTopN", "7");
        var subject = new WebSearchSettings(settingsRepo);

        assertThat(subject.autoBrowseTopN()).isEqualTo(7);
    }

    @Test
    @DisplayName("Auto-browse top N does not clamp parseable values")
    void autoBrowseTopN_whenValueOutsideUiRange_returnsValue() throws Exception {
        var settingsRepo = settingsRepo("web-search-no-clamp");
        settingsRepo.put("chat4j.web.autoBrowseTopN", "99");
        var subject = new WebSearchSettings(settingsRepo);

        assertThat(subject.autoBrowseTopN()).isEqualTo(99);
    }

    @Test
    @DisplayName("Invalid auto-browse top N propagates parse failure")
    void autoBrowseTopN_whenValueInvalid_propagatesFailure() throws Exception {
        var settingsRepo = settingsRepo("web-search-invalid");
        settingsRepo.put("chat4j.web.autoBrowseTopN", "invalid");
        var subject = new WebSearchSettings(settingsRepo);

        assertThatThrownBy(subject::autoBrowseTopN)
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("Persist auto-browse top N writes unchanged setting key")
    void persistAutoBrowseTopN_whenCalled_writesPersistedSettingName() throws Exception {
        var settingsRepo = settingsRepo("web-search-persist");
        var subject = new WebSearchSettings(settingsRepo);

        subject.persistAutoBrowseTopN(5);

        assertThat(settingsRepo.get("chat4j.web.autoBrowseTopN")).contains("5");
    }

    @Test
    @DisplayName("Repository failures propagate")
    void autoBrowseTopN_whenRepositoryFails_propagatesFailure() {
        var subject = new WebSearchSettings(new ThrowingSettingsRepo());

        assertThatThrownBy(subject::autoBrowseTopN)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
        assertThatThrownBy(() -> subject.persistAutoBrowseTopN(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-web-search-settings.properties"));
        }

        @Override
        public Optional<String> get(String key) {
            throw new IllegalStateException("forced failure");
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }
    }
}
