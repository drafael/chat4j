package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class JavaNetSttHttpTransportTest {

    @Test
    @DisplayName("Request timeout closes a response body that stalls after sending headers")
    void send_whenResponseBodyStallsAfterHeaders_honorsRequestTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        var headersSent = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        server.createContext("/transcribe", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            headersSent.countDown();
            try {
                releaseResponse.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var subject = new JavaNetSttHttpTransport();
        var request = new SttHttpRequest(
                "POST",
                java.net.URI.create("http://127.0.0.1:%d/transcribe".formatted(server.getAddress().getPort())),
                Map.of(),
                HttpRequest.BodyPublishers.noBody(),
                Duration.ofMillis(500),
                1_024
        );

        CompletableFuture<SttHttpResponse> response = CompletableFuture.supplyAsync(() -> {
            try {
                return subject.send(request, () -> false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            assertThat(headersSent.await(2, TimeUnit.SECONDS)).isTrue();

            Throwable error = catchThrowable(() -> response.get(2, TimeUnit.SECONDS));
            assertThat(ExceptionUtils.getThrowableList(error)).anySatisfy(cause -> assertThat(cause)
                    .isInstanceOf(SpeechToTextException.class)
                    .hasMessage("Transcription request timed out."));
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Cancellation closes a response body that stalls after sending headers")
    void send_whenCancelledAfterResponseHeaders_unblocksBodyRead() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        var headersSent = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        server.createContext("/transcribe", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            headersSent.countDown();
            try {
                releaseResponse.await();
                exchange.getResponseBody().write("late response".getBytes());
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        var cancelled = new AtomicBoolean();
        var subject = new JavaNetSttHttpTransport();
        var request = new SttHttpRequest(
                "POST",
                java.net.URI.create("http://127.0.0.1:%d/transcribe".formatted(server.getAddress().getPort())),
                Map.of(),
                HttpRequest.BodyPublishers.noBody(),
                Duration.ofSeconds(10),
                1_024
        );

        CompletableFuture<SttHttpResponse> response = CompletableFuture.supplyAsync(() -> {
            try {
                return subject.send(request, cancelled::get);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            assertThat(headersSent.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);

            Throwable error = catchThrowable(() -> response.get(2, TimeUnit.SECONDS));
            assertThat(ExceptionUtils.getThrowableList(error)).anySatisfy(cause -> assertThat(cause)
                    .isInstanceOf(SpeechToTextException.class)
                    .hasMessage("Transcription canceled."));
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }
}
