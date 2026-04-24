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

public class ProviderMenuStructureRebuilder {

    private final ProviderMenuDataResolver providerMenuDataResolver;
    private final ProviderFavoritesSectionAppender providerFavoritesSectionAppender;
    private final ProviderCatalogSectionAppender providerCatalogSectionAppender;
    private final ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory;

    public ProviderMenuStructureRebuilder(
            @NonNull ProviderMenuDataResolver providerMenuDataResolver,
            @NonNull ProviderFavoritesSectionAppender providerFavoritesSectionAppender,
            @NonNull ProviderCatalogSectionAppender providerCatalogSectionAppender,
            @NonNull ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory
    ) {
        this.providerMenuDataResolver = providerMenuDataResolver;
        this.providerFavoritesSectionAppender = providerFavoritesSectionAppender;
        this.providerCatalogSectionAppender = providerCatalogSectionAppender;
        this.providerMenuEmptyStateFactory = providerMenuEmptyStateFactory;
    }

    public void rebuild(
            @NonNull JMenu modelsMenu,
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            @NonNull Map<String, JMenuItem> providerHeaderItemsByName,
            @NonNull List<ProviderRegistry.ProviderDef> providers,
            @NonNull Consumer<String> onModelSelected
    ) {

        modelsMenu.removeAll();
        modelMenuItemsByKey.clear();
        providerHeaderItemsByName.clear();

        if (providers.isEmpty()) {
            modelsMenu.add(providerMenuEmptyStateFactory.noProvidersAvailableItem());
            return;
        }

        ProviderMenuDataResolver.ProviderMenuData menuData = providerMenuDataResolver.resolve(providers);

        ButtonGroup group = new ButtonGroup();
        boolean favoritesAppended = providerFavoritesSectionAppender.append(
                modelsMenu,
                group,
                modelMenuItemsByKey,
                menuData.favorites(),
                menuData.providerSelectable(),
                onModelSelected
        );
        boolean catalogAppended = providerCatalogSectionAppender.append(
                modelsMenu,
                group,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providers,
                menuData.modelsByProvider(),
                menuData.providerSelectable(),
                onModelSelected
        );

        if (favoritesAppended && !catalogAppended && modelsMenu.getMenuComponentCount() > 0) {
            int lastIndex = modelsMenu.getMenuComponentCount() - 1;
            if (modelsMenu.getMenuComponent(lastIndex) instanceof javax.swing.JSeparator) {
                modelsMenu.remove(lastIndex);
            }
        }
    }
}
