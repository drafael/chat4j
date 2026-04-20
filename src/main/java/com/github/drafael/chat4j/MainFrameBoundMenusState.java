package com.github.drafael.chat4j;

import javax.swing.JMenu;

public class MainFrameBoundMenusState {

    private JMenu modelsMenu;
    private JMenu themesMenu;
    private JMenu fontMenu;

    public JMenu modelsMenu() {
        return modelsMenu;
    }

    public JMenu themesMenu() {
        return themesMenu;
    }

    public JMenu fontMenu() {
        return fontMenu;
    }

    public void setModelsMenu(JMenu modelsMenu) {
        this.modelsMenu = modelsMenu;
    }

    public void setThemesMenu(JMenu themesMenu) {
        this.themesMenu = themesMenu;
    }

    public void setFontMenu(JMenu fontMenu) {
        this.fontMenu = fontMenu;
    }
}
