package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentConversationSaveUiApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates MainFrame state/sidebar when conversation was saved")
    void apply_whenSaved_updatesStateAndSidebar() {
        var subject = new CurrentConversationSaveUiApplyCoordinator();
        UUID conversationId = UUID.randomUUID();
        var saveResult = new CurrentConversationSaveCoordinator.SaveResult(
                true,
                conversationId,
                AssistantRenderMode.PREVIEW,
                true
        );

        var calls = new ArrayList<String>();
        var currentConversationId = new AtomicReference<UUID>();
        var pendingRenderMode = new AtomicReference<AssistantRenderMode>();
        var activeConversationId = new AtomicReference<UUID>();

        boolean applied = subject.apply(
                saveResult,
                value -> {
                    calls.add("set-current");
                    currentConversationId.set(value);
                },
                value -> {
                    calls.add("set-pending");
                    pendingRenderMode.set(value);
                },
                value -> {
                    calls.add("set-active");
                    activeConversationId.set(value);
                },
                () -> calls.add("sidebar-refresh"),
                value -> calls.add("sidebar-select:%s".formatted(value))
        );

        assertThat(applied).isTrue();
        assertThat(currentConversationId.get()).isEqualTo(conversationId);
        assertThat(pendingRenderMode.get()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(activeConversationId.get()).isEqualTo(conversationId);
        assertThat(calls).containsExactly(
                "set-current",
                "set-pending",
                "set-active",
                "sidebar-refresh",
                "sidebar-select:%s".formatted(conversationId)
        );
    }

    @Test
    @DisplayName("Apply does nothing when save result indicates conversation was not saved")
    void apply_whenNotSaved_doesNothing() {
        var subject = new CurrentConversationSaveUiApplyCoordinator();
        var saveResult = new CurrentConversationSaveCoordinator.SaveResult(
                false,
                UUID.randomUUID(),
                null,
                false
        );

        var calls = new ArrayList<String>();

        boolean applied = subject.apply(
                saveResult,
                value -> calls.add("set-current"),
                value -> calls.add("set-pending"),
                value -> calls.add("set-active"),
                () -> calls.add("sidebar-refresh"),
                value -> calls.add("sidebar-select")
        );

        assertThat(applied).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new CurrentConversationSaveUiApplyCoordinator();
        var saveResult = new CurrentConversationSaveCoordinator.SaveResult(
                true,
                UUID.randomUUID(),
                null,
                false
        );

        assertThatThrownBy(() -> subject.apply(
                null,
                value -> {
                },
                value -> {
                },
                value -> {
                },
                () -> {
                },
                value -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("saveResult must not be null");

        assertThatThrownBy(() -> subject.apply(
                saveResult,
                null,
                value -> {
                },
                value -> {
                },
                () -> {
                },
                value -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setCurrentConversationId must not be null");
    }
}
