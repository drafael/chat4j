package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.support.ModelSelectionCodec.ModelSelection;
import lombok.NonNull;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ProviderFavoritesSectionAppender {

    private final ProviderModelMenuItemFactory providerModelMenuItemFactory;

    public ProviderFavoritesSectionAppender(@NonNull ProviderModelMenuItemFactory providerModelMenuItemFactory) {
        this.providerModelMenuItemFactory = providerModelMenuItemFactory;
    }

    public boolean append(
            @NonNull JMenu modelsMenu,
            @NonNull ButtonGroup group,
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            @NonNull List<ModelSelection> favorites,
            @NonNull Map<String, Boolean> providerSelectable,
            @NonNull Consumer<String> onModelSelected
    ) {

        if (favorites.isEmpty()) {
            return false;
        }

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
