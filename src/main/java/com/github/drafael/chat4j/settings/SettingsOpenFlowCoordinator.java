package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class SettingsOpenFlowCoordinator {

    private final OpenDispatchAction openDispatchAction;
    private final DialogOpenAction dialogOpenAction;

    public SettingsOpenFlowCoordinator(
            SettingsOpenDispatchCoordinator settingsOpenDispatchCoordinator,
            SettingsDialogCoordinator settingsDialogCoordinator
    ) {
        this(settingsOpenDispatchCoordinator::open, settingsDialogCoordinator::open);
    }

    SettingsOpenFlowCoordinator(@NonNull OpenDispatchAction openDispatchAction, @NonNull DialogOpenAction dialogOpenAction) {
        this.openDispatchAction = openDispatchAction;
        this.dialogOpenAction = dialogOpenAction;
    }

    public void open(
            boolean onEdt,
            @NonNull Runnable scheduleOpenOnEdt,
            @NonNull SettingsDialogCoordinator.DialogFactory dialogFactory,
            @NonNull Runnable onDialogClosed
    ) {

        openDispatchAction.open(
                onEdt,
                scheduleOpenOnEdt,
                () -> dialogOpenAction.open(dialogFactory, onDialogClosed)
        );
    }

    @FunctionalInterface
    interface OpenDispatchAction {
        void open(boolean onEdt, Runnable scheduleOpenOnEdt, Runnable openOnEdt);
    }

    @FunctionalInterface
    interface DialogOpenAction {
        void open(SettingsDialogCoordinator.DialogFactory dialogFactory, Runnable onDialogClosed);
    }
}
