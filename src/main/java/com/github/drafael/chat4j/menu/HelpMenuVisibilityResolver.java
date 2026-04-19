package com.github.drafael.chat4j.menu;

import com.formdev.flatlaf.util.SystemInfo;

public class HelpMenuVisibilityResolver {

    public boolean shouldShowHelpMenu() {
        return shouldShowHelpMenu(
                SystemInfo.isMacOS,
                Boolean.parseBoolean(System.getProperty("apple.laf.useScreenMenuBar", "false"))
        );
    }

    boolean shouldShowHelpMenu(boolean macOs, boolean screenMenuBarEnabled) {
        return !macOs || !screenMenuBarEnabled;
    }
}
