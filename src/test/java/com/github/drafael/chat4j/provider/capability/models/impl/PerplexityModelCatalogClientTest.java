package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerplexityModelCatalogClientTest {

    @Test
    @DisplayName("Perplexity model catalog returns the supported Sonar chat models")
    void fetchModels_whenCalled_returnsStaticSonarModels() {
        var subject = new PerplexityModelCatalogClient();

        List<String> models = subject.fetchModels(runtime("http://127.0.0.1:1"));

        assertThat(models).containsExactly(
                "sonar",
                "sonar-pro",
                "sonar-reasoning-pro",
                "sonar-deep-research"
        );
    }

    private ProviderRuntime runtime(String baseUrl) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "Perplexity",
                        AuthType.ENV_VAR,
                        "PERPLEXITY_API_KEY",
                        null,
                        "https://api.perplexity.ai",
                        List.of("sonar", "sonar-pro"),
                        ProviderCapabilities.chatModelsAndNativeWebSearch(),
                        value -> value
                ),
                "PERPLEXITY_API_KEY",
                baseUrl,
                "test-key",
                null
        );
    }
}
