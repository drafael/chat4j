package com.github.drafael.chat4j.provider.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningLevelTest {

    @ParameterizedTest
    @CsvSource({
            "extra_high, EXTRA_HIGH",
            "xhigh, EXTRA_HIGH",
            "max, MAX",
            "maximum, MAX",
            "ultra, ULTRA"
    })
    @DisplayName("Persisted reasoning values parse into their supported levels")
    void fromSettingValue_whenValueIsSupported_returnsReasoningLevel(String value, ReasoningLevel expected) {
        assertThat(ReasoningLevel.fromSettingValue(value, ReasoningLevel.OFF)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "EXTRA_HIGH, extra_high",
            "MAX, max",
            "ULTRA, ultra"
    })
    @DisplayName("Reasoning levels serialize to stable setting values")
    void toSettingValue_whenLevelIsPersisted_returnsStableValue(ReasoningLevel level, String expected) {
        assertThat(level.toSettingValue()).isEqualTo(expected);
    }
}
