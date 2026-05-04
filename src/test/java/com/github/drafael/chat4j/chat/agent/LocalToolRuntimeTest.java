package com.github.drafael.chat4j.chat.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
    @DisplayName("Read tool truncates large files")
    void execute_whenReadToolTargetsLargeFile_returnsTruncatedContent() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Files.writeString(projectRoot.resolve("large.txt"), "a".repeat(70_000), StandardCharsets.UTF_8);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest("1", "read", "{\"path\":\"large.txt\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(result.success()).isTrue();
            assertThat(result.output()).contains("[truncated after");
            assertThat(result.output().length()).isLessThan(70_000);
        }
    }

    @Test
    @DisplayName("Tool execution stops when cancellation is requested")
    void execute_whenCancelled_returnsFailure() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Files.writeString(projectRoot.resolve("note.txt"), "hello", StandardCharsets.UTF_8);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest("1", "read", "{\"path\":\"note.txt\"}"),
                    projectRoot,
                    () -> true
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("cancelled");
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
    @DisplayName("Batch edit validates every replacement against original content")
    void execute_whenBatchEditTargetsTextCreatedByEarlierEdit_returnsFailure() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Files.writeString(projectRoot.resolve("file.txt"), "alpha beta", StandardCharsets.UTF_8);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest(
                            "1",
                            "edit",
                            """
                                    {
                                      "path": "file.txt",
                                      "edits": [
                                        {"oldText": "beta", "newText": "gamma"},
                                        {"oldText": "gamma", "newText": "delta"}
                                      ]
                                    }
                                    """
                    ),
                    projectRoot,
                    () -> false
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not found");
            assertThat(Files.readString(projectRoot.resolve("file.txt"), StandardCharsets.UTF_8)).isEqualTo("alpha beta");
        }
    }

    @Test
    @DisplayName("Edit tool rejects malformed edits array")
    void execute_whenEditReceivesMalformedEdits_returnsFailure() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Files.writeString(projectRoot.resolve("file.txt"), "alpha beta", StandardCharsets.UTF_8);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest("1", "edit", "{\"path\":\"file.txt\",\"edits\":\"bad\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("edits should be a non-empty array");
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
    @DisplayName("Filesystem escape attempts are logged at WARN level")
    void execute_whenPathEscapesProjectRoot_logsWarning() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalToolRuntime.class);
        ListAppender<ILoggingEvent> appender = attachListAppender(logger);
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Files.writeString(fs.getPath("/outside.txt"), "secret", StandardCharsets.UTF_8);

            subject.execute(
                    new ToolInvocationRequest("1", "read", "{\"path\":\"../outside.txt\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("attempted to access path outside selected root");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("Filesystem tools reject symlinks that escape project root")
    void execute_whenSymlinkEscapesProjectRoot_returnsFailure() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Files.createDirectories(projectRoot);
            Path outside = fs.getPath("/outside.txt");
            Files.writeString(outside, "secret", StandardCharsets.UTF_8);
            Files.createSymbolicLink(projectRoot.resolve("link.txt"), outside);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest("1", "read", "{\"path\":\"link.txt\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("escapes project root");
        }
    }

    @Test
    @DisplayName("Write tool rejects symlink directories that escape project root")
    void execute_whenWriteTargetsEscapingSymlinkDirectory_returnsFailure() throws Exception {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
            Path projectRoot = fs.getPath("/workspace");
            Path outside = fs.getPath("/outside");
            Files.createDirectories(projectRoot);
            Files.createDirectories(outside);
            Files.createSymbolicLink(projectRoot.resolve("link-dir"), outside);

            ToolInvocationResult result = subject.execute(
                    new ToolInvocationRequest("1", "write", "{\"path\":\"link-dir/secret.txt\",\"content\":\"secret\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("escapes project root");
            assertThat(Files.exists(outside.resolve("secret.txt"))).isFalse();
        }
    }

    @Test
    @DisplayName("Bash tool executes shell command from selected project root")
    void execute_whenBashToolInvoked_runsWithinProjectRoot() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-bash");
        Files.writeString(projectRoot.resolve("note.txt"), "hello", StandardCharsets.UTF_8);

        ToolInvocationResult result = subject.execute(
                new ToolInvocationRequest("1", "bash", "{\"command\":\"pwd && ls\"}"),
                projectRoot,
                () -> false
        );

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("exit=0");
        assertThat(result.output()).contains(projectRoot.toAbsolutePath().normalize().toString());
        assertThat(result.output()).contains("note.txt");
    }

    @Test
    @DisplayName("Bash tool invocations are logged at INFO level")
    void execute_whenBashToolInvoked_logsInvocation() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalToolRuntime.class);
        ListAppender<ILoggingEvent> appender = attachListAppender(logger);
        try {
            Path projectRoot = Files.createTempDirectory("chat4j-agent-bash-logging");

            subject.execute(
                    new ToolInvocationRequest("1", "bash", "{\"command\":\"echo log-test\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getFormattedMessage()).contains("Agent bash tool invocation");
                        assertThat(event.getFormattedMessage()).contains("echo log-test");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("Bash tool redacts sensitive command values in logs")
    void execute_whenBashToolCommandContainsSensitiveValue_redactsLogValue() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalToolRuntime.class);
        ListAppender<ILoggingEvent> appender = attachListAppender(logger);
        try {
            Path projectRoot = Files.createTempDirectory("chat4j-agent-bash-redaction");

            subject.execute(
                    new ToolInvocationRequest("1", "bash", "{\"command\":\"echo OPENAI_API_KEY=secret-value --token secret-flag\"}"),
                    projectRoot,
                    () -> false
            );

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getFormattedMessage()).contains("OPENAI_API_KEY=<redacted>");
                        assertThat(event.getFormattedMessage()).contains("--token <redacted>");
                        assertThat(event.getFormattedMessage()).doesNotContain("secret-value");
                        assertThat(event.getFormattedMessage()).doesNotContain("secret-flag");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("Bash tool supports shell features")
    void execute_whenBashToolUsesShellFeatures_runsThroughBash() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-bash-shell");

        ToolInvocationResult result = subject.execute(
                new ToolInvocationRequest("1", "bash", "{\"command\":\"printf 'hello' > generated.txt && cat generated.txt\"}"),
                projectRoot,
                () -> false
        );

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("exit=0");
        assertThat(result.output()).contains("hello");
        assertThat(Files.readString(projectRoot.resolve("generated.txt"), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    @DisplayName("Bash tool truncates large command output")
    void execute_whenBashToolOutputIsLarge_returnsTruncatedOutput() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-bash-large-output");

        ToolInvocationResult result = subject.execute(
                new ToolInvocationRequest("1", "bash", "{\"command\":\"yes a | head -c 70000\"}"),
                projectRoot,
                () -> false
        );

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("exit=0");
        assertThat(result.output()).contains("truncated after");
        assertThat(result.output().length()).isLessThan(70_000);
    }

    private ListAppender<ILoggingEvent> attachListAppender(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
