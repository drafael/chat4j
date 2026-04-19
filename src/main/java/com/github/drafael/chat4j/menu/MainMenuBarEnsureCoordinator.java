package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenuBar;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MainMenuBarEnsureCoordinator {

    public JMenuBar ensure(
            JMenuBar existingMenuBar,
            Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            Consumer<MainMenuBarBuilder.CreatedMenuBar> onCreated
    ) {
        Validate.notNull(menuBarCreator, "menuBarCreator must not be null");
        Validate.notNull(onCreated, "onCreated must not be null");

        if (existingMenuBar != null) {
            return existingMenuBar;
        }

        MainMenuBarBuilder.CreatedMenuBar createdMenuBar = Validate.notNull(
                menuBarCreator.get(),
                "createdMenuBar must not be null"
        );
        onCreated.accept(createdMenuBar);
        return createdMenuBar.menuBar();
    }
}
