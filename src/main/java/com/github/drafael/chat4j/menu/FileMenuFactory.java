package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;

public class FileMenuFactory {

    private static final String FILE_MENU_TITLE = "File";
    private static final String NEW_CHAT_ITEM_TITLE = "New Chat";

    public JMenu create(int menuShortcutMask, Runnable onNewChat) {
        Validate.notNull(onNewChat, "onNewChat must not be null");

        JMenu fileMenu = new JMenu(FILE_MENU_TITLE);
        JMenuItem newChatItem = new JMenuItem(NEW_CHAT_ITEM_TITLE);
        newChatItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutMask));
        newChatItem.addActionListener(e -> onNewChat.run());
        fileMenu.add(newChatItem);
        return fileMenu;
    }
}
