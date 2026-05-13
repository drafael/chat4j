package com.github.drafael.chat4j.sidebar;

import lombok.NonNull;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JSplitPane;
import java.awt.Component;

public class SidebarToggleApplyCoordinator {

    public ApplyResult apply(
            @NonNull SidebarVisibilityCoordinator.ToggleResult toggleResult,
            @NonNull JSplitPane splitPane,
            JButton sidebarToggleButton,
            Icon sidebarToggleIconFilled,
            Icon sidebarToggleIconOutline
    ) {

        Component sidebar = splitPane.getLeftComponent();
        if (sidebar != null) {
            sidebar.setVisible(toggleResult.sidebarVisible());
        }
        splitPane.setDividerSize(toggleResult.dividerSize());
        splitPane.setDividerLocation(toggleResult.dividerLocation());
        splitPane.revalidate();
        splitPane.repaint();

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
