package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.CurrentConversationSaveCoordinator;
import com.github.drafael.chat4j.persistence.shutdown.ShutdownSaveDispatchCoordinator;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MainFrameShutdownSaveActionFactoryTest {

    private final MainFrameShutdownSaveActionFactory subject = new MainFrameShutdownSaveActionFactory();

    @Test
    @DisplayName("Create captures shutdown state and persists the captured snapshot")
    void create_whenSnapshotCaptured_returnsSaveActionUsingCapturedValues() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Path projectRoot = Path.of("/tmp/project");
        List<Message> history = new ArrayList<>(List.of(Message.user("before mutation")));
        CurrentConversationSaveCoordinator saveCoordinator = mock(CurrentConversationSaveCoordinator.class);

        ShutdownSaveDispatchCoordinator.SaveAction saveAction = subject.create(new MainFrameShutdownSaveActionFactory.ShutdownSaveRequest(
                () -> conversationId,
                () -> history,
                () -> "OpenAI:gpt-4o",
                () -> ReasoningLevel.HIGH,
                () -> true,
                () -> projectRoot,
                saveCoordinator,
                error -> {
                    throw new AssertionError(error);
                }
        ));
        history.add(Message.user("after mutation"));

        saveAction.save();

        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(saveCoordinator).save(
                eq(conversationId),
                historyCaptor.capture(),
                eq("OpenAI:gpt-4o"),
                eq(ReasoningLevel.HIGH),
                eq(true),
                eq(projectRoot)
        );
        assertThat(historyCaptor.getValue())
                .hasSize(1)
                .extracting(Message::content)
                .containsExactly("before mutation");
    }

    @Test
    @DisplayName("Create reports snapshot failures and returns a no-op save action")
    void create_whenSnapshotCaptureFails_reportsFailureAndReturnsNoOpAction() throws Exception {
        CurrentConversationSaveCoordinator saveCoordinator = mock(CurrentConversationSaveCoordinator.class);
        var failure = new IllegalStateException("boom");
        var capturedFailure = new AtomicReference<Exception>();

        ShutdownSaveDispatchCoordinator.SaveAction saveAction = subject.create(new MainFrameShutdownSaveActionFactory.ShutdownSaveRequest(
                () -> UUID.randomUUID(),
                () -> {
                    throw failure;
                },
                () -> "OpenAI:gpt-4o",
                () -> ReasoningLevel.OFF,
                () -> false,
                () -> null,
                saveCoordinator,
                capturedFailure::set
        ));

        saveAction.save();

        assertThat(capturedFailure).hasValue(failure);
        verify(saveCoordinator, never()).save(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Snapshot string masks message content")
    void toString_whenSnapshotContainsMessages_masksMessageContent() {
        MainFrameShutdownSaveActionFactory.ShutdownSaveSnapshot snapshot = subject.capture(new MainFrameShutdownSaveActionFactory.ShutdownSaveRequest(
                () -> UUID.randomUUID(),
                () -> List.of(Message.user("secret chat text")),
                () -> "OpenAI:gpt-4o",
                () -> ReasoningLevel.OFF,
                () -> false,
                () -> null,
                mock(CurrentConversationSaveCoordinator.class),
                error -> {
                    throw new AssertionError(error);
                }
        ));

        assertThat(snapshot.toString())
                .contains("historySize=1")
                .doesNotContain("secret chat text");
    }
}
