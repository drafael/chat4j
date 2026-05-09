package com.github.drafael.chat4j;


import lombok.NonNull;
import javax.swing.JMenu;

public class ModelMenuSelectionChangeCoordinator {

    public boolean onSelectedModelChanged(
            JMenu modelsMenu,
            boolean modelsMenuDirty,
            @NonNull Runnable syncModelsMenuSelection
    ) {

        if (modelsMenu == null || modelsMenuDirty) {
            return false;
        }

        syncModelsMenuSelection.run();
        return true;
    }
}
