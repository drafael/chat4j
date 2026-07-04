package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.db.StoragePaths;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderMenuDataResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve composes models, selectable flags, and favorites from collaborators")
    void resolve_whenCalled_composesMenuDataFromCollaborators() throws Exception {
        List<ProviderRegistry.ProviderDef> providers = List.of(provider("OpenAI"), provider("Ollama"));

        Map<String, List<String>> modelsByProvider = new LinkedHashMap<>();
        modelsByProvider.put("OpenAI", List.of("gpt-4.1"));
        modelsByProvider.put("Ollama", List.of("llama3.2"));

        var modelsResolver = new StubProviderModelsResolver(modelsByProvider);
        var favoritesResolver = new StubProviderFavoritesResolver(List.of(
                new ModelSelectionCodec.ModelSelection("OpenAI", "gpt-4.1")
        ));
        var selectableResolver = new ProviderSelectableResolver();
        var availabilityResolver = new StubProviderAvailabilityResolver(settingsRepo("provider-menu-data"));

        var subject = new ProviderMenuDataResolver(
                modelsResolver,
                selectableResolver,
                favoritesResolver,
                availabilityResolver
        );

        ProviderMenuDataResolver.ProviderMenuData menuData = subject.resolve(providers);

        assertThat(modelsResolver.lastProviders).isEqualTo(providers);
        assertThat(favoritesResolver.lastProviders).isEqualTo(providers);
        assertThat(favoritesResolver.lastModelsByProvider).isEqualTo(modelsByProvider);

        assertThat(menuData.modelsByProvider()).isEqualTo(modelsByProvider);
        assertThat(menuData.providerSelectable()).containsEntry("OpenAI", true).containsEntry("Ollama", false);
        assertThat(menuData.favorites())
                .containsExactly(new ModelSelectionCodec.ModelSelection("OpenAI", "gpt-4.1"));
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

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private static class StubProviderModelsResolver extends ProviderModelsResolver {

        private final Map<String, List<String>> modelsByProvider;
        private List<ProviderRegistry.ProviderDef> lastProviders;

        private StubProviderModelsResolver(Map<String, List<String>> modelsByProvider) {
            super(new ProviderModelCacheService(new ProviderModelCache(StoragePaths.defaultPaths())));
            this.modelsByProvider = modelsByProvider;
        }

        @Override
        public Map<String, List<String>> resolve(List<ProviderRegistry.ProviderDef> providers) {
            this.lastProviders = providers;
            return modelsByProvider;
        }
    }

    private static class StubProviderFavoritesResolver extends ProviderFavoritesResolver {

        private final List<ModelSelectionCodec.ModelSelection> favorites;
        private List<ProviderRegistry.ProviderDef> lastProviders;
        private Map<String, List<String>> lastModelsByProvider;

        private StubProviderFavoritesResolver(List<ModelSelectionCodec.ModelSelection> favorites) {
            super(ModelFavoritesService.createInMemory());
            this.favorites = favorites;
        }

        @Override
        public List<ModelSelectionCodec.ModelSelection> resolveFavoriteSelections(
                List<ProviderRegistry.ProviderDef> providers,
                Map<String, List<String>> modelsByProvider
        ) {
            this.lastProviders = providers;
            this.lastModelsByProvider = modelsByProvider;
            return favorites;
        }
    }

    private static class StubProviderAvailabilityResolver extends ProviderAvailabilityResolver {

        private StubProviderAvailabilityResolver(SettingsRepository settingsRepo) {
            super(settingsRepo);
        }

        @Override
        public boolean isModelSelectionEnabled(ProviderRegistry.ProviderDef providerDef) {
            return !"Ollama".equals(providerDef.name());
        }
    }
}
