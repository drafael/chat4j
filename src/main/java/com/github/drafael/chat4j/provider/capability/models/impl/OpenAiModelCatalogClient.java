package com.github.drafael.chat4j.provider.capability.models.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import com.github.drafael.chat4j.provider.support.ModelFilters;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.models.Model;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import static java.util.Collections.emptyList;

public class OpenAiModelCatalogClient implements ModelCatalogClient {

    private static final Pattern CODEX_SLUG_PATTERN = Pattern.compile("\\\"slug\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final String COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String COPILOT_TOKEN_ENDPOINT_PROPERTY = "chat4j.copilot.tokenEndpoint";
    private static final String COPILOT_TOKEN_ENDPOINT_DEFAULT = "https://api.github.com/copilot_internal/v2/token";
    private static final Duration COPILOT_EXCHANGE_SUCCESS_TTL = Duration.ofMinutes(10);
    private static final Duration COPILOT_EXCHANGE_FAILURE_TTL = Duration.ofMinutes(2);
    private static final Map<String, CopilotExchangedTokenSnapshot> COPILOT_EXCHANGED_TOKEN_BY_SOURCE = new ConcurrentHashMap<>();

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        if (isCopilotProvider(runtime)) {
            List<String> copilotModels = fetchCopilotModels(runtime);
            if (!copilotModels.isEmpty()) {
                return copilotModels;
            }
        }

        try {
            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .apiKey(runtime.apiKey())
                    .baseUrl(runtime.baseUrl())
                    .build();

            return client.models().list().data().stream()
                    .filter(model -> ModelFilters.isSupportedChatModelId(model.id()))
                    .sorted((left, right) -> {
                        int byRecency = ModelOrdering.compareByRecency(left.id(), right.id());
                        return byRecency != 0
                                ? byRecency
                                : Long.compare(right.created(), left.created());
                    })
                    .map(Model::id)
                    .toList();
        } catch (Exception e) {
            return fallbackModels(runtime);
        }
    }

    private List<String> fallbackModels(ProviderRuntime runtime) {
        List<String> httpFallback = fetchModelsFromHttp(runtime, runtime.apiKey(), false);
        if (!httpFallback.isEmpty()) {
            return httpFallback;
        }

        if (runtime.descriptor().authType() != AuthType.CLI_OAUTH) {
            return emptyList();
        }

        if ("OpenAI Codex".equals(runtime.descriptor().name())) {
            List<String> codexModels = readCodexModelsFromCache();
            if (!codexModels.isEmpty()) {
                return codexModels;
            }
        }

        return emptyList();
    }

    private List<String> fetchCopilotModels(ProviderRuntime runtime) {
        String apiKey = runtime.apiKey();
        if (StringUtils.isBlank(apiKey)) {
            return emptyList();
        }

        List<String> directModels = fetchModelsFromHttp(runtime, apiKey, true);
        if (!directModels.isEmpty() && hasModernGptModels(directModels)) {
            return directModels;
        }

        if (!looksLikeGitHubOAuthToken(apiKey)) {
            return directModels;
        }

        String exchangedToken = exchangeCopilotTokenCached(apiKey);
        if (StringUtils.isBlank(exchangedToken)) {
            return directModels;
        }

        List<String> exchangedModels = fetchModelsFromHttp(runtime, exchangedToken, true);
        return exchangedModels.isEmpty() ? directModels : exchangedModels;
    }

    private List<String> fetchModelsFromHttp(ProviderRuntime runtime, String apiKey, boolean copilotHeadersRequired) {
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

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return emptyList();
            }

            JsonNode root = JSON.readTree(response.body());
            List<JsonNode> modelEntries = extractModelEntries(root);
            if (modelEntries.isEmpty()) {
                return emptyList();
            }

            List<JsonNode> pickerFilteredEntries = applyCopilotModelPickerFilter(runtime, modelEntries);

            List<String> modelIds = pickerFilteredEntries.stream()
                    .filter(modelNode -> supportsConfiguredApiEndpoint(runtime, modelNode))
                    .map(OpenAiModelCatalogClient::modelId)
                    .filter(StringUtils::isNotBlank)
                    .filter(ModelFilters::isSupportedChatModelId)
                    .toList();

            return ModelOrdering.sanitizeAndSortByProvider(runtime.descriptor().name(), modelIds);
        } catch (Exception e) {
            return emptyList();
        }
    }

    private String exchangeCopilotTokenCached(String githubToken) {
        CopilotExchangedTokenSnapshot cached = COPILOT_EXCHANGED_TOKEN_BY_SOURCE.get(githubToken);
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expiresAtEpochMs()) {
            return cached.exchangedToken();
        }

        String exchangedToken = exchangeCopilotToken(githubToken);
        Duration ttl = StringUtils.isBlank(exchangedToken) ? COPILOT_EXCHANGE_FAILURE_TTL : COPILOT_EXCHANGE_SUCCESS_TTL;
        COPILOT_EXCHANGED_TOKEN_BY_SOURCE.put(
                githubToken,
                new CopilotExchangedTokenSnapshot(exchangedToken, now + ttl.toMillis())
        );
        return exchangedToken;
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

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = JSON.readTree(response.body());
            String token = root.path("token").asText("");
            return StringUtils.isBlank(token) ? null : token.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String copilotTokenEndpoint() {
        return StringUtils.defaultIfBlank(System.getProperty(COPILOT_TOKEN_ENDPOINT_PROPERTY), COPILOT_TOKEN_ENDPOINT_DEFAULT);
    }

    private boolean hasModernGptModels(List<String> modelIds) {
        return modelIds.stream().anyMatch(modelId -> modelId.startsWith("gpt-5"));
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
        return "/chat/completions".equals(endpoint)
                || "/responses".equals(endpoint)
                || "ws:/responses".equals(endpoint);
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

    private List<String> readCodexModelsFromCache() {
        try {
            Path modelCache = Path.of(System.getProperty("user.home"), ".codex", "models_cache.json");
            if (!Files.exists(modelCache)) {
                return emptyList();
            }

            String content = Files.readString(modelCache, StandardCharsets.UTF_8);
            Matcher matcher = CODEX_SLUG_PATTERN.matcher(content);
            LinkedHashSet<String> slugs = new LinkedHashSet<>();
            while (matcher.find()) {
                slugs.add(matcher.group(1));
            }

            return ModelOrdering.sanitizeAndSortByRecency(slugs.stream().toList());
        } catch (Exception e) {
            return emptyList();
        }
    }

    private record CopilotExchangedTokenSnapshot(String exchangedToken, long expiresAtEpochMs) {
    }
}
