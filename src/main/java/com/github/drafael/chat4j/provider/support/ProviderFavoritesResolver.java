package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

public class ProviderFavoritesResolver {

    private final ModelFavoritesService modelFavoritesService;

    public ProviderFavoritesResolver(@NonNull ModelFavoritesService modelFavoritesService) {
        this.modelFavoritesService = modelFavoritesService;
    }

    public List<ModelSelectionCodec.ModelSelection> resolveFavoriteSelections(
            @NonNull List<ProviderRegistry.ProviderDef> providers,
            @NonNull Map<String, List<String>> modelsByProvider
    ) {

        return providers.stream()
                .flatMap(provider -> modelsByProvider.getOrDefault(provider.name(), emptyList()).stream()
                        .filter(model -> modelFavoritesService.isFavorite(provider.name(), model))
                        .map(model -> new ModelSelectionCodec.ModelSelection(provider.name(), model))
                )
                .toList();
    }

    public List<String> excludeFavorites(String providerName, @NonNull List<String> models) {
        Validate.notBlank(providerName, "providerName must not be blank");

        return models.stream()
                .filter(model -> !modelFavoritesService.isFavorite(providerName, model))
                .toList();
    }
}
