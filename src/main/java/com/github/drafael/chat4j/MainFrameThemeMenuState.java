package com.github.drafael.chat4j;

public class MainFrameThemeMenuState {

    private boolean themesMenuBuilt;
    private String lastMenuSelectedTheme;

    public MainFrameThemeMenuState() {
        this(false, null);
    }

    MainFrameThemeMenuState(boolean themesMenuBuilt, String lastMenuSelectedTheme) {
        this.themesMenuBuilt = themesMenuBuilt;
        this.lastMenuSelectedTheme = lastMenuSelectedTheme;
    }

    public boolean themesMenuBuilt() {
        return themesMenuBuilt;
    }

    public String lastMenuSelectedTheme() {
        return lastMenuSelectedTheme;
    }

    public void setThemesMenuBuilt(boolean themesMenuBuilt) {
        this.themesMenuBuilt = themesMenuBuilt;
    }

    public void setLastMenuSelectedTheme(String lastMenuSelectedTheme) {
        this.lastMenuSelectedTheme = lastMenuSelectedTheme;
    }
}
