package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

public class ProviderMenuReadyDispatchCoordinator {

    private final EnsureReadyAction ensureReadyAction;

    public ProviderMenuReadyDispatchCoordinator(ProviderMenuReadyCoordinator providerMenuReadyCoordinator) {
        this(providerMenuReadyCoordinator::ensureReady);
    }

    ProviderMenuReadyDispatchCoordinator(EnsureReadyAction ensureReadyAction) {
        this.ensureReadyAction = Validate.notNull(ensureReadyAction, "ensureReadyAction must not be null");
    }

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

        ensureReadyAction.ensureReady(
                modelsMenuDirty,
                rebuildModelsMenuStructure,
                refreshLocalProviderAvailabilityInMenu,
                syncModelsMenuSelection
        );
    }

    @FunctionalInterface
    interface EnsureReadyAction {
        void ensureReady(
                boolean modelsMenuDirty,
                Runnable rebuildModelsMenuStructure,
                Runnable refreshLocalProviderAvailabilityInMenu,
                Runnable syncModelsMenuSelection
        );
    }
}
