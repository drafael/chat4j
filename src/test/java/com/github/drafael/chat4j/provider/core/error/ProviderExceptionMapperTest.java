package com.github.drafael.chat4j.provider.core.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource({
            "400, InvalidRequestException",
            "401, AuthenticationException",
            "402, ProviderException",
            "404, InvalidRequestException",
            "429, RateLimitException",
            "500, ProviderUnavailableException",
            "502, ProviderUnavailableException",
            "503, ProviderUnavailableException",
            "504, ProviderUnavailableException",
            "524, ProviderUnavailableException",
            "529, ProviderUnavailableException"
    })
    @DisplayName("Hosted Together statuses map from their authoritative HTTP values")
    void mapHttpStatus_whenHostedTogetherStatusIsKnown_returnsExpectedDomainType(
            int statusCode,
            String expectedType
    ) {
        Exception mapped = ProviderExceptionMapper.mapHttpStatus(
                "Together",
                "https://api.together.ai/v1/",
                statusCode,
                "provider_type",
                "provider_code",
                "actionable message",
                "secret"
        );

        assertThat(mapped.getClass().getSimpleName()).isEqualTo(expectedType);
        assertThat(mapped.getMessage()).contains("HTTP %d".formatted(statusCode), "actionable message");
    }

    @ParameterizedTest
    @CsvSource({
            "context length exceeded, InvalidRequestException",
            "context_length, InvalidRequestException",
            "context window too large, InvalidRequestException",
            "input token count is too large, InvalidRequestException",
            "input tokens exceed the token limit, InvalidRequestException",
            "permission denied, AuthenticationException",
            "max_tokens is invalid, AuthenticationException",
            "token is invalid, AuthenticationException"
    })
    @DisplayName("Hosted Together 403 classification requires narrow context-overflow evidence")
    void mapHttpStatus_whenHostedTogetherReturns403_classifiesOnlyContextOverflow(
            String message,
            String expectedType
    ) {
        Exception mapped = ProviderExceptionMapper.mapHttpStatus(
                "Together",
                "https://api.together.ai/v1",
                403,
                "",
                "",
                message,
                "secret"
        );

        assertThat(mapped.getClass().getSimpleName()).isEqualTo(expectedType);
    }

    @Test
    @DisplayName("Hosted Together 403 classification inspects error type and code independently")
    void mapHttpStatus_whenContextEvidenceIsStructured_classifiesTypeAndCode() {
        Exception typeEvidence = ProviderExceptionMapper.mapHttpStatus(
                "Together",
                "https://api.together.ai/v1",
                403,
                "context_length_exceeded",
                "",
                "permission denied",
                "secret"
        );
        Exception codeEvidence = ProviderExceptionMapper.mapHttpStatus(
                "Together",
                "https://api.together.ai/v1",
                403,
                "",
                "input_token_limit_exceeded",
                "permission denied",
                "secret"
        );

        assertThat(typeEvidence).isInstanceOf(InvalidRequestException.class);
        assertThat(codeEvidence).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("Custom Together and other provider 403 responses retain generic authentication semantics")
    void mapHttpStatus_whenEndpointIsNotHostedTogether_keepsGeneric403Mapping() {
        Exception customTogether = ProviderExceptionMapper.mapHttpStatus(
                "Together",
                "https://proxy.example/v1",
                403,
                "context_length",
                "",
                "context length exceeded",
                "secret"
        );
        Exception otherProvider = ProviderExceptionMapper.mapHttpStatus(
                "OpenAI",
                "https://api.openai.com/v1",
                403,
                "context_length",
                "",
                "context length exceeded",
                "secret"
        );

        assertThat(customTogether).isInstanceOf(AuthenticationException.class);
        assertThat(otherProvider).isInstanceOf(AuthenticationException.class);
    }

    @Test
    @DisplayName("Structured provider fields are redacted before bounding and classification")
    void mapHttpStatus_whenStructuredFieldsContainCredential_redactsBeforeBounding() {
        String key = "key-" + "x".repeat(500);
        String prefix = "a".repeat(500);

        Exception mapped = ProviderExceptionMapper.mapHttpStatus(
                "Together",
                "https://api.together.ai/v1",
                403,
                "%s%s".formatted(prefix, key),
                key,
                "%s context length exceeded".formatted(key),
                key
        );

        assertThat(mapped)
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageNotContaining(key)
                .hasMessageNotContaining("key-xxx")
                .hasMessageContaining("[REDACTED]");
        assertThat(mapped.getMessage().length()).isLessThan(1_700);
    }
}
