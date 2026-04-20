package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameShutdownStateTest {

    @Test
    @DisplayName("Default shutdown state starts as not in progress")
    void defaults_whenConstructed_startsWithShutdownNotInProgress() {
        var subject = new MainFrameShutdownState();

        assertThat(subject.shutdownInProgress()).isFalse();
    }

    @Test
    @DisplayName("Setter updates tracked shutdown progress flag")
    void setShutdownInProgress_whenCalled_updatesFlag() {
        var subject = new MainFrameShutdownState();

        subject.setShutdownInProgress(true);

        assertThat(subject.shutdownInProgress()).isTrue();
    }
}
