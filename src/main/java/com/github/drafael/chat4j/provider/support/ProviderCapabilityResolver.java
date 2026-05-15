package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;

import java.util.Optional;

import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicImageSupport;
import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicNativeWebSearchSupport;
import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicReasoningSupport;
import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicToolSupport;
import static com.github.drafael.chat4j.provider.support.ProviderCapabilityHints.*;

public final class ProviderCapabilityResolver {

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

}
