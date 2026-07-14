package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ModelOrderingTest {

    @Test
    @DisplayName("Dated model IDs are prioritized and blank entries are removed")
    void sanitizeAndSortByRecency_whenModelIdsIncludeDates_returnsNewestDateFirst() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByRecency(List.of(
                "  ",
                "claude-3-5-haiku-20241022",
                "claude-3-7-sonnet-20250219",
                "claude-3-5-haiku-20241022"
        ));

        assertThat(sorted).containsExactly(
                "claude-3-7-sonnet-20250219",
                "claude-3-5-haiku-20241022"
        );
    }

    @Test
    @DisplayName("Control characters are rejected from provider model IDs")
    void sanitizeAndSortByRecency_whenModelIdsContainControlCharacters_removesUnsafeEntries() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByRecency(List.of(
                "safe-model",
                "injected-model\nphantom-model",
                "tabbed\tmodel"
        ));

        assertThat(sorted).containsExactly("safe-model");
    }

    @Test
    @DisplayName("Non-dated models are listed before dated models")
    void sanitizeAndSortByRecency_whenMixedDateSuffixes_returnsNonDatedBeforeDated() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByRecency(List.of(
                "claude-sonnet-4-5-20250929",
                "claude-sonnet-4-6",
                "claude-opus-4-5-20251101",
                "claude-opus-4-6"
        ));

        assertThat(sorted).containsExactly(
                "claude-sonnet-4-6",
                "claude-opus-4-6",
                "claude-sonnet-4-5-20250929",
                "claude-opus-4-5-20251101"
        );
    }

    @Test
    @DisplayName("Natural version ordering is used when date suffixes are absent")
    void sanitizeAndSortByRecency_whenNoDates_returnsVersionDescendingOrder() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByRecency(List.of(
                "llama-3.1-8b-instant",
                "llama-3.3-70b-versatile",
                "llama-2.2-13b"
        ));

        assertThat(sorted).containsExactly(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
                "llama-2.2-13b"
        );
    }

    @Test
    @DisplayName("Prefixed models are ordered by provider and then by descending version")
    void sanitizeAndSortByRecency_whenModelsHaveProviderPrefix_returnsPrefixThenVersionOrder() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByRecency(List.of(
                "openai/gpt-4.1",
                "anthropic/claude-3.5",
                "openai/gpt-4.2",
                "google/gemini-2.5",
                "anthropic/claude-3.7"
        ));

        assertThat(sorted).containsExactly(
                "anthropic/claude-3.7",
                "anthropic/claude-3.5",
                "google/gemini-2.5",
                "openai/gpt-4.2",
                "openai/gpt-4.1"
        );
    }

    @Test
    @DisplayName("Dated models are ordered by descending date within each base model")
    void sanitizeAndSortByRecency_whenOnlyDatedModels_returnsBaseThenDateDescendingOrder() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByRecency(List.of(
                "claude-sonnet-4-5-20250801",
                "claude-sonnet-4-5-20250929",
                "claude-opus-4-5-20251101"
        ));

        assertThat(sorted).containsExactly(
                "claude-sonnet-4-5-20250929",
                "claude-sonnet-4-5-20250801",
                "claude-opus-4-5-20251101"
        );
    }

    @Test
    @DisplayName("Google AI model IDs are normalized by removing the models/ prefix")
    void sanitizeAndSortByProvider_whenProviderIsGoogleAi_returnsModelsWithoutPrefix() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByProvider("Google AI", List.of(
                "models/gemini-2.0-flash",
                "gemini-2.0-flash",
                "models/gemini-2.5-pro"
        ));

        assertThat(sorted).containsExactly(
                "gemini-2.5-pro",
                "gemini-2.0-flash"
        );
    }

    @Test
    @DisplayName("Ollama model IDs are sorted in natural ascending order")
    void sanitizeAndSortByProvider_whenProviderIsOllama_returnsNaturallyAscendingOrder() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByProvider("Ollama", List.of(
                "llama3.2:latest",
                "llama3.10:latest",
                "llama2:13b",
                "llama3.2:latest"
        ));

        assertThat(sorted).containsExactly(
                "llama2:13b",
                "llama3.2:latest",
                "llama3.10:latest"
        );
    }

    @Test
    @DisplayName("Copilot alias filtering is independent of the system locale")
    void sanitizeAndSortByProvider_whenDefaultLocaleIsTurkish_filtersLegacyAliases() {
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            List<String> sorted = ModelOrdering.sanitizeAndSortByProvider("GitHub Copilot", List.of(
                    "GPT-4O-MINI-2024-07-18",
                    "gpt-4o-mini"
            ));

            assertThat(sorted).containsExactly("gpt-4o-mini");
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    @DisplayName("Copilot model IDs filter deprecated GPT-3.5 and dated GPT-4o aliases when canonical models exist")
    void sanitizeAndSortByProvider_whenProviderIsCopilot_filtersLegacyAliases() {
        List<String> sorted = ModelOrdering.sanitizeAndSortByProvider("GitHub Copilot", List.of(
                "gpt-4o-mini-2024-07-18",
                "gpt-4o-2024-11-20",
                "gpt-3.5-turbo-0613",
                "gpt-3.5-turbo",
                "gpt-4o-mini",
                "gpt-4o",
                "gpt-5.4"
        ));

        assertThat(sorted).containsExactly(
                "gpt-5.4",
                "gpt-4o-mini",
                "gpt-4o"
        );
    }
}
