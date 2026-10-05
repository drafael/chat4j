package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFiltersTest {

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "gpt-4o-2024-11-20", "GPT-4O_2024_08_06", "chatgpt-4o-latest",
            "openai/gpt-4o", "openai/gpt-4o-2024-11-20:extended", "openai/chatgpt-4o-latest"})
    @DisplayName("Retired GPT-4o models are excluded from fresh and cached catalogs")
    void isSupportedChatModelId_retiredGpt4o_excludesModel(String model) {
        assertThat(ModelFilters.isSupportedChatModelId(model)).isFalse();
        assertThat(ModelOrdering.sanitizeAndSortByProvider("OpenAI", List.of(model, "gpt-5.5")))
                .containsExactly("gpt-5.5");
        assertThat(ModelOrdering.sanitizeAndSortByProvider("GitHub Copilot", List.of(model, "gpt-5.5")))
                .containsExactly("gpt-5.5");
        assertThat(ModelOrdering.sanitizeAndSortByProvider("OpenRouter", List.of(model, "openai/gpt-5.5")))
                .containsExactly("openai/gpt-5.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "gpt-4o-mini-2024-07-18", "gpt-4o-mini-search-preview",
            "gpt-4o-search-preview", "gpt-4.1", "openai/gpt-4o-mini", "custom-gpt-4o"})
    @DisplayName("Retiring GPT-4o does not retire separate models or custom names")
    void isSupportedChatModelId_otherModel_remainsSupported(String model) {
        assertThat(ModelFilters.isSupportedChatModelId(model)).isTrue();
        assertThat(ModelOrdering.sanitizeAndSortByProvider("OpenAI", List.of(model))).containsExactly(model);
    }
}
