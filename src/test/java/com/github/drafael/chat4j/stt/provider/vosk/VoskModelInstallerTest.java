package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

    private record ZipContent(String name, String text) {
    }
}
