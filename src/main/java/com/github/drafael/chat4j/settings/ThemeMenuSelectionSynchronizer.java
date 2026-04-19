package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.Objects;

public class ThemeMenuSelectionSynchronizer {

    public String syncSelection(
            Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            String selectedTheme,
            String lastSelectedTheme,
            boolean themesMenuBuilt
    ) {
        Validate.notNull(themeMenuItemsByName, "themeMenuItemsByName must not be null");

        if (!themesMenuBuilt) {
            return lastSelectedTheme;
        }

        if (Objects.equals(selectedTheme, lastSelectedTheme)) {
            return lastSelectedTheme;
        }

        if (lastSelectedTheme != null) {
            JRadioButtonMenuItem previous = themeMenuItemsByName.get(lastSelectedTheme);
            if (previous != null) {
                previous.setSelected(false);
            }
        }

        JRadioButtonMenuItem current = themeMenuItemsByName.get(selectedTheme);
        if (current != null) {
            current.setSelected(true);
        }

        return selectedTheme;
    }
}
