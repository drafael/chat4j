package com.github.drafael.chat4j;

public class MainFrameSidebarState {

    private boolean sidebarVisible;
    private int lastDividerLocation;

    public MainFrameSidebarState() {
        this(true, 250);
    }

    MainFrameSidebarState(boolean sidebarVisible, int lastDividerLocation) {
        this.sidebarVisible = sidebarVisible;
        this.lastDividerLocation = lastDividerLocation;
    }

    public boolean sidebarVisible() {
        return sidebarVisible;
    }

    public int lastDividerLocation() {
        return lastDividerLocation;
    }

    public void setSidebarVisible(boolean sidebarVisible) {
        this.sidebarVisible = sidebarVisible;
    }

    public void setLastDividerLocation(int lastDividerLocation) {
        this.lastDividerLocation = lastDividerLocation;
    }
}
