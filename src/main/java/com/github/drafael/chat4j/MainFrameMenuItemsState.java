package com.github.drafael.chat4j;

import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainFrameMenuItemsState {

    private final Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
    private final Map<String, JMenuItem> providerHeaderItemsByName = new LinkedHashMap<>();
    private final Map<String, JRadioButtonMenuItem> themeMenuItemsByName = new LinkedHashMap<>();
    private final Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily = new LinkedHashMap<>();
    private final Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize = new LinkedHashMap<>();
    private final Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily = new LinkedHashMap<>();

    public Map<String, JRadioButtonMenuItem> modelMenuItemsByKey() {
        return modelMenuItemsByKey;
    }

    public Map<String, JMenuItem> providerHeaderItemsByName() {
        return providerHeaderItemsByName;
    }

    public Map<String, JRadioButtonMenuItem> themeMenuItemsByName() {
        return themeMenuItemsByName;
    }

    public Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily() {
        return appFontMenuItemsByFamily;
    }

    public Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize() {
        return appFontSizeMenuItemsBySize;
    }

    public Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily() {
        return codeFontMenuItemsByFamily;
    }
}
