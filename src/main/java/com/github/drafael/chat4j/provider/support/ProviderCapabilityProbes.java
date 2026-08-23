package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import com.github.drafael.chat4j.json.JsonCodec;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.github.drafael.chat4j.provider.support.ProviderCapabilityHints.*;

final class ProviderCapabilityProbes {
    private static final JsonCodec JSON = JsonCodec.standard();
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Duration BODY_READ_TIMEOUT = Duration.ofSeconds(2);
    private static final HttpTransport TRANSPORT = JavaNetHttpTransport.create(Duration.ofMillis(800), RedirectPolicy.NEVER);

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
        ).flatMap(ProviderCapabilityJsonParser::nodeEvidence)
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::tools);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(payload -> ProviderCapabilityJsonParser.modelsEvidence(payload, modelId))
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::tools);
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
        ).flatMap(ProviderCapabilityJsonParser::nodeEvidence)
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::nativeWebSearch);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(payload -> ProviderCapabilityJsonParser.modelsEvidence(payload, modelId))
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::nativeWebSearch);
    }

    static Optional<Boolean> probeLmStudioToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(payload -> ProviderCapabilityJsonParser.lmStudioEvidence(payload, modelId))
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::tools);
    }

    static Optional<Boolean> probeGoogleAiToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelEvidence(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(evidence -> evidence.tools().or(() -> {
                    String description = normalize(evidence.description());
                    return description.contains("tool") || description.contains("function calling")
                            ? Optional.of(true)
                            : Optional.empty();
                }));
    }

    static Optional<Boolean> probeGoogleAiNativeWebSearchSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelEvidence(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::nativeWebSearch);
    }

    static Optional<Boolean> probeOllamaToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        try {
            return postJson(ollamaShowEndpoint(normalizedBaseUrl), modelId, provider, apiKey)
                    .flatMap(ProviderCapabilityJsonParser::nodeEvidence)
                    .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::tools);
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
        ).flatMap(ProviderCapabilityJsonParser::nodeEvidence)
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::image);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(payload -> ProviderCapabilityJsonParser.modelsEvidence(payload, modelId))
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::image);
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
        ).flatMap(ProviderCapabilityJsonParser::nodeEvidence)
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::reasoning);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(payload -> ProviderCapabilityJsonParser.modelsEvidence(payload, modelId))
                .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::reasoning);
    }

    static Optional<Boolean> probeLmStudioImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(payload -> ProviderCapabilityJsonParser.lmStudioEvidence(payload, modelId))
                .flatMap(evidence -> evidence.explicitVision().or(evidence::image));
    }

    static Optional<Boolean> probeLmStudioReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(payload -> ProviderCapabilityJsonParser.lmStudioEvidence(payload, modelId))
                .flatMap(evidence -> evidence.explicitReasoning().or(evidence::reasoning));
    }

    static Optional<Boolean> probeGoogleAiImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelEvidence(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(evidence -> evidence.image().or(() -> {
                    String description = normalize(evidence.description());
                    return description.contains("multimodal") || containsAny(description, DYNAMIC_IMAGE_HINTS)
                            ? Optional.of(true)
                            : Optional.empty();
                }));
    }

    static Optional<Boolean> probeGoogleAiReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelEvidence(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(evidence -> evidence.reasoning().or(() -> {
                    String description = normalize(evidence.description());
                    return description.contains("reason") || description.contains("thinking")
                            ? Optional.of(true)
                            : Optional.empty();
                }));
    }

    private static Optional<ProviderCapabilityJsonParser.CapabilityEvidence> resolveGoogleAiModelEvidence(
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
        String normalizedCanonicalId = normalize(canonicalModelId);
        Optional<ProviderCapabilityJsonParser.CapabilityEvidence> direct = fetchJson(
                googleModelEndpoint(normalizedBaseUrl, canonicalModelId),
                provider,
                apiKey
        ).flatMap(payload -> ProviderCapabilityJsonParser.googleModelEvidence(
                payload,
                normalizedModelId,
                normalizedCanonicalId
        ));
        return direct.isPresent()
                ? direct
                : fetchJson(googleModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                        .flatMap(payload -> ProviderCapabilityJsonParser.googleModelEvidence(
                                payload,
                                normalizedModelId,
                                normalizedCanonicalId
                        ));
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
            return postJson(ollamaShowEndpoint(normalizedBaseUrl), modelId, provider, apiKey)
                    .flatMap(ProviderCapabilityJsonParser::ollamaImageSupport);
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
            return postJson(ollamaShowEndpoint(normalizedBaseUrl), modelId, provider, apiKey)
                    .flatMap(ProviderCapabilityJsonParser::nodeEvidence)
                    .flatMap(ProviderCapabilityJsonParser.CapabilityEvidence::reasoning);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> fetchJson(String endpoint, String provider, String apiKey) {
        try {
            return sendJson(new HttpExchangeRequest(
                    "GET",
                    URI.create(endpoint),
                    authHeaders(provider, apiKey),
                    HttpBody.empty(),
                    BODY_READ_TIMEOUT,
                    MAX_RESPONSE_BYTES
            ));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> postJson(String endpoint, String modelId, String provider, String apiKey) throws Exception {
        Map<String, String> headers = new java.util.LinkedHashMap<>(authHeaders(provider, apiKey));
        headers.put("Content-Type", "application/json");
        return sendJson(new HttpExchangeRequest(
                "POST",
                URI.create(endpoint),
                headers,
                HttpBody.bytes(JSON.writeBytes(Map.of("name", modelId))),
                BODY_READ_TIMEOUT,
                MAX_RESPONSE_BYTES
        ));
    }

    private static Optional<byte[]> sendJson(HttpExchangeRequest request) throws Exception {
        HttpExchangeResponse response = TRANSPORT.send(request, Thread.currentThread()::isInterrupted);
        return response.successful() ? Optional.of(response.body()) : Optional.empty();
    }

    private static Map<String, String> authHeaders(String provider, String apiKey) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (StringUtils.isBlank(apiKey)) {
            return headers;
        }
        if (containsAny(provider, Set.of("anthropic"))) {
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");
        } else if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            headers.put("x-goog-api-key", apiKey);
            headers.put("Authorization", "Bearer %s".formatted(apiKey));
        } else {
            headers.put("Authorization", "Bearer %s".formatted(apiKey));
        }
        return headers;
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
