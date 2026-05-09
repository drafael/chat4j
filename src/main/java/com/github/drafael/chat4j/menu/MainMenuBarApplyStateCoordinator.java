package com.github.drafael.chat4j.menu;


import lombok.NonNull;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.function.Consumer;

public class MainMenuBarApplyStateCoordinator {

    public void apply(
            @NonNull MainMenuBarEnsureResultApplyCoordinator.ApplyState applyState,
            @NonNull Consumer<JMenuBar> setModelMenuBar,
            @NonNull Consumer<JMenu> setFileMenu,
            @NonNull Consumer<JMenu> setViewMenu,
            @NonNull Consumer<JMenu> setModelsMenu,
            @NonNull Consumer<JMenu> setFontMenu,
            @NonNull Consumer<JMenu> setThemesMenu,
            @NonNull Consumer<JCheckBoxMenuItem> setTogglePreviewMenuItem,
            @NonNull Consumer<Boolean> setModelsMenuDirty,
            @NonNull Consumer<Boolean> setThemesMenuBuilt,
            @NonNull Consumer<Boolean> setFontMenuBuilt
    ) {

        setModelMenuBar.accept(applyState.modelMenuBar());
        setFileMenu.accept(applyState.fileMenu());
        setViewMenu.accept(applyState.viewMenu());
        setModelsMenu.accept(applyState.modelsMenu());
        setFontMenu.accept(applyState.fontMenu());
        setThemesMenu.accept(applyState.themesMenu());
        setTogglePreviewMenuItem.accept(applyState.togglePreviewMenuItem());
        setModelsMenuDirty.accept(applyState.modelsMenuDirty());
        setThemesMenuBuilt.accept(applyState.themesMenuBuilt());
        setFontMenuBuilt.accept(applyState.fontMenuBuilt());
    }
}
