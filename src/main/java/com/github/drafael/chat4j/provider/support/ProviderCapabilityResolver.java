package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.ReasoningOptions;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Optional;

import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicImageSupport;
import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicNativeWebSearchSupport;
import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicReasoningSupport;
import static com.github.drafael.chat4j.provider.support.DynamicCapabilityResolver.resolveDynamicToolSupport;
import static com.github.drafael.chat4j.provider.support.ProviderCapabilityHints.*;

public final class ProviderCapabilityResolver {

    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";
    private static final String COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String COPILOT_BASE_URL = "https://api.githubcopilot.com";
    private static final String COPILOT_RESPONSES_ENDPOINT = "/responses";
    private static final String COPILOT_WEB_SEARCH_MODEL = "gpt-5.4-mini";

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
        if (TogetherModelSupport.isTogether(providerName)) {
            return TogetherModelSupport.supportsVision(baseUrl, modelId);
        }

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

    /** Resolves control choices from existing model evidence; does not perform capability probes. */
    public static ReasoningOptions reasoningOptions(
            String providerName,
            String modelId,
            String baseUrl,
            @NonNull ReasoningOptions catalogOptions
    ) {
        if (TogetherModelSupport.isTogether(providerName)) {
            return TogetherModelSupport.reasoningOptions(baseUrl, modelId);
        }
        if (Strings.CS.equals(providerName, "Anthropic")
                || (Strings.CS.equals(providerName, "OpenRouter") && Strings.CS.startsWith(modelId, "anthropic/claude-"))) {
            return ReasoningOptions.of(
                    ClaudeReasoningSupport.availableLevels(modelId, Strings.CS.equals(providerName, "OpenRouter")),
                    Strings.CS.equals(providerName, "OpenRouter")
                            && Strings.CS.equals(StringUtils.substringBefore(modelId, ":"), "anthropic/claude-opus-5.5")
                            ? ReasoningLevel.HIGH : ClaudeReasoningSupport.recommendedLevel(modelId)
            );
        }
        if (CODEX_PROVIDER_NAME.equals(providerName)) {
            return catalogOptions;
        }
        Optional<ReasoningOptions> knownOptions = knownReasoningOptions(providerName, modelId);
        if (knownOptions.isPresent()) {
            return knownOptions.get();
        }
        if (Strings.CS.equals(providerName, "Perplexity") && PerplexityModelIds.isReasoningSonarModel(modelId)) {
            // The native Sonar client does not send an effort or reasoning-disable parameter.
            return ReasoningOptions.of(List.of(ReasoningLevel.MEDIUM));
        }
        return ReasoningOptions.of(ReasoningLevel.standardLevels());
    }

    /** Blocking metadata lookup; call from the existing capability worker, never the EDT. */
    public static LocalReasoningMetadata resolveLocalReasoningOptions(String providerName, String modelId, String baseUrl, String apiKey) {
        String provider = normalize(providerName);
        if (StringUtils.isBlank(baseUrl) || StringUtils.isBlank(modelId)
                || !(containsAny(provider, OLLAMA_PROVIDER_HINTS) || containsAny(provider, LM_STUDIO_PROVIDER_HINTS))) {
            return LocalReasoningMetadata.ABSENT;
        }
        return DynamicCapabilityResolver.resolveLocalReasoningOptions(provider, modelId, baseUrl, apiKey);
    }

    public static boolean supportsExplicitReasoningOff(String providerName, String modelId) {
        if (!Strings.CS.equalsAny(providerName, "OpenAI", "Google AI", "Mistral", "xAI")) {
            return false;
        }
        return knownReasoningOptions(providerName, modelId)
                .filter(options -> options.levels().contains(ReasoningLevel.OFF))
                .filter(options -> options.levels().stream().anyMatch(ReasoningLevel::enabled)).isPresent();
    }

    // Provider routes have different effort vocabularies and defaults for the same model family.
    private static Optional<ReasoningOptions> knownReasoningOptions(String providerName, String modelId) {
        if (Strings.CS.equals(providerName, "OpenRouter")) {
            return OpenRouterReasoningSupport.options(modelId);
        }
        if (Strings.CS.equals(providerName, "Google AI")) {
            return GoogleReasoningSupport.options(modelId);
        }
        if (Strings.CS.equals(providerName, "DeepSeek")) {
            return switch (StringUtils.trimToEmpty(modelId)) {
                case "deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash" -> Optional.of(ReasoningOptions.of(
                        List.of(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), ReasoningLevel.HIGH));
                default -> Optional.empty();
            };
        }
        if (Strings.CS.equals(providerName, "Mistral")) {
            return switch (StringUtils.trimToEmpty(modelId)) {
                case "mistral-small-latest", "mistral-small-2603", "mistral-medium-3-5", "mistral-medium-latest",
                     "mistral-medium-2604" -> Optional.of(ReasoningOptions.of(List.of(ReasoningLevel.OFF, ReasoningLevel.HIGH), ReasoningLevel.HIGH));
                case "zai-glm-5-3" -> Optional.of(ReasoningOptions.of(List.of(
                        ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX), ReasoningLevel.HIGH));
                default -> Optional.empty();
            };
        }
        if (Strings.CS.equals(providerName, "Groq")) {
            return Optional.ofNullable(switch (StringUtils.trimToEmpty(modelId)) {
                case "openai/gpt-oss-20b", "openai/gpt-oss-120b" -> ReasoningOptions.of(
                        List.of(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
                );
                case "qwen/qwen3.8-27b" -> ReasoningOptions.of(
                        List.of(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), ReasoningLevel.OFF
                );
                default -> null;
            });
        }
        if (Strings.CS.equals(providerName, "xAI")) {
            return switch (StringUtils.trimToEmpty(modelId)) {
                case "grok-4.3", "grok-4.7" -> Optional.of(ReasoningOptions.of(
                        StringUtils.trimToEmpty(modelId).equals("grok-4.3") ? ReasoningLevel.standardLevels()
                                : List.of(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.EXTRA_HIGH),
                        StringUtils.trimToEmpty(modelId).equals("grok-4.3") ? ReasoningLevel.LOW : ReasoningLevel.HIGH
                ));
                default -> Optional.empty();
            };
        }
        if (!Strings.CS.equals(providerName, "OpenAI")) {
            return Optional.empty();
        }
        String model = StringUtils.trimToEmpty(modelId).replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", "");
        return Optional.ofNullable(switch (model) {
            case "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano" -> ReasoningOptions.UNAVAILABLE;
            case "gpt-5", "gpt-5-mini", "gpt-5-nano" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH));
            case "gpt-5.1" -> ReasoningOptions.of(
                    List.of(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), ReasoningLevel.OFF
            );
            case "gpt-5.2", "gpt-5.4" -> ReasoningOptions.of(ReasoningLevel.standardLevels(), ReasoningLevel.OFF);
            case "gpt-5.5" -> ReasoningOptions.of(ReasoningLevel.standardLevels());
            case "gpt-6-sol", "gpt-6-luna" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.EXTRA_HIGH, ReasoningLevel.MAX));
            case "gpt-6-astra", "gpt-6.1-sol" -> ReasoningOptions.of(List.of(
                    ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.EXTRA_HIGH, ReasoningLevel.MAX
            ));
            default -> null;
        });
    }

    public static boolean supportsReasoning(String providerName, String modelId) {
        return supportsReasoning(providerName, modelId, null, null);
    }

    public static boolean supportsReasoning(String providerName, String modelId, String baseUrl) {
        return supportsReasoning(providerName, modelId, baseUrl, null);
    }

    public static boolean supportsReasoning(String providerName, String modelId, String baseUrl, String apiKey) {
        return resolveReasoningSupport(providerName, modelId, baseUrl, apiKey).orElse(false);
    }

    /** Empty means no evidence, not confirmation that reasoning is unsupported. */
    public static Optional<Boolean> resolveReasoningSupport(String providerName, String modelId, String baseUrl, String apiKey) {
        if (TogetherModelSupport.isTogether(providerName)) {
            return Optional.of(TogetherModelSupport.supportsReasoning(baseUrl, modelId));
        }

        String provider = normalize(providerName);
        String model = normalize(modelId);

        if (containsAny(provider, PERPLEXITY_PROVIDER_HINTS)) {
            return Optional.of(PerplexityModelIds.isReasoningSonarModel(modelId));
        }

        Optional<Boolean> dynamicallyResolvedSupport = resolveDynamicReasoningSupport(provider, modelId, baseUrl, apiKey);
        if (dynamicallyResolvedSupport.isPresent()) {
            return dynamicallyResolvedSupport;
        }

        Optional<ReasoningOptions> knownOptions = knownReasoningOptions(providerName, modelId);
        if (knownOptions.isPresent()) {
            return Optional.of(knownOptions.get().levels().stream().anyMatch(ReasoningLevel::enabled));
        }
        if (containsAny(model, REASONING_MODEL_DENY_HINTS)) {
            return Optional.of(false);
        }
        if (containsAny(provider, DEEPSEEK_PROVIDER_HINTS)) {
            return Optional.of(supportsDeepSeekReasoning(model));
        }

        if (OPENROUTER_PROVIDER_HINTS.contains(provider) && PerplexityModelIds.isNamespacedReasoningSonarModel(modelId)) {
            return Optional.of(true);
        }

        return containsAny(provider, REASONING_PROVIDER_HINTS)
                && !model.isBlank() && containsAny(model, REASONING_MODEL_ALLOW_HINTS)
                ? Optional.of(true) : Optional.empty();
    }

    public static boolean supportsToolInvocation(String providerName, String modelId) {
        return supportsToolInvocation(providerName, modelId, null, null);
    }

    public static boolean supportsToolInvocation(String providerName, String modelId, String baseUrl) {
        return supportsToolInvocation(providerName, modelId, baseUrl, null);
    }

    public static boolean supportsToolInvocation(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        if (TogetherModelSupport.isTogether(providerName)) {
            return TogetherModelSupport.supportsTools(baseUrl, modelId);
        }

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
        return staticNativeWebSearchOutcome(providerName, modelId, baseUrl, defaultBaseUrl, Optional.empty());
    }

    public static NativeWebSearchOutcome nativeWebSearchOutcomeFromCachedEndpoints(
            String providerName,
            String modelId,
            String baseUrl,
            String defaultBaseUrl,
            Optional<List<String>> supportedEndpoints
    ) {
        return staticNativeWebSearchOutcome(
                providerName,
                modelId,
                baseUrl,
                defaultBaseUrl,
                supportedEndpoints
        );
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
                defaultBaseUrl,
                Optional.empty()
        );
        if (staticOutcome != NativeWebSearchOutcome.PENDING
                || !supportsRuntimeDynamicNativeWebSearchProbe(providerName)) {
            return staticOutcome;
        }
        return resolveDynamicNativeWebSearchSupport(normalize(providerName), modelId, baseUrl, apiKey)
                .map(supported -> supported ? NativeWebSearchOutcome.OPTIONAL : NativeWebSearchOutcome.UNSUPPORTED)
                .orElse(NativeWebSearchOutcome.PENDING);
    }

    private static NativeWebSearchOutcome staticNativeWebSearchOutcome(
            String providerName,
            String modelId,
            String baseUrl,
            String defaultBaseUrl,
            Optional<List<String>> supportedEndpoints
    ) {
        String provider = normalize(providerName);
        String model = normalize(modelId);
        if (TogetherModelSupport.isTogether(providerName)
                || model.isBlank()
                || containsAny(model, NATIVE_WEB_SEARCH_MODEL_DENY_HINTS)) {
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
        if (MistralNativeWebSearchSupport.isMistral(providerName)) {
            return MistralNativeWebSearchSupport.supports(providerName, modelId, baseUrl)
                    ? NativeWebSearchOutcome.OPTIONAL
                    : NativeWebSearchOutcome.UNSUPPORTED;
        }
        if (Strings.CS.equals(providerName, COPILOT_PROVIDER_NAME)) {
            return copilotNativeWebSearchOutcome(model, baseUrl, defaultBaseUrl, supportedEndpoints);
        }
        if (!sameEndpoint(baseUrl, defaultBaseUrl)) {
            return NativeWebSearchOutcome.UNSUPPORTED;
        }
        if (Strings.CS.equals(providerName, CODEX_PROVIDER_NAME)) {
            return NativeWebSearchOutcome.OPTIONAL;
        }
        if (GOOGLE_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider) && isGoogleLatestAlias(model)) {
            return NativeWebSearchOutcome.UNSUPPORTED;
        }
        if ((GROQ_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider) && supportsGroqNativeWebSearch(model))
                || (OPENROUTER_PROVIDER_HINTS.contains(provider) && supportsOpenRouterNativeWebSearch(model))
                || (OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider) && isOpenAiRequiredSearchModel(model))) {
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
        if ((ANTHROPIC_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && containsAny(model, ANTHROPIC_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS))
                || (OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && containsAny(model, OPENAI_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS))
                || (XAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && supportsXaiNativeWebSearch(model))
                || (OPENROUTER_PROVIDER_HINTS.contains(provider)
                && supportsOpenRouterServerWebSearch(model))
                || (GOOGLE_NATIVE_WEB_SEARCH_PROVIDER_HINTS.contains(provider)
                && supportsGoogleNativeWebSearchModel(model))) {
            return NativeWebSearchOutcome.OPTIONAL;
        }
        return NativeWebSearchOutcome.UNSUPPORTED;
    }

    public static boolean supportsCopilotResponsesWebSearchRoute(
            String providerName,
            String modelId,
            String baseUrl,
            String defaultBaseUrl
    ) {
        return Strings.CS.equals(providerName, COPILOT_PROVIDER_NAME)
                && Strings.CS.equals(BaseUrlNormalizer.normalize(baseUrl, ""), COPILOT_BASE_URL)
                && Strings.CS.equals(BaseUrlNormalizer.normalize(defaultBaseUrl, ""), COPILOT_BASE_URL)
                && Strings.CS.equals(normalize(modelId), COPILOT_WEB_SEARCH_MODEL);
    }

    private static NativeWebSearchOutcome copilotNativeWebSearchOutcome(
            String normalizedModel,
            String baseUrl,
            String defaultBaseUrl,
            Optional<List<String>> supportedEndpoints
    ) {
        if (!supportsCopilotResponsesWebSearchRoute(
                COPILOT_PROVIDER_NAME,
                normalizedModel,
                baseUrl,
                defaultBaseUrl
        )) {
            return NativeWebSearchOutcome.UNSUPPORTED;
        }
        if (supportedEndpoints.isEmpty()) {
            return NativeWebSearchOutcome.PENDING;
        }
        return supportedEndpoints.get().stream().anyMatch(COPILOT_RESPONSES_ENDPOINT::equals)
                ? NativeWebSearchOutcome.OPTIONAL
                : NativeWebSearchOutcome.UNSUPPORTED;
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

    private static boolean supportsGoogleNativeWebSearchModel(String modelId) {
        String model = normalize(modelId);
        return containsAny(model, GOOGLE_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS) && !isGoogleLatestAlias(model);
    }

    public static boolean isGoogleLatestAlias(String modelId) {
        String model = Strings.CS.removeStart(normalize(modelId), "models/");
        return model.matches("gemini(?:-.+)?-latest");
    }

    public static boolean supportsXaiNativeWebSearch(String modelId) {
        return ProviderCapabilityHints.supportsXaiNativeWebSearch(normalize(modelId));
    }

    public static boolean isOpenAiSearchPreviewModel(String modelId) {
        return normalize(modelId).matches("gpt-4o(?:-mini)?-search-preview(?:-\\d{4}-\\d{2}-\\d{2})?");
    }

    public static boolean isOpenAiRequiredSearchModel(String modelId) {
        return Strings.CS.equals(normalize(modelId), "gpt-5-search-api")
                || isOpenAiSearchPreviewModel(modelId);
    }

    public static boolean supportsFileInput(ProviderCapabilities capabilities) {
        return capabilities != null && capabilities.supportsFileInput();
    }

}
