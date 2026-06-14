package com.github.drafael.chat4j.persistence.conversation;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentConversationSaveUiApplyCoordinatorTest {

    private final CurrentConversationSaveUiApplyCoordinator subject = new CurrentConversationSaveUiApplyCoordinator();

    @Test
    @DisplayName("Apply updates conversation state and refreshes sidebar")
    void apply_whenSaved_updatesUiState() {
        UUID conversationId = UUID.randomUUID();
        var currentId = new AtomicReference<UUID>();
        var activeId = new AtomicReference<UUID>();
        var refreshed = new AtomicBoolean(false);
        var selectedId = new AtomicReference<UUID>();

        boolean applied = subject.apply(
                new CurrentConversationSaveCoordinator.SaveResult(true, conversationId, true),
                currentId::set,
                activeId::set,
                () -> refreshed.set(true),
                selectedId::set,
                true
        );

        assertThat(applied).isTrue();
        assertThat(currentId.get()).isEqualTo(conversationId);
        assertThat(activeId.get()).isEqualTo(conversationId);
        assertThat(refreshed).isTrue();
        assertThat(selectedId.get()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("Apply returns false when save skipped")
    void apply_whenSaveSkipped_returnsFalse() {
        boolean applied = subject.apply(
                new CurrentConversationSaveCoordinator.SaveResult(false, UUID.randomUUID(), false),
                value -> {},
                value -> {},
                () -> {},
                value -> {},
                true
        );

        assertThat(applied).isFalse();
    }

    @Test
    @DisplayName("Apply validates save result")
    void apply_whenSaveResultMissing_throwsException() {
        assertThatThrownBy(() -> subject.apply(null, value -> {}, value -> {}, () -> {}, value -> {}, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveResult");
    }
}
