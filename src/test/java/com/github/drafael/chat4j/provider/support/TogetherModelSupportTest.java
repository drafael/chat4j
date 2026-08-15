package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TogetherModelSupportTest {

    private static final String HOSTED_BASE_URL = "https://api.together.ai/v1";
    private static final Set<String> SERVERLESS_MODELS = Set.of(
            "thinkingmachines/Inkling",
            "MiniMaxAI/MiniMax-M3",
            "Qwen/Qwen3.8-2.4T-A95B",
            "Qwen/Qwen3.7-Max",
            "Qwen/Qwen3.6-Plus",
            "Qwen/Qwen3.5-9B",
            "moonshotai/Kimi-K3",
            "moonshotai/Kimi-K2.7-Code",
            "moonshotai/Kimi-K2.6",
            "zai-org/GLM-5.2",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "deepseek-ai/DeepSeek-V4-Pro",
            "deepseek-ai/DeepSeek-V4-Flash-0731",
            "nvidia/nemotron-3-ultra-550b-a55b",
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
            "moonshotai/Kimi-K3",
            "moonshotai/Kimi-K2.7-Code",
            "moonshotai/Kimi-K2.6"
    );
    private static final Set<String> TOOL_MODELS = Set.of(
            "thinkingmachines/Inkling",
            "MiniMaxAI/MiniMax-M3",
            "Qwen/Qwen3.5-9B",
            "moonshotai/Kimi-K3",
            "moonshotai/Kimi-K2.7-Code",
            "moonshotai/Kimi-K2.6",
            "zai-org/GLM-5.2",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "deepseek-ai/DeepSeek-V4-Pro",
            "deepseek-ai/DeepSeek-V4-Flash-0731",
            "nvidia/nemotron-3-ultra-550b-a55b",
            "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "Qwen/Qwen2.5-7B-Instruct-Turbo",
            "google/gemma-4-31B-it"
    );
    private static final Set<String> REASONING_MODELS = Set.of(
            "MiniMaxAI/MiniMax-M3",
            "deepseek-ai/DeepSeek-V4-Pro",
            "zai-org/GLM-5.2",
            "moonshotai/Kimi-K3",
            "moonshotai/Kimi-K2.6",
            "Qwen/Qwen3.6-Plus",
            "Qwen/Qwen3.5-9B",
            "deepcogito/cogito-v2-1-671b",
            "nvidia/nemotron-3-ultra-550b-a55b",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b"
    );

    @Test
    @DisplayName("Together provider and hosted endpoint matching preserve the intended normalization boundaries")
    void providerAndEndpointMatching_whenValuesVary_appliesDocumentedNormalization() {
        assertThat(TogetherModelSupport.isTogether(" together ")).isTrue();
        assertThat(TogetherModelSupport.isTogether("TOGETHER")).isTrue();
        assertThat(TogetherModelSupport.isHostedEndpoint(HOSTED_BASE_URL)).isTrue();
        assertThat(TogetherModelSupport.isHostedEndpoint("https://api.together.ai/v1/")).isTrue();
        assertThat(TogetherModelSupport.isHostedEndpoint("https://proxy.example/v1")).isFalse();
        assertThat(TogetherModelSupport.isHostedEndpoint(null)).isFalse();
    }

    @Test
    @DisplayName("The dated serverless snapshot admits exact case-sensitive model IDs only")
    void isServerlessChatModel_whenIdIsExact_admitsReviewedModels() {
        assertThat(SERVERLESS_MODELS).allSatisfy(modelId ->
                assertThat(TogetherModelSupport.isServerlessChatModel(modelId)).isTrue()
        );
        assertThat(TogetherModelSupport.isServerlessChatModel(" Qwen/Qwen3.5-9B ")).isTrue();
        assertThat(TogetherModelSupport.isServerlessChatModel("qwen/qwen3.5-9b")).isFalse();
        assertThat(TogetherModelSupport.isServerlessChatModel("Qwen/new-model")).isFalse();
    }

    @Test
    @DisplayName("Capability snapshots expose only their reviewed serverless subsets")
    void capabilities_whenServerlessModelVaries_matchReviewedSubsets() {
        assertThat(VISION_MODELS).isSubsetOf(SERVERLESS_MODELS);
        assertThat(TOOL_MODELS).isSubsetOf(SERVERLESS_MODELS);
        assertThat(REASONING_MODELS).isSubsetOf(SERVERLESS_MODELS);
        assertThat(SERVERLESS_MODELS).allSatisfy(modelId -> {
            assertThat(TogetherModelSupport.supportsVision(HOSTED_BASE_URL, modelId))
                    .isEqualTo(VISION_MODELS.contains(modelId));
            assertThat(TogetherModelSupport.supportsTools(HOSTED_BASE_URL, modelId))
                    .isEqualTo(TOOL_MODELS.contains(modelId));
            assertThat(TogetherModelSupport.supportsReasoning(HOSTED_BASE_URL, modelId))
                    .isEqualTo(REASONING_MODELS.contains(modelId));
        });
    }

    @Test
    @DisplayName("Hosted capabilities use exact model IDs and custom endpoints stay text only")
    void capabilities_whenEndpointOrCaseDiffers_remainConservative() {
        assertThat(TogetherModelSupport.supportsVision(HOSTED_BASE_URL, "qwen/qwen3.5-9b")).isFalse();
        assertThat(TogetherModelSupport.supportsReasoning(HOSTED_BASE_URL, "zai-org/GLM-5")).isFalse();
        assertThat(TogetherModelSupport.supportsVision("https://proxy.example/v1", "Qwen/Qwen3.5-9B")).isFalse();
        assertThat(TogetherModelSupport.supportsTools("https://proxy.example/v1", "Qwen/Qwen3.5-9B")).isFalse();
        assertThat(TogetherModelSupport.supportsReasoning("https://proxy.example/v1", "Qwen/Qwen3.5-9B")).isFalse();
    }

    @Test
    @DisplayName("Together reasoning requests map Chat4J levels to documented wire values")
    void reasoningRequest_whenLevelVaries_usesLossyDocumentedMapping() {
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "MiniMaxAI/MiniMax-M3", ReasoningLevel.OFF))
                .isEqualTo(new TogetherModelSupport.ReasoningRequest(true, false, null, false));
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "MiniMaxAI/MiniMax-M3", ReasoningLevel.EXTRA_HIGH))
                .isEqualTo(new TogetherModelSupport.ReasoningRequest(true, true, null, false));
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "moonshotai/Kimi-K3", ReasoningLevel.OFF))
                .isEqualTo(new TogetherModelSupport.ReasoningRequest(false, false, "low", false));
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "moonshotai/Kimi-K3", ReasoningLevel.LOW).effort())
                .isEqualTo("low");
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "moonshotai/Kimi-K3", ReasoningLevel.MEDIUM).effort())
                .isEqualTo("high");
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "moonshotai/Kimi-K3", ReasoningLevel.EXTRA_HIGH).effort())
                .isEqualTo("max");
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "openai/gpt-oss-120b", ReasoningLevel.EXTRA_HIGH).effort())
                .isEqualTo("high");
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "deepseek-ai/DeepSeek-V4-Pro", ReasoningLevel.LOW).effort())
                .isEqualTo("high");
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "zai-org/GLM-5.2", ReasoningLevel.EXTRA_HIGH).effort())
                .isEqualTo("max");
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "nvidia/nemotron-3-ultra-550b-a55b", ReasoningLevel.MEDIUM).mediumEffort())
                .isTrue();
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "nvidia/nemotron-3-ultra-550b-a55b", ReasoningLevel.HIGH).mediumEffort())
                .isFalse();
        assertThat(TogetherModelSupport.reasoningRequest(HOSTED_BASE_URL, "Qwen/Qwen3.7-Max", ReasoningLevel.HIGH))
                .isEqualTo(new TogetherModelSupport.ReasoningRequest(false, false, null, false));
    }

    @Test
    @DisplayName("Agent continuation is enabled only for documented hosted models")
    void agentContinuationMode_whenModelVaries_returnsExactPolicy() {
        assertThat(TogetherModelSupport.agentContinuationMode(HOSTED_BASE_URL, "deepseek-ai/DeepSeek-V4-Pro"))
                .isEqualTo(TogetherModelSupport.AgentContinuationMode.DEEPSEEK_EXACT_FIELD);
        assertThat(TogetherModelSupport.agentContinuationMode(HOSTED_BASE_URL, "zai-org/GLM-5.2"))
                .isEqualTo(TogetherModelSupport.AgentContinuationMode.GLM_PRESERVED_REASONING);
        assertThat(TogetherModelSupport.agentContinuationMode(HOSTED_BASE_URL, "MiniMaxAI/MiniMax-M3"))
                .isEqualTo(TogetherModelSupport.AgentContinuationMode.NONE);
        assertThat(TogetherModelSupport.agentContinuationMode("https://proxy.example/v1", "zai-org/GLM-5.2"))
                .isEqualTo(TogetherModelSupport.AgentContinuationMode.NONE);
    }
}
