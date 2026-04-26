package com.github.drafael.chat4j.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class LoggingBootstrapTest {

    private Logger chat4jLogger;
    private Level originalLevel;
    private String originalLogDirectoryProperty;

    @BeforeEach
    void setUp() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        chat4jLogger = loggerContext.getLogger(LoggingBootstrap.CHAT4J_LOGGER_NAME);
        originalLevel = chat4jLogger.getLevel();
        originalLogDirectoryProperty = System.getProperty(LoggingBootstrap.LOG_DIRECTORY_PROPERTY);
        System.clearProperty(LoggingBootstrap.LOG_LEVEL_PROPERTY);
        System.clearProperty(LoggingBootstrap.LOG_DIRECTORY_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(LoggingBootstrap.LOG_LEVEL_PROPERTY);
        if (originalLogDirectoryProperty == null) {
            System.clearProperty(LoggingBootstrap.LOG_DIRECTORY_PROPERTY);
        } else {
            System.setProperty(LoggingBootstrap.LOG_DIRECTORY_PROPERTY, originalLogDirectoryProperty);
        }
        chat4jLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("System property log level takes precedence over environment values")
    void initialize_whenSystemPropertyConfigured_appliesSystemPropertyLevel() {
        System.setProperty(LoggingBootstrap.LOG_LEVEL_PROPERTY, "DEBUG");

        LoggingBootstrap.initialize(
                Map.of(LoggingBootstrap.LOG_LEVEL_ENV_VAR, "ERROR"),
                Map.of(LoggingBootstrap.LOG_LEVEL_ENV_VAR, "WARN")
        );

        assertThat(chat4jLogger.getLevel()).isEqualTo(Level.DEBUG);
    }

    @Test
    @DisplayName("Shell environment value is used when property and process env are absent")
    void initialize_whenOnlyShellEnvironmentConfigured_appliesShellLevel() {
        LoggingBootstrap.initialize(
                emptyMap(),
                Map.of(LoggingBootstrap.LOG_LEVEL_ENV_VAR, "TRACE")
        );

        assertThat(chat4jLogger.getLevel()).isEqualTo(Level.TRACE);
    }

    @Test
    @DisplayName("Invalid configured level silently falls back to INFO")
    void initialize_whenConfiguredLevelInvalid_appliesInfoLevel() {
        LoggingBootstrap.initialize(
                Map.of(LoggingBootstrap.LOG_LEVEL_ENV_VAR, "VERBOSE"),
                emptyMap()
        );

        assertThat(chat4jLogger.getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("Resolver keeps precedence of property then env then shell")
    void resolveLevel_whenMultipleSourcesProvided_prefersConfiguredOrder() {
        Level resolved = LoggingBootstrap.resolveLevel("warn", "debug", "trace");

        assertThat(resolved).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("Initialize sets default log directory property when missing")
    void initialize_whenLogDirectoryPropertyMissing_setsDefaultLogDirectoryProperty() {
        LoggingBootstrap.initialize(emptyMap(), emptyMap());

        assertThat(System.getProperty(LoggingBootstrap.LOG_DIRECTORY_PROPERTY)).isNotBlank();
    }
}
