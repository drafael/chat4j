package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class ThemeMenuReadyCoordinator {

    public void ensureReady(
        boolean themesMenuBuilt,
        @NonNull Runnable rebuildThemesMenuStructure,
        @NonNull Runnable syncThemeMenuSelection
) {

        if (!themesMenuBuilt) {
            rebuildThemesMenuStructure.run();
        }

        syncThemeMenuSelection.run();
    }
}
