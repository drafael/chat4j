package com.github.drafael.chat4j.stt.provider.whisper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WhisperInstalledModelScannerTest {

    private static final String HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Scanner accepts only metadata and checksum matched Chat4J installs")
    void scan_whenMetadataAndChecksumMatch_returnsReadyModel() throws Exception {
        var entry = entry();
        Path root = Files.createDirectories(tempDir.resolve("whisper"));
        Path directory = Files.createDirectories(root.resolve("tiny"));
        Files.writeString(directory.resolve("model.bin"), "hello");
        writeMetadata(directory, entry);
        var subject = new WhisperInstalledModelScanner();

        List<WhisperInstalledModel> models = subject.scan(root, List.of(entry));

        assertThat(models).hasSize(1);
        assertThat(models.getFirst().ready()).isTrue();
        assertThat(models.getFirst().reference().modelId()).isEqualTo("tiny");
    }

    @Test
    @DisplayName("Scanner reports known catalog folders with mismatched metadata as invalid")
    void scan_whenKnownFolderHasBadMetadata_returnsInvalidDeleteableModel() throws Exception {
        var entry = entry();
        Path root = Files.createDirectories(tempDir.resolve("whisper"));
        Path directory = Files.createDirectories(root.resolve("tiny"));
        Files.writeString(directory.resolve("model.bin"), "hello");
        writeMetadata(directory, entry);
        Files.writeString(directory.resolve("metadata.properties"), "metadataVersion=1\nid=tiny\n");
        var subject = new WhisperInstalledModelScanner();

        List<WhisperInstalledModel> models = subject.scan(root, List.of(entry));

        assertThat(models).hasSize(1);
        assertThat(models.getFirst().ready()).isFalse();
        assertThat(models.getFirst().deleteable()).isTrue();
        assertThat(models.getFirst().validationStatus()).isEqualTo(WhisperValidationStatus.INVALID);
    }

    @Test
    @DisplayName("Ordinary scanner refresh defers checksum verification")
    void scan_whenChecksumMismatchButRefreshIsOrdinary_keepsModelVisibleUntilExplicitValidation() throws Exception {
        var entry = entry();
        Path root = Files.createDirectories(tempDir.resolve("cheap-whisper"));
        Path directory = Files.createDirectories(root.resolve("tiny"));
        Files.writeString(directory.resolve("model.bin"), "hullo");
        writeMetadata(directory, entry);
        var subject = new WhisperInstalledModelScanner();

        List<WhisperInstalledModel> models = subject.scan(root, List.of(entry));
        WhisperInstalledModelScanner.ValidationResult validation = subject.validateInstall(directory, root, entry);

        assertThat(models).hasSize(1);
        assertThat(models.getFirst().ready()).isTrue();
        assertThat(validation.status()).isEqualTo(WhisperValidationStatus.INVALID);
        assertThat(validation.message()).contains("checksum");
    }

    @Test
    @DisplayName("Scanner ignores unknown unmarked folders")
    void scan_whenUnknownFolderExists_ignoresIt() throws Exception {
        var entry = entry();
        Path root = Files.createDirectories(tempDir.resolve("whisper"));
        Files.createDirectories(root.resolve("custom-model"));
        var subject = new WhisperInstalledModelScanner();

        assertThat(subject.scan(root, List.of(entry))).isEmpty();
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

    private void writeMetadata(Path directory, WhisperModelCatalogEntry entry) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("metadataVersion", WhisperInstalledModelScanner.METADATA_VERSION);
        properties.setProperty("id", entry.id());
        properties.setProperty("sourceUrl", entry.downloadUri().toString());
        properties.setProperty("expectedFileName", entry.expectedFileName());
        properties.setProperty("sizeBytes", Long.toString(entry.sizeBytes()));
        properties.setProperty("sha1", entry.sha1());
        try (var output = Files.newOutputStream(directory.resolve("metadata.properties"))) {
            properties.store(output, "test");
        }
    }
}
