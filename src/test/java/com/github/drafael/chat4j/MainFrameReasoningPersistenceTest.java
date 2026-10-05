package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MainFrameReasoningPersistenceTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Only applying a loaded runtime suppresses reasoning writes, not waiting for another conversation to load")
    void onReasoningLevelChanged_loadPending_persistsUnlessRuntimeApplicationIsStaged(boolean staged) throws Exception {
        SwingUtilities.invokeAndWait(() -> assertThatCode(() -> {
            MainFrame subject = mock(MainFrame.class, CALLS_REAL_METHODS);
            ChatPanel panel = mock(ChatPanel.class);
            var settings = mock(MainFrameConversationRuntimeSettingsCoordinator.class);
            var state = new MainFrameConversationState();
            UUID outgoingConversationId = UUID.randomUUID();
            state.setCurrentConversationId(outgoingConversationId);
            when(panel.isConversationRuntimeLoadStaged()).thenReturn(staged);
            when(settings.persistReasoningLevel(outgoingConversationId, ReasoningLevel.MEDIUM))
                    .thenReturn(CompletableFuture.completedFuture(null));
            setField(subject, "chatPanel", panel);
            setField(subject, "conversationRuntimeSettingsCoordinator", settings);
            setField(subject, "conversationState", state);
            setField(subject, "pendingLoadConversationId", UUID.randomUUID());
            Method change = MainFrame.class.getDeclaredMethod("onReasoningLevelChanged", ReasoningLevel.class);
            change.setAccessible(true);

            change.invoke(subject, ReasoningLevel.MEDIUM);

            verify(settings, times(staged ? 0 : 1)).persistReasoningLevel(outgoingConversationId, ReasoningLevel.MEDIUM);
        }).doesNotThrowAnyException());
    }

    private void setField(MainFrame subject, String name, Object value) throws Exception {
        Field field = MainFrame.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(subject, value);
    }
}
