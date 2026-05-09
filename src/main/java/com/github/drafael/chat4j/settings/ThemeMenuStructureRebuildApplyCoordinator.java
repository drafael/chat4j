package com.github.drafael.chat4j.settings;


import lombok.NonNull;
import java.util.function.Consumer;

public class ThemeMenuStructureRebuildApplyCoordinator {

    public ThemeMenuStructureRebuildCoordinator.RebuildState apply(
            @NonNull ThemeMenuStructureRebuildCoordinator.RebuildState rebuildState,
            @NonNull Consumer<Boolean> setThemesMenuBuilt,
            @NonNull Consumer<String> setLastMenuSelectedTheme
    ) {

        setThemesMenuBuilt.accept(rebuildState.themesMenuBuilt());
        setLastMenuSelectedTheme.accept(rebuildState.lastMenuSelectedTheme());
        return rebuildState;
    }
}
