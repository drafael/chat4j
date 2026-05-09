package com.github.drafael.chat4j.menu;


import lombok.NonNull;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

public class MenuBarAssemblyFactory {

    private final HelpMenuVisibilityResolver helpMenuVisibilityResolver;
    private final HelpMenuFactory helpMenuFactory;

    public MenuBarAssemblyFactory(
            @NonNull HelpMenuVisibilityResolver helpMenuVisibilityResolver,
            @NonNull HelpMenuFactory helpMenuFactory
    ) {
        this.helpMenuVisibilityResolver = helpMenuVisibilityResolver;
        this.helpMenuFactory = helpMenuFactory;
    }

    public JMenuBar create(
            String appTitle,
            @NonNull JMenu fileMenu,
            @NonNull JMenu viewMenu,
            @NonNull JMenu modelsMenu,
            @NonNull JMenu fontMenu,
            @NonNull JMenu themesMenu,
            @NonNull Runnable onAbout
    ) {

        JMenuBar menuBar = new JMenuBar();
        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        menuBar.add(modelsMenu);
        menuBar.add(fontMenu);
        menuBar.add(themesMenu);

        if (helpMenuVisibilityResolver.shouldShowHelpMenu()) {
            menuBar.add(helpMenuFactory.create(appTitle, onAbout));
        }

        return menuBar;
    }
}
