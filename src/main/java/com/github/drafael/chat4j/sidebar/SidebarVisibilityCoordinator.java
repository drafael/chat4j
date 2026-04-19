package com.github.drafael.chat4j.sidebar;

public class SidebarVisibilityCoordinator {

    private static final int VISIBLE_DIVIDER_SIZE = 2;
    private static final int HIDDEN_DIVIDER_SIZE = 0;

    public ToggleResult toggle(boolean sidebarVisible, int lastDividerLocation, int currentDividerLocation) {
        if (sidebarVisible) {
            return new ToggleResult(false, currentDividerLocation, 0, HIDDEN_DIVIDER_SIZE);
        }

        return new ToggleResult(true, lastDividerLocation, lastDividerLocation, VISIBLE_DIVIDER_SIZE);
    }

    public record ToggleResult(
            boolean sidebarVisible,
            int lastDividerLocation,
            int dividerLocation,
            int dividerSize
    ) {
    }
}
