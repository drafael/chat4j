package com.github.drafael.chat4j.settings;

import lombok.NonNull;

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

    FontMenuSelectionFlowCoordinator(@NonNull RefreshAction refreshAction, @NonNull ApplyAction applyAction) {
        this.refreshAction = refreshAction;
        this.applyAction = applyAction;
    }

    public FontMenuSelectionSynchronizer.FontMenuSelectionState refreshAndApply(
            @NonNull Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            @NonNull Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            @NonNull Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            String lastMenuSelectedAppFontFamily,
            Integer lastMenuSelectedAppFontSize,
            String lastMenuSelectedCodeFontFamily,
            boolean fontMenuBuilt,
            @NonNull Consumer<String> setLastMenuSelectedAppFontFamily,
            @NonNull Consumer<Integer> setLastMenuSelectedAppFontSize,
            @NonNull Consumer<String> setLastMenuSelectedCodeFontFamily
    ) {

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
