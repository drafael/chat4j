package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class ThemeMenuReadyCoordinator {

    public void ensureReady(boolean themesMenuBuilt, Runnable rebuildThemesMenuStructure, Runnable syncThemeMenuSelection) {
        Validate.notNull(rebuildThemesMenuStructure, "rebuildThemesMenuStructure must not be null");
        Validate.notNull(syncThemeMenuSelection, "syncThemeMenuSelection must not be null");

        if (!themesMenuBuilt) {
            rebuildThemesMenuStructure.run();
        }

        syncThemeMenuSelection.run();
    }
}
