package com.github.drafael.chat4j;

import com.github.drafael.chat4j.settings.ThemeMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuReadyDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.ThemeSettings;
import lombok.NonNull;

public class MainFrameThemeMenuCoordinator {

    private final ThemeMenuReadyDispatchCoordinator themeMenuReadyDispatchCoordinator;
    private final ThemeMenuStructureRebuildCoordinator themeMenuStructureRebuildCoordinator;
    private final ThemeMenuStructureRebuildApplyCoordinator themeMenuStructureRebuildApplyCoordinator;
    private final ThemeMenuSelectionFlowCoordinator themeMenuSelectionFlowCoordinator;
    private final ThemeMenuApplyDispatchCoordinator themeMenuApplyDispatchCoordinator;
    private final ThemeMenuApplyCoordinator themeMenuApplyCoordinator;

    public MainFrameThemeMenuCoordinator(
            @NonNull ThemeMenuReadyDispatchCoordinator themeMenuReadyDispatchCoordinator,
            @NonNull ThemeMenuStructureRebuildCoordinator themeMenuStructureRebuildCoordinator,
            @NonNull ThemeMenuStructureRebuildApplyCoordinator themeMenuStructureRebuildApplyCoordinator,
            @NonNull ThemeMenuSelectionFlowCoordinator themeMenuSelectionFlowCoordinator,
            @NonNull ThemeMenuApplyDispatchCoordinator themeMenuApplyDispatchCoordinator,
            @NonNull ThemeMenuApplyCoordinator themeMenuApplyCoordinator
    ) {
        this.themeMenuReadyDispatchCoordinator = themeMenuReadyDispatchCoordinator;
        this.themeMenuStructureRebuildCoordinator = themeMenuStructureRebuildCoordinator;
        this.themeMenuStructureRebuildApplyCoordinator = themeMenuStructureRebuildApplyCoordinator;
        this.themeMenuSelectionFlowCoordinator = themeMenuSelectionFlowCoordinator;
        this.themeMenuApplyDispatchCoordinator = themeMenuApplyDispatchCoordinator;
        this.themeMenuApplyCoordinator = themeMenuApplyCoordinator;
    }

    public void ensureReady(@NonNull ThemeMenuContext context) {
        themeMenuReadyDispatchCoordinator.ensureReady(
                context.themeMenuState().themesMenuBuilt(),
                () -> rebuildStructure(context),
                () -> syncSelection(context)
        );
    }

    public void rebuildStructure(@NonNull ThemeMenuContext context) {
        ThemeMenuStructureRebuildCoordinator.RebuildState rebuildState = themeMenuStructureRebuildCoordinator.rebuild(
                context.boundMenusState().themesMenu(),
                context.menuItemsState().themeMenuItemsByName(),
                (themeName, className) -> applyTheme(themeName, className, context),
                context.themeMenuState().themesMenuBuilt(),
                context.themeMenuState().lastMenuSelectedTheme()
        );

        themeMenuStructureRebuildApplyCoordinator.apply(
                rebuildState,
                context.themeMenuState()::setThemesMenuBuilt,
                context.themeMenuState()::setLastMenuSelectedTheme
        );
    }

    public void syncSelection(@NonNull ThemeMenuContext context) {
        themeMenuSelectionFlowCoordinator.refreshAndApply(
                context.menuItemsState().themeMenuItemsByName(),
                context.themeMenuState().lastMenuSelectedTheme(),
                context.themeMenuState().themesMenuBuilt(),
                ThemeSettings.DEFAULT_THEME,
                context.themeMenuState()::setLastMenuSelectedTheme
        );
    }

    public boolean applyTheme(String themeName, String className, @NonNull ThemeMenuContext context) {
        return themeMenuApplyDispatchCoordinator.apply(
                themeName,
                className,
                themeMenuApplyCoordinator::apply,
                context.markModelsMenuDirty(),
                () -> syncSelection(context),
                context.syncFontMenuSelection(),
                context.errorPresenter()
        );
    }

    public record ThemeMenuContext(
            @NonNull MainFrameBoundMenusState boundMenusState,
            @NonNull MainFrameMenuItemsState menuItemsState,
            @NonNull MainFrameThemeMenuState themeMenuState,
            @NonNull Runnable markModelsMenuDirty,
            @NonNull Runnable syncFontMenuSelection,
            @NonNull ThemeMenuApplyDispatchCoordinator.ErrorPresenter errorPresenter
    ) {
    }
}
