package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ThemeMenuSelectionDispatchCoordinator {

    private final RefreshAction refreshAction;

    public ThemeMenuSelectionDispatchCoordinator(ThemeMenuSelectionRefreshCoordinator themeMenuSelectionRefreshCoordinator) {
        this(themeMenuSelectionRefreshCoordinator::refresh);
    }

    ThemeMenuSelectionDispatchCoordinator(RefreshAction refreshAction) {
        this.refreshAction = Validate.notNull(refreshAction, "refreshAction must not be null");
    }

    public String refresh(
            Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            String previousSelection,
            boolean themesMenuBuilt,
            String defaultTheme
    ) {
        Validate.notNull(themeMenuItemsByName, "themeMenuItemsByName must not be null");
        Validate.notBlank(defaultTheme, "defaultTheme must not be blank");

        return refreshAction.refresh(themeMenuItemsByName, previousSelection, themesMenuBuilt, defaultTheme);
    }

    @FunctionalInterface
    interface RefreshAction {
        String refresh(
                Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
                String previousSelection,
                boolean themesMenuBuilt,
                String defaultTheme
        );
    }
}
