package com.github.drafael.chat4j.chat.agent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.NonNull;

import static java.util.Objects.requireNonNull;

public final class McpInvocationPermit {

    private final Object admissionLock = new Object();
    private State state;

    private McpInvocationPermit(State initialState) {
        state = initialState;
    }

    public static McpInvocationPermit pendingApproval() {
        return new McpInvocationPermit(State.PENDING);
    }

    public static McpInvocationPermit automaticallyAllowed() {
        return new McpInvocationPermit(State.ALLOWED);
    }

    public boolean allowOnce() {
        synchronized (admissionLock) {
            if (state != State.PENDING) {
                return false;
            }
            state = State.ALLOWED;
            return true;
        }
    }

    public void cancel() {
        synchronized (admissionLock) {
            if (state == State.PENDING || state == State.ALLOWED) {
                state = State.CANCELLED;
            }
        }
    }

    public <T> CompletableFuture<T> admit(
            @NonNull BooleanSupplier cancelled,
            @NonNull Supplier<CompletableFuture<T>> operationStarter
    ) {
        synchronized (admissionLock) {
            if (cancelled.getAsBoolean()) {
                if (state == State.PENDING || state == State.ALLOWED) {
                    state = State.CANCELLED;
                }
                throw new CancellationException("MCP operation cancelled before send admission.");
            }
            if (state != State.ALLOWED) {
                throw new CancellationException("MCP operation is not eligible for send admission.");
            }
            CompletableFuture<T> admitted = requireNonNull(
                    operationStarter.get(),
                    "operationStarter result should not be null"
            );
            state = State.CONSUMED;
            return admitted;
        }
    }

    private enum State {
        PENDING,
        ALLOWED,
        CONSUMED,
        CANCELLED
    }
}
