package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.settings.AgentModeSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class MainFrameConversationRuntimeSettingsCoordinator {

    private final ConversationPersistenceCoordinator persistenceCoordinator;
    private final AgentModeSettings agentModeSettings;

    public MainFrameConversationRuntimeSettingsCoordinator(
            @NonNull ConversationPersistenceCoordinator persistenceCoordinator,
            @NonNull AgentModeSettings agentModeSettings
    ) {
        this.persistenceCoordinator = persistenceCoordinator;
        this.agentModeSettings = agentModeSettings;
    }

    public void applyAgentModeSettings(@NonNull Consumer<String> setAgentSystemPromptAppend) {
        try {
            setAgentSystemPromptAppend.accept(agentModeSettings.resolveSystemPromptAppend());
        } catch (Exception e) {
            log.debug("Failed to resolve Agent Mode settings", e);
        }
    }

    public CompletionStage<Void> persistReasoningLevel(UUID conversationId, ReasoningLevel reasoningLevel) {
        return conversationId == null
                ? CompletableFuture.completedFuture(null)
                : persistenceCoordinator.submitReasoningLevel(conversationId, reasoningLevel);
    }

    public CompletionStage<Void> persistWebSearchSettings(UUID conversationId, boolean enabled) {
        return conversationId == null
                ? CompletableFuture.completedFuture(null)
                : persistenceCoordinator.submitWebSearchSettings(conversationId, enabled);
    }

    public CompletionStage<Void> persistAgentSettings(
            UUID conversationId,
            boolean agentModeRequested,
            Path agentProjectRoot
    ) {
        return conversationId == null
                ? CompletableFuture.completedFuture(null)
                : persistenceCoordinator.submitAgentSettings(conversationId, agentModeRequested, agentProjectRoot);
    }

    public LoadedRuntimeSettings loadedRuntimeSettings(ConversationRepository.ConversationRecord conversation) {
        Path projectRoot = null;
        boolean agentEnabled = false;
        if (conversation != null && StringUtils.isNotBlank(conversation.agentProjectRoot())) {
            try {
                Path normalized = Path.of(conversation.agentProjectRoot()).toAbsolutePath().normalize();
                if (Files.isDirectory(normalized)) {
                    projectRoot = normalized;
                    agentEnabled = conversation.agentModeEnabled();
                }
            } catch (Exception e) {
                log.debug("Ignoring invalid persisted Agent project root: {}", conversation.agentProjectRoot(), e);
            }
        }
        boolean agentCorrectionRequired = conversation != null
                && (conversation.agentModeEnabled() != agentEnabled
                || StringUtils.isNotBlank(conversation.agentProjectRoot()) && projectRoot == null);
        return new LoadedRuntimeSettings(
                conversation != null && conversation.webSearchEnabled(),
                projectRoot,
                agentEnabled,
                agentCorrectionRequired
        );
    }

    public record LoadedRuntimeSettings(
            boolean webSearchEnabled,
            Path agentProjectRoot,
            boolean agentModeEnabled,
            boolean agentCorrectionRequired
    ) {
    }
}
