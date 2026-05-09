package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;

import javax.swing.JMenu;

public class ModelMenuDirtyRefreshTriggerCoordinator {

    private final TriggerAction triggerAction;

    public ModelMenuDirtyRefreshTriggerCoordinator(ModelMenuDirtyRefreshCoordinator modelMenuDirtyRefreshCoordinator) {
        this(modelMenuDirtyRefreshCoordinator::refresh);
    }

    ModelMenuDirtyRefreshTriggerCoordinator(@NonNull TriggerAction triggerAction) {
        this.triggerAction = triggerAction;
    }

    public void trigger(JMenu modelsMenu, @NonNull Runnable markModelsMenuDirty, @NonNull Runnable ensureModelsMenuReady) {

        triggerAction.trigger(modelsMenu, markModelsMenuDirty, ensureModelsMenuReady);
    }

    @FunctionalInterface
    interface TriggerAction {
        void trigger(JMenu modelsMenu, Runnable markModelsMenuDirty, Runnable ensureModelsMenuReady);
    }
}
