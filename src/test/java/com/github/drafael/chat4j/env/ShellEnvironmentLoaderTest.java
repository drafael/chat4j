package com.github.drafael.chat4j.env;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ShellEnvironmentLoaderTest {

    @Test
    @DisplayName("Interactive shell command includes the interactive login flags")
    void shellEnvCommand_whenInteractive_includesInteractiveFlag() {
        assertThat(ShellEnvironmentLoader.shellEnvCommand("/bin/zsh", true))
                .containsExactly("/bin/zsh", "-l", "-i", "-c", "env");
    }

    @Test
    @DisplayName("Login-only fallback omits the interactive flag")
    void shellEnvCommand_whenLoginOnly_omitsInteractiveFlag() {
        assertThat(ShellEnvironmentLoader.shellEnvCommand("/bin/zsh", false))
                .containsExactly("/bin/zsh", "-l", "-c", "env");
    }

    @Test
    @DisplayName("Environment parsing validates names and preserves values containing equals signs")
    void parseEnvOutput_whenInputContainsValidAndInvalidLines_returnsOnlyValidEntries() {
        var output = "PATH=/usr/bin\nTOKEN=abc=def==\nINVALID-NAME=value\n1INVALID=value\n=blank\nNO_EQUALS";

        var parsed = ShellEnvironmentLoader.parseEnvOutput(output);

        assertThat(parsed)
                .containsEntry("PATH", "/usr/bin")
                .containsEntry("TOKEN", "abc=def==")
                .doesNotContainKeys("INVALID-NAME", "1INVALID", "");
    }

    @Test
    @DisplayName("Environment parsing keeps the last duplicate value")
    void parseEnvOutput_whenDuplicateKeysExist_keepsLastValue() {
        var parsed = ShellEnvironmentLoader.parseEnvOutput("OPENAI_API_KEY=first\nOPENAI_API_KEY=second");

        assertThat(parsed).containsExactly(Map.entry("OPENAI_API_KEY", "second"));
    }

    @Test
    @DisplayName("Nonzero shell exits discard valid-looking output from both attempts")
    void loadFromLoginShell_whenProcessFails_discardsOutput() {
        var starts = new AtomicInteger();

        Map<String, String> result = ShellEnvironmentLoader.loadFromLoginShell(
                command -> {
                    starts.incrementAndGet();
                    return FakeProcess.completed("OPENAI_API_KEY=secret", "diagnostic-secret", 1);
                },
                Duration.ofMillis(100)
        );

        assertThat(result).isEmpty();
        assertThat(starts).hasValue(2);
    }

    @Test
    @DisplayName("Shell diagnostics exclude process exception messages and command output")
    void loadFromLoginShell_whenAttemptsFail_keepsSentinelValuesOutOfLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ShellEnvironmentLoader.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var starts = new AtomicInteger();
            ShellEnvironmentLoader.loadFromLoginShell(
                    command -> {
                        if (starts.getAndIncrement() == 0) {
                            return FakeProcess.completed(
                                    "OPENAI_API_KEY=secret",
                                    "diagnostic-secret",
                                    1
                            );
                        }
                        throw new IOException("start-message-secret");
                    },
                    Duration.ofMillis(100)
            );

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList()
                    .toString();
            assertThat(logs)
                    .doesNotContain("start-message-secret")
                    .doesNotContain("OPENAI_API_KEY=secret")
                    .doesNotContain("diagnostic-secret");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Successful shell diagnostics exclude key names, values, stderr, and shell paths")
    void loadFromLoginShell_whenAttemptSucceeds_logsOnlySanitizedMetadata() {
        Logger logger = (Logger) LoggerFactory.getLogger(ShellEnvironmentLoader.class);
        var appender = new ListAppender<ILoggingEvent>();
        var shellPath = new AtomicReference<String>();
        appender.start();
        logger.addAppender(appender);
        try {
            Map<String, String> result = ShellEnvironmentLoader.loadFromLoginShell(
                    command -> {
                        shellPath.set(command.getFirst());
                        return FakeProcess.completed(
                                "OPENAI_API_KEY=success-secret",
                                "success-stderr-secret",
                                0
                        );
                    },
                    Duration.ofMillis(100)
            );

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList()
                    .toString();
            assertThat(result).containsEntry("OPENAI_API_KEY", "success-secret");
            assertThat(logs)
                    .doesNotContain("OPENAI_API_KEY")
                    .doesNotContain("success-secret")
                    .doesNotContain("success-stderr-secret")
                    .doesNotContain(shellPath.get());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Timed out shell attempts are destroyed and close every process stream")
    void loadFromLoginShell_whenProcessTimesOut_destroysProcessAndClosesStreams() {
        var process = FakeProcess.timedOut(true);

        Map<String, String> result = ShellEnvironmentLoader.loadFromLoginShell(
                command -> process,
                Duration.ofMillis(30)
        );

        assertThat(result).isEmpty();
        assertThat(process.destroyCalled).isTrue();
        assertThat(process.destroyForciblyCalled).isTrue();
        assertThat(process.stdin.closed).isTrue();
        assertThat(process.stdout.closed).isTrue();
        assertThat(process.stderr.closed).isTrue();
    }

    @Test
    @DisplayName("Stdout beyond one MiB is drained but never published")
    void loadFromLoginShell_whenStdoutOverflows_returnsEmptyEnvironment() {
        byte[] oversized = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        var starts = new AtomicInteger();

        Map<String, String> result = ShellEnvironmentLoader.loadFromLoginShell(
                command -> {
                    starts.incrementAndGet();
                    return FakeProcess.completed(oversized, new byte[0], 0);
                },
                Duration.ofSeconds(2)
        );

        assertThat(result).isEmpty();
        assertThat(starts).hasValue(2);
    }

    @Test
    @DisplayName("Reader completion shares the process deadline when an output pipe remains open")
    void loadFromLoginShell_whenReaderDoesNotReachEof_closesPipeAndReturnsBoundedly() {
        var process = FakeProcess.withBlockingStdout();
        long started = System.nanoTime();

        Map<String, String> result = ShellEnvironmentLoader.loadFromLoginShell(
                command -> process,
                Duration.ofMillis(40)
        );

        assertThat(result).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(process.stdout.closed).isTrue();
        assertThat(process.readerExited).isTrue();
    }

    @Test
    @DisplayName("Interrupted loading closes the process and preserves interrupt status without a fallback attempt")
    void loadFromLoginShell_whenInterrupted_preservesInterruptAndCleansUp() throws Exception {
        var process = FakeProcess.timedOut(false);
        var interrupted = new AtomicBoolean();
        var starts = new AtomicInteger();
        Thread worker = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            ShellEnvironmentLoader.loadFromLoginShell(command -> {
                starts.incrementAndGet();
                return process;
            }, Duration.ofSeconds(1));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        worker.join(2_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(interrupted).isTrue();
        assertThat(starts).hasValue(1);
        assertThat(process.stdin.closed).isTrue();
        assertThat(process.stdout.closed).isTrue();
        assertThat(process.stderr.closed).isTrue();
    }

    private static final class FakeProcess extends Process {
        private final TrackingInputStream stdout;
        private final TrackingInputStream stderr;
        private final TrackingOutputStream stdin = new TrackingOutputStream();
        private final int exitCode;
        private final boolean finishes;
        private final boolean requiresForce;
        private final AtomicBoolean readerExited;
        private volatile boolean alive;
        private volatile boolean destroyCalled;
        private volatile boolean destroyForciblyCalled;

        private FakeProcess(
                InputStream stdout,
                InputStream stderr,
                int exitCode,
                boolean finishes,
                boolean requiresForce,
                AtomicBoolean readerExited
        ) {
            this.stdout = new TrackingInputStream(stdout);
            this.stderr = new TrackingInputStream(stderr);
            this.exitCode = exitCode;
            this.finishes = finishes;
            this.requiresForce = requiresForce;
            this.readerExited = readerExited;
            alive = !finishes;
        }

        static FakeProcess completed(String stdout, String stderr, int exitCode) {
            return completed(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    stderr.getBytes(java.nio.charset.StandardCharsets.UTF_8), exitCode);
        }

        static FakeProcess completed(byte[] stdout, byte[] stderr, int exitCode) {
            return new FakeProcess(
                    new ByteArrayInputStream(stdout),
                    new ByteArrayInputStream(stderr),
                    exitCode,
                    true,
                    false,
                    new AtomicBoolean(true)
            );
        }

        static FakeProcess timedOut(boolean requiresForce) {
            return new FakeProcess(
                    InputStream.nullInputStream(),
                    InputStream.nullInputStream(),
                    0,
                    false,
                    requiresForce,
                    new AtomicBoolean(true)
            );
        }

        static FakeProcess withBlockingStdout() {
            var exited = new AtomicBoolean();
            InputStream blocking = new InputStream() {
                private final CountDownLatch closed = new CountDownLatch(1);

                @Override
                public int read() throws IOException {
                    boolean interrupted = false;
                    while (closed.getCount() > 0) {
                        try {
                            closed.await();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exited.set(true);
                    return -1;
                }

                @Override
                public void close() {
                    closed.countDown();
                }
            };
            return new FakeProcess(
                    blocking,
                    InputStream.nullInputStream(),
                    0,
                    true,
                    false,
                    exited
            );
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return finishes || !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyCalled = true;
            if (!requiresForce) {
                alive = false;
            }
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    private static final class TrackingInputStream extends InputStream {
        private final InputStream delegate;
        private volatile boolean closed;

        private TrackingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private volatile boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
