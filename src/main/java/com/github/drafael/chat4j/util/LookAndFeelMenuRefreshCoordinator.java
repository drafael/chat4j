package com.github.drafael.chat4j.util;


import lombok.NonNull;
import javax.swing.JMenu;

public class LookAndFeelMenuRefreshCoordinator {

    private final MenuPopupVisibleRunner menuPopupVisibleRunner;

    public LookAndFeelMenuRefreshCoordinator(@NonNull MenuPopupVisibleRunner menuPopupVisibleRunner) {
        this.menuPopupVisibleRunner = menuPopupVisibleRunner;
    }

    public void refresh(
            @NonNull Runnable clearProviderIconCache,
            @NonNull Runnable markModelsMenuDirty,
            JMenu modelsMenu,
            @NonNull Runnable ensureModelsMenuReady,
            JMenu fontMenu,
            @NonNull Runnable ensureFontMenuReady
    ) {

        clearProviderIconCache.run();
        markModelsMenuDirty.run();
        menuPopupVisibleRunner.runIfVisible(modelsMenu, ensureModelsMenuReady);
        menuPopupVisibleRunner.runIfVisible(fontMenu, ensureFontMenuReady);
    }
}
