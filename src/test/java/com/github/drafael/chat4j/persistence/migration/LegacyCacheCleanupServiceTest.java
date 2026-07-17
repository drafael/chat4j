package com.github.drafael.chat4j.persistence.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyCacheCleanupServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Recognized top-level legacy files are deleted without affecting unknown entries")
    void cleanup_whenRecognizedAndUnknownFilesExist_deletesOnlyRecognizedFiles() throws Exception {
        Path legacyRoot = Files.createDirectory(tempDir.resolve("models-cache"));
        Files.writeString(legacyRoot.resolve("provider.txt"), "must not be read");
        Files.writeString(legacyRoot.resolve("github-copilot-model-metadata.json"), "must not be read");
        Files.writeString(legacyRoot.resolve("keep.bin"), "keep");
        Files.createDirectory(legacyRoot.resolve("nested"));

        new LegacyCacheCleanupService(legacyRoot).cleanup();

        assertThat(legacyRoot.resolve("provider.txt")).doesNotExist();
        assertThat(legacyRoot.resolve("github-copilot-model-metadata.json")).doesNotExist();
        assertThat(legacyRoot.resolve("keep.bin")).exists();
        assertThat(legacyRoot.resolve("nested")).isDirectory();
    }

    @Test
    @DisplayName("Recognized-looking symlinks are preserved without touching their targets")
    void cleanup_whenRecognizedEntryIsSymlink_preservesLinkAndTarget() throws Exception {
        Path legacyRoot = Files.createDirectory(tempDir.resolve("models-cache"));
        Path target = Files.createTempFile(tempDir, "target", ".txt");
        Files.writeString(target, "keep");
        Path link = legacyRoot.resolve("provider.txt");
        createSymlinkOrSkip(link, target);

        new LegacyCacheCleanupService(legacyRoot).cleanup();

        assertThat(link).isSymbolicLink();
        assertThat(target).hasContent("keep");
    }

    @Test
    @DisplayName("An empty legacy root is removed after complete cleanup")
    void cleanup_whenRecognizedFileIsOnlyEntry_removesEmptyRoot() throws Exception {
        Path legacyRoot = Files.createDirectory(tempDir.resolve("models-cache"));
        Files.writeString(legacyRoot.resolve("provider.txt"), "unused");

        new LegacyCacheCleanupService(legacyRoot).cleanup();

        assertThat(legacyRoot).doesNotExist();
    }

    @Test
    @DisplayName("Recognized-looking directories and nested files are preserved")
    void cleanup_whenRecognizedNamesAreNotTopLevelRegularFiles_preservesThem() throws Exception {
        Path legacyRoot = Files.createDirectory(tempDir.resolve("models-cache"));
        Path recognizedDirectory = Files.createDirectory(legacyRoot.resolve("directory.txt"));
        Path nestedDirectory = Files.createDirectory(legacyRoot.resolve("nested"));
        Path nestedFile = Files.writeString(nestedDirectory.resolve("provider.txt"), "keep");

        new LegacyCacheCleanupService(legacyRoot).cleanup();

        assertThat(recognizedDirectory).isDirectory();
        assertThat(nestedFile).hasContent("keep");
    }

    @Test
    @DisplayName("A legacy root symlink is preserved without inspecting its target")
    void cleanup_whenLegacyRootIsSymlink_preservesRootAndTarget() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path targetFile = Files.writeString(target.resolve("provider.txt"), "keep");
        Path legacyRoot = tempDir.resolve("models-cache");
        createSymlinkOrSkip(legacyRoot, target);

        new LegacyCacheCleanupService(legacyRoot).cleanup();

        assertThat(legacyRoot).isSymbolicLink();
        assertThat(targetFile).hasContent("keep");
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("Symbolic links are unavailable: %s".formatted(e.getMessage()));
        }
    }
}
