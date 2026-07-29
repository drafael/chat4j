package com.github.drafael.chat4j.mcp;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedMcpHttpClientBuilderTest {

    @Test
    @DisplayName("An oversized JSON body fails before the body handler completes")
    void sendAsync_whenJsonBodyExceedsLimit_failsRequest() throws Exception {
        byte[] body = "x".repeat(1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        withStartedServer(server, () -> {
            URI endpoint = URI.create("http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()));
            var subject = new BoundedMcpHttpClientBuilder();
            HttpClient client = subject.followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                var request = HttpRequest.newBuilder(endpoint).GET().build();

                assertThatThrownBy(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join())
                        .isInstanceOf(CompletionException.class)
                        .hasRootCauseMessage("MCP HTTP body exceeds the 1 MiB limit.");
            } finally {
                client.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("An oversized SSE event fails before event decoding completes")
    void sendAsync_whenSseEventExceedsLimit_failsRequest() throws Exception {
        byte[] body = "data: %s\n\n".formatted("x".repeat(1024 * 1024)).getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        withStartedServer(server, () -> {
            URI endpoint = URI.create("http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()));
            var subject = new BoundedMcpHttpClientBuilder();
            HttpClient client = subject.followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                var request = HttpRequest.newBuilder(endpoint).GET().build();

                assertThatThrownBy(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join())
                        .isInstanceOf(CompletionException.class)
                        .hasRootCauseMessage("MCP SSE event exceeds the 1 MiB limit.");
            } finally {
                client.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("Clean out-of-band GET SSE completion signals session poisoning")
    void sendAsync_whenGetSseCompletes_signalsUnexpectedCompletion() throws Exception {
        byte[] body = "event: message\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        withStartedServer(server, () -> {
            var completion = new CountDownLatch(1);
            URI endpoint = URI.create("http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()));
            var subject = new BoundedMcpHttpClientBuilder();
            subject.onOutOfBandStreamCompletion(error -> completion.countDown());
            HttpClient client = subject.followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                client.sendAsync(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .join();

                assertThat(completion.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                client.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("Finite POST SSE completion does not poison the out-of-band session")
    void sendAsync_whenPostSseCompletes_doesNotSignalUnexpectedCompletion() throws Exception {
        byte[] body = "event: message\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        withStartedServer(server, () -> {
            var completions = new AtomicInteger();
            URI endpoint = URI.create("http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()));
            var subject = new BoundedMcpHttpClientBuilder();
            subject.onOutOfBandStreamCompletion(error -> completions.incrementAndGet());
            HttpClient client = subject.followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                client.sendAsync(
                        HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString()
                ).join();

                assertThat(completions).hasValue(0);
            } finally {
                client.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("A non-success DELETE response fails graceful session close")
    void sendAsync_whenDeleteIsRejected_failsRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        withStartedServer(server, () -> {
            URI endpoint = URI.create("http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()));
            var subject = new BoundedMcpHttpClientBuilder();
            HttpClient client = subject.followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                var request = HttpRequest.newBuilder(endpoint).DELETE().build();

                assertThatThrownBy(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join())
                        .isInstanceOf(CompletionException.class)
                        .hasRootCauseMessage("MCP HTTP session close was rejected.");
            } finally {
                client.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("Hard shutdown cancels a nonresponsive DELETE request")
    void shutdownNow_whenDeleteDoesNotRespond_cancelsRequest() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            received.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        withStartedServer(server, () -> {
            URI endpoint = URI.create("http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()));
            var subject = new BoundedMcpHttpClientBuilder();
            HttpClient client = subject.followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                var request = HttpRequest.newBuilder(endpoint).DELETE().build();
                var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();

                client.shutdownNow();

                assertThatThrownBy(response::join).isInstanceOfAny(CompletionException.class, CancellationException.class);
            } finally {
                release.countDown();
                client.shutdownNow();
            }
        });
    }

    @Test
    @DisplayName("A redirect is rejected without requesting its destination")
    void sendAsync_whenResponseRedirects_doesNotIssueSecondRequest() throws Exception {
        AtomicInteger destinationRequests = new AtomicInteger();
        AtomicInteger destinationHeaders = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().set("Location", "/destination");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/destination", exchange -> {
            destinationRequests.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey("Authorization")) {
                destinationHeaders.incrementAndGet();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        withStartedServer(server, () -> {
            URI endpoint = URI.create("http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()));
            var subject = new BoundedMcpHttpClientBuilder();
            HttpClient client = subject.followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                var request = HttpRequest.newBuilder(endpoint)
                        .header("Authorization", "Bearer origin-only-secret")
                        .GET()
                        .build();

                assertThatThrownBy(() -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join())
                        .isInstanceOf(CompletionException.class)
                        .hasRootCauseMessage("MCP HTTP redirects are not allowed.");
                assertThat(destinationRequests).hasValue(0);
                assertThat(destinationHeaders).hasValue(0);
            } finally {
                client.shutdownNow();
            }
        });
    }
    private void withStartedServer(HttpServer server, ThrowingRunnable action) throws Exception {
        server.start();
        try {
            action.run();
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
