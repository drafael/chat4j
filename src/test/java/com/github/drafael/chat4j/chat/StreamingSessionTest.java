package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.ProviderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StreamingSessionTest {

    @Test
    @DisplayName("A request registered after cancellation is closed immediately")
    void registerActiveRequest_whenSessionWasCancelled_closesLateRequest() {
        var subject = new StreamingSession(1L, null, mock(ProviderService.class));
        var closed = new AtomicBoolean();
        subject.cancelled.set(true);

        subject.registerActiveRequest(() -> closed.set(true));

        assertThat(closed).isTrue();
        assertThat(subject.activeRequest).hasValue(null);
    }

    @Test
    @DisplayName("Replacing an active request closes the superseded handle")
    void registerActiveRequest_whenRequestAlreadyRegistered_closesPreviousRequest() {
        var subject = new StreamingSession(1L, null, mock(ProviderService.class));
        var firstClosed = new AtomicBoolean();
        var secondClosed = new AtomicBoolean();
        subject.registerActiveRequest(() -> firstClosed.set(true));

        subject.registerActiveRequest(() -> secondClosed.set(true));

        assertThat(firstClosed).isTrue();
        assertThat(secondClosed).isFalse();
        assertThat(subject.cancelActiveRequest()).isTrue();
        assertThat(secondClosed).isTrue();
    }
}
