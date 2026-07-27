package com.github.drafael.chat4j.chat.agent;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

final class AgentHttpSupport {

    private AgentHttpSupport() {
    }

    static Response send(HttpClient client, HttpRequest request, BooleanSupplier isCancelled) throws Exception {
        if (shouldStop(isCancelled)) {
            throw new CancellationException("Agent request cancelled");
        }
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        try {
            while (true) {
                if (shouldStop(isCancelled)) {
                    future.cancel(true);
                    throw new CancellationException("Agent request cancelled");
                }
                try {
                    HttpResponse<String> response = future.get(100, TimeUnit.MILLISECONDS);
                    if (shouldStop(isCancelled)) {
                        throw new CancellationException("Agent request cancelled");
                    }
                    return new Response(response.statusCode(), response.body());
                } catch (TimeoutException ignored) {
                    // Poll the logical cancellation flag while the request is in flight.
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || isCancelled.getAsBoolean();
    }

    record Response(int statusCode, String body) {
    }
}
