package com.github.drafael.chat4j.chat.agent;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalToolRuntimeTest {

    private final LocalToolRuntime subject = new LocalToolRuntime();

    @Test
    @DisplayName("Read tool returns file content inside project root")
    void execute_whenReadToolTargetsFileWithinRoot_returnsFileContent() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Files.writeString(projectRoot.resolve("note.txt"), "hello", StandardCharsets.UTF_8);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest("1", "read", "{\"path\":\"note.txt\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEqualTo("hello");
        }
    }

    @Test
    @DisplayName("Write and edit tools update content inside project root")
    void execute_whenWriteAndEditToolsInvoked_updatesFileContent() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);

            ToolInvocationResult writeResult = subject.execute(
                    new ToolInvocationRequest("1", "write", "{\"path\":\"docs/file.txt\",\"content\":\"hello world\"}"),
                    projectRoot,
                    () -> false
            );
            ToolInvocationResult editResult = subject.execute(
                    new ToolInvocationRequest("2", "edit", "{\"path\":\"docs/file.txt\",\"oldText\":\"world\",\"newText\":\"chat4j\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(writeResult.success()).isTrue();
            assertThat(editResult.success()).isTrue();
            assertThat(Files.readString(projectRoot.resolve("docs/file.txt"), StandardCharsets.UTF_8))
                    .isEqualTo("hello chat4j");
        }
    }

    @Test
    @DisplayName("Filesystem tools reject paths that escape project root")
    void execute_whenPathEscapesProjectRoot_returnsFailure() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Files.writeString(fs.getPath("/outside.txt"), "secret", StandardCharsets.UTF_8);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest("1", "read", "{\"path\":\"../outside.txt\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("escapes project root");
        }
    }

    @Test
    @DisplayName("Bash tool executes command from selected project root")
    void execute_whenBashToolInvoked_runsWithinProjectRoot() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-bash");
        Files.writeString(projectRoot.resolve("note.txt"), "hello", StandardCharsets.UTF_8);

        ToolInvocationResult result = subject.execute(
                new ToolInvocationRequest("1", "bash", "{\"command\":\"ls\"}"),
                projectRoot,
                () -> false
        );

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("exit=0");
        assertThat(result.output()).contains("note.txt");
    }
}
