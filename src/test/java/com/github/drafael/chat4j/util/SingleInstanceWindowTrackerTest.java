package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SingleInstanceWindowTrackerTest {

    @Test
    @DisplayName("hasActive returns false when no window is tracked")
    void hasActive_whenNoWindowIsTracked_returnsFalse() {
        var subject = new SingleInstanceWindowTracker<FakeWindow>();

        boolean result = subject.hasActive(FakeWindow::isOpen);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("hasActive returns true when tracked window matches active predicate")
    void hasActive_whenTrackedWindowIsActive_returnsTrue() {
        var subject = new SingleInstanceWindowTracker<FakeWindow>();
        subject.set(new FakeWindow(true));

        boolean result = subject.hasActive(FakeWindow::isOpen);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("clear removes tracked window")
    void clear_whenCalled_removesTrackedWindow() {
        var subject = new SingleInstanceWindowTracker<FakeWindow>();
        subject.set(new FakeWindow(true));

        subject.clear();

        assertThat(subject.get()).isNull();
        assertThat(subject.hasActive(FakeWindow::isOpen)).isFalse();
    }

    private record FakeWindow(boolean isOpen) {}
}
