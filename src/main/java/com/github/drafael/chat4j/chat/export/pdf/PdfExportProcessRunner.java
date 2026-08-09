package com.github.drafael.chat4j.chat.export.pdf;

import static com.github.drafael.chat4j.provider.support.ProcessCommandSupport.applyEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

final class PdfExportProcessRunner {

    static final String DIRECT_EXECUTABLE_ERROR = "PDF export tools must be directly executable without a command shell";
    private static final int MAX_DIAGNOSTIC_LENGTH = 8_000;
    private static final Duration OUTPUT_JOIN_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration OUTPUT_CLOSE_JOIN_TIMEOUT = Duration.ofMillis(500);
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(2);

    Outcome run(
            @NonNull List<String> command,
            @NonNull Path workspace,
            @NonNull Map<String, String> environment,
            @NonNull BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        return run(command, workspace, environment, cancelled, Duration.ZERO, "chat4j-pdf-process-output");
    }

    Outcome run(
            @NonNull List<String> command,
            @NonNull Path workspace,
            @NonNull Map<String, String> environment,
            @NonNull BooleanSupplier cancelled,
            @NonNull Duration timeout,
            @NonNull String outputThreadName
    ) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) {
            return Outcome.cancelledOutcome();
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workspace.toFile());
        processBuilder.redirectErrorStream(true);
        applyEnvironment(processBuilder, environment);
        String resolvedExecutable = processBuilder.command().getFirst().toLowerCase(Locale.ROOT);
        if (resolvedExecutable.endsWith(".cmd") || resolvedExecutable.endsWith(".bat")) {
            throw new IOException(DIRECT_EXECUTABLE_ERROR);
        }
        Process process = processBuilder.start();
        var diagnostics = new AtomicReference<>("");
        Thread outputReader = Thread.ofVirtual().name(outputThreadName).start(() ->
                diagnostics.set(readBounded(process.getInputStream()))
        );
        boolean hasTimeout = !timeout.isZero();
        long deadlineNanos = hasTimeout ? System.nanoTime() + timeout.toNanos() : 0;
        Map<Long, ProcessHandle> trackedDescendants = new LinkedHashMap<>();
        try {
            while (true) {
                trackDescendants(process, trackedDescendants);
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    trackDescendants(process, trackedDescendants);
                    destroyHandles(List.copyOf(trackedDescendants.values()));
                    joinOutputReader(outputReader, process);
                    return Outcome.completed(process.exitValue(), StringUtils.trimToEmpty(diagnostics.get()));
                }
                if (cancelled.getAsBoolean()) {
                    destroyProcessTree(process, trackedDescendants);
                    joinOutputReader(outputReader, process);
                    return Outcome.cancelledOutcome();
                }
                if (hasTimeout && System.nanoTime() - deadlineNanos >= 0) {
                    destroyProcessTree(process, trackedDescendants);
                    joinOutputReader(outputReader, process);
                    return Outcome.timedOut(StringUtils.trimToEmpty(diagnostics.get()));
                }
            }
        } catch (InterruptedException e) {
            destroyProcessTree(process, trackedDescendants);
            joinOutputReader(outputReader, process);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) {
                destroyProcessTree(process, trackedDescendants);
            } else {
                destroyHandles(List.copyOf(trackedDescendants.values()));
            }
        }
    }

    private String readBounded(InputStream input) {
        byte[] buffer = new byte[4_096];
        var diagnostics = new ByteArrayOutputStream(MAX_DIAGNOSTIC_LENGTH);
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                int remaining = MAX_DIAGNOSTIC_LENGTH - diagnostics.size();
                if (remaining > 0) {
                    diagnostics.write(buffer, 0, Math.min(count, remaining));
                }
            }
        } catch (IOException e) {
            if (diagnostics.size() == 0) {
                return StringUtils.defaultString(e.getMessage());
            }
        }
        return diagnostics.toString(StandardCharsets.UTF_8);
    }

    private void joinOutputReader(Thread outputReader, Process process) throws InterruptedException {
        outputReader.join(OUTPUT_JOIN_TIMEOUT);
        if (!outputReader.isAlive()) {
            return;
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        outputReader.join(OUTPUT_CLOSE_JOIN_TIMEOUT);
        if (outputReader.isAlive()) {
            outputReader.interrupt();
        }
    }

    private void trackDescendants(Process process, Map<Long, ProcessHandle> trackedDescendants) {
        process.descendants().forEach(handle -> trackedDescendants.putIfAbsent(handle.pid(), handle));
    }

    private void destroyProcessTree(Process process, Map<Long, ProcessHandle> trackedDescendants) {
        trackDescendants(process, trackedDescendants);
        List<ProcessHandle> ownedProcesses = Stream.concat(
                        trackedDescendants.values().stream(),
                        Stream.of(process.toHandle())
                )
                .distinct()
                .toList();
        destroyHandles(ownedProcesses);
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
    }

    private void destroyHandles(List<ProcessHandle> handles) {
        List<ProcessHandle> running = handles.stream()
                .filter(ProcessHandle::isAlive)
                .distinct()
                .toList();
        running.forEach(ProcessHandle::destroy);
        awaitExit(running);
        List<ProcessHandle> survivors = running.stream().filter(ProcessHandle::isAlive).toList();
        survivors.forEach(ProcessHandle::destroyForcibly);
        awaitExit(survivors);
    }

    private void awaitExit(List<ProcessHandle> handles) {
        try {
            CompletableFuture.allOf(
                    handles.stream().map(ProcessHandle::onExit).toArray(CompletableFuture<?>[]::new)
            ).get(PROCESS_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    enum Status {
        COMPLETED,
        CANCELLED,
        TIMED_OUT
    }

    record Outcome(Status status, int exitCode, String diagnostics) {

        static Outcome completed(int exitCode, String diagnostics) {
            return new Outcome(Status.COMPLETED, exitCode, diagnostics);
        }

        static Outcome cancelledOutcome() {
            return new Outcome(Status.CANCELLED, -1, "");
        }

        static Outcome timedOut(String diagnostics) {
            return new Outcome(Status.TIMED_OUT, -1, diagnostics);
        }

        boolean completedSuccessfully() {
            return status == Status.COMPLETED && exitCode == 0;
        }
    }
}
