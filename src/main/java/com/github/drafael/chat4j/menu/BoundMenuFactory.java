package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;

public class BoundMenuFactory {

    private final MenuSelectionListenerBinder menuSelectionListenerBinder;

    public BoundMenuFactory(MenuSelectionListenerBinder menuSelectionListenerBinder) {
        this.menuSelectionListenerBinder = Validate.notNull(
                menuSelectionListenerBinder,
                "menuSelectionListenerBinder must not be null"
        );
    }

    public JMenu create(String title, Runnable beforeSelect, Runnable onSelect) {
        Validate.notBlank(title, "title must not be blank");
        Validate.notNull(beforeSelect, "beforeSelect must not be null");
        Validate.notNull(onSelect, "onSelect must not be null");

        JMenu menu = new JMenu(title);
        menuSelectionListenerBinder.bind(menu, beforeSelect, onSelect);
        return menu;
    }
}
