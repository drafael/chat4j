package com.github.drafael.chat4j.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FileIdentityTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Directory identity remains stable when a child is created")
    void directory_whenChildIsCreated_retainsIdentity() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("cache"));
        FileIdentity before = FileIdentity.directory(directory).orElseThrow();

        Files.writeString(directory.resolve("snapshot.json"), "data");

        assertThat(FileIdentity.directory(directory)).contains(before);
    }

    @Test
    @DisplayName("Regular-file identity changes after same-path replacement")
    void regularFile_whenSamePathIsReplaced_changesIdentity() throws Exception {
        Path file = Files.writeString(tempDir.resolve("snapshot.json"), "first");
        FileIdentity before = FileIdentity.regularFile(file).orElseThrow();

        Files.delete(file);
        Files.writeString(file, "other");

        assertThat(FileIdentity.regularFile(file))
                .hasValueSatisfying(identity -> assertThat(identity).isNotEqualTo(before));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows supplies a native fallback when BasicFileAttributes has no file key")
    void directory_whenWindowsNioFileKeyIsUnavailable_usesNativeFallback() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("cache"));
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class);
        Assumptions.assumeTrue(attributes.fileKey() == null, "Windows NIO provider supplies a file key");

        assertThat(FileIdentity.directory(directory)).isPresent();
    }
}
