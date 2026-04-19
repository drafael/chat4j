package com.github.drafael.chat4j;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.Objects;

public class ModelMenuSelectionSynchronizer {

    public String syncSelection(
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            String selectedModelKey,
            String lastSelectedModelKey,
            boolean modelsMenuDirty
    ) {
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");

        if (modelsMenuDirty) {
            return lastSelectedModelKey;
        }

        if (Objects.equals(selectedModelKey, lastSelectedModelKey)) {
            return lastSelectedModelKey;
        }

        if (lastSelectedModelKey != null) {
            JRadioButtonMenuItem previous = modelMenuItemsByKey.get(lastSelectedModelKey);
            if (previous != null) {
                previous.setSelected(false);
            }
        }

        if (selectedModelKey != null) {
            JRadioButtonMenuItem current = modelMenuItemsByKey.get(selectedModelKey);
            if (current != null) {
                current.setSelected(true);
            }
        }

        return selectedModelKey;
    }
}
