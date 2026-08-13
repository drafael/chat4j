package com.github.drafael.chat4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.support.ProcessHandleSupport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Chat4jStdioClientTransportTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Cancelling an outbound write force-closes a server that never reads stdin")
    void sendMessage_whenWriteIsCancelled_forceClosesBlockedStdinOwner() throws Exception {
        Path pidFile = tempDirectory.resolve("blocked-stdin.pid");
        Chat4jStdioClientTransport subject = transport(List.of(
                "--write-pid", pidFile.toString(), "--never-read"
        ));
        long pid = -1;
        try {
            subject.connect(messages -> messages).block();
            pid = awaitPid(pidFile);
            var message = new McpSchema.JSONRPCNotification(
                    "test/large",
                    Map.of("payload", "x".repeat(900_000))
            );
            CompletableFuture<Void> write = subject.sendMessage(message).toFuture();

            write.cancel(true);

            assertThat(ProcessHandle.of(pid).map(ProcessHandleSupport::isRunning).orElse(false)).isFalse();
        } finally {
            try {
                subject.closeNow();
            } finally {
                destroyForciblyAndAwait(pid);
            }
        }
    }

    @Test
    @DisplayName("Close racing process startup settles only after the published process terminates")
    void closeNow_whenProcessStartIsInFlight_waitsForAndTerminatesPublishedProcess() throws Exception {
        var launcherEntered = new CountDownLatch(1);
        var releaseLauncher = new CountDownLatch(1);
        var launchedProcess = new AtomicReference<Process>();
        List<String> command = fixtureCommand(List.of("--never-read"));
        var subject = new Chat4jStdioClientTransport(
                command,
                emptyMap(),
                tempDirectory,
                new JacksonMcpJsonMapper(new ObjectMapper()),
                builder -> {
                    launcherEntered.countDown();
                    try {
                        releaseLauncher.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while starting fixture", e);
                    }
                    Process process = builder.start();
                    launchedProcess.set(process);
                    return process;
                }
        );
        CompletableFuture<Void> connecting = CompletableFuture.runAsync(() ->
                subject.connect(messages -> messages).block());
        CompletableFuture<Void> closing = null;
        try {
            assertThat(launcherEntered.await(5, TimeUnit.SECONDS)).isTrue();
            closing = CompletableFuture.runAsync(subject::closeNow);
            releaseLauncher.countDown();
            assertThatThrownBy(() -> connecting.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            closing.get(5, TimeUnit.SECONDS);

            assertThat(launchedProcess.get()).isNotNull();
            assertThat(ProcessHandleSupport.isRunning(launchedProcess.get().toHandle())).isFalse();
        } finally {
            releaseLauncher.countDown();
            try {
                connecting.handle((ignored, error) -> null).join();
                if (closing != null) {
                    closing.handle((ignored, error) -> null).join();
                }
                subject.closeNow();
            } finally {
                destroyForciblyAndAwait(launchedProcess.get());
            }
        }
    }

    @Test
    @DisplayName("Concurrent close callers settle only after TERM-resistant descendants are gone")
    void closeNow_whenCalledConcurrently_waitsForWinningCleanup() throws Exception {
        Assumptions.assumeFalse(Strings.CI.contains(System.getProperty("os.name", ""), "win"));
        Path childPidFile = tempDirectory.resolve("child.pid");
        Chat4jStdioClientTransport subject = transport(List.of(
                "--spawn-term-resistant-child", childPidFile.toString()
        ));
        long childPid = -1;
        try {
            subject.connect(messages -> messages).block();
            childPid = awaitPid(childPidFile);
            var start = new CountDownLatch(1);
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> closeAfter(start, subject));
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> closeAfter(start, subject));

            start.countDown();
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);

            assertThat(ProcessHandle.of(childPid).map(ProcessHandleSupport::isRunning).orElse(false)).isFalse();
        } finally {
            try {
                subject.closeNow();
            } finally {
                destroyForciblyAndAwait(childPid);
            }
        }
    }

    @Test
    @DisplayName("A TERM handler child is discovered and force-terminated before cleanup succeeds")
    void closeNow_whenDescendantSpawnsChildOnTerm_capturesAndTerminatesNewChild() throws Exception {
        Assumptions.assumeFalse(Strings.CI.contains(System.getProperty("os.name", ""), "win"));
        Path childPidFile = tempDirectory.resolve("term-child.pid");
        Path readyFile = tempDirectory.resolve("term-handler.ready");
        Chat4jStdioClientTransport subject = transport(List.of(
                "--spawn-term-resistant-child-on-term",
                childPidFile.toString(),
                readyFile.toString(),
                "--never-read"
        ));
        long childPid = -1;
        try {
            subject.connect(messages -> messages).block();
            awaitFile(readyFile);

            subject.closeNow();
            childPid = awaitPid(childPidFile);

            assertThat(ProcessHandle.of(childPid).map(ProcessHandleSupport::isRunning).orElse(false)).isFalse();
        } finally {
            try {
                subject.retryCleanup();
            } finally {
                destroyForciblyAndAwait(childPid);
            }
        }
    }

    @Test
    @DisplayName("Failed final process-tree verification is visible and can be retried")
    void closeNow_whenProcessRemainsAlive_reportsFailureUntilHardRetrySucceeds() {
        var parentAlive = new AtomicBoolean(true);
        var childAlive = new AtomicBoolean(true);
        Process process = mock(Process.class);
        ProcessHandle parentHandle = mock(ProcessHandle.class);
        ProcessHandle childHandle = mock(ProcessHandle.class);
        when(process.isAlive()).thenAnswer(ignored -> parentAlive.get());
        when(process.toHandle()).thenReturn(parentHandle);
        when(parentHandle.descendants()).thenAnswer(ignored -> Stream.of(childHandle));
        when(childHandle.descendants()).thenReturn(Stream.empty());
        when(childHandle.isAlive()).thenAnswer(ignored -> childAlive.get());
        when(childHandle.destroy()).thenReturn(true);
        when(childHandle.destroyForcibly()).thenReturn(true);
        when(process.destroyForcibly()).thenAnswer(ignored -> {
            parentAlive.set(false);
            return process;
        });
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        var subject = new Chat4jStdioClientTransport(
                List.of("fixture"),
                emptyMap(),
                tempDirectory,
                new JacksonMcpJsonMapper(new ObjectMapper()),
                ignored -> process
        );
        subject.connect(messages -> messages).block();

        assertThatThrownBy(subject::closeNow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP stdio process tree did not terminate.");

        childAlive.set(false);
        subject.retryCleanup();
        assertThatCode(subject::closeNow).doesNotThrowAnyException();
    }

    private void destroyForciblyAndAwait(long pid) throws Exception {
        if (pid <= 0) {
            return;
        }
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        if (ProcessHandleSupport.isRunning(process)) {
            process.destroyForcibly();
            awaitNotRunning(process);
        }
    }

    private void destroyForciblyAndAwait(Process process) throws Exception {
        if (process != null && ProcessHandleSupport.isRunning(process.toHandle())) {
            process.destroyForcibly();
            awaitNotRunning(process.toHandle());
        }
    }

    private void awaitNotRunning(ProcessHandle process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandleSupport.isRunning(process)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Fixture process did not terminate.");
            }
            Thread.sleep(10);
        }
    }

    private Chat4jStdioClientTransport transport(List<String> fixtureArguments) {
        return new Chat4jStdioClientTransport(
                fixtureCommand(fixtureArguments),
                emptyMap(),
                tempDirectory,
                new JacksonMcpJsonMapper(new ObjectMapper())
        );
    }

    private List<String> fixtureCommand(List<String> fixtureArguments) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        Strings.CI.contains(System.getProperty("os.name", ""), "win") ? "java.exe" : "java"
                ).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                McpStdioFixtureMain.class.getName()
        ));
        command.addAll(fixtureArguments);
        return command;
    }

    private void closeAfter(CountDownLatch start, Chat4jStdioClientTransport subject) {
        try {
            start.await();
            subject.closeNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.isRegularFile(file)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Fixture ready file was not created.");
            }
            Thread.onSpinWait();
        }
    }

    private long awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (Files.isRegularFile(pidFile)) {
                String value = Files.readString(pidFile);
                if (StringUtils.isNotBlank(value)) {
                    return Long.parseLong(value.trim());
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Fixture PID file was not created.");
            }
            Thread.onSpinWait();
        }
    }
}
