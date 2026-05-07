package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.core.ProviderFacade;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Collections.emptyMap;

class ProviderCatalogTest {

    @AfterEach
    void tearDown() {
        CredentialResolver.init(emptyMap());
    }

    @Test
    @DisplayName("Provider catalog uses dynamic model loading except static Perplexity seed models")
    void allProviders_whenCatalogInitialized_returnsProvidersWithoutSeedModels() {
        var subject = new ProviderCatalog();

        assertThat(subject.allProviders())
                .filteredOn(providerDefinition -> !"Perplexity".equals(providerDefinition.name()))
                .allSatisfy(providerDefinition -> assertThat(providerDefinition.seedModels()).isEmpty());
        assertThat(subject.allProviders())
                .filteredOn(providerDefinition -> "Perplexity".equals(providerDefinition.name()))
                .singleElement()
                .satisfies(providerDefinition -> assertThat(providerDefinition.seedModels()).containsExactly(
                        "sonar",
                        "sonar-pro",
                        "sonar-reasoning-pro",
                        "sonar-deep-research"
                ));
    }

    @Test
    @DisplayName("Provider catalog keeps Codex and Copilot on Chat4J OAuth auth types")
    void allProviders_whenCatalogInitialized_mapsCodexAndCopilotAuthTypes() {
        var subject = new ProviderCatalog();

        assertThat(subject.allProviders())
                .filteredOn(providerDefinition -> "OpenAI Codex".equals(providerDefinition.name()))
                .singleElement()
                .extracting(providerDefinition -> providerDefinition.descriptor().authType())
                .isEqualTo(AuthType.CODEX_OAUTH);

        assertThat(subject.allProviders())
                .filteredOn(providerDefinition -> "GitHub Copilot".equals(providerDefinition.name()))
                .singleElement()
                .extracting(providerDefinition -> providerDefinition.descriptor().authType())
                .isEqualTo(AuthType.COPILOT_OAUTH);
    }

    @Test
    @DisplayName("Provider catalog shares Copilot metadata store between runtime resolution and model discovery")
    void allProviders_whenCatalogInitialized_sharesCopilotMetadataStoreAcrossFacadeAndFetcher() throws Exception {
        var subject = new ProviderCatalog();
        var copilotProvider = subject.allProviders().stream()
                .filter(providerDefinition -> "GitHub Copilot".equals(providerDefinition.name()))
                .findFirst()
                .orElseThrow();

        Field providerFacadeField = ProviderCatalog.class.getDeclaredField("PROVIDER_FACADE");
        providerFacadeField.setAccessible(true);
        ProviderFacade providerFacade = (ProviderFacade) providerFacadeField.get(null);

        Field facadeStoreField = ProviderFacade.class.getDeclaredField("copilotModelMetadataStore");
        facadeStoreField.setAccessible(true);
        Object facadeStore = facadeStoreField.get(providerFacade);

        Object modelCatalogClient = copilotProvider.module().modelCatalogClient();
        Field clientStoreField = modelCatalogClient.getClass().getDeclaredField("copilotModelMetadataStore");
        clientStoreField.setAccessible(true);
        Object modelCatalogStore = clientStoreField.get(modelCatalogClient);

        assertThat(modelCatalogStore).isSameAs(facadeStore);
    }

    @Test
    @DisplayName("Provider catalog includes local providers for LM Studio and Ollama")
    void allProviders_whenCatalogInitialized_returnsProvidersIncludingLocalProviders() {
        var subject = new ProviderCatalog();

        assertThat(subject.allProviders())
                .extracting(ProviderDefinition::name)
                .contains("LM Studio", "Ollama");
    }

    @Test
    @DisplayName("Anthropic fetcher loads model names dynamically from API and normalizes /v1 base URLs")
    void createFetcher_whenAnthropicProvider_returnsModelNamesFromApi() throws Exception {
        var responseJson = """
                {
                  "data": [
                    {
                      "id": "claude-3-7-sonnet-20250219",
                      "created_at": "2025-02-19T00:00:00Z",
                      "display_name": "Claude 3.7 Sonnet",
                      "type": "model"
                    },
                    {
                      "id": "claude-3-5-haiku-20241022",
                      "created_at": "2024-10-22T00:00:00Z",
                      "display_name": "Claude 3.5 Haiku",
                      "type": "model"
                    }
                  ],
                  "first_id": "claude-3-7-sonnet-20250219",
                  "last_id": "claude-3-5-haiku-20241022",
                  "has_more": false
                }
                """;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            CredentialResolver.init(Map.of("ANTHROPIC_API_KEY", "test-api-key"));

            var subject = new ProviderCatalog();
            var fetcher = subject.createFetcher(
                    "Anthropic",
                    "ANTHROPIC_API_KEY",
                    "http://127.0.0.1:%d/v1".formatted(port));

            List<String> models = fetcher.fetchModels();

            assertThat(models).containsExactly(
                    "claude-3-7-sonnet-20250219",
                    "claude-3-5-haiku-20241022"
            );
        } finally {
            server.stop(0);
        }
    }
}
