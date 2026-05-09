package com.github.drafael.chat4j.settings;

import lombok.NonNull;
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
            @NonNull ThemeResolver themeResolver,
            @NonNull SelectionSynchronizer selectionSynchronizer
    ) {
        this.themeResolver = themeResolver;
        this.selectionSynchronizer = selectionSynchronizer;
    }

    public String refresh(
            @NonNull Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            String previousSelection,
            boolean themesMenuBuilt,
            String defaultTheme
    ) {
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
