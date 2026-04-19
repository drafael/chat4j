package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

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
            SelectionResolver selectionResolver,
            SelectionSynchronizer selectionSynchronizer
    ) {
        this.selectionResolver = Validate.notNull(selectionResolver, "selectionResolver must not be null");
        this.selectionSynchronizer = Validate.notNull(
                selectionSynchronizer,
                "selectionSynchronizer must not be null"
        );
    }

    public FontMenuSelectionSynchronizer.FontMenuSelectionState refresh(
            Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            FontMenuSelectionSynchronizer.FontMenuSelectionState previousSelection,
            boolean fontMenuBuilt
    ) {
        Validate.notNull(appFontMenuItemsByFamily, "appFontMenuItemsByFamily must not be null");
        Validate.notNull(appFontSizeMenuItemsBySize, "appFontSizeMenuItemsBySize must not be null");
        Validate.notNull(codeFontMenuItemsByFamily, "codeFontMenuItemsByFamily must not be null");

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
