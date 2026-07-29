package com.github.drafael.chat4j.mcp;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Strings;

final class BoundedMcpHttpClientBuilder implements HttpClient.Builder {

    private static final long MAX_JSON_BODY_BYTES = 1024 * 1024;
    private static final long MAX_SSE_EVENT_BYTES = 1024 * 1024;
    private static final long MAX_SSE_STREAM_BYTES = 8 * 1024 * 1024;

    private final HttpClient.Builder delegate = HttpClient.newBuilder();
    private final AtomicReference<TrackedHttpClient> builtClient = new AtomicReference<>();
    private volatile Consumer<Throwable> outOfBandStreamCompletionHandler = ignored -> { };

    TrackedHttpClient builtClient() {
        return builtClient.get();
    }

    void onOutOfBandStreamCompletion(Consumer<Throwable> handler) {
        outOfBandStreamCompletionHandler = handler == null ? ignored -> { } : handler;
    }

    @Override
    public HttpClient.Builder cookieHandler(@NonNull CookieHandler cookieHandler) {
        delegate.cookieHandler(cookieHandler);
        return this;
    }

    @Override
    public HttpClient.Builder connectTimeout(@NonNull Duration duration) {
        delegate.connectTimeout(duration);
        return this;
    }

    @Override
    public HttpClient.Builder sslContext(@NonNull SSLContext sslContext) {
        delegate.sslContext(sslContext);
        return this;
    }

    @Override
    public HttpClient.Builder sslParameters(@NonNull SSLParameters sslParameters) {
        delegate.sslParameters(sslParameters);
        return this;
    }

    @Override
    public HttpClient.Builder executor(@NonNull Executor executor) {
        delegate.executor(executor);
        return this;
    }

    @Override
    public HttpClient.Builder followRedirects(@NonNull HttpClient.Redirect policy) {
        delegate.followRedirects(policy);
        return this;
    }

    @Override
    public HttpClient.Builder version(@NonNull HttpClient.Version version) {
        delegate.version(version);
        return this;
    }

    @Override
    public HttpClient.Builder priority(int priority) {
        delegate.priority(priority);
        return this;
    }

    @Override
    public HttpClient.Builder proxy(@NonNull ProxySelector proxySelector) {
        delegate.proxy(proxySelector);
        return this;
    }

    @Override
    public HttpClient.Builder authenticator(@NonNull Authenticator authenticator) {
        delegate.authenticator(authenticator);
        return this;
    }

    @Override
    public HttpClient.Builder localAddress(@NonNull InetAddress localAddress) {
        delegate.localAddress(localAddress);
        return this;
    }

    @Override
    public HttpClient build() {
        var result = new TrackedHttpClient(delegate.build(), () -> outOfBandStreamCompletionHandler);
        builtClient.set(result);
        return result;
    }

    @RequiredArgsConstructor
    static final class TrackedHttpClient extends HttpClient {
        @NonNull
        private final HttpClient delegate;
        private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();
        @NonNull
        private final Supplier<Consumer<Throwable>> streamCompletionHandler;

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> HttpResponse<T> send(
                @NonNull HttpRequest request,
                @NonNull HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException, InterruptedException {
            ensureOpen();
            return delegate.send(request, bounded(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                @NonNull HttpRequest request,
                @NonNull HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            ensureOpen();
            return track(delegate.sendAsync(request, bounded(request, responseBodyHandler)));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                @NonNull HttpRequest request,
                @NonNull HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            ensureOpen();
            return track(delegate.sendAsync(request, bounded(request, responseBodyHandler), pushPromiseHandler));
        }

        @Override
        public void shutdown() {
            closed.set(true);
            requests.forEach(request -> request.cancel(true));
            delegate.shutdown();
        }

        @Override
        public void shutdownNow() {
            if (closed.compareAndSet(false, true)) {
                requests.forEach(request -> request.cancel(true));
            }
            delegate.shutdownNow();
        }

        @Override
        public boolean awaitTermination(@NonNull Duration duration) throws InterruptedException {
            return delegate.awaitTermination(duration);
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        private <T> CompletableFuture<HttpResponse<T>> track(CompletableFuture<HttpResponse<T>> request) {
            requests.add(request);
            request.whenComplete((ignored, error) -> requests.remove(request));
            return request;
        }

        private <T> HttpResponse.BodyHandler<T> bounded(
                HttpRequest request,
                HttpResponse.BodyHandler<T> downstream
        ) {
            return responseInfo -> {
                if (responseInfo.statusCode() >= 300 && responseInfo.statusCode() < 400) {
                    throw new IllegalStateException("MCP HTTP redirects are not allowed.");
                }
                if (Strings.CI.equals("DELETE", request.method())
                        && (responseInfo.statusCode() < 200 || responseInfo.statusCode() >= 300)) {
                    throw new IllegalStateException("MCP HTTP session close was rejected.");
                }
                boolean sse = responseInfo.headers().firstValue("Content-Type")
                        .map(value -> value.toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
                        .orElse(false);
                boolean poisonOnCompletion = sse && Strings.CI.equals("GET", request.method());
                return new BoundedBodySubscriber<>(
                        downstream.apply(responseInfo),
                        sse,
                        poisonOnCompletion,
                        () -> {
                            if (!closed.get()) {
                                streamCompletionHandler.get().accept(new IllegalStateException(
                                        "MCP out-of-band HTTP stream ended unexpectedly."
                                ));
                            }
                        }
                );
            };
        }

        private void ensureOpen() {
            if (closed.get()) {
                throw new IllegalStateException("MCP HTTP client is closed.");
            }
        }
    }

    @RequiredArgsConstructor
    private static final class BoundedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {
        @NonNull
        private final HttpResponse.BodySubscriber<T> delegate;
        private final boolean sse;
        private Flow.Subscription subscription;
        private long totalBytes;
        private long eventBytes;
        private long lineContentBytes;
        private final boolean poisonOnCompletion;
        @NonNull
        private final Runnable completionHandler;
        private boolean failed;

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(@NonNull Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(@NonNull List<ByteBuffer> buffers) {
            if (failed) {
                return;
            }
            try {
                buffers.forEach(this::count);
                delegate.onNext(buffers);
            } catch (RuntimeException e) {
                failed = true;
                subscription.cancel();
                delegate.onError(e);
            }
        }

        @Override
        public void onError(@NonNull Throwable throwable) {
            if (!failed) {
                delegate.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!failed) {
                delegate.onComplete();
                if (poisonOnCompletion) {
                    completionHandler.run();
                }
            }
        }

        private void count(ByteBuffer source) {
            ByteBuffer buffer = source.asReadOnlyBuffer();
            while (buffer.hasRemaining()) {
                byte value = buffer.get();
                totalBytes++;
                if (!sse && totalBytes > MAX_JSON_BODY_BYTES) {
                    throw new IllegalStateException("MCP HTTP body exceeds the 1 MiB limit.");
                }
                if (sse) {
                    eventBytes++;
                    if (totalBytes > MAX_SSE_STREAM_BYTES) {
                        throw new IllegalStateException("MCP SSE stream exceeds the 8 MiB limit.");
                    }
                    if (value == '\n') {
                        if (lineContentBytes == 0) {
                            eventBytes = 0;
                        }
                        lineContentBytes = 0;
                    } else if (value != '\r') {
                        lineContentBytes++;
                    }
                    if (eventBytes > MAX_SSE_EVENT_BYTES) {
                        throw new IllegalStateException("MCP SSE event exceeds the 1 MiB limit.");
                    }
                }
            }
        }
    }
}
