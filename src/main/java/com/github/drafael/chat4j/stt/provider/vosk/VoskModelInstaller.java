package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.lang3.StringUtils;

public class VoskModelInstaller {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ZIP_ENTRIES = 100_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofHours(2);
    private static final Duration STALE_PARTIAL_AGE = Duration.ofHours(24);
    private static final String MARKER = ".chat4j-vosk-partial";

    private final HttpClient httpClient;
    private final VoskModelValidator validator;
    private final DiskSpaceChecker diskSpaceChecker;
    private final FileMover fileMover;
    private volatile CompletableFuture<? extends HttpResponse<InputStream>> activeDownloadRequest;
    private volatile InputStream activeDownloadStream;

    public VoskModelInstaller(VoskModelValidator validator) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build(), validator);
    }

    VoskModelInstaller(HttpClient httpClient, VoskModelValidator validator) {
        this(httpClient, validator, path -> Files.getFileStore(path).getUsableSpace());
    }

    VoskModelInstaller(HttpClient httpClient, VoskModelValidator validator, DiskSpaceChecker diskSpaceChecker) {
        this(httpClient, validator, diskSpaceChecker, Files::move);
    }

    VoskModelInstaller(HttpClient httpClient, VoskModelValidator validator, DiskSpaceChecker diskSpaceChecker, FileMover fileMover) {
        this.httpClient = httpClient;
        this.validator = validator;
        this.diskSpaceChecker = diskSpaceChecker;
        this.fileMover = fileMover;
    }

    public void downloadAndInstall(
            VoskModelCatalogEntry entry,
            Path voskRoot,
            Path tempRoot,
            SpeechToTextProviderContext.CancellationToken cancellationToken,
            Consumer<String> progress
    ) throws Exception {
        validateDownloadEntry(entry);
        Files.createDirectories(voskRoot);
        Files.createDirectories(tempRoot);
        Path finalDirectory = validator.safeChild(voskRoot, entry.name());
        if (Files.exists(finalDirectory)) {
            VoskModelValidator.ValidationResult validation = validator.validate(finalDirectory, voskRoot);
            if (validation.status() == VoskValidationStatus.VALID) {
                progress.accept("Model is already installed.");
                return;
            }
            throw new SpeechToTextException("A folder already exists for this model. Delete or fix it before reinstalling.");
        }
        ensureUsableSpace(tempRoot, entry.size(), "temporary download folder");
        String operationId = UUID.randomUUID().toString();
        Path operationTemp = tempRoot.resolve("chat4j-vosk-download-%s.partial".formatted(operationId));
        Path zipFile = operationTemp.resolve("model.zip");
        Path staging = voskRoot.resolve("chat4j-vosk-install-%s.partial".formatted(operationId));
        try {
            createOwnedPartial(operationTemp, entry.name());
            download(entry, zipFile, cancellationToken, progress);
            verifyMd5(entry, zipFile);
            createOwnedPartial(staging, entry.name());
            ZipInspection inspection = inspectZip(zipFile, staging);
            ensureUsableSpace(voskRoot, inspection.uncompressedBytes(), "Vosk model folder");
            extract(zipFile, staging, cancellationToken, progress);
            Path normalizedRoot = normalizeExtractedRoot(entry, staging);
            VoskModelValidator.ValidationResult validation = validator.validate(normalizedRoot, staging);
            if (validation.status() != VoskValidationStatus.VALID && validation.status() != VoskValidationStatus.PLAUSIBLE_UNVERIFIED) {
                throw new SpeechToTextException("Downloaded model did not contain a valid Vosk layout: %s".formatted(validation.message()));
            }
            boolean stagingIsModelRoot = normalizedRoot.equals(staging);
            publish(normalizedRoot, finalDirectory);
            if (stagingIsModelRoot) {
                Files.deleteIfExists(finalDirectory.resolve(MARKER));
            }
            progress.accept("Installed %s".formatted(entry.name()));
        } finally {
            deleteTreeIfOwned(operationTemp);
            deleteTreeIfOwned(staging);
        }
    }

    public void importFolder(Path source, Path voskRoot, String requestedName) throws Exception {
        importFolder(source, voskRoot, requestedName, SpeechToTextProviderContext.CancellationToken.never(), status -> {
        });
    }

    public void importFolder(
            Path source,
            Path voskRoot,
            String requestedName,
            SpeechToTextProviderContext.CancellationToken cancellationToken,
            Consumer<String> progress
    ) throws Exception {
        if (source == null) {
            throw new SpeechToTextException("Select a Vosk model folder to import.");
        }
        Files.createDirectories(voskRoot);
        Path realSource = source.toRealPath();
        Path finalDirectory = validator.safeChild(voskRoot, StringUtils.defaultIfBlank(requestedName, source.getFileName().toString()));
        if (realSource.equals(finalDirectory.toAbsolutePath().normalize())) {
            throw new SpeechToTextException("This model is already managed by Chat4J.");
        }
        if (finalDirectory.toAbsolutePath().normalize().startsWith(realSource)) {
            throw new SpeechToTextException("Cannot import a model into itself.");
        }
        if (Files.exists(finalDirectory)) {
            throw new SpeechToTextException("A model folder with that name already exists.");
        }
        VoskModelValidator.ValidationResult validation = validator.validate(source, source);
        if (validation.status() == VoskValidationStatus.INVALID || validation.status() == VoskValidationStatus.MISSING || validation.status() == VoskValidationStatus.UNSAFE) {
            throw new SpeechToTextException("Selected folder is not a valid Vosk model: %s".formatted(validation.message()));
        }
        long sourceBytes = sourceTreeBytes(source, cancellationToken);
        ensureUsableSpace(voskRoot, sourceBytes, "Vosk model folder");
        String operationId = UUID.randomUUID().toString();
        Path staging = voskRoot.resolve("chat4j-vosk-import-%s.partial".formatted(operationId));
        try {
            createOwnedPartial(staging, requestedName);
            copyTree(source, staging, cancellationToken, progress);
            VoskModelValidator.ValidationResult copiedValidation = validator.validate(staging, voskRoot);
            if (copiedValidation.status() == VoskValidationStatus.INVALID
                    || copiedValidation.status() == VoskValidationStatus.MISSING
                    || copiedValidation.status() == VoskValidationStatus.UNSAFE) {
                throw new SpeechToTextException("Imported model did not contain a valid Vosk layout: %s".formatted(copiedValidation.message()));
            }
            publish(staging, finalDirectory);
            Files.deleteIfExists(finalDirectory.resolve(MARKER));
            progress.accept("Imported %s".formatted(finalDirectory.getFileName()));
        } finally {
            deleteTreeIfOwned(staging);
        }
    }

    public void deleteInstalled(VoskInstalledModel model, Path voskRoot) throws Exception {
        if (model == null || !model.deleteable()) {
            throw new SpeechToTextException("This model cannot be deleted by Chat4J.");
        }
        Path directory = model.directory().toAbsolutePath().normalize();
        if (!directory.startsWith(voskRoot.toAbsolutePath().normalize())) {
            throw new SpeechToTextException("This model is outside the managed Vosk directory.");
        }
        deleteTree(directory);
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

    public void cleanupStalePartials(Path voskRoot, Path tempRoot) {
        cleanupRoot(voskRoot, "chat4j-vosk-install-");
        cleanupRoot(voskRoot, "chat4j-vosk-import-");
        cleanupRoot(tempRoot, "chat4j-vosk-download-");
    }

    private void validateDownloadEntry(VoskModelCatalogEntry entry) throws SpeechToTextException {
        if (entry == null || !entry.speechRecognition()) {
            throw new SpeechToTextException("Only official Vosk speech-recognition models can be downloaded.");
        }
        if (!validator.safeModelName(entry.name())) {
            throw new SpeechToTextException("Vosk catalog entry has an unsafe model name.");
        }
        validateModelDownloadUri(entry, URI.create(entry.url()));
        if (StringUtils.isBlank(entry.md5())) {
            throw new SpeechToTextException("Vosk catalog entry is missing an MD5 checksum.");
        }
        if (entry.size() <= 0) {
            throw new SpeechToTextException("Vosk catalog entry is missing an expected download size.");
        }
    }

    private void validateModelDownloadUri(VoskModelCatalogEntry entry, URI uri) throws SpeechToTextException {
        validateModelUri(uri);
        String expectedFile = "%s.zip".formatted(entry.name());
        String path = uri.getPath();
        if (!path.endsWith("/" + expectedFile)) {
            throw new SpeechToTextException("Vosk model URL does not match its catalog name.");
        }
    }

    private void validateModelUri(URI uri) throws SpeechToTextException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"alphacephei.com".equalsIgnoreCase(uri.getHost())) {
            throw new SpeechToTextException("Vosk model URL is not trusted.");
        }
        if (uri.getPath() == null || !uri.getPath().startsWith("/vosk/models/") || !uri.getPath().endsWith(".zip")) {
            throw new SpeechToTextException("Vosk model URL path is not trusted.");
        }
    }

    private void download(VoskModelCatalogEntry entry, Path zipFile, SpeechToTextProviderContext.CancellationToken cancellationToken, Consumer<String> progress) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(entry.url()))
                .GET()
                .timeout(DOWNLOAD_TIMEOUT)
                .build();
        HttpResponse<InputStream> response = sendCancellable(request);
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            URI redirected = response.headers().firstValue("location")
                    .map(URI.create(entry.url())::resolve)
                    .orElse(null);
            closeResponseBody(response);
            if (redirected == null) {
                throw new SpeechToTextException("Vosk model redirect was missing a destination.");
            }
            validateModelDownloadUri(entry, redirected);
            response = sendCancellable(HttpRequest.newBuilder(redirected).GET().timeout(DOWNLOAD_TIMEOUT).build());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeResponseBody(response);
            throw new SpeechToTextException("Vosk model download failed: HTTP %d".formatted(response.statusCode()));
        }
        InputStream body = response.body();
        activeDownloadStream = body;
        try (InputStream input = body; OutputStream output = Files.newOutputStream(zipFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while (true) {
                if (cancellationToken != null && cancellationToken.cancelled()) {
                    throw new SpeechToTextException("Vosk model download canceled.");
                }
                read = input.read(buffer);
                if (read == -1) {
                    break;
                }
                if (cancellationToken != null && cancellationToken.cancelled()) {
                    throw new SpeechToTextException("Vosk model download canceled.");
                }
                total += read;
                if (entry.size() > 0 && total > entry.size()) {
                    throw new SpeechToTextException("Vosk model download exceeded the expected size.");
                }
                output.write(buffer, 0, read);
                if (entry.size() > 0) {
                    progress.accept("Downloading %s%%".formatted(Math.min(100, total * 100 / entry.size())));
                }
            }
            if (entry.size() > 0 && total != entry.size()) {
                throw new SpeechToTextException("Vosk model download size did not match the catalog.");
            }
        } finally {
            if (activeDownloadStream == body) {
                activeDownloadStream = null;
            }
        }
    }

    private HttpResponse<InputStream> sendCancellable(HttpRequest request) throws Exception {
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        activeDownloadRequest = future;
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new SpeechToTextException("Vosk model download canceled.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new SpeechToTextException("Vosk model download failed.", cause);
        } finally {
            if (activeDownloadRequest == future) {
                activeDownloadRequest = null;
            }
        }
    }

    private void closeResponseBody(HttpResponse<InputStream> response) {
        try {
            if (response.body() != null) {
                response.body().close();
            }
        } catch (Exception ignored) {
        }
    }

    private void verifyMd5(VoskModelCatalogEntry entry, Path zipFile) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream input = Files.newInputStream(zipFile); DigestInputStream digestInput = new DigestInputStream(input, digest)) {
            digestInput.transferTo(OutputStreamNull.INSTANCE);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(entry.md5())) {
            throw new SpeechToTextException("Vosk model checksum did not match the catalog.");
        }
    }

    ZipInspection inspectZip(Path zipFile, Path staging) throws Exception {
        int entries = 0;
        long uncompressed = 0;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> zipEntries = zip.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new SpeechToTextException("Vosk model ZIP has too many files.");
                }
                safeZipTarget(staging, entry);
                if (!entry.isDirectory()) {
                    long size = entry.getSize();
                    if (size < 0) {
                        throw new SpeechToTextException("Vosk model ZIP contains a file with unknown size.");
                    }
                    uncompressed = Math.addExact(uncompressed, size);
                    if (uncompressed > MAX_UNCOMPRESSED_BYTES) {
                        throw new SpeechToTextException("Vosk model ZIP is too large after extraction.");
                    }
                }
            }
        } catch (ArithmeticException e) {
            throw new SpeechToTextException("Vosk model ZIP is too large after extraction.", e);
        }
        return new ZipInspection(entries, uncompressed);
    }

    private void extract(Path zipFile, Path staging, SpeechToTextProviderContext.CancellationToken cancellationToken, Consumer<String> progress) throws Exception {
        int entries = 0;
        long uncompressed = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                if (cancellationToken != null && cancellationToken.cancelled()) {
                    throw new SpeechToTextException("Vosk model extraction canceled.");
                }
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new SpeechToTextException("Vosk model ZIP has too many files.");
                }
                Path target = safeZipTarget(staging, entry);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        uncompressed = Math.addExact(uncompressed, read);
                        if (uncompressed > MAX_UNCOMPRESSED_BYTES) {
                            throw new SpeechToTextException("Vosk model ZIP is too large after extraction.");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                progress.accept("Extracted %d files".formatted(entries));
            }
        } catch (ArithmeticException e) {
            throw new SpeechToTextException("Vosk model ZIP is too large after extraction.", e);
        }
    }

    private Path safeZipTarget(Path staging, ZipEntry entry) throws SpeechToTextException {
        Path normalizedStaging = staging.toAbsolutePath().normalize();
        Path target = normalizedStaging.resolve(entry.getName()).normalize();
        if (!target.startsWith(normalizedStaging)
                || StringUtils.isBlank(entry.getName())
                || entry.getName().contains("..")
                || entry.getName().contains("\\")
                || Path.of(entry.getName()).isAbsolute()) {
            throw new SpeechToTextException("Vosk model ZIP contains an unsafe path.");
        }
        return target;
    }

    private Path normalizeExtractedRoot(VoskModelCatalogEntry entry, Path staging) throws Exception {
        List<Path> children;
        try (Stream<Path> stream = Files.list(staging)) {
            children = stream.filter(path -> !MARKER.equals(path.getFileName().toString())).toList();
        }
        if (children.size() == 1 && Files.isDirectory(children.getFirst()) && entry.name().equals(children.getFirst().getFileName().toString())) {
            return children.getFirst();
        }
        return staging;
    }

    void publish(Path normalizedRoot, Path finalDirectory) throws Exception {
        ensurePublishTargetAbsent(finalDirectory);
        try {
            fileMover.move(normalizedRoot, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            ensurePublishTargetAbsent(finalDirectory);
            fileMover.move(normalizedRoot, finalDirectory);
        }
    }

    private void ensurePublishTargetAbsent(Path finalDirectory) throws SpeechToTextException {
        if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new SpeechToTextException("A model folder with that name already exists.");
        }
    }

    private void copyTree(Path source, Path target, SpeechToTextProviderContext.CancellationToken cancellationToken, Consumer<String> progress) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                if (cancellationToken != null && cancellationToken.cancelled()) {
                    throw new SpeechToTextException("Vosk model import canceled.");
                }
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target.normalize())) {
                    throw new SpeechToTextException("Model import contains an unsafe path.");
                }
                if (Files.isSymbolicLink(path)) {
                    throw new SpeechToTextException("Model imports cannot contain symbolic links.");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    progress.accept("Imported %s".formatted(relative));
                } else {
                    throw new SpeechToTextException("Model imports can contain only regular files and folders.");
                }
            }
        }
    }

    private long sourceTreeBytes(Path source, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            long total = 0;
            for (Path path : stream.sorted().toList()) {
                if (cancellationToken != null && cancellationToken.cancelled()) {
                    throw new SpeechToTextException("Vosk model import canceled.");
                }
                if (Files.isSymbolicLink(path)) {
                    throw new SpeechToTextException("Model imports cannot contain symbolic links.");
                }
                if (Files.isRegularFile(path)) {
                    total = Math.addExact(total, Files.size(path));
                } else if (!Files.isDirectory(path)) {
                    throw new SpeechToTextException("Model imports can contain only regular files and folders.");
                }
            }
            return total;
        } catch (ArithmeticException e) {
            throw new SpeechToTextException("Vosk model import is too large.", e);
        }
    }

    private void ensureUsableSpace(Path directory, long requiredBytes, String label) throws Exception {
        if (requiredBytes <= 0) {
            return;
        }
        long usable = diskSpaceChecker.usableSpace(directory);
        if (usable < requiredBytes) {
            throw new SpeechToTextException("Not enough disk space in the %s.".formatted(label));
        }
    }

    private void createOwnedPartial(Path path, String markerText) throws Exception {
        Files.createDirectories(path);
        Files.writeString(path.resolve(MARKER), StringUtils.defaultString(markerText), StandardOpenOption.CREATE_NEW);
    }

    private void cleanupRoot(Path root, String prefix) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(path -> Files.isDirectory(path) && path.getFileName().toString().startsWith(prefix))
                    .filter(this::ownedPartial)
                    .filter(this::stalePartial)
                    .forEach(this::deleteIfExists);
        } catch (Exception ignored) {
        }
    }

    private boolean ownedPartial(Path path) {
        return Files.exists(path.resolve(MARKER));
    }

    private boolean stalePartial(Path path) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(path);
            return lastModified.toInstant().isBefore(Instant.now().minus(STALE_PARTIAL_AGE));
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteIfExists(Path path) {
        try {
            if (Files.isDirectory(path)) {
                deleteTree(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
        }
    }

    private void deleteTreeIfOwned(Path path) {
        try {
            if (Files.exists(path.resolve(MARKER))) {
                deleteTree(path);
            }
        } catch (Exception ignored) {
        }
    }

    private void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(child);
            }
        }
    }

    @FunctionalInterface
    interface DiskSpaceChecker {
        long usableSpace(Path path) throws Exception;
    }

    @FunctionalInterface
    interface FileMover {
        Path move(Path source, Path target, CopyOption... options) throws Exception;
    }

    record ZipInspection(int entries, long uncompressedBytes) {
    }

    private static final class OutputStreamNull extends OutputStream {
        private static final OutputStreamNull INSTANCE = new OutputStreamNull();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
