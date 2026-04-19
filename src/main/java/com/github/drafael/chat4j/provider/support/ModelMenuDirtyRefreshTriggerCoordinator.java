package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;

public class ModelMenuDirtyRefreshTriggerCoordinator {

    private final TriggerAction triggerAction;

    public ModelMenuDirtyRefreshTriggerCoordinator(ModelMenuDirtyRefreshCoordinator modelMenuDirtyRefreshCoordinator) {
        this(modelMenuDirtyRefreshCoordinator::refresh);
    }

    ModelMenuDirtyRefreshTriggerCoordinator(TriggerAction triggerAction) {
        this.triggerAction = Validate.notNull(triggerAction, "triggerAction must not be null");
    }

    public void trigger(JMenu modelsMenu, Runnable markModelsMenuDirty, Runnable ensureModelsMenuReady) {
        Validate.notNull(markModelsMenuDirty, "markModelsMenuDirty must not be null");
        Validate.notNull(ensureModelsMenuReady, "ensureModelsMenuReady must not be null");

        triggerAction.trigger(modelsMenu, markModelsMenuDirty, ensureModelsMenuReady);
    }

    @FunctionalInterface
    interface TriggerAction {
        void trigger(JMenu modelsMenu, Runnable markModelsMenuDirty, Runnable ensureModelsMenuReady);
    }
}
