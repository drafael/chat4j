package com.github.drafael.chat4j;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;

public class ModelMenuSelectionChangeCoordinator {

    public boolean onSelectedModelChanged(
            JMenu modelsMenu,
            boolean modelsMenuDirty,
            Runnable syncModelsMenuSelection
    ) {
        Validate.notNull(syncModelsMenuSelection, "syncModelsMenuSelection must not be null");

        if (modelsMenu == null || modelsMenuDirty) {
            return false;
        }

        syncModelsMenuSelection.run();
        return true;
    }
}
