package com.github.drafael.chat4j.persistence.shutdown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShutdownPersistenceFlowIntegrationTest {

    @Test
    @DisplayName("Shutdown flow completes with finish action after successful save")
    void request_whenSaveSucceeds_completesWithoutTimeoutOrFailure() throws Exception {
        var saveDispatchCoordinator = new ShutdownSaveDispatchCoordinator(
                command -> {
                    var worker = new Thread(command, "shutdown-flow-success");
                    worker.setDaemon(true);
                    worker.start();
                },
                Runnable::run
        );
        var subject = new ShutdownFlowCoordinator(saveDispatchCoordinator);
        var shutdownInProgress = new AtomicBoolean();
        var calls = Collections.synchronizedList(new ArrayList<String>());
        var completionLatch = new CountDownLatch(1);
        var failureRef = new AtomicReference<Exception>();

        boolean started = subject.request(
                shutdownInProgress::get,
                () -> {
                    calls.add("mark");
                    shutdownInProgress.set(true);
                },
                250,
                () -> calls.add("pre"),
                () -> {
                    calls.add("factory");
                    return () -> calls.add("save");
                },
                () -> {
                    calls.add("finish");
                    completionLatch.countDown();
                },
                () -> calls.add("timeout"),
                failureRef::set
        );

        assertThat(started).isTrue();
        assertThat(completionLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(shutdownInProgress.get()).isTrue();
        assertThat(calls).contains("mark", "pre", "factory", "save", "finish");
        assertThat(calls).doesNotContain("timeout");
        assertThat(failureRef.get()).isNull();
    }

    @Test
    @DisplayName("Shutdown flow reports timeout and still finishes when save exceeds timeout")
    void request_whenSaveTimesOut_reportsTimeoutAndStillFinishes() throws Exception {
        var saveDispatchCoordinator = new ShutdownSaveDispatchCoordinator(
                command -> {
                    var worker = new Thread(command, "shutdown-flow-timeout");
                    worker.setDaemon(true);
                    worker.start();
                },
                Runnable::run
        );
        var subject = new ShutdownFlowCoordinator(saveDispatchCoordinator);
        var completionLatch = new CountDownLatch(1);
        var saveCanFinish = new CountDownLatch(1);
        var timeoutCalled = new AtomicBoolean();
        var failureRef = new AtomicReference<Exception>();

        boolean started = subject.request(
                () -> false,
                () -> {
                },
                25,
                () -> {
                },
                () -> () -> saveCanFinish.await(1, TimeUnit.SECONDS),
                completionLatch::countDown,
                () -> timeoutCalled.set(true),
                failureRef::set
        );

        assertThat(started).isTrue();
        assertThat(completionLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(timeoutCalled.get()).isTrue();
        assertThat(failureRef.get()).isNull();

        saveCanFinish.countDown();
    }

    @Test
    @DisplayName("Shutdown flow reports save failure and still finishes")
    void request_whenSaveFails_reportsFailureAndStillFinishes() throws Exception {
        var saveDispatchCoordinator = new ShutdownSaveDispatchCoordinator(
                command -> {
                    var worker = new Thread(command, "shutdown-flow-failure");
                    worker.setDaemon(true);
                    worker.start();
                },
                Runnable::run
        );
        var subject = new ShutdownFlowCoordinator(saveDispatchCoordinator);
        var completionLatch = new CountDownLatch(1);
        var timeoutCalled = new AtomicBoolean();
        var failureRef = new AtomicReference<Exception>();

        boolean started = subject.request(
                () -> false,
                () -> {
                },
                250,
                () -> {
                },
                () -> () -> {
                    throw new IllegalStateException("boom");
                },
                completionLatch::countDown,
                () -> timeoutCalled.set(true),
                failureRef::set
        );

        assertThat(started).isTrue();
        assertThat(completionLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(timeoutCalled.get()).isFalse();
        assertThat(failureRef.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }
}
