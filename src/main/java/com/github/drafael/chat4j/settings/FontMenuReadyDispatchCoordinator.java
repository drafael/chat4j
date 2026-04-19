package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class FontMenuReadyDispatchCoordinator {

    private final EnsureReadyAction ensureReadyAction;

    public FontMenuReadyDispatchCoordinator(FontMenuReadyCoordinator fontMenuReadyCoordinator) {
        this(fontMenuReadyCoordinator::ensureReady);
    }

    FontMenuReadyDispatchCoordinator(EnsureReadyAction ensureReadyAction) {
        this.ensureReadyAction = Validate.notNull(ensureReadyAction, "ensureReadyAction must not be null");
    }

    public void ensureReady(boolean fontMenuBuilt, Runnable rebuildFontMenuStructure, Runnable syncFontMenuSelection) {
        Validate.notNull(rebuildFontMenuStructure, "rebuildFontMenuStructure must not be null");
        Validate.notNull(syncFontMenuSelection, "syncFontMenuSelection must not be null");

        ensureReadyAction.ensureReady(fontMenuBuilt, rebuildFontMenuStructure, syncFontMenuSelection);
    }

    @FunctionalInterface
    interface EnsureReadyAction {
        void ensureReady(boolean fontMenuBuilt, Runnable rebuildFontMenuStructure, Runnable syncFontMenuSelection);
    }
}
