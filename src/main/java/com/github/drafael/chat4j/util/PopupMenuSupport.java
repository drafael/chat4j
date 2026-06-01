package com.github.drafael.chat4j.util;

import lombok.NonNull;

import javax.swing.JMenu;
import javax.swing.JPopupMenu;

public final class PopupMenuSupport {

    private PopupMenuSupport() {
    }

    public static void preferHeavyweightPopups() {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
    }

    public static JPopupMenu configureNativeSafePopup(@NonNull JPopupMenu popupMenu) {
        popupMenu.setLightWeightPopupEnabled(false);
        return popupMenu;
    }

    public static JMenu configureNativeSafeMenu(@NonNull JMenu menu) {
        configureNativeSafePopup(menu.getPopupMenu());
        return menu;
    }
}
