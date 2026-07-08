package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.lang3.StringUtils;

public class Sphinx4ModelInstaller {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final int MAX_NESTING_DEPTH = 64;
    private static final int MAX_REDIRECTS = 3;
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_UNCOMPRESSED_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofHours(2);
    private static final Duration STALE_PARTIAL_AGE = Duration.ofHours(24);
    private static final String MARKER = ".chat4j-sphinx4-partial";

    private final HttpClient httpClient;
    private final Sphinx4ModelValidator validator;
    private final DiskSpaceChecker diskSpaceChecker;
    private final FileMover fileMover;

    public Sphinx4ModelInstaller(Sphinx4ModelValidator validator) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build(), validator);
    }

    Sphinx4ModelInstaller(HttpClient httpClient, Sphinx4ModelValidator validator) {
        this(httpClient, validator, path -> Files.getFileStore(path).getUsableSpace());
    }

    Sphinx4ModelInstaller(HttpClient httpClient, Sphinx4ModelValidator validator, DiskSpaceChecker diskSpaceChecker) {
        this(httpClient, validator, diskSpaceChecker, Files::move);
    }

    Sphinx4ModelInstaller(HttpClient httpClient, Sphinx4ModelValidator validator, DiskSpaceChecker diskSpaceChecker, FileMover fileMover) {
        this.httpClient = httpClient;
        this.validator = validator;
        this.diskSpaceChecker = diskSpaceChecker;
        this.fileMover = fileMover;
    }

    public void downloadAndInstall(
            Sphinx4ModelCatalogEntry entry,
            Path sphinx4Root,
            Path tempRoot,
            SpeechToTextProviderContext.CancellationToken cancellationToken,
            Consumer<String> progress
    ) throws Exception {
        validateDownloadEntry(entry);
        Files.createDirectories(sphinx4Root);
        Files.createDirectories(tempRoot);
        Path finalDirectory = validator.safeChild(sphinx4Root, entry.id());
        if (Files.exists(finalDirectory)) {
            throw new SpeechToTextException("A folder already exists for this Sphinx4 model. Delete or fix it before reinstalling.");
        }
        ensureUsableSpace(tempRoot, Math.max(1, entry.sizeBytes()), "temporary download folder");
        String operationId = UUID.randomUUID().toString();
        Path operationTemp = tempRoot.resolve("chat4j-sphinx4-download-%s.partial".formatted(operationId));
        Path staging = sphinx4Root.resolve("chat4j-sphinx4-install-%s.partial".formatted(operationId));
        Set<Path> writtenPaths = new HashSet<>();
        try {
            createOwnedPartial(operationTemp, entry.id());
            createOwnedPartial(staging, entry.id());
            List<Sphinx4ModelArtifact> artifacts = downloadArtifacts(entry, progress);
            for (Sphinx4ModelArtifact artifact : artifacts) {
                Path downloaded = operationTemp.resolve("%s.bin".formatted(artifact.artifactId()));
                download(artifact, downloaded, cancellationToken, progress);
                verifySha256(artifact, downloaded);
                assembleArtifact(artifact, downloaded, staging, writtenPaths, cancellationToken, progress);
            }
            rejectExtractedSymlinks(staging);
            String installStatus = "Installed %s".formatted(entry.label());
            try {
                Sphinx4ModelMetadata metadata = downloadMetadata(entry, artifacts, staging);
                generateRecipeFiles(entry, staging, metadata, cancellationToken);
                Sphinx4ModelValidator.ValidationResult validation = validator.validateRecipe(staging, staging, metadata, true);
                if (entry.hasVerifiedDownload() && validation.status() != Sphinx4ValidationStatus.VALID) {
                    throw new SpeechToTextException("Downloaded Sphinx4 model did not validate: %s".formatted(validation.message()));
                }
                if (validation.status() == Sphinx4ValidationStatus.VALID) {
                    pruneDownloadedStagingToRecipe(staging, metadata);
                } else {
                    installStatus = "Downloaded %s, but it is not selectable: %s".formatted(entry.label(), validation.message());
                }
                validator.writeMetadata(staging, metadata);
            } catch (SpeechToTextException e) {
                if (entry.hasVerifiedDownload()) {
                    throw e;
                }
                installStatus = "Downloaded %s, but it is not selectable: %s".formatted(entry.label(), e.getMessage());
            }
            publish(staging, finalDirectory);
            Files.deleteIfExists(finalDirectory.resolve(MARKER));
            progress.accept(installStatus);
        } finally {
            deleteTreeIfOwned(operationTemp);
            deleteTreeIfOwned(staging);
        }
    }

    public void importFolder(
            Path source,
            Path sphinx4Root,
            String requestedName,
            SpeechToTextProviderContext.CancellationToken cancellationToken,
            Consumer<String> progress
    ) throws Exception {
        if (source == null) {
            throw new SpeechToTextException("Select a Sphinx4 model folder to import.");
        }
        if (Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new SpeechToTextException("Selected Sphinx4 model source is not a folder.");
        }
        Files.createDirectories(sphinx4Root);
        Path realRoot = sphinx4Root.toRealPath().normalize();
        Path realSource = source.toRealPath().normalize();
        rejectNestedImportPath(realSource, realRoot, "This Sphinx4 model is already managed by Chat4J.", "Cannot import a model into itself.");
        String safeName = safeImportName(requestedName);
        Path finalDirectory = validator.safeChild(sphinx4Root, safeName);
        Path realFinalDirectory = realRoot.resolve(safeName).normalize();
        if (Files.exists(finalDirectory)) {
            safeName = "%s-%s".formatted(safeName, stableSuffix(realSource.toString()));
            finalDirectory = validator.safeChild(sphinx4Root, safeName);
            realFinalDirectory = realRoot.resolve(safeName).normalize();
        }
        rejectNestedImportPath(realSource, realFinalDirectory, "Cannot import a model into itself.", "Cannot import a model into itself.");
        if (Files.exists(finalDirectory)) {
            throw new SpeechToTextException("A Sphinx4 model folder with that name already exists.");
        }
        long sourceBytes = sourceTreeBytes(realSource, cancellationToken);
        ensureUsableSpace(sphinx4Root, sourceBytes, "Sphinx4 model folder");
        String operationId = UUID.randomUUID().toString();
        Path staging = sphinx4Root.resolve("chat4j-sphinx4-import-%s.partial".formatted(operationId));
        Path realStaging = realRoot.resolve(staging.getFileName()).normalize();
        rejectNestedImportPath(realSource, realStaging, "Cannot import a model into itself.", "Cannot import a model into itself.");
        try {
            createOwnedPartial(staging, safeName);
            copyTree(realSource, staging, cancellationToken, progress);
            rejectExtractedSymlinks(staging);
            String importStatus = "Imported %s".formatted(finalDirectory.getFileName());
            try {
                Sphinx4ModelMetadata metadata = normalizeImportMetadata(staging, safeName, requestedName);
                validator.writeMetadata(staging, metadata);
                Sphinx4ModelValidator.ValidationResult validation = validator.validateRecipe(staging, sphinx4Root, metadata, true);
                if (validation.status() != Sphinx4ValidationStatus.VALID) {
                    importStatus = "Imported %s, but it is not selectable: %s".formatted(finalDirectory.getFileName(), validation.message());
                }
            } catch (SpeechToTextException e) {
                importStatus = "Imported %s, but it is not selectable: %s".formatted(finalDirectory.getFileName(), e.getMessage());
            }
            publish(staging, finalDirectory);
            Files.deleteIfExists(finalDirectory.resolve(MARKER));
            progress.accept(importStatus);
        } finally {
            deleteTreeIfOwned(staging);
        }
    }

    private void rejectNestedImportPath(Path realSource, Path destination, String sourceInsideMessage, String destinationInsideMessage) throws SpeechToTextException {
        Path source = realSource.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        if (source.startsWith(target)) {
            throw new SpeechToTextException(sourceInsideMessage);
        }
        if (target.startsWith(source)) {
            throw new SpeechToTextException(destinationInsideMessage);
        }
    }

    public void deleteInstalled(Sphinx4InstalledModel model, Path sphinx4Root) throws Exception {
        if (model == null || !model.deleteable()) {
            throw new SpeechToTextException("This Sphinx4 model cannot be deleted by Chat4J.");
        }
        Path directory = model.directory().toAbsolutePath().normalize();
        if (!directory.startsWith(sphinx4Root.toAbsolutePath().normalize())) {
            throw new SpeechToTextException("This model is outside the managed Sphinx4 directory.");
        }
        deleteTree(directory);
    }

    public void cleanupStalePartials(Path sphinx4Root, Path tempRoot) {
        cleanupRoot(sphinx4Root, "chat4j-sphinx4-install-");
        cleanupRoot(sphinx4Root, "chat4j-sphinx4-import-");
        cleanupRoot(tempRoot, "chat4j-sphinx4-download-");
    }

    private void validateDownloadEntry(Sphinx4ModelCatalogEntry entry) throws SpeechToTextException {
        if (entry == null || !entry.canDownload()) {
            throw new SpeechToTextException("This Sphinx4 catalog model cannot be downloaded.");
        }
        if (!validator.safeModelName(entry.id())) {
            throw new SpeechToTextException("Sphinx4 catalog entry has an unsafe model id.");
        }
        if (entry.hasVerifiedDownload() && entry.recipe() == null) {
            throw new SpeechToTextException("Sphinx4 catalog entry is missing a recipe.");
        }
        for (Sphinx4ModelArtifact artifact : entry.artifacts()) {
            validateArtifact(artifact, entry.hasVerifiedDownload());
        }
    }

    private List<Sphinx4ModelArtifact> downloadArtifacts(Sphinx4ModelCatalogEntry entry, Consumer<String> progress) throws Exception {
        if (!entry.artifacts().isEmpty()) {
            return entry.artifacts();
        }
        progress.accept("Reading Sphinx4 SourceForge catalog for %s...".formatted(entry.label()));
        String folderUrl = sourceForgeFolderUrl(entry);
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(folderUrl)).GET().timeout(DOWNLOAD_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SpeechToTextException("Could not read Sphinx4 SourceForge catalog: HTTP %d".formatted(response.statusCode()));
        }
        List<String> urls = sourceForgeDownloadUrls(response.body(), folderUrl);
        List<String> selected = selectedSourceForgeFiles(urls);
        if (selected.isEmpty()) {
            throw new SpeechToTextException("No downloadable Sphinx4 model files were found for %s.".formatted(entry.label()));
        }
        return selected.stream()
                .map(this::sourceForgeArtifact)
                .toList();
    }

    private Sphinx4ModelMetadata downloadMetadata(Sphinx4ModelCatalogEntry entry, List<Sphinx4ModelArtifact> artifacts, Path staging) throws Exception {
        if (entry.recipe() != null) {
            return Sphinx4ModelMetadata.fromCatalog(entry);
        }
        return validator.inferMetadata(staging, entry.id(), entry.label())
                .map(metadata -> new Sphinx4ModelMetadata(
                        1,
                        entry.id(),
                        entry.label(),
                        entry.language(),
                        artifacts.stream()
                                .map(artifact -> new Sphinx4ModelMetadata.SourceArtifact(artifact.artifactId(), artifact.url(), artifact.sha256(), artifact.expectedSizeBytes()))
                                .toList(),
                        metadata.acousticModelPath(),
                        metadata.dictionaryPath(),
                        metadata.languageModelPath(),
                        metadata.sampleRateHz(),
                        metadata.requiredFiles(),
                        entry.id(),
                        1,
                        false
                ))
                .orElseThrow(() -> new SpeechToTextException("Downloaded files do not contain an unambiguous Sphinx4 model layout."));
    }

    private void generateRecipeFiles(Sphinx4ModelCatalogEntry entry, Path staging, Sphinx4ModelMetadata metadata, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        if (entry.recipe() == null || !entry.recipe().generateUnigramLanguageModel()) {
            return;
        }
        Path dictionary = validator.safeRelativePath(staging, metadata.dictionaryPath());
        Path languageModel = validator.safeRelativePath(staging, metadata.languageModelPath());
        if (dictionary == null || languageModel == null) {
            throw new SpeechToTextException("Sphinx4 generated language-model paths are unsafe.");
        }
        if (!Files.isRegularFile(dictionary, LinkOption.NOFOLLOW_LINKS)) {
            throw new SpeechToTextException("Sphinx4 generated language model needs a pronunciation dictionary.");
        }
        if (Files.exists(languageModel, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        generateUnigramLanguageModel(dictionary, languageModel, cancellationToken);
    }

    private void generateUnigramLanguageModel(Path dictionary, Path languageModel, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(dictionary, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                checkCanceled(cancellationToken, "Sphinx4 model preparation canceled.");
                String trimmed = line.strip();
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith(";;;")) {
                    continue;
                }
                String word = trimmed.split("\\s+", 2)[0].replaceFirst("\\(\\d+\\)$", "");
                if (!word.isBlank() && !word.startsWith("#") && !word.contains("\\")) {
                    words.add(word);
                }
            }
        }
        if (words.isEmpty()) {
            throw new SpeechToTextException("Sphinx4 generated language model has no words.");
        }
        Files.createDirectories(languageModel.getParent());
        double probability = -Math.log10(words.size() + 2.0);
        try (var writer = Files.newBufferedWriter(languageModel, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write("\\data\\\n");
            writer.write("ngram 1=%d\n\n".formatted(words.size() + 2));
            writer.write("\\1-grams:\n");
            writer.write(String.format(Locale.ROOT, "%.4f <s> -0.3010%n", probability));
            writer.write(String.format(Locale.ROOT, "%.4f </s>%n", probability));
            int checked = 0;
            for (String word : words) {
                if (++checked % 1000 == 0) {
                    checkCanceled(cancellationToken, "Sphinx4 model preparation canceled.");
                }
                writer.write(String.format(Locale.ROOT, "%.4f %s%n", probability, word));
            }
            writer.write("\\end\\\n");
        }
    }

    private String sourceForgeFolderUrl(Sphinx4ModelCatalogEntry entry) {
        String encoded = URLEncoder.encode(entry.label(), StandardCharsets.UTF_8).replace("+", "%20");
        return "https://sourceforge.net/projects/cmusphinx/files/Acoustic%%20and%%20Language%%20Models/%s/".formatted(encoded);
    }

    private List<String> sourceForgeDownloadUrls(String html, String folderUrl) {
        String prefix = folderUrl.endsWith("/") ? folderUrl : "%s/".formatted(folderUrl);
        Matcher matcher = Pattern.compile("href=\\\"([^\\\"]+/download)\\\"").matcher(StringUtils.defaultString(html));
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        while (matcher.find()) {
            String url = matcher.group(1).replace("&amp;", "&");
            if (url.startsWith(prefix)) {
                urls.add(url);
            }
        }
        return List.copyOf(urls);
    }

    private List<String> selectedSourceForgeFiles(List<String> urls) {
        List<String> modelArchives = urls.stream()
                .filter(url -> sourceForgeFileName(url).matches("(?i).+\\.(tar\\.gz|tgz|tar\\.xz|txz|zip)"))
                .filter(url -> !sourceForgeFileName(url).matches("(?i).*(8khz|ptm|semi|zero).*"))
                .toList();
        String acousticArchive = modelArchives.stream()
                .filter(url -> sourceForgeFileName(url).matches("(?i).*cmusphinx.*\\.(tar\\.gz|tgz|tar\\.xz|txz|zip)"))
                .findFirst()
                .or(() -> modelArchives.stream().findFirst())
                .orElse("");
        List<String> supportFiles = urls.stream()
                .filter(url -> sourceForgeFileName(url).matches("(?i).+\\.(dic|dict|lm|lm\\.gz|lm\\.bin|dmp|lm\\.dmp)"))
                .filter(url -> !sourceForgeFileName(url).matches("(?i).*(phone|test|train).*"))
                .toList();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(acousticArchive)) {
            selected.add(acousticArchive);
        }
        supportFiles.stream()
                .filter(url -> sourceForgeFileName(url).matches("(?i).+\\.(dic|dict)"))
                .findFirst()
                .ifPresent(selected::add);
        supportFiles.stream()
                .filter(url -> sourceForgeFileName(url).matches("(?i).+\\.(lm\\.bin|lm\\.gz|lm|dmp|lm\\.dmp)"))
                .findFirst()
                .ifPresent(selected::add);
        return List.copyOf(selected);
    }

    private Sphinx4ModelArtifact sourceForgeArtifact(String url) {
        String name = sourceForgeFileName(url);
        String format;
        String targetPath = "";
        if (name.matches("(?i).+\\.zip")) {
            format = "zip";
        } else if (name.matches("(?i).+\\.(tar\\.xz|txz)")) {
            format = "tar.xz";
        } else if (name.matches("(?i).+\\.(tar\\.gz|tgz)")) {
            format = "tar.gz";
        } else if (name.matches("(?i).+\\.gz")) {
            format = "raw-gzip";
            targetPath = name.substring(0, name.length() - 3);
        } else {
            format = "raw-file";
            targetPath = name;
        }
        String artifactId = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("\\.(tar\\.gz|tgz|tar\\.xz|txz|zip|gz)$", "")
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return new Sphinx4ModelArtifact(
                StringUtils.defaultIfBlank(artifactId, "artifact"),
                "model",
                url,
                0,
                0,
                "",
                format,
                "",
                targetPath,
                true,
                true
        );
    }

    private String sourceForgeFileName(String url) {
        String path = URI.create(url).getPath();
        String withoutDownload = path.endsWith("/download") ? path.substring(0, path.length() - "/download".length()) : path;
        int slash = withoutDownload.lastIndexOf('/');
        return slash >= 0 ? withoutDownload.substring(slash + 1) : withoutDownload;
    }

    private void validateArtifact(Sphinx4ModelArtifact artifact, boolean verified) throws SpeechToTextException {
        if (artifact == null || StringUtils.isBlank(artifact.artifactId())) {
            throw new SpeechToTextException("Sphinx4 catalog entry has an invalid artifact.");
        }
        if (!validator.safeModelName(artifact.artifactId())) {
            throw new SpeechToTextException("Sphinx4 artifact has an unsafe id.");
        }
        TrustedSourceForgePath trustedPath = trustedSourceForgePath(artifact);
        validateInitialSourceForgeUri(URI.create(artifact.url()), trustedPath.projectPath());
        if (verified && StringUtils.isBlank(artifact.sha256())) {
            throw new SpeechToTextException("Sphinx4 artifact is missing a SHA-256 checksum.");
        }
        if (verified && artifact.expectedSizeBytes() <= 0) {
            throw new SpeechToTextException("Sphinx4 artifact is missing an expected download size.");
        }
        String format = StringUtils.trimToEmpty(artifact.archiveFormat());
        if (!List.of("zip", "tar.gz", "tgz", "tar.xz", "raw-gzip", "raw-file").contains(format)) {
            throw new SpeechToTextException("Sphinx4 artifact format is not supported.");
        }
        if ((artifact.rawFile() || artifact.rawGzip()) && validator.safeRelativePath(Path.of("."), artifact.targetPath()) == null) {
            throw new SpeechToTextException("Sphinx4 raw artifact target path is unsafe.");
        }
    }

    private void download(Sphinx4ModelArtifact artifact, Path outputFile, SpeechToTextProviderContext.CancellationToken cancellationToken, Consumer<String> progress) throws Exception {
        URI uri = URI.create(artifact.url());
        TrustedSourceForgePath trustedPath = trustedSourceForgePath(artifact);
        HttpResponse<InputStream> response = null;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            validateSourceForgeUri(uri, trustedPath.projectPath(), trustedPath.mirrorPath());
            response = httpClient.send(HttpRequest.newBuilder(uri).GET().timeout(DOWNLOAD_TIMEOUT).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 300 || response.statusCode() >= 400) {
                break;
            }
            URI next = response.headers().firstValue("location").map(uri::resolve).orElse(null);
            closeResponseBody(response);
            if (next == null) {
                throw new SpeechToTextException("Sphinx4 model redirect was missing a destination.");
            }
            uri = next;
        }
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            int status = response == null ? 0 : response.statusCode();
            closeResponseBody(response);
            throw new SpeechToTextException("Sphinx4 model download failed: HTTP %d".formatted(status));
        }
        long expectedSize = artifact.expectedSizeBytes();
        try (InputStream input = response.body()) {
            validateContentLength(artifact, response.headers().firstValueAsLong("Content-Length"));
            try (OutputStream output = Files.newOutputStream(outputFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCanceled(cancellationToken, "Sphinx4 model download canceled.");
                    total += read;
                    if (expectedSize > 0 && total > expectedSize) {
                        throw new SpeechToTextException("Sphinx4 model download exceeded the expected size.");
                    }
                    if (expectedSize <= 0 && total > MAX_DOWNLOAD_BYTES) {
                        throw new SpeechToTextException("Sphinx4 model download exceeded the maximum allowed size.");
                    }
                    output.write(buffer, 0, read);
                    if (expectedSize > 0) {
                        progress.accept("Downloading %s %s%%".formatted(artifact.artifactId(), Math.min(100, total * 100 / expectedSize)));
                    }
                }
                if (expectedSize > 0 && total != expectedSize) {
                    throw new SpeechToTextException("Sphinx4 model download size did not match the catalog.");
                }
            }
        }
    }

    private void validateContentLength(Sphinx4ModelArtifact artifact, OptionalLong contentLength) throws SpeechToTextException {
        long expectedSize = artifact.expectedSizeBytes();
        if (expectedSize > 0 && contentLength.isPresent() && contentLength.getAsLong() != expectedSize) {
            throw new SpeechToTextException("Sphinx4 model Content-Length did not match the catalog.");
        }
        if (expectedSize <= 0 && contentLength.isPresent() && contentLength.getAsLong() > MAX_DOWNLOAD_BYTES) {
            throw new SpeechToTextException("Sphinx4 model Content-Length exceeds the maximum allowed size.");
        }
    }

    private void assembleArtifact(
            Sphinx4ModelArtifact artifact,
            Path downloaded,
            Path staging,
            Set<Path> writtenPaths,
            SpeechToTextProviderContext.CancellationToken cancellationToken,
            Consumer<String> progress
    ) throws Exception {
        progress.accept("Extracting %s...".formatted(artifact.artifactId()));
        ensureUsableSpace(staging, artifact.expectedUncompressedBytes(), "Sphinx4 model folder");
        if (artifact.archive()) {
            if ("zip".equals(artifact.archiveFormat())) {
                extractZip(artifact, downloaded, staging, writtenPaths, cancellationToken);
            } else if ("tar.xz".equals(artifact.archiveFormat())) {
                extractTarXz(artifact, downloaded, staging, writtenPaths, cancellationToken);
            } else {
                extractTarGz(artifact, downloaded, staging, writtenPaths, cancellationToken);
            }
            return;
        }
        Path target = safeArtifactTarget(staging, artifact, artifact.targetPath());
        rejectCollision(writtenPaths, target);
        Files.createDirectories(target.getParent());
        if (artifact.rawGzip()) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(downloaded)); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                copyWithLimit(input, output, artifact.expectedUncompressedBytes(), cancellationToken);
            }
        } else if (artifact.rawFile()) {
            Files.copy(downloaded, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        normalizePermissions(target);
        writtenPaths.add(target.toAbsolutePath().normalize());
    }

    private void extractZip(Sphinx4ModelArtifact artifact, Path zipFile, Path staging, Set<Path> writtenPaths, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            ArchiveCounter counter = new ArchiveCounter();
            while ((entry = input.getNextEntry()) != null) {
                checkCanceled(cancellationToken, "Sphinx4 model extraction canceled.");
                counter.count(entry.getName(), entry.getSize());
                if (entry.isDirectory()) {
                    Files.createDirectories(safeArchiveTarget(staging, artifact, entry.getName()));
                    continue;
                }
                Path target = safeArchiveTarget(staging, artifact, entry.getName());
                rejectCollision(writtenPaths, target);
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    counter.addExtracted(copyWithLimit(input, output, entry.getSize(), cancellationToken));
                }
                normalizePermissions(target);
                writtenPaths.add(target.toAbsolutePath().normalize());
            }
            counter.verifyExpected(artifact.expectedUncompressedBytes());
        }
    }

    private void extractTarGz(Sphinx4ModelArtifact artifact, Path archive, Path staging, Set<Path> writtenPaths, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        try (InputStream compressed = new GzipCompressorInputStream(Files.newInputStream(archive));
             TarArchiveInputStream input = new TarArchiveInputStream(compressed)) {
            extractTar(artifact, input, staging, writtenPaths, cancellationToken);
        }
    }

    private void extractTarXz(Sphinx4ModelArtifact artifact, Path archive, Path staging, Set<Path> writtenPaths, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        try (InputStream compressed = new XZCompressorInputStream(Files.newInputStream(archive));
             TarArchiveInputStream input = new TarArchiveInputStream(compressed)) {
            extractTar(artifact, input, staging, writtenPaths, cancellationToken);
        }
    }

    private void extractTar(Sphinx4ModelArtifact artifact, TarArchiveInputStream input, Path staging, Set<Path> writtenPaths, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        TarArchiveEntry entry;
        ArchiveCounter counter = new ArchiveCounter();
        while ((entry = input.getNextTarEntry()) != null) {
            checkCanceled(cancellationToken, "Sphinx4 model extraction canceled.");
            counter.count(entry.getName(), entry.getSize());
            validateTarEntry(entry);
            if (entry.isDirectory()) {
                Files.createDirectories(safeArchiveTarget(staging, artifact, entry.getName()));
                continue;
            }
            if (!entry.isFile()) {
                throw new SpeechToTextException("Sphinx4 archive contains a non-file entry.");
            }
            Path target = safeArchiveTarget(staging, artifact, entry.getName());
            rejectCollision(writtenPaths, target);
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                counter.addExtracted(copyWithLimit(input, output, entry.getSize(), cancellationToken));
            }
            normalizePermissions(target);
            writtenPaths.add(target.toAbsolutePath().normalize());
        }
        counter.verifyExpected(artifact.expectedUncompressedBytes());
    }

    private void validateTarEntry(TarArchiveEntry entry) throws SpeechToTextException {
        if (entry.isSparse()) {
            throw new SpeechToTextException("Sphinx4 archive contains an unsupported sparse entry.");
        }
        if (entry.isSymbolicLink() || entry.isLink() || entry.isBlockDevice() || entry.isCharacterDevice() || entry.isFIFO()) {
            throw new SpeechToTextException("Sphinx4 archive contains an unsafe entry type.");
        }
    }

    private Path safeArchiveTarget(Path staging, Sphinx4ModelArtifact artifact, String entryName) throws SpeechToTextException {
        String normalized = normalizeRelativeArchivePath(entryName, artifact.stripTopLevelDirectory());
        if (normalized.isBlank()) {
            return staging;
        }
        String extractTo = StringUtils.trimToEmpty(artifact.extractTo()).replace('\\', '/');
        String relative = StringUtils.isBlank(extractTo) ? normalized : "%s/%s".formatted(extractTo, normalized);
        return safeArtifactTarget(staging, artifact, relative);
    }

    private Path safeArtifactTarget(Path staging, Sphinx4ModelArtifact artifact, String relativePath) throws SpeechToTextException {
        String relative = StringUtils.trimToEmpty(relativePath).replace('\\', '/');
        if (relative.isBlank() || relative.startsWith("/") || relative.contains("\0") || relative.contains("//")) {
            throw new SpeechToTextException("Sphinx4 artifact path is unsafe.");
        }
        Path root = staging.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new SpeechToTextException("Sphinx4 archive entry escapes the model directory.");
        }
        return target;
    }

    private String normalizeRelativeArchivePath(String entryName, boolean stripTopLevelDirectory) throws SpeechToTextException {
        String value = StringUtils.trimToEmpty(entryName).replace('\\', '/');
        if (value.isBlank() || value.startsWith("/") || value.contains("\0") || value.contains("//")) {
            throw new SpeechToTextException("Sphinx4 archive entry path is unsafe.");
        }
        List<String> parts = Stream.of(value.split("/"))
                .filter(StringUtils::isNotBlank)
                .toList();
        if (parts.stream().anyMatch(part -> ".".equals(part) || "..".equals(part))) {
            throw new SpeechToTextException("Sphinx4 archive entry path is unsafe.");
        }
        if (parts.size() > MAX_NESTING_DEPTH) {
            throw new SpeechToTextException("Sphinx4 archive nesting is too deep.");
        }
        if (stripTopLevelDirectory && parts.size() <= 1) {
            return "";
        }
        List<String> effective = stripTopLevelDirectory ? parts.subList(1, parts.size()) : parts;
        return String.join("/", effective);
    }

    private TrustedSourceForgePath trustedSourceForgePath(Sphinx4ModelArtifact artifact) throws SpeechToTextException {
        URI uri = URI.create(StringUtils.defaultString(artifact.url()));
        validateBasicSourceForgeUri(uri);
        rejectSourceForgeQuery(uri);
        String host = canonicalHost(uri.getHost());
        if (!"sourceforge.net".equals(host)) {
            throw new SpeechToTextException("Sphinx4 model URL host is not trusted.");
        }
        String projectPath = safeDecodedPath(uri.getRawPath());
        if (!projectPath.endsWith("/download")) {
            throw new SpeechToTextException("Sphinx4 model URL path is not trusted.");
        }
        String projectPrefix = "/projects/cmusphinx/files/";
        String projectFilePath;
        if (projectPath.equals("/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download")) {
            projectFilePath = "sphinx4/5prealpha/sphinx4-5prealpha-src.zip";
        } else if (projectPath.startsWith("/projects/cmusphinx/files/Acoustic and Language Models/")) {
            projectFilePath = projectPath.substring(projectPrefix.length(), projectPath.length() - "/download".length());
            if (StringUtils.isBlank(projectFilePath) || projectFilePath.endsWith("/")) {
                throw new SpeechToTextException("Sphinx4 model URL path is not trusted.");
            }
        } else {
            throw new SpeechToTextException("Sphinx4 model URL path is not trusted.");
        }
        return new TrustedSourceForgePath(projectPath, "/project/cmusphinx/%s".formatted(projectFilePath));
    }

    private void validateInitialSourceForgeUri(URI uri, String trustedProjectPath) throws SpeechToTextException {
        validateBasicSourceForgeUri(uri);
        rejectSourceForgeQuery(uri);
        String host = canonicalHost(uri.getHost());
        if (!"sourceforge.net".equals(host)) {
            throw new SpeechToTextException("Sphinx4 model URL host is not trusted.");
        }
        if (!safeDecodedPath(uri.getRawPath()).equals(trustedProjectPath)) {
            throw new SpeechToTextException("Sphinx4 model URL path is not trusted.");
        }
    }

    private void validateSourceForgeUri(URI uri, String trustedProjectPath, String trustedMirrorPath) throws SpeechToTextException {
        validateBasicSourceForgeUri(uri);
        String host = canonicalHost(uri.getHost());
        if (!"sourceforge.net".equals(host) && !"downloads.sourceforge.net".equals(host) && !host.endsWith(".dl.sourceforge.net")) {
            throw new SpeechToTextException("Sphinx4 model URL host is not trusted.");
        }
        String path = safeDecodedPath(uri.getRawPath());
        boolean projectPage = "sourceforge.net".equals(host) && path.equals(trustedProjectPath);
        boolean mirror = !"sourceforge.net".equals(host) && path.equals(trustedMirrorPath);
        if (!projectPage && !mirror) {
            throw new SpeechToTextException("Sphinx4 model URL path is not trusted.");
        }
        if (projectPage) {
            rejectSourceForgeQuery(uri);
        } else {
            validateSourceForgeMirrorQuery(uri);
        }
    }

    private void validateBasicSourceForgeUri(URI uri) throws SpeechToTextException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getRawFragment() != null) {
            throw new SpeechToTextException("Sphinx4 model URL is not trusted.");
        }
    }

    private void rejectSourceForgeQuery(URI uri) throws SpeechToTextException {
        if (uri.getRawQuery() != null) {
            throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
        }
    }

    private void validateSourceForgeMirrorQuery(URI uri) throws SpeechToTextException {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null) {
            return;
        }
        if (rawQuery.isBlank() || rawQuery.contains("\\") || rawQuery.contains("/")) {
            throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
        }
        String lowerQuery = rawQuery.toLowerCase(Locale.ROOT);
        if (lowerQuery.contains("%25") || lowerQuery.contains("%2e") || lowerQuery.contains("%2f") || lowerQuery.contains("%5c")) {
            throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
        }
        Set<String> seen = new HashSet<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isBlank()) {
                throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
            }
            int separator = pair.indexOf('=');
            if (separator < 0 || pair.indexOf('=', separator + 1) >= 0) {
                throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
            }
            String key = pair.substring(0, separator);
            String value = pair.substring(separator + 1);
            if (!seen.add(key)) {
                throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
            }
            validateSourceForgeMirrorQueryParameter(key, value);
        }
    }

    private void validateSourceForgeMirrorQueryParameter(String key, String value) throws SpeechToTextException {
        switch (key) {
            case "ts", "st" -> validateSourceForgeToken(value);
            case "use_mirror" -> {
                if (!value.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
                }
            }
            case "r" -> {
                if (!value.isEmpty()) {
                    throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
                }
            }
            case "viasf" -> {
                if (!"1".equals(value)) {
                    throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
                }
            }
            case "fid" -> {
                if (!value.matches("[A-Fa-f0-9]{8,64}")) {
                    throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
                }
            }
            case "e" -> {
                if (!value.matches("[0-9]{1,20}")) {
                    throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
                }
            }
            default -> throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
        }
    }

    private void validateSourceForgeToken(String value) throws SpeechToTextException {
        if (!value.matches("[A-Za-z0-9_.~%-]{1,256}")) {
            throw new SpeechToTextException("Sphinx4 model URL query is not trusted.");
        }
    }

    private String canonicalHost(String host) throws SpeechToTextException {
        String value = StringUtils.trimToEmpty(host).toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.endsWith(".")) {
            throw new SpeechToTextException("Sphinx4 model URL host is not trusted.");
        }
        return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private String safeDecodedPath(String rawPath) throws SpeechToTextException {
        String raw = StringUtils.defaultString(rawPath);
        String lowerRaw = raw.toLowerCase(Locale.ROOT);
        if (lowerRaw.contains("%2f") || lowerRaw.contains("%5c") || lowerRaw.contains("%25")) {
            throw new SpeechToTextException("Sphinx4 model URL path is unsafe.");
        }
        URI normalized = URI.create("https://sourceforge.net%s".formatted(raw)).normalize();
        String path = normalized.getPath();
        if (path.contains("%") || path.contains("\\")) {
            throw new SpeechToTextException("Sphinx4 model URL path is unsafe.");
        }
        List<String> segments = Stream.of(path.split("/"))
                .filter(StringUtils::isNotBlank)
                .toList();
        if (segments.stream().anyMatch(segment -> ".".equals(segment) || "..".equals(segment))) {
            throw new SpeechToTextException("Sphinx4 model URL path is unsafe.");
        }
        return path;
    }

    private void verifySha256(Sphinx4ModelArtifact artifact, Path file) throws Exception {
        if (StringUtils.isBlank(artifact.sha256())) {
            return;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        String actual = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        if (!actual.equals(StringUtils.trimToEmpty(artifact.sha256()).toLowerCase(Locale.ROOT))) {
            throw new SpeechToTextException("Sphinx4 model checksum did not match the catalog.");
        }
    }

    private void pruneDownloadedStagingToRecipe(Path staging, Sphinx4ModelMetadata metadata) throws Exception {
        Path root = staging.toAbsolutePath().normalize();
        Path acoustic = validator.safeRelativePath(staging, metadata.acousticModelPath());
        Path dictionary = validator.safeRelativePath(staging, metadata.dictionaryPath());
        Path languageModel = validator.safeRelativePath(staging, metadata.languageModelPath());
        if (acoustic == null || dictionary == null || languageModel == null) {
            throw new SpeechToTextException("Sphinx4 recipe paths are unsafe.");
        }
        Set<Path> keepFiles = new HashSet<>();
        Path marker = root.resolve(MARKER).toAbsolutePath().normalize();
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            keepFiles.add(marker);
        }
        keepFiles.add(dictionary.toAbsolutePath().normalize());
        keepFiles.add(languageModel.toAbsolutePath().normalize());
        try (Stream<Path> stream = Files.walk(acoustic)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(keepFiles::add);
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Path normalized = path.toAbsolutePath().normalize();
                    if (normalized.equals(root)) {
                        return;
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !keepFiles.contains(normalized)) {
                        Files.deleteIfExists(path);
                        return;
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && directoryEmpty(path)) {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private boolean directoryEmpty(Path path) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        }
    }

    private Sphinx4ModelMetadata normalizeImportMetadata(Path staging, String safeName, String requestedName) throws Exception {
        Sphinx4ModelValidator.ValidationResult validation = validator.validateStructural(staging, staging);
        if (validation.metadata() != null) {
            Sphinx4ModelMetadata existing = validation.metadata();
            if (!validator.safeModelName(existing.id())) {
                throw new SpeechToTextException("Imported Sphinx4 metadata has an unsafe id.");
            }
            return new Sphinx4ModelMetadata(
                    1,
                    safeName,
                    StringUtils.defaultIfBlank(existing.label(), StringUtils.defaultIfBlank(requestedName, safeName)),
                    StringUtils.defaultIfBlank(existing.language(), "Custom"),
                    existing.sourceArtifacts(),
                    existing.acousticModelPath(),
                    existing.dictionaryPath(),
                    existing.languageModelPath(),
                    existing.sampleRateHz(),
                    existing.requiredFiles(),
                    StringUtils.defaultIfBlank(existing.recipeId(), safeName),
                    Math.max(1, existing.recipeVersion()),
                    false
            );
        }
        return validator.inferMetadata(staging, safeName, StringUtils.defaultIfBlank(requestedName, safeName))
                .orElseThrow(() -> new SpeechToTextException("Selected folder does not contain an unambiguous Sphinx4 model with sample-rate metadata."));
    }

    private String safeImportName(String requestedName) {
        String slug = StringUtils.defaultString(requestedName).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        String normalized = StringUtils.defaultIfBlank(slug, "model");
        String safe = normalized.startsWith("local-") ? normalized : "local-%s".formatted(normalized);
        return validator.safeModelName(safe) ? safe : "local-model";
    }

    private String stableSuffix(String value) {
        CRC32 crc32 = new CRC32();
        crc32.update(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc32.getValue());
    }

    private void rejectCollision(Set<Path> writtenPaths, Path target) throws SpeechToTextException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!writtenPaths.add(normalized)) {
            throw new SpeechToTextException("Sphinx4 model artifacts write the same path more than once.");
        }
        writtenPaths.remove(normalized);
    }

    private void rejectExtractedSymlinks(Path root) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            boolean hasSymlink = stream.anyMatch(Files::isSymbolicLink);
            if (hasSymlink) {
                throw new SpeechToTextException("Sphinx4 model contains a symlink and was rejected.");
            }
        }
    }

    private long copyWithLimit(InputStream input, OutputStream output, long expectedUncompressedBytes, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkCanceled(cancellationToken, "Sphinx4 model operation canceled.");
            total += read;
            if (total > MAX_UNCOMPRESSED_BYTES || expectedUncompressedBytes > 0 && total > expectedUncompressedBytes) {
                throw new SpeechToTextException("Sphinx4 model extraction exceeded the expected size.");
            }
            output.write(buffer, 0, read);
        }
        if (expectedUncompressedBytes > 0 && total != expectedUncompressedBytes) {
            throw new SpeechToTextException("Sphinx4 model extracted size did not match the catalog.");
        }
        return total;
    }

    private long sourceTreeBytes(Path source, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(path -> {
                        try {
                            checkCanceled(cancellationToken, "Sphinx4 model import canceled.");
                            return Files.size(path);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .sum();
        }
    }

    private void copyTree(Path source, Path target, SpeechToTextProviderContext.CancellationToken cancellationToken, Consumer<String> progress) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                checkCanceled(cancellationToken, "Sphinx4 model import canceled.");
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString()).normalize();
                if (!destination.startsWith(target.normalize())) {
                    throw new SpeechToTextException("Sphinx4 import path escapes the target directory.");
                }
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new SpeechToTextException("Sphinx4 import contains an unsupported file type.");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    rejectHardLinkIfDetectable(path);
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    normalizePermissions(destination);
                }
                progress.accept("Copying %s".formatted(relative));
            }
        }
    }

    private void rejectHardLinkIfDetectable(Path path) throws SpeechToTextException {
        try {
            Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (links instanceof Number count && count.longValue() > 1L) {
                throw new SpeechToTextException("Sphinx4 import contains a hard link.");
            }
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            // Link counts are platform-specific; reject hard links where the filesystem exposes them.
        } catch (IOException e) {
            throw new SpeechToTextException("Could not inspect Sphinx4 import file links.", e);
        }
    }

    private void ensureUsableSpace(Path root, long bytes, String label) throws Exception {
        if (bytes <= 0) {
            return;
        }
        long usable = diskSpaceChecker.usableSpace(root);
        if (usable > 0 && usable < bytes + 128L * 1024L * 1024L) {
            throw new SpeechToTextException("Not enough disk space in the %s.".formatted(label));
        }
    }

    private void createOwnedPartial(Path directory, String modelName) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(MARKER), StringUtils.defaultString(modelName), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void publish(Path source, Path destination) throws Exception {
        CopyOption[] atomic = {StandardCopyOption.ATOMIC_MOVE};
        try {
            fileMover.move(source, destination, atomic);
        } catch (AtomicMoveNotSupportedException e) {
            fileMover.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupRoot(Path root, String prefix) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(path -> path.getFileName().toString().startsWith(prefix) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::staleOwnedPartial)
                    .forEach(this::deleteTreeIfOwned);
        } catch (Exception ignored) {
        }
    }

    private boolean staleOwnedPartial(Path path) {
        try {
            Path marker = path.resolve(MARKER);
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && Files.getLastModifiedTime(marker).toInstant().isBefore(Instant.now().minus(STALE_PARTIAL_AGE));
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteTreeIfOwned(Path path) {
        if (path == null || !Files.exists(path) || !Files.exists(path.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        deleteTree(path);
    }

    private void deleteTree(Path path) {
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void normalizePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private void closeResponseBody(HttpResponse<InputStream> response) {
        if (response == null || response.body() == null) {
            return;
        }
        try {
            response.body().close();
        } catch (Exception ignored) {
        }
    }

    private void checkCanceled(SpeechToTextProviderContext.CancellationToken cancellationToken, String message) throws SpeechToTextException {
        if (cancellationToken != null && cancellationToken.cancelled()) {
            throw new SpeechToTextException(message);
        }
    }

    private final class ArchiveCounter {
        private int entries;
        private long totalBytes;

        private void count(String name, long size) throws SpeechToTextException {
            entries++;
            if (entries > MAX_ARCHIVE_ENTRIES) {
                throw new SpeechToTextException("Sphinx4 archive contains too many entries.");
            }
            normalizeRelativeArchivePath(name, false);
            if (size > MAX_UNCOMPRESSED_BYTES) {
                throw new SpeechToTextException("Sphinx4 archive entry is too large.");
            }
        }

        private void addExtracted(long size) throws SpeechToTextException {
            totalBytes += Math.max(0, size);
            if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                throw new SpeechToTextException("Sphinx4 archive is too large.");
            }
        }

        private void verifyExpected(long expectedUncompressedBytes) throws SpeechToTextException {
            if (expectedUncompressedBytes > 0 && totalBytes != expectedUncompressedBytes) {
                throw new SpeechToTextException("Sphinx4 model extracted size did not match the catalog.");
            }
        }
    }

    private record TrustedSourceForgePath(String projectPath, String mirrorPath) {
    }

    @FunctionalInterface
    interface DiskSpaceChecker {
        long usableSpace(Path path) throws Exception;
    }

    @FunctionalInterface
    interface FileMover {
        void move(Path source, Path destination, CopyOption... options) throws Exception;
    }
}
