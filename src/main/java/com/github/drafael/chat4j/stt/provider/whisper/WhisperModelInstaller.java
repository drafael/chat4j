package com.github.drafael.chat4j.stt.provider.whisper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class WhisperModelInstaller {

    public static final String DOWNLOAD_PARTIAL_PREFIX = "chat4j-whisper-download-";
    public static final String INSTALL_PARTIAL_PREFIX = "chat4j-whisper-install-";
    public static final String PARTIAL_SUFFIX = ".partial";
    public static final String DOWNLOAD_MARKER = ".chat4j-whisper-download-partial";
    public static final String INSTALL_MARKER = ".chat4j-whisper-install-partial";

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration CLEANUP_AGE = Duration.ofHours(12);

    private final HttpClient httpClient;
    private volatile CompletableFuture<? extends HttpResponse<InputStream>> activeDownloadRequest;
    private volatile InputStream activeDownloadStream;

    public WhisperModelInstaller() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    WhisperModelInstaller(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void downloadAndInstall(
            WhisperModelCatalogEntry entry,
            Path root,
            Path tempRoot,
            BooleanSupplier cancelled,
            ProgressListener progressListener
    ) throws Exception {
        validateEntry(entry);
        validateInitialUri(entry);
        Files.createDirectories(root);
        Files.createDirectories(tempRoot);
        checkDiskSpace(entry, root, tempRoot);
        Path downloadStaging = createMarkedDirectory(tempRoot, DOWNLOAD_PARTIAL_PREFIX, DOWNLOAD_MARKER);
        Path installStaging = null;
        try {
            Path downloaded = download(entry, downloadStaging, cancelled, progressListener);
            checkCanceled(cancelled);
            String sha1 = WhisperInstalledModelScanner.sha1(downloaded);
            if (!Objects.equals(entry.sha1(), sha1)) {
                throw new IllegalStateException("Whisper.cpp model checksum does not match the catalog.");
            }
            installStaging = createMarkedDirectory(root, INSTALL_PARTIAL_PREFIX, INSTALL_MARKER);
            Path modelFile = installStaging.resolve(WhisperInstalledModelScanner.MODEL_FILE_NAME);
            Files.move(downloaded, modelFile, StandardCopyOption.REPLACE_EXISTING);
            writeMetadata(installStaging.resolve(WhisperInstalledModelScanner.METADATA_FILE_NAME), entry);
            verifyStagedInstall(installStaging, root, entry);
            publish(entry, root, installStaging);
            installStaging = null;
        } finally {
            deleteMarkedPartial(downloadStaging, DOWNLOAD_MARKER);
            if (installStaging != null) {
                deleteMarkedPartial(installStaging, INSTALL_MARKER);
            }
        }
    }

    public void deleteInstalled(WhisperInstalledModel model, Path root) throws Exception {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path directory = model.directory().toAbsolutePath().normalize();
        if (!directory.startsWith(normalizedRoot) || Files.isSymbolicLink(directory)) {
            throw new IllegalStateException("Whisper.cpp model directory is unsafe.");
        }
        deleteTree(directory);
    }

    public void cleanupStalePartials(Path root, Path tempRoot) {
        cleanupStalePartialsIn(root, INSTALL_PARTIAL_PREFIX, INSTALL_MARKER);
        cleanupStalePartialsIn(tempRoot, DOWNLOAD_PARTIAL_PREFIX, DOWNLOAD_MARKER);
    }

    private Path download(WhisperModelCatalogEntry entry, Path staging, BooleanSupplier cancelled, ProgressListener progressListener) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkCanceled(cancelled);
            Path target = staging.resolve(entry.expectedFileName());
            Files.deleteIfExists(target);
            try {
                HttpResponse<InputStream> response = openDownloadResponse(entry, cancelled);
                stream(response, target, entry, cancelled, progressListener);
                return target;
            } catch (Exception e) {
                last = e;
                Files.deleteIfExists(target);
                if (attempt == MAX_ATTEMPTS || !transientFailure(e)) {
                    throw e;
                }
                progressListener.progress("Retrying download for %s...".formatted(entry.label()), 0, entry.sizeBytes(), false);
            }
        }
        throw last == null ? new IllegalStateException("Could not download Whisper.cpp model.") : last;
    }

    public void abortActiveDownload() {
        CompletableFuture<? extends HttpResponse<InputStream>> request = activeDownloadRequest;
        if (request != null) {
            request.cancel(true);
        }
        InputStream input = activeDownloadStream;
        if (input != null) {
            try {
                input.close();
            } catch (Exception ignored) {
            }
        }
    }

    private HttpResponse<InputStream> openDownloadResponse(WhisperModelCatalogEntry entry, BooleanSupplier cancelled) throws Exception {
        URI current = entry.downloadUri();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkCanceled(cancelled);
            HttpRequest request = HttpRequest.newBuilder(current).timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<InputStream> response = sendCancellable(request);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException("Whisper.cpp download redirect was missing a Location header."));
                current = current.resolve(location);
                validateRedirectUri(current, entry);
                continue;
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new DownloadHttpException(status);
            }
            try {
                validateRedirectUri(current, entry);
            } catch (Exception e) {
                closeQuietly(response.body());
                throw e;
            }
            return response;
        }
        throw new IllegalStateException("Whisper.cpp download redirected too many times.");
    }

    private HttpResponse<InputStream> sendCancellable(HttpRequest request) throws Exception {
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        activeDownloadRequest = future;
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("Whisper.cpp model operation canceled.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Whisper.cpp model download failed.", cause);
        } finally {
            if (activeDownloadRequest == future) {
                activeDownloadRequest = null;
            }
        }
    }

    private void stream(HttpResponse<InputStream> response, Path target, WhisperModelCatalogEntry entry, BooleanSupplier cancelled, ProgressListener progressListener) throws Exception {
        InputStream body = response.body();
        activeDownloadStream = body;
        try (InputStream input = body) {
            checkCanceled(cancelled);
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > entry.sizeBytes()) {
                throw new IllegalStateException("Whisper.cpp download was larger than expected.");
            }
            if (contentLength > 0 && contentLength != entry.sizeBytes()) {
                throw new IllegalStateException("Whisper.cpp download size did not match the catalog.");
            }
            try (OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[1024 * 1024];
                long total = 0;
                int read;
                while (true) {
                    checkCanceled(cancelled);
                    read = input.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    checkCanceled(cancelled);
                    total += read;
                    if (total > entry.sizeBytes()) {
                        throw new IllegalStateException("Whisper.cpp download was larger than expected.");
                    }
                    output.write(buffer, 0, read);
                    progressListener.progress("Downloading %s...".formatted(entry.label()), total, entry.sizeBytes(), true);
                }
                if (total != entry.sizeBytes()) {
                    throw new IllegalStateException("Whisper.cpp download size did not match the catalog.");
                }
            }
        } finally {
            if (activeDownloadStream == body) {
                activeDownloadStream = null;
            }
        }
    }

    private void publish(WhisperModelCatalogEntry entry, Path root, Path installStaging) throws Exception {
        Path finalDirectory = root.resolve(WhisperInstalledModelScanner.slug(entry.id())).normalize();
        if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("A Whisper.cpp model with this id is already installed. Delete it before reinstalling.");
        }
        try {
            Files.move(installStaging, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(installStaging, finalDirectory);
        }
        Files.deleteIfExists(finalDirectory.resolve(INSTALL_MARKER));
    }

    private void verifyStagedInstall(Path installStaging, Path root, WhisperModelCatalogEntry entry) throws Exception {
        WhisperInstalledModelScanner scanner = new WhisperInstalledModelScanner();
        WhisperInstalledModelScanner.ValidationResult validation = scanner.validateInstall(installStaging, root, entry);
        if (validation.status() != WhisperValidationStatus.VALID) {
            throw new IllegalStateException(validation.message());
        }
    }

    private void writeMetadata(Path metadata, WhisperModelCatalogEntry entry) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("id", entry.id());
        properties.setProperty("label", entry.label());
        properties.setProperty("sourceUrl", entry.downloadUri().toString());
        properties.setProperty("expectedFileName", entry.expectedFileName());
        properties.setProperty("sha1", entry.sha1());
        properties.setProperty("sizeBytes", Long.toString(entry.sizeBytes()));
        properties.setProperty("sizeLabel", entry.sizeLabel());
        properties.setProperty("metadataVersion", WhisperInstalledModelScanner.METADATA_VERSION);
        properties.setProperty("installedAt", Instant.now().toString());
        try (OutputStream output = Files.newOutputStream(metadata)) {
            properties.store(output, "Chat4J Whisper.cpp model metadata");
        }
    }

    private Path createMarkedDirectory(Path parent, String prefix, String marker) throws Exception {
        Files.createDirectories(parent);
        Path directory = parent.resolve(prefix + UUID.randomUUID() + PARTIAL_SUFFIX);
        Files.createDirectory(directory);
        Files.writeString(directory.resolve(marker), "Chat4J Whisper.cpp partial directory\n");
        return directory;
    }

    private void checkDiskSpace(WhisperModelCatalogEntry entry, Path root, Path tempRoot) throws Exception {
        Files.createDirectories(root);
        Files.createDirectories(tempRoot);
        long required = entry.sizeBytes() * 3 + 1024 * 1024;
        FileStore rootStore = Files.getFileStore(root);
        FileStore tempStore = Files.getFileStore(tempRoot);
        if (Objects.equals(rootStore, tempStore)) {
            if (rootStore.getUsableSpace() < required) {
                throw new IllegalStateException("Not enough disk space to download and install this Whisper.cpp model.");
            }
            return;
        }
        if (tempStore.getUsableSpace() < entry.sizeBytes() + 1024 * 1024 || rootStore.getUsableSpace() < entry.sizeBytes() * 2 + 1024 * 1024) {
            throw new IllegalStateException("Not enough disk space to download and install this Whisper.cpp model.");
        }
    }

    private void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Exception ignored) {
        }
    }

    private void validateEntry(WhisperModelCatalogEntry entry) {
        if (entry.sizeBytes() <= 0 || StringUtils.isBlank(entry.sha1()) || StringUtils.isBlank(entry.expectedFileName())) {
            throw new IllegalArgumentException("Whisper.cpp model catalog entry is incomplete.");
        }
    }

    private void validateInitialUri(WhisperModelCatalogEntry entry) {
        URI uri = entry.downloadUri();
        validateHttpsUri(uri);
        String host = canonicalHost(uri);
        if (!Objects.equals("huggingface.co", host)) {
            throw new IllegalArgumentException("Whisper.cpp model URL host is not trusted.");
        }
        String expectedPath = entry.tinydiarize()
                ? "/akashmjn/tinydiarize-whisper.cpp/resolve/main/%s".formatted(entry.expectedFileName())
                : "/ggerganov/whisper.cpp/resolve/main/%s".formatted(entry.expectedFileName());
        if (!Objects.equals(expectedPath, uri.getRawPath())) {
            throw new IllegalArgumentException("Whisper.cpp model URL path is not trusted.");
        }
    }

    private void validateRedirectUri(URI uri, WhisperModelCatalogEntry entry) {
        validateHttpsUri(uri);
        String host = canonicalHost(uri);
        boolean allowed = Objects.equals("huggingface.co", host)
                || Objects.equals("cdn-lfs.huggingface.co", host)
                || Objects.equals("cas-bridge.xethub.hf.co", host)
                || Objects.equals("transfer.xethub.hf.co", host)
                || dotSuffix(host, ".hf.co")
                || dotSuffix(host, ".xethub.hf.co");
        if (!allowed) {
            throw new IllegalStateException("Whisper.cpp download redirected to an untrusted host.");
        }
        String path = StringUtils.defaultString(uri.getRawPath());
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("%2f") || lowerPath.contains("%5c") || lowerPath.contains("..") || lowerPath.contains("%2e")) {
            throw new IllegalStateException("Whisper.cpp download redirected to an unsafe path.");
        }
        if (path.endsWith(".bin") && !path.endsWith("/" + entry.expectedFileName())) {
            throw new IllegalStateException("Whisper.cpp download redirected to an unexpected file.");
        }
    }

    private void validateHttpsUri(URI uri) {
        if (!Objects.equals("https", StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT))
                || StringUtils.isNotBlank(uri.getUserInfo())
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalStateException("Whisper.cpp download URL is not trusted.");
        }
    }

    private String canonicalHost(URI uri) {
        String host = StringUtils.defaultString(uri.getHost());
        if (host.endsWith(".")) {
            throw new IllegalStateException("Whisper.cpp download URL host is not trusted.");
        }
        return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private boolean dotSuffix(String host, String suffix) {
        return host.endsWith(suffix) && host.length() > suffix.length();
    }

    private boolean transientFailure(Exception e) {
        return e instanceof DownloadHttpException http && (http.statusCode() == 408 || http.statusCode() == 429 || http.statusCode() >= 500);
    }

    private void cleanupStalePartialsIn(Path parent, String prefix, String marker) {
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.list(parent)) {
            stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().startsWith(prefix) && path.getFileName().toString().endsWith(PARTIAL_SUFFIX))
                    .filter(path -> Files.isRegularFile(path.resolve(marker), LinkOption.NOFOLLOW_LINKS))
                    .filter(this::stale)
                    .forEach(path -> {
                        try {
                            deleteMarkedPartial(path, marker);
                        } catch (Exception e) {
                            log.debug("Could not clean stale Whisper partial: {}", StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private boolean stale(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(Instant.now().minus(CLEANUP_AGE));
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteMarkedPartial(Path directory, String marker) throws Exception {
        if (directory != null && Files.isRegularFile(directory.resolve(marker), LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(directory);
        }
    }

    private void deleteTree(Path directory) throws Exception {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private void checkCanceled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new IllegalStateException("Whisper.cpp model operation canceled.");
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void progress(String status, long bytesDownloaded, long totalBytes, boolean cancelable);
    }

    private static class DownloadHttpException extends Exception {
        private final int statusCode;

        DownloadHttpException(int statusCode) {
            super("Whisper.cpp model download failed with HTTP status %d.".formatted(statusCode));
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
