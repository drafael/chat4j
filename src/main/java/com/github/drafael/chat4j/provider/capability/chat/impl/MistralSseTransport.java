package com.github.drafael.chat4j.provider.capability.chat.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import lombok.NonNull;

final class MistralSseTransport {

    private static final long CANCELLATION_POLL_MILLIS = 100;

    private final HttpClient client;

    MistralSseTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    MistralSseTransport(@NonNull HttpClient client) {
        this.client = client;
    }

    Call open(@NonNull URI endpoint, @NonNull Map<String, String> headers, byte[] requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody == null ? new byte[0] : requestBody.clone()));
        headers.forEach(builder::header);
        return new Call(client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()));
    }

    static final class Call implements AutoCloseable {
        private final CompletableFuture<HttpResponse<InputStream>> future;
        private final AtomicReference<InputStream> stream = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Call(CompletableFuture<HttpResponse<InputStream>> future) {
            this.future = future;
            future.whenComplete((response, failure) -> {
                if (response != null && closed.get()) {
                    closeQuietly(response.body());
                }
            });
        }

        Response await(BooleanSupplier isCancelled) throws Exception {
            BooleanSupplier cancellation = isCancelled == null ? () -> false : isCancelled;
            if (shouldStop(cancellation)) {
                close();
                return null;
            }
            try {
                while (true) {
                    if (shouldStop(cancellation)) {
                        close();
                        return null;
                    }
                    try {
                        HttpResponse<InputStream> response = future.get(
                                CANCELLATION_POLL_MILLIS,
                                TimeUnit.MILLISECONDS
                        );
                        InputStream body = response.body();
                        stream.set(body);
                        if (closed.get() || shouldStop(cancellation)) {
                            closeQuietly(body);
                            return null;
                        }
                        return new Response(response.statusCode(), body);
                    } catch (TimeoutException ignored) {
                        // Recheck cooperative cancellation while headers are pending.
                    }
                }
            } catch (InterruptedException e) {
                close();
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IOException("Mistral Conversations request failed", cause);
            }
        }

        private boolean shouldStop(BooleanSupplier cancellation) {
            return Thread.currentThread().isInterrupted() || cancellation.getAsBoolean();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                HttpResponse<InputStream> completed = completedResponse();
                future.cancel(true);
                closeQuietly(stream.get());
                if (completed != null) {
                    closeQuietly(completed.body());
                }
            }
        }

        private HttpResponse<InputStream> completedResponse() {
            return future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally()
                    ? future.getNow(null)
                    : null;
        }

        private static void closeQuietly(InputStream input) {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    record Response(int statusCode, InputStream body) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
