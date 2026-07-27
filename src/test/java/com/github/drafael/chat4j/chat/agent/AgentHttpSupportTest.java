package com.github.drafael.chat4j.chat.agent;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentHttpSupportTest {

    @Test
    @DisplayName("Cancellation aborts an in-flight direct agent HTTP request without waiting for its timeout")
    void send_whenCancelledInFlight_abortsRequest() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent", exchange -> {
            requestStarted.countDown();
            try {
                releaseResponse.await();
                byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var cancelled = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:%d/agent".formatted(server.getAddress().getPort())))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            var response = executor.submit(() -> AgentHttpSupport.send(
                    HttpClient.newHttpClient(),
                    request,
                    cancelled::get
            ));
            try {
                assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
                cancelled.set(true);

                assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(CancellationException.class);
            } finally {
                releaseResponse.countDown();
            }
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }
}
