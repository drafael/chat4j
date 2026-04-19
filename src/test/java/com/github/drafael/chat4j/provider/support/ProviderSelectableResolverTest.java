package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderSelectableResolverTest {

    private final ProviderSelectableResolver subject = new ProviderSelectableResolver();

    @Test
    @DisplayName("Resolve maps providers to selectable flags preserving provider order")
    void resolve_whenCalled_returnsSelectableMapInProviderOrder() {
        List<ProviderRegistry.ProviderDef> providers = List.of(
                provider("OpenAI"),
                provider("Ollama"),
                provider("Anthropic")
        );
        List<String> evaluatedProviders = new ArrayList<>();

        Map<String, Boolean> selectable = subject.resolve(providers, provider -> {
            evaluatedProviders.add(provider.name());
            return !"Ollama".equals(provider.name());
        });

        assertThat(selectable.keySet()).containsExactly("OpenAI", "Ollama", "Anthropic");
        assertThat(selectable).containsEntry("OpenAI", true)
                .containsEntry("Ollama", false)
                .containsEntry("Anthropic", true);
        assertThat(evaluatedProviders).containsExactly("OpenAI", "Ollama", "Anthropic");
    }

    private ProviderRegistry.ProviderDef provider(String name) {
        return new ProviderRegistry.ProviderDef(
                name,
                "API_KEY",
                "https://example.invalid",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }
}
