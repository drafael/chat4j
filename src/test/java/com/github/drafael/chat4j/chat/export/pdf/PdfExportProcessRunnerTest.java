package com.github.drafael.chat4j.chat.export.pdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfExportProcessRunnerTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("A completed process returns its exit status and bounded diagnostics")
    void run_whenProcessCompletes_returnsExitStatusAndDiagnostics() throws Exception {
        var subject = new PdfExportProcessRunner();

        PdfExportProcessRunner.Outcome outcome = subject.run(
                fixtureCommand("success"),
                tempDirectory,
                Map.of(),
                () -> false
        );

        assertThat(outcome.status()).isEqualTo(PdfExportProcessRunner.Status.COMPLETED);
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.diagnostics()).isEqualTo("fixture-success");
        assertThat(outcome.completedSuccessfully()).isTrue();
    }

    @Test
    @DisplayName("A nonzero process remains a completed outcome for caller-specific error handling")
    void run_whenProcessExitsNonzero_returnsCompletedFailure() throws Exception {
        var subject = new PdfExportProcessRunner();

        PdfExportProcessRunner.Outcome outcome = subject.run(
                fixtureCommand("failure"),
                tempDirectory,
                Map.of(),
                () -> false
        );

        assertThat(outcome.status()).isEqualTo(PdfExportProcessRunner.Status.COMPLETED);
        assertThat(outcome.exitCode()).isEqualTo(7);
        assertThat(outcome.completedSuccessfully()).isFalse();
    }

    @Test
    @DisplayName("Cancellation terminates a running process and returns a cancelled outcome")
    void run_whenCancellationArrives_terminatesProcess() throws Exception {
        Path pidFile = tempDirectory.resolve("cancelled.pid");
        var subject = new PdfExportProcessRunner();
        long pid = -1;
        try {
            PdfExportProcessRunner.Outcome outcome = subject.run(
                    fixtureCommand("signal-wait", pidFile.toString()),
                    tempDirectory,
                    Map.of(),
                    () -> Files.exists(pidFile),
                    Duration.ofSeconds(5),
                    "chat4j-process-test-output"
            );
            pid = Long.parseLong(Files.readString(pidFile));

            assertThat(outcome.status()).isEqualTo(PdfExportProcessRunner.Status.CANCELLED);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            if (pid > 0) {
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    @DisplayName("Descendants are terminated even when the direct process exits normally first")
    void run_whenParentExitsBeforeChild_terminatesTrackedDescendant() throws Exception {
        Path pidFile = tempDirectory.resolve("child.pid");
        var subject = new PdfExportProcessRunner();
        long childPid = -1;
        try {
            PdfExportProcessRunner.Outcome outcome = subject.run(
                    fixtureCommand("spawn-child", pidFile.toString()),
                    tempDirectory,
                    Map.of(),
                    () -> false
            );
            childPid = Long.parseLong(Files.readString(pidFile));

            assertThat(outcome.completedSuccessfully()).isTrue();
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            if (childPid > 0) {
                ProcessHandle.of(childPid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    @DisplayName("Interrupting the waiting thread terminates the process and restores interruption")
    void run_whenWaitingThreadIsInterrupted_terminatesProcessAndPropagates() throws Exception {
        Path started = tempDirectory.resolve("started");
        var subject = new PdfExportProcessRunner();
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                subject.run(
                        fixtureCommand("signal-wait", started.toString()),
                        tempDirectory,
                        Map.of(),
                        () -> false
                );
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            awaitFile(started);

            worker.interrupt();
            worker.join(Duration.ofSeconds(5));

            long pid = Long.parseLong(Files.readString(started));
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    @DisplayName("A process that exceeds its deadline is terminated and reported as timed out")
    void run_whenDeadlineExpires_returnsTimedOutOutcome() throws Exception {
        var subject = new PdfExportProcessRunner();

        PdfExportProcessRunner.Outcome outcome = subject.run(
                fixtureCommand("wait"),
                tempDirectory,
                Map.of(),
                () -> false,
                Duration.ofMillis(200),
                "chat4j-process-test-output"
        );

        assertThat(outcome.status()).isEqualTo(PdfExportProcessRunner.Status.TIMED_OUT);
    }

    @Test
    @DisplayName("Process environment additions reach the executable without invoking a shell")
    void run_whenEnvironmentIsProvided_passesValuesDirectly() throws Exception {
        var subject = new PdfExportProcessRunner();

        PdfExportProcessRunner.Outcome outcome = subject.run(
                fixtureCommand("environment"),
                tempDirectory,
                Map.of("CHAT4J_PROCESS_FIXTURE", "direct-value"),
                () -> false
        );

        assertThat(outcome.diagnostics()).isEqualTo("direct-value");
    }

    @Test
    @DisplayName("The supplied environment replaces inherited variables instead of exposing credentials")
    void run_whenEnvironmentIsApplied_clearsInheritedEnvironment() throws Exception {
        var subject = new PdfExportProcessRunner();

        PdfExportProcessRunner.Outcome outcome = subject.run(
                fixtureCommand("path-absent"),
                tempDirectory,
                Map.of("CHAT4J_PROCESS_FIXTURE", "direct-value"),
                () -> false
        );

        assertThat(outcome.diagnostics()).isEqualTo("true");
    }

    @Test
    @DisplayName("A bare command resolves against the supplied PATH before process startup")
    void run_whenCommandIsBare_resolvesSuppliedPath() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path executable = tempDirectory.resolve("pdf-process-fixture");
        Files.writeString(executable, "#!/bin/sh\nprintf path-resolved");
        Files.setPosixFilePermissions(executable, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        var subject = new PdfExportProcessRunner();

        PdfExportProcessRunner.Outcome outcome = subject.run(
                List.of("pdf-process-fixture"),
                tempDirectory,
                Map.of("PATH", tempDirectory.toString()),
                () -> false
        );

        assertThat(outcome.diagnostics()).isEqualTo("path-resolved");
    }

    @Test
    @DisplayName("Command shell launchers are rejected before process startup")
    void run_whenExecutableIsCommandScript_rejectsShellLauncher() throws Exception {
        Path launcher = tempDirectory.resolve("mmdc.cmd");
        Files.writeString(launcher, "echo unsupported");
        var subject = new PdfExportProcessRunner();

        assertThatThrownBy(() -> subject.run(
                List.of(launcher.toString()),
                tempDirectory,
                Map.of(),
                () -> false
        )).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("directly executable");
    }

    @Test
    @DisplayName("Process diagnostics are drained without exceeding the publication bound")
    void run_whenOutputIsLarge_boundsCapturedDiagnostics() throws Exception {
        var subject = new PdfExportProcessRunner();

        PdfExportProcessRunner.Outcome outcome = subject.run(
                fixtureCommand("large-output"),
                tempDirectory,
                Map.of(),
                () -> false
        );

        assertThat(outcome.diagnostics()).hasSize(8_000);
    }

    private List<String> fixtureCommand(String mode, String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProcessFixture.class.getName(),
                mode
        ));
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(path) && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
        assertThat(path).exists();
    }

    private static Path javaExecutable() {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(executable) ? executable : executable.resolveSibling("java.exe");
    }

    static final class ProcessFixture {

        private ProcessFixture() {
        }

        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "success" -> System.out.print("fixture-success");
                case "failure" -> System.exit(7);
                case "wait" -> Thread.sleep(Duration.ofSeconds(30));
                case "signal-wait" -> {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(Duration.ofSeconds(30));
                }
                case "spawn-child" -> {
                    Process child = new ProcessBuilder(
                            javaExecutable().toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            ProcessFixture.class.getName(),
                            "wait"
                    ).start();
                    Files.writeString(Path.of(arguments[1]), Long.toString(child.pid()));
                    Thread.sleep(Duration.ofMillis(500));
                }
                case "environment" -> System.out.print(System.getenv("CHAT4J_PROCESS_FIXTURE"));
                case "path-absent" -> System.out.print(System.getenv("PATH") == null);
                case "large-output" -> System.out.print("x".repeat(20_000));
                default -> throw new IllegalArgumentException("Unknown fixture mode");
            }
        }
    }
}
