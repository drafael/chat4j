package com.github.drafael.chat4j.provider.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReasoningOptionsTest {

    @Test
    @DisplayName("A supported current selection takes precedence over the recommendation")
    void select_supportedCurrent_keepsSelection() {
        var subject = ReasoningOptions.of(ReasoningLevel.standardLevels(), ReasoningLevel.HIGH);

        assertThat(subject.select(ReasoningLevel.LOW)).isEqualTo(ReasoningLevel.LOW);
        assertThat(subject.select(ReasoningLevel.OFF)).isEqualTo(ReasoningLevel.OFF);
    }

    @ParameterizedTest
    @CsvSource({"HIGH, HIGH", "ULTRA, MEDIUM"})
    @DisplayName("Unsupported selections use a supported recommendation before the Medium fallback")
    void select_unsupportedCurrent_resolvesRecommendation(ReasoningLevel recommendation, ReasoningLevel expected) {
        var subject = ReasoningOptions.of(ReasoningLevel.standardLevels(), recommendation);

        assertThat(subject.select(ReasoningLevel.ULTRA)).isEqualTo(expected);
        assertThat(subject.defaultLevel()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Sparse choices fall back to the lowest enabled effort rather than Off")
    void select_withoutMedium_selectsLowestEnabled() {
        var subject = ReasoningOptions.of(List.of(ReasoningLevel.MAX, ReasoningLevel.OFF, ReasoningLevel.HIGH));

        assertThat(subject.select(ReasoningLevel.MEDIUM)).isEqualTo(ReasoningLevel.HIGH);
    }

    @Test
    @DisplayName("Unavailable reasoning resolves to Off")
    void select_unavailable_returnsOff() {
        var subject = ReasoningOptions.UNAVAILABLE;

        assertThat(subject.select(ReasoningLevel.HIGH)).isEqualTo(ReasoningLevel.OFF);
    }

    @Test
    @DisplayName("A fixed enabled configuration stays enabled")
    void select_fixedEffort_returnsRequiredLevel() {
        var subject = ReasoningOptions.of(List.of(ReasoningLevel.HIGH));

        assertThat(subject.select(ReasoningLevel.OFF)).isEqualTo(ReasoningLevel.HIGH);
    }

    @Test
    @DisplayName("Options are copied, ordered, deduplicated, and immutable")
    void constructor_mutableUnsortedLevels_preservesImmutableChoices() {
        var levels = new ArrayList<>(List.of(ReasoningLevel.HIGH, ReasoningLevel.LOW, ReasoningLevel.HIGH));
        var subject = new ReasoningOptions(levels, ReasoningLevel.LOW);
        levels.clear();

        assertThat(subject.levels()).containsExactly(ReasoningLevel.LOW, ReasoningLevel.HIGH);
        assertThatThrownBy(() -> subject.levels().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Empty choices and an unsupported resolved default are invalid")
    void constructor_invalidOptions_rejectsConfiguration() {
        assertThatThrownBy(() -> ReasoningOptions.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReasoningOptions(List.of(ReasoningLevel.LOW), ReasoningLevel.HIGH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
