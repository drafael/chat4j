package com.github.drafael.chat4j.settings;


import lombok.NonNull;
import java.util.function.Consumer;

public class ThemeMenuSelectionApplyCoordinator {

    public String apply(String selectedTheme, @NonNull Consumer<String> setLastMenuSelectedTheme) {

        setLastMenuSelectedTheme.accept(selectedTheme);
        return selectedTheme;
    }
}
