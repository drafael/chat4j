package com.github.drafael.chat4j.sidebar;

import lombok.NonNull;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JSplitPane;

public class SidebarToggleCoordinator {

    private final SidebarVisibilityCoordinator sidebarVisibilityCoordinator;
    private final SidebarToggleApplyCoordinator sidebarToggleApplyCoordinator;

    public SidebarToggleCoordinator() {
        this(new SidebarVisibilityCoordinator(), new SidebarToggleApplyCoordinator());
    }

    SidebarToggleCoordinator(
            @NonNull SidebarVisibilityCoordinator sidebarVisibilityCoordinator,
            @NonNull SidebarToggleApplyCoordinator sidebarToggleApplyCoordinator
    ) {
        this.sidebarVisibilityCoordinator = sidebarVisibilityCoordinator;
        this.sidebarToggleApplyCoordinator = sidebarToggleApplyCoordinator;
    }

    public ToggleState toggle(
            boolean sidebarVisible,
            int lastDividerLocation,
            int currentDividerLocation,
            @NonNull JSplitPane splitPane,
            JButton sidebarToggleButton,
            Icon sidebarToggleIconFilled,
            Icon sidebarToggleIconOutline
    ) {

        SidebarVisibilityCoordinator.ToggleResult toggleResult = sidebarVisibilityCoordinator.toggle(
                sidebarVisible,
                lastDividerLocation,
                currentDividerLocation
        );

        SidebarToggleApplyCoordinator.ApplyResult applyResult = sidebarToggleApplyCoordinator.apply(
                toggleResult,
                splitPane,
                sidebarToggleButton,
                sidebarToggleIconFilled,
                sidebarToggleIconOutline
        );

        return new ToggleState(applyResult.sidebarVisible(), applyResult.lastDividerLocation());
    }

    public record ToggleState(boolean sidebarVisible, int lastDividerLocation) {
    }
}
