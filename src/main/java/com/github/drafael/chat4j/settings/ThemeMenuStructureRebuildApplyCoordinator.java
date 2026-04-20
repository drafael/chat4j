package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class ThemeMenuStructureRebuildApplyCoordinator {

    public ThemeMenuStructureRebuildCoordinator.RebuildState apply(
            ThemeMenuStructureRebuildCoordinator.RebuildState rebuildState,
            Consumer<Boolean> setThemesMenuBuilt,
            Consumer<String> setLastMenuSelectedTheme
    ) {
        Validate.notNull(rebuildState, "rebuildState must not be null");
        Validate.notNull(setThemesMenuBuilt, "setThemesMenuBuilt must not be null");
        Validate.notNull(setLastMenuSelectedTheme, "setLastMenuSelectedTheme must not be null");

        setThemesMenuBuilt.accept(rebuildState.themesMenuBuilt());
        setLastMenuSelectedTheme.accept(rebuildState.lastMenuSelectedTheme());
        return rebuildState;
    }
}
