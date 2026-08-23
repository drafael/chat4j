package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiRedactionTest {

    @Test
    @DisplayName("Codex wire diagnostics mask every OAuth credential")
    void toString_whenCodexTokenResponseContainsSecrets_masksCredentials() {
        var response = new CodexAuthApi.TokenResponse("id-secret", "access-secret", "refresh-secret", 300);

        assertThat(response.toString())
                .doesNotContain("id-secret", "access-secret", "refresh-secret")
                .contains("****");
    }

    @Test
    @DisplayName("Copilot wire diagnostics mask device and session credentials")
    void toString_whenCopilotResponsesContainSecrets_masksCredentials() {
        var device = new CopilotAuthApi.DeviceAuthorizationResponse(
                "device-secret",
                "user-secret",
                "https://verify.example/secret",
                5,
                300
        );
        var session = new CopilotAuthApi.SessionTokenResponse("session-secret", 123L);

        assertThat(device.toString())
                .doesNotContain("device-secret", "user-secret", "https://verify.example/secret")
                .contains("****");
        assertThat(session.toString()).doesNotContain("session-secret").contains("****");
    }
}
