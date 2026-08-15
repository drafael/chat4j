package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Set;

/**
 * Exact hosted-serverless and capability snapshots reviewed on 2026-08-15.
 *
 * @see <a href="https://docs.together.ai/docs/serverless/models">Together serverless models</a>
 * @see <a href="https://docs.together.ai/docs/inference/vision/overview">Together vision</a>
 * @see <a href="https://docs.together.ai/docs/inference/chat/reasoning">Together reasoning</a>
 * @see <a href="https://docs.together.ai/docs/inference/function-calling/overview">Together function calling</a>
 * @see <a href="https://www.together.ai/blog/kimi-k3-guide">Together Kimi K3 guide</a>
 */
public final class TogetherModelSupport {

    private static final String PROVIDER_NAME = "Together";
    private static final String DEFAULT_BASE_URL = "https://api.together.ai/v1";

    private static final String DEEPSEEK_V4_PRO = "deepseek-ai/DeepSeek-V4-Pro";
    private static final String GLM_5_2 = "zai-org/GLM-5.2";
    private static final String KIMI_K3 = "moonshotai/Kimi-K3";
    private static final String NEMOTRON_3_ULTRA = "nvidia/nemotron-3-ultra-550b-a55b";

    private static final Set<String> SERVERLESS_CHAT_MODELS = Set.of(
            "thinkingmachines/Inkling",
            "MiniMaxAI/MiniMax-M3",
            "Qwen/Qwen3.8-2.4T-A95B",
            "Qwen/Qwen3.7-Max",
            "Qwen/Qwen3.6-Plus",
            "Qwen/Qwen3.5-9B",
            KIMI_K3,
            "moonshotai/Kimi-K2.7-Code",
            "moonshotai/Kimi-K2.6",
            GLM_5_2,
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            DEEPSEEK_V4_PRO,
            "deepseek-ai/DeepSeek-V4-Flash-0731",
            NEMOTRON_3_ULTRA,
            "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "Qwen/Qwen2.5-7B-Instruct-Turbo",
            "google/gemma-4-31B-it",
            "pearl-ai/gemma-4-31b-it",
            "deepcogito/cogito-v2-1-671b",
            "Qwen/Qwen3.7-Plus",
            "google/gemma-3n-E4B-it",
            "LiquidAI/LFM2.5-8B-A1B",
            "thinkingmachines/Inkling-Small",
            "Prism-ML/Ternary-Bonsai-27B",
            "meta-models/Muse-Glimmer-30B"
    );

    private static final Set<String> VISION_MODELS = Set.of(
            "Qwen/Qwen3.5-9B",
            "google/gemma-4-31B-it",
            "MiniMaxAI/MiniMax-M3",
            KIMI_K3,
            "moonshotai/Kimi-K2.7-Code",
            "moonshotai/Kimi-K2.6"
    );

    private static final Set<String> TOOL_MODELS = Set.of(
            "thinkingmachines/Inkling",
            "MiniMaxAI/MiniMax-M3",
            "Qwen/Qwen3.5-9B",
            KIMI_K3,
            "moonshotai/Kimi-K2.7-Code",
            "moonshotai/Kimi-K2.6",
            GLM_5_2,
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            DEEPSEEK_V4_PRO,
            "deepseek-ai/DeepSeek-V4-Flash-0731",
            NEMOTRON_3_ULTRA,
            "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "Qwen/Qwen2.5-7B-Instruct-Turbo",
            "google/gemma-4-31B-it"
    );

    private static final Set<String> REASONING_MODELS = Set.of(
            "MiniMaxAI/MiniMax-M3",
            DEEPSEEK_V4_PRO,
            GLM_5_2,
            KIMI_K3,
            "moonshotai/Kimi-K2.6",
            "Qwen/Qwen3.6-Plus",
            "Qwen/Qwen3.5-9B",
            "deepcogito/cogito-v2-1-671b",
            NEMOTRON_3_ULTRA,
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
    );

    private static final Set<String> GPT_OSS_MODELS = Set.of(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
    );

    private static final Set<String> HIGH_MAX_MODELS = Set.of(DEEPSEEK_V4_PRO, GLM_5_2);

    private TogetherModelSupport() {
    }

    public static boolean isTogether(String providerName) {
        return Strings.CI.equals(StringUtils.trim(providerName), PROVIDER_NAME);
    }

    public static boolean isHostedEndpoint(String baseUrl) {
        return Strings.CS.equals(BaseUrlNormalizer.normalize(baseUrl, ""), DEFAULT_BASE_URL);
    }

    public static boolean isServerlessChatModel(String modelId) {
        return SERVERLESS_CHAT_MODELS.contains(normalizeModelId(modelId));
    }

    public static boolean supportsVision(String baseUrl, String modelId) {
        return isHostedEndpoint(baseUrl) && VISION_MODELS.contains(normalizeModelId(modelId));
    }

    public static boolean supportsTools(String baseUrl, String modelId) {
        return isHostedEndpoint(baseUrl) && TOOL_MODELS.contains(normalizeModelId(modelId));
    }

    public static boolean supportsReasoning(String baseUrl, String modelId) {
        return reasoningMode(baseUrl, modelId) != ReasoningMode.NONE;
    }

    private static ReasoningMode reasoningMode(String baseUrl, String modelId) {
        if (!isHostedEndpoint(baseUrl)) {
            return ReasoningMode.NONE;
        }
        String normalizedModelId = normalizeModelId(modelId);
        if (!REASONING_MODELS.contains(normalizedModelId)) {
            return ReasoningMode.NONE;
        }
        if (KIMI_K3.equals(normalizedModelId)) {
            return ReasoningMode.KIMI_K3;
        }
        if (GPT_OSS_MODELS.contains(normalizedModelId)) {
            return ReasoningMode.GPT_OSS;
        }
        if (HIGH_MAX_MODELS.contains(normalizedModelId)) {
            return ReasoningMode.HIGH_MAX;
        }
        if (NEMOTRON_3_ULTRA.equals(normalizedModelId)) {
            return ReasoningMode.NEMOTRON;
        }
        return ReasoningMode.BINARY_HYBRID;
    }

    public static ReasoningRequest reasoningRequest(String baseUrl, String modelId, ReasoningLevel reasoningLevel) {
        ReasoningLevel level = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        return switch (reasoningMode(baseUrl, modelId)) {
            case NONE -> ReasoningRequest.none();
            case BINARY_HYBRID -> ReasoningRequest.enabled(level.enabled());
            case KIMI_K3 -> kimiK3Request(level);
            case GPT_OSS -> gptOssRequest(level);
            case HIGH_MAX -> level.enabled()
                    ? ReasoningRequest.effort(level == ReasoningLevel.EXTRA_HIGH ? "max" : "high")
                    : ReasoningRequest.enabled(false);
            case NEMOTRON -> level.enabled()
                    ? ReasoningRequest.nemotron(level == ReasoningLevel.LOW || level == ReasoningLevel.MEDIUM)
                    : ReasoningRequest.enabled(false);
        };
    }

    public static AgentContinuationMode agentContinuationMode(String baseUrl, String modelId) {
        if (!isHostedEndpoint(baseUrl)) {
            return AgentContinuationMode.NONE;
        }
        return switch (normalizeModelId(modelId)) {
            case DEEPSEEK_V4_PRO -> AgentContinuationMode.DEEPSEEK_EXACT_FIELD;
            case GLM_5_2 -> AgentContinuationMode.GLM_PRESERVED_REASONING;
            default -> AgentContinuationMode.NONE;
        };
    }

    private static ReasoningRequest kimiK3Request(ReasoningLevel level) {
        return switch (level) {
            case OFF, LOW -> ReasoningRequest.effort("low");
            case MEDIUM, HIGH -> ReasoningRequest.effort("high");
            case EXTRA_HIGH -> ReasoningRequest.effort("max");
        };
    }

    private static ReasoningRequest gptOssRequest(ReasoningLevel level) {
        return switch (level) {
            case OFF -> ReasoningRequest.none();
            case LOW -> ReasoningRequest.effort("low");
            case MEDIUM -> ReasoningRequest.effort("medium");
            case HIGH, EXTRA_HIGH -> ReasoningRequest.effort("high");
        };
    }

    private static String normalizeModelId(String modelId) {
        return StringUtils.trimToEmpty(modelId);
    }

    private enum ReasoningMode {
        NONE,
        BINARY_HYBRID,
        KIMI_K3,
        GPT_OSS,
        HIGH_MAX,
        NEMOTRON
    }

    public enum AgentContinuationMode {
        NONE,
        DEEPSEEK_EXACT_FIELD,
        GLM_PRESERVED_REASONING
    }

    public record ReasoningRequest(
            boolean enabledPropertyPresent,
            boolean enabled,
            String effort,
            boolean mediumEffort
    ) {
        private static ReasoningRequest none() {
            return new ReasoningRequest(false, false, null, false);
        }

        private static ReasoningRequest enabled(boolean enabled) {
            return new ReasoningRequest(true, enabled, null, false);
        }

        private static ReasoningRequest effort(String effort) {
            return new ReasoningRequest(false, false, effort, false);
        }

        private static ReasoningRequest nemotron(boolean mediumEffort) {
            return new ReasoningRequest(true, true, null, mediumEffort);
        }
    }
}
