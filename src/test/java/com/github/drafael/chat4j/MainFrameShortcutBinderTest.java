package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameShortcutBinderTest {

    @Test
    @DisplayName("Bind registers expected shortcuts and invokes mapped actions")
    void bind_whenCalled_registersShortcutsAndInvokesActions() {
        var rootPane = new JRootPane();
        var newChatCalls = new AtomicInteger();
        var settingsCalls = new AtomicInteger();
        var sidebarCalls = new AtomicInteger();
        var searchCalls = new AtomicInteger();
        var modelCalls = new AtomicInteger();

        var actions = new MainFrameShortcutBinder.ShortcutActions(
                newChatCalls::incrementAndGet,
                settingsCalls::incrementAndGet,
                sidebarCalls::incrementAndGet,
                searchCalls::incrementAndGet,
                modelCalls::incrementAndGet
        );

        MainFrameShortcutBinder.bind(rootPane, "ctrl", actions);

        assertThat(mappedActionKey(rootPane, "ctrl N")).isEqualTo("newChat");
        assertThat(mappedActionKey(rootPane, "ctrl COMMA")).isEqualTo("openSettings");
        assertThat(mappedActionKey(rootPane, "ctrl B")).isEqualTo("toggleSidebar");
        assertThat(mappedActionKey(rootPane, "ctrl shift F")).isEqualTo("openChatSearch");
        assertThat(mappedActionKey(rootPane, "ctrl SLASH")).isEqualTo("toggleModelDropdown");

        trigger(rootPane, "newChat");
        trigger(rootPane, "openSettings");
        trigger(rootPane, "toggleSidebar");
        trigger(rootPane, "openChatSearch");
        trigger(rootPane, "toggleModelDropdown");

        assertThat(newChatCalls.get()).isEqualTo(1);
        assertThat(settingsCalls.get()).isEqualTo(1);
        assertThat(sidebarCalls.get()).isEqualTo(1);
        assertThat(searchCalls.get()).isEqualTo(1);
        assertThat(modelCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Bind rejects unsupported modifier")
    void bind_whenModifierIsUnsupported_throwsException() {
        var rootPane = new JRootPane();
        var actions = new MainFrameShortcutBinder.ShortcutActions(
                () -> { },
                () -> { },
                () -> { },
                () -> { },
                () -> { }
        );

        assertThatThrownBy(() -> MainFrameShortcutBinder.bind(rootPane, "alt", actions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modifier must be either");
    }

    private String mappedActionKey(JRootPane rootPane, String keyStroke) {
        Object actionKey = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(keyStroke));
        return actionKey == null ? null : actionKey.toString();
    }

    private void trigger(JRootPane rootPane, String actionKey) {
        Action action = rootPane.getActionMap().get(actionKey);
        assertThat(action).isNotNull();
        action.actionPerformed(new ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, actionKey));
    }
}
