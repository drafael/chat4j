package com.github.drafael.chat4j.menu;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

public class HelpMenuFactory {

    public JMenu create(String appTitle, @NonNull Runnable onAbout) {
        Validate.notBlank(appTitle, "appTitle must not be blank");

        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About %s".formatted(appTitle));
        aboutItem.addActionListener(e -> onAbout.run());
        helpMenu.add(aboutItem);
        return helpMenu;
    }
}
