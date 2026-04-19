package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MainMenuBarEnsureApplyFlowCoordinator {

    private final ResolveAction resolveAction;
    private final ApplyAction applyAction;

    public MainMenuBarEnsureApplyFlowCoordinator(
            MainMenuBarEnsureStateResolver mainMenuBarEnsureStateResolver,
            MainMenuBarApplyStateCoordinator mainMenuBarApplyStateCoordinator
    ) {
        this(mainMenuBarEnsureStateResolver::resolve, mainMenuBarApplyStateCoordinator::apply);
    }

    MainMenuBarEnsureApplyFlowCoordinator(ResolveAction resolveAction, ApplyAction applyAction) {
        this.resolveAction = Validate.notNull(resolveAction, "resolveAction must not be null");
        this.applyAction = Validate.notNull(applyAction, "applyAction must not be null");
    }

    public void ensureAndApply(
            JMenuBar modelMenuBar,
            Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            Runnable syncTogglePreviewMenuSelection,
            MainMenuBarEnsureStateResolver.CurrentState currentState,
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
        Validate.notNull(menuBarCreator, "menuBarCreator must not be null");
        Validate.notNull(syncTogglePreviewMenuSelection, "syncTogglePreviewMenuSelection must not be null");
        Validate.notNull(currentState, "currentState must not be null");
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

        MainMenuBarEnsureResultApplyCoordinator.ApplyState applyState = resolveAction.resolve(
                modelMenuBar,
                menuBarCreator,
                syncTogglePreviewMenuSelection,
                currentState
        );

        applyAction.apply(
                applyState,
                setModelMenuBar,
                setFileMenu,
                setViewMenu,
                setModelsMenu,
                setFontMenu,
                setThemesMenu,
                setTogglePreviewMenuItem,
                setModelsMenuDirty,
                setThemesMenuBuilt,
                setFontMenuBuilt
        );
    }

    @FunctionalInterface
    interface ResolveAction {
        MainMenuBarEnsureResultApplyCoordinator.ApplyState resolve(
                JMenuBar modelMenuBar,
                Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
                Runnable syncTogglePreviewMenuSelection,
                MainMenuBarEnsureStateResolver.CurrentState currentState
        );
    }

    @FunctionalInterface
    interface ApplyAction {
        void apply(
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
        );
    }
}
