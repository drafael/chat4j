package com.github.drafael.chat4j.startup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StartupFallbackLoggerTest {

    @BeforeEach
    void setUp() throws Exception {
        var logFile = Path.of(StartupFallbackLogger.fallbackLogPath());
        Files.deleteIfExists(logFile);
    }

    @Test
    @DisplayName("Fallback logger writes startup entries to bootstrap fallback log file")
    void info_whenMessageProvided_writesEntryToFallbackLog() throws Exception {
        StartupFallbackLogger.info("Startup phase started");

        var logFile = Path.of(StartupFallbackLogger.fallbackLogPath());
        var content = Files.readString(logFile);

        assertThat(content)
                .contains("INFO")
                .contains("Startup phase started");
    }

    @Test
    @DisplayName("Fallback logger writes concise exception reason for error entries")
    void error_whenThrowableProvided_writesReasonWithoutStackTrace() throws Exception {
        StartupFallbackLogger.error("Logging bootstrap failed", new IllegalStateException("boom"));

        var logFile = Path.of(StartupFallbackLogger.fallbackLogPath());
        var content = Files.readString(logFile);

        assertThat(content)
                .contains("ERROR")
                .contains("Logging bootstrap failed")
                .contains("reason=IllegalStateException: boom")
                .doesNotContain("at com.github");
    }
}
