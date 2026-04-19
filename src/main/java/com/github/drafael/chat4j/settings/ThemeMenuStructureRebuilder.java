package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.menu.MenuSectionHeaderFactory;
import org.apache.commons.lang3.Validate;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ThemeMenuStructureRebuilder {

    private final MenuSectionHeaderFactory menuSectionHeaderFactory;
    private final GroupedThemesProvider groupedThemesProvider;

    public ThemeMenuStructureRebuilder(MenuSectionHeaderFactory menuSectionHeaderFactory) {
        this(menuSectionHeaderFactory, AppearancePanel::groupedThemes);
    }

    ThemeMenuStructureRebuilder(
            MenuSectionHeaderFactory menuSectionHeaderFactory,
            GroupedThemesProvider groupedThemesProvider
    ) {
        this.menuSectionHeaderFactory = Validate.notNull(
                menuSectionHeaderFactory,
                "menuSectionHeaderFactory must not be null"
        );
        this.groupedThemesProvider = Validate.notNull(groupedThemesProvider, "groupedThemesProvider must not be null");
    }

    public void rebuild(
            JMenu themesMenu,
            Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            ThemeSelectionHandler onThemeSelected
    ) {
        Validate.notNull(themesMenu, "themesMenu must not be null");
        Validate.notNull(themeMenuItemsByName, "themeMenuItemsByName must not be null");
        Validate.notNull(onThemeSelected, "onThemeSelected must not be null");

        themesMenu.removeAll();
        themeMenuItemsByName.clear();

        ButtonGroup group = new ButtonGroup();
        boolean firstSection = true;
        for (Map.Entry<String, Map<String, String>> section : groupedThemesProvider.groupedThemes().entrySet()) {
            if (!firstSection) {
                themesMenu.addSeparator();
            }

            themesMenu.add(menuSectionHeaderFactory.create(section.getKey()));
            section.getValue().forEach((themeName, className) -> {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(themeName);
                item.addActionListener(e -> onThemeSelected.select(themeName, className));
                group.add(item);
                themesMenu.add(item);
                themeMenuItemsByName.put(themeName, item);
            });

            firstSection = false;
        }
    }

    @FunctionalInterface
    interface GroupedThemesProvider {
        Map<String, Map<String, String>> groupedThemes();
    }

    @FunctionalInterface
    public interface ThemeSelectionHandler {
        void select(String themeName, String className);
    }
}
