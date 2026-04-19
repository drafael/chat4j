package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.event.MenuEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuSelectionListenerBinderTest {

    @Test
    @DisplayName("Bind runs callbacks in order only when menu is selected")
    void bind_whenMenuSelected_runsCallbacksInOrder() {
        var subject = new MenuSelectionListenerBinder();
        JMenu menu = new JMenu("Test");
        AtomicInteger callSequence = new AtomicInteger();
        AtomicInteger beforeSelectOrder = new AtomicInteger();
        AtomicInteger onSelectOrder = new AtomicInteger();

        subject.bind(
                menu,
                () -> beforeSelectOrder.set(callSequence.incrementAndGet()),
                () -> onSelectOrder.set(callSequence.incrementAndGet())
        );

        var listener = menu.getMenuListeners()[0];
        var event = new MenuEvent(menu);

        listener.menuSelected(event);
        assertThat(beforeSelectOrder.get()).isEqualTo(1);
        assertThat(onSelectOrder.get()).isEqualTo(2);

        callSequence.set(0);
        beforeSelectOrder.set(0);
        onSelectOrder.set(0);

        listener.menuDeselected(event);
        listener.menuCanceled(event);

        assertThat(beforeSelectOrder.get()).isZero();
        assertThat(onSelectOrder.get()).isZero();
    }

    @Test
    @DisplayName("Bind validates required arguments")
    void bind_whenArgumentMissing_throwsException() {
        var subject = new MenuSelectionListenerBinder();

        assertThatThrownBy(() -> subject.bind(null, () -> {
        }, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menu must not be null");

        assertThatThrownBy(() -> subject.bind(new JMenu("x"), null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("beforeSelect must not be null");

        assertThatThrownBy(() -> subject.bind(new JMenu("x"), () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onSelect must not be null");
    }
}
