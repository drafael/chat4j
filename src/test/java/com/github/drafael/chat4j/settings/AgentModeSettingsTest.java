package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentModeSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve system prompt append returns empty default when value missing")
    void resolveSystemPromptAppend_whenValueMissing_returnsEmpty() {
        SettingsRepository settingsRepo = settingsRepo("agent-mode-defaults");
        var subject = new AgentModeSettings(settingsRepo);

        assertThat(subject.resolveSystemPromptAppend()).isEmpty();
    }

    @Test
    @DisplayName("Resolve system prompt append returns stored value")
    void resolveSystemPromptAppend_whenStored_returnsValue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("agent-mode-prompt-resolve");
        settingsRepo.put("chat4j.chat.agent.systemPromptAppend", "Use terse bullet points");

        var subject = new AgentModeSettings(settingsRepo);

        assertThat(subject.resolveSystemPromptAppend()).isEqualTo("Use terse bullet points");
    }

    @Test
    @DisplayName("Resolve system prompt append returns empty when repository read fails")
    void resolveSystemPromptAppend_whenRepositoryFails_returnsEmpty() {
        var subject = new AgentModeSettings(new ThrowingSettingsRepo(true));

        assertThat(subject.resolveSystemPromptAppend()).isEmpty();
    }

    @Test
    @DisplayName("Persist system prompt append stores value and removes blank value")
    void persistSystemPromptAppend_whenCalled_storesAndClearsValue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("agent-mode-persist-prompt");

        var subject = new AgentModeSettings(settingsRepo);
        subject.persistSystemPromptAppend("Always inspect pom.xml first");

        assertThat(settingsRepo.get("chat4j.chat.agent.systemPromptAppend"))
                .contains("Always inspect pom.xml first");

        subject.persistSystemPromptAppend("   ");
        assertThat(settingsRepo.get("chat4j.chat.agent.systemPromptAppend")).isEmpty();
    }

    @Test
    @DisplayName("Persist system prompt append write failures propagate")
    void persistSystemPromptAppend_whenRepositoryFails_propagatesFailure() {
        var subject = new AgentModeSettings(new ThrowingSettingsRepo(false));

        assertThatThrownBy(() -> subject.persistSystemPromptAppend("Use terse bullet points"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    @Test
    @DisplayName("Persist blank system prompt append remove failures propagate")
    void persistSystemPromptAppend_whenBlankRemoveFails_propagatesFailure() {
        var subject = new AgentModeSettings(new ThrowingSettingsRepo(false));

        assertThatThrownBy(() -> subject.persistSystemPromptAppend("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced failure");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private final boolean failReads;

        private ThrowingSettingsRepo(boolean failReads) {
            super(Path.of("unused-agent-mode-settings.properties"));
            this.failReads = failReads;
        }

        @Override
        public Optional<String> get(String key) {
            if (failReads) {
                throw new IllegalStateException("forced failure");
            }
            return Optional.empty();
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }

        @Override
        public void remove(String key) {
            throw new IllegalStateException("forced failure");
        }
    }
}
