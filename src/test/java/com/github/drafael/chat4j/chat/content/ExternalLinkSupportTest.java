package com.github.drafael.chat4j.chat.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalLinkSupportTest {

    @Test
    @DisplayName("External link allowlist accepts http/https/mailto and rejects unsafe schemes")
    void isAllowedExternalLink_whenSchemeValidationRuns_allowsSafeSchemesOnly() {
        assertThat(ExternalLinkSupport.isAllowedExternalLink("https://example.com/path")).isTrue();
        assertThat(ExternalLinkSupport.isAllowedExternalLink("http://example.com")).isTrue();
        assertThat(ExternalLinkSupport.isAllowedExternalLink("mailto:security@example.com")).isTrue();

        assertThat(ExternalLinkSupport.isAllowedExternalLink("javascript:alert(1)")).isFalse();
        assertThat(ExternalLinkSupport.isAllowedExternalLink("file:///etc/passwd")).isFalse();
        assertThat(ExternalLinkSupport.isAllowedExternalLink("data:text/html;base64,PHNjcmlwdD4=")).isFalse();
    }
}
