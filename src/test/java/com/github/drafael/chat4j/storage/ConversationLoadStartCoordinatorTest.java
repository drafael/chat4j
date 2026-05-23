package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationLoadStartCoordinatorTest {

    @Test
    @DisplayName("Start saves current conversation and selects loaded conversation")
    void start_whenCalled_dispatchesLoad() {
        UUID conversationId = UUID.randomUUID();
        var calls = new ArrayList<String>();
        var currentConversationId = new AtomicReference<UUID>();
        var activeConversationId = new AtomicReference<UUID>();
        var subject = new ConversationLoadStartCoordinator(new ConversationLoadDispatchCoordinator(Runnable::run));

        long requestId = subject.start(
                conversationId,
                () -> calls.add("save"),
                currentConversationId::set,
                activeConversationId::set,
                (id, listener) -> 1L,
                (request, id, records, conversation) -> {},
                (request, id, error) -> {}
        );

        assertThat(requestId).isEqualTo(1L);
        assertThat(calls).containsExactly("save");
        assertThat(currentConversationId.get()).isEqualTo(conversationId);
        assertThat(activeConversationId.get()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("Start validates conversation id")
    void start_whenConversationIdMissing_throwsException() {
        var subject = new ConversationLoadStartCoordinator(new ConversationLoadDispatchCoordinator(Runnable::run));

        assertThatThrownBy(() -> subject.start(null, () -> {}, id -> {}, id -> {}, (id, listener) -> 1L, (request, id, records, conversation) -> {}, (request, id, error) -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");
    }
}
