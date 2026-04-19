package com.github.drafael.chat4j.sidebar;

import org.apache.commons.lang3.Validate;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JSplitPane;

public class SidebarToggleApplyCoordinator {

    public ApplyResult apply(
            SidebarVisibilityCoordinator.ToggleResult toggleResult,
            JSplitPane splitPane,
            JButton sidebarToggleButton,
            Icon sidebarToggleIconFilled,
            Icon sidebarToggleIconOutline
    ) {
        Validate.notNull(toggleResult, "toggleResult must not be null");
        Validate.notNull(splitPane, "splitPane must not be null");

        splitPane.setDividerSize(toggleResult.dividerSize());
        splitPane.setDividerLocation(toggleResult.dividerLocation());

        if (sidebarToggleButton != null) {
            sidebarToggleButton.setIcon(toggleResult.sidebarVisible()
                    ? sidebarToggleIconFilled
                    : sidebarToggleIconOutline);
        }

        return new ApplyResult(toggleResult.sidebarVisible(), toggleResult.lastDividerLocation());
    }

    public record ApplyResult(boolean sidebarVisible, int lastDividerLocation) {
    }
}
