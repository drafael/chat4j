package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenuBar;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MenuBarSettingDispatchCoordinator {

    private final ApplyAction applyAction;

    public MenuBarSettingDispatchCoordinator(MenuBarSettingCoordinator menuBarSettingCoordinator) {
        this(menuBarSettingCoordinator::apply);
    }

    MenuBarSettingDispatchCoordinator(ApplyAction applyAction) {
        this.applyAction = Validate.notNull(applyAction, "applyAction must not be null");
    }

    public void apply(
            boolean enabled,
            Consumer<JMenuBar> menuBarSetter,
            Runnable ensureMenuBar,
            Supplier<JMenuBar> modelMenuBarSupplier,
            Runnable ensureThemesMenuReady,
            Runnable ensureModelsMenuReady,
            Runnable ensureFontMenuReady,
            Runnable syncTogglePreviewMenuSelection,
            Runnable refreshWindow
    ) {
        Validate.notNull(menuBarSetter, "menuBarSetter must not be null");
        Validate.notNull(ensureMenuBar, "ensureMenuBar must not be null");
        Validate.notNull(modelMenuBarSupplier, "modelMenuBarSupplier must not be null");
        Validate.notNull(ensureThemesMenuReady, "ensureThemesMenuReady must not be null");
        Validate.notNull(ensureModelsMenuReady, "ensureModelsMenuReady must not be null");
        Validate.notNull(ensureFontMenuReady, "ensureFontMenuReady must not be null");
        Validate.notNull(
                syncTogglePreviewMenuSelection,
                "syncTogglePreviewMenuSelection must not be null"
        );
        Validate.notNull(refreshWindow, "refreshWindow must not be null");

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
