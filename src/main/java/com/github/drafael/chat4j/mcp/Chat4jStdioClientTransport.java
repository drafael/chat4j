/*
 * Derived in part from the Model Context Protocol Java SDK StdioClientTransport.
 * Copyright 2024-2024 the original authors. Licensed under Apache License 2.0.
 */
package com.github.drafael.chat4j.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;
import reactor.core.publisher.Mono;

import static java.lang.Math.min;

public final class Chat4jStdioClientTransport implements McpClientTransport {

    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 16 * 1024;
    private static final Duration GRACE_PERIOD = Duration.ofMillis(350);
    private static final Duration CLOSE_SETTLEMENT_TIMEOUT = Duration.ofSeconds(2);

    private final List<String> command;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private final McpJsonMapper jsonMapper;
    private final ProcessLauncher processLauncher;
    private final Object lifecycleLock = new Object();
    private final Object cleanupLock = new Object();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final Set<ProcessHandle> knownDescendants = new LinkedHashSet<>();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat4j-mcp-stdin", 0).factory()
    );
    private volatile Process process;
    private volatile Thread stdoutThread;
    private volatile Thread stderrThread;
    private volatile Consumer<Throwable> exceptionHandler = ignored -> { };
    private volatile boolean cleanupSucceeded;

    public Chat4jStdioClientTransport(
            @NonNull List<String> command,
            @NonNull Map<String, String> environment,
            @NonNull Path workingDirectory,
            @NonNull McpJsonMapper jsonMapper
    ) {
        this(command, environment, workingDirectory, jsonMapper, ProcessBuilder::start);
    }

    Chat4jStdioClientTransport(
            @NonNull List<String> command,
            @NonNull Map<String, String> environment,
            @NonNull Path workingDirectory,
            @NonNull McpJsonMapper jsonMapper,
            ProcessLauncher processLauncher
    ) {
        Validate.notEmpty(command, "command should not be empty");
        this.command = List.copyOf(command);
        this.environment = new HashMap<>(environment);
        this.workingDirectory = workingDirectory;
        this.jsonMapper = jsonMapper;
        this.processLauncher = processLauncher;
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(ProtocolVersions.MCP_2025_06_18);
    }

    @Override
    public Mono<Void> connect(
            @NonNull Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler
    ) {
        return Mono.fromRunnable(() -> start(handler));
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler == null ? ignored -> { } : exceptionHandler;
    }

    @Override
    public Mono<Void> sendMessage(@NonNull McpSchema.JSONRPCMessage message) {
        if (closeStarted.get()) {
            return Mono.error(new IllegalStateException("MCP stdio transport is closed."));
        }
        byte[] serialized;
        try {
            serialized = jsonMapper.writeValueAsBytes(message);
        } catch (IOException e) {
            return Mono.error(new IllegalStateException("Could not encode MCP stdio message."));
        }
        if (serialized.length > MAX_MESSAGE_BYTES || containsNewline(serialized)) {
            return Mono.error(new IllegalArgumentException("MCP stdio message exceeds framing limits."));
        }
        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> writeMessage(serialized), writerExecutor);
        return Mono.fromFuture(write).doOnCancel(this::closeNow);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::closeNow);
    }

    public void closeNow() {
        if (!closeStarted.compareAndSet(false, true)) {
            awaitCloseCompletion();
            return;
        }
        try {
            retryCleanup();
            closeCompletion.complete(null);
        } catch (RuntimeException e) {
            closeCompletion.completeExceptionally(e);
            throw e;
        }
    }

    void retryCleanup() {
        closeStarted.set(true);
        synchronized (cleanupLock) {
            if (cleanupSucceeded) {
                return;
            }
            Process current;
            synchronized (lifecycleLock) {
                current = process;
            }
            RuntimeException failure = null;
            try {
                terminateProcessTree(current);
            } catch (RuntimeException e) {
                failure = e;
            } finally {
                closeProcessStreams(current);
                writerExecutor.shutdownNow();
                join(stdoutThread);
                join(stderrThread);
                environment.clear();
            }
            if (failure != null) {
                throw failure;
            }
            cleanupSucceeded = true;
        }
    }

    boolean isAlive() {
        Process current = process;
        return !closeStarted.get() && current != null && current.isAlive();
    }

    @Override
    public <T> T unmarshalFrom(@NonNull Object data, @NonNull TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    private void start(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        synchronized (lifecycleLock) {
            if (closeStarted.get()) {
                throw new IllegalStateException("MCP stdio transport is closed.");
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            try {
                process = processLauncher.start(builder);
            } catch (IOException e) {
                throw new IllegalStateException("Could not start MCP stdio server.", e);
            }
            if (closeStarted.get()) {
                terminateProcessTree(process);
                closeProcessStreams(process);
                throw new IllegalStateException("MCP stdio transport closed during startup.");
            }
            stdoutThread = Thread.ofVirtual().name("chat4j-mcp-stdout").start(() -> readStdout(handler));
            stderrThread = Thread.ofVirtual().name("chat4j-mcp-stderr").start(this::drainStderr);
        }
    }

    private void readStdout(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        try (InputStream input = process.getInputStream()) {
            while (!closeStarted.get()) {
                byte[] line = readBoundedLine(input, MAX_MESSAGE_BYTES);
                if (line == null) {
                    throw new IOException("MCP stdio server exited unexpectedly.");
                }
                String json = decodeUtf8(line);
                McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, json);
                handler.apply(Mono.just(message))
                        .doOnError(exceptionHandler)
                        .subscribe();
            }
        } catch (Exception e) {
            if (!closeStarted.get()) {
                exceptionHandler.accept(new IllegalStateException("MCP stdio protocol failed.", e));
            }
        }
    }

    private void drainStderr() {
        try (InputStream input = process.getErrorStream()) {
            byte[] buffer = new byte[1024];
            int retained = 0;
            int read;
            while (!closeStarted.get() && (read = input.read(buffer)) >= 0) {
                retained = min(MAX_STDERR_BYTES, retained + read);
            }
        } catch (IOException e) {
            if (!closeStarted.get()) {
                exceptionHandler.accept(new IllegalStateException("MCP stdio error stream failed."));
            }
        }
    }

    private byte[] readBoundedLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) >= 0) {
            if (next == '\n') {
                return line.toByteArray();
            }
            if (next == '\r') {
                if (input.read() == '\n') {
                    return line.toByteArray();
                }
                throw new IOException("MCP stdio carriage returns must be followed by a newline.");
            }
            if (line.size() >= limit) {
                throw new IOException("MCP stdio message exceeds the 1 MiB limit.");
            }
            line.write(next);
        }
        if (line.size() != 0) {
            throw new IOException("MCP stdio ended with an incomplete message.");
        }
        return null;
    }

    private void writeMessage(byte[] serialized) {
        if (closeStarted.get()) {
            throw new IllegalStateException("MCP stdio transport is closed.");
        }
        try {
            Process current = process;
            if (current == null) {
                throw new IllegalStateException("MCP stdio transport is not connected.");
            }
            OutputStream output = current.getOutputStream();
            output.write(serialized);
            output.write('\n');
            output.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write MCP stdio message.");
        }
    }

    private String decodeUtf8(byte[] value) throws Exception {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
    }

    private boolean containsNewline(byte[] value) {
        for (byte item : value) {
            if (item == '\r' || item == '\n') {
                return true;
            }
        }
        return false;
    }

    private void awaitCloseCompletion() {
        if (cleanupSucceeded) {
            return;
        }
        try {
            closeCompletion.get(CLOSE_SETTLEMENT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP stdio process cleanup was interrupted.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("MCP stdio process cleanup failed.", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("MCP stdio process cleanup did not settle.", e);
        }
    }

    private void terminateProcessTree(Process current) {
        if (current == null) {
            return;
        }
        Set<ProcessHandle> descendants = knownDescendants;
        captureDescendants(current, descendants);
        destroyDescendants(descendants, false);
        current.destroy();
        settleProcessTree(current, descendants, false, GRACE_PERIOD);
        if (current.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            captureDescendants(current, descendants);
            destroyDescendants(descendants, true);
            if (current.isAlive()) {
                current.destroyForcibly();
            }
            settleProcessTree(current, descendants, true, GRACE_PERIOD);
        }
        captureDescendants(current, descendants);
        if (current.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            throw new IllegalStateException("MCP stdio process tree did not terminate.");
        }
    }

    private void settleProcessTree(
            Process current,
            Set<ProcessHandle> descendants,
            boolean force,
            Duration duration
    ) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            captureDescendants(current, descendants);
            destroyDescendants(descendants, force);
            if (force && current.isAlive()) {
                current.destroyForcibly();
            }
            if (!current.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        captureDescendants(current, descendants);
    }

    private void captureDescendants(Process current, Set<ProcessHandle> descendants) {
        List<ProcessHandle> roots = new ArrayList<>();
        if (current.isAlive()) {
            roots.add(current.toHandle());
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(roots::add);
        roots.forEach(root -> {
            try {
                descendants.addAll(root.descendants().toList());
            } catch (RuntimeException ignored) {
            }
        });
    }

    private void destroyDescendants(Set<ProcessHandle> descendants, boolean force) {
        new ArrayList<>(descendants).reversed().stream()
                .filter(ProcessHandle::isAlive)
                .forEach(descendant -> {
                    if (force) {
                        descendant.destroyForcibly();
                    } else {
                        descendant.destroy();
                    }
                });
    }

    private void closeProcessStreams(Process current) {
        if (current == null) {
            return;
        }
        try {
            current.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
            current.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            current.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(ProcessBuilder builder) throws IOException;
    }

    private void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(GRACE_PERIOD);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
