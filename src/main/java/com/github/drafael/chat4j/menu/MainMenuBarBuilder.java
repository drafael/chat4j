package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.function.Consumer;

public class MainMenuBarBuilder {

    private final FileMenuFactory fileMenuFactory;
    private final ViewMenuFactory viewMenuFactory;
    private final BoundMenuFactory boundMenuFactory;
    private final MenuBarAssemblyFactory menuBarAssemblyFactory;

    public MainMenuBarBuilder(
            FileMenuFactory fileMenuFactory,
            ViewMenuFactory viewMenuFactory,
            BoundMenuFactory boundMenuFactory,
            MenuBarAssemblyFactory menuBarAssemblyFactory
    ) {
        this.fileMenuFactory = Validate.notNull(fileMenuFactory, "fileMenuFactory must not be null");
        this.viewMenuFactory = Validate.notNull(viewMenuFactory, "viewMenuFactory must not be null");
        this.boundMenuFactory = Validate.notNull(boundMenuFactory, "boundMenuFactory must not be null");
        this.menuBarAssemblyFactory = Validate.notNull(
                menuBarAssemblyFactory,
                "menuBarAssemblyFactory must not be null"
        );
    }

    public CreatedMenuBar create(
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
    ) {
        Validate.notNull(onNewChat, "onNewChat must not be null");
        Validate.notNull(beforeMenuSelected, "beforeMenuSelected must not be null");
        Validate.notNull(onViewMenuSelected, "onViewMenuSelected must not be null");
        Validate.notNull(onToggleSidebar, "onToggleSidebar must not be null");
        Validate.notNull(onToggleModelDropdown, "onToggleModelDropdown must not be null");
        Validate.notNull(onChatSearch, "onChatSearch must not be null");
        Validate.notNull(onTogglePreview, "onTogglePreview must not be null");
        Validate.notNull(onThemesMenuSelected, "onThemesMenuSelected must not be null");
        Validate.notNull(onFontMenuSelected, "onFontMenuSelected must not be null");
        Validate.notNull(onModelsMenuSelected, "onModelsMenuSelected must not be null");
        Validate.notNull(onAbout, "onAbout must not be null");

        JMenu fileMenu = fileMenuFactory.create(menuShortcutMask, onNewChat);
        ViewMenuFactory.CreatedViewMenu createdViewMenu = viewMenuFactory.create(
                menuShortcutMask,
                beforeMenuSelected,
                onViewMenuSelected,
                onToggleSidebar,
                onToggleModelDropdown,
                onChatSearch,
                onTogglePreview
        );

        JMenu themesMenu = boundMenuFactory.create("Theme", beforeMenuSelected, onThemesMenuSelected);
        JMenu fontMenu = boundMenuFactory.create("Font", beforeMenuSelected, onFontMenuSelected);
        JMenu modelsMenu = boundMenuFactory.create("Model", beforeMenuSelected, onModelsMenuSelected);

        JMenuBar menuBar = menuBarAssemblyFactory.create(
                appTitle,
                fileMenu,
                createdViewMenu.menu(),
                modelsMenu,
                fontMenu,
                themesMenu,
                onAbout
        );

        return new CreatedMenuBar(
                menuBar,
                fileMenu,
                createdViewMenu.menu(),
                modelsMenu,
                fontMenu,
                themesMenu,
                createdViewMenu.togglePreviewMenuItem()
        );
    }

    public record CreatedMenuBar(
            JMenuBar menuBar,
            JMenu fileMenu,
            JMenu viewMenu,
            JMenu modelsMenu,
            JMenu fontMenu,
            JMenu themesMenu,
            JCheckBoxMenuItem togglePreviewMenuItem
    ) {
    }
}
