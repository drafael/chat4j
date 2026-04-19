package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

public class ProviderFavoritesResolver {

    private final ModelFavoritesService modelFavoritesService;

    public ProviderFavoritesResolver(ModelFavoritesService modelFavoritesService) {
        this.modelFavoritesService = Validate.notNull(modelFavoritesService, "modelFavoritesService must not be null");
    }

    public List<ModelSelectionCodec.ModelSelection> resolveFavoriteSelections(
            List<ProviderRegistry.ProviderDef> providers,
            Map<String, List<String>> modelsByProvider
    ) {
        Validate.notNull(providers, "providers must not be null");
        Validate.notNull(modelsByProvider, "modelsByProvider must not be null");

        return providers.stream()
                .flatMap(provider -> modelsByProvider.getOrDefault(provider.name(), emptyList()).stream()
                        .filter(model -> modelFavoritesService.isFavorite(provider.name(), model))
                        .map(model -> new ModelSelectionCodec.ModelSelection(provider.name(), model))
                )
                .toList();
    }

    public List<String> excludeFavorites(String providerName, List<String> models) {
        Validate.notBlank(providerName, "providerName must not be blank");
        Validate.notNull(models, "models must not be null");

        return models.stream()
                .filter(model -> !modelFavoritesService.isFavorite(providerName, model))
                .toList();
    }
}
