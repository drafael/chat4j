package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class FontMenuReadyCoordinator {

    public void ensureReady(boolean fontMenuBuilt, Runnable rebuildFontMenuStructure, Runnable syncFontMenuSelection) {
        Validate.notNull(rebuildFontMenuStructure, "rebuildFontMenuStructure must not be null");
        Validate.notNull(syncFontMenuSelection, "syncFontMenuSelection must not be null");

        if (!fontMenuBuilt) {
            rebuildFontMenuStructure.run();
        }

        syncFontMenuSelection.run();
    }
}
