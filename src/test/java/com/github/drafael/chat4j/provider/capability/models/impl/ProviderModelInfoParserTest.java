package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.api.ProviderModelInfo;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderModelInfoParserTest {

    @Test
    @DisplayName("OpenRouter per-token prices become per-million prices, including free models")
    void parse_openRouterMetadata_convertsPricesAndKeepsLimits() {
        Map<String, ProviderModelInfo> subject = parse("OpenRouter", """
                {"data":[{"id":"vendor/model","name":"Model","description":"Useful model",
                  "context_length":200000,"top_provider":{"max_completion_tokens":32000},
                  "pricing":{"prompt":"0.000003","completion":"0"}}]}
                """);

        ProviderModelInfo info = subject.get("vendor/model");
        assertThat(info.displayName()).isEqualTo("Model");
        assertThat(info.description()).isEqualTo("Useful model");
        assertThat(info.contextWindowTokens()).isEqualTo(200000L);
        assertThat(info.maxOutputTokens()).isEqualTo(32000L);
        assertThat(info.inputUsdPerMillion()).isEqualByComparingTo("3");
        assertThat(info.outputUsdPerMillion()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Together prices are already denominated per million tokens")
    void parse_togetherMetadata_preservesPriceUnits() {
        Map<String, ProviderModelInfo> subject = parse("Together", """
                [{"id":"vendor/model","display_name":"Model","context_length":131072,
                  "pricing":{"input":0.6,"output":1.2}}]
                """);

        assertThat(subject.get("vendor/model").inputUsdPerMillion()).isEqualByComparingTo("0.6");
        assertThat(subject.get("vendor/model").outputUsdPerMillion()).isEqualByComparingTo("1.2");
    }

    @Test
    @DisplayName("Google model IDs match the selector, while input and context limits remain distinct")
    void parse_googleMetadata_normalizesIdsAndLabelsInputLimits() {
        Map<String, ProviderModelInfo> subject = parse("Google AI", """
                {"models":[{"name":"models/gemini-test","displayName":"Gemini Test",
                  "inputTokenLimit":1000000,"outputTokenLimit":65536}]}
                """);

        ProviderModelInfo info = subject.get("gemini-test");
        assertThat(info.displayName()).isEqualTo("Gemini Test");
        assertThat(info.maxInputTokens()).isEqualTo(1000000L);
        assertThat(info.contextWindowTokens()).isNull();
        assertThat(info.maxOutputTokens()).isEqualTo(65536L);
    }

    @Test
    @DisplayName("Missing, invalid, and sentinel values are not invented as usable metadata")
    void parse_partialAndMalformedEntries_omitsUnknownFieldsAndIdOnlyCards() {
        Map<String, ProviderModelInfo> subject = parse("OpenRouter", """
                {"data":[null, {"id":42}, {"id":"id-only"},
                  {"id":"bad-values","name":"Bad Values","context_length":1.5,
                   "top_provider":{"max_completion_tokens":-1},
                   "pricing":{"prompt":"-1","completion":"not-a-price"}},
                  {"id":"overflow","context_length":999999999999999999999999999999}]}
                """);

        assertThat(subject).containsOnlyKeys("bad-values");
        ProviderModelInfo info = subject.get("bad-values");
        assertThat(info.contextWindowTokens()).isNull();
        assertThat(info.maxOutputTokens()).isNull();
        assertThat(info.inputUsdPerMillion()).isNull();
        assertThat(info.outputUsdPerMillion()).isNull();
    }

    @Test
    @DisplayName("Copilot advertised model limits are retained without interpreting billing multipliers as prices")
    void parse_copilotMetadata_keepsExplicitLimitsOnly() {
        Map<String, ProviderModelInfo> subject = parse("GitHub Copilot", """
                {"data":[{"id":"claude-test","name":"Claude Test","billing":{"multiplier":3},
                  "capabilities":{"limits":{"max_context_window_tokens":200000,
                  "max_prompt_tokens":128000,"max_output_tokens":16000}}}]}
                """);

        ProviderModelInfo info = subject.get("claude-test");
        assertThat(info.contextWindowTokens()).isEqualTo(200000L);
        assertThat(info.maxInputTokens()).isEqualTo(128000L);
        assertThat(info.maxOutputTokens()).isEqualTo(16000L);
        assertThat(info.inputUsdPerMillion()).isNull();
    }

    @Test
    @DisplayName("Groq, Mistral, and LM Studio retain their advertised context limits")
    void parse_providerContextFields_keepsAdvertisedLimits() {
        assertThat(parse("Groq", """
                {"data":[{"id":"model","context_window":32768}]}
                """).get("model").contextWindowTokens()).isEqualTo(32768L);
        assertThat(parse("Mistral", """
                {"data":[{"id":"model","max_context_length":128000}]}
                """).get("model").contextWindowTokens()).isEqualTo(128000L);
        assertThat(parse("LM Studio", """
                {"models":[{"key":"model","display_name":"Local Model","max_context_length":8192}]}
                """).get("model").contextWindowTokens()).isEqualTo(8192L);
    }

    private Map<String, ProviderModelInfo> parse(String provider, String json) {
        return ProviderModelInfoParser.parse(provider, json.getBytes(StandardCharsets.UTF_8));
    }
}
