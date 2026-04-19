package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Map;

public class ProviderMenuDataResolver {

    private final ProviderModelsResolver providerModelsResolver;
    private final ProviderSelectableResolver providerSelectableResolver;
    private final ProviderFavoritesResolver providerFavoritesResolver;
    private final ProviderAvailabilityResolver providerAvailabilityResolver;

    public ProviderMenuDataResolver(
            ProviderModelsResolver providerModelsResolver,
            ProviderSelectableResolver providerSelectableResolver,
            ProviderFavoritesResolver providerFavoritesResolver,
            ProviderAvailabilityResolver providerAvailabilityResolver
    ) {
        this.providerModelsResolver = Validate.notNull(providerModelsResolver, "providerModelsResolver must not be null");
        this.providerSelectableResolver = Validate.notNull(
                providerSelectableResolver,
                "providerSelectableResolver must not be null"
        );
        this.providerFavoritesResolver = Validate.notNull(
                providerFavoritesResolver,
                "providerFavoritesResolver must not be null"
        );
        this.providerAvailabilityResolver = Validate.notNull(
                providerAvailabilityResolver,
                "providerAvailabilityResolver must not be null"
        );
    }

    public ProviderMenuData resolve(List<ProviderRegistry.ProviderDef> providers) {
        Validate.notNull(providers, "providers must not be null");

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
