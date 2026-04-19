package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderAvailabilityLabelFormatterTest {

    private final ProviderAvailabilityLabelFormatter subject = new ProviderAvailabilityLabelFormatter();

    @Test
    @DisplayName("Format returns provider name unchanged when enabled")
    void format_whenEnabled_returnsProviderName() {
        assertThat(subject.format("Ollama", true)).isEqualTo("Ollama");
    }

    @Test
    @DisplayName("Format appends offline suffix when disabled")
    void format_whenDisabled_appendsOfflineSuffix() {
        assertThat(subject.format("Ollama", false)).isEqualTo("Ollama (offline)");
    }

    @Test
    @DisplayName("Format rejects blank provider names")
    void format_whenProviderNameBlank_throwsException() {
        assertThatThrownBy(() -> subject.format("   ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName must not be blank");
    }
}
