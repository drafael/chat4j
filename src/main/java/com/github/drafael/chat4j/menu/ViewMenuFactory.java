package com.github.drafael.chat4j.menu;

import org.apache.commons.lang3.Validate;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

public class ViewMenuFactory {

    private static final String VIEW_MENU_TITLE = "View";
    private static final String TOGGLE_SIDEBAR = "Toggle Sidebar";
    private static final String TOGGLE_MODEL_DROPDOWN = "Toggle Model Dropdown";
    private static final String CHAT_SEARCH = "Chat Search";
    private static final String TOGGLE_PREVIEW = "Toggle Preview";

    private final MenuSelectionListenerBinder menuSelectionListenerBinder;

    public ViewMenuFactory(MenuSelectionListenerBinder menuSelectionListenerBinder) {
        this.menuSelectionListenerBinder = Validate.notNull(
                menuSelectionListenerBinder,
                "menuSelectionListenerBinder must not be null"
        );
    }

    public CreatedViewMenu create(
            int menuShortcutMask,
            Runnable beforeSelect,
            Runnable onMenuSelected,
            Runnable onToggleSidebar,
            Runnable onToggleModelDropdown,
            Runnable onChatSearch,
            Consumer<Boolean> onTogglePreview
    ) {
        Validate.notNull(beforeSelect, "beforeSelect must not be null");
        Validate.notNull(onMenuSelected, "onMenuSelected must not be null");
        Validate.notNull(onToggleSidebar, "onToggleSidebar must not be null");
        Validate.notNull(onToggleModelDropdown, "onToggleModelDropdown must not be null");
        Validate.notNull(onChatSearch, "onChatSearch must not be null");
        Validate.notNull(onTogglePreview, "onTogglePreview must not be null");

        JMenu viewMenu = new JMenu(VIEW_MENU_TITLE);
        menuSelectionListenerBinder.bind(viewMenu, beforeSelect, onMenuSelected);

        JMenuItem toggleSidebarItem = new JMenuItem(TOGGLE_SIDEBAR);
        toggleSidebarItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, menuShortcutMask));
        toggleSidebarItem.addActionListener(e -> onToggleSidebar.run());
        viewMenu.add(toggleSidebarItem);

        JMenuItem toggleModelDropdownItem = new JMenuItem(TOGGLE_MODEL_DROPDOWN);
        toggleModelDropdownItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, menuShortcutMask));
        toggleModelDropdownItem.addActionListener(e -> onToggleModelDropdown.run());
        viewMenu.add(toggleModelDropdownItem);

        JMenuItem chatSearchItem = new JMenuItem(CHAT_SEARCH);
        chatSearchItem.setAccelerator(
                KeyStroke.getKeyStroke(KeyEvent.VK_F, menuShortcutMask | InputEvent.SHIFT_DOWN_MASK)
        );
        chatSearchItem.addActionListener(e -> onChatSearch.run());
        viewMenu.add(chatSearchItem);

        JCheckBoxMenuItem togglePreviewMenuItem = new JCheckBoxMenuItem(TOGGLE_PREVIEW);
        togglePreviewMenuItem.addActionListener(e -> onTogglePreview.accept(togglePreviewMenuItem.isSelected()));
        viewMenu.add(togglePreviewMenuItem);

        return new CreatedViewMenu(viewMenu, togglePreviewMenuItem);
    }

    public record CreatedViewMenu(JMenu menu, JCheckBoxMenuItem togglePreviewMenuItem) {
    }
}
