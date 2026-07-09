package com.github.drafael.chat4j.stt.provider.whisper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhisperModelInstallerTest {

    private static final String HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Installer accepts trusted Hugging Face wildcard redirect subdomains")
    void downloadAndInstall_whenRedirectUsesHfWildcardSubdomain_installsModel() throws Exception {
        var client = new FakeHttpClient(
                FakeResponse.redirect("https://foo.hf.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"),
                FakeResponse.ok("hello", 5)
        );
        var subject = new WhisperModelInstaller(client);
        Path root = tempDir.resolve("models");

        subject.downloadAndInstall(entry(), root, tempDir.resolve("temp"), () -> false, (status, done, total, cancelable) -> {
        });

        assertThat(Files.readString(root.resolve("tiny").resolve("model.bin"))).isEqualTo("hello");
        assertThat(client.requests()).extracting(HttpRequest::uri)
                .containsExactly(
                        URI.create("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"),
                        URI.create("https://foo.hf.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin")
                );
    }

    @Test
    @DisplayName("Installer rejects redirect hosts that only look like Hugging Face suffixes")
    void downloadAndInstall_whenRedirectUsesLookalikeSuffix_rejectsHost() {
        var subject = new WhisperModelInstaller(new FakeHttpClient(
                FakeResponse.redirect("https://evil-hf.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin")
        ));

        assertThatThrownBy(() -> subject.downloadAndInstall(entry(), tempDir.resolve("models"), tempDir.resolve("temp"), () -> false, (status, done, total, cancelable) -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("untrusted host");
    }

    @Test
    @DisplayName("Installer rejects mismatched content length before publishing")
    void downloadAndInstall_whenContentLengthDiffers_rejectsDownload() {
        var body = new CloseTrackingInputStream("hello!");
        var subject = new WhisperModelInstaller(new FakeHttpClient(FakeResponse.ok(body, 6)));

        assertThatThrownBy(() -> subject.downloadAndInstall(entry(), tempDir.resolve("models"), tempDir.resolve("temp"), () -> false, (status, done, total, cancelable) -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("larger than expected");
        assertThat(body.closed()).isTrue();
        assertThat(tempDir.resolve("models").resolve("tiny")).doesNotExist();
    }

    @Test
    @DisplayName("Aborting an active response body unblocks a stalled Whisper model download")
    void abortActiveDownload_whenResponseBodyReadStalls_closesBodyAndCleansStaging() throws Exception {
        var body = new BlockingInputStream();
        var client = new FakeHttpClient(FakeResponse.ok(body, 5));
        var subject = new WhisperModelInstaller(client);
        Path tempRoot = tempDir.resolve("temp");

        CompletableFuture<Void> download = CompletableFuture.runAsync(() -> {
            try {
                subject.downloadAndInstall(entry(), tempDir.resolve("models"), tempRoot, () -> false, (status, done, total, cancelable) -> {
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(body.awaitReadStarted()).isTrue();

        subject.abortActiveDownload();

        assertThatThrownBy(download::join).isInstanceOf(Exception.class);
        assertThat(body.closed()).isTrue();
        assertThat(listDirectories(tempRoot)).isEmpty();
    }

    @Test
    @DisplayName("Installer rejects checksum mismatch before publishing")
    void downloadAndInstall_whenChecksumDiffers_rejectsDownload() {
        var subject = new WhisperModelInstaller(new FakeHttpClient(FakeResponse.ok("hullo", 5)));

        assertThatThrownBy(() -> subject.downloadAndInstall(entry(), tempDir.resolve("models"), tempDir.resolve("temp"), () -> false, (status, done, total, cancelable) -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum");
        assertThat(tempDir.resolve("models").resolve("tiny")).doesNotExist();
    }

    @Test
    @DisplayName("Aborting an active download cancels the in-flight HTTP future before a body exists")
    void abortActiveDownload_whenHttpResponseHasNotArrived_cancelsFutureAndCleansStaging() throws Exception {
        CompletableFuture<HttpResponse<InputStream>> pending = new CompletableFuture<>();
        var client = new FakeHttpClient(pending);
        var subject = new WhisperModelInstaller(client);
        Path tempRoot = tempDir.resolve("temp");

        CompletableFuture<Void> download = CompletableFuture.runAsync(() -> {
            try {
                subject.downloadAndInstall(entry(), tempDir.resolve("models"), tempRoot, () -> false, (status, done, total, cancelable) -> {
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitUntil(() -> !client.requests().isEmpty());

        subject.abortActiveDownload();

        assertThatThrownBy(download::join).isInstanceOf(Exception.class);
        assertThat(pending).isCancelled();
        assertThat(listDirectories(tempRoot)).isEmpty();
    }

    private WhisperModelCatalogEntry entry() {
        return new WhisperModelCatalogEntry(
                "tiny",
                "Whisper tiny",
                "Test model",
                "5 B",
                URI.create("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"),
                5,
                HELLO_SHA1,
                "ggml-tiny.bin",
                false,
                false,
                false
        );
    }

    private List<Path> listDirectories(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (var stream = Files.list(parent)) {
            return stream.filter(Files::isDirectory).toList();
        }
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.met()).isTrue();
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }

    private static final class FakeHttpClient extends HttpClient {
        private final ArrayDeque<FakeResponse> responses = new ArrayDeque<>();
        private final CompletableFuture<HttpResponse<InputStream>> pending;
        private final List<HttpRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

        private FakeHttpClient(FakeResponse... responses) {
            this.responses.addAll(List.of(responses));
            this.pending = null;
        }

        private FakeHttpClient(CompletableFuture<HttpResponse<InputStream>> pending) {
            this.pending = pending;
        }

        private List<HttpRequest> requests() {
            return requests;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("Whisper installer should use cancellable sendAsync");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.add(request);
            if (pending != null) {
                return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pending;
            }
            FakeResponse response = responses.removeFirst();
            return CompletableFuture.completedFuture((HttpResponse<T>) response.toHttpResponse(request));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseTrackingInputStream(String body) {
            super(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }

        private boolean closed() {
            return closed.get();
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean closeCalled = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            readStarted.countDown();
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closeCalled.set(true);
            closed.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(5, TimeUnit.SECONDS);
        }

        private boolean closed() {
            return closeCalled.get();
        }
    }

    private record FakeResponse(int status, InputStream body, Map<String, List<String>> headers) {
        private static FakeResponse redirect(String location) {
            return new FakeResponse(302, new ByteArrayInputStream(new byte[0]), Map.of("Location", List.of(location)));
        }

        private static FakeResponse ok(String body, long contentLength) {
            return ok(new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)), contentLength);
        }

        private static FakeResponse ok(InputStream body, long contentLength) {
            return new FakeResponse(200, body, Map.of("Content-Length", List.of(Long.toString(contentLength))));
        }

        private HttpResponse<InputStream> toHttpResponse(HttpRequest request) {
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<InputStream>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(headers, (name, value) -> true);
                }

                @Override
                public InputStream body() {
                    return body;
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
        }
    }
}
