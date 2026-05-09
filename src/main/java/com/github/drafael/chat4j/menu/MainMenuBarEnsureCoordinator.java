package com.github.drafael.chat4j.menu;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.JMenuBar;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MainMenuBarEnsureCoordinator {

    public JMenuBar ensure(
            JMenuBar existingMenuBar,
            @NonNull Supplier<MainMenuBarBuilder.CreatedMenuBar> menuBarCreator,
            @NonNull Consumer<MainMenuBarBuilder.CreatedMenuBar> onCreated
    ) {

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
