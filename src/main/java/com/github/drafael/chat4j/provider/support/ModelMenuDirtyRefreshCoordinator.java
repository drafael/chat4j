package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;

public class ModelMenuDirtyRefreshCoordinator {

    private final MenuPopupVisibleRunner menuPopupVisibleRunner;

    public ModelMenuDirtyRefreshCoordinator(MenuPopupVisibleRunner menuPopupVisibleRunner) {
        this.menuPopupVisibleRunner = Validate.notNull(menuPopupVisibleRunner, "menuPopupVisibleRunner must not be null");
    }

    public void refresh(JMenu modelsMenu, Runnable markModelsMenuDirty, Runnable ensureModelsMenuReady) {
        Validate.notNull(markModelsMenuDirty, "markModelsMenuDirty must not be null");
        Validate.notNull(ensureModelsMenuReady, "ensureModelsMenuReady must not be null");

        markModelsMenuDirty.run();
        menuPopupVisibleRunner.runIfVisible(modelsMenu, ensureModelsMenuReady);
    }
}
