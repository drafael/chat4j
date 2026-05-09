package com.github.drafael.chat4j.menu;

import lombok.NonNull;

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

    MainMenuBarEnsureDispatchCoordinator(@NonNull EnsureAction ensureAction, @NonNull CreatedApplyAction createdApplyAction) {
        this.ensureAction = ensureAction;
        this.createdApplyAction = createdApplyAction;
    }

    public EnsureResult ensure(
            JMenuBar existingMenuBar,
            @NonNull Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            @NonNull Runnable syncTogglePreviewMenuSelection
    ) {

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
            @NonNull JMenuBar menuBar,
            MainMenuBarCreatedApplyCoordinator.ApplyResult createdApplyResult
    ) {

        public EnsureResult {
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
