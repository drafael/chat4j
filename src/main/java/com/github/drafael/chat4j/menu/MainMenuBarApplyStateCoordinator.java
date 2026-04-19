package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.function.Consumer;

public class MainMenuBarApplyStateCoordinator {

    public void apply(
            MainMenuBarEnsureResultApplyCoordinator.ApplyState applyState,
            Consumer<JMenuBar> setModelMenuBar,
            Consumer<JMenu> setFileMenu,
            Consumer<JMenu> setViewMenu,
            Consumer<JMenu> setModelsMenu,
            Consumer<JMenu> setFontMenu,
            Consumer<JMenu> setThemesMenu,
            Consumer<JCheckBoxMenuItem> setTogglePreviewMenuItem,
            Consumer<Boolean> setModelsMenuDirty,
            Consumer<Boolean> setThemesMenuBuilt,
            Consumer<Boolean> setFontMenuBuilt
    ) {
        Validate.notNull(applyState, "applyState must not be null");
        Validate.notNull(setModelMenuBar, "setModelMenuBar must not be null");
        Validate.notNull(setFileMenu, "setFileMenu must not be null");
        Validate.notNull(setViewMenu, "setViewMenu must not be null");
        Validate.notNull(setModelsMenu, "setModelsMenu must not be null");
        Validate.notNull(setFontMenu, "setFontMenu must not be null");
        Validate.notNull(setThemesMenu, "setThemesMenu must not be null");
        Validate.notNull(setTogglePreviewMenuItem, "setTogglePreviewMenuItem must not be null");
        Validate.notNull(setModelsMenuDirty, "setModelsMenuDirty must not be null");
        Validate.notNull(setThemesMenuBuilt, "setThemesMenuBuilt must not be null");
        Validate.notNull(setFontMenuBuilt, "setFontMenuBuilt must not be null");

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
