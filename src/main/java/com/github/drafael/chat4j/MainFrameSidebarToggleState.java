package com.github.drafael.chat4j;

import javax.swing.Icon;
import javax.swing.JButton;

public class MainFrameSidebarToggleState {

    private JButton sidebarToggleButton;
    private Icon sidebarToggleFilledIcon;
    private Icon sidebarToggleOutlineIcon;

    public JButton sidebarToggleButton() {
        return sidebarToggleButton;
    }

    public Icon sidebarToggleFilledIcon() {
        return sidebarToggleFilledIcon;
    }

    public Icon sidebarToggleOutlineIcon() {
        return sidebarToggleOutlineIcon;
    }

    public void setSidebarToggleButton(JButton sidebarToggleButton) {
        this.sidebarToggleButton = sidebarToggleButton;
    }

    public void setSidebarToggleFilledIcon(Icon sidebarToggleFilledIcon) {
        this.sidebarToggleFilledIcon = sidebarToggleFilledIcon;
    }

    public void setSidebarToggleOutlineIcon(Icon sidebarToggleOutlineIcon) {
        this.sidebarToggleOutlineIcon = sidebarToggleOutlineIcon;
    }
}
