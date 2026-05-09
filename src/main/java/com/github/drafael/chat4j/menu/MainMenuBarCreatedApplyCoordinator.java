package com.github.drafael.chat4j.menu;


import lombok.NonNull;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;

public class MainMenuBarCreatedApplyCoordinator {

    public ApplyResult apply(
            @NonNull MainMenuBarBuilder.CreatedMenuBar createdMenuBar,
            @NonNull Runnable syncTogglePreviewMenuSelection
    ) {

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
