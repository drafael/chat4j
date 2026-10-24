package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectionCodecTest {

    @Test
    @DisplayName("Format and parse roundtrip retains provider and model")
    void formatAndParse_roundTrip_retainsSelectionValues() {
        String key = ModelSelectionCodec.format("OpenAI", "gpt-4.1-mini");

        var selection = ModelSelectionCodec.parse(key);

        assertThat(selection).isPresent();
        assertThat(selection.get().provider()).isEqualTo("OpenAI");
        assertThat(selection.get().model()).isEqualTo("gpt-4.1-mini");
    }

    @ParameterizedTest
    @CsvSource({"sonar, fast", "sonar-pro, fast", "sonar-reasoning-pro, low", "sonar-deep-research, high", "medium, medium"})
    @DisplayName("Saved Perplexity selections reopen at the equivalent Agent API research effort")
    void parse_savedPerplexitySelection_preservesResearchDepth(String model, String effort) {
        var subject = ModelSelectionCodec.parse("Perplexity > %s".formatted(model));

        assertThat(subject).contains(new ModelSelectionCodec.ModelSelection("Perplexity", effort));
    }

    @Test
    @DisplayName("OpenRouter Sonar model IDs are not migrated to native Perplexity effort names")
    void parse_openRouterSonarSelection_preservesModelId() {
        var subject = ModelSelectionCodec.parse("OpenRouter > perplexity/sonar-pro");

        assertThat(subject).contains(new ModelSelectionCodec.ModelSelection("OpenRouter", "perplexity/sonar-pro"));
    }

    @Test
    @DisplayName("Parse rejects blank and malformed keys")
    void parse_whenKeyIsBlankOrMalformed_returnsEmpty() {
        assertThat(ModelSelectionCodec.parse(null)).isEmpty();
        assertThat(ModelSelectionCodec.parse("   ")).isEmpty();
        assertThat(ModelSelectionCodec.parse("OpenAI")).isEmpty();
        assertThat(ModelSelectionCodec.parse(" > gpt-4.1-mini")).isEmpty();
        assertThat(ModelSelectionCodec.parse("OpenAI > ")).isEmpty();
    }

    @Test
    @DisplayName("Parse keeps delimiter-like suffix inside model id")
    void parse_whenModelContainsDelimiterLikeText_keepsRemainderInModelId() {
        var selection = ModelSelectionCodec.parse("OpenRouter > meta/llama > 70b");

        assertThat(selection).isPresent();
        assertThat(selection.get().provider()).isEqualTo("OpenRouter");
        assertThat(selection.get().model()).isEqualTo("meta/llama > 70b");
    }
}
