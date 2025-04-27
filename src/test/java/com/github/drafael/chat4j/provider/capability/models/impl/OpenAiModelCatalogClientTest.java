package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiModelCatalogClientTest {

    private static final OpenAiModelCatalogClient subject = new OpenAiModelCatalogClient();
    private String originalUserHome;

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @DisplayName("Codex OAuth provider falls back to local codex models cache when API listing fails")
    void fetchModels_whenCodexApiListingFails_usesLocalCodexModelsCache() throws Exception {
        originalUserHome = System.getProperty("user.home");
        Path tempHome = Files.createTempDirectory("chat4j-codex-home");
        System.setProperty("user.home", tempHome.toString());

        Path codexDir = tempHome.resolve(".codex");
        Files.createDirectories(codexDir);
        Files.writeString(codexDir.resolve("models_cache.json"), """
                {
                  "models": [
                    {"slug": "gpt-5-codex"},
                    {"slug": "gpt-5-codex-mini"},
                    {"slug": "gpt-5-codex"}
                  ]
                }
                """);

        ProviderDescriptor descriptor = new ProviderDescriptor(
                "OpenAI Codex",
                AuthType.CLI_OAUTH,
                null,
                null,
                null,
                "http://127.0.0.1:9",
                List.of(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity());

        ProviderRuntime runtime = new ProviderRuntime(
                descriptor,
                null,
                "http://127.0.0.1:9",
                "test-token",
                null
        );

        List<String> models = subject.fetchModels(runtime);

        assertThat(models).contains("gpt-5-codex", "gpt-5-codex-mini");
        assertThat(models).doesNotHaveDuplicates();
    }
}
