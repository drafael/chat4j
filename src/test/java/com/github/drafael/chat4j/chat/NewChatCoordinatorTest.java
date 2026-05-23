package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewChatCoordinatorTest {

    @Test
    @DisplayName("Start saves current conversation and clears chat state without changing render mode")
    void start_whenCalled_runsStepsInOrder() {
        var subject = new NewChatCoordinator();
        var calls = new ArrayList<String>();

        subject.start(
                () -> calls.add("save"),
                () -> calls.add("clear-current"),
                () -> calls.add("clear-active"),
                () -> calls.add("clear-selection"),
                () -> calls.add("clear-view"),
                () -> calls.add("reset-runtime"),
                () -> calls.add("focus")
        );

        assertThat(calls).containsExactly(
                "save",
                "clear-current",
                "clear-active",
                "clear-selection",
                "clear-view",
                "reset-runtime",
                "focus"
        );
    }

    @Test
    @DisplayName("Start validates required arguments")
    void start_whenRequiredArgumentMissing_throwsException() {
        var subject = new NewChatCoordinator();

        assertThatThrownBy(() -> subject.start(null, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveCurrentConversation");
    }
}
