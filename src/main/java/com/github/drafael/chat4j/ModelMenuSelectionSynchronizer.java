package com.github.drafael.chat4j;


import lombok.NonNull;
import javax.swing.ButtonGroup;
import javax.swing.DefaultButtonModel;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

public class ModelMenuSelectionSynchronizer {

    public String syncSelection(
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            String selectedModelKey,
            String lastSelectedModelKey,
            boolean modelsMenuDirty
    ) {

        if (modelsMenuDirty) {
            return lastSelectedModelKey;
        }

        Set<ButtonGroup> groups = modelMenuItemsByKey.values().stream()
                .map(JRadioButtonMenuItem::getModel)
                .filter(DefaultButtonModel.class::isInstance)
                .map(DefaultButtonModel.class::cast)
                .map(DefaultButtonModel::getGroup)
                .filter(Objects::nonNull)
                .collect(toSet());
        groups.forEach(ButtonGroup::clearSelection);
        modelMenuItemsByKey.values().stream()
                .filter(item -> !(item.getModel() instanceof DefaultButtonModel model) || model.getGroup() == null)
                .forEach(item -> item.setSelected(false));

        JRadioButtonMenuItem selectedItem = modelMenuItemsByKey.get(selectedModelKey);
        if (selectedItem != null) {
            selectedItem.setSelected(true);
        }
        return selectedModelKey;
    }
}
