package com.github.drafael.chat4j.settings;

import lombok.NonNull;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class FontMenuSelectionDispatchCoordinator {

    private final RefreshAction refreshAction;

    public FontMenuSelectionDispatchCoordinator(FontMenuSelectionRefreshCoordinator fontMenuSelectionRefreshCoordinator) {
        this(fontMenuSelectionRefreshCoordinator::refresh);
    }

    FontMenuSelectionDispatchCoordinator(@NonNull RefreshAction refreshAction) {
        this.refreshAction = refreshAction;
    }

    public FontMenuSelectionSynchronizer.FontMenuSelectionState refresh(
            @NonNull Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            @NonNull Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            @NonNull Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            String lastMenuSelectedAppFontFamily,
            Integer lastMenuSelectedAppFontSize,
            String lastMenuSelectedCodeFontFamily,
            boolean fontMenuBuilt
    ) {

        return refreshAction.refresh(
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                new FontMenuSelectionSynchronizer.FontMenuSelectionState(
                        lastMenuSelectedAppFontFamily,
                        lastMenuSelectedAppFontSize,
                        lastMenuSelectedCodeFontFamily
                ),
                fontMenuBuilt
        );
    }

    @FunctionalInterface
    interface RefreshAction {
        FontMenuSelectionSynchronizer.FontMenuSelectionState refresh(
                Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
                Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
                Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
                FontMenuSelectionSynchronizer.FontMenuSelectionState previousSelection,
                boolean fontMenuBuilt
        );
    }
}
