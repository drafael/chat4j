package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.StringUtils;

public final class ProviderCapabilityResolver {

    private static final Set<String> IMAGE_PROVIDER_HINTS = Set.of(
            "anthropic",
            "openai",
            "google ai",
            "openrouter",
            "ollama",
            "lm studio",
            "lmstudio"
    );
    private static final Set<String> OLLAMA_PROVIDER_HINTS = Set.of("ollama");
    private static final Set<String> LM_STUDIO_PROVIDER_HINTS = Set.of("lm studio", "lmstudio");
    private static final Set<String> GOOGLE_AI_PROVIDER_HINTS = Set.of("google ai", "google", "gemini");
    private static final Set<String> REASONING_PROVIDER_HINTS = Set.of(
            "anthropic",
            "openai",
            "google ai",
            "openrouter",
            "groq",
            "deepseek",
            "mistral",
            "xai",
            "ollama",
            "lm studio",
            "lmstudio"
    );
    private static final Set<String> IMAGE_MODEL_ALLOW_HINTS = Set.of(
            "vision",
            "gpt-4o",
            "gpt-4.1",
            "gpt-4.5",
            "gpt-5",
            "gemini",
            "claude",
            "sonnet",
            "opus",
            "haiku",
            "llava",
            "bakllava",
            "moondream",
            "minicpm-v",
            "qwen-vl",
            "qwen2-vl",
            "qwen2.5-vl",
            "pixtral",
            "llama3.2-vision",
            "gemma3",
            "gemma4",
            "gemma-4"
    );
    private static final Set<String> IMAGE_MODEL_DENY_HINTS = Set.of("codex", "whisper", "embedding", "moderation", "tts");
    private static final Set<String> REASONING_MODEL_ALLOW_HINTS = Set.of(
            "reasoning",
            "thinking",
            "think",
            "o1",
            "o3",
            "o4",
            "gpt-5",
            "gpt-oss",
            "r1",
            "qwq",
            "qwen3",
            "deepseek-r1",
            "magistral",
            "grok-3",
            "claude",
            "sonnet",
            "opus",
            "haiku",
            "gemini-2.5",
            "gemini-3",
            "reasoner",
            "deepseek-reasoner"
    );
    private static final Set<String> REASONING_MODEL_DENY_HINTS = Set.of(
            "embedding",
            "moderation",
            "whisper",
            "transcribe",
            "tts",
            "speech",
            "image"
    );
    private static final Set<String> DYNAMIC_IMAGE_HINTS = Set.of("image", "vision", "multimodal", "image_input", "vision_input");
    private static final Set<String> DYNAMIC_TEXT_ONLY_HINTS = Set.of(
            "text-only",
            "text_only",
            "no-image",
            "no_image",
            "without-image"
    );
    private static final Set<String> DYNAMIC_REASONING_HINTS = Set.of(
            "reasoning",
            "thinking",
            "chain_of_thought",
            "cot",
            "reasoning_effort",
            "include_reasoning"
    );
    private static final Set<String> DYNAMIC_NON_REASONING_HINTS = Set.of(
            "no-reasoning",
            "no_reasoning",
            "without-reasoning",
            "non-reasoning",
            "non_reasoning",
            "reasoning-disabled"
    );
    private static final Set<String> CAPABILITY_BOOLEAN_FIELDS = Set.of(
            "vision",
            "supports_vision",
            "supportsVision",
            "image_input",
            "supports_image_input",
            "imageInput",
            "supportsImageInput",
            "multimodal",
            "supports_multimodal",
            "supportsMultimodal"
    );
    private static final Set<String> REASONING_BOOLEAN_FIELDS = Set.of(
            "reasoning",
            "supports_reasoning",
            "supportsReasoning",
            "thinking",
            "supports_thinking",
            "supportsThinking"
    );
    private static final Set<String> OLLAMA_MODELFAMILY_VISION_HINTS = Set.of(
            "vision",
            "mllama",
            "llava",
            "bakllava",
            "gemma3",
            "gemma4",
            "qwen2-vl",
            "qwen2.5-vl"
    );
    private static final Set<String> OLLAMA_MODELINFO_FIELD_VISION_HINTS = Set.of("vision", "clip", "projector");
    private static final Set<String> OLLAMA_MODELINFO_TEXT_VISION_HINTS = Set.of("projector", "vision encoder");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_IMAGE_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_REASONING_SUPPORT_CACHE = new ConcurrentHashMap<>();

    private ProviderCapabilityResolver() {
    }

    public static boolean supportsImageInput(ProviderCapabilities capabilities, String providerName, String modelId) {
        return supportsImageInput(capabilities, providerName, modelId, null, null);
    }

    public static boolean supportsImageInput(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl
    ) {
        return supportsImageInput(capabilities, providerName, modelId, baseUrl, null);
    }

    public static boolean supportsImageInput(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        String provider = normalize(providerName);
        String model = normalize(modelId);

        if (containsAny(model, IMAGE_MODEL_DENY_HINTS)) {
            return false;
        }

        if (capabilities != null && capabilities.supportsImageInput()) {
            return true;
        }

        Optional<Boolean> dynamicallyResolvedSupport = resolveDynamicImageSupport(provider, modelId, baseUrl, apiKey);
        if (dynamicallyResolvedSupport.isPresent()) {
            return dynamicallyResolvedSupport.get();
        }

        boolean providerHinted = containsAny(provider, IMAGE_PROVIDER_HINTS);
        if (!providerHinted) {
            return false;
        }

        return !model.isBlank() && containsAny(model, IMAGE_MODEL_ALLOW_HINTS);
    }

    public static boolean supportsReasoning(ProviderCapabilities capabilities, String providerName, String modelId) {
        return supportsReasoning(capabilities, providerName, modelId, null, null);
    }

    public static boolean supportsReasoning(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl
    ) {
        return supportsReasoning(capabilities, providerName, modelId, baseUrl, null);
    }

    public static boolean supportsReasoning(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        String provider = normalize(providerName);
        String model = normalize(modelId);

        if (containsAny(model, REASONING_MODEL_DENY_HINTS)) {
            return false;
        }

        Optional<Boolean> dynamicallyResolvedSupport = resolveDynamicReasoningSupport(provider, modelId, baseUrl, apiKey);
        if (dynamicallyResolvedSupport.isPresent()) {
            return dynamicallyResolvedSupport.get();
        }

        if (!containsAny(provider, REASONING_PROVIDER_HINTS)) {
            return false;
        }

        return !model.isBlank() && containsAny(model, REASONING_MODEL_ALLOW_HINTS);
    }

    public static boolean supportsFileInput(ProviderCapabilities capabilities, String providerName, String modelId) {
        if (capabilities != null && capabilities.supportsFileInput()) {
            return true;
        }

        return false;
    }

    private static Optional<Boolean> resolveDynamicImageSupport(
            String provider,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        if (StringUtils.isBlank(baseUrl)) {
            return Optional.empty();
        }

        String resolvedModelId = modelId == null ? "" : modelId.trim();
        if (resolvedModelId.isBlank()) {
            return Optional.empty();
        }

        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        ModelCapabilityKey key = new ModelCapabilityKey(
                normalize(provider),
                normalizedBaseUrl,
                normalize(resolvedModelId),
                authFingerprint(apiKey)
        );

        Boolean cachedSupport = DYNAMIC_IMAGE_SUPPORT_CACHE.get(key);
        if (cachedSupport != null) {
            return Optional.of(cachedSupport);
        }

        Optional<Boolean> resolvedSupport;
        if (containsAny(provider, OLLAMA_PROVIDER_HINTS)) {
            resolvedSupport = probeOllamaImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeLmStudioImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey))
                    .or(() -> probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, LM_STUDIO_PROVIDER_HINTS)) {
            resolvedSupport = probeLmStudioImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            resolvedSupport = probeGoogleAiImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else {
            resolvedSupport = probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);
        }

        resolvedSupport.ifPresent(value -> DYNAMIC_IMAGE_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    private static Optional<Boolean> resolveDynamicReasoningSupport(
            String provider,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        if (StringUtils.isBlank(baseUrl)) {
            return Optional.empty();
        }

        String resolvedModelId = modelId == null ? "" : modelId.trim();
        if (resolvedModelId.isBlank()) {
            return Optional.empty();
        }

        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        ModelCapabilityKey key = new ModelCapabilityKey(
                normalize(provider),
                normalizedBaseUrl,
                normalize(resolvedModelId),
                authFingerprint(apiKey)
        );

        Boolean cachedSupport = DYNAMIC_REASONING_SUPPORT_CACHE.get(key);
        if (cachedSupport != null) {
            return Optional.of(cachedSupport);
        }

        Optional<Boolean> resolvedSupport;
        if (containsAny(provider, OLLAMA_PROVIDER_HINTS)) {
            resolvedSupport = probeOllamaReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeLmStudioReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey))
                    .or(() -> probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, LM_STUDIO_PROVIDER_HINTS)) {
            resolvedSupport = probeLmStudioReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            resolvedSupport = probeGoogleAiReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else {
            resolvedSupport = probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);
        }

        resolvedSupport.ifPresent(value -> DYNAMIC_REASONING_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    private static Optional<Boolean> probeModelCatalogImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityResolver::resolveImageSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> resolveImageSupportFromModelsList(root, modelId));
    }

    private static Optional<Boolean> probeModelCatalogReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityResolver::resolveReasoningSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> resolveReasoningSupportFromModelsList(root, modelId));
    }

    private static Optional<Boolean> probeLmStudioImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> resolveLmStudioModelNode(root, modelId))
                .flatMap(modelNode -> {
                    JsonNode capabilitiesNode = modelNode.path("capabilities");
                    if (capabilitiesNode.path("vision").isBoolean()) {
                        return Optional.of(capabilitiesNode.path("vision").asBoolean());
                    }

                    return resolveImageSupportFromNode(modelNode);
                });
    }

    private static Optional<Boolean> probeLmStudioReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> resolveLmStudioModelNode(root, modelId))
                .flatMap(modelNode -> {
                    JsonNode capabilitiesNode = modelNode.path("capabilities");
                    JsonNode reasoningNode = capabilitiesNode.path("reasoning");
                    if (reasoningNode.isObject()) {
                        return Optional.of(true);
                    }
                    if (reasoningNode.isBoolean()) {
                        return Optional.of(reasoningNode.asBoolean());
                    }

                    return resolveReasoningSupportFromNode(modelNode);
                });
    }

    private static Optional<JsonNode> resolveLmStudioModelNode(JsonNode root, String modelId) {
        JsonNode modelsNode = root.path("models");
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .findFirst();
    }

    private static Optional<Boolean> probeGoogleAiImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = resolveImageSupportFromNode(modelNode);
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

    private static Optional<Boolean> probeGoogleAiReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = resolveReasoningSupportFromNode(modelNode);
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
                googleModelEndpoint(normalizedBaseUrl, canonicalModelId, apiKey),
                provider,
                apiKey
        );
        if (direct.isPresent()) {
            JsonNode node = direct.get();
            if (modelMatches(node, normalizedModelId) || modelMatches(node, normalize(canonicalModelId))) {
                return Optional.of(node);
            }
        }

        return fetchJson(googleModelsEndpoint(normalizedBaseUrl, apiKey), provider, apiKey)
                .flatMap(root -> {
                    JsonNode modelsNode = root.path("models");
                    if (!modelsNode.isArray()) {
                        return Optional.empty();
                    }

                    return StreamSupport.stream(modelsNode.spliterator(), false)
                            .filter(modelNode -> modelMatches(modelNode, normalizedModelId)
                                    || modelMatches(modelNode, normalize(canonicalModelId)))
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

    private static Optional<Boolean> probeOllamaImageSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        try {
            String requestJson = JSON.writeValueAsString(Map.of("model", modelId));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaShowEndpoint(normalizedBaseUrl)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            applyAuthHeaders(requestBuilder, provider, apiKey);

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = JSON.readTree(response.body());
            Optional<Boolean> fromCapabilities = resolveImageSupportFromCapabilitiesField(root.path("capabilities"));
            if (fromCapabilities.isPresent()) {
                return fromCapabilities;
            }

            if (containsOllamaVisionSignals(root)) {
                return Optional.of(true);
            }

            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> probeOllamaReasoningSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        try {
            String requestJson = JSON.writeValueAsString(Map.of("model", modelId));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaShowEndpoint(normalizedBaseUrl)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));

            applyAuthHeaders(requestBuilder, provider, apiKey);

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = JSON.readTree(response.body());
            return resolveReasoningSupportFromNode(root);
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

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            return Optional.of(JSON.readTree(response.body()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> resolveImageSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityResolver::resolveImageSupportFromNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Boolean> resolveReasoningSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityResolver::resolveReasoningSupportFromNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Boolean> resolveImageSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveImageSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveImageSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveImageSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveImageSupportFromSingleNode(node.path("architecture"));
    }

    private static Optional<Boolean> resolveReasoningSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveReasoningSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveReasoningSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveReasoningSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveReasoningSupportFromSingleNode(node.path("architecture"));
    }

    private static Optional<Boolean> resolveImageSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveImageSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> modalitiesResolution = resolveImageSupportFromModalities(node);
        if (modalitiesResolution.isPresent()) {
            return modalitiesResolution;
        }

        Optional<Boolean> modalityTextResolution = resolveImageSupportFromModalityText(node.path("modality"));
        if (modalityTextResolution.isPresent()) {
            return modalityTextResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveImageSupportFromCapabilitiesField(node.path("capabilities"));
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> architectureResolution = resolveImageSupportFromSingleNode(node.path("architecture"));
        if (architectureResolution.isPresent()) {
            return architectureResolution;
        }

        if (containsOllamaVisionSignals(node)) {
            return Optional.of(true);
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveReasoningSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveReasoningSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveReasoningSupportFromCapabilitiesField(node.path("capabilities"));
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> supportedParametersResolution = resolveReasoningSupportFromSupportedParameters(
                node.path("supported_parameters"));
        if (supportedParametersResolution.isPresent()) {
            return supportedParametersResolution;
        }

        Optional<Boolean> tagsResolution = resolveReasoningSupportFromStringArray(node.path("tags"));
        if (tagsResolution.isPresent()) {
            return tagsResolution;
        }

        Optional<Boolean> featuresResolution = resolveReasoningSupportFromStringArray(node.path("features"));
        if (featuresResolution.isPresent()) {
            return featuresResolution;
        }

        Optional<Boolean> inputModalitiesResolution = resolveReasoningSupportFromStringArray(node.path("input_modalities"));
        if (inputModalitiesResolution.isPresent()) {
            return inputModalitiesResolution;
        }

        Optional<Boolean> inputModalitiesCamelResolution = resolveReasoningSupportFromStringArray(node.path("inputModalities"));
        if (inputModalitiesCamelResolution.isPresent()) {
            return inputModalitiesCamelResolution;
        }

        Optional<Boolean> modalitiesResolution = resolveReasoningSupportFromStringArray(node.path("modalities"));
        if (modalitiesResolution.isPresent()) {
            return modalitiesResolution;
        }

        Optional<Boolean> generationMethodsResolution = resolveReasoningSupportFromStringArray(
                node.path("supportedGenerationMethods"));
        if (generationMethodsResolution.isPresent()) {
            return generationMethodsResolution;
        }

        Optional<Boolean> architectureResolution = resolveReasoningSupportFromSingleNode(node.path("architecture"));
        if (architectureResolution.isPresent()) {
            return architectureResolution;
        }

        return resolveReasoningSupportFromStringArray(node.path("supported_generation_methods"));
    }

    private static Optional<Boolean> resolveImageSupportFromBooleanFields(JsonNode node) {
        return CAPABILITY_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce((left, right) -> left || right)
                .map(Optional::of)
                .orElse(Optional.empty());
    }

    private static Optional<Boolean> resolveReasoningSupportFromBooleanFields(JsonNode node) {
        boolean hasReasoningField = REASONING_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .anyMatch(Optional::isPresent);
        if (!hasReasoningField) {
            return Optional.empty();
        }

        boolean supported = REASONING_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce((left, right) -> left || right)
                .orElse(false);

        return Optional.of(supported);
    }

    private static Optional<Boolean> resolveImageSupportFromModalities(JsonNode node) {
        Optional<Boolean> inputModalities = resolveImageSupportFromStringArray(node.path("input_modalities"));
        if (inputModalities.isPresent()) {
            return inputModalities;
        }

        Optional<Boolean> inputModalitiesCamel = resolveImageSupportFromStringArray(node.path("inputModalities"));
        if (inputModalitiesCamel.isPresent()) {
            return inputModalitiesCamel;
        }

        Optional<Boolean> supportedInputModalities = resolveImageSupportFromStringArray(node.path("supportedInputModalities"));
        if (supportedInputModalities.isPresent()) {
            return supportedInputModalities;
        }

        Optional<Boolean> supportedInputModalitiesSnake = resolveImageSupportFromStringArray(node.path("supported_input_modalities"));
        if (supportedInputModalitiesSnake.isPresent()) {
            return supportedInputModalitiesSnake;
        }

        Optional<Boolean> modalities = resolveImageSupportFromStringArray(node.path("modalities"));
        if (modalities.isPresent()) {
            return modalities;
        }

        Optional<Boolean> supportedModalities = resolveImageSupportFromStringArray(node.path("supportedModalities"));
        if (supportedModalities.isPresent()) {
            return supportedModalities;
        }

        return resolveImageSupportFromStringArray(node.path("supported_modalities"));
    }

    private static Optional<Boolean> resolveImageSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveImageSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> objectResolution = resolveImageSupportFromSingleNode(capabilitiesNode);
            if (objectResolution.isPresent()) {
                return objectResolution;
            }

            Optional<Boolean> modalitiesResolution = resolveImageSupportFromStringArray(capabilitiesNode.path("modalities"));
            if (modalitiesResolution.isPresent()) {
                return modalitiesResolution;
            }

            return resolveImageSupportFromModalityText(capabilitiesNode.path("modality"));
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveReasoningSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveReasoningSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> booleanResolution = resolveReasoningSupportFromBooleanFields(capabilitiesNode);
            if (booleanResolution.isPresent()) {
                return booleanResolution;
            }

            Optional<Boolean> modalitiesResolution = resolveReasoningSupportFromStringArray(capabilitiesNode.path("modalities"));
            if (modalitiesResolution.isPresent()) {
                return modalitiesResolution;
            }

            Optional<Boolean> tagsResolution = resolveReasoningSupportFromStringArray(capabilitiesNode.path("tags"));
            if (tagsResolution.isPresent()) {
                return tagsResolution;
            }

            return resolveReasoningSupportFromSupportedParameters(capabilitiesNode.path("supported_parameters"));
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveImageSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_IMAGE_HINTS, DYNAMIC_TEXT_ONLY_HINTS);
    }

    private static Optional<Boolean> resolveReasoningSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_REASONING_HINTS, DYNAMIC_NON_REASONING_HINTS);
    }

    private static Optional<Boolean> resolveImageSupportFromModalityText(JsonNode modalityNode) {
        if (!modalityNode.isTextual()) {
            return Optional.empty();
        }

        String modality = normalize(modalityNode.asText(""));
        if (modality.isBlank()) {
            return Optional.empty();
        }

        if (containsAny(modality, DYNAMIC_IMAGE_HINTS)) {
            return Optional.of(true);
        }

        if (containsAny(modality, DYNAMIC_TEXT_ONLY_HINTS)) {
            return Optional.of(false);
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveReasoningSupportFromSupportedParameters(JsonNode parametersNode) {
        return resolveReasoningSupportFromStringArray(parametersNode);
    }

    private static Optional<Boolean> booleanField(JsonNode node, String expectedFieldName) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Optional.empty();
        }

        String normalizedExpectedFieldName = normalizeFieldName(expectedFieldName);
        for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!normalizeFieldName(entry.getKey()).equals(normalizedExpectedFieldName)) {
                continue;
            }

            if (entry.getValue().isBoolean()) {
                return Optional.of(entry.getValue().asBoolean());
            }
        }

        return Optional.empty();
    }

    private static String normalizeFieldName(String fieldName) {
        return normalize(fieldName).replace("_", "").replace("-", "");
    }

    private static Optional<Boolean> resolveSupportFromStringArray(
            JsonNode arrayNode,
            Set<String> positiveHints,
            Set<String> negativeHints
    ) {
        if (!arrayNode.isArray()) {
            return Optional.empty();
        }

        Set<String> values = StreamSupport.stream(arrayNode.spliterator(), false)
                .map(node -> normalize(node.asText("")))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        if (values.isEmpty()) {
            return Optional.empty();
        }

        boolean hasPositiveHint = values.stream().anyMatch(value -> containsAny(value, positiveHints));
        if (hasPositiveHint) {
            return Optional.of(true);
        }

        boolean explicitNegative = values.stream().allMatch(value -> containsAny(value, negativeHints));
        if (explicitNegative) {
            return Optional.of(false);
        }

        return Optional.empty();
    }

    private static boolean containsOllamaVisionSignals(JsonNode root) {
        JsonNode projectorInfo = root.path("projector_info");
        if (projectorInfo.isObject() && projectorInfo.fieldNames().hasNext()) {
            return true;
        }

        String modelfile = normalize(root.path("modelfile").asText(""));
        if (containsAny(modelfile, OLLAMA_MODELINFO_FIELD_VISION_HINTS)) {
            return true;
        }

        JsonNode modelInfo = root.path("model_info");
        if (modelInfo.isObject()) {
            for (var iterator = modelInfo.fields(); iterator.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                if (containsAny(normalize(entry.getKey()), OLLAMA_MODELINFO_FIELD_VISION_HINTS)) {
                    return true;
                }

                if (entry.getValue().isTextual()
                        && containsAny(normalize(entry.getValue().asText("")), OLLAMA_MODELINFO_TEXT_VISION_HINTS)
                ) {
                    return true;
                }
            }
        }

        JsonNode familiesNode = root.path("details").path("families");
        if (familiesNode.isArray()) {
            return StreamSupport.stream(familiesNode.spliterator(), false)
                    .map(node -> normalize(node.asText("")))
                    .anyMatch(family -> containsAny(family, OLLAMA_MODELFAMILY_VISION_HINTS));
        }

        return false;
    }

    private static boolean modelMatches(JsonNode modelNode, String normalizedModelId) {
        if (StringUtils.isBlank(normalizedModelId)) {
            return false;
        }

        List<String> candidates = List.of(
                normalize(modelNode.path("id").asText("")),
                normalize(modelNode.path("name").asText("")),
                normalize(modelNode.path("key").asText("")),
                normalize(modelNode.path("display_name").asText("")),
                normalize(modelNode.path("displayName").asText("")),
                normalize(modelNode.path("canonical_slug").asText("")),
                normalize(modelNode.path("baseModelId").asText("")),
                normalize(modelNode.path("base_model_id").asText(""))
        );

        return candidates.stream()
                .filter(candidate -> !candidate.isBlank())
                .anyMatch(candidate -> candidate.equals(normalizedModelId)
                        || candidate.endsWith("/%s".formatted(normalizedModelId))
                        || normalizedModelId.endsWith("/%s".formatted(candidate)));
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

    private static String normalizeBaseUrl(String baseUrl) {
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

    private static String googleModelsEndpoint(String normalizedBaseUrl, String apiKey) {
        String base = googleApiBase(normalizedBaseUrl);
        String endpoint = "%s/models".formatted(base);
        return appendGoogleApiKey(endpoint, apiKey);
    }

    private static String googleModelEndpoint(String normalizedBaseUrl, String modelId, String apiKey) {
        String base = googleApiBase(normalizedBaseUrl);
        String encodedModelId = URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20");
        String endpoint = "%s/models/%s".formatted(base, encodedModelId);
        return appendGoogleApiKey(endpoint, apiKey);
    }

    private static String googleApiBase(String normalizedBaseUrl) {
        String base = normalizedBaseUrl;
        if (base.endsWith("/openai")) {
            base = base.substring(0, base.length() - "/openai".length());
        }

        return normalizeBaseUrl(base);
    }

    private static String appendGoogleApiKey(String endpoint, String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return endpoint;
        }

        String encodedApiKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8).replace("+", "%20");
        String delimiter = endpoint.contains("?") ? "&" : "?";
        return "%s%skey=%s".formatted(endpoint, delimiter, encodedApiKey);
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

    private static String authFingerprint(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return "none";
        }

        return Integer.toHexString(apiKey.hashCode());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String value, Set<String> hints) {
        return hints.stream().anyMatch(value::contains);
    }

    private record ModelCapabilityKey(String provider, String baseUrl, String modelId, String authFingerprint) {
    }
}
