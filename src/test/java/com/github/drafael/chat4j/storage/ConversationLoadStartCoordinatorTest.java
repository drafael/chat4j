package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationLoadStartCoordinatorTest {

    @Test
    @DisplayName("Start saves current conversation, updates load state, and delegates to dispatch coordinator")
    void start_whenCalled_savesUpdatesStateAndDelegatesToDispatchCoordinator() {
        var dispatchCoordinator = new RecordingConversationLoadDispatchCoordinator();
        var subject = new ConversationLoadStartCoordinator(dispatchCoordinator);
        var calls = new ArrayList<String>();
        var currentConversationId = new AtomicReference<UUID>();
        var activeConversationId = new AtomicReference<UUID>();
        UUID conversationId = UUID.randomUUID();

        long requestId = subject.start(
                conversationId,
                () -> calls.add("save"),
                value -> {
                    calls.add("set-current");
                    currentConversationId.set(value);
                },
                () -> calls.add("clear-pending"),
                value -> {
                    calls.add("set-active");
                    activeConversationId.set(value);
                },
                (id, listener) -> {
                    calls.add("async-loader");
                    return 1L;
                },
                (loadedRequestId, loadedConversationId, records, conversation) -> {
                },
                (failedRequestId, failedConversationId, error) -> {
                }
        );

        assertThat(requestId).isEqualTo(77L);
        assertThat(calls).containsExactly("save", "set-current", "clear-pending", "set-active");
        assertThat(currentConversationId.get()).isEqualTo(conversationId);
        assertThat(activeConversationId.get()).isEqualTo(conversationId);
        assertThat(dispatchCoordinator.lastConversationId).isEqualTo(conversationId);
        assertThat(dispatchCoordinator.lastAsyncLoader).isNotNull();
        assertThat(dispatchCoordinator.lastOnLoaded).isNotNull();
        assertThat(dispatchCoordinator.lastOnFailure).isNotNull();
    }

    @Test
    @DisplayName("Start validates required arguments")
    void start_whenArgumentMissing_throwsException() {
        var subject = new ConversationLoadStartCoordinator(new RecordingConversationLoadDispatchCoordinator());

        assertThatThrownBy(() -> subject.start(
                null,
                () -> {
                },
                value -> {
                },
                () -> {
                },
                value -> {
                },
                (id, listener) -> 1L,
                (loadedRequestId, loadedConversationId, records, conversation) -> {
                },
                (failedRequestId, failedConversationId, error) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationId");

        assertThatThrownBy(() -> subject.start(
                UUID.randomUUID(),
                null,
                value -> {
                },
                () -> {
                },
                value -> {
                },
                (id, listener) -> 1L,
                (loadedRequestId, loadedConversationId, records, conversation) -> {
                },
                (failedRequestId, failedConversationId, error) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveCurrentConversation");
    }

    @Test
    @DisplayName("Constructor validates dispatch coordinator")
    void constructor_whenDispatchCoordinatorMissing_throwsException() {
        assertThatThrownBy(() -> new ConversationLoadStartCoordinator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("conversationLoadDispatchCoordinator");
    }

    private static class RecordingConversationLoadDispatchCoordinator extends ConversationLoadDispatchCoordinator {

        private UUID lastConversationId;
        private AsyncLoader lastAsyncLoader;
        private LoadedHandler lastOnLoaded;
        private FailureHandler lastOnFailure;

        private RecordingConversationLoadDispatchCoordinator() {
            super(action -> action.run());
        }

        @Override
        public long dispatch(
                UUID conversationId,
                AsyncLoader asyncLoader,
                LoadedHandler onLoaded,
                FailureHandler onFailure
        ) {
            lastConversationId = conversationId;
            lastAsyncLoader = asyncLoader;
            lastOnLoaded = onLoaded;
            lastOnFailure = onFailure;
            return 77L;
        }
    }
}
