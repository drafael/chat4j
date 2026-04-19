package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class SettingsOpenDispatchCoordinator {

    public void open(boolean onEdt, Runnable scheduleOpenOnEdt, Runnable openOnEdt) {
        Validate.notNull(scheduleOpenOnEdt, "scheduleOpenOnEdt must not be null");
        Validate.notNull(openOnEdt, "openOnEdt must not be null");

        if (!onEdt) {
            scheduleOpenOnEdt.run();
            return;
        }

        openOnEdt.run();
    }
}
