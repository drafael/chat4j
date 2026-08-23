package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import com.github.drafael.chat4j.provider.support.MistralNativeWebSearchSupport;
import com.github.drafael.chat4j.provider.support.ModelFilters;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.models.Model;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.URI;
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

import static java.util.Collections.emptyList;

@Slf4j
public class OpenAiModelCatalogClient implements ModelCatalogClient {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final HttpTransport DEFAULT_TRANSPORT = JavaNetHttpTransport.create(Duration.ofSeconds(3), RedirectPolicy.NEVER);
    private static final String COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String COPILOT_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.tokenEndpoint";
    private static final String COPILOT_ALLOW_CUSTOM_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.allowCustomTokenEndpoint";
    private static final String COPILOT_TOKEN_ENDPOINT_DEFAULT = "https://api.github.com/copilot_internal/v2/token";
    private static final Set<String> TRUSTED_COPILOT_TOKEN_ENDPOINT_HOSTS = Set.of("api.github.com");
    private static final Duration COPILOT_EXCHANGE_SUCCESS_TTL = Duration.ofMinutes(10);
    private static final Duration COPILOT_EXCHANGE_FAILURE_TTL = Duration.ofMinutes(2);
    private final Map<String, CopilotExchangedTokenSnapshot> copilotTokenCache = new ConcurrentHashMap<>();
    private final CopilotModelMetadataStore copilotModelMetadataStore;
    private final HttpTransport transport;

    public OpenAiModelCatalogClient(CopilotModelMetadataStore copilotModelMetadataStore) {
        this(copilotModelMetadataStore, DEFAULT_TRANSPORT);
    }

    OpenAiModelCatalogClient(CopilotModelMetadataStore copilotModelMetadataStore, HttpTransport transport) {
        this.copilotModelMetadataStore = copilotModelMetadataStore;
        this.transport = transport;
    }

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        return fetchModels(runtime, copilotModelMetadataStore.currentGeneration());
    }

    @Override
    public List<String> fetchModels(ProviderRuntime runtime, long metadataGeneration) {
        if (Thread.currentThread().isInterrupted()) {
            return emptyList();
        }

        if (isCopilotProvider(runtime)) {
            CatalogFetchResult copilotCatalog = fetchCopilotModels(runtime, metadataGeneration);
            return Thread.currentThread().isInterrupted()
                    ? emptyList()
                    : copilotCatalog.modelIds();
        }

        OpenAIClient client = null;
        try {
            client = OpenAIOkHttpClient.builder()
                    .apiKey(runtime.apiKey())
                    .baseUrl(runtime.baseUrl())
                    .build();
            List<String> models = client.models().list().data().stream()
                    .filter(model -> isSupportedChatModel(runtime, model.id()))
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
            if (!models.isEmpty()) {
                return models;
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                return emptyList();
            }
            log.debug("Primary model listing failed for {}: {}",
                    runtime.descriptor().name(),
                    ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), runtime.apiKey()));
        } finally {
            if (client != null) {
                client.close();
            }
        }
        return fallbackModels(runtime);
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

    private CatalogFetchResult fetchCopilotModels(ProviderRuntime runtime, long metadataGeneration) {
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
            persistCopilotMetadata(runtime, exchangedCatalog, metadataGeneration);
            return exchangedCatalog;
        }

        CatalogFetchResult directCatalog = fetchModelsFromHttp(runtime, apiKey, true);
        persistCopilotMetadata(runtime, directCatalog, metadataGeneration);
        return directCatalog;
    }

    private CatalogFetchResult fetchModelsFromHttp(ProviderRuntime runtime, String apiKey, boolean copilotHeadersRequired) {
        try {
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            if (StringUtils.isNotBlank(apiKey)) {
                headers.put("Authorization", "Bearer %s".formatted(apiKey));
            }
            if (copilotHeadersRequired) {
                headers.putAll(CopilotRequestHeaders.asMap());
            }
            var request = new HttpExchangeRequest(
                    "GET",
                    URI.create(modelsEndpoint(runtime.baseUrl())),
                    headers,
                    HttpBody.empty(),
                    Duration.ofSeconds(4),
                    0
            );
            HttpExchangeResponse response = transport.send(request, Thread.currentThread()::isInterrupted);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return CatalogFetchResult.empty();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> root = JSON.read(response.body(), Map.class);
            if (!hasCatalogArray(root)) {
                return CatalogFetchResult.empty();
            }
            List<Object> modelEntries = extractModelEntries(root);
            if (modelEntries.isEmpty()) {
                return CatalogFetchResult.successfulEmpty();
            }
            if (modelEntries.stream().anyMatch(model -> !isValidCatalogEntry(runtime, model))) {
                return CatalogFetchResult.empty();
            }

            List<Object> pickerFilteredEntries = applyCopilotModelPickerFilter(runtime, modelEntries);
            List<Object> selectableEntries = pickerFilteredEntries.stream()
                    .filter(modelNode -> supportsConfiguredApiEndpoint(runtime, modelNode))
                    .toList();

            List<String> modelIds = selectableEntries.stream()
                    .map(OpenAiModelCatalogClient::modelId)
                    .filter(StringUtils::isNotBlank)
                    .filter(modelId -> isSupportedChatModel(runtime, modelId))
                    .toList();

            return new CatalogFetchResult(
                    ModelOrdering.sanitizeAndSortByProvider(runtime.descriptor().name(), modelIds),
                    isCopilotProvider(runtime) ? toCopilotModelMetadata(selectableEntries) : emptyList(),
                    true
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("HTTP model listing interrupted for {}", runtime.descriptor().name());
            return CatalogFetchResult.empty();
        } catch (Exception e) {
            log.debug("HTTP model listing failed for {}: {}",
                    runtime.descriptor().name(),
                    ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), apiKey));
            return CatalogFetchResult.empty();
        }
    }

    private String exchangeCopilotTokenCached(String githubToken) {
        long now = System.currentTimeMillis();
        copilotTokenCache.entrySet()
                .removeIf(entry -> now >= entry.getValue().expiresAtEpochMs());
        String cacheKey = tokenCacheKey(githubToken);
        CopilotExchangedTokenSnapshot cached = copilotTokenCache.get(cacheKey);
        if (cached != null) {
            return cached.exchangedToken();
        }

        String exchangedToken = exchangeCopilotToken(githubToken);
        if (Thread.currentThread().isInterrupted()) {
            return exchangedToken;
        }

        Duration ttl = StringUtils.isBlank(exchangedToken) ? COPILOT_EXCHANGE_FAILURE_TTL : COPILOT_EXCHANGE_SUCCESS_TTL;
        copilotTokenCache.put(
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
            var request = new HttpExchangeRequest(
                    "GET",
                    URI.create(copilotTokenEndpoint()),
                    Map.of(
                            "Authorization", "token %s".formatted(githubToken),
                            "Accept", "application/json",
                            "User-Agent", "chat4j"
                    ),
                    HttpBody.empty(),
                    Duration.ofSeconds(4),
                    0
            );

            HttpExchangeResponse response = transport.send(request, Thread.currentThread()::isInterrupted);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> root = JSON.read(response.body(), Map.class);
            String token = stringValue(root.get("token"));
            return StringUtils.isBlank(token) ? null : token.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Copilot token exchange interrupted");
            return null;
        } catch (Exception e) {
            log.debug(
                    "Copilot token exchange failed: {}",
                    ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), githubToken)
            );
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

    private boolean isSupportedChatModel(ProviderRuntime runtime, String modelId) {
        return ModelFilters.isSupportedChatModelId(modelId)
                && (!"Mistral".equals(runtime.descriptor().name())
                || MistralNativeWebSearchSupport.isChatModel(modelId));
    }

    private boolean supportsConfiguredApiEndpoint(ProviderRuntime runtime, Object modelEntry) {
        Map<String, Object> model = objectMap(modelEntry);
        if (!isCopilotProvider(runtime) || model == null) {
            return true;
        }
        Object endpointsValue = model.get("supported_endpoints");
        if (!(endpointsValue instanceof List<?> endpoints) || endpoints.isEmpty()) {
            return true;
        }
        return endpoints.stream().map(OpenAiModelCatalogClient::stringValue).anyMatch(this::isSupportedCopilotEndpoint);
    }

    private boolean isSupportedCopilotEndpoint(String endpoint) {
        return "/chat/completions".equals(endpoint) || "/responses".equals(endpoint);
    }

    private void persistCopilotMetadata(
            ProviderRuntime runtime,
            CatalogFetchResult catalog,
            long metadataGeneration
    ) {
        if (Thread.currentThread().isInterrupted() || !isCopilotProvider(runtime) || !catalog.authoritative()) {
            return;
        }

        copilotModelMetadataStore.updateIfGenerationCurrent(
                metadataGeneration,
                runtime.baseUrl(),
                catalog.metadata()
        );
    }

    private List<CopilotModelMetadataStore.ModelMetadata> toCopilotModelMetadata(List<Object> modelEntries) {
        return modelEntries.stream()
                .map(this::toCopilotModelMetadata)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<CopilotModelMetadataStore.ModelMetadata> toCopilotModelMetadata(Object modelNode) {
        String id = modelId(modelNode);
        if (StringUtils.isBlank(id)) {
            return Optional.empty();
        }

        List<String> supportedEndpoints = extractSupportedEndpoints(modelNode);
        return Optional.of(new CopilotModelMetadataStore.ModelMetadata(id, supportedEndpoints));
    }

    private List<String> extractSupportedEndpoints(Object modelEntry) {
        Map<String, Object> model = objectMap(modelEntry);
        Object endpointsValue = model == null ? null : model.get("supported_endpoints");
        if (!(endpointsValue instanceof List<?> endpoints)) {
            return emptyList();
        }
        return endpoints.stream().map(OpenAiModelCatalogClient::stringValue).filter(StringUtils::isNotBlank).toList();
    }

    private static String modelsEndpoint(String baseUrl) {
        return baseUrl.endsWith("/")
                ? "%smodels".formatted(baseUrl)
                : "%s/models".formatted(baseUrl);
    }

    private static String modelId(Object modelEntry) {
        if (modelEntry instanceof String id) {
            return id;
        }
        Map<String, Object> model = objectMap(modelEntry);
        if (model == null) {
            return "";
        }
        String id = stringValue(model.get("id"));
        return StringUtils.isNotBlank(id) ? id : stringValue(model.get("name"));
    }

    private boolean hasCatalogArray(Map<String, Object> root) {
        return root.get("data") instanceof List<?> || root.get("models") instanceof List<?>;
    }

    private boolean isValidCatalogEntry(ProviderRuntime runtime, Object modelEntry) {
        if (modelEntry instanceof String id) {
            return StringUtils.isNotBlank(id);
        }
        Map<String, Object> model = objectMap(modelEntry);
        if (model == null || !hasValidModelId(model)) {
            return false;
        }
        if (!isCopilotProvider(runtime)) {
            return true;
        }

        Object modelPickerEnabled = model.get("model_picker_enabled");
        if (model.containsKey("model_picker_enabled") && !(modelPickerEnabled instanceof Boolean)) {
            return false;
        }
        Object endpointsValue = model.get("supported_endpoints");
        return !model.containsKey("supported_endpoints") || endpointsValue instanceof List<?> endpoints
                && endpoints.stream().allMatch(endpoint -> endpoint instanceof String text && StringUtils.isNotBlank(text));
    }

    private boolean hasValidModelId(Map<String, Object> model) {
        if (model.containsKey("id") && model.get("id") != null) {
            if (!(model.get("id") instanceof String id)) {
                return false;
            }
            if (StringUtils.isNotBlank(id)) {
                return true;
            }
        }
        return model.get("name") instanceof String name && StringUtils.isNotBlank(name);
    }

    private List<Object> extractModelEntries(Map<String, Object> root) {
        Object data = root.get("data");
        if (data instanceof List<?> entries) {
            return new java.util.ArrayList<>(entries);
        }
        Object models = root.get("models");
        return models instanceof List<?> entries ? new java.util.ArrayList<>(entries) : emptyList();
    }

    private List<Object> applyCopilotModelPickerFilter(ProviderRuntime runtime, List<Object> modelEntries) {
        if (!isCopilotProvider(runtime) || modelEntries.isEmpty()) {
            return modelEntries;
        }

        boolean hasModelPickerField = modelEntries.stream()
                .map(OpenAiModelCatalogClient::objectMap)
                .filter(java.util.Objects::nonNull)
                .anyMatch(model -> model.containsKey("model_picker_enabled"));
        if (!hasModelPickerField) {
            return modelEntries;
        }

        List<Object> pickerEnabled = modelEntries.stream()
                .filter(entry -> {
                    Map<String, Object> model = objectMap(entry);
                    return model != null && Boolean.TRUE.equals(model.get("model_picker_enabled"));
                })
                .toList();

        return pickerEnabled.size() >= 2 || pickerEnabled.size() == modelEntries.size() ? pickerEnabled : modelEntries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private record CopilotExchangedTokenSnapshot(String exchangedToken, long expiresAtEpochMs) {

        @Override
        public String toString() {
            return "CopilotExchangedTokenSnapshot[exchangedToken=<masked>, expiresAtEpochMs=%d]".formatted(expiresAtEpochMs);
        }
    }

    private record CatalogFetchResult(
            List<String> modelIds,
            List<CopilotModelMetadataStore.ModelMetadata> metadata,
            boolean authoritative
    ) {

        private static CatalogFetchResult empty() {
            return new CatalogFetchResult(emptyList(), emptyList(), false);
        }

        private static CatalogFetchResult successfulEmpty() {
            return new CatalogFetchResult(emptyList(), emptyList(), true);
        }
    }
}
