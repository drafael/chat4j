package com.github.drafael.chat4j;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;

public class MainFrameSidebarToggleState {

    private JButton sidebarToggleButton;
    private JButton searchButton;
    private JPanel leftButtons;
    private JPanel rightPanel;
    private Icon sidebarToggleFilledIcon;
    private Icon sidebarToggleOutlineIcon;

    public JButton sidebarToggleButton() {
        return sidebarToggleButton;
    }

    public JButton searchButton() {
        return searchButton;
    }

    public JPanel leftButtons() {
        return leftButtons;
    }

    public JPanel rightPanel() {
        return rightPanel;
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

    public void setSearchButton(JButton searchButton) {
        this.searchButton = searchButton;
    }

    public void setLeftButtons(JPanel leftButtons) {
        this.leftButtons = leftButtons;
    }

    public void setRightPanel(JPanel rightPanel) {
        this.rightPanel = rightPanel;
    }

    public void setSidebarToggleFilledIcon(Icon sidebarToggleFilledIcon) {
        this.sidebarToggleFilledIcon = sidebarToggleFilledIcon;
    }

    public void setSidebarToggleOutlineIcon(Icon sidebarToggleOutlineIcon) {
        this.sidebarToggleOutlineIcon = sidebarToggleOutlineIcon;
    }
}
