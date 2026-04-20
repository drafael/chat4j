package com.github.drafael.chat4j;

import javax.swing.JMenu;
import javax.swing.JMenuBar;

public class MainFrameTopMenusState {

    private JMenuBar modelMenuBar;
    private JMenu fileMenu;
    private JMenu viewMenu;

    public JMenuBar modelMenuBar() {
        return modelMenuBar;
    }

    public JMenu fileMenu() {
        return fileMenu;
    }

    public JMenu viewMenu() {
        return viewMenu;
    }

    public void setModelMenuBar(JMenuBar modelMenuBar) {
        this.modelMenuBar = modelMenuBar;
    }

    public void setFileMenu(JMenu fileMenu) {
        this.fileMenu = fileMenu;
    }

    public void setViewMenu(JMenu viewMenu) {
        this.viewMenu = viewMenu;
    }
}
