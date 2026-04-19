package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ThemeMenuSelectionRefreshCoordinator {

    private final ThemeResolver themeResolver;
    private final SelectionSynchronizer selectionSynchronizer;

    public ThemeMenuSelectionRefreshCoordinator(
            ThemeSettingsResolver themeSettingsResolver,
            ThemeMenuSelectionSynchronizer themeMenuSelectionSynchronizer
    ) {
        this(
                themeSettingsResolver::resolveSelectedTheme,
                themeMenuSelectionSynchronizer::syncSelection
        );
    }

    ThemeMenuSelectionRefreshCoordinator(
            ThemeResolver themeResolver,
            SelectionSynchronizer selectionSynchronizer
    ) {
        this.themeResolver = Validate.notNull(themeResolver, "themeResolver must not be null");
        this.selectionSynchronizer = Validate.notNull(selectionSynchronizer, "selectionSynchronizer must not be null");
    }

    public String refresh(
            Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            String previousSelection,
            boolean themesMenuBuilt,
            String defaultTheme
    ) {
        Validate.notNull(themeMenuItemsByName, "themeMenuItemsByName must not be null");
        Validate.notBlank(defaultTheme, "defaultTheme must not be blank");

        String selectedTheme = themeResolver.resolve(defaultTheme);
        return selectionSynchronizer.sync(themeMenuItemsByName, selectedTheme, previousSelection, themesMenuBuilt);
    }

    @FunctionalInterface
    interface ThemeResolver {
        String resolve(String defaultTheme);
    }

    @FunctionalInterface
    interface SelectionSynchronizer {
        String sync(
                Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
                String selectedTheme,
                String previousSelection,
                boolean themesMenuBuilt
        );
    }
}
