package com.github.drafael.chat4j.menu;

import lombok.NonNull;

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

    MainMenuBarEnsureApplyFlowCoordinator(@NonNull ResolveAction resolveAction, @NonNull ApplyAction applyAction) {
        this.resolveAction = resolveAction;
        this.applyAction = applyAction;
    }

    public void ensureAndApply(
            JMenuBar modelMenuBar,
            @NonNull Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            @NonNull Runnable syncTogglePreviewMenuSelection,
            @NonNull MainMenuBarEnsureStateResolver.CurrentState currentState,
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
