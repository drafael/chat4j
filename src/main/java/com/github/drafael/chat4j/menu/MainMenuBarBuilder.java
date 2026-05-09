package com.github.drafael.chat4j.menu;


import lombok.NonNull;
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
            @NonNull FileMenuFactory fileMenuFactory,
            @NonNull ViewMenuFactory viewMenuFactory,
            @NonNull BoundMenuFactory boundMenuFactory,
            @NonNull MenuBarAssemblyFactory menuBarAssemblyFactory
    ) {
        this.fileMenuFactory = fileMenuFactory;
        this.viewMenuFactory = viewMenuFactory;
        this.boundMenuFactory = boundMenuFactory;
        this.menuBarAssemblyFactory = menuBarAssemblyFactory;
    }

    public CreatedMenuBar create(
            int menuShortcutMask,
            String appTitle,
            @NonNull Runnable onNewChat,
            @NonNull Runnable beforeMenuSelected,
            @NonNull Runnable onViewMenuSelected,
            @NonNull Runnable onToggleSidebar,
            @NonNull Runnable onToggleModelDropdown,
            @NonNull Runnable onChatSearch,
            @NonNull Consumer<Boolean> onTogglePreview,
            @NonNull Runnable onThemesMenuSelected,
            @NonNull Runnable onFontMenuSelected,
            @NonNull Runnable onModelsMenuSelected,
            @NonNull Runnable onAbout
    ) {

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
