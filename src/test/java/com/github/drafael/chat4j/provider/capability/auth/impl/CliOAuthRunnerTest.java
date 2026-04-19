package com.github.drafael.chat4j.provider.capability.auth.impl;

import com.github.drafael.chat4j.provider.api.OAuthCliSpec;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import static org.assertj.core.api.Assertions.assertThat;

class CliOAuthRunnerTest {

    private static final CliOAuthRunner subject = new CliOAuthRunner();

    @AfterEach
    void tearDown() {
        CredentialResolver.init(Map.of());
    }

    @Test
    @DisplayName("Status command success marks provider as authorized")
    void checkStatus_whenStatusCommandSucceeds_returnsAuthorized() {
        OAuthCliSpec spec = new OAuthCliSpec(
                command("echo authorized"),
                command("echo login"),
                command("echo logout"),
                command("echo token"));

        CliOAuthRunner.OAuthStatus status = subject.checkStatus(spec);

        assertThat(status.authorized()).isTrue();
        assertThat(status.message()).contains("Authorized");
    }

    @Test
    @DisplayName("Status command failure marks provider as unauthorized")
    void checkStatus_whenStatusCommandFails_returnsUnauthorized() {
        OAuthCliSpec spec = new OAuthCliSpec(
                command("exit 2"),
                command("echo login"),
                command("echo logout"),
                command("echo token"));

        CliOAuthRunner.OAuthStatus status = subject.checkStatus(spec);

        assertThat(status.authorized()).isFalse();
        assertThat(status.message()).contains("Unauthorized");
    }

    @Test
    @DisplayName("Status command reports missing CLI without raw process exception text")
    void checkStatus_whenCliIsMissing_returnsUnavailableWithFriendlyMessage() {
        OAuthCliSpec spec = new OAuthCliSpec(
                List.of("chat4j-missing-cli-%s".formatted(UUID.randomUUID())),
                command("echo login"),
                command("echo logout"),
                command("echo token"));

        CliOAuthRunner.OAuthStatus status = subject.checkStatus(spec);

        assertThat(status.authorized()).isFalse();
        assertThat(status.cliAvailable()).isFalse();
        assertThat(status.message())
                .contains("not installed or not on PATH")
                .doesNotContain("Cannot run program");
    }

    @Test
    @DisplayName("Status command can resolve CLI from loaded shell PATH")
    void checkStatus_whenCliIsOnlyInShellPath_returnsAuthorized() throws Exception {
        Path tempDir = Files.createTempDirectory("chat4j-cli-path");
        String commandName = "chat4j-cli-%s".formatted(UUID.randomUUID());
        Path executable = tempDir.resolve(commandName);

        try {
            Files.writeString(executable, "#!/bin/sh\necho authorized\n", StandardCharsets.UTF_8);
            boolean executableSet = executable.toFile().setExecutable(true);
            assertThat(executableSet).isTrue();

            String currentPath = System.getenv("PATH");
            String shellPath = StringUtils.isBlank(currentPath)
                    ? tempDir.toString()
                    : "%s:%s".formatted(tempDir, currentPath);
            CredentialResolver.init(Map.of("PATH", shellPath));

            OAuthCliSpec spec = new OAuthCliSpec(
                    List.of(commandName),
                    command("echo login"),
                    command("echo logout"),
                    command("echo token"));

            CliOAuthRunner.OAuthStatus status = subject.checkStatus(spec);

            assertThat(status.authorized()).isTrue();
            assertThat(status.cliAvailable()).isTrue();
        } finally {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    @DisplayName("Token command output supports Bearer prefix")
    void resolveBearerToken_whenCommandReturnsBearerPrefix_returnsTrimmedToken() {
        OAuthCliSpec spec = new OAuthCliSpec(
                command("echo authorized"),
                command("echo login"),
                command("echo logout"),
                command("echo 'Bearer test-token'"));

        String token = subject.resolveBearerToken(spec);

        assertThat(token).isEqualTo("test-token");
    }

    private static List<String> command(String script) {
        return List.of("/bin/sh", "-c", script);
    }
}
