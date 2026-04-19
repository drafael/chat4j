package com.github.drafael.chat4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;

public final class MainFrameShortcutBinder {

    private MainFrameShortcutBinder() {
    }

    public static void bindDefault(JRootPane rootPane, ShortcutActions actions) {
        String modifier = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK
                ? "meta"
                : "ctrl";
        bind(rootPane, modifier, actions);
    }

    static void bind(JRootPane rootPane, String modifier, ShortcutActions actions) {
        Validate.notNull(rootPane, "rootPane must not be null");
        Validate.notNull(actions, "actions must not be null");
        Validate.notBlank(modifier, "modifier must not be blank");

        String normalizedModifier = modifier.trim().toLowerCase();
        Validate.isTrue(
                "meta".equals(normalizedModifier) || "ctrl".equals(normalizedModifier),
                "modifier must be either 'meta' or 'ctrl', got: %s",
                modifier
        );

        registerShortcut(rootPane, normalizedModifier, "N", "newChat", actions.newChat());
        registerShortcut(rootPane, normalizedModifier, "COMMA", "openSettings", actions.openSettings());
        registerShortcut(rootPane, normalizedModifier, "B", "toggleSidebar", actions.toggleSidebar());
        registerShortcut(rootPane, "%s shift".formatted(normalizedModifier), "F", "openChatSearch", actions.openChatSearch());
        registerShortcut(rootPane, normalizedModifier, "SLASH", "toggleModelDropdown", actions.toggleModelDropdown());
    }

    private static void registerShortcut(
            JRootPane rootPane,
            String modifier,
            String key,
            String actionKey,
            Runnable action
    ) {
        Validate.notBlank(modifier, "modifier must not be blank");
        Validate.notBlank(key, "key must not be blank");
        Validate.notBlank(actionKey, "actionKey must not be blank");
        Validate.notNull(action, "action must not be null");

        KeyStroke keyStroke = KeyStroke.getKeyStroke("%s %s".formatted(modifier, key));
        Validate.validState(keyStroke != null, "Invalid key stroke: %s %s".formatted(modifier, key));

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionKey);
        rootPane.getActionMap().put(actionKey, runnableAction(action));
    }

    private static Action runnableAction(Runnable action) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };
    }

    public record ShortcutActions(
            Runnable newChat,
            Runnable openSettings,
            Runnable toggleSidebar,
            Runnable openChatSearch,
            Runnable toggleModelDropdown
    ) {

        public ShortcutActions {
            newChat = validateAction(newChat, "newChat");
            openSettings = validateAction(openSettings, "openSettings");
            toggleSidebar = validateAction(toggleSidebar, "toggleSidebar");
            openChatSearch = validateAction(openChatSearch, "openChatSearch");
            toggleModelDropdown = validateAction(toggleModelDropdown, "toggleModelDropdown");
        }

        private static Runnable validateAction(Runnable action, String name) {
            Validate.notBlank(name, "name must not be blank");
            return Validate.notNull(action, "%s action must not be null".formatted(StringUtils.defaultString(name)));
        }
    }
}
