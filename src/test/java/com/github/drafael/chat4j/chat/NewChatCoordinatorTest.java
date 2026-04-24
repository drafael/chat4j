package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewChatCoordinatorTest {

    @Test
    @DisplayName("Start executes new-chat reset/apply flow in expected order")
    void start_whenCalled_executesFlowInExpectedOrder() {
        var subject = new NewChatCoordinator();
        var calls = new ArrayList<String>();

        subject.start(
                () -> calls.add("save"),
                () -> calls.add("clear-current"),
                () -> calls.add("clear-pending"),
                () -> calls.add("clear-active"),
                () -> calls.add("clear-sidebar-selection"),
                () -> calls.add("clear-chat-view"),
                AssistantRenderMode.PREVIEW,
                (mode, userInitiated) -> calls.add("render-mode:%s:%s".formatted(mode, userInitiated)),
                () -> calls.add("focus")
        );

        assertThat(calls).containsExactly(
                "save",
                "clear-current",
                "clear-pending",
                "clear-active",
                "clear-sidebar-selection",
                "clear-chat-view",
                "render-mode:PREVIEW:true",
                "focus"
        );
    }

    @Test
    @DisplayName("Start validates required arguments")
    void start_whenArgumentMissing_throwsException() {
        var subject = new NewChatCoordinator();

        assertThatThrownBy(() -> subject.start(
                null,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                AssistantRenderMode.PREVIEW,
                (mode, userInitiated) -> {
                },
                () -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveCurrentConversation must not be null");

        assertThatThrownBy(() -> subject.start(
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                null,
                (mode, userInitiated) -> {
                },
                () -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("defaultAssistantRenderMode must not be null");
    }
}
