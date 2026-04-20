package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.function.Consumer;

public class FontMenuSelectionFlowCoordinator {

    private final RefreshAction refreshAction;
    private final ApplyAction applyAction;

    public FontMenuSelectionFlowCoordinator(
            FontMenuSelectionDispatchCoordinator fontMenuSelectionDispatchCoordinator,
            FontMenuSelectionApplyCoordinator fontMenuSelectionApplyCoordinator
    ) {
        this(fontMenuSelectionDispatchCoordinator::refresh, fontMenuSelectionApplyCoordinator::apply);
    }

    FontMenuSelectionFlowCoordinator(RefreshAction refreshAction, ApplyAction applyAction) {
        this.refreshAction = Validate.notNull(refreshAction, "refreshAction must not be null");
        this.applyAction = Validate.notNull(applyAction, "applyAction must not be null");
    }

    public FontMenuSelectionSynchronizer.FontMenuSelectionState refreshAndApply(
            Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            String lastMenuSelectedAppFontFamily,
            Integer lastMenuSelectedAppFontSize,
            String lastMenuSelectedCodeFontFamily,
            boolean fontMenuBuilt,
            Consumer<String> setLastMenuSelectedAppFontFamily,
            Consumer<Integer> setLastMenuSelectedAppFontSize,
            Consumer<String> setLastMenuSelectedCodeFontFamily
    ) {
        Validate.notNull(appFontMenuItemsByFamily, "appFontMenuItemsByFamily must not be null");
        Validate.notNull(appFontSizeMenuItemsBySize, "appFontSizeMenuItemsBySize must not be null");
        Validate.notNull(codeFontMenuItemsByFamily, "codeFontMenuItemsByFamily must not be null");
        Validate.notNull(
                setLastMenuSelectedAppFontFamily,
                "setLastMenuSelectedAppFontFamily must not be null"
        );
        Validate.notNull(setLastMenuSelectedAppFontSize, "setLastMenuSelectedAppFontSize must not be null");
        Validate.notNull(
                setLastMenuSelectedCodeFontFamily,
                "setLastMenuSelectedCodeFontFamily must not be null"
        );

        FontMenuSelectionSynchronizer.FontMenuSelectionState syncedSelection = refreshAction.refresh(
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                lastMenuSelectedAppFontFamily,
                lastMenuSelectedAppFontSize,
                lastMenuSelectedCodeFontFamily,
                fontMenuBuilt
        );

        return applyAction.apply(
                syncedSelection,
                setLastMenuSelectedAppFontFamily,
                setLastMenuSelectedAppFontSize,
                setLastMenuSelectedCodeFontFamily
        );
    }

    @FunctionalInterface
    interface RefreshAction {
        FontMenuSelectionSynchronizer.FontMenuSelectionState refresh(
                Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
                Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
                Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
                String lastMenuSelectedAppFontFamily,
                Integer lastMenuSelectedAppFontSize,
                String lastMenuSelectedCodeFontFamily,
                boolean fontMenuBuilt
        );
    }

    @FunctionalInterface
    interface ApplyAction {
        FontMenuSelectionSynchronizer.FontMenuSelectionState apply(
                FontMenuSelectionSynchronizer.FontMenuSelectionState selectionState,
                Consumer<String> setLastMenuSelectedAppFontFamily,
                Consumer<Integer> setLastMenuSelectedAppFontSize,
                Consumer<String> setLastMenuSelectedCodeFontFamily
        );
    }
}
