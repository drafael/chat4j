package com.github.drafael.chat4j.sidebar;

public class SidebarVisibilityCoordinator {

    private static final int VISIBLE_DIVIDER_SIZE = 1;
    private static final int HIDDEN_DIVIDER_SIZE = 0;

    public ToggleResult toggle(boolean sidebarVisible, int lastDividerLocation, int currentDividerLocation) {
        return sidebarVisible
            ? new ToggleResult(false, currentDividerLocation, 0, HIDDEN_DIVIDER_SIZE)
            : new ToggleResult(true, lastDividerLocation, lastDividerLocation, VISIBLE_DIVIDER_SIZE);
    }

    public record ToggleResult(
            boolean sidebarVisible,
            int lastDividerLocation,
            int dividerLocation,
            int dividerSize
    ) {
    }
}
