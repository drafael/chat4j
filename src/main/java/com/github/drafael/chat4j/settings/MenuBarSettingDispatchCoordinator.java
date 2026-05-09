package com.github.drafael.chat4j.settings;

import lombok.NonNull;

import javax.swing.JMenuBar;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MenuBarSettingDispatchCoordinator {

    private final ApplyAction applyAction;

    public MenuBarSettingDispatchCoordinator(MenuBarSettingCoordinator menuBarSettingCoordinator) {
        this(menuBarSettingCoordinator::apply);
    }

    MenuBarSettingDispatchCoordinator(@NonNull ApplyAction applyAction) {
        this.applyAction = applyAction;
    }

    public void apply(
            boolean enabled,
            @NonNull Consumer<JMenuBar> menuBarSetter,
            @NonNull Runnable ensureMenuBar,
            @NonNull Supplier<JMenuBar> modelMenuBarSupplier,
            @NonNull Runnable ensureThemesMenuReady,
            @NonNull Runnable ensureModelsMenuReady,
            @NonNull Runnable ensureFontMenuReady,
            @NonNull Runnable syncTogglePreviewMenuSelection,
            @NonNull Runnable refreshWindow
    ) {

        applyAction.apply(
                enabled,
                new MenuBarSettingCoordinator.MenuBarActions(
                        () -> menuBarSetter.accept(null),
                        ensureMenuBar,
                        () -> menuBarSetter.accept(modelMenuBarSupplier.get()),
                        ensureThemesMenuReady,
                        ensureModelsMenuReady,
                        ensureFontMenuReady,
                        syncTogglePreviewMenuSelection,
                        refreshWindow
                )
        );
    }

    @FunctionalInterface
    interface ApplyAction {
        void apply(boolean enabled, MenuBarSettingCoordinator.MenuBarActions actions);
    }
}
