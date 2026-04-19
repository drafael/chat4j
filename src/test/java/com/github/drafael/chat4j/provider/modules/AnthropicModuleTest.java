package com.github.drafael.chat4j.provider.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicModuleTest {

    private static final AnthropicModule subject = new AnthropicModule(
            "Anthropic",
            "ANTHROPIC_API_KEY",
            "https://api.anthropic.com"
    );

    @Test
    @DisplayName("Base URL with v1 suffix is normalized to provider root URL")
    void descriptor_whenBaseUrlEndsWithV1_returnsRootUrlWithoutV1Suffix() {
        String normalized = subject.descriptor().normalizeBaseUrl("https://api.anthropic.com/v1");

        assertThat(normalized).isEqualTo("https://api.anthropic.com");
    }

    @Test
    @DisplayName("Blank base URL falls back to anthropic default root URL")
    void descriptor_whenBaseUrlIsBlank_returnsDefaultRootUrl() {
        String normalized = subject.descriptor().normalizeBaseUrl("   ");

        assertThat(normalized).isEqualTo("https://api.anthropic.com");
    }

    @Test
    @DisplayName("Base URL with trailing slash is normalized by removing trailing slash")
    void descriptor_whenBaseUrlEndsWithSlash_returnsUrlWithoutTrailingSlash() {
        String normalized = subject.descriptor().normalizeBaseUrl("https://api.anthropic.com/");

        assertThat(normalized).isEqualTo("https://api.anthropic.com");
    }
}
