package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.function.Consumer;

public class ThemeMenuSelectionFlowCoordinator {

    private final RefreshAction refreshAction;
    private final ApplyAction applyAction;

    public ThemeMenuSelectionFlowCoordinator(
            ThemeMenuSelectionDispatchCoordinator themeMenuSelectionDispatchCoordinator,
            ThemeMenuSelectionApplyCoordinator themeMenuSelectionApplyCoordinator
    ) {
        this(themeMenuSelectionDispatchCoordinator::refresh, themeMenuSelectionApplyCoordinator::apply);
    }

    ThemeMenuSelectionFlowCoordinator(RefreshAction refreshAction, ApplyAction applyAction) {
        this.refreshAction = Validate.notNull(refreshAction, "refreshAction must not be null");
        this.applyAction = Validate.notNull(applyAction, "applyAction must not be null");
    }

    public String refreshAndApply(
            Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            String previousSelection,
            boolean themesMenuBuilt,
            String defaultTheme,
            Consumer<String> setLastMenuSelectedTheme
    ) {
        Validate.notNull(themeMenuItemsByName, "themeMenuItemsByName must not be null");
        Validate.notBlank(defaultTheme, "defaultTheme must not be blank");
        Validate.notNull(setLastMenuSelectedTheme, "setLastMenuSelectedTheme must not be null");

        String selectedTheme = refreshAction.refresh(
                themeMenuItemsByName,
                previousSelection,
                themesMenuBuilt,
                defaultTheme
        );

        return applyAction.apply(selectedTheme, setLastMenuSelectedTheme);
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

    @FunctionalInterface
    interface ApplyAction {
        String apply(String selectedTheme, Consumer<String> setLastMenuSelectedTheme);
    }
}
