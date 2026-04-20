package com.github.drafael.chat4j.sidebar;

import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class SidebarToggleStateApplyCoordinator {

    public SidebarToggleCoordinator.ToggleState apply(
            SidebarToggleCoordinator.ToggleState toggleState,
            Consumer<Boolean> setSidebarVisible,
            Consumer<Integer> setLastDividerLocation
    ) {
        Validate.notNull(toggleState, "toggleState must not be null");
        Validate.notNull(setSidebarVisible, "setSidebarVisible must not be null");
        Validate.notNull(setLastDividerLocation, "setLastDividerLocation must not be null");

        setSidebarVisible.accept(toggleState.sidebarVisible());
        setLastDividerLocation.accept(toggleState.lastDividerLocation());
        return toggleState;
    }
}
