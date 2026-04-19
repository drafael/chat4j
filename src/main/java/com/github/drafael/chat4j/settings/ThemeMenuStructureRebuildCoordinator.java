package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ThemeMenuStructureRebuildCoordinator {

    private final RebuildAction rebuildAction;

    public ThemeMenuStructureRebuildCoordinator(ThemeMenuStructureRebuilder themeMenuStructureRebuilder) {
        this(themeMenuStructureRebuilder::rebuild);
    }

    ThemeMenuStructureRebuildCoordinator(RebuildAction rebuildAction) {
        this.rebuildAction = Validate.notNull(rebuildAction, "rebuildAction must not be null");
    }

    public RebuildState rebuild(
            JMenu themesMenu,
            Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            ThemeMenuStructureRebuilder.ThemeSelectionHandler onThemeSelected,
            boolean currentThemesMenuBuilt,
            String currentLastMenuSelectedTheme
    ) {
        Validate.notNull(themeMenuItemsByName, "themeMenuItemsByName must not be null");
        Validate.notNull(onThemeSelected, "onThemeSelected must not be null");

        if (themesMenu == null) {
            return new RebuildState(currentThemesMenuBuilt, currentLastMenuSelectedTheme);
        }

        rebuildAction.rebuild(themesMenu, themeMenuItemsByName, onThemeSelected);
        return new RebuildState(true, null);
    }

    public record RebuildState(boolean themesMenuBuilt, String lastMenuSelectedTheme) {
    }

    @FunctionalInterface
    interface RebuildAction {
        void rebuild(
                JMenu themesMenu,
                Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
                ThemeMenuStructureRebuilder.ThemeSelectionHandler onThemeSelected
        );
    }
}
