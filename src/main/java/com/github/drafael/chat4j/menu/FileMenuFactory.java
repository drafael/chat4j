package com.github.drafael.chat4j.menu;


import lombok.NonNull;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.util.function.BooleanSupplier;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

public class FileMenuFactory {

    private static final String FILE_MENU_TITLE = "File";
    private static final String NEW_CHAT_ITEM_TITLE = "New Chat";
    private static final String EXPORT_PDF_ITEM_TITLE = "Export to PDF…";

    public JMenu create(int menuShortcutMask, @NonNull Runnable onNewChat) {

        JMenu fileMenu = new JMenu(FILE_MENU_TITLE);
        JMenuItem newChatItem = new JMenuItem(NEW_CHAT_ITEM_TITLE);
        newChatItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutMask));
        newChatItem.addActionListener(e -> onNewChat.run());
        fileMenu.add(newChatItem);
        return fileMenu;
    }

    public JMenuItem addExportPdfItem(
            @NonNull JMenu fileMenu,
            @NonNull Runnable onExportPdf,
            @NonNull BooleanSupplier enabledSupplier
    ) {
        fileMenu.addSeparator();
        JMenuItem exportItem = new JMenuItem(EXPORT_PDF_ITEM_TITLE);
        exportItem.setEnabled(enabledSupplier.getAsBoolean());
        exportItem.addActionListener(e -> onExportPdf.run());
        fileMenu.add(exportItem);
        fileMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                exportItem.setEnabled(enabledSupplier.getAsBoolean());
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        return exportItem;
    }
}
