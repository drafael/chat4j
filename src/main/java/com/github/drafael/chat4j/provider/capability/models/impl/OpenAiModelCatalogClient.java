package com.github.drafael.chat4j.provider.capability.models.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
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

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
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
        List<String> httpFallback = fetchModelsFromHttp(runtime);
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

    private List<String> fetchModelsFromHttp(ProviderRuntime runtime) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(modelsEndpoint(runtime.baseUrl())))
                    .timeout(Duration.ofSeconds(4))
                    .GET();

            if (StringUtils.isNotBlank(runtime.apiKey())) {
                requestBuilder.header("Authorization", "Bearer %s".formatted(runtime.apiKey()));
            }

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return emptyList();
            }

            JsonNode root = JSON.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return emptyList();
            }

            List<String> modelIds = StreamSupport.stream(data.spliterator(), false)
                    .map(OpenAiModelCatalogClient::modelId)
                    .filter(StringUtils::isNotBlank)
                    .filter(ModelFilters::isSupportedChatModelId)
                    .toList();

            return ModelOrdering.sanitizeAndSortByProvider(runtime.descriptor().name(), modelIds);
        } catch (Exception e) {
            return emptyList();
        }
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

        return modelNode.path("id").asText("");
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
}
