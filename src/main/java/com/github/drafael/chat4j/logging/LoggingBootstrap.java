package com.github.drafael.chat4j.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.github.drafael.chat4j.storage.StoragePaths;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static java.util.Collections.emptyMap;

public final class LoggingBootstrap {

    static final String LOG_LEVEL_PROPERTY = "chat4j.log.level";
    static final String LOG_LEVEL_ENV_VAR = "CHAT4J_LOG_LEVEL";
    static final String CHAT4J_LOGGER_NAME = "com.github.drafael.chat4j";
    static final String LOG_DIRECTORY_PROPERTY = "chat4j.log.dir";
    static final Level DEFAULT_LEVEL = Level.INFO;

    private LoggingBootstrap() {
    }

    public static void initialize() {
        initialize(System.getenv(), emptyMap());
    }

    public static void initialize(@NonNull Map<String, String> shellEnvironment) {
        initialize(System.getenv(), shellEnvironment);
    }

    static void initialize(Map<String, String> processEnvironment, Map<String, String> shellEnvironment) {
        ensureLogDirectoryConfigured();

        String systemPropertyValue = System.getProperty(LOG_LEVEL_PROPERTY);
        String processEnvironmentValue = readEnvironmentValue(processEnvironment, LOG_LEVEL_ENV_VAR);
        String shellEnvironmentValue = readEnvironmentValue(shellEnvironment, LOG_LEVEL_ENV_VAR);
        Level level = resolveLevel(systemPropertyValue, processEnvironmentValue, shellEnvironmentValue);
        applyChat4jLevel(level);
    }

    static Level resolveLevel(String systemPropertyValue, String environmentValue, String shellEnvironmentValue) {
        String configuredValue = StringUtils.firstNonBlank(systemPropertyValue, environmentValue, shellEnvironmentValue);
        if (StringUtils.isBlank(configuredValue)) {
            return DEFAULT_LEVEL;
        }

        return Level.toLevel(configuredValue.toUpperCase(Locale.ROOT), DEFAULT_LEVEL);
    }

    static void applyChat4jLevel(Level level) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(CHAT4J_LOGGER_NAME).setLevel(level == null ? DEFAULT_LEVEL : level);
    }

    private static String readEnvironmentValue(Map<String, String> environment, String key) {
        if (environment == null) {
            return null;
        }

        return environment.get(key);
    }

    private static void ensureLogDirectoryConfigured() {
        if (StringUtils.isNotBlank(System.getProperty(LOG_DIRECTORY_PROPERTY))) {
            return;
        }

        try {
            Path logDirectory = StoragePaths.defaultPaths().appConfigDirectory().resolve("logs");
            Files.createDirectories(logDirectory);
            System.setProperty(LOG_DIRECTORY_PROPERTY, logDirectory.toString());
        } catch (Exception ignored) {
            // keep default logback directory fallback behavior
        }
    }
}
