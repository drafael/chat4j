package com.github.drafael.chat4j.settings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class SettingsWriteQueue {

    private final ExecutorService executor;
    private boolean closed;

    SettingsWriteQueue(String threadName) {
        executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name(threadName, 0).factory());
    }

    synchronized CompletableFuture<Void> submit(Runnable write) {
        if (closed) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Settings panel is closed"));
        }
        return CompletableFuture.runAsync(write, executor);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
    }

    static Error fatalError(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof Error error && !(error instanceof LinkageError) ? error : null;
    }
}
