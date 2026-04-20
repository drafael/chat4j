package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class FontMenuStructureRebuildApplyCoordinator {

    public FontMenuStructureRebuildCoordinator.RebuildState apply(
            FontMenuStructureRebuildCoordinator.RebuildState rebuildState,
            Consumer<Boolean> setFontMenuBuilt,
            Consumer<String> setLastMenuSelectedAppFontFamily,
            Consumer<Integer> setLastMenuSelectedAppFontSize,
            Consumer<String> setLastMenuSelectedCodeFontFamily
    ) {
        Validate.notNull(rebuildState, "rebuildState must not be null");
        Validate.notNull(setFontMenuBuilt, "setFontMenuBuilt must not be null");
        Validate.notNull(
                setLastMenuSelectedAppFontFamily,
                "setLastMenuSelectedAppFontFamily must not be null"
        );
        Validate.notNull(setLastMenuSelectedAppFontSize, "setLastMenuSelectedAppFontSize must not be null");
        Validate.notNull(
                setLastMenuSelectedCodeFontFamily,
                "setLastMenuSelectedCodeFontFamily must not be null"
        );

        setFontMenuBuilt.accept(rebuildState.fontMenuBuilt());
        setLastMenuSelectedAppFontFamily.accept(rebuildState.lastMenuSelectedAppFontFamily());
        setLastMenuSelectedAppFontSize.accept(rebuildState.lastMenuSelectedAppFontSize());
        setLastMenuSelectedCodeFontFamily.accept(rebuildState.lastMenuSelectedCodeFontFamily());
        return rebuildState;
    }
}
