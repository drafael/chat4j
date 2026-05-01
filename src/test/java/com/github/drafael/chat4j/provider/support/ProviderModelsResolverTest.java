package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ModelCache;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.StoragePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderModelsResolverTest {

    @Test
    @DisplayName("Resolve prefers cached models when cache contains entries")
    void resolve_whenCacheContainsModels_prefersCachedModels() {
        var modelCacheService = new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths()));
        String providerName = "TestProvider-%s".formatted(UUID.randomUUID());
        modelCacheService.update(providerName, List.of("z-model", "a-model"));

        var subject = new ProviderModelsResolver(modelCacheService);
        var provider = provider(providerName, "https://example.invalid", List.of("seed-1"));

        Map<String, List<String>> modelsByProvider = subject.resolve(List.of(provider));

        assertThat(modelsByProvider).containsOnlyKeys(providerName);
        assertThat(modelsByProvider.get(providerName))
                .isEqualTo(ModelOrdering.sanitizeAndSortByProvider(providerName, List.of("z-model", "a-model")));
    }

    @Test
    @DisplayName("Resolve uses Perplexity seed models even when stale cache entries exist")
    void resolve_whenPerplexityCacheContainsStaleModels_usesSeedModels() {
        var modelCacheService = new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths()));
        modelCacheService.update("Perplexity", List.of("sonar-pro", "sonar"));

        var subject = new ProviderModelsResolver(modelCacheService);
        var provider = provider("Perplexity", "https://api.perplexity.ai", PerplexityModelIds.SONAR_MODELS);

        Map<String, List<String>> modelsByProvider = subject.resolve(List.of(provider));

        assertThat(modelsByProvider.get("Perplexity")).containsExactly(
                "sonar",
                "sonar-pro",
                "sonar-reasoning-pro",
                "sonar-deep-research"
        );
    }

    @Test
    @DisplayName("Resolve falls back to seed models when cache is empty")
    void resolve_whenCacheIsEmpty_usesSeedModels() {
        var modelCacheService = new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths()));
        String providerName = "SeedProvider-%s".formatted(UUID.randomUUID());

        var subject = new ProviderModelsResolver(modelCacheService);
        var provider = provider(providerName, "https://example.invalid", List.of("model-b", "model-a"));

        Map<String, List<String>> modelsByProvider = subject.resolve(List.of(provider));

        assertThat(modelsByProvider.get(providerName))
                .isEqualTo(ModelOrdering.sanitizeAndSortByProvider(providerName, List.of("model-b", "model-a")));
    }

    private ProviderRegistry.ProviderDef provider(String name, String baseUrl, List<String> seedModels) {
        return new ProviderRegistry.ProviderDef(
                name,
                "API_KEY",
                baseUrl,
                seedModels,
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }
}
