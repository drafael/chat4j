package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Collections.emptyMap;

class ThemeMenuSelectionRefreshCoordinatorTest {

    @Test
    @DisplayName("Refresh resolves selected theme and delegates to synchronizer")
    void refresh_whenCalled_resolvesThemeAndDelegatesToSynchronizer() {
        var capturedDefaultTheme = new AtomicReference<String>();
        var capturedItems = new AtomicReference<Map<String, JRadioButtonMenuItem>>();
        var capturedSelectedTheme = new AtomicReference<String>();
        var capturedPrevious = new AtomicReference<String>();
        var capturedBuilt = new AtomicReference<Boolean>();

        var subject = new ThemeMenuSelectionRefreshCoordinator(
                defaultTheme -> {
                    capturedDefaultTheme.set(defaultTheme);
                    return "Dracula";
                },
                (themeMenuItemsByName, selectedTheme, previousSelection, themesMenuBuilt) -> {
                    capturedItems.set(themeMenuItemsByName);
                    capturedSelectedTheme.set(selectedTheme);
                    capturedPrevious.set(previousSelection);
                    capturedBuilt.set(themesMenuBuilt);
                    return "Dracula";
                }
        );

        var menuItemsByName = new LinkedHashMap<String, JRadioButtonMenuItem>();
        menuItemsByName.put("GitHub", new JRadioButtonMenuItem("GitHub"));
        menuItemsByName.put("Dracula", new JRadioButtonMenuItem("Dracula"));

        String refreshed = subject.refresh(menuItemsByName, "GitHub", true, "GitHub");

        assertThat(refreshed).isEqualTo("Dracula");
        assertThat(capturedDefaultTheme.get()).isEqualTo("GitHub");
        assertThat(capturedItems.get()).isSameAs(menuItemsByName);
        assertThat(capturedSelectedTheme.get()).isEqualTo("Dracula");
        assertThat(capturedPrevious.get()).isEqualTo("GitHub");
        assertThat(capturedBuilt.get()).isTrue();
    }

    @Test
    @DisplayName("Refresh validates required arguments")
    void refresh_whenArgumentsInvalid_throwsException() {
        var subject = new ThemeMenuSelectionRefreshCoordinator(
                defaultTheme -> defaultTheme,
                (themeMenuItemsByName, selectedTheme, previousSelection, themesMenuBuilt) -> selectedTheme
        );

        assertThatThrownBy(() -> subject.refresh(null, null, true, "GitHub"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("themeMenuItemsByName must not be null");

        assertThatThrownBy(() -> subject.refresh(emptyMap(), null, true, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTheme must not be blank");
    }
}
