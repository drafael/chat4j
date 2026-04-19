package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class SettingsOpenFlowCoordinator {

    private final OpenDispatchAction openDispatchAction;
    private final DialogOpenAction dialogOpenAction;

    public SettingsOpenFlowCoordinator(
            SettingsOpenDispatchCoordinator settingsOpenDispatchCoordinator,
            SettingsDialogCoordinator settingsDialogCoordinator
    ) {
        this(settingsOpenDispatchCoordinator::open, settingsDialogCoordinator::open);
    }

    SettingsOpenFlowCoordinator(OpenDispatchAction openDispatchAction, DialogOpenAction dialogOpenAction) {
        this.openDispatchAction = Validate.notNull(openDispatchAction, "openDispatchAction must not be null");
        this.dialogOpenAction = Validate.notNull(dialogOpenAction, "dialogOpenAction must not be null");
    }

    public void open(
            boolean onEdt,
            Runnable scheduleOpenOnEdt,
            SettingsDialogCoordinator.DialogFactory dialogFactory,
            Runnable onDialogClosed
    ) {
        Validate.notNull(scheduleOpenOnEdt, "scheduleOpenOnEdt must not be null");
        Validate.notNull(dialogFactory, "dialogFactory must not be null");
        Validate.notNull(onDialogClosed, "onDialogClosed must not be null");

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
