package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderModelsResolverTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Resolve prefers cached models when cache contains entries")
    void resolve_whenCacheContainsModels_prefersCachedModels() {
        var modelCacheService = modelCacheService();
        String providerName = "TestProvider";
        updateModels(
                modelCacheService,
                providerName,
                "https://example.invalid",
                List.of("z-model", "a-model")
        );

        var subject = new ProviderModelsResolver(modelCacheService);
        var provider = provider(providerName, "https://example.invalid", List.of("seed-1"));

        Map<String, List<String>> modelsByProvider = subject.resolve(List.of(provider));

        assertThat(modelsByProvider).containsOnlyKeys(providerName);
        assertThat(modelsByProvider.get(providerName))
                .isEqualTo(ModelOrdering.sanitizeAndSortByProvider(providerName, List.of("z-model", "a-model")));
    }

    @Test
    @DisplayName("Resolve uses seed models instead of stale cached models after invalidation")
    void resolve_whenProviderIsInvalidated_usesSeedModels() {
        var modelCacheService = modelCacheService();
        String providerName = "InvalidatedProvider";
        updateModels(
                modelCacheService,
                providerName,
                "https://example.invalid",
                List.of("old-account-model")
        );
        modelCacheService.invalidate(providerName);

        var subject = new ProviderModelsResolver(modelCacheService);
        var provider = provider(providerName, "https://example.invalid", List.of("seed-model"));

        Map<String, List<String>> modelsByProvider = subject.resolve(List.of(provider));

        assertThat(modelsByProvider.get(providerName)).containsExactly("seed-model");
        assertThat(modelCacheService.getModels(providerName)).containsExactly("old-account-model");
    }

    @Test
    @DisplayName("Resolve uses Perplexity seed models even when stale cache entries exist")
    void resolve_whenPerplexityCacheContainsStaleModels_usesSeedModels() {
        var modelCacheService = modelCacheService();
        updateModels(modelCacheService, "Perplexity", "https://api.perplexity.ai", List.of("sonar-pro", "sonar"));

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
        var modelCacheService = modelCacheService();
        String providerName = "SeedProvider";

        var subject = new ProviderModelsResolver(modelCacheService);
        var provider = provider(providerName, "https://example.invalid", List.of("model-b", "model-a"));

        Map<String, List<String>> modelsByProvider = subject.resolve(List.of(provider));

        assertThat(modelsByProvider.get(providerName))
                .isEqualTo(ModelOrdering.sanitizeAndSortByProvider(providerName, List.of("model-b", "model-a")));
    }

    private ProviderModelCacheService modelCacheService() {
        return new ProviderModelCacheService(new ProviderModelCache(StoragePaths.ofConfigHome(tempDir)));
    }

    private static void updateModels(
            ProviderModelCacheService modelCacheService,
            String providerName,
            String scope,
            List<String> models
    ) {
        long scopeVersion = modelCacheService.nextScopeVersion();
        modelCacheService.synchronizeScope(providerName, scope, scopeVersion);
        ProviderModelCacheService.RefreshAttempt attempt = modelCacheService
                .tryBeginRefreshIfNeeded(providerName, scope, Duration.ZERO)
                .orElseThrow();
        assertThat(modelCacheService.update(attempt, models)).isTrue();
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
