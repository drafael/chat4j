package com.github.drafael.chat4j.provider.capability.models.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import com.github.drafael.chat4j.provider.support.ModelFilters;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.models.Model;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import static java.util.Collections.emptyList;

@Slf4j
public class OpenAiModelCatalogClient implements ModelCatalogClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final String COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String COPILOT_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.tokenEndpoint";
    private static final String COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.allowCustomTokenEndpoint";
    private static final String COPILOT_TOKEN_ENDPOINT_DEFAULT = "https://api.github.com/copilot_internal/v2/token";
    private static final Set<String> TRUSTED_COPILOT_TOKEN_ENDPOINT_HOSTS = Set.of("api.github.com");
    private static final Duration COPILOT_EXCHANGE_SUCCESS_TTL = Duration.ofMinutes(10);
    private static final Duration COPILOT_EXCHANGE_FAILURE_TTL = Duration.ofMinutes(2);
    private static final Map<String, CopilotExchangedTokenSnapshot> COPILOT_TOKEN_CACHE = new ConcurrentHashMap<>();

    private final CopilotModelMetadataStore copilotModelMetadataStore;
    private final HttpClient httpClient;

    public OpenAiModelCatalogClient() {
        this(new CopilotModelMetadataStore(), DEFAULT_HTTP_CLIENT);
    }

    public OpenAiModelCatalogClient(CopilotModelMetadataStore copilotModelMetadataStore) {
        this(copilotModelMetadataStore, DEFAULT_HTTP_CLIENT);
    }

    OpenAiModelCatalogClient(CopilotModelMetadataStore copilotModelMetadataStore, HttpClient httpClient) {
        this.copilotModelMetadataStore = copilotModelMetadataStore;
        this.httpClient = httpClient;
    }

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        if (Thread.currentThread().isInterrupted()) {
            return emptyList();
        }

        if (isCopilotProvider(runtime)) {
            CatalogFetchResult copilotCatalog = fetchCopilotModels(runtime);
            return Thread.currentThread().isInterrupted()
                    ? emptyList()
                    : copilotCatalog.modelIds();
        }

        try {
            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .apiKey(runtime.apiKey())
                    .baseUrl(runtime.baseUrl())
                    .build();

            List<String> models = client.models().list().data().stream()
                    .filter(model -> ModelFilters.isSupportedChatModelId(model.id()))
                    .sorted((left, right) -> {
                        int byRecency = ModelOrdering.compareByRecency(left.id(), right.id());
                        return byRecency != 0
                                ? byRecency
                                : Long.compare(right.created(), left.created());
                    })
                    .map(Model::id)
                    .toList();

            if (Thread.currentThread().isInterrupted()) {
                return emptyList();
            }
            return models.isEmpty() ? fallbackModels(runtime) : models;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return emptyList();
            }
            log.debug("Primary model listing failed for {}: {}",
                    runtime.descriptor().name(), ExceptionUtils.getMessage(e));
            return fallbackModels(runtime);
        }
    }

    private List<String> fallbackModels(ProviderRuntime runtime) {
        CatalogFetchResult httpFallback = fetchModelsFromHttp(runtime, runtime.apiKey(), false);
        if (Thread.currentThread().isInterrupted()) {
            return emptyList();
        }
        if (!httpFallback.modelIds().isEmpty()) {
            log.info("Recovered model listing for {} via HTTP fallback ({} models)",
                    runtime.descriptor().name(), httpFallback.modelIds().size());
            return httpFallback.modelIds();
        }

        log.warn("No models available for {} after fallback attempts", runtime.descriptor().name());
        return emptyList();
    }

    private CatalogFetchResult fetchCopilotModels(ProviderRuntime runtime) {
        String apiKey = runtime.apiKey();
        if (StringUtils.isBlank(apiKey)) {
            return CatalogFetchResult.empty();
        }

        if (looksLikeGitHubOAuthToken(apiKey)) {
            String exchangedToken = exchangeCopilotTokenCached(apiKey);
            if (Thread.currentThread().isInterrupted() || StringUtils.isBlank(exchangedToken)) {
                return CatalogFetchResult.empty();
            }

            CatalogFetchResult exchangedCatalog = fetchModelsFromHttp(runtime, exchangedToken, true);
            persistCopilotMetadata(runtime, exchangedCatalog);
            return exchangedCatalog;
        }

        CatalogFetchResult directCatalog = fetchModelsFromHttp(runtime, apiKey, true);
        persistCopilotMetadata(runtime, directCatalog);
        return directCatalog;
    }

    private CatalogFetchResult fetchModelsFromHttp(ProviderRuntime runtime, String apiKey, boolean copilotHeadersRequired) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(modelsEndpoint(runtime.baseUrl())))
                    .timeout(Duration.ofSeconds(4))
                    .GET();

            if (StringUtils.isNotBlank(apiKey)) {
                requestBuilder.header("Authorization", "Bearer %s".formatted(apiKey));
            }

            if (copilotHeadersRequired) {
                CopilotRequestHeaders.asMap().forEach(requestBuilder::header);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return CatalogFetchResult.empty();
            }

            JsonNode root = JSON.readTree(response.body());
            List<JsonNode> modelEntries = extractModelEntries(root);
            if (modelEntries.isEmpty()) {
                return CatalogFetchResult.empty();
            }

            List<JsonNode> pickerFilteredEntries = applyCopilotModelPickerFilter(runtime, modelEntries);
            List<JsonNode> selectableEntries = pickerFilteredEntries.stream()
                    .filter(modelNode -> supportsConfiguredApiEndpoint(runtime, modelNode))
                    .toList();

            List<String> modelIds = selectableEntries.stream()
                    .map(OpenAiModelCatalogClient::modelId)
                    .filter(StringUtils::isNotBlank)
                    .filter(ModelFilters::isSupportedChatModelId)
                    .toList();

            return new CatalogFetchResult(
                    ModelOrdering.sanitizeAndSortByProvider(runtime.descriptor().name(), modelIds),
                    isCopilotProvider(runtime) ? toCopilotModelMetadata(selectableEntries) : emptyList()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("HTTP model listing interrupted for {}", runtime.descriptor().name());
            return CatalogFetchResult.empty();
        } catch (Exception e) {
            log.debug("HTTP model listing failed for {}: {}",
                    runtime.descriptor().name(), ExceptionUtils.getMessage(e));
            return CatalogFetchResult.empty();
        }
    }

    private String exchangeCopilotTokenCached(String githubToken) {
        long now = System.currentTimeMillis();
        COPILOT_TOKEN_CACHE.entrySet()
                .removeIf(entry -> now >= entry.getValue().expiresAtEpochMs());
        String cacheKey = tokenCacheKey(githubToken);
        CopilotExchangedTokenSnapshot cached = COPILOT_TOKEN_CACHE.get(cacheKey);
        if (cached != null) {
            return cached.exchangedToken();
        }

        String exchangedToken = exchangeCopilotToken(githubToken);
        if (Thread.currentThread().isInterrupted()) {
            return exchangedToken;
        }

        Duration ttl = StringUtils.isBlank(exchangedToken) ? COPILOT_EXCHANGE_FAILURE_TTL : COPILOT_EXCHANGE_SUCCESS_TTL;
        COPILOT_TOKEN_CACHE.put(
                cacheKey,
                new CopilotExchangedTokenSnapshot(exchangedToken, now + ttl.toMillis())
        );
        return exchangedToken;
    }

    private static String tokenCacheKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private String exchangeCopilotToken(String githubToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(copilotTokenEndpoint()))
                    .timeout(Duration.ofSeconds(4))
                    .header("Authorization", "token %s".formatted(githubToken))
                    .header("Accept", "application/json")
                    .header("User-Agent", "chat4j")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = JSON.readTree(response.body());
            String token = root.path("token").asText("");
            return StringUtils.isBlank(token) ? null : token.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Copilot token exchange interrupted");
            return null;
        } catch (Exception e) {
            log.debug("Copilot token exchange failed: {}", ExceptionUtils.getMessage(e));
            return null;
        }
    }

    String copilotTokenEndpoint() {
        String configuredEndpoint = StringUtils.defaultIfBlank(System.getProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY), COPILOT_TOKEN_ENDPOINT_DEFAULT);
        if (Boolean.getBoolean(COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY)) {
            return configuredEndpoint;
        }

        if (isTrustedCopilotTokenEndpoint(configuredEndpoint)) {
            return configuredEndpoint;
        }

        log.warn("Ignoring untrusted Copilot token endpoint override");
        return COPILOT_TOKEN_ENDPOINT_DEFAULT;
    }

    private boolean isTrustedCopilotTokenEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }

            String host = StringUtils.defaultString(uri.getHost()).toLowerCase(Locale.ROOT);
            if (!TRUSTED_COPILOT_TOKEN_ENDPOINT_HOSTS.contains(host)) {
                return false;
            }

            return "/copilot_internal/v2/token".equals(uri.getPath());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean looksLikeGitHubOAuthToken(String token) {
        String normalized = token.trim();
        return normalized.startsWith("gho_")
                || normalized.startsWith("ghu_")
                || normalized.startsWith("github_pat_");
    }

    private boolean isCopilotProvider(ProviderRuntime runtime) {
        return COPILOT_PROVIDER_NAME.equals(runtime.descriptor().name());
    }

    private boolean supportsConfiguredApiEndpoint(ProviderRuntime runtime, JsonNode modelNode) {
        if (!isCopilotProvider(runtime) || !modelNode.isObject()) {
            return true;
        }

        JsonNode endpoints = modelNode.path("supported_endpoints");
        if (!endpoints.isArray() || endpoints.isEmpty()) {
            return true;
        }

        return StreamSupport.stream(endpoints.spliterator(), false)
                .map(endpoint -> endpoint.asText(""))
                .anyMatch(this::isSupportedCopilotEndpoint);
    }

    private boolean isSupportedCopilotEndpoint(String endpoint) {
        return "/chat/completions".equals(endpoint) || "/responses".equals(endpoint);
    }

    private void persistCopilotMetadata(ProviderRuntime runtime, CatalogFetchResult catalog) {
        if (Thread.currentThread().isInterrupted()
                || !isCopilotProvider(runtime)
                || catalog.metadata().isEmpty()) {
            return;
        }

        copilotModelMetadataStore.update(runtime.baseUrl(), catalog.metadata());
    }

    private List<CopilotModelMetadataStore.ModelMetadata> toCopilotModelMetadata(List<JsonNode> modelEntries) {
        return modelEntries.stream()
                .map(this::toCopilotModelMetadata)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<CopilotModelMetadataStore.ModelMetadata> toCopilotModelMetadata(JsonNode modelNode) {
        String id = modelId(modelNode);
        if (StringUtils.isBlank(id)) {
            return Optional.empty();
        }

        List<String> supportedEndpoints = extractSupportedEndpoints(modelNode);
        return Optional.of(new CopilotModelMetadataStore.ModelMetadata(id, supportedEndpoints));
    }

    private List<String> extractSupportedEndpoints(JsonNode modelNode) {
        JsonNode endpointsNode = modelNode.path("supported_endpoints");
        if (!endpointsNode.isArray()) {
            return emptyList();
        }

        return StreamSupport.stream(endpointsNode.spliterator(), false)
                .map(endpoint -> endpoint.asText(""))
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private static String modelsEndpoint(String baseUrl) {
        return baseUrl.endsWith("/")
                ? "%smodels".formatted(baseUrl)
                : "%s/models".formatted(baseUrl);
    }

    private static String modelId(JsonNode modelNode) {
        if (modelNode.isTextual()) {
            return modelNode.asText("");
        }

        String id = modelNode.path("id").asText("");
        if (StringUtils.isNotBlank(id)) {
            return id;
        }

        return modelNode.path("name").asText("");
    }

    private List<JsonNode> extractModelEntries(JsonNode root) {
        JsonNode data = root.path("data");
        if (data.isArray()) {
            return StreamSupport.stream(data.spliterator(), false).toList();
        }

        JsonNode models = root.path("models");
        if (models.isArray()) {
            return StreamSupport.stream(models.spliterator(), false).toList();
        }

        return emptyList();
    }

    private List<JsonNode> applyCopilotModelPickerFilter(ProviderRuntime runtime, List<JsonNode> modelEntries) {
        if (!isCopilotProvider(runtime) || modelEntries.isEmpty()) {
            return modelEntries;
        }

        boolean hasModelPickerField = modelEntries.stream()
                .filter(JsonNode::isObject)
                .anyMatch(modelNode -> modelNode.has("model_picker_enabled"));
        if (!hasModelPickerField) {
            return modelEntries;
        }

        List<JsonNode> pickerEnabled = modelEntries.stream()
                .filter(JsonNode::isObject)
                .filter(modelNode -> modelNode.path("model_picker_enabled").asBoolean(false))
                .toList();

        if (pickerEnabled.size() >= 2 || pickerEnabled.size() == modelEntries.size()) {
            return pickerEnabled;
        }

        return modelEntries;
    }

    private record CopilotExchangedTokenSnapshot(String exchangedToken, long expiresAtEpochMs) {

        @Override
        public String toString() {
            return "CopilotExchangedTokenSnapshot[exchangedToken=<masked>, expiresAtEpochMs=%d]".formatted(expiresAtEpochMs);
        }
    }

    private record CatalogFetchResult(
            List<String> modelIds,
            List<CopilotModelMetadataStore.ModelMetadata> metadata
    ) {

        private static CatalogFetchResult empty() {
            return new CatalogFetchResult(emptyList(), emptyList());
        }
    }
}
