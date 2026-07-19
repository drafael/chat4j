package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.core.ProviderFacade;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderCatalogTest {

    @TempDir
    private Path tempDir;

    @AfterEach
    void tearDown() {
        CredentialResolver.init(emptyMap());
    }

    @Test
    @DisplayName("Provider catalog uses dynamic model loading except static Perplexity seed models")
    void allProviders_whenCatalogInitialized_returnsProvidersWithoutSeedModels() {
        var subject = newCatalog();

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
        var subject = newCatalog();

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
        var subject = newCatalog();
        var copilotProvider = subject.allProviders().stream()
                .filter(providerDefinition -> "GitHub Copilot".equals(providerDefinition.name()))
                .findFirst()
                .orElseThrow();

        Field providerFacadeField = ProviderCatalog.class.getDeclaredField("providerFacade");
        providerFacadeField.setAccessible(true);
        ProviderFacade providerFacade = (ProviderFacade) providerFacadeField.get(subject);

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
    @DisplayName("Copilot metadata resolved before an auth clear cannot publish after the clear")
    void createFetcher_whenAuthClearsDuringRuntimeResolution_rejectsOldAccountMetadata() throws Exception {
        var tokenResolutionStarted = new CountDownLatch(1);
        var releaseTokenResolution = new CountDownLatch(1);
        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("generation-race-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public String resolveBearerTokenOrNull() {
                tokenResolutionStarted.countDown();
                try {
                    releaseTokenResolution.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Token resolution interrupted", e);
                }
                return "tid=old-account;exp=4102444800";
            }
        };
        var metadataStore = new CopilotModelMetadataStore(tempDir.resolve("generation-race-metadata"));
        var subject = new ProviderCatalog(
                copilotAuthResolver,
                new CodexAuthResolver(
                        tempDir.resolve("generation-race-codex-home"),
                        emptyMap(),
                        HttpClient.newHttpClient()
                ),
                metadataStore
        );
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            byte[] body = """
                    {"data":[{"id":"old-account-model","supported_endpoints":["/responses"]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Thread fetchThread = null;

        try {
            String baseUrl = "http://127.0.0.1:%d".formatted(server.getAddress().getPort());
            var fetcher = subject.createFetcher("GitHub Copilot", null, baseUrl);
            var models = new AtomicReference<List<String>>();
            var fetchFailure = new AtomicReference<Throwable>();
            fetchThread = Thread.startVirtualThread(() -> {
                try {
                    models.set(fetcher.fetchModels());
                } catch (Throwable t) {
                    fetchFailure.set(t);
                }
            });

            assertThat(tokenResolutionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(metadataStore.clear()).isTrue();
            releaseTokenResolution.countDown();
            fetchThread.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(fetchThread.isAlive()).isFalse();
            assertThat(fetchFailure.get()).isNull();
            assertThat(models.get()).contains("old-account-model");
            assertThat(metadataStore.supportedEndpoints(baseUrl, "old-account-model")).isEmpty();
        } finally {
            releaseTokenResolution.countDown();
            if (fetchThread != null) {
                fetchThread.interrupt();
                fetchThread.join(TimeUnit.SECONDS.toMillis(2));
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Provider catalog includes local providers for LM Studio and Ollama")
    void allProviders_whenCatalogInitialized_returnsProvidersIncludingLocalProviders() {
        var subject = newCatalog();

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

            var subject = newCatalog();
            var fetcher = subject.createFetcher(
                    "Anthropic",
                    "ANTHROPIC_API_KEY",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            List<String> models = fetcher.fetchModels();

            assertThat(models).containsExactly(
                    "claude-3-7-sonnet-20250219",
                    "claude-3-5-haiku-20241022"
            );
        } finally {
            server.stop(0);
        }
    }

    private ProviderCatalog newCatalog() {
        return new ProviderCatalog(
                new CopilotAuthResolver(tempDir.resolve("copilot-home"), emptyMap(), HttpClient.newHttpClient()),
                new CodexAuthResolver(tempDir.resolve("codex-home"), emptyMap(), HttpClient.newHttpClient()),
                new CopilotModelMetadataStore(tempDir.resolve("metadata"))
        );
    }
}
