package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class FontMenuSelectionApplyCoordinator {

    public FontMenuSelectionSynchronizer.FontMenuSelectionState apply(
            FontMenuSelectionSynchronizer.FontMenuSelectionState selectionState,
            Consumer<String> setLastMenuSelectedAppFontFamily,
            Consumer<Integer> setLastMenuSelectedAppFontSize,
            Consumer<String> setLastMenuSelectedCodeFontFamily
    ) {
        Validate.notNull(selectionState, "selectionState must not be null");
        Validate.notNull(
                setLastMenuSelectedAppFontFamily,
                "setLastMenuSelectedAppFontFamily must not be null"
        );
        Validate.notNull(setLastMenuSelectedAppFontSize, "setLastMenuSelectedAppFontSize must not be null");
        Validate.notNull(
                setLastMenuSelectedCodeFontFamily,
                "setLastMenuSelectedCodeFontFamily must not be null"
        );

        setLastMenuSelectedAppFontFamily.accept(selectionState.appFontFamily());
        setLastMenuSelectedAppFontSize.accept(selectionState.appFontSize());
        setLastMenuSelectedCodeFontFamily.accept(selectionState.codeFontFamily());
        return selectionState;
    }
}
