package com.github.drafael.chat4j.persistence.shutdown;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.SwingUtilities;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class ShutdownSaveDispatchCoordinator {

    private static final String THREAD_NAME = "chat4j-shutdown-save";

    private final Executor saveExecutor;
    private final EdtDispatcher edtDispatcher;

    public ShutdownSaveDispatchCoordinator() {
        this(
                Executors.newSingleThreadExecutor(runnable -> {
                    var thread = new Thread(runnable, THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                }),
                SwingUtilities::invokeLater
        );
    }

    ShutdownSaveDispatchCoordinator(@NonNull Executor saveExecutor, @NonNull EdtDispatcher edtDispatcher) {
        this.saveExecutor = saveExecutor;
        this.edtDispatcher = edtDispatcher;
    }

    public void dispatch(
            long timeoutMillis,
            @NonNull SaveAction saveAction,
            @NonNull CompletionHandler completionHandler,
            @NonNull TimeoutHandler timeoutHandler,
            @NonNull FailureHandler failureHandler
    ) {
        Validate.isTrue(timeoutMillis > 0, "timeoutMillis must be greater than zero");

        CompletableFuture.runAsync(() -> {
            try {
                saveAction.save();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, saveExecutor)
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((ignored, throwable) -> edtDispatcher.dispatch(() -> {
                    if (throwable == null) {
                        completionHandler.complete();
                        return;
                    }

                    var cause = unwrap(throwable);
                    if (cause instanceof TimeoutException) {
                        timeoutHandler.handle();
                    } else if (cause instanceof Exception e) {
                        failureHandler.handle(e);
                    } else {
                        failureHandler.handle(new RuntimeException(cause));
                    }
                    completionHandler.complete();
                }));
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    @FunctionalInterface
    public interface SaveAction {
        void save() throws Exception;
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
