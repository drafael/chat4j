package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationLoadDispatchCoordinatorTest {

    @Test
    @DisplayName("Dispatch delegates load and routes success/failure callbacks through EDT dispatcher")
    void dispatch_whenCalled_routesCallbacksThroughEdtDispatcher() {
        var calls = new ArrayList<String>();
        var capturedConversationId = new AtomicReference<UUID>();
        var capturedListener = new AtomicReference<ConversationLoadCoordinator.Listener>();

        var subject = new ConversationLoadDispatchCoordinator(action -> {
            calls.add("edt");
            action.run();
        });

        UUID conversationId = UUID.randomUUID();

        long requestId = subject.dispatch(
                conversationId,
                (id, listener) -> {
                    capturedConversationId.set(id);
                    capturedListener.set(listener);
                    return 99L;
                },
                (loadedRequestId, loadedConversationId, records, conversation) -> {
                    calls.add("loaded:%d".formatted(loadedRequestId));
                    assertThat(loadedConversationId).isEqualTo(conversationId);
                    assertThat(records).hasSize(1);
                },
                (failedRequestId, failedConversationId, error) -> {
                    calls.add("failed:%d:%s".formatted(failedRequestId, error.getMessage()));
                    assertThat(failedConversationId).isEqualTo(conversationId);
                }
        );

        assertThat(requestId).isEqualTo(99L);
        assertThat(capturedConversationId.get()).isEqualTo(conversationId);
        assertThat(capturedListener.get()).isNotNull();

        capturedListener.get().onLoaded(
                1L,
                conversationId,
                List.of(new ConversationRepo.MessageRecord(UUID.randomUUID(), Message.user("hello"), LocalDateTime.now())),
                null
        );
        capturedListener.get().onFailure(2L, conversationId, new RuntimeException("boom"));

        assertThat(calls).containsExactly("edt", "loaded:1", "edt", "failed:2:boom");
    }

    @Test
    @DisplayName("Dispatch validates required arguments")
    void dispatch_whenArgumentMissing_throwsException() {
        var subject = new ConversationLoadDispatchCoordinator(action -> action.run());

        assertThatThrownBy(() -> subject.dispatch(
                null,
                (id, listener) -> 1L,
                (loadedRequestId, loadedConversationId, records, conversation) -> {
                },
                (failedRequestId, failedConversationId, error) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");

        assertThatThrownBy(() -> subject.dispatch(
                UUID.randomUUID(),
                null,
                (loadedRequestId, loadedConversationId, records, conversation) -> {
                },
                (failedRequestId, failedConversationId, error) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("asyncLoader");
    }

    @Test
    @DisplayName("Constructor validates EDT dispatcher")
    void constructor_whenDispatcherMissing_throwsException() {
        assertThatThrownBy(() -> new ConversationLoadDispatchCoordinator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("edtDispatcher");
    }
}
