package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeMenuSelectionSynchronizerTest {

    private final ThemeMenuSelectionSynchronizer subject = new ThemeMenuSelectionSynchronizer();

    @Test
    @DisplayName("Sync selection updates selected theme item and clears previous")
    void syncSelection_whenSelectionChanges_updatesMenuItemsAndReturnsCurrentTheme() {
        var previous = new JRadioButtonMenuItem("GitHub");
        previous.setSelected(true);
        var current = new JRadioButtonMenuItem("Solarized Dark");
        Map<String, JRadioButtonMenuItem> itemsByTheme = new LinkedHashMap<>();
        itemsByTheme.put("GitHub", previous);
        itemsByTheme.put("Solarized Dark", current);

        String last = subject.syncSelection(itemsByTheme, "Solarized Dark", "GitHub", true);

        assertThat(previous.isSelected()).isFalse();
        assertThat(current.isSelected()).isTrue();
        assertThat(last).isEqualTo("Solarized Dark");
    }

    @Test
    @DisplayName("Sync selection keeps previous theme when menu has not been built")
    void syncSelection_whenMenuNotBuilt_returnsPreviousThemeWithoutMutatingSelection() {
        var selected = new JRadioButtonMenuItem("GitHub");
        selected.setSelected(true);
        Map<String, JRadioButtonMenuItem> itemsByTheme = Map.of("GitHub", selected);

        String last = subject.syncSelection(itemsByTheme, "Solarized Dark", "GitHub", false);

        assertThat(selected.isSelected()).isTrue();
        assertThat(last).isEqualTo("GitHub");
    }

    @Test
    @DisplayName("Sync selection returns same value when theme is unchanged")
    void syncSelection_whenThemeUnchanged_returnsExistingTheme() {
        var selected = new JRadioButtonMenuItem("GitHub");
        selected.setSelected(true);
        Map<String, JRadioButtonMenuItem> itemsByTheme = Map.of("GitHub", selected);

        String last = subject.syncSelection(itemsByTheme, "GitHub", "GitHub", true);

        assertThat(selected.isSelected()).isTrue();
        assertThat(last).isEqualTo("GitHub");
    }
}
