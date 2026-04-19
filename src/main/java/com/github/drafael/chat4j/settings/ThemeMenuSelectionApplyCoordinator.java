package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class ThemeMenuSelectionApplyCoordinator {

    public String apply(String selectedTheme, Consumer<String> setLastMenuSelectedTheme) {
        Validate.notNull(setLastMenuSelectedTheme, "setLastMenuSelectedTheme must not be null");

        setLastMenuSelectedTheme.accept(selectedTheme);
        return selectedTheme;
    }
}
