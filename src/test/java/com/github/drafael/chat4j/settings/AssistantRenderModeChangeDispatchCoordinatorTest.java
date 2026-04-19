package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantRenderModeChangeDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply delegates to change apply and UI apply actions")
    void apply_whenCalled_delegatesToChangeAndUiActions() {
        UUID currentConversationId = UUID.randomUUID();
        var calls = new ArrayList<String>();
        var capturedApplyResult = new AtomicReference<AssistantRenderModeChangeCoordinator.ApplyResult>();

        var applyResult = new AssistantRenderModeChangeCoordinator.ApplyResult(true, AssistantRenderMode.PREVIEW);

        var subject = new AssistantRenderModeChangeDispatchCoordinator(
                (conversationId, mode, pendingUnsavedMode) -> {
                    calls.add("change-apply:%s:%s".formatted(conversationId, mode));
                    return applyResult;
                },
                (resolvedApplyResult, syncTogglePreviewMenuSelection, setPendingUnsavedConversationRenderMode) -> {
                    calls.add("ui-apply");
                    capturedApplyResult.set(resolvedApplyResult);
                    syncTogglePreviewMenuSelection.run();
                    setPendingUnsavedConversationRenderMode.accept(resolvedApplyResult.pendingUnsavedConversationRenderMode());
                    return true;
                }
        );

        var uiCalls = new ArrayList<String>();
        boolean handled = subject.apply(
                currentConversationId,
                AssistantRenderMode.MARKDOWN,
                AssistantRenderMode.PREVIEW,
                () -> uiCalls.add("sync-toggle"),
                mode -> uiCalls.add("set-pending:%s".formatted(mode))
        );

        assertThat(handled).isTrue();
        assertThat(calls).containsExactly(
                "change-apply:%s:MARKDOWN".formatted(currentConversationId),
                "ui-apply"
        );
        assertThat(capturedApplyResult.get()).isSameAs(applyResult);
        assertThat(uiCalls).containsExactly("sync-toggle", "set-pending:PREVIEW");
    }

    @Test
    @DisplayName("Apply validates required arguments and constructor dependencies")
    void apply_whenInvalidInput_throwsException() {
        var subject = new AssistantRenderModeChangeDispatchCoordinator(
                (conversationId, mode, pendingUnsavedMode) ->
                        new AssistantRenderModeChangeCoordinator.ApplyResult(true, pendingUnsavedMode),
                (applyResult, syncTogglePreviewMenuSelection, setPendingUnsavedConversationRenderMode) -> true
        );

        assertThatThrownBy(() -> subject.apply(
                null,
                null,
                null,
                () -> {
                },
                mode -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mode must not be null");

        assertThatThrownBy(() -> new AssistantRenderModeChangeDispatchCoordinator(
                (AssistantRenderModeChangeDispatchCoordinator.ChangeApplyAction) null,
                (applyResult, syncTogglePreviewMenuSelection, setPendingUnsavedConversationRenderMode) -> true
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("changeApplyAction must not be null");
    }
}
