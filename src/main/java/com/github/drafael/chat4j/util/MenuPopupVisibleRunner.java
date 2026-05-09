package com.github.drafael.chat4j.util;


import lombok.NonNull;
import javax.swing.JMenu;

public class MenuPopupVisibleRunner {

    public void runIfVisible(JMenu menu, @NonNull Runnable action) {

        if (menu != null && menu.isPopupMenuVisible()) {
            action.run();
        }
    }
}
