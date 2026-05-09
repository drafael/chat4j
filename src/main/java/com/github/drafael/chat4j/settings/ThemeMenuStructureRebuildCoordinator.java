package com.github.drafael.chat4j.settings;

import lombok.NonNull;

import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ThemeMenuStructureRebuildCoordinator {

    private final RebuildAction rebuildAction;

    public ThemeMenuStructureRebuildCoordinator(ThemeMenuStructureRebuilder themeMenuStructureRebuilder) {
        this(themeMenuStructureRebuilder::rebuild);
    }

    ThemeMenuStructureRebuildCoordinator(@NonNull RebuildAction rebuildAction) {
        this.rebuildAction = rebuildAction;
    }

    public RebuildState rebuild(
            JMenu themesMenu,
            @NonNull Map<String, JRadioButtonMenuItem> themeMenuItemsByName,
            @NonNull ThemeMenuStructureRebuilder.ThemeSelectionHandler onThemeSelected,
            boolean currentThemesMenuBuilt,
            String currentLastMenuSelectedTheme
    ) {

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
