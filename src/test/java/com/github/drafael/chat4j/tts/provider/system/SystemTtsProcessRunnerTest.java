package com.github.drafael.chat4j.tts.provider.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemTtsProcessRunnerTest {

    @Test
    @DisplayName("Completed commands remain bounded when a descendant keeps stdout open")
    void run_whenReaderDoesNotReachEof_closesStreamsAndTimesOut() {
        var stdout = new CloseBlockingInputStream();
        var stderr = new CloseTrackingInputStream("");
        var process = new FakeProcess(stdout, stderr, false, 0);
        var subject = new SystemTtsProcessRunner(ignored -> process);

        assertThatThrownBy(() -> subject.run(List.of("tts"), Map.of(), Duration.ofMillis(100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("System Text to Speech command timed out.");

        assertThat(stdout.closed()).isTrue();
        assertThat(stderr.closed()).isTrue();
        assertThat(process.outputClosed()).isTrue();
    }

    @Test
    @DisplayName("Interrupted commands destroy the process, close streams, and preserve interruption")
    void run_whenInterrupted_cleansUpAndPreservesInterrupt() {
        var stdout = new CloseTrackingInputStream("");
        var stderr = new CloseTrackingInputStream("");
        var process = new FakeProcess(stdout, stderr, true, 0);
        var subject = new SystemTtsProcessRunner(ignored -> process);

        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> subject.run(List.of("tts"), Map.of(), Duration.ofSeconds(1)))
                    .isInstanceOf(InterruptedException.class);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(process.destroyed()).isTrue();
            assertThat(stdout.closed()).isTrue();
            assertThat(stderr.closed()).isTrue();
            assertThat(process.outputClosed()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Successful commands return bounded stdout and stderr")
    void run_whenCommandCompletes_returnsCapturedOutput() throws Exception {
        var process = new FakeProcess(
                new CloseTrackingInputStream(" stdout "),
                new CloseTrackingInputStream(" stderr "),
                false,
                3
        );
        var subject = new SystemTtsProcessRunner(ignored -> process);

        SystemTtsCommandResult result = subject.run(List.of("tts"), Map.of("LANG", "en_US"), Duration.ofSeconds(1));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEqualTo("stdout");
        assertThat(result.stderr()).isEqualTo("stderr");
    }

    private static final class FakeProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final CloseTrackingOutputStream stdin = new CloseTrackingOutputStream();
        private final AtomicBoolean alive;
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final int exitCode;

        private FakeProcess(InputStream stdout, InputStream stderr, boolean alive, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.alive = new AtomicBoolean(alive);
            this.exitCode = exitCode;
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
        public int waitFor() throws InterruptedException {
            while (alive.get()) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (!alive.get()) {
                return true;
            }
            unit.sleep(timeout);
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        private boolean destroyed() {
            return destroyed.get();
        }

        private boolean outputClosed() {
            return stdin.closed();
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private CloseTrackingInputStream(String content) {
            super(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }

    private static final class CloseBlockingInputStream extends InputStream {
        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            return -1;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }

        private synchronized boolean closed() {
            return closed;
        }
    }

    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }
}
