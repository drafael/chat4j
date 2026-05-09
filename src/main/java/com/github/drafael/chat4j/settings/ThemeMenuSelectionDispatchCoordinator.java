package com.github.drafael.chat4j.settings;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ThemeMenuSelectionDispatchCoordinator {

    private final RefreshAction refreshAction;

    public ThemeMenuSelectionDispatchCoordinator(ThemeMenuSelectionRefreshCoordinator themeMenuSelectionRefreshCoordinator) {
        this(themeMenuSelectionRefreshCoordinator::refresh);
    }

    ThemeMenuSelectionDispatchCoordinator(@NonNull RefreshAction refreshAction) {
        this.refreshAction = refreshAction;
    }

    public String refresh(
            @NonNull Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            String previousSelection,
            boolean themesMenuBuilt,
            String defaultTheme
    ) {
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
