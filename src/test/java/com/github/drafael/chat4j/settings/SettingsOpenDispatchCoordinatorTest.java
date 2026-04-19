package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsOpenDispatchCoordinatorTest {

    @Test
    @DisplayName("Open schedules settings open when called off EDT")
    void open_whenOffEdt_schedulesOpen() {
        var subject = new SettingsOpenDispatchCoordinator();
        var scheduledCalls = new AtomicInteger();
        var openedCalls = new AtomicInteger();

        subject.open(false, scheduledCalls::incrementAndGet, openedCalls::incrementAndGet);

        assertThat(scheduledCalls.get()).isEqualTo(1);
        assertThat(openedCalls.get()).isZero();
    }

    @Test
    @DisplayName("Open runs settings open immediately when called on EDT")
    void open_whenOnEdt_opensImmediately() {
        var subject = new SettingsOpenDispatchCoordinator();
        var scheduledCalls = new AtomicInteger();
        var openedCalls = new AtomicInteger();

        subject.open(true, scheduledCalls::incrementAndGet, openedCalls::incrementAndGet);

        assertThat(scheduledCalls.get()).isZero();
        assertThat(openedCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Open validates required callbacks")
    void open_whenCallbackMissing_throwsException() {
        var subject = new SettingsOpenDispatchCoordinator();

        assertThatThrownBy(() -> subject.open(true, null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scheduleOpenOnEdt must not be null");

        assertThatThrownBy(() -> subject.open(true, () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("openOnEdt must not be null");
    }
}
