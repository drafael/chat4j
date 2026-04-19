package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class ThemeMenuReadyDispatchCoordinator {

    private final EnsureReadyAction ensureReadyAction;

    public ThemeMenuReadyDispatchCoordinator(ThemeMenuReadyCoordinator themeMenuReadyCoordinator) {
        this(themeMenuReadyCoordinator::ensureReady);
    }

    ThemeMenuReadyDispatchCoordinator(EnsureReadyAction ensureReadyAction) {
        this.ensureReadyAction = Validate.notNull(ensureReadyAction, "ensureReadyAction must not be null");
    }

    public void ensureReady(boolean themesMenuBuilt, Runnable rebuildThemesMenuStructure, Runnable syncThemeMenuSelection) {
        Validate.notNull(rebuildThemesMenuStructure, "rebuildThemesMenuStructure must not be null");
        Validate.notNull(syncThemeMenuSelection, "syncThemeMenuSelection must not be null");

        ensureReadyAction.ensureReady(themesMenuBuilt, rebuildThemesMenuStructure, syncThemeMenuSelection);
    }

    @FunctionalInterface
    interface EnsureReadyAction {
        void ensureReady(boolean themesMenuBuilt, Runnable rebuildThemesMenuStructure, Runnable syncThemeMenuSelection);
    }
}
