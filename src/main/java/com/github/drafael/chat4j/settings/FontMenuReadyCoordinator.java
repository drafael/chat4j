package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class FontMenuReadyCoordinator {

    public void ensureReady(
        boolean fontMenuBuilt,
        @NonNull Runnable rebuildFontMenuStructure,
        @NonNull Runnable syncFontMenuSelection
) {

        if (!fontMenuBuilt) {
            rebuildFontMenuStructure.run();
        }

        syncFontMenuSelection.run();
    }
}
