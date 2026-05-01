package com.github.drafael.chat4j.provider.capability.models.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;

import static java.util.Collections.emptyList;

@Slf4j
public class PerplexityModelCatalogClient implements ModelCatalogClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final List<String> FALLBACK_MODELS = List.of("sonar", "sonar-pro");

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
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
                return FALLBACK_MODELS;
            }

            List<String> models = parseModels(response.body());
            return models.isEmpty() ? FALLBACK_MODELS : models;
        } catch (Exception e) {
            log.debug("Perplexity model listing failed: {}", ExceptionUtils.getMessage(e));
            return FALLBACK_MODELS;
        }
    }

    private List<String> parseModels(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return emptyList();
        }

        List<String> modelIds = StreamSupport.stream(data.spliterator(), false)
                .map(this::normalizePerplexityModelId)
                .filter(StringUtils::isNotBlank)
                .toList();
        return ModelOrdering.sanitizeAndSortByProvider("Perplexity", modelIds);
    }

    private String normalizePerplexityModelId(JsonNode modelNode) {
        String id = modelNode.path("id").asText("").trim();
        String owner = modelNode.path("owned_by").asText("").trim();
        if (StringUtils.isBlank(id)) {
            return "";
        }

        if (id.startsWith("perplexity/")) {
            return id.substring("perplexity/".length()).trim();
        }

        if (StringUtils.equalsIgnoreCase(owner, "perplexity") && !id.contains("/")) {
            return id;
        }

        if (id.startsWith("sonar")) {
            return id;
        }

        return "";
    }

    private String modelsEndpoint(String baseUrl) {
        String normalizedBaseUrl = StringUtils.defaultIfBlank(baseUrl, "https://api.perplexity.ai").trim();
        normalizedBaseUrl = StringUtils.removeEnd(normalizedBaseUrl, "/");
        return normalizedBaseUrl.endsWith("/v1")
                ? "%s/models".formatted(normalizedBaseUrl)
                : "%s/v1/models".formatted(normalizedBaseUrl);
    }
}
