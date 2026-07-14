package com.github.drafael.chat4j.chat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectorPopupRefreshPolicyTest {

    @Test
    @DisplayName("Perplexity popup models ignore stale cache and use static Sonar list")
    void initialModels_whenProviderIsPerplexity_returnsStaticSonarModels() {
        List<String> models = ModelSelectorPopup.initialModels(
                "Perplexity",
                List.of("sonar-pro", "sonar"),
                List.of("sonar", "sonar-pro"),
                false
        );

        assertThat(models).containsExactly(
                "sonar",
                "sonar-pro",
                "sonar-reasoning-pro",
                "sonar-deep-research"
        );
    }

    @Test
    @DisplayName("Invalidated popup models use seeds instead of cached account models")
    void initialModels_whenProviderIsInvalidated_returnsSeedModels() {
        List<String> models = ModelSelectorPopup.initialModels(
                "OpenAI",
                List.of("old-account-model"),
                List.of("seed-b", "seed-a"),
                true
        );

        assertThat(models).containsExactly("seed-b", "seed-a");
    }

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
    @DisplayName("Local providers without a base URL remain unavailable")
    void isProviderSelectable_whenLocalBaseUrlIsBlank_returnsFalse() {
        assertThat(ModelSelectorPopup.isProviderSelectable("Ollama", " ")).isFalse();
    }

    @Test
    @DisplayName("Cloud provider selection does not depend on local health state")
    void isProviderSelectable_whenProviderIsCloud_returnsTrue() {
        assertThat(ModelSelectorPopup.isProviderSelectable("OpenAI", null)).isTrue();
    }

    @Test
    @DisplayName("Cloud providers without special policy keep default refresh cadence")
    void refreshTtl_whenProviderHasNoSpecialPolicy_returnsDefaultRefreshTtl() {
        Duration ttl = ModelSelectorPopup.refreshTtl("OpenAI");

        assertThat(ttl).isEqualTo(Duration.ofHours(12));
    }
}
