package com.github.drafael.chat4j.env;

import com.github.drafael.chat4j.provider.support.CredentialResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toUnmodifiableMap;

@Slf4j
public final class ShellEnvironmentLoader {
    private static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("chat4j.shellEnvTimeoutSeconds", 5L));
    private static final int STDERR_PREVIEW_MAX_LENGTH = 240;

    private ShellEnvironmentLoader() {
    }

    public static Map<String, String> loadFromLoginShell() {
        String shell = StringUtils.defaultIfBlank(System.getenv("SHELL"), "/bin/zsh");

        Map<String, String> interactiveEnv = load(shell, true);
        if (!interactiveEnv.isEmpty()) {
            return interactiveEnv;
        }

        log.warn("Shell environment is empty after login+interactive attempt; falling back to login shell");
        Map<String, String> loginEnv = load(shell, false);
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

    private static Map<String, String> load(String shell, boolean interactive) {
        String mode = interactive ? "login+interactive" : "login";
        Instant startedAt = Instant.now();

        try {
            List<String> command = shellEnvCommand(shell, interactive);
            Process process = new ProcessBuilder(command).start();

            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn(
                        "Shell environment loading timed out: mode={} shell={} timeoutSeconds={} elapsedMs={} command={}",
                        mode,
                        shell,
                        TIMEOUT.toSeconds(),
                        elapsedMillis(startedAt),
                        String.join(" ", command)
                );
                return emptyMap();
            }

            String output = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            if (process.exitValue() != 0) {
                log.warn(
                        "Shell environment loading failed: mode={} shell={} exitCode={} elapsedMs={} stderr={}",
                        mode,
                        shell,
                        process.exitValue(),
                        elapsedMillis(startedAt),
                        summarizeStderr(stderr)
                );
                return emptyMap();
            }

            Map<String, String> loadedEnv = parseEnvOutput(output);
            log.info(
                    "Shell environment loaded: mode={} shell={} elapsedMs={} envEntries={} providerKeys={}",
                    mode,
                    shell,
                    elapsedMillis(startedAt),
                    loadedEnv.size(),
                    summarizeProviderCredentialNames(loadedEnv)
            );
            if (StringUtils.isNotBlank(stderr)) {
                log.debug(
                        "Shell environment stderr output: mode={} shell={} stderr={}",
                        mode,
                        shell,
                        summarizeStderr(stderr)
                );
            }

            return loadedEnv;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "Shell environment loading interrupted: mode={} shell={} elapsedMs={} reason={}",
                    mode,
                    shell,
                    elapsedMillis(startedAt),
                    ExceptionUtils.getMessage(e)
            );
            return emptyMap();
        } catch (Exception e) {
            log.warn(
                    "Failed to load shell environment: mode={} shell={} elapsedMs={} reason={}",
                    mode,
                    shell,
                    elapsedMillis(startedAt),
                    ExceptionUtils.getMessage(e)
            );
            return emptyMap();
        }
    }

    static Map<String, String> parseEnvOutput(String output) {
        return output.lines()
                .map(line -> line.split("=", 2))
                .filter(parts -> parts.length == 2 && !parts[0].isBlank())
                .collect(toUnmodifiableMap(
                    parts -> parts[0],
                    parts -> parts[1],
                    (first, second) -> second
                ));
    }

    static String summarizeProviderCredentialNames(Map<String, String> env) {
        List<String> names = CredentialResolver.supportedProviderEnvVars().stream()
                .filter(name -> StringUtils.isNotBlank(env.get(name)))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static String readStream(InputStream stream) throws IOException {
        try (InputStreamReader streamReader = new InputStreamReader(stream, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(streamReader)) {
            return reader.lines().collect(joining("\n"));
        }
    }

    private static long elapsedMillis(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private static String summarizeStderr(String stderr) {
        String normalized = StringUtils.normalizeSpace(StringUtils.defaultString(stderr));
        return StringUtils.isBlank(normalized)
            ? "none"
            : StringUtils.abbreviate(normalized, STDERR_PREVIEW_MAX_LENGTH);
    }
}
