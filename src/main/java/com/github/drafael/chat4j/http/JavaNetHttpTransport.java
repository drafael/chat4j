package com.github.drafael.chat4j.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import lombok.NonNull;

public final class JavaNetHttpTransport implements HttpTransport {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long CANCELLATION_POLL_MILLIS = 25;

    private final HttpClient client;

    public JavaNetHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build());
    }

    public JavaNetHttpTransport(@NonNull HttpClient client) {
        this.client = client;
    }

    public static JavaNetHttpTransport create(@NonNull Duration connectTimeout, @NonNull RedirectPolicy redirects) {
        return new JavaNetHttpTransport(HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(redirects.jdkPolicy)
                .build());
    }

    @Override
    public HttpExchangeResponse send(@NonNull HttpExchangeRequest request, BooleanSupplier isCancelled) throws Exception {
        try (HttpCall call = open(request)) {
            return call.await(isCancelled);
        }
    }

    @Override
    public HttpCall open(@NonNull HttpExchangeRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.method(request.method(), publisher(request.body())).build();
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(
                httpRequest,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        return new JavaNetHttpCall(future, request.timeout(), request.maxResponseBytes());
    }

    private static HttpRequest.BodyPublisher publisher(HttpBody body) {
        return switch (body) {
            case HttpBody.Empty ignored -> HttpRequest.BodyPublishers.noBody();
            case HttpBody.Bytes bytes -> HttpRequest.BodyPublishers.ofByteArray(bytes.value());
            case HttpBody.File file -> filePublisher(file);
            case HttpBody.Composite composite -> HttpRequest.BodyPublishers.concat(
                    composite.parts().stream().map(JavaNetHttpTransport::publisher).toArray(HttpRequest.BodyPublisher[]::new)
            );
        };
    }

    private static HttpRequest.BodyPublisher filePublisher(HttpBody.File file) {
        try {
            return HttpRequest.BodyPublishers.ofFile(file.path());
        } catch (IOException e) {
            throw new IllegalArgumentException("HTTP request file could not be opened.", e);
        }
    }

    public enum RedirectPolicy {
        NEVER(HttpClient.Redirect.NEVER),
        NORMAL(HttpClient.Redirect.NORMAL),
        ALWAYS(HttpClient.Redirect.ALWAYS);

        private final HttpClient.Redirect jdkPolicy;

        RedirectPolicy(HttpClient.Redirect jdkPolicy) {
            this.jdkPolicy = jdkPolicy;
        }
    }

    private static final class JavaNetHttpCall implements HttpCall {
        private final CompletableFuture<HttpResponse<InputStream>> future;
        private final Duration timeout;
        private final long maxResponseBytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<InputStream> responseStream = new AtomicReference<>();

        private JavaNetHttpCall(CompletableFuture<HttpResponse<InputStream>> future, Duration timeout, long maxResponseBytes) {
            this.future = future;
            this.timeout = timeout;
            this.maxResponseBytes = maxResponseBytes;
            future.whenComplete((response, failure) -> {
                if (response != null && closed.get()) {
                    closeQuietly(response.body());
                }
            });
        }

        @Override
        public HttpExchangeResponse await(BooleanSupplier isCancelled) throws Exception {
            BooleanSupplier cancellation = isCancelled == null ? () -> false : isCancelled;
            var finished = new AtomicBoolean();
            var timedOut = new AtomicBoolean();
            var interrupted = new AtomicBoolean();
            Thread owner = Thread.currentThread();
            Thread watcher = startWatcher(cancellation, finished, timedOut, interrupted, owner);
            try {
                HttpResponse<InputStream> response = awaitResponse(cancellation);
                InputStream stream = response.body();
                responseStream.set(stream);
                if (closed.get() || cancellation.getAsBoolean()) {
                    closeQuietly(stream);
                    throw new CancellationException("HTTP request was canceled.");
                }
                rejectDeclaredOversize(response, maxResponseBytes);
                byte[] body = readBounded(stream, maxResponseBytes, cancellation);
                if (timedOut.get()) {
                    throw new HttpTimeoutException("HTTP request timed out.");
                }
                if (closed.get() || cancellation.getAsBoolean()) {
                    throw new CancellationException("HTTP request was canceled.");
                }
                return new HttpExchangeResponse(response.statusCode(), response.headers().map(), body);
            } catch (InterruptedException e) {
                close();
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                if (interrupted.get()) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("HTTP request was interrupted.");
                }
                if (timedOut.get()) {
                    throw new HttpTimeoutException("HTTP request timed out.");
                }
                if (closed.get() || cancellation.getAsBoolean()) {
                    throw new CancellationException("HTTP request was canceled.");
                }
                throw unwrap(e);
            } finally {
                finished.set(true);
                watcher.interrupt();
                joinPreservingInterrupt(watcher);
            }
        }

        private Thread startWatcher(
                BooleanSupplier cancellation,
                AtomicBoolean finished,
                AtomicBoolean timedOut,
                AtomicBoolean interrupted,
                Thread owner
        ) {
            return Thread.startVirtualThread(() -> {
                long deadline = System.nanoTime() + timeout.toNanos();
                while (!finished.get()) {
                    boolean cancelled = cancellation.getAsBoolean();
                    boolean ownerInterrupted = owner.isInterrupted();
                    if (cancelled || ownerInterrupted || System.nanoTime() >= deadline) {
                        interrupted.set(ownerInterrupted);
                        timedOut.set(!cancelled && !ownerInterrupted);
                        close();
                        return;
                    }
                    try {
                        Thread.sleep(CANCELLATION_POLL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        private HttpResponse<InputStream> awaitResponse(BooleanSupplier cancellation) throws Exception {
            while (true) {
                if (closed.get() || cancellation.getAsBoolean()) {
                    close();
                    throw new CancellationException("HTTP request was canceled.");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("HTTP request was interrupted.");
                }
                try {
                    return future.get(CANCELLATION_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll interruption and logical cancellation while headers are pending.
                }
            }
        }

        private static byte[] readBounded(InputStream input, long limit, BooleanSupplier cancellation) throws Exception {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("HTTP request was interrupted.");
                    }
                    if (cancellation.getAsBoolean()) {
                        throw new CancellationException("HTTP request was canceled.");
                    }
                    total += read;
                    if (limit > 0 && total > limit) {
                        throw new IOException("HTTP response was too large.");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }

        private static void rejectDeclaredOversize(HttpResponse<?> response, long limit) throws IOException {
            OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
            if (limit > 0 && contentLength.isPresent() && contentLength.getAsLong() > limit) {
                closeQuietly((InputStream) response.body());
                throw new IOException("HTTP response was too large.");
            }
        }

        private static Exception unwrap(Exception exception) {
            if (exception instanceof ExecutionException execution && execution.getCause() instanceof Exception cause) {
                return cause;
            }
            return exception;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                HttpResponse<InputStream> completed = completedResponse();
                future.cancel(true);
                closeQuietly(responseStream.get());
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

        private static void joinPreservingInterrupt(Thread thread) {
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

        private static void closeQuietly(InputStream input) {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
