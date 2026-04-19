package com.github.drafael.chat4j.util;

import javax.swing.SwingUtilities;
import java.awt.Window;

public final class WindowUiRefreshSupport {

    private WindowUiRefreshSupport() {
    }

    public static void refreshAllWindows() {
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            Fonts.refreshComponentTreeFonts(window);
            window.invalidate();
            window.validate();
            window.repaint();
        }
    }
}
