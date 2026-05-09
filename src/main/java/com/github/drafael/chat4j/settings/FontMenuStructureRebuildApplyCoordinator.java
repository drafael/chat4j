package com.github.drafael.chat4j.settings;


import lombok.NonNull;
import java.util.function.Consumer;

public class FontMenuStructureRebuildApplyCoordinator {

    public FontMenuStructureRebuildCoordinator.RebuildState apply(
            @NonNull FontMenuStructureRebuildCoordinator.RebuildState rebuildState,
            @NonNull Consumer<Boolean> setFontMenuBuilt,
            @NonNull Consumer<String> setLastMenuSelectedAppFontFamily,
            @NonNull Consumer<Integer> setLastMenuSelectedAppFontSize,
            @NonNull Consumer<String> setLastMenuSelectedCodeFontFamily
    ) {

        setFontMenuBuilt.accept(rebuildState.fontMenuBuilt());
        setLastMenuSelectedAppFontFamily.accept(rebuildState.lastMenuSelectedAppFontFamily());
        setLastMenuSelectedAppFontSize.accept(rebuildState.lastMenuSelectedAppFontSize());
        setLastMenuSelectedCodeFontFamily.accept(rebuildState.lastMenuSelectedCodeFontFamily());
        return rebuildState;
    }
}
