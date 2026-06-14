package com.github.drafael.chat4j.persistence.conversation;

import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationLoadFailureCoordinatorTest {

    @Test
    @DisplayName("Handle presents error when failure guard allows handling")
    void handle_whenGuardAllows_presentsErrorMessage() {
        var subject = new ConversationLoadFailureCoordinator();
        var messages = new ArrayList<String>();

        boolean handled = subject.handle(
                11L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new RuntimeException("boom"),
                (requestId, currentConversationId, failedConversationId) -> true,
                messages::add
        );

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly("Failed to load conversation: boom");
    }

    @Test
    @DisplayName("Handle skips presenting error when failure guard rejects handling")
    void handle_whenGuardRejects_skipsPresentation() {
        var subject = new ConversationLoadFailureCoordinator();
        var messages = new ArrayList<String>();

        boolean handled = subject.handle(
                12L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new RuntimeException("ignored"),
                (requestId, currentConversationId, failedConversationId) -> false,
                messages::add
        );

        assertThat(handled).isFalse();
        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("Handle validates required arguments")
    void handle_whenArgumentMissing_throwsException() {
        var subject = new ConversationLoadFailureCoordinator();

        assertThatThrownBy(() -> subject.handle(
                1L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                (requestId, currentConversationId, failedConversationId) -> true,
                message -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("error");

        assertThatThrownBy(() -> subject.handle(
                1L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new RuntimeException("boom"),
                null,
                message -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("failureGuard");
    }
}
