package com.github.drafael.chat4j.startup;

import com.github.drafael.chat4j.storage.StoragePaths;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/**
 * Minimal startup logger that remains available before the regular SLF4J backend is ready.
 */
public final class StartupFallbackLogger {

    private static final String FILE_NAME = "bootstrap-fallback.log";

    private StartupFallbackLogger() {
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void error(String message, Throwable throwable) {
        write("ERROR", message, throwable);
    }

    public static String fallbackLogPath() {
        return resolveFallbackLogFile().toString();
    }

    private static synchronized void write(String level, String message, Throwable throwable) {
        if (StringUtils.isBlank(message)) {
            return;
        }

        try {
            Path logFile = resolveFallbackLogFile();
            Files.createDirectories(logFile.getParent());

            String throwableMessage = throwable == null ? "" : " | reason=%s".formatted(ExceptionUtils.getMessage(throwable));
            String entry = "%s %-5s %s%s%n"
                    .formatted(OffsetDateTime.now(), level, message, throwableMessage);
            Files.writeString(
                    logFile,
                    entry,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
            // No-op: fallback logger must never fail startup.
        }
    }

    private static Path resolveFallbackLogFile() {
        try {
            return StoragePaths.defaultPaths().appConfigDirectory().resolve("logs").resolve(FILE_NAME);
        } catch (Exception ignored) {
            return Path.of(System.getProperty("user.home", "."), ".config", "chat4j", "logs", FILE_NAME);
        }
    }
}
