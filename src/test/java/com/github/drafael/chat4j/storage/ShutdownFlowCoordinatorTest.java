package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShutdownFlowCoordinatorTest {

    @Test
    @DisplayName("Request marks shutdown state, runs pre-shutdown actions, and delegates dispatch")
    void request_whenNotInProgress_marksPreparesAndDispatches() throws Exception {
        var dispatcher = new RecordingShutdownDispatcher();
        var subject = new ShutdownFlowCoordinator(dispatcher);
        var shutdownInProgress = new AtomicBoolean();
        var calls = new ArrayList<String>();
        var finishCalls = new AtomicInteger();
        var timeoutCalls = new AtomicInteger();
        var failureRef = new AtomicReference<Exception>();

        boolean started = subject.request(
                shutdownInProgress::get,
                () -> {
                    calls.add("mark");
                    shutdownInProgress.set(true);
                },
                500,
                () -> calls.add("pre"),
                () -> {
                    calls.add("factory");
                    return () -> calls.add("save");
                },
                () -> {
                    calls.add("finish");
                    finishCalls.incrementAndGet();
                },
                () -> {
                    calls.add("timeout");
                    timeoutCalls.incrementAndGet();
                },
                failureRef::set
        );

        assertThat(started).isTrue();
        assertThat(shutdownInProgress.get()).isTrue();
        assertThat(calls).containsExactly("mark", "pre", "factory");
        assertThat(dispatcher.timeoutMillis).isEqualTo(500);

        dispatcher.saveAction.save();
        dispatcher.completionHandler.complete();

        assertThat(calls).containsExactly("mark", "pre", "factory", "save", "finish");
        assertThat(finishCalls.get()).isEqualTo(1);
        assertThat(timeoutCalls.get()).isZero();
        assertThat(failureRef.get()).isNull();
    }

    @Test
    @DisplayName("Request is ignored when shutdown is already in progress")
    void request_whenAlreadyInProgress_returnsFalseWithoutDispatch() {
        var dispatcher = new RecordingShutdownDispatcher();
        var subject = new ShutdownFlowCoordinator(dispatcher);
        var markCalls = new AtomicInteger();
        var preCalls = new AtomicInteger();

        boolean started = subject.request(
                () -> true,
                markCalls::incrementAndGet,
                500,
                preCalls::incrementAndGet,
                () -> () -> {
                },
                () -> {
                },
                () -> {
                },
                error -> {
                }
        );

        assertThat(started).isFalse();
        assertThat(markCalls.get()).isZero();
        assertThat(preCalls.get()).isZero();
        assertThat(dispatcher.invocationCount.get()).isZero();
    }

    @Test
    @DisplayName("Request validates timeout and required arguments")
    void request_whenArgumentsInvalid_throwsException() {
        var subject = new ShutdownFlowCoordinator(new RecordingShutdownDispatcher());

        assertThatThrownBy(() -> subject.request(
                () -> false,
                () -> {
                },
                0,
                () -> {
                },
                () -> () -> {
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

        assertThatThrownBy(() -> subject.request(
                null,
                () -> {
                },
                500,
                () -> {
                },
                () -> () -> {
                },
                () -> {
                },
                () -> {
                },
                error -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("shutdownInProgressSupplier");
    }

    @Test
    @DisplayName("Request rejects null save action produced by supplier")
    void request_whenSupplierReturnsNull_throwsException() {
        var subject = new ShutdownFlowCoordinator(new RecordingShutdownDispatcher());

        assertThatThrownBy(() -> subject.request(
                () -> false,
                () -> {
                },
                500,
                () -> {
                },
                () -> null,
                () -> {
                },
                () -> {
                },
                error -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveActionSupplier must not return null");
    }

    private static class RecordingShutdownDispatcher implements ShutdownFlowCoordinator.ShutdownDispatcher {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private long timeoutMillis;
        private ShutdownSaveDispatchCoordinator.SaveAction saveAction;
        private ShutdownSaveDispatchCoordinator.CompletionHandler completionHandler;

        @Override
        public void dispatch(
                long timeoutMillis,
                ShutdownSaveDispatchCoordinator.SaveAction saveAction,
                ShutdownSaveDispatchCoordinator.CompletionHandler completionHandler,
                ShutdownSaveDispatchCoordinator.TimeoutHandler timeoutHandler,
                ShutdownSaveDispatchCoordinator.FailureHandler failureHandler
        ) {
            invocationCount.incrementAndGet();
            this.timeoutMillis = timeoutMillis;
            this.saveAction = saveAction;
            this.completionHandler = completionHandler;
        }
    }
}
