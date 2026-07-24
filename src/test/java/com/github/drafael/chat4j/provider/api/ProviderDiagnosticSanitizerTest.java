package com.github.drafael.chat4j.provider.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderDiagnosticSanitizerTest {

    @Test
    @DisplayName("Provider diagnostics retain only the configured endpoint origin")
    void safeOrigin_whenUrlContainsSensitiveComponents_removesThem() {
        String sanitized = ProviderDiagnosticSanitizer.safeOrigin(
                "https://user:secret@example.test:8443/v1/models?token=query-secret#fragment"
        );

        assertThat(sanitized)
                .isEqualTo("https://example.test:8443")
                .doesNotContain("user", "secret", "token", "/v1");
    }

    @Test
    @DisplayName("Malformed provider endpoints use a non-sensitive diagnostic label")
    void safeOrigin_whenUrlIsMalformed_returnsGenericLabel() {
        assertThat(ProviderDiagnosticSanitizer.safeOrigin("not a url?secret=value"))
                .isEqualTo("configured-endpoint");
    }
}
