package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class JavaNetSttHttpTransport implements SttHttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public JavaNetSttHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    JavaNetSttHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public SttHttpResponse send(SttHttpRequest request, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.method(request.method(), request.bodyPublisher()).build();
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                httpRequest,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        var responseBody = new AtomicReference<InputStream>();
        var finished = new AtomicBoolean();
        var timedOut = new AtomicBoolean();
        Thread watcher = Thread.startVirtualThread(() -> watchCancellationAndTimeout(
                future,
                responseBody,
                finished,
                timedOut,
                cancellationToken,
                request.timeout()
        ));
        try {
            HttpResponse<InputStream> response = future.join();
            responseBody.set(response.body());
            if (cancellationRequested(cancellationToken) || timedOut.get()) {
                close(response.body());
                throw terminalException(cancellationToken, null);
            }
            byte[] body = boundedBody(response.body(), request.maxResponseBytes());
            if (cancellationRequested(cancellationToken) || timedOut.get()) {
                throw terminalException(cancellationToken, null);
            }
            return new SttHttpResponse(response.statusCode(), response.headers().map(), body);
        } catch (CancellationException e) {
            throw terminalException(cancellationToken, e);
        } catch (Exception e) {
            if (cancellationRequested(cancellationToken) || timedOut.get() || causedByTimeout(e)) {
                throw terminalException(cancellationToken, e);
            }
            throw e;
        } finally {
            finished.set(true);
            watcher.interrupt();
            join(watcher);
        }
    }

    private static void watchCancellationAndTimeout(
            CompletableFuture<?> future,
            AtomicReference<InputStream> responseBody,
            AtomicBoolean finished,
            AtomicBoolean timedOut,
            SpeechToTextProviderContext.CancellationToken cancellationToken,
            Duration timeout
    ) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!finished.get()) {
            if (cancellationRequested(cancellationToken) || System.nanoTime() >= deadlineNanos) {
                timedOut.set(!cancellationRequested(cancellationToken));
                future.cancel(true);
                close(responseBody.get());
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean causedByTimeout(Exception exception) {
        Throwable cause = exception instanceof CompletionException && exception.getCause() != null
                ? exception.getCause()
                : exception;
        return cause instanceof HttpTimeoutException;
    }

    private static SpeechToTextException terminalException(
            SpeechToTextProviderContext.CancellationToken cancellationToken,
            Exception cause
    ) {
        String message = cancellationRequested(cancellationToken)
                ? "Transcription canceled."
                : "Transcription request timed out.";
        return cause == null ? new SpeechToTextException(message) : new SpeechToTextException(message, cause);
    }

    private static boolean cancellationRequested(SpeechToTextProviderContext.CancellationToken cancellationToken) {
        return cancellationToken != null && cancellationToken.cancelled();
    }

    private static void close(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    private static void join(Thread thread) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    restoreInterrupt = true;
                }
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static byte[] boundedBody(InputStream input, long maxResponseBytes) throws Exception {
        long limit = maxResponseBytes <= 0 ? 1024 * 1024 : maxResponseBytes;
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new SpeechToTextException("Provider response was too large.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
