package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectorPopupRefreshPolicyTest {

    @Test
    @DisplayName("GitHub Copilot uses default provider refresh cadence")
    void refreshTtl_whenProviderIsGitHubCopilot_returnsDefaultRefreshTtl() {
        Duration ttl = ModelSelectorPopup.refreshTtl("GitHub Copilot");

        assertThat(ttl).isEqualTo(Duration.ofHours(12));
    }

    @Test
    @DisplayName("Local providers refresh every five minutes")
    void refreshTtl_whenProviderIsLocal_returnsFiveMinuteRefreshTtl() {
        Duration ttl = ModelSelectorPopup.refreshTtl("Ollama");

        assertThat(ttl).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Cloud providers without special policy keep default refresh cadence")
    void refreshTtl_whenProviderHasNoSpecialPolicy_returnsDefaultRefreshTtl() {
        Duration ttl = ModelSelectorPopup.refreshTtl("OpenAI");

        assertThat(ttl).isEqualTo(Duration.ofHours(12));
    }
}
