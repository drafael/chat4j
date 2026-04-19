package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenuBar;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MainMenuBarEnsureDispatchCoordinator {

    private final EnsureAction ensureAction;
    private final CreatedApplyAction createdApplyAction;

    public MainMenuBarEnsureDispatchCoordinator(
            MainMenuBarEnsureCoordinator mainMenuBarEnsureCoordinator,
            MainMenuBarCreatedApplyCoordinator mainMenuBarCreatedApplyCoordinator
    ) {
        this(
                mainMenuBarEnsureCoordinator::ensure,
                mainMenuBarCreatedApplyCoordinator::apply
        );
    }

    MainMenuBarEnsureDispatchCoordinator(EnsureAction ensureAction, CreatedApplyAction createdApplyAction) {
        this.ensureAction = Validate.notNull(ensureAction, "ensureAction must not be null");
        this.createdApplyAction = Validate.notNull(createdApplyAction, "createdApplyAction must not be null");
    }

    public EnsureResult ensure(
            JMenuBar existingMenuBar,
            Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            Runnable syncTogglePreviewMenuSelection
    ) {
        Validate.notNull(menuBarCreator, "menuBarCreator must not be null");
        Validate.notNull(syncTogglePreviewMenuSelection, "syncTogglePreviewMenuSelection must not be null");

        var createdApplyResultRef = new AtomicReference<MainMenuBarCreatedApplyCoordinator.ApplyResult>();
        JMenuBar menuBar = ensureAction.ensure(
                existingMenuBar,
                menuBarCreator,
                createdMenuBar -> createdApplyResultRef.set(
                        createdApplyAction.apply(createdMenuBar, syncTogglePreviewMenuSelection)
                )
        );

        return new EnsureResult(menuBar, createdApplyResultRef.get());
    }

    public record EnsureResult(
            JMenuBar menuBar,
            MainMenuBarCreatedApplyCoordinator.ApplyResult createdApplyResult
    ) {

        public EnsureResult {
            Validate.notNull(menuBar, "menuBar must not be null");
        }

        public boolean created() {
            return createdApplyResult != null;
        }
    }

    @FunctionalInterface
    interface EnsureAction {
        JMenuBar ensure(
                JMenuBar existingMenuBar,
                Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
                Consumer<MainMenuBarBuilder.CreatedMenuBar> onCreated
        );
    }

    @FunctionalInterface
    interface CreatedApplyAction {
        MainMenuBarCreatedApplyCoordinator.ApplyResult apply(
                MainMenuBarBuilder.CreatedMenuBar createdMenuBar,
                Runnable syncTogglePreviewMenuSelection
        );
    }
}
