package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;

public class ProviderMenuReadyDispatchCoordinator {

    private final EnsureReadyAction ensureReadyAction;

    public ProviderMenuReadyDispatchCoordinator(ProviderMenuReadyCoordinator providerMenuReadyCoordinator) {
        this(providerMenuReadyCoordinator::ensureReady);
    }

    ProviderMenuReadyDispatchCoordinator(@NonNull EnsureReadyAction ensureReadyAction) {
        this.ensureReadyAction = ensureReadyAction;
    }

    public void ensureReady(
            boolean modelsMenuDirty,
            @NonNull Runnable rebuildModelsMenuStructure,
            @NonNull Runnable refreshLocalProviderAvailabilityInMenu,
            @NonNull Runnable syncModelsMenuSelection
    ) {

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
