package com.github.drafael.chat4j.persistence.shutdown;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShutdownSaveDispatchCoordinatorTest {

    @Test
    @DisplayName("Dispatch completes immediately when save succeeds")
    void dispatch_whenSaveSucceeds_completesWithoutFailureOrTimeout() {
        var completionCalls = new AtomicInteger();
        var timeoutCalls = new AtomicInteger();
        var failureRef = new AtomicReference<Exception>();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run, Runnable::run);

        subject.dispatch(
                200,
                () -> {
                },
                completionCalls::incrementAndGet,
                timeoutCalls::incrementAndGet,
                failureRef::set
        );

        assertThat(completionCalls.get()).isEqualTo(1);
        assertThat(timeoutCalls.get()).isZero();
        assertThat(failureRef.get()).isNull();
    }

    @Test
    @DisplayName("Dispatch reports failure when save action throws")
    void dispatch_whenSaveThrows_reportsFailureAndCompletes() {
        var completionCalls = new AtomicInteger();
        var timeoutCalls = new AtomicInteger();
        var failureRef = new AtomicReference<Exception>();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run, Runnable::run);

        subject.dispatch(
                200,
                () -> {
                    throw new IllegalStateException("boom");
                },
                completionCalls::incrementAndGet,
                timeoutCalls::incrementAndGet,
                failureRef::set
        );

        assertThat(completionCalls.get()).isEqualTo(1);
        assertThat(timeoutCalls.get()).isZero();
        assertThat(failureRef.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("Dispatch reports timeout and still completes when save is too slow")
    void dispatch_whenSaveTimesOut_reportsTimeoutAndCompletes() throws Exception {
        var saveCanFinish = new CountDownLatch(1);
        var completionLatch = new CountDownLatch(1);
        var timeoutCalls = new AtomicInteger();
        var failureRef = new AtomicReference<Exception>();
        var subject = new ShutdownSaveDispatchCoordinator(
                command -> {
                    var worker = new Thread(command, "shutdown-save-test");
                    worker.setDaemon(true);
                    worker.start();
                },
                Runnable::run
        );

        subject.dispatch(
                25,
                () -> saveCanFinish.await(1, TimeUnit.SECONDS),
                completionLatch::countDown,
                timeoutCalls::incrementAndGet,
                failureRef::set
        );

        assertThat(completionLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(timeoutCalls.get()).isEqualTo(1);
        assertThat(failureRef.get()).isNull();

        saveCanFinish.countDown();
    }

    @Test
    @DisplayName("Dispatch validates timeout and required callbacks")
    void dispatch_whenArgumentsInvalid_throwsException() {
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run, Runnable::run);

        assertThatThrownBy(() -> subject.dispatch(
                0,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                error -> {
                }
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMillis must be greater than zero");

        assertThatThrownBy(() -> subject.dispatch(
                200,
                null,
                () -> {
                },
                () -> {
                },
                error -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveAction");
    }
}
