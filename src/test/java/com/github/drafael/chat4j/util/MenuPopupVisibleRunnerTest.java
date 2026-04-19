package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MenuPopupVisibleRunnerTest {

    private final MenuPopupVisibleRunner subject = new MenuPopupVisibleRunner();

    @Test
    @DisplayName("Run if visible executes action when menu popup is visible")
    void runIfVisible_whenPopupVisible_executesAction() {
        AtomicInteger calls = new AtomicInteger();

        subject.runIfVisible(new FakeMenu(true), calls::incrementAndGet);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Run if visible skips action when menu popup is hidden or menu is null")
    void runIfVisible_whenPopupHiddenOrMenuNull_skipsAction() {
        AtomicInteger calls = new AtomicInteger();

        subject.runIfVisible(new FakeMenu(false), calls::incrementAndGet);
        subject.runIfVisible(null, calls::incrementAndGet);

        assertThat(calls.get()).isZero();
    }

    private static class FakeMenu extends JMenu {

        private final boolean popupVisible;

        private FakeMenu(boolean popupVisible) {
            this.popupVisible = popupVisible;
        }

        @Override
        public boolean isPopupMenuVisible() {
            return popupVisible;
        }
    }
}
