package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

public class ProviderMenuReadyCoordinator {

    public void ensureReady(
            boolean modelsMenuDirty,
            Runnable rebuildModelsMenuStructure,
            Runnable refreshLocalProviderAvailabilityInMenu,
            Runnable syncModelsMenuSelection
    ) {
        Validate.notNull(rebuildModelsMenuStructure, "rebuildModelsMenuStructure must not be null");
        Validate.notNull(
                refreshLocalProviderAvailabilityInMenu,
                "refreshLocalProviderAvailabilityInMenu must not be null"
        );
        Validate.notNull(syncModelsMenuSelection, "syncModelsMenuSelection must not be null");

        if (modelsMenuDirty) {
            rebuildModelsMenuStructure.run();
        }

        refreshLocalProviderAvailabilityInMenu.run();
        syncModelsMenuSelection.run();
    }
}
