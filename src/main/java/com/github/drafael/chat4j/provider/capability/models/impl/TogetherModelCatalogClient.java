package com.github.drafael.chat4j.provider.capability.models.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
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
public class TogetherModelCatalogClient implements ModelCatalogClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final HttpClient httpClient;

    public TogetherModelCatalogClient() {
        this(DEFAULT_HTTP_CLIENT);
    }

    TogetherModelCatalogClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        if (Thread.currentThread().isInterrupted() || StringUtils.isBlank(runtime.apiKey())) {
            return emptyList();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsEndpoint(runtime.baseUrl())))
                    .timeout(Duration.ofSeconds(4))
                    .header("Authorization", "Bearer %s".formatted(runtime.apiKey()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug("Together model listing failed with HTTP {}", response.statusCode());
                return emptyList();
            }

            JsonNode root = JSON.readTree(response.body());
            if (root == null || !root.isArray()) {
                log.debug("Together model listing returned a non-array root");
                return emptyList();
            }

            List<String> modelIds = StreamSupport.stream(root.spliterator(), false)
                    .filter(TogetherModelCatalogClient::validChatEntry)
                    .map(model -> model.path("id").asText())
                    .toList();
            List<String> selectable = ModelOrdering.sanitizeAndSortByProvider("Together", modelIds);
            if (root.isEmpty()) {
                log.debug("Together model listing returned an empty catalog");
            } else if (selectable.isEmpty()) {
                log.debug("Together model listing contained no selectable serverless chat models");
            }
            return selectable;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Together model listing interrupted");
            return emptyList();
        } catch (Exception e) {
            String diagnostic = ProviderExceptionMapper.sanitizeMessage(
                    ExceptionUtils.getMessage(e),
                    runtime.apiKey()
            );
            log.debug("Together model listing failed: {}", diagnostic);
            return emptyList();
        }
    }

    private static boolean validChatEntry(JsonNode model) {
        if (model == null || !model.isObject()) {
            return false;
        }
        JsonNode id = model.get("id");
        JsonNode object = model.get("object");
        JsonNode created = model.get("created");
        JsonNode type = model.get("type");
        return id != null
                && id.isTextual()
                && StringUtils.isNotBlank(id.asText())
                && object != null
                && object.isTextual()
                && "model".equals(object.asText())
                && created != null
                && created.isIntegralNumber()
                && type != null
                && type.isTextual()
                && "chat".equals(type.asText());
    }

    private static String modelsEndpoint(String baseUrl) {
        return baseUrl.endsWith("/")
                ? "%smodels".formatted(baseUrl)
                : "%s/models".formatted(baseUrl);
    }
}
