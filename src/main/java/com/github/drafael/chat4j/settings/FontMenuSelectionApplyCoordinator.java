package com.github.drafael.chat4j.settings;


import lombok.NonNull;
import java.util.function.Consumer;

public class FontMenuSelectionApplyCoordinator {

    public FontMenuSelectionSynchronizer.FontMenuSelectionState apply(
            @NonNull FontMenuSelectionSynchronizer.FontMenuSelectionState selectionState,
            @NonNull Consumer<String> setLastMenuSelectedAppFontFamily,
            @NonNull Consumer<Integer> setLastMenuSelectedAppFontSize,
            @NonNull Consumer<String> setLastMenuSelectedCodeFontFamily
    ) {

        setLastMenuSelectedAppFontFamily.accept(selectionState.appFontFamily());
        setLastMenuSelectedAppFontSize.accept(selectionState.appFontSize());
        setLastMenuSelectedCodeFontFamily.accept(selectionState.codeFontFamily());
        return selectionState;
    }
}
