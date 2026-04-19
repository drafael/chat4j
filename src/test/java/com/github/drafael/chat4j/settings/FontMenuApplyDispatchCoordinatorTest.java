package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FontMenuApplyDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply returns true and does not present error when apply action succeeds")
    void apply_whenSuccess_returnsTrueWithoutError() {
        var subject = new FontMenuApplyDispatchCoordinator();
        var messages = new ArrayList<String>();

        boolean applied = subject.apply(
                () -> new FontMenuApplyCoordinator.ApplyResult(true, null),
                "Failed to apply UI font: ",
                messages::add
        );

        assertThat(applied).isTrue();
        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("Apply returns false and presents prefixed message when apply action fails")
    void apply_whenFailure_returnsFalseAndPresentsError() {
        var subject = new FontMenuApplyDispatchCoordinator();
        var messages = new ArrayList<String>();

        boolean applied = subject.apply(
                () -> new FontMenuApplyCoordinator.ApplyResult(false, "boom"),
                "Failed to apply code font: ",
                messages::add
        );

        assertThat(applied).isFalse();
        assertThat(messages).containsExactly("Failed to apply code font: boom");
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new FontMenuApplyDispatchCoordinator();

        assertThatThrownBy(() -> subject.apply(
                null,
                "Failed to apply font: ",
                message -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applyAction must not be null");

        assertThatThrownBy(() -> subject.apply(
                () -> new FontMenuApplyCoordinator.ApplyResult(true, null),
                "  ",
                message -> {
                }
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorPrefix must not be blank");
    }
}
