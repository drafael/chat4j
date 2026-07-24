package com.github.drafael.chat4j.tts.provider.system;

import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.emptyMap;

class SystemTtsProcessRunner {

    private static final int OUTPUT_LIMIT_BYTES = 65_536;
    private static final Duration PROCESS_CLEANUP_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READER_CLEANUP_TIMEOUT = Duration.ofMillis(250);

    private final ProcessStarter processStarter;

    SystemTtsProcessRunner() {
        this(ProcessBuilder::start);
    }

    SystemTtsProcessRunner(ProcessStarter processStarter) {
        this.processStarter = processStarter;
    }

    SystemTtsCommandResult run(List<String> command, Map<String, String> environment, Duration timeout) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Map<String, String> processEnvironment = processBuilder.environment();
        processEnvironment.clear();
        processEnvironment.putAll(environment == null ? emptyMap() : environment);

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Process process = null;
        ReaderTask stdoutReader = null;
        ReaderTask stderrReader = null;
        boolean interrupted = false;
        try {
            process = processStarter.start(processBuilder);
            stdoutReader = new ReaderTask(process.getInputStream());
            stderrReader = new ReaderTask(process.getErrorStream());
            stdoutReader.start();
            stderrReader.start();

            if (!process.waitFor(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("System Text to Speech command timed out.");
            }

            String stdout = stdoutReader.await(deadlineNanos);
            String stderr = stderrReader.await(deadlineNanos);
            return new SystemTtsCommandResult(process.exitValue(), stdout, stderr);
        } catch (TimeoutException e) {
            throw new IllegalStateException("System Text to Speech command timed out.", e);
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            destroy(process);
            closeProcessStreams(process);
            stopReader(stdoutReader);
            stopReader(stderrReader);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long remainingNanos(long deadlineNanos) throws TimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("System Text to Speech command deadline elapsed.");
        }
        return remaining;
    }

    private static String readBounded(InputStream stream) throws IOException {
        byte[] buffer = new byte[4096];
        var output = new ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            int remaining = OUTPUT_LIMIT_BYTES - total;
            if (remaining <= 0) {
                continue;
            }
            int copied = Math.min(read, remaining);
            output.write(buffer, 0, copied);
            total += copied;
        }
        return StringUtils.trimToEmpty(output.toString(StandardCharsets.UTF_8));
    }

    private static void stopReader(ReaderTask reader) {
        if (reader == null) {
            return;
        }
        reader.cancel();
        reader.join(READER_CLEANUP_TIMEOUT);
    }

    private static void closeProcessStreams(Process process) {
        if (process == null) {
            return;
        }
        close(process.getOutputStream());
        close(process.getInputStream());
        close(process.getErrorStream());
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static void destroy(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(PROCESS_CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(PROCESS_CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    private static final class ReaderTask {
        private final FutureTask<String> task;
        private final Thread thread;

        private ReaderTask(InputStream stream) {
            task = new FutureTask<>(() -> readBounded(stream));
            thread = Thread.ofVirtual().unstarted(task);
        }

        void start() {
            thread.start();
        }

        String await(long deadlineNanos) throws InterruptedException, ExecutionException, TimeoutException {
            return task.isDone()
                    ? task.get()
                    : task.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        }

        void cancel() {
            task.cancel(true);
            thread.interrupt();
        }

        void join(Duration timeout) {
            boolean restoreInterrupt = Thread.interrupted();
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            try {
                while (thread.isAlive()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        break;
                    }
                    try {
                        thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    } catch (InterruptedException e) {
                        restoreInterrupt = true;
                    }
                }
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
