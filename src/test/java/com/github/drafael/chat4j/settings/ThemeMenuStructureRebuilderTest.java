package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.menu.MenuSectionHeaderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ThemeMenuStructureRebuilderTest {

    @Test
    @DisplayName("Rebuild adds section headers, separators, and theme items wired to selection callback")
    void rebuild_whenSectionsPresent_buildsMenuAndWiresSelectionCallback() {
        Map<String, Map<String, String>> groupedThemes = new LinkedHashMap<>();
        var coreThemes = new LinkedHashMap<String, String>();
        coreThemes.put("GitHub", "theme.GitHub");
        coreThemes.put("Dracula", "theme.Dracula");
        groupedThemes.put("Core Themes", coreThemes);

        var materialThemes = new LinkedHashMap<String, String>();
        materialThemes.put("Nord", "theme.Nord");
        groupedThemes.put("Material Themes", materialThemes);

        var subject = new ThemeMenuStructureRebuilder(
                new MenuSectionHeaderFactory(),
                () -> groupedThemes
        );

        JMenu themesMenu = new JMenu("Themes");
        themesMenu.add(new JMenuItem("stale"));
        Map<String, JRadioButtonMenuItem> themeMenuItemsByName = new LinkedHashMap<>();
        themeMenuItemsByName.put("stale", new JRadioButtonMenuItem("stale"));
        AtomicReference<Selection> selected = new AtomicReference<>();

        subject.rebuild(
                themesMenu,
                themeMenuItemsByName,
                (themeName, className) -> selected.set(new Selection(themeName, className))
        );

        assertThat(themeMenuItemsByName).containsOnlyKeys("GitHub", "Dracula", "Nord");

        var components = themesMenu.getMenuComponents();
        assertThat(components).hasSize(6);
        assertThat(((JMenuItem) components[0]).getText()).isEqualTo("Core Themes");
        assertThat(((JMenuItem) components[0]).isEnabled()).isFalse();
        assertThat(((JMenuItem) components[1]).getText()).isEqualTo("GitHub");
        assertThat(((JMenuItem) components[2]).getText()).isEqualTo("Dracula");
        assertThat(components[3]).isInstanceOf(JSeparator.class);
        assertThat(((JMenuItem) components[4]).getText()).isEqualTo("Material Themes");
        assertThat(((JMenuItem) components[4]).isEnabled()).isFalse();
        assertThat(((JMenuItem) components[5]).getText()).isEqualTo("Nord");

        themeMenuItemsByName.get("Nord").doClick();
        assertThat(selected.get()).isEqualTo(new Selection("Nord", "theme.Nord"));
    }

    @Test
    @DisplayName("Rebuild clears stale items when grouped themes are empty")
    void rebuild_whenNoSections_clearsMenuAndThemeItems() {
        var subject = new ThemeMenuStructureRebuilder(
                new MenuSectionHeaderFactory(),
                () -> emptyMap()
        );

        JMenu themesMenu = new JMenu("Themes");
        themesMenu.add(new JMenuItem("stale"));
        Map<String, JRadioButtonMenuItem> themeMenuItemsByName = new LinkedHashMap<>();
        themeMenuItemsByName.put("stale", new JRadioButtonMenuItem("stale"));

        subject.rebuild(themesMenu, themeMenuItemsByName, (themeName, className) -> {
        });

        assertThat(themesMenu.getItemCount()).isZero();
        assertThat(themeMenuItemsByName).isEmpty();
    }

    private record Selection(String themeName, String className) {
    }
}
