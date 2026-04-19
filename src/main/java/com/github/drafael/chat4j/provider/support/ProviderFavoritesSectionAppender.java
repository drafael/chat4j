package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.menu.MenuSectionHeaderFactory;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec.ModelSelection;
import org.apache.commons.lang3.Validate;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ProviderFavoritesSectionAppender {

    private static final String FAVORITES_HEADER = "★ Favorites";

    private final MenuSectionHeaderFactory menuSectionHeaderFactory;
    private final ProviderModelMenuItemFactory providerModelMenuItemFactory;

    public ProviderFavoritesSectionAppender(
            MenuSectionHeaderFactory menuSectionHeaderFactory,
            ProviderModelMenuItemFactory providerModelMenuItemFactory
    ) {
        this.menuSectionHeaderFactory = Validate.notNull(menuSectionHeaderFactory, "menuSectionHeaderFactory must not be null");
        this.providerModelMenuItemFactory = Validate.notNull(
                providerModelMenuItemFactory,
                "providerModelMenuItemFactory must not be null"
        );
    }

    public boolean append(
            JMenu modelsMenu,
            ButtonGroup group,
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            List<ModelSelection> favorites,
            Map<String, Boolean> providerSelectable,
            Consumer<String> onModelSelected
    ) {
        Validate.notNull(modelsMenu, "modelsMenu must not be null");
        Validate.notNull(group, "group must not be null");
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");
        Validate.notNull(favorites, "favorites must not be null");
        Validate.notNull(providerSelectable, "providerSelectable must not be null");
        Validate.notNull(onModelSelected, "onModelSelected must not be null");

        if (favorites.isEmpty()) {
            return false;
        }

        modelsMenu.add(menuSectionHeaderFactory.create(FAVORITES_HEADER));

        favorites.forEach(selection -> {
            boolean selectable = providerSelectable.getOrDefault(selection.provider(), true);
            ProviderModelMenuItemFactory.CreatedModelItem createdItem = providerModelMenuItemFactory.create(
                    selection.provider(),
                    selection.model(),
                    selectable,
                    onModelSelected
            );
            group.add(createdItem.item());
            modelsMenu.add(createdItem.item());
            modelMenuItemsByKey.put(createdItem.modelKey(), createdItem.item());
        });

        modelsMenu.addSeparator();
        return true;
    }
}
