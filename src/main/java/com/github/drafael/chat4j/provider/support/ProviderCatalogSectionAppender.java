package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import lombok.NonNull;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;

public class ProviderCatalogSectionAppender {

    private final ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter;
    private final ProviderHeaderMenuItemFactory providerHeaderMenuItemFactory;
    private final ProviderFavoritesResolver providerFavoritesResolver;
    private final ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory;
    private final ProviderModelMenuItemFactory providerModelMenuItemFactory;

    public ProviderCatalogSectionAppender(
            @NonNull ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter,
            @NonNull ProviderHeaderMenuItemFactory providerHeaderMenuItemFactory,
            @NonNull ProviderFavoritesResolver providerFavoritesResolver,
            @NonNull ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory,
            @NonNull ProviderModelMenuItemFactory providerModelMenuItemFactory
    ) {
        this.providerAvailabilityLabelFormatter = providerAvailabilityLabelFormatter;
        this.providerHeaderMenuItemFactory = providerHeaderMenuItemFactory;
        this.providerFavoritesResolver = providerFavoritesResolver;
        this.providerMenuEmptyStateFactory = providerMenuEmptyStateFactory;
        this.providerModelMenuItemFactory = providerModelMenuItemFactory;
    }

    public boolean append(
            @NonNull JMenu modelsMenu,
            @NonNull ButtonGroup group,
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            @NonNull Map<String, JMenuItem> providerHeaderItemsByName,
            @NonNull List<ProviderRegistry.ProviderDef> providers,
            @NonNull Map<String, List<String>> modelsByProvider,
            @NonNull Map<String, Boolean> providerSelectable,
            @NonNull Consumer<String> onModelSelected
    ) {

        boolean appended = false;
        for (ProviderRegistry.ProviderDef provider : providers) {
            List<String> availableModels = modelsByProvider.getOrDefault(provider.name(), emptyList());
            List<String> models = providerFavoritesResolver.excludeFavorites(provider.name(), availableModels);
            if (models.isEmpty() && !availableModels.isEmpty()) {
                continue;
            }

            if (appended) {
                modelsMenu.addSeparator();
            }

            boolean providerEnabled = providerSelectable.getOrDefault(provider.name(), true);
            if (availableModels.isEmpty()) {
                String providerLabel = providerAvailabilityLabelFormatter.format(provider.name(), providerEnabled);
                JMenuItem providerHeader = providerHeaderMenuItemFactory.create(
                        provider.name(),
                        providerLabel,
                        providerEnabled
                );
                modelsMenu.add(providerHeader);
                providerHeaderItemsByName.put(provider.name(), providerHeader);
                modelsMenu.add(providerMenuEmptyStateFactory.noModelsAvailableItem());
                appended = true;
                continue;
            }

            models.forEach(model -> {
                ProviderModelMenuItemFactory.CreatedModelItem createdItem = providerModelMenuItemFactory.create(
                        provider.name(),
                        model,
                        providerEnabled,
                        onModelSelected
                );
                group.add(createdItem.item());
                modelsMenu.add(createdItem.item());
                modelMenuItemsByKey.put(createdItem.modelKey(), createdItem.item());
            });
            appended = true;
        }

        return appended;
    }
}
