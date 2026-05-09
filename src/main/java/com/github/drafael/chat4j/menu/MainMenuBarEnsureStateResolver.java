package com.github.drafael.chat4j.menu;

import lombok.NonNull;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.function.Supplier;

public class MainMenuBarEnsureStateResolver {

    private final EnsureDispatchAction ensureDispatchAction;
    private final ResultApplyAction resultApplyAction;

    public MainMenuBarEnsureStateResolver(
            MainMenuBarEnsureDispatchCoordinator mainMenuBarEnsureDispatchCoordinator,
            MainMenuBarEnsureResultApplyCoordinator mainMenuBarEnsureResultApplyCoordinator
    ) {
        this(mainMenuBarEnsureDispatchCoordinator::ensure, mainMenuBarEnsureResultApplyCoordinator::apply);
    }

    MainMenuBarEnsureStateResolver(@NonNull EnsureDispatchAction ensureDispatchAction, @NonNull ResultApplyAction resultApplyAction) {
        this.ensureDispatchAction = ensureDispatchAction;
        this.resultApplyAction = resultApplyAction;
    }

    public MainMenuBarEnsureResultApplyCoordinator.ApplyState resolve(
            JMenuBar modelMenuBar,
            @NonNull Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            @NonNull Runnable syncTogglePreviewMenuSelection,
            @NonNull CurrentState currentState
    ) {

        MainMenuBarEnsureDispatchCoordinator.EnsureResult ensureResult = ensureDispatchAction.ensure(
                modelMenuBar,
                menuBarCreator,
                syncTogglePreviewMenuSelection
        );

        return resultApplyAction.apply(
                ensureResult,
                currentState.fileMenu(),
                currentState.viewMenu(),
                currentState.modelsMenu(),
                currentState.fontMenu(),
                currentState.themesMenu(),
                currentState.togglePreviewMenuItem(),
                currentState.modelsMenuDirty(),
                currentState.themesMenuBuilt(),
                currentState.fontMenuBuilt()
        );
    }

    public record CurrentState(
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

    @FunctionalInterface
    interface EnsureDispatchAction {
        MainMenuBarEnsureDispatchCoordinator.EnsureResult ensure(
                JMenuBar existingMenuBar,
                Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
                Runnable syncTogglePreviewMenuSelection
        );
    }

    @FunctionalInterface
    interface ResultApplyAction {
        MainMenuBarEnsureResultApplyCoordinator.ApplyState apply(
                MainMenuBarEnsureDispatchCoordinator.EnsureResult ensureResult,
                JMenu currentFileMenu,
                JMenu currentViewMenu,
                JMenu currentModelsMenu,
                JMenu currentFontMenu,
                JMenu currentThemesMenu,
                JCheckBoxMenuItem currentTogglePreviewMenuItem,
                boolean currentModelsMenuDirty,
                boolean currentThemesMenuBuilt,
                boolean currentFontMenuBuilt
        );
    }
}
