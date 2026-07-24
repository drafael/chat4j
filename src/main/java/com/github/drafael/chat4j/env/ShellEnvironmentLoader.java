package com.github.drafael.chat4j.env;

import lombok.extern.slf4j.Slf4j;
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
import java.util.regex.Pattern;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toUnmodifiableMap;

@Slf4j
public final class ShellEnvironmentLoader {
    private static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("chat4j.shellEnvTimeoutSeconds", 5L));
    private static final Duration PROCESS_CLEANUP_TIMEOUT = Duration.ofMillis(250);
    private static final int MAX_STDOUT_BYTES = 1024 * 1024;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private ShellEnvironmentLoader() {
    }

    public static Map<String, String> loadFromLoginShell() {
        return loadFromLoginShell(command -> new ProcessBuilder(command).start(), TIMEOUT);
    }

    static Map<String, String> loadFromLoginShell(ProcessStarter processStarter) {
        return loadFromLoginShell(processStarter, TIMEOUT);
    }

    static Map<String, String> loadFromLoginShell(ProcessStarter processStarter, Duration timeout) {
        String shell = StringUtils.defaultIfBlank(System.getenv("SHELL"), "/bin/zsh");

        Map<String, String> interactiveEnv = load(shell, true, processStarter, timeout);
        if (!interactiveEnv.isEmpty() || Thread.currentThread().isInterrupted()) {
            return interactiveEnv;
        }

        log.warn("Shell environment is empty after login+interactive attempt; falling back to login shell");
        Map<String, String> loginEnv = load(shell, false, processStarter, timeout);
        if (loginEnv.isEmpty()) {
            log.warn("Shell environment loading returned empty map after all attempts");
        }
        return loginEnv;
    }

    static List<String> shellEnvCommand(String shell, boolean interactive) {
        return interactive
                ? List.of(shell, "-l", "-i", "-c", "env")
                : List.of(shell, "-l", "-c", "env");
    }

    private static Map<String, String> load(
            String shell,
            boolean interactive,
            ProcessStarter processStarter,
            Duration timeout
    ) {
        String mode = interactive ? "login+interactive" : "login";
        long startedAtNanos = System.nanoTime();
        long deadlineNanos = startedAtNanos + timeout.toNanos();
        Process process = null;
        ReaderTask stdoutReader = null;
        ReaderTask stderrReader = null;
        boolean interrupted = false;
        try {
            process = processStarter.start(shellEnvCommand(shell, interactive));
            stdoutReader = ReaderTask.capture(process.getInputStream(), MAX_STDOUT_BYTES);
            stderrReader = ReaderTask.discard(process.getErrorStream());
            stdoutReader.start();
            stderrReader.start();

            if (!process.waitFor(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
                logAttemptFailure(mode, "timeout", startedAtNanos);
                return emptyMap();
            }

            ReadResult stdout = stdoutReader.await(deadlineNanos);
            stderrReader.await(deadlineNanos);
            if (process.exitValue() != 0) {
                log.warn(
                        "Shell environment loading failed: mode={} exitCode={} elapsedMs={}",
                        mode,
                        process.exitValue(),
                        elapsedMillis(startedAtNanos)
                );
                return emptyMap();
            }
            if (stdout.overflowed()) {
                logAttemptFailure(mode, "stdout_overflow", startedAtNanos);
                return emptyMap();
            }

            Map<String, String> loadedEnvironment = parseEnvOutput(stdout.content());
            log.info(
                    "Shell environment loaded: mode={} elapsedMs={} envEntries={}",
                    mode,
                    elapsedMillis(startedAtNanos),
                    loadedEnvironment.size()
            );
            return loadedEnvironment;
        } catch (InterruptedException e) {
            interrupted = true;
            logAttemptFailure(mode, "interrupted", startedAtNanos);
            return emptyMap();
        } catch (TimeoutException e) {
            logAttemptFailure(mode, "reader_timeout", startedAtNanos);
            return emptyMap();
        } catch (ExecutionException e) {
            log.warn(
                    "Shell environment loading failed: mode={} category=reader_failure exception={} elapsedMs={}",
                    mode,
                    e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName(),
                    elapsedMillis(startedAtNanos)
            );
            return emptyMap();
        } catch (Exception e) {
            log.warn(
                    "Shell environment loading failed: mode={} category=process_failure exception={} elapsedMs={}",
                    mode,
                    e.getClass().getSimpleName(),
                    elapsedMillis(startedAtNanos)
            );
            return emptyMap();
        } finally {
            destroyProcess(process);
            closeProcessStreams(process);
            stopReader(stdoutReader);
            stopReader(stderrReader);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static Map<String, String> parseEnvOutput(String output) {
        return StringUtils.defaultString(output).lines()
                .map(line -> line.split("=", 2))
                .filter(parts -> parts.length == 2 && ENVIRONMENT_NAME.matcher(parts[0]).matches())
                .collect(toUnmodifiableMap(
                        parts -> parts[0],
                        parts -> parts[1],
                        (first, second) -> second
                ));
    }

    private static long remainingNanos(long deadlineNanos) throws TimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("Shell environment attempt deadline elapsed.");
        }
        return remaining;
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

    private static void stopReader(ReaderTask reader) {
        if (reader == null) {
            return;
        }
        reader.cancel();
        reader.join(PROCESS_CLEANUP_TIMEOUT);
    }

    private static void destroyProcess(Process process) {
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

    private static void logAttemptFailure(String mode, String category, long startedAtNanos) {
        log.warn(
                "Shell environment loading failed: mode={} category={} elapsedMs={}",
                mode,
                category,
                elapsedMillis(startedAtNanos)
        );
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    private record ReadResult(String content, boolean overflowed) {
    }

    private static final class ReaderTask {
        private final FutureTask<ReadResult> task;
        private final Thread thread;

        private ReaderTask(FutureTask<ReadResult> task) {
            this.task = task;
            thread = Thread.ofVirtual().unstarted(task);
        }

        static ReaderTask capture(InputStream stream, int limit) {
            return new ReaderTask(new FutureTask<>(() -> read(stream, limit, true)));
        }

        static ReaderTask discard(InputStream stream) {
            return new ReaderTask(new FutureTask<>(() -> read(stream, 0, false)));
        }

        void start() {
            thread.start();
        }

        ReadResult await(long deadlineNanos) throws InterruptedException, ExecutionException, TimeoutException {
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

        private static ReadResult read(InputStream stream, int limit, boolean capture) throws IOException {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = capture ? new ByteArrayOutputStream(Math.min(limit, buffer.length)) : null;
            boolean overflowed = false;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (!capture) {
                    continue;
                }
                int remaining = limit - output.size();
                if (remaining > 0) {
                    output.write(buffer, 0, Math.min(remaining, count));
                }
                overflowed |= count > remaining;
            }
            String content = capture ? output.toString(StandardCharsets.UTF_8) : "";
            return new ReadResult(content, overflowed);
        }
    }
}
