package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedImageAttachmentWriterTest {

    @Test
    @DisplayName("Generated images use UUID-only managed storage names")
    void write_whenImageIsValid_usesUuidStorageName(@TempDir Path tempDir) throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("config"));
        var subject = new GeneratedImageAttachmentWriter(storagePaths);

        var result = subject.write("image".getBytes(), "image/png");
        Path storedPath = Path.of(result.storagePath());

        assertThat(storedPath).startsWith(storagePaths.attachmentsDirectory());
        assertThat(storedPath.getFileName().toString()).isEqualTo(UUID.fromString(
                storedPath.getFileName().toString()
        ).toString());
        assertThat(result.originalName()).startsWith("generated-image-").endsWith(".png");
        assertThat(Files.readString(storedPath)).isEqualTo("image");
    }
}
