package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;

public class MainMenuBarCreatedApplyCoordinator {

    public ApplyResult apply(
            MainMenuBarBuilder.CreatedMenuBar createdMenuBar,
            Runnable syncTogglePreviewMenuSelection
    ) {
        Validate.notNull(createdMenuBar, "createdMenuBar must not be null");
        Validate.notNull(syncTogglePreviewMenuSelection, "syncTogglePreviewMenuSelection must not be null");

        syncTogglePreviewMenuSelection.run();

        return new ApplyResult(
                createdMenuBar.fileMenu(),
                createdMenuBar.viewMenu(),
                createdMenuBar.modelsMenu(),
                createdMenuBar.fontMenu(),
                createdMenuBar.themesMenu(),
                createdMenuBar.togglePreviewMenuItem(),
                true,
                false,
                false
        );
    }

    public record ApplyResult(
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
    }
}
