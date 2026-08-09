package com.github.drafael.chat4j.chat.export.pdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumExecutableResolverTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("Chromium discovery accepts a directly executable Puppeteer environment override")
    void discover_whenPuppeteerExecutableIsValid_returnsAbsolutePath() throws Exception {
        Path chromium = executable("chromium");

        assertThat(ChromiumExecutableResolver.discover(Map.of(
                ChromiumExecutableResolver.PUPPETEER_EXECUTABLE_PATH,
                chromium.toString()
        ))).contains(chromium.toString());
    }

    @Test
    @DisplayName("An explicit Chromium setting replaces an inherited Puppeteer executable")
    void withExecutable_whenExplicitPathExists_replacesEnvironmentValue() {
        Path inherited = tempDirectory.resolve("inherited-chrome").toAbsolutePath().normalize();
        Path selected = tempDirectory.resolve("selected-chrome").toAbsolutePath().normalize();

        assertThat(ChromiumExecutableResolver.withExecutable(
                Map.of(
                        "PATH", tempDirectory.toString(),
                        ChromiumExecutableResolver.PUPPETEER_EXECUTABLE_PATH, inherited.toString()
                ),
                selected.toString()
        )).containsEntry(ChromiumExecutableResolver.PUPPETEER_EXECUTABLE_PATH, selected.toString());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("A blank Chromium setting adds an executable discovered from PATH")
    void withExecutable_whenChromiumIsOnPath_addsPuppeteerExecutable() throws Exception {
        Path chromium = executable("google-chrome");

        assertThat(ChromiumExecutableResolver.withExecutable(
                Map.of("PATH", tempDirectory.toString()),
                ""
        )).containsEntry(ChromiumExecutableResolver.PUPPETEER_EXECUTABLE_PATH, chromium.toString());
    }

    private Path executable(String name) throws Exception {
        Path executable = Files.writeString(tempDirectory.resolve(name), "test").toAbsolutePath().normalize();
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
