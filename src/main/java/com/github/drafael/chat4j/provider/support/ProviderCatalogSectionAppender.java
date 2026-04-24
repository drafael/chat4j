package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.Validate;

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
            ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter,
            ProviderHeaderMenuItemFactory providerHeaderMenuItemFactory,
            ProviderFavoritesResolver providerFavoritesResolver,
            ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory,
            ProviderModelMenuItemFactory providerModelMenuItemFactory
    ) {
        this.providerAvailabilityLabelFormatter = Validate.notNull(
                providerAvailabilityLabelFormatter,
                "providerAvailabilityLabelFormatter must not be null"
        );
        this.providerHeaderMenuItemFactory = Validate.notNull(
                providerHeaderMenuItemFactory,
                "providerHeaderMenuItemFactory must not be null"
        );
        this.providerFavoritesResolver = Validate.notNull(
                providerFavoritesResolver,
                "providerFavoritesResolver must not be null"
        );
        this.providerMenuEmptyStateFactory = Validate.notNull(
                providerMenuEmptyStateFactory,
                "providerMenuEmptyStateFactory must not be null"
        );
        this.providerModelMenuItemFactory = Validate.notNull(
                providerModelMenuItemFactory,
                "providerModelMenuItemFactory must not be null"
        );
    }

    public boolean append(
            JMenu modelsMenu,
            ButtonGroup group,
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            Map<String, JMenuItem> providerHeaderItemsByName,
            List<ProviderRegistry.ProviderDef> providers,
            Map<String, List<String>> modelsByProvider,
            Map<String, Boolean> providerSelectable,
            Consumer<String> onModelSelected
    ) {
        Validate.notNull(modelsMenu, "modelsMenu must not be null");
        Validate.notNull(group, "group must not be null");
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");
        Validate.notNull(providerHeaderItemsByName, "providerHeaderItemsByName must not be null");
        Validate.notNull(providers, "providers must not be null");
        Validate.notNull(modelsByProvider, "modelsByProvider must not be null");
        Validate.notNull(providerSelectable, "providerSelectable must not be null");
        Validate.notNull(onModelSelected, "onModelSelected must not be null");

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
