package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionDirectoryContextTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Nested scopes restore previous execution directory")
    void open_whenNested_restoresPreviousDirectory() throws Exception {
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);

        try (ExecutionDirectoryContext.Scope outer = ExecutionDirectoryContext.open(first)) {
            assertThat(ExecutionDirectoryContext.currentDirectory()).hasValue(first.toAbsolutePath().normalize());

            try (ExecutionDirectoryContext.Scope inner = ExecutionDirectoryContext.open(second)) {
                assertThat(ExecutionDirectoryContext.currentDirectory()).hasValue(second.toAbsolutePath().normalize());
            }

            assertThat(ExecutionDirectoryContext.currentDirectory()).hasValue(first.toAbsolutePath().normalize());
        }

        assertThat(ExecutionDirectoryContext.currentDirectory()).isEmpty();
    }

    @Test
    @DisplayName("Null directory opens a no-op scope")
    void open_whenDirectoryIsNull_returnsNoOpScope() {
        try (ExecutionDirectoryContext.Scope ignored = ExecutionDirectoryContext.open(null)) {
            assertThat(ExecutionDirectoryContext.currentDirectory()).isEmpty();
        }

        assertThat(ExecutionDirectoryContext.currentDirectory()).isEmpty();
    }
}
