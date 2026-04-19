package com.github.drafael.chat4j.util;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;

public class LookAndFeelMenuRefreshCoordinator {

    private final MenuPopupVisibleRunner menuPopupVisibleRunner;

    public LookAndFeelMenuRefreshCoordinator(MenuPopupVisibleRunner menuPopupVisibleRunner) {
        this.menuPopupVisibleRunner = Validate.notNull(menuPopupVisibleRunner, "menuPopupVisibleRunner must not be null");
    }

    public void refresh(
            Runnable clearProviderIconCache,
            Runnable markModelsMenuDirty,
            JMenu modelsMenu,
            Runnable ensureModelsMenuReady,
            JMenu fontMenu,
            Runnable ensureFontMenuReady
    ) {
        Validate.notNull(clearProviderIconCache, "clearProviderIconCache must not be null");
        Validate.notNull(markModelsMenuDirty, "markModelsMenuDirty must not be null");
        Validate.notNull(ensureModelsMenuReady, "ensureModelsMenuReady must not be null");
        Validate.notNull(ensureFontMenuReady, "ensureFontMenuReady must not be null");

        clearProviderIconCache.run();
        markModelsMenuDirty.run();
        menuPopupVisibleRunner.runIfVisible(modelsMenu, ensureModelsMenuReady);
        menuPopupVisibleRunner.runIfVisible(fontMenu, ensureFontMenuReady);
    }
}
