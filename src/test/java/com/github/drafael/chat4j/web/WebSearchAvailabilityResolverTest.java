package com.github.drafael.chat4j.web;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchAvailabilityResolverTest {

    private final WebSearchAvailabilityResolver subject = new WebSearchAvailabilityResolver();

    @Test
    @DisplayName("Web search is unavailable when no native or external provider is available")
    void resolve_whenNoNativeOrExternalProviderAvailable_returnsNoOptions() {
        ProviderRegistry.ProviderDef selected = provider("Ollama", ProviderCapabilities.chatAndModels());

        WebSearchAvailability availability = subject.resolve(selected, "llama3", List.of(selected));

        assertThat(availability.available()).isFalse();
        assertThat(availability.options()).isEmpty();
    }

    @Test
    @DisplayName("Native web search is offered when the selected model supports it")
    void resolve_whenSelectedModelSupportsNativeWebSearch_includesNativeOption() {
        ProviderRegistry.ProviderDef selected = provider(
                "Perplexity",
                ProviderCapabilities.chatModelsAndNativeWebSearch()
        );

        WebSearchAvailability availability = subject.resolve(selected, "sonar", List.of(selected));

        assertThat(availability.available()).isTrue();
        assertThat(availability.defaultOptionId()).isEqualTo(WebSearchAvailabilityResolver.NATIVE_OPTION_ID);
        assertThat(availability.options()).extracting(WebSearchOption::id)
                .containsExactly(WebSearchAvailabilityResolver.NATIVE_OPTION_ID);
    }

    @Test
    @DisplayName("Anthropic models with a native request path expose the native option")
    void resolve_whenAnthropicModelSupportsNativeWebSearch_includesNativeOption() {
        ProviderRegistry.ProviderDef selected = provider("Anthropic", ProviderCapabilities.chatModelsAndImages());

        WebSearchAvailability availability = subject.resolve(
                selected,
                "claude-haiku-4-5-20251001",
                List.of(selected)
        );

        assertThat(availability.available()).isTrue();
        assertThat(availability.defaultOptionId()).isEqualTo(WebSearchAvailabilityResolver.NATIVE_OPTION_ID);
        assertThat(availability.options()).extracting(WebSearchOption::id)
                .containsExactly(WebSearchAvailabilityResolver.NATIVE_OPTION_ID);
    }

    @Test
    @DisplayName("Perplexity is offered as an external alternative when available")
    void resolve_whenExternalPerplexityAvailable_includesPerplexityAlternative() {
        ProviderRegistry.ProviderDef selected = provider("OpenAI", ProviderCapabilities.chatAndModels());
        ProviderRegistry.ProviderDef perplexity = provider(
                "Perplexity",
                ProviderCapabilities.chatModelsAndNativeWebSearch()
        );

        WebSearchAvailability availability = subject.resolve(selected, "gpt-4.1", List.of(selected, perplexity));

        assertThat(availability.available()).isTrue();
        assertThat(availability.defaultOptionId()).isEqualTo(WebSearchAvailabilityResolver.NATIVE_OPTION_ID);
        assertThat(availability.options()).extracting(WebSearchOption::id)
                .containsExactly(
                        WebSearchAvailabilityResolver.NATIVE_OPTION_ID,
                        WebSearchAvailabilityResolver.PERPLEXITY_OPTION_ID
                );
    }

    private ProviderRegistry.ProviderDef provider(String name, ProviderCapabilities capabilities) {
        return new ProviderRegistry.ProviderDef(
                name,
                null,
                "https://example.test",
                emptyList(),
                capabilities,
                model -> null,
                () -> emptyList()
        );
    }
}
