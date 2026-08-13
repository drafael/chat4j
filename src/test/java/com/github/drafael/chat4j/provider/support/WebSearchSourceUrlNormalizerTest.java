package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchSourceUrlNormalizerTest {

    @Test
    @DisplayName("URL identity ignores host case, default ports, fragments, and a root slash")
    void normalize_whenUrlsDifferOnlyByIdentityRules_returnsSameKey() {
        String first = WebSearchSourceUrlNormalizer.normalize("HTTPS://Example.COM:443/#one").orElseThrow().key();
        String second = WebSearchSourceUrlNormalizer.normalize("https://example.com#two").orElseThrow().key();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("Non-root trailing slashes remain semantically distinct")
    void normalize_whenPathTrailingSlashDiffers_returnsDifferentKeys() {
        String first = WebSearchSourceUrlNormalizer.normalize("https://example.com/path").orElseThrow().key();
        String second = WebSearchSourceUrlNormalizer.normalize("https://example.com/path/").orElseThrow().key();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("Credential-bearing and non-HTTP URLs are rejected")
    void normalize_whenUrlIsUnsafe_returnsEmpty() {
        assertThat(WebSearchSourceUrlNormalizer.normalize("https://user@example.com/path")).isEmpty();
        assertThat(WebSearchSourceUrlNormalizer.normalize("file:///tmp/source")).isEmpty();
    }
}
