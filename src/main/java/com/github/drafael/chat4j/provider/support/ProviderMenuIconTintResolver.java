package com.github.drafael.chat4j.provider.support;

import com.formdev.flatlaf.util.SystemInfo;
import lombok.NonNull;

import javax.swing.JMenuItem;
import javax.swing.UIManager;
import java.awt.Color;

public class ProviderMenuIconTintResolver {

    private static final Color MAC_MENU_ICON_ENABLED = new Color(66, 66, 66);
    private static final Color MAC_MENU_ICON_DISABLED = new Color(150, 150, 150);

    public Color resolve(JMenuItem item, boolean enabled) {
        return resolve(
                item,
                enabled,
                SystemInfo.isMacOS,
                Boolean.parseBoolean(System.getProperty("apple.laf.useScreenMenuBar", "false"))
        );
    }

    Color resolve(@NonNull JMenuItem item, boolean enabled, boolean macOs, boolean screenMenuBarEnabled) {

        if (macOs && screenMenuBarEnabled) {
            return enabled ? MAC_MENU_ICON_ENABLED : MAC_MENU_ICON_DISABLED;
        }

        if (enabled) {
            return ProviderMenuIconRenderer.opaqueColor(item.getForeground(), new Color(55, 55, 55));
        }

        Color disabledForeground = UIManager.getColor("MenuItem.disabledForeground");
        return ProviderMenuIconRenderer.opaqueColor(disabledForeground, new Color(140, 140, 140));
    }
}
