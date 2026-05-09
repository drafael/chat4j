package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

public class ProviderMenuDataResolver {

    private final ProviderModelsResolver providerModelsResolver;
    private final ProviderSelectableResolver providerSelectableResolver;
    private final ProviderFavoritesResolver providerFavoritesResolver;
    private final ProviderAvailabilityResolver providerAvailabilityResolver;

    public ProviderMenuDataResolver(
            @NonNull ProviderModelsResolver providerModelsResolver,
            @NonNull ProviderSelectableResolver providerSelectableResolver,
            @NonNull ProviderFavoritesResolver providerFavoritesResolver,
            @NonNull ProviderAvailabilityResolver providerAvailabilityResolver
    ) {
        this.providerModelsResolver = providerModelsResolver;
        this.providerSelectableResolver = providerSelectableResolver;
        this.providerFavoritesResolver = providerFavoritesResolver;
        this.providerAvailabilityResolver = providerAvailabilityResolver;
    }

    public ProviderMenuData resolve(@NonNull List<ProviderRegistry.ProviderDef> providers) {

        Map<String, List<String>> modelsByProvider = providerModelsResolver.resolve(providers);
        Map<String, Boolean> providerSelectable = providerSelectableResolver.resolve(
                providers,
                providerAvailabilityResolver::isModelSelectionEnabled
        );
        List<ModelSelectionCodec.ModelSelection> favorites =
                providerFavoritesResolver.resolveFavoriteSelections(providers, modelsByProvider);

        return new ProviderMenuData(modelsByProvider, providerSelectable, favorites);
    }

    public record ProviderMenuData(
            Map<String, List<String>> modelsByProvider,
            Map<String, Boolean> providerSelectable,
            List<ModelSelectionCodec.ModelSelection> favorites
    ) {
    }
}
