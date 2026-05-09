package com.github.drafael.chat4j.settings;

import lombok.NonNull;
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

    ThemeMenuSelectionFlowCoordinator(@NonNull RefreshAction refreshAction, @NonNull ApplyAction applyAction) {
        this.refreshAction = refreshAction;
        this.applyAction = applyAction;
    }

    public String refreshAndApply(
            @NonNull Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            String previousSelection,
            boolean themesMenuBuilt,
            String defaultTheme,
            @NonNull Consumer<String> setLastMenuSelectedTheme
    ) {
        Validate.notBlank(defaultTheme, "defaultTheme must not be blank");

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
