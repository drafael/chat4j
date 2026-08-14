package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import org.apache.commons.lang3.StringUtils;

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

        if (DeepSeekNativeWebSearchSupport.isDeepSeek(providerName)) {
            return false;
        }

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

    public static NativeWebSearchOutcome nativeWebSearchOutcome(
            String providerName,
            String modelId,
            String baseUrl,
            String defaultBaseUrl
    ) {
        return staticNativeWebSearchOutcome(providerName, modelId, baseUrl, defaultBaseUrl);
    }

    public static NativeWebSearchOutcome nativeWebSearchOutcome(
            String providerName,
            String modelId,
            String baseUrl,
            String defaultBaseUrl,
            String apiKey
    ) {
        NativeWebSearchOutcome staticOutcome = staticNativeWebSearchOutcome(
                providerName,
                modelId,
                baseUrl,
                defaultBaseUrl
        );
        if (staticOutcome != NativeWebSearchOutcome.PENDING) {
            return staticOutcome;
        }
        return resolveDynamicNativeWebSearchSupport(providerName, modelId, baseUrl, apiKey)
                .map(supported -> supported ? NativeWebSearchOutcome.OPTIONAL : NativeWebSearchOutcome.UNSUPPORTED)
                .orElse(NativeWebSearchOutcome.PENDING);
    }

    private static NativeWebSearchOutcome staticNativeWebSearchOutcome(
            String providerName,
            String modelId,
            String baseUrl,
            String defaultBaseUrl
    ) {
        String provider = normalize(providerName);
        String model = normalize(modelId);
        if (model.isBlank() || containsAny(model, NATIVE_WEB_SEARCH_MODEL_DENY_HINTS)) {
            return NativeWebSearchOutcome.UNSUPPORTED;
        }
        if (PERPLEXITY_PROVIDER_HINTS.contains(provider)) {
            return PerplexityModelIds.isSonarModel(modelId)
                    ? NativeWebSearchOutcome.REQUIRED
                    : NativeWebSearchOutcome.UNSUPPORTED;
        }
        if (DeepSeekNativeWebSearchSupport.isDeepSeek(providerName)) {
            return DeepSeekNativeWebSearchSupport.supports(providerName, modelId, baseUrl)
                    ? NativeWebSearchOutcome.OPTIONAL
                    : NativeWebSearchOutcome.UNSUPPORTED;
        }
        if (!sameEndpoint(baseUrl, defaultBaseUrl)) {
            return NativeWebSearchOutcome.UNSUPPORTED;
        }
        if (GROQ_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider) && supportsGroqNativeWebSearch(model)
                || OPENROUTER_PROVIDER_HINTS.contains(provider) && supportsOpenRouterNativeWebSearch(model)
                || OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider) && isOpenAiSearchPreviewModel(model)) {
            return NativeWebSearchOutcome.REQUIRED;
        }

        NativeWebSearchOutcome staticOutcome = staticOptionalOutcome(provider, model);
        if (staticOutcome == NativeWebSearchOutcome.OPTIONAL) {
            return staticOutcome;
        }
        return supportsRuntimeDynamicNativeWebSearchProbe(providerName)
                ? NativeWebSearchOutcome.PENDING
                : NativeWebSearchOutcome.UNSUPPORTED;
    }

    private static NativeWebSearchOutcome staticOptionalOutcome(
            String provider,
            String model
    ) {
        if (ANTHROPIC_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && containsAny(model, ANTHROPIC_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS)
                || OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && containsAny(model, OPENAI_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS)
                || XAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && supportsXaiNativeWebSearch(model)
                || GOOGLE_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && containsAny(model, GOOGLE_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS)) {
            return NativeWebSearchOutcome.OPTIONAL;
        }
        return NativeWebSearchOutcome.UNSUPPORTED;
    }

    private static boolean supportsRuntimeDynamicNativeWebSearchProbe(String providerName) {
        String provider = normalize(providerName);
        return OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                || GOOGLE_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider);
    }

    private static boolean sameEndpoint(String baseUrl, String defaultBaseUrl) {
        return StringUtils.isNotBlank(baseUrl)
                && StringUtils.isNotBlank(defaultBaseUrl)
                && baseUrl.equals(defaultBaseUrl);
    }

    public static boolean supportsXaiNativeWebSearch(String modelId) {
        return ProviderCapabilityHints.supportsXaiNativeWebSearch(normalize(modelId));
    }

    public static boolean isOpenAiSearchPreviewModel(String modelId) {
        return normalize(modelId).matches("gpt-4o(?:-mini)?-search-preview(?:-\\d{4}-\\d{2}-\\d{2})?");
    }

    public static boolean supportsFileInput(ProviderCapabilities capabilities) {
        return capabilities != null && capabilities.supportsFileInput();
    }

}
