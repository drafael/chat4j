package com.github.drafael.chat4j.provider.core.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderExceptionMapperTest {

    @Test
    @DisplayName("Credential-bearing provider failures are redacted and detached from their cause")
    void map_whenFailureContainsCredential_redactsAndDropsCause() {
        var failure = new IllegalStateException("401 rejected secret-key");

        Exception mapped = ProviderExceptionMapper.map(failure, "secret-key");

        assertThat(mapped)
                .isInstanceOf(AuthenticationException.class)
                .hasMessage("401 rejected [REDACTED]")
                .hasNoCause();
    }

    @Test
    @DisplayName("Credential-bearing nested causes are not retained")
    void map_whenCredentialExistsOnlyInCause_dropsCauseChain() {
        var failure = new IllegalStateException(
                "Provider request failed",
                new IllegalArgumentException("nested secret-key")
        );

        Exception mapped = ProviderExceptionMapper.map(failure, "secret-key");

        assertThat(mapped)
                .hasMessage("Provider request failed")
                .hasNoCause();
    }

    @Test
    @DisplayName("Failures without credentials retain their diagnostic cause")
    void map_whenCredentialIsBlank_preservesCause() {
        var failure = new IllegalStateException("Provider request failed");

        Exception mapped = ProviderExceptionMapper.map(failure, "");

        assertThat(mapped)
                .isInstanceOf(ProviderException.class)
                .hasCause(failure);
    }
}
