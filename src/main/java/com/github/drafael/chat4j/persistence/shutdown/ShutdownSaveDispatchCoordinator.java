package com.github.drafael.chat4j.persistence.shutdown;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import javax.swing.SwingUtilities;
import lombok.NonNull;

public class ShutdownSaveDispatchCoordinator {

    private final EdtDispatcher edtDispatcher;
    private final LongSupplier nanoTime;
    private final DelayedTaskScheduler delayedTaskScheduler;

    public ShutdownSaveDispatchCoordinator() {
        this(
                SwingUtilities::invokeLater,
                System::nanoTime,
                (delay, task) -> CompletableFuture.delayedExecutor(
                        delay,
                        TimeUnit.NANOSECONDS,
                        Runnable::run
                ).execute(task)
        );
    }

    ShutdownSaveDispatchCoordinator(@NonNull EdtDispatcher edtDispatcher) {
        this(
                edtDispatcher,
                System::nanoTime,
                (delay, task) -> CompletableFuture.delayedExecutor(
                        delay,
                        TimeUnit.NANOSECONDS,
                        Runnable::run
                ).execute(task)
        );
    }

    ShutdownSaveDispatchCoordinator(@NonNull EdtDispatcher edtDispatcher, @NonNull LongSupplier nanoTime) {
        this(
                edtDispatcher,
                nanoTime,
                (delay, task) -> CompletableFuture.delayedExecutor(
                        delay,
                        TimeUnit.NANOSECONDS,
                        Runnable::run
                ).execute(task)
        );
    }

    ShutdownSaveDispatchCoordinator(
            @NonNull EdtDispatcher edtDispatcher,
            @NonNull LongSupplier nanoTime,
            @NonNull DelayedTaskScheduler delayedTaskScheduler
    ) {
        this.edtDispatcher = edtDispatcher;
        this.nanoTime = nanoTime;
        this.delayedTaskScheduler = delayedTaskScheduler;
    }

    public void dispatchStages(
            long deadlineNanos,
            @NonNull CompletionStage<Void> persistence,
            @NonNull CompletionStage<Void> cleanup,
            @NonNull CompletionHandler completionHandler,
            @NonNull TimeoutHandler timeoutHandler,
            @NonNull FailureHandler failureHandler
    ) {
        long remainingNanos = deadlineNanos - nanoTime.getAsLong();
        if (remainingNanos <= 0) {
            dispatchOutcome(
                    timeoutOutcome(),
                    deadlineNanos,
                    completionHandler,
                    timeoutHandler,
                    failureHandler
            );
            return;
        }

        CompletableFuture<Outcome> persistenceOutcome = persistence
                .handle((ignored, error) -> new Outcome(unwrap(error)))
                .toCompletableFuture();
        CompletableFuture<Outcome> cleanupOutcome = cleanup
                .handle((ignored, error) -> new Outcome(unwrap(error)))
                .toCompletableFuture();
        CompletableFuture<Outcome> aggregate = persistenceOutcome.thenCombine(cleanupOutcome, (saved, cleaned) ->
                new Outcome(combineFailures(saved.failure(), cleaned.failure()))
        );
        CompletableFuture<Outcome> timeout = new CompletableFuture<>();
        delayedTaskScheduler.schedule(remainingNanos, () -> timeout.complete(timeoutOutcome()));

        aggregate.applyToEither(timeout, outcome -> outcome).thenAccept(outcome -> dispatchOutcome(
                outcome,
                deadlineNanos,
                completionHandler,
                timeoutHandler,
                failureHandler
        ));
    }

    private void dispatchOutcome(
            Outcome outcome,
            long deadlineNanos,
            CompletionHandler completionHandler,
            TimeoutHandler timeoutHandler,
            FailureHandler failureHandler
    ) {
        edtDispatcher.dispatch(() -> {
            try {
                Outcome deliveredOutcome = nanoTime.getAsLong() >= deadlineNanos ? timeoutOutcome() : outcome;
                Throwable cause = deliveredOutcome.failure();
                if (cause instanceof TimeoutException) {
                    timeoutHandler.handle();
                } else if (cause != null) {
                    failureHandler.handle(cause instanceof Exception e ? e : new RuntimeException(cause));
                }
            } finally {
                completionHandler.complete();
            }
        });
    }

    private Outcome timeoutOutcome() {
        return new Outcome(new TimeoutException("Shutdown deadline elapsed"));
    }

    private Throwable combineFailures(Throwable persistenceFailure, Throwable cleanupFailure) {
        if (persistenceFailure == null) {
            return cleanupFailure;
        }
        if (cleanupFailure != null && cleanupFailure != persistenceFailure) {
            persistenceFailure.addSuppressed(cleanupFailure);
        }
        return persistenceFailure;
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    private record Outcome(Throwable failure) {
    }

    @FunctionalInterface
    interface DelayedTaskScheduler {
        void schedule(long delayNanos, Runnable task);
    }

    @FunctionalInterface
    public interface CompletionHandler {
        void complete();
    }

    @FunctionalInterface
    public interface TimeoutHandler {
        void handle();
    }

    @FunctionalInterface
    public interface FailureHandler {
        void handle(Exception error);
    }

    @FunctionalInterface
    interface EdtDispatcher {
        void dispatch(Runnable action);
    }
}
