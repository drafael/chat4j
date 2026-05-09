package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.event.MenuEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundMenuFactoryTest {

    @Test
    @DisplayName("Create builds titled menu and binds selection callbacks")
    void create_whenCalled_buildsMenuAndBindsSelectionCallbacks() {
        var subject = new BoundMenuFactory(new MenuSelectionListenerBinder());
        var beforeCalls = new AtomicInteger();
        var onSelectCalls = new AtomicInteger();

        var menu = subject.create("Theme", beforeCalls::incrementAndGet, onSelectCalls::incrementAndGet);

        assertThat(menu.getText()).isEqualTo("Theme");
        assertThat(menu.getMenuListeners()).hasSize(1);

        menu.getMenuListeners()[0].menuSelected(new MenuEvent(menu));
        assertThat(beforeCalls.get()).isEqualTo(1);
        assertThat(onSelectCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Create validates title and callbacks")
    void create_whenArgumentsInvalid_throwsException() {
        var subject = new BoundMenuFactory(new MenuSelectionListenerBinder());

        assertThatThrownBy(() -> subject.create("  ", () -> {
        }, () -> {
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title must not be blank");

        assertThatThrownBy(() -> subject.create("Theme", null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("beforeSelect");

        assertThatThrownBy(() -> subject.create("Theme", () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onSelect");
    }

    @Test
    @DisplayName("Constructor validates listener binder")
    void constructor_whenBinderMissing_throwsException() {
        assertThatThrownBy(() -> new BoundMenuFactory(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuSelectionListenerBinder");
    }
}
