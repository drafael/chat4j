package com.github.drafael.chat4j;

import com.github.drafael.chat4j.settings.AppFontSizeAdjustCoordinator;
import com.github.drafael.chat4j.settings.AppearancePanel;
import com.github.drafael.chat4j.settings.FontMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuReadyDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.FontSettingsResolver;
import lombok.NonNull;

public class MainFrameFontMenuCoordinator {

    private final FontMenuReadyDispatchCoordinator fontMenuReadyDispatchCoordinator;
    private final FontMenuStructureRebuildCoordinator fontMenuStructureRebuildCoordinator;
    private final FontMenuStructureRebuildApplyCoordinator fontMenuStructureRebuildApplyCoordinator;
    private final FontMenuSelectionFlowCoordinator fontMenuSelectionFlowCoordinator;
    private final FontMenuApplyDispatchCoordinator fontMenuApplyDispatchCoordinator;
    private final FontMenuApplyCoordinator fontMenuApplyCoordinator;
    private final FontSettingsResolver fontSettingsResolver;
    private final AppFontSizeAdjustCoordinator appFontSizeAdjustCoordinator;

    public MainFrameFontMenuCoordinator(
            @NonNull FontMenuReadyDispatchCoordinator fontMenuReadyDispatchCoordinator,
            @NonNull FontMenuStructureRebuildCoordinator fontMenuStructureRebuildCoordinator,
            @NonNull FontMenuStructureRebuildApplyCoordinator fontMenuStructureRebuildApplyCoordinator,
            @NonNull FontMenuSelectionFlowCoordinator fontMenuSelectionFlowCoordinator,
            @NonNull FontMenuApplyDispatchCoordinator fontMenuApplyDispatchCoordinator,
            @NonNull FontMenuApplyCoordinator fontMenuApplyCoordinator,
            @NonNull FontSettingsResolver fontSettingsResolver,
            @NonNull AppFontSizeAdjustCoordinator appFontSizeAdjustCoordinator
    ) {
        this.fontMenuReadyDispatchCoordinator = fontMenuReadyDispatchCoordinator;
        this.fontMenuStructureRebuildCoordinator = fontMenuStructureRebuildCoordinator;
        this.fontMenuStructureRebuildApplyCoordinator = fontMenuStructureRebuildApplyCoordinator;
        this.fontMenuSelectionFlowCoordinator = fontMenuSelectionFlowCoordinator;
        this.fontMenuApplyDispatchCoordinator = fontMenuApplyDispatchCoordinator;
        this.fontMenuApplyCoordinator = fontMenuApplyCoordinator;
        this.fontSettingsResolver = fontSettingsResolver;
        this.appFontSizeAdjustCoordinator = appFontSizeAdjustCoordinator;
    }

    public void ensureReady(@NonNull FontMenuContext context) {
        fontMenuReadyDispatchCoordinator.ensureReady(
                context.fontMenuState().fontMenuBuilt(),
                () -> rebuildStructure(context),
                () -> syncSelection(context)
        );
    }

    public void rebuildStructure(@NonNull FontMenuContext context) {
        FontMenuStructureRebuildCoordinator.RebuildState rebuildState = fontMenuStructureRebuildCoordinator.rebuild(
                context.boundMenusState().fontMenu(),
                context.menuItemsState().appFontMenuItemsByFamily(),
                context.menuItemsState().appFontSizeMenuItemsBySize(),
                context.menuItemsState().codeFontMenuItemsByFamily(),
                () -> restoreAppFont(context),
                () -> adjustAppFontSize(context, true),
                () -> adjustAppFontSize(context, false),
                fontFamily -> applyAppFontFamily(context, fontFamily),
                fontFamily -> applyCodeFont(context, fontFamily),
                appFontSize -> applyAppFontSize(context, appFontSize),
                context.fontMenuState().fontMenuBuilt(),
                context.fontMenuState().lastMenuSelectedAppFontFamily(),
                context.fontMenuState().lastMenuSelectedAppFontSize(),
                context.fontMenuState().lastMenuSelectedCodeFontFamily()
        );

        fontMenuStructureRebuildApplyCoordinator.apply(
                rebuildState,
                context.fontMenuState()::setFontMenuBuilt,
                context.fontMenuState()::setLastMenuSelectedAppFontFamily,
                context.fontMenuState()::setLastMenuSelectedAppFontSize,
                context.fontMenuState()::setLastMenuSelectedCodeFontFamily
        );
    }

    public void syncSelection(@NonNull FontMenuContext context) {
        fontMenuSelectionFlowCoordinator.refreshAndApply(
                context.menuItemsState().appFontMenuItemsByFamily(),
                context.menuItemsState().appFontSizeMenuItemsBySize(),
                context.menuItemsState().codeFontMenuItemsByFamily(),
                context.fontMenuState().lastMenuSelectedAppFontFamily(),
                context.fontMenuState().lastMenuSelectedAppFontSize(),
                context.fontMenuState().lastMenuSelectedCodeFontFamily(),
                context.fontMenuState().fontMenuBuilt(),
                context.fontMenuState()::setLastMenuSelectedAppFontFamily,
                context.fontMenuState()::setLastMenuSelectedAppFontSize,
                context.fontMenuState()::setLastMenuSelectedCodeFontFamily
        );
    }

    public boolean applyAppFontFamily(@NonNull FontMenuContext context, String fontFamily) {
        return applyAppFontSelection(context, fontFamily, fontSettingsResolver.resolveAppFontSizeSetting());
    }

    public boolean applyAppFontSize(@NonNull FontMenuContext context, int appFontSize) {
        return applyAppFontSelection(context, fontSettingsResolver.resolveAppFontFamilySetting(), appFontSize);
    }

    public boolean applyAppFontSelection(@NonNull FontMenuContext context, String appFontFamily, int appFontSize) {
        return fontMenuApplyDispatchCoordinator.apply(
                () -> fontMenuApplyCoordinator.applyAppFontSelection(
                        appFontFamily,
                        appFontSize,
                        context.menuItemsState().appFontMenuItemsByFamily().keySet(),
                        () -> syncSelection(context)
                ),
                "Failed to apply UI font: ",
                context.errorPresenter()
        );
    }

    public boolean applyCodeFont(@NonNull FontMenuContext context, String fontFamily) {
        return fontMenuApplyDispatchCoordinator.apply(
                () -> fontMenuApplyCoordinator.applyCodeFontSelection(
                        fontFamily,
                        context.menuItemsState().codeFontMenuItemsByFamily().keySet(),
                        () -> syncSelection(context)
                ),
                "Failed to apply code font: ",
                context.errorPresenter()
        );
    }

    public boolean restoreAppFont(@NonNull FontMenuContext context) {
        return applyAppFontSelection(context, AppearancePanel.DEFAULT_APP_FONT, AppearancePanel.defaultAppFontSize());
    }

    public int adjustAppFontSize(@NonNull FontMenuContext context, boolean increase) {
        return appFontSizeAdjustCoordinator.adjust(
                increase,
                AppearancePanel::appFontSizeOptions,
                fontSettingsResolver::resolveAppFontSizeSetting,
                appFontSize -> applyAppFontSize(context, appFontSize)
        );
    }

    public record FontMenuContext(
            @NonNull MainFrameBoundMenusState boundMenusState,
            @NonNull MainFrameMenuItemsState menuItemsState,
            @NonNull MainFrameFontMenuState fontMenuState,
            @NonNull FontMenuApplyDispatchCoordinator.ErrorPresenter errorPresenter
    ) {
    }
}
