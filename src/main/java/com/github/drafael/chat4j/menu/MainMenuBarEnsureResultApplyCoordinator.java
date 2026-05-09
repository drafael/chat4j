package com.github.drafael.chat4j.menu;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

public class MainMenuBarEnsureResultApplyCoordinator {

    public ApplyState apply(
            @NonNull MainMenuBarEnsureDispatchCoordinator.EnsureResult ensureResult,
            JMenu currentFileMenu,
            JMenu currentViewMenu,
            JMenu currentModelsMenu,
            JMenu currentFontMenu,
            JMenu currentThemesMenu,
            JCheckBoxMenuItem currentTogglePreviewMenuItem,
            boolean currentModelsMenuDirty,
            boolean currentThemesMenuBuilt,
            boolean currentFontMenuBuilt
    ) {

        if (!ensureResult.created()) {
            return new ApplyState(
                    ensureResult.menuBar(),
                    currentFileMenu,
                    currentViewMenu,
                    currentModelsMenu,
                    currentFontMenu,
                    currentThemesMenu,
                    currentTogglePreviewMenuItem,
                    currentModelsMenuDirty,
                    currentThemesMenuBuilt,
                    currentFontMenuBuilt
            );
        }

        MainMenuBarCreatedApplyCoordinator.ApplyResult createdApplyResult = Validate.notNull(
                ensureResult.createdApplyResult(),
                "createdApplyResult must not be null when ensureResult indicates created"
        );

        return new ApplyState(
                ensureResult.menuBar(),
                createdApplyResult.fileMenu(),
                createdApplyResult.viewMenu(),
                createdApplyResult.modelsMenu(),
                createdApplyResult.fontMenu(),
                createdApplyResult.themesMenu(),
                createdApplyResult.togglePreviewMenuItem(),
                createdApplyResult.modelsMenuDirty(),
                createdApplyResult.themesMenuBuilt(),
                createdApplyResult.fontMenuBuilt()
        );
    }

    public record ApplyState(
            @NonNull JMenuBar modelMenuBar,
            JMenu fileMenu,
            JMenu viewMenu,
            JMenu modelsMenu,
            JMenu fontMenu,
            JMenu themesMenu,
            JCheckBoxMenuItem togglePreviewMenuItem,
            boolean modelsMenuDirty,
            boolean themesMenuBuilt,
            boolean fontMenuBuilt
    ) {

        public ApplyState {
        }
    }
}
