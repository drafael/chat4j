package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewMenuFactoryTest {

    @Test
    @DisplayName("Create builds view menu entries with accelerators, menu selection callbacks, and toggle preview callback")
    void create_whenCalled_buildsViewMenuAndWiresCallbacks() {
        var subject = new ViewMenuFactory(new MenuSelectionListenerBinder());
        var beforeSelectCalls = new AtomicInteger();
        var onMenuSelectedCalls = new AtomicInteger();
        var toggleSidebarCalls = new AtomicInteger();
        var toggleModelDropdownCalls = new AtomicInteger();
        var chatSearchCalls = new AtomicInteger();
        var togglePreviewSelections = new ArrayList<Boolean>();

        var createdViewMenu = subject.create(
                KeyEvent.CTRL_DOWN_MASK,
                beforeSelectCalls::incrementAndGet,
                onMenuSelectedCalls::incrementAndGet,
                toggleSidebarCalls::incrementAndGet,
                toggleModelDropdownCalls::incrementAndGet,
                chatSearchCalls::incrementAndGet,
                togglePreviewSelections::add
        );

        var viewMenu = createdViewMenu.menu();
        assertThat(viewMenu.getText()).isEqualTo("View");
        assertThat(viewMenu.getItemCount()).isEqualTo(4);
        assertThat(viewMenu.getItem(0).getText()).isEqualTo("Toggle Sidebar");
        assertThat(viewMenu.getItem(0).getAccelerator())
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK));
        assertThat(viewMenu.getItem(1).getText()).isEqualTo("Toggle Model Dropdown");
        assertThat(viewMenu.getItem(1).getAccelerator())
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, KeyEvent.CTRL_DOWN_MASK));
        assertThat(viewMenu.getItem(2).getText()).isEqualTo("Chat Search");
        assertThat(viewMenu.getItem(2).getAccelerator())
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertThat(viewMenu.getItem(3).getText()).isEqualTo("Toggle Preview");

        var menuListener = viewMenu.getMenuListeners()[0];
        menuListener.menuSelected(new MenuEvent(viewMenu));
        assertThat(beforeSelectCalls.get()).isEqualTo(1);
        assertThat(onMenuSelectedCalls.get()).isEqualTo(1);

        viewMenu.getItem(0).doClick();
        viewMenu.getItem(1).doClick();
        viewMenu.getItem(2).doClick();
        createdViewMenu.togglePreviewMenuItem().doClick();
        createdViewMenu.togglePreviewMenuItem().doClick();

        assertThat(toggleSidebarCalls.get()).isEqualTo(1);
        assertThat(toggleModelDropdownCalls.get()).isEqualTo(1);
        assertThat(chatSearchCalls.get()).isEqualTo(1);
        assertThat(togglePreviewSelections).containsExactly(true, false);
    }

    @Test
    @DisplayName("Create validates required callbacks")
    void create_whenCallbackMissing_throwsException() {
        var subject = new ViewMenuFactory(new MenuSelectionListenerBinder());

        assertThatThrownBy(() -> subject.create(
                KeyEvent.CTRL_DOWN_MASK,
                null,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                selected -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("beforeSelect must not be null");
    }

    @Test
    @DisplayName("Constructor validates listener binder")
    void constructor_whenBinderMissing_throwsException() {
        assertThatThrownBy(() -> new ViewMenuFactory(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuSelectionListenerBinder must not be null");
    }
}
