package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;
import javax.swing.JMenuBar;

public class MenuBarAssemblyFactory {

    private final HelpMenuVisibilityResolver helpMenuVisibilityResolver;
    private final HelpMenuFactory helpMenuFactory;

    public MenuBarAssemblyFactory(
            HelpMenuVisibilityResolver helpMenuVisibilityResolver,
            HelpMenuFactory helpMenuFactory
    ) {
        this.helpMenuVisibilityResolver = Validate.notNull(
                helpMenuVisibilityResolver,
                "helpMenuVisibilityResolver must not be null"
        );
        this.helpMenuFactory = Validate.notNull(helpMenuFactory, "helpMenuFactory must not be null");
    }

    public JMenuBar create(
            String appTitle,
            JMenu fileMenu,
            JMenu viewMenu,
            JMenu modelsMenu,
            JMenu fontMenu,
            JMenu themesMenu,
            Runnable onAbout
    ) {
        Validate.notNull(fileMenu, "fileMenu must not be null");
        Validate.notNull(viewMenu, "viewMenu must not be null");
        Validate.notNull(modelsMenu, "modelsMenu must not be null");
        Validate.notNull(fontMenu, "fontMenu must not be null");
        Validate.notNull(themesMenu, "themesMenu must not be null");
        Validate.notNull(onAbout, "onAbout must not be null");

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
