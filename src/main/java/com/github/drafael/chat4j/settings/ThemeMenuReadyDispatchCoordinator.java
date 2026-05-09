package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class ThemeMenuReadyDispatchCoordinator {

    private final EnsureReadyAction ensureReadyAction;

    public ThemeMenuReadyDispatchCoordinator(ThemeMenuReadyCoordinator themeMenuReadyCoordinator) {
        this(themeMenuReadyCoordinator::ensureReady);
    }

    ThemeMenuReadyDispatchCoordinator(@NonNull EnsureReadyAction ensureReadyAction) {
        this.ensureReadyAction = ensureReadyAction;
    }

    public void ensureReady(
        boolean themesMenuBuilt,
        @NonNull Runnable rebuildThemesMenuStructure,
        @NonNull Runnable syncThemeMenuSelection
) {

        ensureReadyAction.ensureReady(themesMenuBuilt, rebuildThemesMenuStructure, syncThemeMenuSelection);
    }

    @FunctionalInterface
    interface EnsureReadyAction {
        void ensureReady(boolean themesMenuBuilt, Runnable rebuildThemesMenuStructure, Runnable syncThemeMenuSelection);
    }
}
