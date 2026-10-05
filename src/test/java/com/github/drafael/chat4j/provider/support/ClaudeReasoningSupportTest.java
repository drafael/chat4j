package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeReasoningSupportTest {

    @ParameterizedTest
    @CsvSource({
            "claude-sonnet-4-6, MEDIUM", "anthropic/claude-sonnet-4.6:batch, MEDIUM",
            "claude-opus-5-5, MEDIUM", "claude-sonnet-5-5, HIGH", "claude-opus-4-6, HIGH",
            "claude-opus-4-8, HIGH", "claude-fable-5-1, HIGH", "claude-3-7-sonnet, MEDIUM"
    })
    @DisplayName("Claude defaults follow general-purpose recommendations without changing legacy budget controls")
    void recommendedLevel_knownModel_usesDocumentedRecommendation(String model, ReasoningLevel expected) {
        assertThat(ClaudeReasoningSupport.recommendedLevel(model)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-6", "claude-opus-4-6", "anthropic/claude-sonnet-4.6:batch", "anthropic/claude-opus-4.6"})
    @DisplayName("Claude 4.6 offers Max but not the unsupported Extra High effort")
    void availableLevels_whenClaude46Selected_omitsExtraHigh(String modelId) {
        assertThat(ClaudeReasoningSupport.availableLevels(modelId, modelId.startsWith("anthropic/")))
                .containsExactly(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.MAX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-5", "claude-opus-5", "claude-sonnet-5-5"})
    @DisplayName("Optional adaptive thinking offers Off through Max without Ultra")
    void availableLevels_whenThinkingIsOptional_offersDistinctEfforts(String modelId) {
        assertThat(ClaudeReasoningSupport.usesAdaptiveThinking(modelId)).isTrue();
        assertThat(ClaudeReasoningSupport.availableLevels(modelId, false))
                .containsExactly(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM,
                        ReasoningLevel.HIGH, ReasoningLevel.EXTRA_HIGH, ReasoningLevel.MAX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-opus-5-5", "claude-fable-5", "claude-fable-5-1"})
    @DisplayName("Mandatory Claude thinking does not offer Off or Ultra")
    void availableLevels_whenThinkingIsMandatory_omitsOff(String modelId) {
        assertThat(ClaudeReasoningSupport.availableLevels(modelId, false))
                .containsExactly(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH,
                        ReasoningLevel.EXTRA_HIGH, ReasoningLevel.MAX);
    }

    @Test
    @DisplayName("OpenRouter requires Sonnet 5.5 thinking while native Anthropic supports between-tools mode")
    void availableLevels_whenSonnet55ProviderChanges_matchesProviderPolicy() {
        assertThat(ClaudeReasoningSupport.availableLevels("anthropic/claude-sonnet-5.5:batch", true))
                .containsExactly(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH,
                        ReasoningLevel.EXTRA_HIGH, ReasoningLevel.MAX);
        assertThat(ClaudeReasoningSupport.requiresThinking("claude-sonnet-5-5", false)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-5", "anthropic/claude-sonnet-4.5", "claude-3-7-sonnet-latest", "claude-future"})
    @DisplayName("Older and unreviewed Claude models retain the existing budget-based choices")
    void availableLevels_whenModelIsNotAdaptive_preservesStandardLevels(String modelId) {
        assertThat(ClaudeReasoningSupport.usesAdaptiveThinking(modelId)).isFalse();
        assertThat(ClaudeReasoningSupport.availableLevels(modelId, false)).isEqualTo(ReasoningLevel.standardLevels());
    }
}
