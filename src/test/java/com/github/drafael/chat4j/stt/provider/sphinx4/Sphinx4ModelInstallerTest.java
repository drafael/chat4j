package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class Sphinx4ModelInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("SourceForge URL policy requires exact trusted initial and mirrored paths")
    void validateSourceForgeUri_rejectsNearMisses() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        String bootstrapProjectPath = "/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download";
        String bootstrapMirrorPath = "/project/cmusphinx/sphinx4/5prealpha/sphinx4-5prealpha-src.zip";
        String acousticProjectUrl = "https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/US%20English/model.tar.gz/download";
        String acousticProjectPath = "/projects/cmusphinx/files/Acoustic and Language Models/US English/model.tar.gz/download";
        String acousticMirrorPath = "/project/cmusphinx/Acoustic and Language Models/US English/model.tar.gz";

        assertThatCode(() -> validateArtifact(installer, artifact("https://sourceforge.net%s".formatted(bootstrapProjectPath), 0)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateArtifact(installer, artifact(acousticProjectUrl, 0)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateRedirect(installer, "https://downloads.sourceforge.net%s".formatted(bootstrapMirrorPath), bootstrapProjectPath, bootstrapMirrorPath))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateRedirect(installer, "https://downloads.sourceforge.net%s?ts=gAAAAABqTfPYFxNY8QfP9VILQoCv-LxhnnaayqNbufFh10bguc6dfor7kGP-zc-dIb-ED_Gz7bEKUliOmqnjT955thbifD7Txg%%3D%%3D&use_mirror=master&r=".formatted(bootstrapMirrorPath), bootstrapProjectPath, bootstrapMirrorPath))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateRedirect(installer, "https://phoenixnap.dl.sourceforge.net/project/cmusphinx/Acoustic%20and%20Language%20Models/US%20English/model.tar.gz?use_mirror=autoselect&ts=1712345678&r=", acousticProjectPath, acousticMirrorPath))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateRedirect(installer, "https://master.dl.sourceforge.net/project/cmusphinx/Acoustic%20and%20Language%20Models/US%20English/model.tar.gz?viasf=1&fid=b34efcadc0e3453a&e=1783579992&st=lruYhgjy8EItIlwQmmexEA", acousticProjectPath, acousticMirrorPath))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validateArtifact(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/other.zip/download", 0)))
                .hasMessageContaining("path is not trusted");
        assertThatThrownBy(() -> validateArtifact(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip", 0)))
                .hasMessageContaining("path is not trusted");
        assertThatThrownBy(() -> validateRedirect(installer, "https://downloads.sourceforge.net/project/cmusphinx/sphinx4/5prealpha/other.zip", bootstrapProjectPath, bootstrapMirrorPath))
                .hasMessageContaining("path is not trusted");
        assertThatThrownBy(() -> validateRedirect(installer, "https://downloads.sourceforge.net/project/cmusphinx/Acoustic%20and%20Language%20Models/US%20English/other.tar.gz", acousticProjectPath, acousticMirrorPath))
                .hasMessageContaining("path is not trusted");
        assertThatThrownBy(() -> validateRedirect(installer, "https://downloads.sourceforge.net.evil.example%s".formatted(bootstrapMirrorPath), bootstrapProjectPath, bootstrapMirrorPath))
                .hasMessageContaining("host is not trusted");
        assertThatThrownBy(() -> validateArtifact(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/%252e%252e/evil.zip/download", 0)))
                .hasMessageContaining("path is unsafe");
        assertThatThrownBy(() -> validateRedirect(installer, "https://sourceforge.net%s?ts=1712345678&use_mirror=phoenixnap&r=".formatted(bootstrapProjectPath), bootstrapProjectPath, bootstrapMirrorPath))
                .hasMessageContaining("query is not trusted");
        assertThatThrownBy(() -> validateRedirect(installer, "https://downloads.sourceforge.net%s?ts=1712345678&use_mirror=phoenixnap&evil=1&r=".formatted(bootstrapMirrorPath), bootstrapProjectPath, bootstrapMirrorPath))
                .hasMessageContaining("query is not trusted");
        assertThatThrownBy(() -> validateRedirect(installer, "https://downloads.sourceforge.net%s?ts=1712345678&use_mirror=phoenixnap&r=https://evil.example".formatted(bootstrapMirrorPath), bootstrapProjectPath, bootstrapMirrorPath))
                .hasMessageContaining("query is not trusted");
        assertThatThrownBy(() -> validateRedirect(installer, "https://downloads.sourceforge.net%s?ts=1712345678&use_mirror=%%252e%%252e&r=".formatted(bootstrapMirrorPath), bootstrapProjectPath, bootstrapMirrorPath))
                .hasMessageContaining("query is not trusted");
    }

    @Test
    @DisplayName("SourceForge catalog selection keeps one acoustic model and companion files")
    void selectedSourceForgeFiles_whenFolderHasCatalogFiles_selectsDownloadPlan() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        List<String> urls = List.of(
                "https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/Spanish/cmusphinx-es-5.2.tar.gz/download",
                "https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/Spanish/es-20k.lm.gz/download",
                "https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/Spanish/es.dict/download",
                "https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/Spanish/LICENSE/download"
        );

        @SuppressWarnings("unchecked")
        List<String> selected = (List<String>) invoke(installer, "selectedSourceForgeFiles", new Class<?>[]{List.class}, urls);

        assertThat(selected).containsExactly(urls.get(0), urls.get(2), urls.get(1));
    }

    @Test
    @DisplayName("Download pre-rejects mismatched Content-Length when catalog size is known")
    void validateContentLength_whenHeaderDiffersFromCatalog_rejectsDownload() {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());

        assertThatThrownBy(() -> validateContentLength(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download", 0, 5), OptionalLong.of(6)))
                .hasMessageContaining("Content-Length did not match");
        assertThatCode(() -> validateContentLength(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download", 0, 5), OptionalLong.of(5)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateContentLength(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download", 0, 0), OptionalLong.of(6)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validateContentLength(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download", 0, 0), OptionalLong.of(3L * 1024L * 1024L * 1024L)))
                .hasMessageContaining("maximum allowed size");
    }

    @Test
    @DisplayName("ZIP extraction enforces artifact-level expected uncompressed bytes")
    void extractZip_whenExpectedUncompressedBytesDiffers_rejectsArchive() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path zip = tempDir.resolve("model.zip");
        writeZip(zip, "model/file.txt", "hello");
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging);

        assertThatThrownBy(() -> assembleArtifact(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download", 6), zip, staging))
                .hasMessageContaining("extracted size did not match");
    }

    @Test
    @DisplayName("ZIP extraction accepts archive when artifact-level uncompressed bytes match")
    void extractZip_whenExpectedUncompressedBytesMatches_acceptsArchive() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path zip = tempDir.resolve("model-ok.zip");
        writeZip(zip, "model/file.txt", "hello");
        Path staging = tempDir.resolve("staging-ok");
        Files.createDirectories(staging);

        assertThatCode(() -> assembleArtifact(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download", 5), zip, staging))
                .doesNotThrowAnyException();
        assertThat(Files.readString(staging.resolve("model/file.txt"))).isEqualTo("hello");
    }

    @Test
    @DisplayName("TAR/XZ extraction accepts German-style archive format")
    void extractTarXz_whenArchiveIsXzCompressed_extractsFiles() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path tarXz = tempDir.resolve("model.tar.xz");
        writeTarXz(tarXz, "model/file.txt", "hello");
        Path staging = tempDir.resolve("staging-tar-xz");
        Files.createDirectories(staging);
        var artifact = new Sphinx4ModelArtifact(
                "test-artifact",
                "all",
                "https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/German/model.tar.xz/download",
                1,
                5,
                "0".repeat(64),
                "tar.xz",
                "",
                "",
                false,
                true
        );

        assertThatCode(() -> assembleArtifact(installer, artifact, tarXz, staging))
                .doesNotThrowAnyException();
        assertThat(Files.readString(staging.resolve("model/file.txt"))).isEqualTo("hello");
    }

    @Test
    @DisplayName("Generated unigram language models are written from dictionaries")
    void generateRecipeFiles_whenRecipeRequestsUnigramLanguageModel_createsArpaFile() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path staging = tempDir.resolve("staging-generated-lm");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("dict.dic"), "olá o l a\nolá(2) o lh a\nmundo m u n d o\n", StandardCharsets.UTF_8);
        var entry = new Sphinx4ModelCatalogEntry(
                "cmusphinx-pt",
                "Portuguese",
                "Portuguese",
                "",
                false,
                true,
                false,
                1,
                16000,
                "",
                "",
                "",
                List.of(),
                new Sphinx4ModelRecipe(".", "dict.dic", "generated.lm", List.of("dict.dic", "generated.lm"), true)
        );
        var metadata = Sphinx4ModelMetadata.fromCatalog(entry);

        invoke(installer,
                "generateRecipeFiles",
                new Class<?>[]{Sphinx4ModelCatalogEntry.class, Path.class, Sphinx4ModelMetadata.class, SpeechToTextProviderContext.CancellationToken.class},
                entry,
                staging,
                metadata,
                SpeechToTextProviderContext.CancellationToken.never());

        assertThat(staging.resolve("generated.lm")).exists();
        assertThat(Files.readString(staging.resolve("generated.lm"), StandardCharsets.UTF_8))
                .contains("\\data\\", "olá", "mundo")
                .doesNotContain("olá(2)");
    }

    @Test
    @DisplayName("Extraction checks expected uncompressed bytes against usable disk space before writing")
    void assembleArtifact_whenExpectedUncompressedBytesExceedUsableSpace_rejectsBeforeExtraction() throws Exception {
        var installer = new Sphinx4ModelInstaller(HttpClient.newHttpClient(), new Sphinx4ModelValidator(), path -> 1L);
        Path zip = tempDir.resolve("model-space.zip");
        writeZip(zip, "model/file.txt", "hello");
        Path staging = tempDir.resolve("staging-space");
        Files.createDirectories(staging);

        assertThatThrownBy(() -> assembleArtifact(installer, artifact("https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download", 5), zip, staging))
                .hasMessageContaining("Not enough disk space");
        assertThat(staging.resolve("model/file.txt")).doesNotExist();
    }

    @Test
    @DisplayName("Import rejects selected source root symlinks")
    void importFolder_whenSelectedSourceIsSymlink_rejectsImport() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path realSource = tempDir.resolve("real-source-model");
        Files.createDirectories(realSource);
        Path sourceLink = tempDir.resolve("source-link");
        try {
            Files.createSymbolicLink(sourceLink, realSource);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are not available: %s".formatted(e.getMessage()));
        }

        assertThatThrownBy(() -> installer.importFolder(sourceLink, tempDir.resolve("managed-root"), "imported", SpeechToTextProviderContext.CancellationToken.never(), ignored -> {
        }))
                .hasMessageContaining("Selected Sphinx4 model source is not a folder");
    }

    @Test
    @DisplayName("Import rejects model root symlink nested inside selected source")
    void importFolder_whenRootSymlinkPointsInsideSource_rejectsRecursiveImport() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path source = tempDir.resolve("source-model");
        Path targetRoot = source.resolve("managed-root");
        Files.createDirectories(targetRoot);
        Path rootLink = tempDir.resolve("sphinx4-root-link");
        try {
            Files.createSymbolicLink(rootLink, targetRoot);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are not available: %s".formatted(e.getMessage()));
        }

        assertThatThrownBy(() -> installer.importFolder(source, rootLink, "imported", SpeechToTextProviderContext.CancellationToken.never(), ignored -> {
        }))
                .hasMessageContaining("Cannot import a model into itself");
    }

    @Test
    @DisplayName("Import copy rejects child symlinks")
    void copyTree_whenSourceContainsChildSymlink_rejectsImport() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path source = tempDir.resolve("source-with-child-link");
        Files.createDirectories(source);
        Files.writeString(source.resolve("real-file.txt"), "real");
        Path childLink = source.resolve("child-link.txt");
        try {
            Files.createSymbolicLink(childLink, source.resolve("real-file.txt"));
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are not available: %s".formatted(e.getMessage()));
        }

        assertThatThrownBy(() -> copyTree(installer, source, tempDir.resolve("child-link-target")))
                .hasMessageContaining("unsupported file type");
    }

    @Test
    @DisplayName("Import copy rejects hard links when link counts are available")
    void copyTree_whenSourceContainsHardLink_rejectsImport() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path source = tempDir.resolve("source-with-hard-link");
        Files.createDirectories(source);
        Path original = source.resolve("original.txt");
        Path hardLink = source.resolve("hard-link.txt");
        Files.writeString(original, "shared");
        try {
            Files.createLink(hardLink, original);
            Object links = Files.getAttribute(original, "unix:nlink");
            assumeTrue(links instanceof Number count && count.longValue() > 1L, "Link counts are not available.");
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Hard links or link counts are not available: %s".formatted(e.getMessage()));
        }

        assertThatThrownBy(() -> copyTree(installer, source, tempDir.resolve("hard-link-target")))
                .hasMessageContaining("hard link");
    }

    @Test
    @DisplayName("Import copy rejects unsupported special files")
    void copyTree_whenSourceContainsSpecialFile_rejectsImport() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path source = tempDir.resolve("source-with-special-file");
        Files.createDirectories(source);
        Path fifo = source.resolve("pipe");
        createNamedPipeOrSkip(fifo);

        assertThatThrownBy(() -> copyTree(installer, source, tempDir.resolve("special-file-target")))
                .hasMessageContaining("unsupported file type");
    }

    @Test
    @DisplayName("Downloaded staging pruning preserves ownership marker for failure cleanup")
    void pruneDownloadedStagingToRecipe_preservesOwnershipMarker() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        Path staging = tempDir.resolve("chat4j-sphinx4-install-test.partial");
        Files.createDirectories(staging.resolve("model"));
        Files.writeString(staging.resolve(".chat4j-sphinx4-partial"), "cmusphinx-en-us");
        Files.writeString(staging.resolve("model/mdef"), "mdef");
        Files.writeString(staging.resolve("dict.dic"), "dict");
        Files.writeString(staging.resolve("lm.bin"), "lm");
        Files.writeString(staging.resolve("unused.txt"), "unused");
        var metadata = new Sphinx4ModelMetadata(
                1,
                "cmusphinx-en-us",
                "US English",
                "English",
                List.of(),
                "model",
                "dict.dic",
                "lm.bin",
                16000,
                List.of("model/mdef", "dict.dic", "lm.bin"),
                "cmusphinx-en-us",
                1,
                true
        );

        invoke(installer, "pruneDownloadedStagingToRecipe", new Class<?>[]{Path.class, Sphinx4ModelMetadata.class}, staging, metadata);

        assertThat(staging.resolve(".chat4j-sphinx4-partial")).exists();
        assertThat(staging.resolve("unused.txt")).doesNotExist();
        assertThat(staging.resolve("model/mdef")).exists();
        assertThat(staging.resolve("dict.dic")).exists();
        assertThat(staging.resolve("lm.bin")).exists();
    }

    @Test
    @DisplayName("TAR validation rejects unsupported sparse entries")
    void validateTarEntry_whenSparse_rejectsEntry() throws Exception {
        var installer = new Sphinx4ModelInstaller(new Sphinx4ModelValidator());
        var entry = new TarArchiveEntry("model/file.txt", (byte) 'S');
        entry.setSize(1);

        assertThatThrownBy(() -> invoke(installer, "validateTarEntry", new Class<?>[]{TarArchiveEntry.class}, entry))
                .hasMessageContaining("unsupported sparse entry");
    }

    private Sphinx4ModelArtifact artifact(String url, long expectedUncompressedBytes) {
        return artifact(url, expectedUncompressedBytes, 1);
    }

    private Sphinx4ModelArtifact artifact(String url, long expectedUncompressedBytes, long expectedSizeBytes) {
        return new Sphinx4ModelArtifact(
                "test-artifact",
                "all",
                url,
                expectedSizeBytes,
                expectedUncompressedBytes,
                "0".repeat(64),
                "zip",
                "",
                "",
                false,
                true
        );
    }

    private void validateArtifact(Sphinx4ModelInstaller installer, Sphinx4ModelArtifact artifact) throws Exception {
        invoke(installer, "validateArtifact", new Class<?>[]{Sphinx4ModelArtifact.class, boolean.class}, artifact, true);
    }

    private void validateRedirect(Sphinx4ModelInstaller installer, String url, String trustedProjectPath, String trustedMirrorPath) throws Exception {
        invoke(installer, "validateSourceForgeUri", new Class<?>[]{URI.class, String.class, String.class}, URI.create(url), trustedProjectPath, trustedMirrorPath);
    }

    private void validateContentLength(Sphinx4ModelInstaller installer, Sphinx4ModelArtifact artifact, OptionalLong contentLength) throws Exception {
        invoke(installer, "validateContentLength", new Class<?>[]{Sphinx4ModelArtifact.class, OptionalLong.class}, artifact, contentLength);
    }

    private void assembleArtifact(Sphinx4ModelInstaller installer, Sphinx4ModelArtifact artifact, Path downloaded, Path staging) throws Exception {
        invoke(installer,
                "assembleArtifact",
                new Class<?>[]{Sphinx4ModelArtifact.class, Path.class, Path.class, Set.class, SpeechToTextProviderContext.CancellationToken.class, Consumer.class},
                artifact,
                downloaded,
                staging,
                new HashSet<Path>(),
                null,
                (Consumer<String>) ignored -> {
                });
    }

    private void copyTree(Sphinx4ModelInstaller installer, Path source, Path target) throws Exception {
        invoke(installer,
                "copyTree",
                new Class<?>[]{Path.class, Path.class, SpeechToTextProviderContext.CancellationToken.class, Consumer.class},
                source,
                target,
                SpeechToTextProviderContext.CancellationToken.never(),
                (Consumer<String>) ignored -> {
                });
    }

    private void createNamedPipeOrSkip(Path fifo) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        } catch (IOException | SecurityException e) {
            assumeTrue(false, "Named pipes are not available: %s".formatted(e.getMessage()));
            return;
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            assumeTrue(false, "Interrupted while checking named pipe support.");
            return;
        }
        assumeTrue(exitCode == 0 && Files.exists(fifo), "Named pipes are not available on this filesystem.");
    }

    private void writeZip(Path zip, String name, String content) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry(name));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private void writeTarXz(Path tarXz, String name, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (XZOutputStream xz = new XZOutputStream(Files.newOutputStream(tarXz), new LZMA2Options());
             TarArchiveOutputStream output = new TarArchiveOutputStream(xz)) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(bytes.length);
            output.putArchiveEntry(entry);
            output.write(bytes);
            output.closeArchiveEntry();
        }
    }

    private Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
