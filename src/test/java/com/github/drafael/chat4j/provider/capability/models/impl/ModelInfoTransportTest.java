package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModelInfoTransportTest {

    @Test
    @DisplayName("Google metadata uses the native models endpoint and header authentication")
    void fetchModelInfos_googleProvider_usesNativeEndpointAndDoesNotPutKeyInUrl() throws Exception {
        var captured = new AtomicReference<HttpExchangeRequest>();
        HttpTransport transport = (request, cancellation) -> {
            captured.set(request);
            return response(200, """
                    {"models":[{"name":"models/gemini-test","inputTokenLimit":1000000}]}
                    """);
        };
        var subject = new OpenAiModelCatalogClient(mock(CopilotModelMetadataStore.class), transport);

        assertThat(subject.fetchModelInfos(runtime("Google AI", "https://example.test/v1beta/openai/", "secret")))
                .containsKey("gemini-test");
        assertThat(captured.get().uri().toString()).isEqualTo("https://example.test/v1beta/models");
        assertThat(captured.get().headers()).containsEntry("x-goog-api-key", "secret").doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("OpenRouter metadata uses the configured catalog and bearer credential")
    void fetchModelInfos_openRouterProvider_keepsConfiguredEndpointAndCredential() throws Exception {
        var captured = new AtomicReference<HttpExchangeRequest>();
        HttpTransport transport = (request, cancellation) -> {
            captured.set(request);
            return response(200, """
                    {"data":[{"id":"vendor/model","context_length":128000}]}
                    """);
        };
        var subject = new OpenAiModelCatalogClient(mock(CopilotModelMetadataStore.class), transport);

        assertThat(subject.fetchModelInfos(runtime("OpenRouter", "https://example.test/custom/v1", "secret")))
                .containsKey("vendor/model");
        assertThat(captured.get().uri().toString()).isEqualTo("https://example.test/custom/v1/models");
        assertThat(captured.get().headers()).containsEntry("Authorization", "Bearer secret");
    }

    @Test
    @DisplayName("LM Studio metadata uses its native endpoint rather than its OpenAI-compatible endpoint")
    void fetchModelInfos_lmStudioProvider_usesNativeCatalog() throws Exception {
        var captured = new AtomicReference<HttpExchangeRequest>();
        HttpTransport transport = (request, cancellation) -> {
            captured.set(request);
            return response(200, """
                    {"models":[{"key":"local-model","max_context_length":8192}]}
                    """);
        };
        var subject = new OpenAiModelCatalogClient(mock(CopilotModelMetadataStore.class), transport);

        assertThat(subject.fetchModelInfos(runtime("LM Studio", "http://localhost:1234/v1/", "lmstudio")))
                .containsKey("local-model");
        assertThat(captured.get().uri().toString()).isEqualTo("http://localhost:1234/api/v1/models");
    }

    @Test
    @DisplayName("Together metadata parses a top-level catalog array")
    void fetchModelInfos_togetherProvider_returnsMetadataWithoutChangingDiscovery() throws Exception {
        HttpTransport transport = (request, cancellation) -> response(200, """
                [{"id":"vendor/model","context_length":8192,"pricing":{"input":0.2}}]
                """);
        var subject = new TogetherModelCatalogClient(transport);

        assertThat(subject.fetchModelInfos(runtime("Together", "https://example.test/v1", "secret"))
                .get("vendor/model").inputUsdPerMillion()).isEqualByComparingTo("0.2");
    }

    @Test
    @DisplayName("Copilot metadata exchanges GitHub OAuth credentials and applies Copilot request headers")
    void fetchModelInfos_copilotOAuthToken_usesExchangedCredential() throws Exception {
        var captured = new AtomicReference<HttpExchangeRequest>();
        HttpTransport transport = (request, cancellation) -> {
            if (request.uri().getPath().equals("/copilot_internal/v2/token")) {
                assertThat(request.headers()).containsEntry("Authorization", "token gho_test");
                return response(200, "{\"token\":\"exchanged-token\"}");
            }
            captured.set(request);
            return response(200, """
                    {"data":[{"id":"model","name":"Model"}]}
                    """);
        };
        var subject = new OpenAiModelCatalogClient(mock(CopilotModelMetadataStore.class), transport);

        assertThat(subject.fetchModelInfos(runtime("GitHub Copilot", "https://example.test", "gho_test")))
                .containsKey("model");
        assertThat(captured.get().headers()).containsEntry("Authorization", "Bearer exchanged-token")
                .containsKey("Copilot-Integration-Id");
    }

    @Test
    @DisplayName("Copilot token exchange failures do not become permanently cached missing model information")
    void fetchModelInfos_copilotExchangeFails_reportsFailureForRetry() {
        HttpTransport transport = (request, cancellation) -> response(503, "Unavailable");
        var subject = new OpenAiModelCatalogClient(mock(CopilotModelMetadataStore.class), transport);

        assertThatThrownBy(() -> subject.fetchModelInfos(runtime("GitHub Copilot", "https://example.test", "gho_test")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("authentication unavailable");
    }

    @Test
    @DisplayName("HTTP failures remain failures so the selector can retry instead of caching unsupported metadata")
    void fetchModelInfos_httpFailure_throwsWithoutExposingResponseBody() {
        HttpTransport transport = (request, cancellation) -> response(503, "secret diagnostic body");
        var subject = new OpenAiModelCatalogClient(mock(CopilotModelMetadataStore.class), transport);

        assertThatThrownBy(() -> subject.fetchModelInfos(runtime("OpenRouter", "https://example.test", "secret")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("503")
                .hasMessageNotContaining("secret");
    }

    private ProviderRuntime runtime(String provider, String baseUrl, String key) {
        var descriptor = new ProviderDescriptor(provider, AuthType.ENV_VAR, null, null, baseUrl, null, null, null);
        return new ProviderRuntime(descriptor, null, baseUrl, key, null);
    }

    private HttpExchangeResponse response(int status, String body) {
        return new HttpExchangeResponse(status, emptyMap(), body.getBytes(StandardCharsets.UTF_8));
    }
}
