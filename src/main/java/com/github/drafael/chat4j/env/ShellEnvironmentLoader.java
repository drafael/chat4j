package com.github.drafael.chat4j.env;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class ShellEnvironmentLoader {

    private static final Logger LOG = Logger.getLogger(ShellEnvironmentLoader.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ShellEnvironmentLoader() {
    }

    public static Map<String, String> loadFromLoginShell() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "/bin/zsh";
        }

        try {
            var process = new ProcessBuilder(shell, "-l", "-i", "-c", "env").start();

            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warning("Shell environment loading timed out after " + TIMEOUT.toSeconds() + "s");
                return Map.of();
            }

            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            if (process.exitValue() != 0) {
                LOG.warning("Shell exited with code " + process.exitValue());
                return Map.of();
            }

            return parseEnvOutput(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Shell environment loading interrupted", e);
            return Map.of();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load shell environment", e);
            return Map.of();
        }
    }

    static Map<String, String> parseEnvOutput(String output) {
        return output.lines()
                .map(line -> line.split("=", 2))
                .filter(parts -> parts.length == 2 && !parts[0].isBlank())
                .collect(Collectors.toUnmodifiableMap(
                    parts -> parts[0],
                    parts -> parts[1],
                    (first, second) -> second
                ));
    }
}
