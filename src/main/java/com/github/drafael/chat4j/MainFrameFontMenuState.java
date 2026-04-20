package com.github.drafael.chat4j;

public class MainFrameFontMenuState {

    private boolean fontMenuBuilt;
    private String lastMenuSelectedAppFontFamily;
    private Integer lastMenuSelectedAppFontSize;
    private String lastMenuSelectedCodeFontFamily;

    public MainFrameFontMenuState() {
        this(false, null, null, null);
    }

    MainFrameFontMenuState(
            boolean fontMenuBuilt,
            String lastMenuSelectedAppFontFamily,
            Integer lastMenuSelectedAppFontSize,
            String lastMenuSelectedCodeFontFamily
    ) {
        this.fontMenuBuilt = fontMenuBuilt;
        this.lastMenuSelectedAppFontFamily = lastMenuSelectedAppFontFamily;
        this.lastMenuSelectedAppFontSize = lastMenuSelectedAppFontSize;
        this.lastMenuSelectedCodeFontFamily = lastMenuSelectedCodeFontFamily;
    }

    public boolean fontMenuBuilt() {
        return fontMenuBuilt;
    }

    public String lastMenuSelectedAppFontFamily() {
        return lastMenuSelectedAppFontFamily;
    }

    public Integer lastMenuSelectedAppFontSize() {
        return lastMenuSelectedAppFontSize;
    }

    public String lastMenuSelectedCodeFontFamily() {
        return lastMenuSelectedCodeFontFamily;
    }

    public void setFontMenuBuilt(boolean fontMenuBuilt) {
        this.fontMenuBuilt = fontMenuBuilt;
    }

    public void setLastMenuSelectedAppFontFamily(String lastMenuSelectedAppFontFamily) {
        this.lastMenuSelectedAppFontFamily = lastMenuSelectedAppFontFamily;
    }

    public void setLastMenuSelectedAppFontSize(Integer lastMenuSelectedAppFontSize) {
        this.lastMenuSelectedAppFontSize = lastMenuSelectedAppFontSize;
    }

    public void setLastMenuSelectedCodeFontFamily(String lastMenuSelectedCodeFontFamily) {
        this.lastMenuSelectedCodeFontFamily = lastMenuSelectedCodeFontFamily;
    }
}
