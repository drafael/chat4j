package com.github.drafael.chat4j.sidebar;

import org.apache.commons.lang3.Validate;

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
            SidebarVisibilityCoordinator sidebarVisibilityCoordinator,
            SidebarToggleApplyCoordinator sidebarToggleApplyCoordinator
    ) {
        this.sidebarVisibilityCoordinator = Validate.notNull(
                sidebarVisibilityCoordinator,
                "sidebarVisibilityCoordinator must not be null"
        );
        this.sidebarToggleApplyCoordinator = Validate.notNull(
                sidebarToggleApplyCoordinator,
                "sidebarToggleApplyCoordinator must not be null"
        );
    }

    public ToggleState toggle(
            boolean sidebarVisible,
            int lastDividerLocation,
            int currentDividerLocation,
            JSplitPane splitPane,
            JButton sidebarToggleButton,
            Icon sidebarToggleIconFilled,
            Icon sidebarToggleIconOutline
    ) {
        Validate.notNull(splitPane, "splitPane must not be null");

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
