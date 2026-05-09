package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;

public class ProviderMenuReadyCoordinator {

    public void ensureReady(
            boolean modelsMenuDirty,
            @NonNull Runnable rebuildModelsMenuStructure,
            @NonNull Runnable refreshLocalProviderAvailabilityInMenu,
            @NonNull Runnable syncModelsMenuSelection
    ) {

        if (modelsMenuDirty) {
            rebuildModelsMenuStructure.run();
        }

        refreshLocalProviderAvailabilityInMenu.run();
        syncModelsMenuSelection.run();
    }
}
