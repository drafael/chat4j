package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppFontSizeStepResolverTest {

    private final AppFontSizeStepResolver subject = new AppFontSizeStepResolver();

    @Test
    @DisplayName("Resolve adjusted size returns next configured option when increasing")
    void resolveAdjustedSize_whenIncreasing_returnsNextOption() {
        int adjusted = subject.resolveAdjustedSize(new int[]{12, 14, 16, 18}, 14, true);

        assertThat(adjusted).isEqualTo(16);
    }

    @Test
    @DisplayName("Resolve adjusted size keeps current normalized size when increasing at maximum")
    void resolveAdjustedSize_whenIncreasingAtMaximum_returnsCurrentSize() {
        int adjusted = subject.resolveAdjustedSize(new int[]{12, 14, 16, 18}, 18, true);

        assertThat(adjusted).isEqualTo(18);
    }

    @Test
    @DisplayName("Resolve adjusted size returns previous configured option when decreasing")
    void resolveAdjustedSize_whenDecreasing_returnsPreviousOption() {
        int adjusted = subject.resolveAdjustedSize(new int[]{12, 14, 16, 18}, 16, false);

        assertThat(adjusted).isEqualTo(14);
    }

    @Test
    @DisplayName("Resolve adjusted size keeps current normalized size when decreasing at minimum")
    void resolveAdjustedSize_whenDecreasingAtMinimum_returnsCurrentSize() {
        int adjusted = subject.resolveAdjustedSize(new int[]{12, 14, 16, 18}, 12, false);

        assertThat(adjusted).isEqualTo(12);
    }

    @Test
    @DisplayName("Resolve adjusted size rejects empty options")
    void resolveAdjustedSize_whenOptionsAreEmpty_throwsException() {
        assertThatThrownBy(() -> subject.resolveAdjustedSize(new int[0], 14, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeOptions must not be empty");
    }
}
