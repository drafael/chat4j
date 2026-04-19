package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

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

    MainMenuBarEnsureStateResolver(EnsureDispatchAction ensureDispatchAction, ResultApplyAction resultApplyAction) {
        this.ensureDispatchAction = Validate.notNull(ensureDispatchAction, "ensureDispatchAction must not be null");
        this.resultApplyAction = Validate.notNull(resultApplyAction, "resultApplyAction must not be null");
    }

    public MainMenuBarEnsureResultApplyCoordinator.ApplyState resolve(
            JMenuBar modelMenuBar,
            Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            Runnable syncTogglePreviewMenuSelection,
            CurrentState currentState
    ) {
        Validate.notNull(menuBarCreator, "menuBarCreator must not be null");
        Validate.notNull(syncTogglePreviewMenuSelection, "syncTogglePreviewMenuSelection must not be null");
        Validate.notNull(currentState, "currentState must not be null");

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
