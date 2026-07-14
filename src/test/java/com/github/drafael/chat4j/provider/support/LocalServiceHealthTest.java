package com.github.drafael.chat4j.provider.support;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalServiceHealthTest {

    @Test
    @DisplayName("Non-blocking health check returns false when no cached status exists")
    void isReachableNonBlocking_whenNoCachedStatusExists_returnsFalse() {
        String baseUrl = "not-a-url";

        assertThat(LocalServiceHealth.isReachableNonBlocking(baseUrl)).isFalse();
        assertThat(LocalServiceHealth.isReachable(baseUrl)).isFalse();
    }

    @Test
    @DisplayName("Health checks on the EDT return cached state without blocking")
    void isReachable_whenCalledOnEdt_returnsBeforeProbeCompletes() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var releaseRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/edt/models", exchange -> {
            requestStarted.countDown();
            try {
                releaseRequest.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:%d/edt".formatted(server.getAddress().getPort());
            var reachable = new AtomicReference<Boolean>();

            SwingUtilities.invokeAndWait(() -> reachable.set(LocalServiceHealth.isReachable(baseUrl)));

            assertThat(reachable).hasValue(false);
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            releaseRequest.countDown();
            assertThat(LocalServiceHealth.isReachable(baseUrl)).isTrue();
        } finally {
            releaseRequest.countDown();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Already interrupted health checks do not start a probe")
    void isReachable_whenCallerIsAlreadyInterrupted_skipsProbe() throws Exception {
        var requests = new AtomicInteger();
        var requestStarted = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pre-interrupted/models", exchange -> {
            requests.incrementAndGet();
            requestStarted.countDown();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:%d/pre-interrupted".formatted(server.getAddress().getPort());
            var reachable = new AtomicReference<Boolean>();
            var interrupted = new AtomicBoolean();
            Thread caller = Thread.startVirtualThread(() -> {
                Thread.currentThread().interrupt();
                reachable.set(LocalServiceHealth.isReachable(baseUrl));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            caller.join(2_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(reachable).hasValue(false);
            assertThat(interrupted).isTrue();
            assertThat(requestStarted.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Interrupted blocking health checks return without abandoning the shared probe")
    void isReachable_whenWaitingThreadIsInterrupted_preservesInterruptStatus() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var releaseRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/interrupt/models", exchange -> {
            requestStarted.countDown();
            try {
                releaseRequest.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:%d/interrupt".formatted(server.getAddress().getPort());
            var reachable = new AtomicReference<Boolean>();
            var interrupted = new AtomicBoolean();
            Thread caller = Thread.startVirtualThread(() -> {
                reachable.set(LocalServiceHealth.isReachable(baseUrl));
                interrupted.set(Thread.currentThread().isInterrupted());
            });

            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(reachable).hasValue(false);
            assertThat(interrupted).isTrue();

            releaseRequest.countDown();
            assertThat(LocalServiceHealth.isReachable(baseUrl)).isTrue();
        } finally {
            releaseRequest.countDown();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Overlapping blocking health checks share the same probe result")
    void isReachable_whenProbeAlreadyInFlight_waitsForSharedResult() throws Exception {
        var requests = new AtomicInteger();
        var requestStarted = new CountDownLatch(1);
        var releaseRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/shared/models", exchange -> {
            requests.incrementAndGet();
            requestStarted.countDown();
            try {
                releaseRequest.await(2, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:%d/shared".formatted(server.getAddress().getPort());
            var firstResult = new AtomicReference<Boolean>();
            var secondResult = new AtomicReference<Boolean>();
            Thread first = Thread.startVirtualThread(() -> firstResult.set(LocalServiceHealth.isReachable(baseUrl)));
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread second = Thread.startVirtualThread(() -> secondResult.set(LocalServiceHealth.isReachable(baseUrl)));
            releaseRequest.countDown();
            first.join(2_000);
            second.join(2_000);

            assertThat(first.isAlive()).isFalse();
            assertThat(second.isAlive()).isFalse();
            assertThat(firstResult).hasValue(true);
            assertThat(secondResult).hasValue(true);
            assertThat(requests).hasValue(1);
        } finally {
            releaseRequest.countDown();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Non-blocking health check returns false when base URL is blank")
    void isReachableNonBlocking_whenBaseUrlBlank_returnsFalse() {
        boolean reachable = LocalServiceHealth.isReachableNonBlocking("  ");

        assertThat(reachable).isFalse();
    }
}
