package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CredentialTokenIdsTest {

    @Test
    @DisplayName("Together API keys are accepted as supported provider credentials")
    void validateSupportedTokenId_whenTogetherKeyProvided_acceptsTokenId() {
        assertThat(CredentialTokenIds.supported("TOGETHER_API_KEY")).isTrue();
        assertThat(CredentialTokenIds.supportedProviderEnvVars()).contains("TOGETHER_API_KEY");
        assertThatCode(() -> CredentialTokenIds.validateSupportedTokenId("TOGETHER_API_KEY"))
                .doesNotThrowAnyException();
    }
}
