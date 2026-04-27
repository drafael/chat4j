package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentModeSettingsCoordinatorTest {

    @Test
    @DisplayName("Resolve system prompt append returns empty default when value missing")
    void resolveSystemPromptAppend_whenValueMissing_returnsEmpty() {
        SettingsRepo settingsRepo = settingsRepo("agent-mode-defaults");
        var subject = new AgentModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveSystemPromptAppend()).isEmpty();
    }

    @Test
    @DisplayName("Resolve system prompt append returns stored value")
    void resolveSystemPromptAppend_whenStored_returnsValue() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("agent-mode-prompt-resolve");
        settingsRepo.put(SettingsKeys.CHAT_AGENT_SYSTEM_PROMPT_APPEND, "Use terse bullet points");

        var subject = new AgentModeSettingsCoordinator(settingsRepo);

        assertThat(subject.resolveSystemPromptAppend()).isEqualTo("Use terse bullet points");
    }

    @Test
    @DisplayName("Persist system prompt append stores value and removes blank value")
    void persistSystemPromptAppend_whenCalled_storesAndClearsValue() throws Exception {
        SettingsRepo settingsRepo = settingsRepo("agent-mode-persist-prompt");

        var subject = new AgentModeSettingsCoordinator(settingsRepo);
        subject.persistSystemPromptAppend("Always inspect pom.xml first");

        assertThat(settingsRepo.get(SettingsKeys.CHAT_AGENT_SYSTEM_PROMPT_APPEND))
                .contains("Always inspect pom.xml first");

        subject.persistSystemPromptAppend("   ");
        assertThat(settingsRepo.get(SettingsKeys.CHAT_AGENT_SYSTEM_PROMPT_APPEND)).isEmpty();
    }

    private SettingsRepo settingsRepo(String testName) {
        return new SettingsRepo(Path.of("target", "%s.properties".formatted(testName)));
    }
}
