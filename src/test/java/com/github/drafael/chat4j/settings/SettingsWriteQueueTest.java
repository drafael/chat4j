package com.github.drafael.chat4j.settings;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsWriteQueueTest {

    @Test
    @DisplayName("Settings writes execute in submission order")
    void submit_whenFirstWriteBlocks_doesNotStartSecondWriteEarly() throws Exception {
        var subject = new SettingsWriteQueue("settings-write-queue-order-");
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new AtomicBoolean();
        try {
            var first = subject.submit(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            var second = subject.submit(() -> secondStarted.set(true));
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(secondStarted).isFalse();

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(secondStarted).isTrue();
        } finally {
            releaseFirst.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Closing the queue drains writes accepted before closure")
    void close_whenWriteWasAccepted_allowsWriteToFinish() throws Exception {
        var subject = new SettingsWriteQueue("settings-write-queue-drain-");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var accepted = subject.submit(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            subject.close();
            release.countDown();

            accepted.get(5, TimeUnit.SECONDS);
            assertThat(accepted).isCompleted();
        } finally {
            release.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Closing the queue rejects new settings writes")
    void submit_whenQueueIsClosed_returnsRejectedFuture() {
        var subject = new SettingsWriteQueue("settings-write-queue-closed-");
        subject.close();

        var rejected = subject.submit(() -> {
        });

        assertThatThrownBy(rejected::join)
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("Fatal errors wrapped by asynchronous settings writes remain fatal")
    void fatalError_whenCompletionWrapsFatalError_returnsOriginalError() {
        var fatalError = new AssertionError("fatal");

        Error result = SettingsWriteQueue.fatalError(new CompletionException(fatalError));

        assertThat(result).isSameAs(fatalError);
    }

    @Test
    @DisplayName("Recoverable exceptions and linkage failures are not treated as fatal")
    void fatalError_whenFailureIsRecoverable_returnsNull() {
        assertThat(SettingsWriteQueue.fatalError(new CompletionException(new IllegalStateException("failure"))))
                .isNull();
        assertThat(SettingsWriteQueue.fatalError(new CompletionException(new NoClassDefFoundError("optional"))))
                .isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release settings write");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to release settings write", e);
        }
    }
}
