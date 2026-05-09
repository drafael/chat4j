package com.github.drafael.chat4j.menu;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

public class MenuSectionHeaderFactory {

    public JMenuItem create(String text) {
        Validate.notBlank(text, "text must not be blank");

        JMenuItem sectionHeader = new JMenuItem(text);
        sectionHeader.setEnabled(false);
        return sectionHeader;
    }

    public void addTo(@NonNull JMenu menu, String text) {
        menu.add(create(text));
    }
}
