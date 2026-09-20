package com.github.drafael.chat4j.logging;

import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingBootstrapProcessTest {

    private static final String APPENDER_PREFIX = "APPENDER_FILE=";
    private static final String FALLBACK_EXISTS_OUTPUT = "FALLBACK_EXISTS_AFTER_STARTUP_LOGGING=false";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Application startup initializes native diagnostics after resolving log storage")
    void initialize_whenNativeDiagnosticsLoad_usesOnlyResolvedStateDirectory() throws Exception {
        Path logbackConfiguration = Path.of("target", "classes", "logback.xml").toAbsolutePath().normalize();
        assertThat(logbackConfiguration).isRegularFile();

        Path userHome = Files.createDirectories(tempDir.resolve("home"));
        Path stateHome = Files.createDirectories(tempDir.resolve("state"));
        Path appData = Files.createDirectories(tempDir.resolve("appdata"));
        Process process = startFixture(logbackConfiguration, userHome, stateHome, appData);

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(finished).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains(FALLBACK_EXISTS_OUTPUT);
        Path expectedDirectory = (SystemUtils.IS_OS_WINDOWS ? appData : stateHome).resolve("chat4j/logs");
        String appenderFile = output.lines()
                .filter(line -> line.startsWith(APPENDER_PREFIX))
                .map(line -> line.substring(APPENDER_PREFIX.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing file appender path in output:\n" + output));
        assertThat(Path.of(appenderFile).toAbsolutePath().normalize())
                .isEqualTo(expectedDirectory.resolve("chat4j.log").toAbsolutePath().normalize());
    }

    private Process startFixture(Path logbackConfiguration, Path userHome, Path stateHome, Path appData) throws IOException {
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java"
        ).toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable,
                "-Duser.home=" + userHome,
                "-Dlogback.configurationFile=" + logbackConfiguration,
                "-cp",
                classPath,
                LoggingBootstrapFixtureMain.class.getName()
        ).redirectErrorStream(true);
        processBuilder.environment().put("XDG_CONFIG_HOME", tempDir.resolve("config").toString());
        processBuilder.environment().put("XDG_DATA_HOME", tempDir.resolve("data").toString());
        processBuilder.environment().put("XDG_CACHE_HOME", tempDir.resolve("cache").toString());
        processBuilder.environment().put("XDG_STATE_HOME", stateHome.toString());
        processBuilder.environment().put("APPDATA", appData.toString());
        return processBuilder.start();
    }
}
