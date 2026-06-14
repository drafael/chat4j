package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderAvailabilityLabelFormatter;
import com.github.drafael.chat4j.provider.support.ProviderAvailabilityResolver;
import com.github.drafael.chat4j.provider.support.ProviderCatalogSectionAppender;
import com.github.drafael.chat4j.provider.support.ProviderFavoritesResolver;
import com.github.drafael.chat4j.provider.support.ProviderFavoritesSectionAppender;
import com.github.drafael.chat4j.provider.support.ProviderHeaderMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityApplier;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshDispatchCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuDataResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuEmptyStateFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuStructureRebuilder;
import com.github.drafael.chat4j.provider.support.ProviderModelMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderModelsResolver;
import com.github.drafael.chat4j.provider.support.ProviderSelectableResolver;
import com.github.drafael.chat4j.settings.ProviderRuntimeSettingsResolver;
import com.github.drafael.chat4j.settings.ProviderSettingsApplyCoordinator;
import lombok.NonNull;

public class MainFrameProviderMenuWiringFactory {

    public ProviderMenuWiring create(
            @NonNull SettingsRepository settingsRepo,
            @NonNull ProviderModelCacheService modelCacheService,
            @NonNull ModelFavoritesService modelFavoritesService,
            @NonNull ProviderSelectableResolver providerSelectableResolver,
            @NonNull ProviderMenuAvailabilityApplier providerMenuAvailabilityApplier,
            @NonNull ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter,
            @NonNull ProviderHeaderMenuItemFactory providerHeaderMenuItemFactory,
            @NonNull ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory,
            @NonNull ProviderModelMenuItemFactory providerModelMenuItemFactory,
            @NonNull ProviderFavoritesSectionAppender providerFavoritesSectionAppender
    ) {

        var providerSettingsApplyCoordinator =
                new ProviderSettingsApplyCoordinator(new ProviderRuntimeSettingsResolver(settingsRepo));
        var providerModelsResolver = new ProviderModelsResolver(modelCacheService);
        var providerFavoritesResolver = new ProviderFavoritesResolver(modelFavoritesService);
        var providerAvailabilityResolver = new ProviderAvailabilityResolver(settingsRepo);
        var providerMenuDataResolver = new ProviderMenuDataResolver(
                providerModelsResolver,
                providerSelectableResolver,
                providerFavoritesResolver,
                providerAvailabilityResolver
        );
        var providerMenuAvailabilityRefreshCoordinator = new ProviderMenuAvailabilityRefreshCoordinator(
                providerAvailabilityResolver::resolveMenuAvailability,
                providerMenuAvailabilityApplier
        );
        var providerMenuAvailabilityRefreshDispatchCoordinator =
                new ProviderMenuAvailabilityRefreshDispatchCoordinator(providerMenuAvailabilityRefreshCoordinator);
        var providerCatalogSectionAppender = new ProviderCatalogSectionAppender(
                providerAvailabilityLabelFormatter,
                providerHeaderMenuItemFactory,
                providerFavoritesResolver,
                providerMenuEmptyStateFactory,
                providerModelMenuItemFactory
        );
        var providerMenuStructureRebuilder = new ProviderMenuStructureRebuilder(
                providerMenuDataResolver,
                providerFavoritesSectionAppender,
                providerCatalogSectionAppender,
                providerMenuEmptyStateFactory
        );
        var modelMenuStructureRebuildCoordinator =
                new ModelMenuStructureRebuildCoordinator(providerMenuStructureRebuilder);

        return new ProviderMenuWiring(
                providerSettingsApplyCoordinator,
                providerModelsResolver,
                providerFavoritesResolver,
                providerAvailabilityResolver,
                providerMenuDataResolver,
                providerMenuAvailabilityRefreshCoordinator,
                providerMenuAvailabilityRefreshDispatchCoordinator,
                providerCatalogSectionAppender,
                providerMenuStructureRebuilder,
                modelMenuStructureRebuildCoordinator
        );
    }

    public record ProviderMenuWiring(
            ProviderSettingsApplyCoordinator providerSettingsApplyCoordinator,
            ProviderModelsResolver providerModelsResolver,
            ProviderFavoritesResolver providerFavoritesResolver,
            ProviderAvailabilityResolver providerAvailabilityResolver,
            ProviderMenuDataResolver providerMenuDataResolver,
            ProviderMenuAvailabilityRefreshCoordinator providerMenuAvailabilityRefreshCoordinator,
            ProviderMenuAvailabilityRefreshDispatchCoordinator providerMenuAvailabilityRefreshDispatchCoordinator,
            ProviderCatalogSectionAppender providerCatalogSectionAppender,
            ProviderMenuStructureRebuilder providerMenuStructureRebuilder,
            ModelMenuStructureRebuildCoordinator modelMenuStructureRebuildCoordinator
    ) {
    }
}
