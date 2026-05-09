package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class MenuBarSettingCoordinator {

    public void apply(boolean enabled, @NonNull MenuBarActions actions) {

        if (!enabled) {
            actions.disableMenuBar().run();
            actions.refreshWindow().run();
            return;
        }

        actions.ensureMenuBar().run();
        actions.installMenuBar().run();
        actions.ensureThemesMenuReady().run();
        actions.ensureModelsMenuReady().run();
        actions.ensureFontMenuReady().run();
        actions.syncTogglePreviewMenuSelection().run();
        actions.refreshWindow().run();
    }

    public record MenuBarActions(
            @NonNull Runnable disableMenuBar,
            @NonNull Runnable ensureMenuBar,
            @NonNull Runnable installMenuBar,
            @NonNull Runnable ensureThemesMenuReady,
            @NonNull Runnable ensureModelsMenuReady,
            @NonNull Runnable ensureFontMenuReady,
            @NonNull Runnable syncTogglePreviewMenuSelection,
            @NonNull Runnable refreshWindow
    ) {

        public MenuBarActions {
        }
    }
}
