package com.github.drafael.chat4j.settings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderAuthLifecycleTest {

    @Test
    @DisplayName("Removing a provider panel cancels every tracked authentication resource")
    void deactivate_whenResourcesAreTracked_cancelsAndRejectsThem() {
        var subject = new ProviderAuthLifecycle();
        long generation = subject.currentGeneration();
        var cancellations = new AtomicInteger();
        Object firstResource = new Object();
        Object secondResource = new Object();

        assertThat(subject.register(generation, firstResource, cancellations::incrementAndGet)).isTrue();
        assertThat(subject.register(generation, secondResource, cancellations::incrementAndGet)).isTrue();

        subject.deactivate();

        assertThat(cancellations).hasValue(2);
        assertThat(subject.isCurrent(generation)).isFalse();
        assertThat(subject.register(generation, new Object(), cancellations::incrementAndGet)).isFalse();
        assertThat(cancellations).hasValue(3);
    }

    @Test
    @DisplayName("Re-adding a provider panel cannot make callbacks from its prior lifecycle current")
    void activate_whenPriorLifecycleWasRemoved_keepsOldGenerationStale() {
        var subject = new ProviderAuthLifecycle();
        long oldGeneration = subject.currentGeneration();

        subject.deactivate();
        subject.activate();
        long newGeneration = subject.currentGeneration();

        assertThat(newGeneration).isGreaterThan(oldGeneration);
        assertThat(subject.isCurrent(oldGeneration)).isFalse();
        assertThat(subject.isCurrent(newGeneration)).isTrue();
    }

    @Test
    @DisplayName("Removing a provider panel interrupts its tracked authentication worker")
    void deactivate_whenAuthenticationWorkerIsBlocked_interruptsAndTerminatesWorker() throws Exception {
        var subject = new ProviderAuthLifecycle();
        long generation = subject.currentGeneration();
        var workerStarted = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            workerStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                subject.unregister(Thread.currentThread());
            }
        });
        assertThat(subject.register(generation, worker, worker::interrupt)).isTrue();
        worker.start();

        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        subject.deactivate();
        worker.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(worker.isAlive()).isFalse();
        assertThat(interrupted).isTrue();
        assertThat(subject.isCurrent(generation)).isFalse();
    }

    @Test
    @DisplayName("Interrupting the dialog waiter before future completion preserves interrupt status")
    void awaitAuthDialogResult_whenWorkerInterruptsFirst_restoresInterruptStatus() throws Exception {
        var result = new AwaitObservedFuture<String>();
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread worker = Thread.startVirtualThread(() -> {
            try {
                ProvidersPanel.awaitAuthDialogResult(result);
            } catch (Throwable t) {
                failure.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            assertThat(result.awaitGetCalled()).isTrue();
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            assertThat(interrupted).isTrue();
        } finally {
            result.complete("cancelled");
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    @DisplayName("Dialog timeout completion is rethrown as the original timeout")
    void awaitAuthDialogResult_whenFutureTimesOut_throwsTimeoutException() {
        var result = new CompletableFuture<String>();
        result.completeExceptionally(new TimeoutException("timed out"));

        assertThatThrownBy(() -> ProvidersPanel.awaitAuthDialogResult(result))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("timed out");
    }

    @Test
    @DisplayName("Cancelling the dialog result resource interrupts its waiter before completion")
    void authDialogCancellation_whenResultResourceCancelsFirst_preservesWorkerInterruption() throws Exception {
        var result = new AwaitObservedFuture<String>();
        var observed = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread worker = Thread.startVirtualThread(() -> {
            try {
                observed.set(ProvidersPanel.awaitAuthDialogResult(result));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            assertThat(result.awaitGetCalled()).isTrue();

            ProvidersPanel.authDialogCancellation(worker, result).run();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive()).isFalse();
            assertThat(interrupted).isTrue();
            assertThat(observed.get() != null || failure.get() instanceof InterruptedException).isTrue();
        } finally {
            result.complete("cancelled");
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    @DisplayName("Removing a provider panel completes a tracked authentication wait")
    void deactivate_whenAuthenticationWaitIsBlocked_unblocksWaiter() throws Exception {
        var subject = new ProviderAuthLifecycle();
        long generation = subject.currentGeneration();
        var result = new CompletableFuture<String>();
        assertThat(subject.register(generation, result, () -> result.complete("cancelled"))).isTrue();
        var observed = new AtomicReference<String>();
        Thread waiter = Thread.startVirtualThread(() -> observed.set(result.join()));

        subject.deactivate();
        waiter.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(waiter.isAlive()).isFalse();
        assertThat(observed).hasValue("cancelled");
    }

    private static final class AwaitObservedFuture<T> extends CompletableFuture<T> {
        private final CountDownLatch getCalled = new CountDownLatch(1);

        @Override
        public T get() throws InterruptedException, ExecutionException {
            getCalled.countDown();
            return super.get();
        }

        private boolean awaitGetCalled() throws InterruptedException {
            return getCalled.await(2, TimeUnit.SECONDS);
        }
    }
}
