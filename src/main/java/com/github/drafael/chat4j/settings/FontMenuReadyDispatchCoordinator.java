package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class FontMenuReadyDispatchCoordinator {

    private final EnsureReadyAction ensureReadyAction;

    public FontMenuReadyDispatchCoordinator(FontMenuReadyCoordinator fontMenuReadyCoordinator) {
        this(fontMenuReadyCoordinator::ensureReady);
    }

    FontMenuReadyDispatchCoordinator(@NonNull EnsureReadyAction ensureReadyAction) {
        this.ensureReadyAction = ensureReadyAction;
    }

    public void ensureReady(
        boolean fontMenuBuilt,
        @NonNull Runnable rebuildFontMenuStructure,
        @NonNull Runnable syncFontMenuSelection
) {

        ensureReadyAction.ensureReady(fontMenuBuilt, rebuildFontMenuStructure, syncFontMenuSelection);
    }

    @FunctionalInterface
    interface EnsureReadyAction {
        void ensureReady(boolean fontMenuBuilt, Runnable rebuildFontMenuStructure, Runnable syncFontMenuSelection);
    }
}
