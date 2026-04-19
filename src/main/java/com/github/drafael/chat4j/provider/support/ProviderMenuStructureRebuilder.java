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

public class ProviderMenuStructureRebuilder {

    private final ProviderMenuDataResolver providerMenuDataResolver;
    private final ProviderFavoritesSectionAppender providerFavoritesSectionAppender;
    private final ProviderCatalogSectionAppender providerCatalogSectionAppender;
    private final ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory;

    public ProviderMenuStructureRebuilder(
            ProviderMenuDataResolver providerMenuDataResolver,
            ProviderFavoritesSectionAppender providerFavoritesSectionAppender,
            ProviderCatalogSectionAppender providerCatalogSectionAppender,
            ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory
    ) {
        this.providerMenuDataResolver = Validate.notNull(
                providerMenuDataResolver,
                "providerMenuDataResolver must not be null"
        );
        this.providerFavoritesSectionAppender = Validate.notNull(
                providerFavoritesSectionAppender,
                "providerFavoritesSectionAppender must not be null"
        );
        this.providerCatalogSectionAppender = Validate.notNull(
                providerCatalogSectionAppender,
                "providerCatalogSectionAppender must not be null"
        );
        this.providerMenuEmptyStateFactory = Validate.notNull(
                providerMenuEmptyStateFactory,
                "providerMenuEmptyStateFactory must not be null"
        );
    }

    public boolean rebuild(
            JMenu modelsMenu,
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            Map<String, JMenuItem> providerHeaderItemsByName,
            List<ProviderRegistry.ProviderDef> providers,
            Consumer<String> onModelSelected
    ) {
        Validate.notNull(modelsMenu, "modelsMenu must not be null");
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");
        Validate.notNull(providerHeaderItemsByName, "providerHeaderItemsByName must not be null");
        Validate.notNull(providers, "providers must not be null");
        Validate.notNull(onModelSelected, "onModelSelected must not be null");

        modelsMenu.removeAll();
        modelMenuItemsByKey.clear();
        providerHeaderItemsByName.clear();

        if (providers.isEmpty()) {
            modelsMenu.add(providerMenuEmptyStateFactory.noProvidersAvailableItem());
            return false;
        }

        ProviderMenuDataResolver.ProviderMenuData menuData = providerMenuDataResolver.resolve(providers);

        ButtonGroup group = new ButtonGroup();
        providerFavoritesSectionAppender.append(
                modelsMenu,
                group,
                modelMenuItemsByKey,
                menuData.favorites(),
                menuData.providerSelectable(),
                onModelSelected
        );
        providerCatalogSectionAppender.append(
                modelsMenu,
                group,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providers,
                menuData.modelsByProvider(),
                menuData.providerSelectable(),
                onModelSelected
        );
        return true;
    }
}
