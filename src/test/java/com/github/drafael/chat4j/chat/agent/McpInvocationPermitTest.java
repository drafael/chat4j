package com.github.drafael.chat4j.chat.agent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpInvocationPermitTest {

    @Test
    @DisplayName("Cancellation after eligibility but before admission prevents operation initiation")
    void admit_whenCancellationWinsBeforeOperationInitiation_doesNotInvokeStarter() throws Exception {
        var subject = McpInvocationPermit.pendingApproval();
        assertThat(subject.allowOnce()).isTrue();
        var beginAdmission = new CountDownLatch(1);
        var starterCalls = new AtomicInteger();
        CompletableFuture<CompletableFuture<String>> admission = CompletableFuture.supplyAsync(() -> {
            try {
                beginAdmission.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return subject.admit(() -> false, () -> {
                starterCalls.incrementAndGet();
                return CompletableFuture.completedFuture("sent");
            });
        });

        subject.cancel();
        beginAdmission.countDown();

        assertThatThrownBy(admission::join).hasRootCauseInstanceOf(CancellationException.class);
        assertThat(starterCalls).hasValue(0);
    }

    @Test
    @DisplayName("Operation initiation holding admission lock wins over concurrent cancellation")
    void admit_whenOperationInitiationWins_cancelCannotInterleave() throws Exception {
        var subject = McpInvocationPermit.automaticallyAllowed();
        var starterEntered = new CountDownLatch(1);
        var releaseStarter = new CountDownLatch(1);
        var cancelStarted = new CountDownLatch(1);
        var admittedOperation = new CompletableFuture<String>();
        CompletableFuture<CompletableFuture<String>> admission = CompletableFuture.supplyAsync(() ->
                subject.admit(() -> false, () -> {
                    starterEntered.countDown();
                    try {
                        releaseStarter.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return admittedOperation;
                }));
        CompletableFuture<Void> cancellation = null;
        try {
            assertThat(starterEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> cancellationTask = CompletableFuture.runAsync(() -> {
                cancelStarted.countDown();
                subject.cancel();
            });
            cancellation = cancellationTask;
            assertThat(cancelStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellationTask).isNotDone();
        } finally {
            releaseStarter.countDown();
            admission.handle((ignored, error) -> null).join();
            if (cancellation != null) {
                cancellation.handle((ignored, error) -> null).join();
            }
        }

        assertThat(admission.get(5, TimeUnit.SECONDS)).isSameAs(admittedOperation);
        assertThat(cancellation).isCompleted();
        admittedOperation.complete("sent");
        assertThat(admission.join().join()).isEqualTo("sent");
        assertThatThrownBy(() -> subject.admit(
                () -> false,
                () -> CompletableFuture.completedFuture("replayed")
        )).isInstanceOf(CancellationException.class);
    }
}
