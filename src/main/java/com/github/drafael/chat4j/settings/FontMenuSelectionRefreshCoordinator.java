package com.github.drafael.chat4j.settings;

import lombok.NonNull;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.Set;

public class FontMenuSelectionRefreshCoordinator {

    private final SelectionResolver selectionResolver;
    private final SelectionSynchronizer selectionSynchronizer;

    public FontMenuSelectionRefreshCoordinator(
            FontSettingsResolver fontSettingsResolver,
            FontMenuSelectionSynchronizer fontMenuSelectionSynchronizer
    ) {
        this(
                fontSettingsResolver::resolveMenuSelection,
                fontMenuSelectionSynchronizer::syncSelection
        );
    }

    FontMenuSelectionRefreshCoordinator(
            @NonNull SelectionResolver selectionResolver,
            @NonNull SelectionSynchronizer selectionSynchronizer
    ) {
        this.selectionResolver = selectionResolver;
        this.selectionSynchronizer = selectionSynchronizer;
    }

    public FontMenuSelectionSynchronizer.FontMenuSelectionState refresh(
            @NonNull Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            @NonNull Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            @NonNull Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            FontMenuSelectionSynchronizer.FontMenuSelectionState previousSelection,
            boolean fontMenuBuilt
    ) {

        FontSettingsResolver.FontMenuSelection fontMenuSelection = selectionResolver.resolve(
                appFontMenuItemsByFamily.keySet(),
                appFontSizeMenuItemsBySize.keySet(),
                codeFontMenuItemsByFamily.keySet()
        );

        return selectionSynchronizer.sync(
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                fontMenuSelection,
                previousSelection,
                fontMenuBuilt
        );
    }

    @FunctionalInterface
    interface SelectionResolver {
        FontSettingsResolver.FontMenuSelection resolve(
                Set<String> availableAppFontFamilies,
                Set<Integer> availableAppFontSizes,
                Set<String> availableCodeFontFamilies
        );
    }

    @FunctionalInterface
    interface SelectionSynchronizer {
        FontMenuSelectionSynchronizer.FontMenuSelectionState sync(
                Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
                Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
                Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
                FontSettingsResolver.FontMenuSelection currentSelection,
                FontMenuSelectionSynchronizer.FontMenuSelectionState previousSelection,
                boolean fontMenuBuilt
        );
    }
}
