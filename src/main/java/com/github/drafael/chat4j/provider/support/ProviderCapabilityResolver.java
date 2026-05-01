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

import static java.util.stream.Collectors.toSet;
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
    private static final Set<String> PERPLEXITY_PROVIDER_HINTS = Set.of("perplexity");
    private static final Set<String> DEEPSEEK_PROVIDER_HINTS = Set.of("deepseek");
    private static final Set<String> DEEPSEEK_REASONING_MODEL_ALLOW_HINTS = Set.of(
            "deepseek-v4",
            "deepseek-reasoner",
            "deepseek-r1"
    );
    private static final Set<String> DEEPSEEK_TOOL_MODEL_ALLOW_HINTS = Set.of(
            "deepseek-v4",
            "deepseek-chat",
            "deepseek-reasoner"
    );
    private static final Set<String> ANTHROPIC_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("anthropic");
    private static final Set<String> OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("openai");
    private static final Set<String> GOOGLE_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("google ai", "google", "gemini");
    private static final Set<String> OPENROUTER_PROVIDER_HINTS = Set.of("openrouter");
    private static final Set<String> NATIVE_WEB_SEARCH_MODEL_DENY_HINTS = Set.of(
            "embedding",
            "moderation",
            "whisper",
            "transcribe",
            "tts",
            "speech",
            "image"
    );
    private static final Set<String> ANTHROPIC_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of("claude");
    private static final Set<String> OPENAI_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of(
            "search-preview",
            "gpt-4o-search",
            "gpt-4.1",
            "gpt-5",
            "o3",
            "o4"
    );
    private static final Set<String> OPENROUTER_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of(":online");
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
    private static final Set<String> TOOL_PROVIDER_HINTS = Set.of(
            "anthropic",
            "openai",
            "openrouter",
            "google ai",
            "google",
            "gemini",
            "groq",
            "mistral",
            "xai",
            "deepseek",
            "copilot"
    );
    private static final Set<String> TOOL_MODEL_ALLOW_HINTS = Set.of(
            "gpt-4",
            "gpt-5",
            "o1",
            "o3",
            "o4",
            "claude",
            "sonnet",
            "opus",
            "haiku",
            "gemini",
            "grok",
            "deepseek-chat",
            "codex",
            "mistral",
            "ministral",
            "devstral"
    );
    private static final Set<String> TOOL_MODEL_DENY_HINTS = Set.of(
            "embedding",
            "whisper",
            "moderation",
            "transcribe",
            "tts",
            "speech",
            "image",
            "vision"
    );
    private static final Set<String> DYNAMIC_TOOL_HINTS = Set.of(
            "tool",
            "tool_use",
            "tool-use",
            "function",
            "function_calling",
            "function-calling",
            "tool_choice",
            "tool-choice",
            "parallel_tool_calls",
            "parallel-tool-calls",
            "computer_use",
            "computer-use",
            "web_search",
            "web-search"
    );
    private static final Set<String> DYNAMIC_NON_TOOL_HINTS = Set.of(
            "no-tools",
            "no_tools",
            "without-tools",
            "without_tools",
            "tool-disabled",
            "tools-disabled",
            "none"
    );
    private static final Set<String> DYNAMIC_NATIVE_WEB_SEARCH_HINTS = Set.of(
            "web_search",
            "web-search",
            "web search",
            "web_browsing",
            "web-browsing",
            "web browsing",
            "browser_search",
            "browser-search",
            "browsing",
            "grounding",
            "google_search",
            "google-search",
            "google search"
    );
    private static final Set<String> DYNAMIC_NON_NATIVE_WEB_SEARCH_HINTS = Set.of(
            "no-web-search",
            "no_web_search",
            "without-web-search",
            "without_web_search",
            "web-search-disabled",
            "web_search_disabled",
            "search-disabled",
            "search_disabled",
            "none"
    );
    private static final Set<String> NATIVE_WEB_SEARCH_BOOLEAN_FIELDS = Set.of(
            "web_search",
            "webSearch",
            "supports_web_search",
            "supportsWebSearch",
            "native_web_search",
            "nativeWebSearch",
            "supports_native_web_search",
            "supportsNativeWebSearch",
            "web_browsing",
            "webBrowsing",
            "supports_web_browsing",
            "supportsWebBrowsing",
            "grounding",
            "supports_grounding",
            "supportsGrounding",
            "google_search",
            "googleSearch",
            "supports_google_search",
            "supportsGoogleSearch"
    );
    private static final Set<String> TOOL_BOOLEAN_FIELDS = Set.of(
            "tools",
            "supports_tools",
            "supportsTools",
            "tool_use",
            "toolUse",
            "supports_tool_use",
            "supportsToolUse",
            "function_calling",
            "functionCalling",
            "supports_function_calling",
            "supportsFunctionCalling",
            "tool_calling",
            "toolCalling",
            "supports_tool_calling",
            "supportsToolCalling"
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
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_TOOL_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE = new ConcurrentHashMap<>();

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

        if (containsAny(provider, PERPLEXITY_PROVIDER_HINTS)) {
            return PerplexityModelIds.isReasoningSonarModel(modelId);
        }

        Optional<Boolean> dynamicallyResolvedSupport = resolveDynamicReasoningSupport(provider, modelId, baseUrl, apiKey);
        if (dynamicallyResolvedSupport.isPresent()) {
            return dynamicallyResolvedSupport.get();
        }

        if (containsAny(provider, DEEPSEEK_PROVIDER_HINTS)) {
            return supportsDeepSeekReasoning(model);
        }

        if (OPENROUTER_PROVIDER_HINTS.contains(provider) && PerplexityModelIds.isNamespacedReasoningSonarModel(modelId)) {
            return true;
        }

        if (!containsAny(provider, REASONING_PROVIDER_HINTS)) {
            return false;
        }

        return !model.isBlank() && containsAny(model, REASONING_MODEL_ALLOW_HINTS);
    }

    public static boolean supportsToolInvocation(ProviderCapabilities capabilities, String providerName, String modelId) {
        return supportsToolInvocation(capabilities, providerName, modelId, null, null);
    }

    public static boolean supportsToolInvocation(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl
    ) {
        return supportsToolInvocation(capabilities, providerName, modelId, baseUrl, null);
    }

    public static boolean supportsToolInvocation(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        String provider = normalize(providerName);
        String model = normalize(modelId);

        if (containsAny(model, TOOL_MODEL_DENY_HINTS)) {
            return false;
        }

        Optional<Boolean> dynamicallyResolvedSupport = resolveDynamicToolSupport(provider, modelId, baseUrl, apiKey);
        if (dynamicallyResolvedSupport.isPresent()) {
            return dynamicallyResolvedSupport.get();
        }

        if (containsAny(provider, OLLAMA_PROVIDER_HINTS) || containsAny(provider, LM_STUDIO_PROVIDER_HINTS)) {
            return !model.isBlank();
        }

        if (containsAny(provider, DEEPSEEK_PROVIDER_HINTS)) {
            return supportsDeepSeekToolInvocation(model);
        }

        if (!containsAny(provider, TOOL_PROVIDER_HINTS)) {
            return false;
        }

        return !model.isBlank() && containsAny(model, TOOL_MODEL_ALLOW_HINTS);
    }

    public static boolean supportsNativeWebSearch(ProviderCapabilities capabilities, String providerName, String modelId) {
        return supportsNativeWebSearch(capabilities, providerName, modelId, null, null);
    }

    public static boolean supportsNativeWebSearch(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl
    ) {
        return supportsNativeWebSearch(capabilities, providerName, modelId, baseUrl, null);
    }

    public static boolean supportsNativeWebSearch(
            ProviderCapabilities capabilities,
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        String provider = normalize(providerName);
        String model = normalize(modelId);
        if (model.isBlank() || containsAny(model, NATIVE_WEB_SEARCH_MODEL_DENY_HINTS)) {
            return false;
        }

        if (containsAny(provider, PERPLEXITY_PROVIDER_HINTS)) {
            return PerplexityModelIds.isSonarModel(modelId);
        }

        if (capabilities != null && capabilities.supportsNativeWebSearch()) {
            return true;
        }

        Optional<Boolean> dynamicallyResolvedSupport = resolveDynamicNativeWebSearchSupport(
                provider,
                modelId,
                baseUrl,
                apiKey
        );
        if (dynamicallyResolvedSupport.isPresent()) {
            return dynamicallyResolvedSupport.get();
        }

        if (ANTHROPIC_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)) {
            return containsAny(model, ANTHROPIC_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS);
        }

        if (OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)) {
            return containsAny(model, OPENAI_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS);
        }

        if (containsAny(provider, GOOGLE_NATIVE_WEB_SEARCH_PROVIDER_HINTS)) {
            return false;
        }

        if (OPENROUTER_PROVIDER_HINTS.contains(provider)) {
            return supportsOpenRouterNativeWebSearch(model);
        }

        return false;
    }

    public static boolean supportsFileInput(ProviderCapabilities capabilities, String providerName, String modelId) {
        if (capabilities != null && capabilities.supportsFileInput()) {
            return true;
        }

        return false;
    }

    private static boolean supportsOpenRouterNativeWebSearch(String model) {
        return containsAny(model, OPENROUTER_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS)
                || PerplexityModelIds.isNamespacedSonarModel(model);
    }

    private static boolean supportsDeepSeekReasoning(String model) {
        return StringUtils.isNotBlank(model) && containsAny(model, DEEPSEEK_REASONING_MODEL_ALLOW_HINTS);
    }

    private static boolean supportsDeepSeekToolInvocation(String model) {
        return StringUtils.isNotBlank(model) && containsAny(model, DEEPSEEK_TOOL_MODEL_ALLOW_HINTS);
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

    private static Optional<Boolean> resolveDynamicToolSupport(
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

        Boolean cachedSupport = DYNAMIC_TOOL_SUPPORT_CACHE.get(key);
        if (cachedSupport != null) {
            return Optional.of(cachedSupport);
        }

        Optional<Boolean> resolvedSupport;
        if (containsAny(provider, OLLAMA_PROVIDER_HINTS)) {
            resolvedSupport = probeOllamaToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeLmStudioToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey))
                    .or(() -> probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, LM_STUDIO_PROVIDER_HINTS)) {
            resolvedSupport = probeLmStudioToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            resolvedSupport = probeGoogleAiToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else {
            resolvedSupport = probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);
        }

        resolvedSupport.ifPresent(value -> DYNAMIC_TOOL_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    private static Optional<Boolean> resolveDynamicNativeWebSearchSupport(
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

        Boolean cachedSupport = DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE.get(key);
        if (cachedSupport != null) {
            return Optional.of(cachedSupport);
        }

        Optional<Boolean> resolvedSupport = containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)
                ? probeGoogleAiNativeWebSearchSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                        .or(() -> probeModelCatalogNativeWebSearchSupport(
                                normalizedBaseUrl,
                                resolvedModelId,
                                provider,
                                apiKey
                        ))
                : probeModelCatalogNativeWebSearchSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);

        resolvedSupport.ifPresent(value -> DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    private static Optional<Boolean> probeModelCatalogToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityResolver::resolveToolSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> resolveToolSupportFromModelsList(root, modelId));
    }

    private static Optional<Boolean> probeModelCatalogNativeWebSearchSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        Optional<Boolean> fromModelEndpoint = fetchJson(
                modelEndpoint(normalizedBaseUrl, modelId),
                provider,
                apiKey
        ).flatMap(ProviderCapabilityResolver::resolveNativeWebSearchSupportFromNode);

        if (fromModelEndpoint.isPresent()) {
            return fromModelEndpoint;
        }

        return fetchJson(modelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> resolveNativeWebSearchSupportFromModelsList(root, modelId));
    }

    private static Optional<Boolean> probeLmStudioToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return fetchJson(lmStudioModelsEndpoint(normalizedBaseUrl), provider, apiKey)
                .flatMap(root -> resolveLmStudioModelNode(root, modelId))
                .flatMap(ProviderCapabilityResolver::resolveToolSupportFromNode);
    }

    private static Optional<Boolean> probeGoogleAiToolSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = resolveToolSupportFromNode(modelNode);
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

    private static Optional<Boolean> probeGoogleAiNativeWebSearchSupport(
            String normalizedBaseUrl,
            String modelId,
            String provider,
            String apiKey
    ) {
        return resolveGoogleAiModelNode(normalizedBaseUrl, modelId, provider, apiKey)
                .flatMap(modelNode -> {
                    Optional<Boolean> resolved = resolveNativeWebSearchSupportFromNode(modelNode);
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

    private static Optional<Boolean> probeOllamaToolSupport(
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

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = JSON.readTree(response.body());
            return resolveToolSupportFromNode(root);
        } catch (Exception e) {
            return Optional.empty();
        }
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
                googleModelEndpoint(normalizedBaseUrl, canonicalModelId),
                provider,
                apiKey
        );
        if (direct.isPresent()) {
            JsonNode node = direct.get();
            if (modelMatches(node, normalizedModelId) || modelMatches(node, normalize(canonicalModelId))) {
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
            String requestJson = JSON.writeValueAsString(Map.of("name", modelId));
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
            String requestJson = JSON.writeValueAsString(Map.of("name", modelId));
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

    private static Optional<Boolean> resolveToolSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityResolver::resolveToolSupportFromNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityResolver::resolveNativeWebSearchSupportFromNode)
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

    private static Optional<Boolean> resolveToolSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveToolSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveToolSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveToolSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveToolSupportFromSingleNode(node.path("architecture"));
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveNativeWebSearchSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveNativeWebSearchSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveNativeWebSearchSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveNativeWebSearchSupportFromSingleNode(node.path("architecture"));
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

    private static Optional<Boolean> resolveToolSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveToolSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveToolSupportFromCapabilitiesField(node.path("capabilities"));
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> directFieldsResolution = resolveToolSupportFromKnownFields(node);
        if (directFieldsResolution.isPresent()) {
            return directFieldsResolution;
        }

        Optional<Boolean> supportedParametersResolution = resolveToolSupportFromSupportedParameters(
                node.path("supported_parameters"));
        if (supportedParametersResolution.isPresent()) {
            return supportedParametersResolution;
        }

        Optional<Boolean> supportedParametersCamelResolution = resolveToolSupportFromSupportedParameters(
                node.path("supportedParameters"));
        if (supportedParametersCamelResolution.isPresent()) {
            return supportedParametersCamelResolution;
        }

        Optional<Boolean> tagsResolution = resolveToolSupportFromStringArray(node.path("tags"));
        if (tagsResolution.isPresent()) {
            return tagsResolution;
        }

        Optional<Boolean> featuresResolution = resolveToolSupportFromStringArray(node.path("features"));
        if (featuresResolution.isPresent()) {
            return featuresResolution;
        }

        return resolveToolSupportFromSingleNode(node.path("architecture"));
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveNativeWebSearchSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveNativeWebSearchSupportFromCapabilitiesField(
                node.path("capabilities")
        );
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> directFieldsResolution = resolveNativeWebSearchSupportFromKnownFields(node);
        if (directFieldsResolution.isPresent()) {
            return directFieldsResolution;
        }

        Optional<Boolean> supportedParametersResolution = resolveNativeWebSearchSupportFromSupportedParameters(
                node.path("supported_parameters")
        );
        if (supportedParametersResolution.isPresent()) {
            return supportedParametersResolution;
        }

        Optional<Boolean> supportedParametersCamelResolution = resolveNativeWebSearchSupportFromSupportedParameters(
                node.path("supportedParameters")
        );
        if (supportedParametersCamelResolution.isPresent()) {
            return supportedParametersCamelResolution;
        }

        Optional<Boolean> tagsResolution = resolveNativeWebSearchSupportFromStringArray(node.path("tags"));
        if (tagsResolution.isPresent()) {
            return tagsResolution;
        }

        Optional<Boolean> featuresResolution = resolveNativeWebSearchSupportFromStringArray(node.path("features"));
        if (featuresResolution.isPresent()) {
            return featuresResolution;
        }

        return resolveNativeWebSearchSupportFromSingleNode(node.path("architecture"));
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

    private static Optional<Boolean> resolveToolSupportFromBooleanFields(JsonNode node) {
        boolean hasToolField = TOOL_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .anyMatch(Optional::isPresent);
        if (!hasToolField) {
            return Optional.empty();
        }

        boolean supported = TOOL_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce((left, right) -> left || right)
                .orElse(false);

        return Optional.of(supported);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromBooleanFields(JsonNode node) {
        boolean hasNativeWebSearchField = NATIVE_WEB_SEARCH_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .anyMatch(Optional::isPresent);
        if (!hasNativeWebSearchField) {
            return Optional.empty();
        }

        boolean supported = NATIVE_WEB_SEARCH_BOOLEAN_FIELDS.stream()
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

    private static Optional<Boolean> resolveToolSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveToolSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> booleanResolution = resolveToolSupportFromBooleanFields(capabilitiesNode);
            if (booleanResolution.isPresent()) {
                return booleanResolution;
            }

            Optional<Boolean> directFieldsResolution = resolveToolSupportFromKnownFields(capabilitiesNode);
            if (directFieldsResolution.isPresent()) {
                return directFieldsResolution;
            }

            Optional<Boolean> tagsResolution = resolveToolSupportFromStringArray(capabilitiesNode.path("tags"));
            if (tagsResolution.isPresent()) {
                return tagsResolution;
            }

            Optional<Boolean> featuresResolution = resolveToolSupportFromStringArray(capabilitiesNode.path("features"));
            if (featuresResolution.isPresent()) {
                return featuresResolution;
            }

            Optional<Boolean> supportedParametersResolution = resolveToolSupportFromSupportedParameters(
                    capabilitiesNode.path("supported_parameters"));
            if (supportedParametersResolution.isPresent()) {
                return supportedParametersResolution;
            }

            return resolveToolSupportFromSupportedParameters(capabilitiesNode.path("supportedParameters"));
        }

        return resolveToolSupportFromFieldValue(capabilitiesNode);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveNativeWebSearchSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> booleanResolution = resolveNativeWebSearchSupportFromBooleanFields(capabilitiesNode);
            if (booleanResolution.isPresent()) {
                return booleanResolution;
            }

            Optional<Boolean> directFieldsResolution = resolveNativeWebSearchSupportFromKnownFields(capabilitiesNode);
            if (directFieldsResolution.isPresent()) {
                return directFieldsResolution;
            }

            Optional<Boolean> tagsResolution = resolveNativeWebSearchSupportFromStringArray(capabilitiesNode.path("tags"));
            if (tagsResolution.isPresent()) {
                return tagsResolution;
            }

            Optional<Boolean> featuresResolution = resolveNativeWebSearchSupportFromStringArray(capabilitiesNode.path("features"));
            if (featuresResolution.isPresent()) {
                return featuresResolution;
            }

            Optional<Boolean> supportedParametersResolution = resolveNativeWebSearchSupportFromSupportedParameters(
                    capabilitiesNode.path("supported_parameters")
            );
            if (supportedParametersResolution.isPresent()) {
                return supportedParametersResolution;
            }

            return resolveNativeWebSearchSupportFromSupportedParameters(capabilitiesNode.path("supportedParameters"));
        }

        return resolveNativeWebSearchSupportFromFieldValue(capabilitiesNode);
    }

    private static Optional<Boolean> resolveImageSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_IMAGE_HINTS, DYNAMIC_TEXT_ONLY_HINTS);
    }

    private static Optional<Boolean> resolveReasoningSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_REASONING_HINTS, DYNAMIC_NON_REASONING_HINTS);
    }

    private static Optional<Boolean> resolveToolSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_TOOL_HINTS, DYNAMIC_NON_TOOL_HINTS);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(
                arrayNode,
                DYNAMIC_NATIVE_WEB_SEARCH_HINTS,
                DYNAMIC_NON_NATIVE_WEB_SEARCH_HINTS
        );
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

    private static Optional<Boolean> resolveToolSupportFromSupportedParameters(JsonNode parametersNode) {
        return resolveToolSupportFromStringArray(parametersNode);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromSupportedParameters(JsonNode parametersNode) {
        return resolveNativeWebSearchSupportFromStringArray(parametersNode);
    }

    private static Optional<Boolean> resolveToolSupportFromKnownFields(JsonNode node) {
        return StreamSupport.stream(List.of(
                        node.path("tools"),
                        node.path("tool_use"),
                        node.path("toolUse"),
                        node.path("function_calling"),
                        node.path("functionCalling"),
                        node.path("tool_calling"),
                        node.path("toolCalling")
                ).spliterator(), false)
                .map(ProviderCapabilityResolver::resolveToolSupportFromFieldValue)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromKnownFields(JsonNode node) {
        return StreamSupport.stream(List.of(
                        node.path("web_search"),
                        node.path("webSearch"),
                        node.path("native_web_search"),
                        node.path("nativeWebSearch"),
                        node.path("web_browsing"),
                        node.path("webBrowsing"),
                        node.path("grounding"),
                        node.path("google_search"),
                        node.path("googleSearch")
                ).spliterator(), false)
                .map(ProviderCapabilityResolver::resolveNativeWebSearchSupportFromFieldValue)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Boolean> resolveToolSupportFromFieldValue(JsonNode fieldNode) {
        if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull()) {
            return Optional.empty();
        }

        if (fieldNode.isBoolean()) {
            return Optional.of(fieldNode.asBoolean());
        }

        if (fieldNode.isTextual()) {
            String normalized = normalize(fieldNode.asText(""));
            if (normalized.isBlank()) {
                return Optional.empty();
            }

            if (containsAny(normalized, DYNAMIC_TOOL_HINTS)
                    || "enabled".equals(normalized)
                    || "supported".equals(normalized)
                    || "required".equals(normalized)
            ) {
                return Optional.of(true);
            }

            if (containsAny(normalized, DYNAMIC_NON_TOOL_HINTS)
                    || "disabled".equals(normalized)
                    || "unsupported".equals(normalized)
            ) {
                return Optional.of(false);
            }

            return Optional.empty();
        }

        if (fieldNode.isArray()) {
            if (fieldNode.isEmpty()) {
                return Optional.of(false);
            }

            Optional<Boolean> textualResolution = resolveToolSupportFromStringArray(fieldNode);
            if (textualResolution.isPresent()) {
                return textualResolution;
            }

            return Optional.of(true);
        }

        if (fieldNode.isObject()) {
            Optional<Boolean> enabledResolution = booleanField(fieldNode, "enabled");
            if (enabledResolution.isPresent()) {
                return enabledResolution;
            }

            Optional<Boolean> supportedResolution = booleanField(fieldNode, "supported");
            if (supportedResolution.isPresent()) {
                return supportedResolution;
            }

            Optional<Boolean> allowResolution = booleanField(fieldNode, "allow");
            if (allowResolution.isPresent()) {
                return allowResolution;
            }

            return fieldNode.fieldNames().hasNext()
                    ? Optional.of(true)
                    : Optional.of(false);
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromFieldValue(JsonNode fieldNode) {
        if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull()) {
            return Optional.empty();
        }

        if (fieldNode.isBoolean()) {
            return Optional.of(fieldNode.asBoolean());
        }

        if (fieldNode.isTextual()) {
            String normalized = normalize(fieldNode.asText(""));
            if (normalized.isBlank()) {
                return Optional.empty();
            }

            if (containsAny(normalized, DYNAMIC_NATIVE_WEB_SEARCH_HINTS)
                    || "enabled".equals(normalized)
                    || "supported".equals(normalized)
                    || "required".equals(normalized)
            ) {
                return Optional.of(true);
            }

            if (containsAny(normalized, DYNAMIC_NON_NATIVE_WEB_SEARCH_HINTS)
                    || "disabled".equals(normalized)
                    || "unsupported".equals(normalized)
            ) {
                return Optional.of(false);
            }

            return Optional.empty();
        }

        if (fieldNode.isArray()) {
            if (fieldNode.isEmpty()) {
                return Optional.of(false);
            }

            Optional<Boolean> textualResolution = resolveNativeWebSearchSupportFromStringArray(fieldNode);
            if (textualResolution.isPresent()) {
                return textualResolution;
            }

            return Optional.of(true);
        }

        if (fieldNode.isObject()) {
            Optional<Boolean> enabledResolution = booleanField(fieldNode, "enabled");
            if (enabledResolution.isPresent()) {
                return enabledResolution;
            }

            Optional<Boolean> supportedResolution = booleanField(fieldNode, "supported");
            if (supportedResolution.isPresent()) {
                return supportedResolution;
            }

            Optional<Boolean> allowResolution = booleanField(fieldNode, "allow");
            if (allowResolution.isPresent()) {
                return allowResolution;
            }

            return fieldNode.fieldNames().hasNext()
                    ? Optional.of(true)
                    : Optional.of(false);
        }

        return Optional.empty();
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
                .collect(toSet());

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
