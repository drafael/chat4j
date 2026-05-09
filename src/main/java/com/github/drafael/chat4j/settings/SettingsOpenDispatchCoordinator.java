package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class SettingsOpenDispatchCoordinator {

    public void open(boolean onEdt, @NonNull Runnable scheduleOpenOnEdt, @NonNull Runnable openOnEdt) {

        if (!onEdt) {
            scheduleOpenOnEdt.run();
            return;
        }

        openOnEdt.run();
    }
}
