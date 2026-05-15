package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.github.drafael.chat4j.provider.support.ProviderCapabilityHints.*;

final class DynamicCapabilityResolver {
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_IMAGE_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_REASONING_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_TOOL_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, Boolean> DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE = new ConcurrentHashMap<>();

    private DynamicCapabilityResolver() {
    }

    static Optional<Boolean> resolveDynamicImageSupport(
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

        String normalizedBaseUrl = ProviderCapabilityProbes.normalizeBaseUrl(baseUrl);
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
            resolvedSupport = ProviderCapabilityProbes.probeOllamaImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeLmStudioImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey))
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, LM_STUDIO_PROVIDER_HINTS)) {
            resolvedSupport = ProviderCapabilityProbes.probeLmStudioImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            resolvedSupport = ProviderCapabilityProbes.probeGoogleAiImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else {
            resolvedSupport = ProviderCapabilityProbes.probeModelCatalogImageSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);
        }

        resolvedSupport.ifPresent(value -> DYNAMIC_IMAGE_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    static Optional<Boolean> resolveDynamicReasoningSupport(
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

        String normalizedBaseUrl = ProviderCapabilityProbes.normalizeBaseUrl(baseUrl);
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
            resolvedSupport = ProviderCapabilityProbes.probeOllamaReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeLmStudioReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey))
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, LM_STUDIO_PROVIDER_HINTS)) {
            resolvedSupport = ProviderCapabilityProbes.probeLmStudioReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            resolvedSupport = ProviderCapabilityProbes.probeGoogleAiReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else {
            resolvedSupport = ProviderCapabilityProbes.probeModelCatalogReasoningSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);
        }

        resolvedSupport.ifPresent(value -> DYNAMIC_REASONING_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    static Optional<Boolean> resolveDynamicToolSupport(
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

        String normalizedBaseUrl = ProviderCapabilityProbes.normalizeBaseUrl(baseUrl);
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
            resolvedSupport = ProviderCapabilityProbes.probeOllamaToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeLmStudioToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey))
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, LM_STUDIO_PROVIDER_HINTS)) {
            resolvedSupport = ProviderCapabilityProbes.probeLmStudioToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else if (containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)) {
            resolvedSupport = ProviderCapabilityProbes.probeGoogleAiToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                    .or(() -> ProviderCapabilityProbes.probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey));
        } else {
            resolvedSupport = ProviderCapabilityProbes.probeModelCatalogToolSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);
        }

        resolvedSupport.ifPresent(value -> DYNAMIC_TOOL_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    static Optional<Boolean> resolveDynamicNativeWebSearchSupport(
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

        String normalizedBaseUrl = ProviderCapabilityProbes.normalizeBaseUrl(baseUrl);
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
                ? ProviderCapabilityProbes.probeGoogleAiNativeWebSearchSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                        .or(() -> ProviderCapabilityProbes.probeModelCatalogNativeWebSearchSupport(
                                normalizedBaseUrl,
                                resolvedModelId,
                                provider,
                                apiKey
                        ))
                : ProviderCapabilityProbes.probeModelCatalogNativeWebSearchSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);

        resolvedSupport.ifPresent(value -> DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE.put(key, value));
        return resolvedSupport;
    }

    private static String authFingerprint(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return "none";
        }

        return Integer.toHexString(apiKey.hashCode());
    }

}
