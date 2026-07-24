package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

import static com.github.drafael.chat4j.provider.support.ProviderCapabilityHints.*;

final class ProviderCapabilityProbes {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Duration BODY_READ_TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();

    private ProviderCapabilityProbes() {
    }

    static Optional<Boolean> probeModelCatalogToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityJsonParser::resolveToolSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> ProviderCapabilityJsonParser.resolveToolSupportFromModelsList(root, modelId));
    }

    static Optional<Boolean> probeModelCatalogNativeWebSearchSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityJsonParser::resolveNativeWebSearchSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> ProviderCapabilityJsonParser.resolveNativeWebSearchSupportFromModelsList(root, modelId));
    }

    static Optional<Boolean> probeLmStudioToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> ProviderCapabilityJsonParser.resolveLmStudioModelNode(root, modelId))
                .flatMap(ProviderCapabilityJsonParser::resolveToolSupportFromNode);
    }

    static Optional<Boolean> probeGoogleAiToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = ProviderCapabilityJsonParser.resolveToolSupportFromNode(modelNode);
                    if (resolved.isPresent()) {
                        return resolved;
                    }

                    String description = normalize(modelNode.path("description").asText(""));
                    if (description.contains("tool") || description.contains("function calling")) {
                        return Optional.of(true);
                    }

                    return Optional.empty();
                });
    }

    static Optional<Boolean> probeGoogleAiNativeWebSearchSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = ProviderCapabilityJsonParser.resolveNativeWebSearchSupportFromNode(modelNode);
                    if (resolved.isPresent()) {
                        return resolved;
                    }

                    String description = normalize(modelNode.path("description").asText(""));
                    if (containsAny(description, DYNAMIC_NATIVE_WEB_SEARCH_HINTS)) {
                        return Optional.of(true);
                    }

                    return Optional.empty();
                });
    }

    static Optional<Boolean> probeOllamaToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        try {
            String requestJson = JSON.writeValueAsString(Map.of("name", modelId));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaShowEndpoint(normalizedBaseUrl)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            applyAuthHeaders(requestBuilder, provider, apiKey);

            return sendJson(requestBuilder.build())
                    .flatMap(ProviderCapabilityJsonParser::resolveToolSupportFromNode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<Boolean> probeModelCatalogImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityJsonParser::resolveImageSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> ProviderCapabilityJsonParser.resolveImageSupportFromModelsList(root, modelId));
    }

    static Optional<Boolean> probeModelCatalogReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityJsonParser::resolveReasoningSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> ProviderCapabilityJsonParser.resolveReasoningSupportFromModelsList(root, modelId));
    }

    static Optional<Boolean> probeLmStudioImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> ProviderCapabilityJsonParser.resolveLmStudioModelNode(root, modelId))
                .flatMap(modelNode -> {
                    JsonNode capabilitiesNode = modelNode.path("capabilities");
                    if (capabilitiesNode.path("vision").isBoolean()) {
                        return Optional.of(capabilitiesNode.path("vision").asBoolean());
                    }

                    return ProviderCapabilityJsonParser.resolveImageSupportFromNode(modelNode);
                });
    }

    static Optional<Boolean> probeLmStudioReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> ProviderCapabilityJsonParser.resolveLmStudioModelNode(root, modelId))
                .flatMap(modelNode -> {
                    JsonNode capabilitiesNode = modelNode.path("capabilities");
                    JsonNode reasoningNode = capabilitiesNode.path("reasoning");
                    if (reasoningNode.isObject()) {
                        return Optional.of(true);
                    }
                    if (reasoningNode.isBoolean()) {
                        return Optional.of(reasoningNode.asBoolean());
                    }

                    return ProviderCapabilityJsonParser.resolveReasoningSupportFromNode(modelNode);
                });
    }

    static Optional<Boolean> probeGoogleAiImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = ProviderCapabilityJsonParser.resolveImageSupportFromNode(modelNode);
                    if (resolved.isPresent()) {
                        return resolved;
                    }

                    String description = normalize(modelNode.path("description").asText(""));
                    if (description.contains("multimodal") || containsAny(description, DYNAMIC_IMAGE_HINTS)) {
                        return Optional.of(true);
                    }

                    return Optional.empty();
                });
    }

    static Optional<Boolean> probeGoogleAiReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = ProviderCapabilityJsonParser.resolveReasoningSupportFromNode(modelNode);
                    if (resolved.isPresent()) {
                        return resolved;
                    }

                    String description = normalize(modelNode.path("description").asText(""));
                    if (description.contains("reason") || description.contains("thinking")) {
                        return Optional.of(true);
                    }

                    return Optional.empty();
                });
    }

    private static Optional<JsonNode> resolveGoogleAiModelNode(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        String canonicalModelId = canonicalGoogleModelId(modelId);
        if (canonicalModelId.isBlank()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        Optional<JsonNode> direct = fetchJson(
                googleModelEndpoint(normalizedBaseUrl, canonicalModelId),
                provider,
                apiKey
        );
        if (direct.isPresent()) {
            JsonNode node = direct.get();
            if (ProviderCapabilityJsonParser.modelMatches(node, normalizedModelId) || ProviderCapabilityJsonParser.modelMatches(node, normalize(canonicalModelId))) {
                return Optional.of(node);
            }
        }

        return fetchJson(googleModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> {
                    JsonNode modelsNode = root.path("models");
                    if (!modelsNode.isArray()) {
                        return Optional.empty();
                    }

                    return StreamSupport.stream(modelsNode.spliterator(), false)
                            .filter(modelNode -> ProviderCapabilityJsonParser.modelMatches(modelNode, normalizedModelId)
                                    || ProviderCapabilityJsonParser.modelMatches(modelNode, normalize(canonicalModelId)))
                            .findFirst();
                });
    }

    private static String canonicalGoogleModelId(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return "";
        }

        String normalized = modelId.trim();
        if (normalized.startsWith("models/")) {
            normalized = normalized.substring("models/".length());
        }

        if (normalized.contains("/")) {
            normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
        }

        return normalized;
    }

    static Optional<Boolean> probeOllamaImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        try {
            String requestJson = JSON.writeValueAsString(Map.of("name", modelId));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaShowEndpoint(normalizedBaseUrl)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            applyAuthHeaders(requestBuilder, provider, apiKey);

            return sendJson(requestBuilder.build()).flatMap(root -> {
                Optional<Boolean> fromCapabilities = ProviderCapabilityJsonParser.resolveImageSupportFromCapabilitiesField(
                        root.path("capabilities")
                );
                if (fromCapabilities.isPresent()) {
                    return fromCapabilities;
                }
                return ProviderCapabilityJsonParser.containsOllamaVisionSignals(root)
                        ? Optional.of(true)
                        : Optional.empty();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<Boolean> probeOllamaReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        try {
            String requestJson = JSON.writeValueAsString(Map.of("name", modelId));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaShowEndpoint(normalizedBaseUrl)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            applyAuthHeaders(requestBuilder, provider, apiKey);

            return sendJson(requestBuilder.build())
                    .flatMap(ProviderCapabilityJsonParser::resolveReasoningSupportFromNode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<JsonNode> fetchJson(String endpoint, String provider, String apiKey) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(2))
                    .GET();
            applyAuthHeaders(requestBuilder, provider, apiKey);

            return sendJson(requestBuilder.build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }


    private static Optional<JsonNode> sendJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > MAX_RESPONSE_BYTES) {
                return Optional.empty();
            }
            AtomicBoolean finished = new AtomicBoolean();
            Thread readerThread = Thread.currentThread();
            Thread watcher = Thread.startVirtualThread(() -> watchBodyRead(body, readerThread, finished));
            try {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    return Optional.empty();
                }
                return Optional.ofNullable(JSON.readTree(bytes));
            } finally {
                finished.set(true);
                watcher.interrupt();
                join(watcher);
            }
        }
    }

    private static void watchBodyRead(InputStream body, Thread readerThread, AtomicBoolean finished) {
        long deadlineNanos = System.nanoTime() + BODY_READ_TIMEOUT.toNanos();
        while (!finished.get()) {
            if (readerThread.isInterrupted() || System.nanoTime() >= deadlineNanos) {
                try {
                    body.close();
                } catch (IOException ignored) {
                }
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void join(Thread thread) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    restoreInterrupt = true;
                }
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void applyAuthHeaders(HttpRequest.Builder requestBuilder, String provider, String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return;
        }

        if (containsAny(provider, Set.of("anthropic"))) {
            requestBuilder.header("x-api-key", apiKey);
            requestBuilder.header("anthropic-version", "2023-06-01");
            return;
        }

        if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            // Google AI can be accessed through both native Gemini endpoints and OpenAI-compatible paths.
            // Different deployments/proxies may expect either x-goog-api-key or Bearer auth, so we send both
            // to keep dynamic model-capability probing (native + OpenAI fallback) reliable.
            requestBuilder.header("x-goog-api-key", apiKey);
            requestBuilder.header("Authorization", "Bearer %s".formatted(apiKey));
            return;
        }

        requestBuilder.header("Authorization", "Bearer %s".formatted(apiKey));
    }

    static String normalizeBaseUrl(String baseUrl) {
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        return normalizedBaseUrl;
    }

    private static String modelsEndpoint(String normalizedBaseUrl) {
        return normalizedBaseUrl.endsWith("/models")
                ? normalizedBaseUrl
                : "%s/models".formatted(normalizedBaseUrl);
    }

    private static String modelEndpoint(String normalizedBaseUrl, String modelId) {
        String encodedModelId = URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20");
        return "%s/%s".formatted(modelsEndpoint(normalizedBaseUrl), encodedModelId);
    }

    private static String googleModelsEndpoint(String normalizedBaseUrl) {
        String base = googleApiBase(normalizedBaseUrl);
        return "%s/models".formatted(base);
    }

    private static String googleModelEndpoint(String normalizedBaseUrl, String modelId) {
        String base = googleApiBase(normalizedBaseUrl);
        String encodedModelId = URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20");
        return "%s/models/%s".formatted(base, encodedModelId);
    }

    private static String googleApiBase(String normalizedBaseUrl) {
        String base = normalizedBaseUrl;
        if (base.endsWith("/openai")) {
            base = base.substring(0, base.length() - "/openai".length());
        }

        return normalizeBaseUrl(base);
    }

    private static String lmStudioModelsEndpoint(String normalizedBaseUrl) {
        String baseWithoutV1 = normalizedBaseUrl.endsWith("/v1")
                ? normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 3)
                : normalizedBaseUrl;
        String collapsed = normalizeBaseUrl(baseWithoutV1);
        return "%s/api/v1/models".formatted(collapsed);
    }

    private static String ollamaShowEndpoint(String normalizedBaseUrl) {
        String baseWithoutV1 = normalizedBaseUrl.endsWith("/v1")
                ? normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 3)
                : normalizedBaseUrl;
        String collapsed = normalizeBaseUrl(baseWithoutV1);
        return "%s/api/show".formatted(collapsed);
    }

}
