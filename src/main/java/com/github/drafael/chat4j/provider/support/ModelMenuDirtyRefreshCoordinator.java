package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import lombok.NonNull;

import javax.swing.JMenu;

public class ModelMenuDirtyRefreshCoordinator {

    private final MenuPopupVisibleRunner menuPopupVisibleRunner;

    public ModelMenuDirtyRefreshCoordinator(@NonNull MenuPopupVisibleRunner menuPopupVisibleRunner) {
        this.menuPopupVisibleRunner = menuPopupVisibleRunner;
    }

    public void refresh(JMenu modelsMenu, @NonNull Runnable markModelsMenuDirty, @NonNull Runnable ensureModelsMenuReady) {

        markModelsMenuDirty.run();
        menuPopupVisibleRunner.runIfVisible(modelsMenu, ensureModelsMenuReady);
    }
}
