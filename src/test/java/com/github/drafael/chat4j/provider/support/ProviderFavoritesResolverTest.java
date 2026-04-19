package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderFavoritesResolverTest {

    @Test
    @DisplayName("Resolve favorite selections returns only favorite provider-model pairs")
    void resolveFavoriteSelections_whenFavoritesExist_returnsOnlyFavoriteSelections() throws Exception {
        ModelFavoritesService favoritesService = ModelFavoritesService.createInMemory();
        favoritesService.setFavorite("OpenAI", "gpt-4.1", true);

        var subject = new ProviderFavoritesResolver(favoritesService);

        List<ProviderRegistry.ProviderDef> providers = List.of(
                provider("OpenAI"),
                provider("Anthropic")
        );
        Map<String, List<String>> modelsByProvider = Map.of(
                "OpenAI", List.of("gpt-4.1", "gpt-4o-mini"),
                "Anthropic", List.of("claude-sonnet")
        );

        List<ModelSelectionCodec.ModelSelection> favorites =
                subject.resolveFavoriteSelections(providers, modelsByProvider);

        assertThat(favorites)
                .containsExactly(new ModelSelectionCodec.ModelSelection("OpenAI", "gpt-4.1"));
    }

    @Test
    @DisplayName("Exclude favorites removes favorite models and keeps non-favorites")
    void excludeFavorites_whenFavoritesExist_filtersOutFavoriteModels() throws Exception {
        ModelFavoritesService favoritesService = ModelFavoritesService.createInMemory();
        favoritesService.setFavorite("OpenAI", "gpt-4.1", true);

        var subject = new ProviderFavoritesResolver(favoritesService);

        List<String> remaining = subject.excludeFavorites("OpenAI", List.of("gpt-4.1", "gpt-4o-mini"));

        assertThat(remaining).containsExactly("gpt-4o-mini");
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
