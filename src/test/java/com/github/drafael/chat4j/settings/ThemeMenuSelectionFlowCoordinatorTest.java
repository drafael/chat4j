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

class ThemeMenuSelectionFlowCoordinatorTest {

    @Test
    @DisplayName("Refresh and apply delegates to refresh and apply actions")
    void refreshAndApply_whenCalled_delegatesAndReturnsAppliedSelection() {
        var capturedItems = new AtomicReference<Map<String, JRadioButtonMenuItem>>();
        var capturedPreviousSelection = new AtomicReference<String>();
        var capturedDefaultTheme = new AtomicReference<String>();
        var capturedSelectedTheme = new AtomicReference<String>();
        var capturedAppliedSelection = new AtomicReference<String>();

        var subject = new ThemeMenuSelectionFlowCoordinator(
                (themeMenuItemsByName, previousSelection, themesMenuBuilt, defaultTheme) -> {
                    capturedItems.set(themeMenuItemsByName);
                    capturedPreviousSelection.set(previousSelection);
                    capturedDefaultTheme.set(defaultTheme);
                    return "Catppuccin";
                },
                (selectedTheme, setLastMenuSelectedTheme) -> {
                    capturedSelectedTheme.set(selectedTheme);
                    setLastMenuSelectedTheme.accept(selectedTheme);
                    return selectedTheme;
                }
        );

        var themeMenuItemsByName = new LinkedHashMap<String, JRadioButtonMenuItem>();
        var lastMenuSelectedTheme = new AtomicReference<String>();

        String applied = subject.refreshAndApply(
                themeMenuItemsByName,
                "GitHub",
                true,
                "GitHub",
                value -> {
                    capturedAppliedSelection.set(value);
                    lastMenuSelectedTheme.set(value);
                }
        );

        assertThat(capturedItems.get()).isSameAs(themeMenuItemsByName);
        assertThat(capturedPreviousSelection.get()).isEqualTo("GitHub");
        assertThat(capturedDefaultTheme.get()).isEqualTo("GitHub");
        assertThat(capturedSelectedTheme.get()).isEqualTo("Catppuccin");
        assertThat(capturedAppliedSelection.get()).isEqualTo("Catppuccin");
        assertThat(lastMenuSelectedTheme.get()).isEqualTo("Catppuccin");
        assertThat(applied).isEqualTo("Catppuccin");
    }

    @Test
    @DisplayName("Refresh and apply validates required arguments and collaborators")
    void refreshAndApply_whenRequiredArgumentMissing_throwsException() {
        var subject = new ThemeMenuSelectionFlowCoordinator(
                (themeMenuItemsByName, previousSelection, themesMenuBuilt, defaultTheme) -> defaultTheme,
                (selectedTheme, setLastMenuSelectedTheme) -> selectedTheme
        );

        assertThatThrownBy(() -> subject.refreshAndApply(null, "GitHub", true, "GitHub", value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("themeMenuItemsByName");

        assertThatThrownBy(() -> subject.refreshAndApply(emptyMap(), "GitHub", true, " ", value -> {
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTheme must not be blank");

        assertThatThrownBy(() -> subject.refreshAndApply(emptyMap(), "GitHub", true, "GitHub", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedTheme");

        assertThatThrownBy(() -> new ThemeMenuSelectionFlowCoordinator(
                (ThemeMenuSelectionFlowCoordinator.RefreshAction) null,
                (selectedTheme, setLastMenuSelectedTheme) -> selectedTheme
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshAction");
    }
}
