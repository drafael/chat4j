package com.github.drafael.chat4j.tts.provider.system;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyMap;

class SystemTtsProcessRunner {

    private static final int OUTPUT_LIMIT_BYTES = 65_536;

    SystemTtsCommandResult run(List<String> command, Map<String, String> environment, Duration timeout) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Map<String, String> processEnvironment = processBuilder.environment();
        processEnvironment.clear();
        processEnvironment.putAll(environment == null ? emptyMap() : environment);

        Process process = processBuilder.start();
        Future<String> stdout = startReader(process.getInputStream());
        Future<String> stderr = startReader(process.getErrorStream());
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            destroy(process);
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            destroy(process);
            throw new IllegalStateException("System Text to Speech command timed out.");
        }
        return new SystemTtsCommandResult(process.exitValue(), futureValue(stdout), futureValue(stderr));
    }

    private static Future<String> startReader(InputStream stream) {
        FutureTask<String> task = new FutureTask<>(() -> readBounded(stream));
        Thread.startVirtualThread(task);
        return task;
    }

    private static String futureValue(Future<String> future) throws InterruptedException, ExecutionException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static String readBounded(InputStream stream) throws IOException {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                int remaining = OUTPUT_LIMIT_BYTES - total;
                if (remaining > 0) {
                    int copied = Math.min(read, remaining);
                    output.write(buffer, 0, copied);
                    total += copied;
                }
            }
            return StringUtils.trimToEmpty(output.toString(StandardCharsets.UTF_8));
        }
    }

    private static void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
