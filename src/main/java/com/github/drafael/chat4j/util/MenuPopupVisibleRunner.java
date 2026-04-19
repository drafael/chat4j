package com.github.drafael.chat4j.util;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;

public class MenuPopupVisibleRunner {

    public void runIfVisible(JMenu menu, Runnable action) {
        Validate.notNull(action, "action must not be null");

        if (menu != null && menu.isPopupMenuVisible()) {
            action.run();
        }
    }
}
