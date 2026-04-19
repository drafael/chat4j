package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

public class MenuSelectionListenerBinder {

    public void bind(JMenu menu, Runnable beforeSelect, Runnable onSelect) {
        Validate.notNull(menu, "menu must not be null");
        Validate.notNull(beforeSelect, "beforeSelect must not be null");
        Validate.notNull(onSelect, "onSelect must not be null");

        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                beforeSelect.run();
                onSelect.run();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
    }
}
