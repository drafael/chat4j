package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoskModelInstallerTest {

    private static final String MARKER = ".chat4j-vosk-partial";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Downloads require a positive expected compressed size")
    void downloadAndInstall_whenCatalogSizeIsMissing_rejectsBeforeNetworkOrStaging() {
        Path root = tempDir.resolve("vosk");
        Path temp = tempDir.resolve("temp");
        var subject = new VoskModelInstaller(HttpClient.newHttpClient(), new VoskModelValidator(), path -> Long.MAX_VALUE);

        assertThatThrownBy(() -> subject.downloadAndInstall(catalogEntry(0), root, temp, SpeechToTextProviderContext.CancellationToken.never(), status -> {
        }))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("expected download size");
        assertThat(root).doesNotExist();
        assertThat(temp).doesNotExist();
    }

    @Test
    @DisplayName("Custom model imports fail before staging when model root lacks space")
    void importFolder_whenModelRootHasInsufficientSpace_failsBeforeStaging() throws Exception {
        Path source = tempDir.resolve("source-model");
        Path root = tempDir.resolve("vosk");
        createValidModel(source);
        var subject = new VoskModelInstaller(HttpClient.newHttpClient(), new VoskModelValidator(), path -> 0L);

        assertThatThrownBy(() -> subject.importFolder(source, root, "source-model"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("Not enough disk space");
        assertThat(directoryContents(root)).isEmpty();
    }

    @Test
    @DisplayName("Canceled imports remove owned staging directories")
    void importFolder_whenCanceledDuringCopy_removesStagingDirectory() throws Exception {
        Path source = tempDir.resolve("cancel-source");
        Path root = tempDir.resolve("cancel-vosk");
        createValidModel(source);
        var subject = new VoskModelInstaller(HttpClient.newHttpClient(), new VoskModelValidator(), path -> Long.MAX_VALUE);
        AtomicInteger cancellationChecks = new AtomicInteger();
        SpeechToTextProviderContext.CancellationToken cancellationToken = () -> cancellationChecks.incrementAndGet() > 8;

        assertThatThrownBy(() -> subject.importFolder(source, root, "cancel-source", cancellationToken, status -> {
        }))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("canceled");
        assertThat(directoryContents(root)).isEmpty();
    }

    @Test
    @DisplayName("Aborting an active response body unblocks a stalled Vosk model download")
    void abortActiveDownload_whenResponseBodyReadStalls_closesBodyAndCleansStaging() throws Exception {
        var body = new BlockingInputStream();
        var client = new FakeHttpClient(FakeResponse.ok(body));
        var subject = new VoskModelInstaller(client, new VoskModelValidator(), path -> Long.MAX_VALUE);
        Path tempRoot = tempDir.resolve("temp");

        CompletableFuture<Void> download = CompletableFuture.runAsync(() -> {
            try {
                subject.downloadAndInstall(catalogEntry(5), tempDir.resolve("models"), tempRoot, SpeechToTextProviderContext.CancellationToken.never(), status -> {
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(body.awaitReadStarted()).isTrue();

        subject.abortActiveDownload();

        assertThatThrownBy(() -> download.get(2, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(body.closed()).isTrue();
        assertThat(directoryContents(tempRoot)).isEmpty();
    }

    @Test
    @DisplayName("Aborting an active Vosk download cancels the in-flight HTTP future before a body exists")
    void abortActiveDownload_whenHttpResponseHasNotArrived_cancelsFutureAndCleansStaging() throws Exception {
        var pending = new PendingResponseFuture();
        var client = new FakeHttpClient(pending);
        var subject = new VoskModelInstaller(client, new VoskModelValidator(), path -> Long.MAX_VALUE);
        Path tempRoot = tempDir.resolve("temp");

        CompletableFuture<Void> download = CompletableFuture.runAsync(() -> {
            try {
                subject.downloadAndInstall(catalogEntry(5), tempDir.resolve("models"), tempRoot, SpeechToTextProviderContext.CancellationToken.never(), status -> {
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(pending.awaitGetStarted()).isTrue();

        subject.abortActiveDownload();

        assertThatThrownBy(() -> download.get(2, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(pending).isCancelled();
        assertThat(directoryContents(tempRoot)).isEmpty();
    }

    @Test
    @DisplayName("ZIP inspection rejects unsafe archive paths")
    void inspectZip_whenEntryEscapesStaging_rejectsArchive() throws Exception {
        Path zip = tempDir.resolve("unsafe.zip");
        writeZip(zip, new ZipContent("../evil.txt", "evil"));
        var subject = new VoskModelInstaller(new VoskModelValidator());

        assertThatThrownBy(() -> subject.inspectZip(zip, tempDir.resolve("staging")))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("unsafe path");
    }

    @Test
    @DisplayName("ZIP inspection sums uncompressed sizes without extracting")
    void inspectZip_whenEntriesAreSafe_reportsUncompressedSize() throws Exception {
        Path zip = tempDir.resolve("safe.zip");
        writeZip(zip, new ZipContent("model/a.txt", "abc"), new ZipContent("model/b.txt", "de"));
        var subject = new VoskModelInstaller(new VoskModelValidator());

        var inspection = subject.inspectZip(zip, tempDir.resolve("staging"));

        assertThat(inspection.entries()).isEqualTo(2);
        assertThat(inspection.uncompressedBytes()).isEqualTo(5);
    }

    @Test
    @DisplayName("Publish fallback refuses to replace a target created after atomic move fails")
    void publish_whenFallbackTargetAppears_refusesToReplaceExistingTarget() throws Exception {
        Path source = tempDir.resolve("publish-source");
        Path target = tempDir.resolve("publish-target");
        Files.createDirectories(source);
        Files.writeString(source.resolve("source.txt"), "source");
        AtomicInteger moveCalls = new AtomicInteger();
        var subject = new VoskModelInstaller(HttpClient.newHttpClient(), new VoskModelValidator(), path -> Long.MAX_VALUE, (from, to, options) -> {
            if (moveCalls.incrementAndGet() == 1) {
                Files.createDirectories(to);
                Files.writeString(to.resolve("existing.txt"), "keep");
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "simulated");
            }
            return Files.move(from, to, options);
        });

        assertThatThrownBy(() -> subject.publish(source, target))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("already exists");
        assertThat(moveCalls).hasValue(1);
        assertThat(source).exists();
        assertThat(target.resolve("existing.txt")).hasContent("keep");
    }

    @Test
    @DisplayName("Stale cleanup keeps fresh owned temp directories and removes old ones only")
    void cleanupStalePartials_whenTempDownloadIsFresh_keepsFreshAndDeletesOldOwnedDirectory() throws Exception {
        Path tempRoot = tempDir.resolve("temp");
        Path voskRoot = tempDir.resolve("vosk");
        Path fresh = tempRoot.resolve("chat4j-vosk-download-fresh.partial");
        Path old = tempRoot.resolve("chat4j-vosk-download-old.partial");
        Files.createDirectories(fresh);
        Files.createDirectories(old);
        Files.writeString(fresh.resolve(MARKER), "fresh");
        Files.writeString(old.resolve(MARKER), "old");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(48 * 60 * 60)));
        var subject = new VoskModelInstaller(new VoskModelValidator());

        subject.cleanupStalePartials(voskRoot, tempRoot);

        assertThat(fresh).exists();
        assertThat(old).doesNotExist();
    }

    private VoskModelCatalogEntry catalogEntry(long size) {
        return new VoskModelCatalogEntry(
                "vosk-model-small-test-0.1",
                "en",
                "English",
                "small",
                "https://alphacephei.com/vosk/models/vosk-model-small-test-0.1.zip",
                "0123456789abcdef0123456789abcdef",
                size,
                "unknown",
                false,
                "0.1"
        );
    }

    private void createValidModel(Path model) throws Exception {
        Files.createDirectories(model.resolve("am"));
        Files.createDirectories(model.resolve("conf"));
        Files.createDirectories(model.resolve("graph"));
        Files.writeString(model.resolve("am").resolve("final.mdl"), "model");
        Files.writeString(model.resolve("conf").resolve("model.conf"), "conf");
        Files.writeString(model.resolve("conf").resolve("mfcc.conf"), "mfcc");
        Files.writeString(model.resolve("graph").resolve("HCLG.fst"), "graph");
    }

    private List<Path> directoryContents(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return emptyList();
        }
        try (var stream = Files.list(directory)) {
            return stream.toList();
        }
    }

    private void writeZip(Path zip, ZipContent... contents) throws Exception {
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (ZipContent content : contents) {
                output.putNextEntry(new ZipEntry(content.name()));
                output.write(content.text().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static final class PendingResponseFuture extends CompletableFuture<HttpResponse<InputStream>> {
        private final CountDownLatch getStarted = new CountDownLatch(1);

        @Override
        public HttpResponse<InputStream> get() throws java.lang.InterruptedException, java.util.concurrent.ExecutionException {
            getStarted.countDown();
            return super.get();
        }

        private boolean awaitGetStarted() throws InterruptedException {
            return getStarted.await(5, TimeUnit.SECONDS);
        }
    }

    private static final class FakeHttpClient extends HttpClient {
        private final ArrayDeque<FakeResponse> responses = new ArrayDeque<>();
        private final CompletableFuture<HttpResponse<InputStream>> pending;

        private FakeHttpClient(FakeResponse... responses) {
            this.responses.addAll(List.of(responses));
            this.pending = null;
        }

        private FakeHttpClient(CompletableFuture<HttpResponse<InputStream>> pending) {
            this.pending = pending;
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
            throw new UnsupportedOperationException("Vosk installer should use cancellable sendAsync");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
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
        private static FakeResponse ok(InputStream body) {
            return new FakeResponse(200, body, Map.of());
        }

        private <T> HttpResponse<T> toHttpResponse(HttpRequest request) {
            @SuppressWarnings("unchecked")
            T typedBody = (T) body;
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
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(headers, (left, right) -> true);
                }

                @Override
                public T body() {
                    return typedBody;
                }

                @Override
                public Optional<javax.net.ssl.SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public java.net.URI uri() {
                    return request.uri();
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
        }
    }

    private record ZipContent(String name, String text) {
    }
}
