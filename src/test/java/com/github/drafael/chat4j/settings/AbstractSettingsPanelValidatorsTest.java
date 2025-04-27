package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractSettingsPanelValidatorsTest {

    @Test
    @DisplayName("HTTP URL validator accepts valid https URL and trims whitespace")
    void httpUrl_whenValidHttpsUrl_returnsNormalizedValue() {
        var result = AbstractSettingsPanel.Validators.httpUrl("invalid")
                .validate("  https://api.example.com/v1  ");

        assertThat(result.valid()).isTrue();
        assertThat(result.normalizedValue()).isEqualTo("https://api.example.com/v1");
    }

    @Test
    @DisplayName("HTTP URL validator rejects unsupported scheme")
    void httpUrl_whenSchemeIsUnsupported_returnsInvalidResult() {
        var result = AbstractSettingsPanel.Validators.httpUrl("invalid")
                .validate("ftp://example.com");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("invalid");
    }

    @Test
    @DisplayName("One-of validator rejects value outside allowed set")
    void oneOf_whenValueIsNotAllowed_returnsInvalidResult() {
        var result = AbstractSettingsPanel.Validators.oneOf(Set.of("Enter", "Ctrl+Enter"), "invalid option")
                .validate("Shift+Enter");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("invalid option");
    }

    @Test
    @DisplayName("Trim-non-blank validator rejects blank values")
    void trimNonBlank_whenValueIsBlank_returnsInvalidResult() {
        var result = AbstractSettingsPanel.Validators.trimNonBlank("required")
                .validate("   ");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("required");
    }
}
