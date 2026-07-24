package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.github.drafael.chat4j.provider.support.ProviderCapabilityHints.*;

final class DynamicCapabilityResolver {
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHE_ENTRIES = 1_024;
    private static final ConcurrentMap<ModelCapabilityKey, CachedCapability> DYNAMIC_IMAGE_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, CachedCapability> DYNAMIC_REASONING_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, CachedCapability> DYNAMIC_TOOL_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ModelCapabilityKey, CachedCapability> DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE = new ConcurrentHashMap<>();

    private DynamicCapabilityResolver() {
    }

    static Optional<Boolean> resolveDynamicImageSupport(
            String provider,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        ModelCapabilityKey key = capabilityKey(provider, modelId, baseUrl, apiKey);
        if (key == null) {
            return Optional.empty();
        }

        Optional<Boolean> cachedSupport = cached(DYNAMIC_IMAGE_SUPPORT_CACHE, key);
        if (cachedSupport.isPresent()) {
            return cachedSupport;
        }

        String normalizedBaseUrl = key.baseUrl();
        String resolvedModelId = modelId.trim();
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

        resolvedSupport.ifPresent(value -> cache(DYNAMIC_IMAGE_SUPPORT_CACHE, key, value));
        return resolvedSupport;
    }

    static Optional<Boolean> resolveDynamicReasoningSupport(
            String provider,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        ModelCapabilityKey key = capabilityKey(provider, modelId, baseUrl, apiKey);
        if (key == null) {
            return Optional.empty();
        }

        Optional<Boolean> cachedSupport = cached(DYNAMIC_REASONING_SUPPORT_CACHE, key);
        if (cachedSupport.isPresent()) {
            return cachedSupport;
        }

        String normalizedBaseUrl = key.baseUrl();
        String resolvedModelId = modelId.trim();
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

        resolvedSupport.ifPresent(value -> cache(DYNAMIC_REASONING_SUPPORT_CACHE, key, value));
        return resolvedSupport;
    }

    static Optional<Boolean> resolveDynamicToolSupport(
            String provider,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        ModelCapabilityKey key = capabilityKey(provider, modelId, baseUrl, apiKey);
        if (key == null) {
            return Optional.empty();
        }

        Optional<Boolean> cachedSupport = cached(DYNAMIC_TOOL_SUPPORT_CACHE, key);
        if (cachedSupport.isPresent()) {
            return cachedSupport;
        }

        String normalizedBaseUrl = key.baseUrl();
        String resolvedModelId = modelId.trim();
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

        resolvedSupport.ifPresent(value -> cache(DYNAMIC_TOOL_SUPPORT_CACHE, key, value));
        return resolvedSupport;
    }

    static Optional<Boolean> resolveDynamicNativeWebSearchSupport(
            String provider,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        ModelCapabilityKey key = capabilityKey(provider, modelId, baseUrl, apiKey);
        if (key == null) {
            return Optional.empty();
        }

        Optional<Boolean> cachedSupport = cached(DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE, key);
        if (cachedSupport.isPresent()) {
            return cachedSupport;
        }

        String normalizedBaseUrl = key.baseUrl();
        String resolvedModelId = modelId.trim();
        Optional<Boolean> resolvedSupport = containsAny(provider, GOOGLE_AI_PROVIDER_HINTS)
                ? ProviderCapabilityProbes.probeGoogleAiNativeWebSearchSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey)
                        .or(() -> ProviderCapabilityProbes.probeModelCatalogNativeWebSearchSupport(
                                normalizedBaseUrl,
                                resolvedModelId,
                                provider,
                                apiKey
                        ))
                : ProviderCapabilityProbes.probeModelCatalogNativeWebSearchSupport(normalizedBaseUrl, resolvedModelId, provider, apiKey);

        resolvedSupport.ifPresent(value -> cache(DYNAMIC_NATIVE_WEB_SEARCH_SUPPORT_CACHE, key, value));
        return resolvedSupport;
    }

    private static ModelCapabilityKey capabilityKey(String provider, String modelId, String baseUrl, String apiKey) {
        if (StringUtils.isBlank(baseUrl) || StringUtils.isBlank(modelId)) {
            return null;
        }
        return new ModelCapabilityKey(
                normalize(provider),
                ProviderCapabilityProbes.normalizeBaseUrl(baseUrl),
                normalize(modelId),
                authFingerprint(apiKey)
        );
    }

    private static Optional<Boolean> cached(
            ConcurrentMap<ModelCapabilityKey, CachedCapability> cache,
            ModelCapabilityKey key
    ) {
        CachedCapability cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAtNanos() - System.nanoTime() > 0) {
            return Optional.of(cached.value());
        }
        cache.remove(key, cached);
        return Optional.empty();
    }

    private static void cache(
            ConcurrentMap<ModelCapabilityKey, CachedCapability> cache,
            ModelCapabilityKey key,
            boolean value
    ) {
        synchronized (cache) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                removeExpired(cache);
                if (cache.size() >= MAX_CACHE_ENTRIES) {
                    cache.clear();
                }
            }
            cache.put(key, new CachedCapability(value, System.nanoTime() + CACHE_TTL.toNanos()));
        }
    }

    private static void removeExpired(ConcurrentMap<ModelCapabilityKey, CachedCapability> cache) {
        long now = System.nanoTime();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() - now <= 0);
    }

    private static String authFingerprint(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return "none";
        }

        byte[] encoded = apiKey.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(encoded));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private record CachedCapability(boolean value, long expiresAtNanos) {
    }
}
