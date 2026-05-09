package com.github.drafael.chat4j.menu;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;

public class BoundMenuFactory {

    private final MenuSelectionListenerBinder menuSelectionListenerBinder;

    public BoundMenuFactory(@NonNull MenuSelectionListenerBinder menuSelectionListenerBinder) {
        this.menuSelectionListenerBinder = menuSelectionListenerBinder;
    }

    public JMenu create(String title, @NonNull Runnable beforeSelect, @NonNull Runnable onSelect) {
        Validate.notBlank(title, "title must not be blank");

        JMenu menu = new JMenu(title);
        menuSelectionListenerBinder.bind(menu, beforeSelect, onSelect);
        return menu;
    }
}
