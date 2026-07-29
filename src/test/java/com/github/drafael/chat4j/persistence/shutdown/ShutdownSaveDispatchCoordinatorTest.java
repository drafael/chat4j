package com.github.drafael.chat4j.persistence.shutdown;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShutdownSaveDispatchCoordinatorTest {

    @Test
    @DisplayName("Settled persistence and cleanup complete shutdown once")
    void dispatchStages_whenBothStagesSucceed_completesOnce() {
        var completionCalls = new AtomicInteger();
        var timeoutCalls = new AtomicInteger();
        var failure = new AtomicReference<Exception>();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                deadlineAfter(1, TimeUnit.SECONDS),
                CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(null),
                completionCalls::incrementAndGet,
                timeoutCalls::incrementAndGet,
                failure::set
        );

        assertThat(completionCalls).hasValue(1);
        assertThat(timeoutCalls).hasValue(0);
        assertThat(failure).hasValue(null);
    }

    @Test
    @DisplayName("Persistence remains primary when persistence and cleanup both fail")
    void dispatchStages_whenBothStagesFail_reportsPersistenceWithCleanupSuppressed() {
        var persistenceFailure = new IllegalStateException("persistence failed");
        var cleanupFailure = new IllegalArgumentException("cleanup failed");
        var failure = new AtomicReference<Exception>();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                deadlineAfter(1, TimeUnit.SECONDS),
                CompletableFuture.failedFuture(persistenceFailure),
                CompletableFuture.failedFuture(cleanupFailure),
                () -> {},
                () -> {},
                failure::set
        );

        assertThat(failure).hasValue(persistenceFailure);
        assertThat(persistenceFailure.getSuppressed()).containsExactly(cleanupFailure);
    }

    @Test
    @DisplayName("Failed aggregate reports immediately without waiting for the deadline")
    void dispatchStages_whenPersistenceFails_reportsFailureImmediately() {
        var persistenceFailure = new IllegalStateException("persistence failed");
        var completionCalls = new AtomicInteger();
        var failure = new AtomicReference<Exception>();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                deadlineAfter(1, TimeUnit.SECONDS),
                CompletableFuture.failedFuture(persistenceFailure),
                CompletableFuture.completedFuture(null),
                completionCalls::incrementAndGet,
                () -> {},
                failure::set
        );

        assertThat(completionCalls).hasValue(1);
        assertThat(failure).hasValue(persistenceFailure);
    }

    @Test
    @DisplayName("Deadline completion reports timeout once without completing owned stages")
    void dispatchStages_whenDeadlineExpires_reportsTimeoutAndCompletes() throws Exception {
        var persistence = new CompletableFuture<Void>();
        var cleanup = new CompletableFuture<Void>();
        var completed = new CountDownLatch(1);
        var timeoutCalls = new AtomicInteger();
        var failure = new AtomicReference<Exception>();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                deadlineAfter(25, TimeUnit.MILLISECONDS),
                persistence,
                cleanup,
                completed::countDown,
                timeoutCalls::incrementAndGet,
                failure::set
        );

        assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(timeoutCalls).hasValue(1);
        assertThat(failure).hasValue(null);
        assertThat(persistence).isNotDone();
        assertThat(cleanup).isNotDone();
    }

    @Test
    @DisplayName("Diagnostic handler failure cannot skip terminal completion")
    void dispatchStages_whenFailureHandlerThrows_stillCompletes() {
        var completionCalls = new AtomicInteger();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                deadlineAfter(1, TimeUnit.SECONDS),
                CompletableFuture.failedFuture(new IllegalStateException("persistence failed")),
                CompletableFuture.completedFuture(null),
                completionCalls::incrementAndGet,
                () -> {},
                error -> {
                    throw new IllegalStateException("diagnostic failed");
                }
        );

        assertThat(completionCalls).hasValue(1);
    }

    @Test
    @DisplayName("An already exhausted deadline reports timeout immediately")
    void dispatchStages_whenDeadlineIsExpired_reportsTimeout() {
        var completionCalls = new AtomicInteger();
        var timeoutCalls = new AtomicInteger();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                0,
                CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(null),
                completionCalls::incrementAndGet,
                timeoutCalls::incrementAndGet,
                error -> {}
        );

        assertThat(timeoutCalls).hasValue(1);
        assertThat(completionCalls).hasValue(1);
    }

    @Test
    @DisplayName("A settled outcome delivered after the absolute deadline is reported as timeout")
    void dispatchStages_whenEdtDeliveryCrossesDeadline_reportsTimeout() {
        var now = new AtomicReference<>(100L);
        var edtAction = new AtomicReference<Runnable>();
        var completionCalls = new AtomicInteger();
        var timeoutCalls = new AtomicInteger();
        var subject = new ShutdownSaveDispatchCoordinator(edtAction::set, now::get);

        subject.dispatchStages(
                1_000_000_100L,
                CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(null),
                completionCalls::incrementAndGet,
                timeoutCalls::incrementAndGet,
                error -> {}
        );
        now.set(1_000_000_100L);
        edtAction.get().run();

        assertThat(timeoutCalls).hasValue(1);
        assertThat(completionCalls).hasValue(1);
    }

    @Test
    @DisplayName("Injected clock and scheduler drive hard deadline without wall-clock delay")
    void dispatchStages_whenInjectedDeadlineRuns_reportsTimeoutDeterministically() {
        var now = new AtomicReference<>(100L);
        var delayedTask = new AtomicReference<Runnable>();
        var scheduledDelay = new AtomicReference<Long>();
        var timeoutCalls = new AtomicInteger();
        var completionCalls = new AtomicInteger();
        var subject = new ShutdownSaveDispatchCoordinator(
                Runnable::run,
                now::get,
                (delay, task) -> {
                    scheduledDelay.set(delay);
                    delayedTask.set(task);
                }
        );

        subject.dispatchStages(
                500L,
                new CompletableFuture<>(),
                new CompletableFuture<>(),
                completionCalls::incrementAndGet,
                timeoutCalls::incrementAndGet,
                error -> { }
        );
        now.set(500L);
        delayedTask.get().run();

        assertThat(scheduledDelay).hasValue(400L);
        assertThat(timeoutCalls).hasValue(1);
        assertThat(completionCalls).hasValue(1);
    }

    private long deadlineAfter(long duration, TimeUnit unit) {
        return System.nanoTime() + unit.toNanos(duration);
    }
}
