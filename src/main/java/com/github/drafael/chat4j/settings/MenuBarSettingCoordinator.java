package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class MenuBarSettingCoordinator {

    public void apply(boolean enabled, MenuBarActions actions) {
        Validate.notNull(actions, "actions must not be null");

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
            Runnable disableMenuBar,
            Runnable ensureMenuBar,
            Runnable installMenuBar,
            Runnable ensureThemesMenuReady,
            Runnable ensureModelsMenuReady,
            Runnable ensureFontMenuReady,
            Runnable syncTogglePreviewMenuSelection,
            Runnable refreshWindow
    ) {

        public MenuBarActions {
            Validate.notNull(disableMenuBar, "disableMenuBar must not be null");
            Validate.notNull(ensureMenuBar, "ensureMenuBar must not be null");
            Validate.notNull(installMenuBar, "installMenuBar must not be null");
            Validate.notNull(ensureThemesMenuReady, "ensureThemesMenuReady must not be null");
            Validate.notNull(ensureModelsMenuReady, "ensureModelsMenuReady must not be null");
            Validate.notNull(ensureFontMenuReady, "ensureFontMenuReady must not be null");
            Validate.notNull(
                    syncTogglePreviewMenuSelection,
                    "syncTogglePreviewMenuSelection must not be null"
            );
            Validate.notNull(refreshWindow, "refreshWindow must not be null");
        }
    }
}
