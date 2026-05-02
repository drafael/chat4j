package com.github.drafael.chat4j;

import lombok.NonNull;
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

    public static void bindDefault(@NonNull JRootPane rootPane, @NonNull ShortcutActions actions) {
        String modifier = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK
                ? "meta"
                : "ctrl";
        bind(rootPane, modifier, actions);
    }

    static void bind(@NonNull JRootPane rootPane, String modifier, @NonNull ShortcutActions actions) {
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
        registerShortcut(rootPane, normalizedModifier, "P", "openCommandCenter", actions.openCommandCenter());
        registerShortcut(rootPane, normalizedModifier, "SLASH", "toggleModelDropdown", actions.toggleModelDropdown());
    }

    private static void registerShortcut(
            JRootPane rootPane,
            String modifier,
            String key,
            String actionKey,
            @NonNull Runnable action
    ) {
        Validate.notBlank(modifier, "modifier must not be blank");
        Validate.notBlank(key, "key must not be blank");
        Validate.notBlank(actionKey, "actionKey must not be blank");

        KeyStroke keyStroke = KeyStroke.getKeyStroke("%s %s".formatted(modifier, key));
        Validate.validState(keyStroke != null, "Invalid key stroke: %s %s".formatted(modifier, key));

        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionKey);
        rootPane.getActionMap().put(actionKey, runnableAction(action));
    }

    private static Action runnableAction(@NonNull Runnable action) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };
    }

    public record ShortcutActions(
            @NonNull Runnable newChat,
            @NonNull Runnable openSettings,
            @NonNull Runnable toggleSidebar,
            @NonNull Runnable openChatSearch,
            @NonNull Runnable openCommandCenter,
            @NonNull Runnable toggleModelDropdown
    ) {
    }
}
