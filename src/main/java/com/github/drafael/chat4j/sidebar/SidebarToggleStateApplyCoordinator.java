package com.github.drafael.chat4j.sidebar;


import lombok.NonNull;
import java.util.function.Consumer;

public class SidebarToggleStateApplyCoordinator {

    public SidebarToggleCoordinator.ToggleState apply(
            @NonNull SidebarToggleCoordinator.ToggleState toggleState,
            @NonNull Consumer<Boolean> setSidebarVisible,
            @NonNull Consumer<Integer> setLastDividerLocation
    ) {

        setSidebarVisible.accept(toggleState.sidebarVisible());
        setLastDividerLocation.accept(toggleState.lastDividerLocation());
        return toggleState;
    }
}
