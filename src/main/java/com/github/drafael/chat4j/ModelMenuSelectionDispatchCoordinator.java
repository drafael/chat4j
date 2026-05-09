package com.github.drafael.chat4j;

import lombok.NonNull;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ModelMenuSelectionDispatchCoordinator {

    private final SelectionSyncAction selectionSyncAction;

    public ModelMenuSelectionDispatchCoordinator(ModelMenuSelectionSynchronizer modelMenuSelectionSynchronizer) {
        this(modelMenuSelectionSynchronizer::syncSelection);
    }

    ModelMenuSelectionDispatchCoordinator(@NonNull SelectionSyncAction selectionSyncAction) {
        this.selectionSyncAction = selectionSyncAction;
    }

    public String sync(
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            String selectedModelKey,
            String lastMenuSelectedModelKey,
            boolean modelsMenuDirty
    ) {

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
