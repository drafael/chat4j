package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.settings.AgentModeSettings;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MainFrameConversationRuntimeSettingsCoordinatorTest {

    private final ConversationPersistenceCoordinator persistenceCoordinator = mock(ConversationPersistenceCoordinator.class);
    private final MainFrameConversationRuntimeSettingsCoordinator subject = new MainFrameConversationRuntimeSettingsCoordinator(
            persistenceCoordinator,
            mock(AgentModeSettings.class)
    );

    @Test
    void loadedRuntimeSettings_whenEnabledAgentRootIsInvalid_requestsCanonicalCorrection() {
        LocalDateTime now = LocalDateTime.now();
        var conversation = new ConversationRepository.ConversationRecord(
                UUID.randomUUID(),
                "Conversation",
                "OpenAI",
                "gpt-5",
                false,
                "OFF",
                true,
                "missing-agent-project",
                false,
                now,
                now
        );

        MainFrameConversationRuntimeSettingsCoordinator.LoadedRuntimeSettings settings =
                subject.loadedRuntimeSettings(conversation);

        assertThat(settings.agentModeEnabled()).isFalse();
        assertThat(settings.agentProjectRoot()).isNull();
        assertThat(settings.agentCorrectionRequired()).isTrue();
    }

    @Test
    void persistWebSearchSettings_whenConversationExists_submitsBooleanOnly() {
        UUID conversationId = UUID.randomUUID();
        when(persistenceCoordinator.submitWebSearchSettings(conversationId, true))
                .thenReturn(CompletableFuture.completedFuture(null));

        subject.persistWebSearchSettings(conversationId, true);

        verify(persistenceCoordinator).submitWebSearchSettings(conversationId, true);
    }
}
