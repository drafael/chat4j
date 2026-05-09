package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantMessageCompletionFlowCoordinatorTest {

    @Test
    @DisplayName("Handle returns false and skips sidebar callbacks when assistant event is ignored")
    void handle_whenEventIgnored_returnsFalseAndSkipsSidebarCallbacks() throws Exception {
        var persistCalls = new AtomicInteger();
        var completionCoordinator = new AssistantMessageCompletionCoordinator(
                (conversationId, message) -> persistCalls.incrementAndGet()
        );
        var subject = new AssistantMessageCompletionFlowCoordinator(completionCoordinator);

        var refreshCalls = new AtomicInteger();
        var selectCalls = new AtomicInteger();

        boolean handled = subject.handle(
                null,
                Message.assistant("done"),
                UUID.randomUUID(),
                refreshCalls::incrementAndGet,
                conversationId -> selectCalls.incrementAndGet()
        );

        assertThat(handled).isFalse();
        assertThat(persistCalls.get()).isZero();
        assertThat(refreshCalls.get()).isZero();
        assertThat(selectCalls.get()).isZero();
    }

    @Test
    @DisplayName("Handle refreshes sidebar and selects current conversation when persistence result requests selection")
    void handle_whenSelectionRequested_refreshesAndSelectsConversation() throws Exception {
        var completionCoordinator = new AssistantMessageCompletionCoordinator((conversationId, message) -> {
        });
        var subject = new AssistantMessageCompletionFlowCoordinator(completionCoordinator);

        var refreshCalls = new AtomicInteger();
        var selectedConversationId = new AtomicReference<UUID>();
        UUID conversationId = UUID.randomUUID();

        boolean handled = subject.handle(
                conversationId,
                Message.assistant("done"),
                conversationId,
                refreshCalls::incrementAndGet,
                selectedConversationId::set
        );

        assertThat(handled).isTrue();
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(selectedConversationId.get()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("Handle refreshes sidebar and skips selection when persistence result has no conversation to select")
    void handle_whenSelectionNotRequested_refreshesAndSkipsConversationSelection() throws Exception {
        var completionCoordinator = new AssistantMessageCompletionCoordinator((conversationId, message) -> {
        });
        var subject = new AssistantMessageCompletionFlowCoordinator(completionCoordinator);

        var refreshCalls = new AtomicInteger();
        var selectCalls = new AtomicInteger();

        boolean handled = subject.handle(
                UUID.randomUUID(),
                Message.assistant("done"),
                UUID.randomUUID(),
                refreshCalls::incrementAndGet,
                conversationId -> selectCalls.incrementAndGet()
        );

        assertThat(handled).isTrue();
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(selectCalls.get()).isZero();
    }

    @Test
    @DisplayName("Handle validates required callbacks")
    void handle_whenCallbackMissing_throwsException() {
        var completionCoordinator = new AssistantMessageCompletionCoordinator((conversationId, message) -> {
        });
        var subject = new AssistantMessageCompletionFlowCoordinator(completionCoordinator);

        assertThatThrownBy(() -> subject.handle(
                UUID.randomUUID(),
                Message.assistant("done"),
                UUID.randomUUID(),
                null,
                conversationId -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshSidebar");

        assertThatThrownBy(() -> subject.handle(
                UUID.randomUUID(),
                Message.assistant("done"),
                UUID.randomUUID(),
                () -> {
                },
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("selectConversation");
    }

    @Test
    @DisplayName("Constructor validates assistant message completion coordinator")
    void constructor_whenCoordinatorMissing_throwsException() {
        assertThatThrownBy(() -> new AssistantMessageCompletionFlowCoordinator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("assistantMessageCompletionCoordinator");
    }
}
