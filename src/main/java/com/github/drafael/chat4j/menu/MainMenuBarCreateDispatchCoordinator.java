package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import java.awt.Toolkit;
import java.util.function.Consumer;

public class MainMenuBarCreateDispatchCoordinator {

    private final ShortcutMaskSupplier shortcutMaskSupplier;
    private final CreateAction createAction;

    public MainMenuBarCreateDispatchCoordinator(MainMenuBarBuilder mainMenuBarBuilder) {
        this(
                () -> Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(),
                mainMenuBarBuilder::create
        );
    }

    MainMenuBarCreateDispatchCoordinator(ShortcutMaskSupplier shortcutMaskSupplier, CreateAction createAction) {
        this.shortcutMaskSupplier = Validate.notNull(shortcutMaskSupplier, "shortcutMaskSupplier must not be null");
        this.createAction = Validate.notNull(createAction, "createAction must not be null");
    }

    public MainMenuBarBuilder.CreatedMenuBar create(
            String appTitle,
            Runnable onNewChat,
            Runnable beforeMenuSelected,
            Runnable onViewMenuSelected,
            Runnable onToggleSidebar,
            Runnable onToggleModelDropdown,
            Runnable onChatSearch,
            Consumer<Boolean> onTogglePreview,
            Runnable onThemesMenuSelected,
            Runnable onFontMenuSelected,
            Runnable onModelsMenuSelected,
            Runnable onAbout
    ) {
        return createAction.create(
                shortcutMaskSupplier.resolve(),
                appTitle,
                onNewChat,
                beforeMenuSelected,
                onViewMenuSelected,
                onToggleSidebar,
                onToggleModelDropdown,
                onChatSearch,
                onTogglePreview,
                onThemesMenuSelected,
                onFontMenuSelected,
                onModelsMenuSelected,
                onAbout
        );
    }

    @FunctionalInterface
    interface ShortcutMaskSupplier {
        int resolve();
    }

    @FunctionalInterface
    interface CreateAction {
        MainMenuBarBuilder.CreatedMenuBar create(
                int menuShortcutMask,
                String appTitle,
                Runnable onNewChat,
                Runnable beforeMenuSelected,
                Runnable onViewMenuSelected,
                Runnable onToggleSidebar,
                Runnable onToggleModelDropdown,
                Runnable onChatSearch,
                Consumer<Boolean> onTogglePreview,
                Runnable onThemesMenuSelected,
                Runnable onFontMenuSelected,
                Runnable onModelsMenuSelected,
                Runnable onAbout
        );
    }
}
