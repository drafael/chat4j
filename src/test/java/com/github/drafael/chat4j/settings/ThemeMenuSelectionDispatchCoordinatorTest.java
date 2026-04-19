package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeMenuSelectionDispatchCoordinatorTest {

    @Test
    @DisplayName("Refresh delegates to action and returns resolved selection")
    void refresh_whenCalled_delegatesAndReturnsSelection() {
        var capturedDefaultTheme = new AtomicReference<String>();
        var capturedPreviousSelection = new AtomicReference<String>();

        var subject = new ThemeMenuSelectionDispatchCoordinator((
                themeMenuItemsByName,
                previousSelection,
                themesMenuBuilt,
                defaultTheme
        ) -> {
            capturedPreviousSelection.set(previousSelection);
            capturedDefaultTheme.set(defaultTheme);
            return "Dracula";
        });

        Map<String, JRadioButtonMenuItem> themeMenuItems = new LinkedHashMap<>();

        String resolved = subject.refresh(
                themeMenuItems,
                "GitHub",
                true,
                "GitHub"
        );

        assertThat(capturedPreviousSelection.get()).isEqualTo("GitHub");
        assertThat(capturedDefaultTheme.get()).isEqualTo("GitHub");
        assertThat(resolved).isEqualTo("Dracula");
    }

    @Test
    @DisplayName("Refresh validates required arguments")
    void refresh_whenArgumentMissing_throwsException() {
        var subject = new ThemeMenuSelectionDispatchCoordinator((
                themeMenuItemsByName,
                previousSelection,
                themesMenuBuilt,
                defaultTheme
        ) -> previousSelection);

        assertThatThrownBy(() -> subject.refresh(null, "GitHub", true, "GitHub"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("themeMenuItemsByName must not be null");

        assertThatThrownBy(() -> subject.refresh(new LinkedHashMap<>(), "GitHub", true, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTheme must not be blank");

        assertThatThrownBy(() -> new ThemeMenuSelectionDispatchCoordinator(
                (ThemeMenuSelectionDispatchCoordinator.RefreshAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshAction must not be null");
    }
}
