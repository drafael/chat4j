package com.github.drafael.chat4j.provider.capability.auth.impl;

import com.github.drafael.chat4j.provider.api.OAuthCliSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CliOAuthRunnerTest {

    private static final CliOAuthRunner subject = new CliOAuthRunner();

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
