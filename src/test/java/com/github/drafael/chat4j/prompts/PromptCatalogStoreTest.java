package com.github.drafael.chat4j.prompts;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class PromptCatalogStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Missing prompt file produces an empty catalog value")
    void loadCatalogJson_whenFileMissing_returnsEmpty() {
        var subject = new PromptCatalogStore(promptsFile());

        assertThat(subject.loadCatalogJson()).isEmpty();
    }

    @Test
    @DisplayName("Prompt JSON is saved and loaded as UTF-8")
    void saveCatalogJson_whenTextContainsUnicode_roundTripsUtf8() throws Exception {
        var subject = new PromptCatalogStore(promptsFile());
        String catalogJson = """
                [{"title":"Café 日本語"}]
                """;

        subject.saveCatalogJson(catalogJson);

        assertThat(subject.loadCatalogJson()).contains(catalogJson);
        assertThat(Files.readString(promptsFile(), StandardCharsets.UTF_8)).isEqualTo(catalogJson);
    }

    @Test
    @DisplayName("Saving creates a missing prompt catalog directory")
    void saveCatalogJson_whenParentDirectoryMissing_createsDirectory() throws Exception {
        Path promptsFile = tempDir.resolve("nested/config/prompts.json");
        var subject = new PromptCatalogStore(promptsFile);

        subject.saveCatalogJson("catalog");

        assertThat(Files.readString(promptsFile, StandardCharsets.UTF_8)).isEqualTo("catalog");
    }

    @Test
    @DisplayName("A parentless relative path publishes through its working directory")
    void saveCatalogJson_whenPathHasNoParent_usesWorkingDirectory() throws Exception {
        Configuration configuration = Configuration.unix().toBuilder().setWorkingDirectory("/work").build();
        try (FileSystem fileSystem = Jimfs.newFileSystem(configuration)) {
            Path promptsFile = fileSystem.getPath("prompts.json");
            var subject = new PromptCatalogStore(promptsFile);

            subject.saveCatalogJson("catalog");

            assertThat(Files.readString(fileSystem.getPath("/work/prompts.json"), StandardCharsets.UTF_8))
                    .isEqualTo("catalog");
            try (var files = Files.list(fileSystem.getPath("/work"))) {
                assertThat(files.map(Path::getFileName).map(Path::toString))
                        .containsExactly("prompts.json");
            }
        }
    }

    @Test
    @DisplayName("Saving replaces the complete prompt file and removes temporary files")
    void saveCatalogJson_whenFileExists_replacesFileAndCleansTemporaryFile() throws Exception {
        Files.writeString(promptsFile(), "old", StandardCharsets.UTF_8);
        var subject = new PromptCatalogStore(promptsFile());

        subject.saveCatalogJson("new");

        assertThat(Files.readString(promptsFile(), StandardCharsets.UTF_8)).isEqualTo("new");
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString))
                    .containsExactly("prompts.json");
        }
    }

    @Test
    @DisplayName("Saving falls back to replacement when an atomic move is unavailable")
    void saveCatalogJson_whenAtomicMoveFails_replacesFile() throws Exception {
        Files.writeString(promptsFile(), "old", StandardCharsets.UTF_8);
        var subject = new PromptCatalogStore(promptsFile());
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), eq(promptsFile()), eq(ATOMIC_MOVE), eq(REPLACE_EXISTING)))
                    .thenThrow(atomicMoveNotSupported());

            subject.saveCatalogJson("new");
        }

        assertThat(Files.readString(promptsFile(), StandardCharsets.UTF_8)).isEqualTo("new");
    }

    @Test
    @DisplayName("An ordinary atomic move failure does not retry with weaker guarantees")
    void saveCatalogJson_whenAtomicMoveFailsForOtherReason_preservesPreviousFile() throws Exception {
        Files.writeString(promptsFile(), "old", StandardCharsets.UTF_8);
        var subject = new PromptCatalogStore(promptsFile());
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), eq(promptsFile()), eq(ATOMIC_MOVE), eq(REPLACE_EXISTING)))
                    .thenThrow(new IOException("forced move failure"));

            assertThatThrownBy(() -> subject.saveCatalogJson("new"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to persist prompt catalog");
        }

        assertThat(Files.readString(promptsFile(), StandardCharsets.UTF_8)).isEqualTo("old");
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString))
                    .containsExactly("prompts.json");
        }
    }

    @Test
    @DisplayName("A failed temporary write removes the incomplete file")
    void saveCatalogJson_whenTemporaryWriteFails_cleansTemporaryFile() throws Exception {
        var subject = new PromptCatalogStore(promptsFile());
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.writeString(any(Path.class), eq("new"), eq(StandardCharsets.UTF_8)))
                    .thenThrow(new IOException("forced write failure"));

            assertThatThrownBy(() -> subject.saveCatalogJson("new"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to persist prompt catalog");
        }

        try (var files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @DisplayName("A failed replacement preserves the previous prompt file and removes temporary files")
    void saveCatalogJson_whenReplacementFails_preservesPreviousFileAndCleansTemporaryFile() throws Exception {
        Files.writeString(promptsFile(), "old", StandardCharsets.UTF_8);
        var subject = new PromptCatalogStore(promptsFile());
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), eq(promptsFile()), eq(ATOMIC_MOVE), eq(REPLACE_EXISTING)))
                    .thenThrow(atomicMoveNotSupported());
            files.when(() -> Files.move(any(Path.class), eq(promptsFile()), eq(REPLACE_EXISTING)))
                    .thenThrow(new IOException("forced replacement failure"));

            assertThatThrownBy(() -> subject.saveCatalogJson("new"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to persist prompt catalog");
        }

        assertThat(Files.readString(promptsFile(), StandardCharsets.UTF_8)).isEqualTo("old");
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString))
                    .containsExactly("prompts.json");
        }
    }

    private AtomicMoveNotSupportedException atomicMoveNotSupported() {
        return new AtomicMoveNotSupportedException("temporary", "prompts.json", "forced unsupported move");
    }

    private Path promptsFile() {
        return tempDir.resolve("prompts.json");
    }
}
