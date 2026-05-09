package com.github.drafael.chat4j.menu;


import lombok.NonNull;
import javax.swing.JMenu;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

public class MenuSelectionListenerBinder {

    public void bind(@NonNull JMenu menu, @NonNull Runnable beforeSelect, @NonNull Runnable onSelect) {

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
