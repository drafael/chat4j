package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class FontMenuSelectionDispatchCoordinator {

    private final RefreshAction refreshAction;

    public FontMenuSelectionDispatchCoordinator(FontMenuSelectionRefreshCoordinator fontMenuSelectionRefreshCoordinator) {
        this(fontMenuSelectionRefreshCoordinator::refresh);
    }

    FontMenuSelectionDispatchCoordinator(RefreshAction refreshAction) {
        this.refreshAction = Validate.notNull(refreshAction, "refreshAction must not be null");
    }

    public FontMenuSelectionSynchronizer.FontMenuSelectionState refresh(
            Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            String lastMenuSelectedAppFontFamily,
            Integer lastMenuSelectedAppFontSize,
            String lastMenuSelectedCodeFontFamily,
            boolean fontMenuBuilt
    ) {
        Validate.notNull(appFontMenuItemsByFamily, "appFontMenuItemsByFamily must not be null");
        Validate.notNull(appFontSizeMenuItemsBySize, "appFontSizeMenuItemsBySize must not be null");
        Validate.notNull(codeFontMenuItemsByFamily, "codeFontMenuItemsByFamily must not be null");

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
