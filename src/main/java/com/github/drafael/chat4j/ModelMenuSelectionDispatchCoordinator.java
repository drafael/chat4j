package com.github.drafael.chat4j;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ModelMenuSelectionDispatchCoordinator {

    private final SelectionSyncAction selectionSyncAction;

    public ModelMenuSelectionDispatchCoordinator(ModelMenuSelectionSynchronizer modelMenuSelectionSynchronizer) {
        this(modelMenuSelectionSynchronizer::syncSelection);
    }

    ModelMenuSelectionDispatchCoordinator(SelectionSyncAction selectionSyncAction) {
        this.selectionSyncAction = Validate.notNull(selectionSyncAction, "selectionSyncAction must not be null");
    }

    public String sync(
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            String selectedModelKey,
            String lastMenuSelectedModelKey,
            boolean modelsMenuDirty
    ) {
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");

        return selectionSyncAction.sync(
                modelMenuItemsByKey,
                selectedModelKey,
                lastMenuSelectedModelKey,
                modelsMenuDirty
        );
    }

    @FunctionalInterface
    interface SelectionSyncAction {
        String sync(
                Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
                String selectedModelKey,
                String lastMenuSelectedModelKey,
                boolean modelsMenuDirty
        );
    }
}
